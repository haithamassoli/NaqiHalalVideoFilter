package com.haithamassoli.naqi.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.haithamassoli.naqi.desktop.pipeline.JobOptions
import com.haithamassoli.naqi.desktop.pipeline.Stage
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File

/** `Pipeline::run`'s shape. A function type, not an interface: the UI test hands App() a fake one. */
typealias RunJob = suspend (JobOptions, onProgress: (Stage, Int) -> Unit) -> Unit

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** One row of the activity list. Fields are snapshot state, written from the worker thread. */
class JobItem(val options: JobOptions) {
    var state by mutableStateOf(JobState.QUEUED)
        internal set
    var stage by mutableStateOf<Stage?>(null)
        internal set
    var percent by mutableIntStateOf(0)
        internal set
    var etaMs by mutableLongStateOf(0L)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    internal var job: Job? = null

    val finished get() = state == JobState.DONE || state == JobState.FAILED || state == JobState.CANCELLED

    fun cancel() {
        job?.cancel()
    }
}

/**
 * Jobs run one at a time, in submission order: every stage already saturates the CPU, so two at once would
 * only make both slower. Each job is its own coroutine waiting on one fair [Mutex], which makes cancel the
 * same call whether the job is running or still waiting its turn.
 */
class JobQueue(private val scope: CoroutineScope, private val runJob: RunJob) {
    /** Newest first, as the list shows them. */
    val items = mutableStateListOf<JobItem>()
    private val turn = Mutex()

    fun submit(options: JobOptions): JobItem {
        val item = JobItem(options)
        items.add(0, item)
        // UNDISPATCHED: the coroutine joins the Mutex's queue right here on the submitting thread, so jobs run
        // in submission order. Launched normally, two jobs submitted back to back race to the lock on
        // Default's threads and can swap. The yield() then moves the work off the submitter's (UI) thread.
        item.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                turn.withLock {
                    yield()
                    item.state = JobState.RUNNING
                    val started = System.nanoTime()
                    runJob(options) { stage, p ->
                        item.stage = stage
                        item.percent = overallPercent(options.ops, stage, p)
                        item.etaMs = etaMs((System.nanoTime() - started) / 1_000_000, item.percent)
                    }
                }
                item.percent = 100
                item.state = JobState.DONE
            } catch (e: CancellationException) {
                item.state = JobState.CANCELLED
                throw e
            } catch (e: Throwable) {
                item.error = e.message ?: e.javaClass.simpleName
                item.state = JobState.FAILED
            }
        }
        return item
    }

    /**
     * For the window's close button: cancel everything and give each pipeline a moment to delete its temp
     * file (Renderer cleans up on cancel), instead of the JVM exiting under it and leaving a partial file.
     */
    fun cancelAllAndWait(timeoutMs: Long = 5_000) {
        val jobs = items.mapNotNull { it.job }
        jobs.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(timeoutMs) { jobs.joinAll() } }
    }
}

/** The stages Pipeline.run goes through for [ops], in its order (SEAMS.md, pipeline). */
internal fun stagesFor(ops: FilterOps): List<Stage> = buildList {
    if (ops.censorFaces || ops.censorNsfw) add(Stage.ANALYZE)
    if (ops.removeMusic) add(Stage.MUSIC)
    add(Stage.RENDER)
}

/**
 * How much of the bar each stage owns, after Android's measured `Eta.Bands`: htdemucs is ~80 % of a combined
 * job, analyze and render about equal. Render without any censoring is a stream copy plus a mux, nearly free.
 *
 * ponytail: phone shares on a desktop CPU. Re-measure on a Windows machine if the ETA visibly lurches when
 * music removal starts.
 */
private fun weight(stage: Stage, ops: FilterOps): Int = when (stage) {
    Stage.ANALYZE -> 9
    Stage.MUSIC -> 80
    Stage.RENDER -> if (ops.censorFaces || ops.censorNsfw) 9 else 2
}

/** One 0..100 bar over the whole job, from the pipeline's per-stage 0..100. */
internal fun overallPercent(ops: FilterOps, stage: Stage, stagePercent: Int): Int {
    val stages = stagesFor(ops).let { if (stage in it) it else it + stage }
    val total = stages.sumOf { weight(it, ops) }
    val before = stages.takeWhile { it != stage }.sumOf { weight(it, ops) }
    val done = before + weight(stage, ops) * stagePercent.coerceIn(0, 100) / 100.0
    return (done * 100 / total).toInt().coerceIn(0, 100)
}

/** Straight-line remaining time; 0 ("say nothing") until there is enough progress for it to mean anything. */
internal fun etaMs(elapsedMs: Long, percent: Int): Long =
    if (percent < 3 || percent >= 100 || elapsedMs < 3_000) 0L else elapsedMs * (100 - percent) / percent

/**
 * `<name>-naqi.mp4` next to the input, numbered past any file that exists or that an earlier queued job will
 * write. Never the input itself: the suffix guarantees that.
 */
internal fun defaultOutput(input: File, taken: Collection<File> = emptyList()): File {
    val dir = input.absoluteFile.parentFile
    val base = input.nameWithoutExtension
    val takenPaths = taken.map { it.absolutePath }.toSet()
    return generateSequence(1) { it + 1 }
        .map { n -> File(dir, if (n == 1) "$base-naqi.mp4" else "$base-naqi-$n.mp4") }
        .first { !it.exists() && it.absolutePath !in takenPaths }
}

/**
 * Same file on disk, through symlinks and `..` where the OS can resolve them. Case-blind on Windows: a
 * not-yet-existing output keeps the case it was typed in, while the existing input canonicalizes to its real
 * case, so "CLIP.mp4" and "clip.mp4" would otherwise look like two files.
 */
internal fun sameFile(a: File, b: File): Boolean {
    fun path(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
    return path(a).equals(path(b), ignoreCase = isWindows)
}

internal val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
