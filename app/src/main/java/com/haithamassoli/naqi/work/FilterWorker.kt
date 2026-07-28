package com.haithamassoli.naqi.work

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.analysis.FaceTracker
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.analysis.NsfwGate
import com.haithamassoli.naqi.audio.AudioPipeline
import com.haithamassoli.naqi.audio.ConcatPart
import com.haithamassoli.naqi.audio.Remux
import com.haithamassoli.naqi.audio.TrackSource
import com.haithamassoli.naqi.audio.canCopyAudio
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.ml.Infer
import com.haithamassoli.naqi.render.RenderPipeline
import com.haithamassoli.naqi.render.RenderSegment
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Filtering job. Up to two passes over one source video plus an audio pass, promoted to a foreground
 * service with staged progress and cancellation. Three job shapes:
 *
 * - **Censor-only** (M1's two passes; EDL output unchanged): pass 1 (0..50) decodes at 10 fps — every
 *   frame feeds ML Kit face tracking, every 2nd frame (5 fps) the NSFW gate; firings become hysteresis
 *   censor intervals and face tracks vote gender, together the [Edl]. Pass 2 (50..100) renders with
 *   [RenderPipeline] into a cache temp. Published directly (no mux).
 *   perf-plan Phase 2 dropped sampling to 5 fps and was REVERTED — see `docs/perf-plan.md`. The speedup
 *   in pass 1 is item 1.3's two overlaps, which do not touch which frames are sampled.
 * - **Music-only**: [AudioPipeline.removeMusic] strips music into a temp .m4a (1..93), then
 *   [Remux.mux] copies the ORIGINAL video track verbatim next to the new audio (93..99).
 * - **Combined**: analyze 0..25, render 25..50, separate 50..93, mux the pass-2 video + new audio.
 *
 * Every shape preflights the source ([Preflight]: readable, not DRM'd, has the tracks it needs, enough
 * free space) before any foreground work, and failures carry a per-cause message to the UI.
 * Every shape publishes into `Movies/Naqi` (MediaStore on API 29+, public dir + media scan below).
 * Cancel/failure delete every temp and any un-published MediaStore row; the ORT sessions and the face
 * detector always close. Cancellation propagates through the suspend calls untouched.
 */
class FilterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Soak instrumentation + the live ETA source (`long-film-plan.md` Phase 0). */
    private val stats = JobStats(ctx)

    // The tuning knobs, read once. Every shape below used to re-read its own subset out of inputData,
    // which meant the default for e.g. KEY_STRICTNESS was written out four times — four places for a
    // future edit to change three of them.
    private val strictness = inputData.getInt(KEY_STRICTNESS, 50)
    private val blurAmount = inputData.getInt(KEY_BLUR_AMOUNT, 60)
    private val grayscale = inputData.getBoolean(KEY_GRAYSCALE, false)
    private val blurUnknownFaces = inputData.getBoolean(KEY_BLUR_UNKNOWN, false)
    private val keepStems = inputData.getString(KEY_KEEP_STEMS) ?: "vocals"

    /**
     * Key for this job's working directory. Derived from the source and every option that changes the
     * OUTPUT — not from [getId] — so Phase 2's resume finds the same directory after process death, and
     * so a job restarted with different settings can never resume into work rendered under the old ones.
     *
     * These reads stay inline rather than using the vals above, even though they look identical. This
     * hash is a **persisted-state contract**: it names a directory that may already hold hours of
     * rendered segments waiting for a Resume tap. `getString(KEY_KEEP_STEMS)` hashes an absent key as
     * "null" where the val substitutes "vocals", so folding them in would change the key for that one
     * input and orphan exactly the work this key exists to find again. Not worth five lines.
     */
    private val jobKey by lazy {
        JobStore.keyOf(
            inputData.getString(KEY_INPUT_URI),
            inputData.getBoolean(KEY_REMOVE_MUSIC, false),
            inputData.getBoolean(KEY_CENSOR_WOMEN, false),
            inputData.getInt(KEY_STRICTNESS, 50),
            inputData.getInt(KEY_BLUR_AMOUNT, 60),
            inputData.getBoolean(KEY_GRAYSCALE, false),
            inputData.getBoolean(KEY_BLUR_UNKNOWN, false),
            inputData.getString(KEY_KEEP_STEMS),
            inputData.getString(KEY_FORCE_INTERVALS), // debug hook, but it does change the output
        )
    }

    /** `filesDir/naqi-work/<jobKey>/` — see [JobStore] for why not `cacheDir`. */
    private val workDir by lazy { JobStore.dir(applicationContext, jobKey) }

    override suspend fun doWork(): Result {
        val removeMusic = inputData.getBoolean(KEY_REMOVE_MUSIC, false)
        val censorWomen = inputData.getBoolean(KEY_CENSOR_WOMEN, false)
        if (!removeMusic && !censorWomen) return Result.failure()
        val inputUri = (inputData.getString(KEY_INPUT_URI) ?: return Result.failure()).toUri()

        // Reclaim orphaned job directories before writing several GB of our own. Here rather than in
        // Application/MainActivity because this is the only entry point that always runs: WorkManager
        // can restart a persisted job after a reboot without the user ever opening the app.
        // ponytail: orphans outlive an app the user never runs another job in. Move it to app startup
        // if that ever shows up as a storage complaint.
        runCatching { JobStore.sweep(applicationContext) }

        // Phase 2: a long source is processed in segments, so an interruption costs one segment instead of
        // the whole job. An empty plan means "short enough to run in one pass" and every shape below takes
        // exactly the M1/M2 route it always did. Probed before Preflight because the plan changes how much
        // scratch the job needs.
        val durationMs = runCatching { FrameSampler.probe(applicationContext, inputUri).durationMs }.getOrDefault(0L)
        var plan = Checkpoint.plan(durationMs, inputData.getLong(KEY_SEGMENT_MS, 0L))
        // Censor-only concat copies the SOURCE audio track sample-for-sample, which framework MediaMuxer
        // only accepts for AAC/AMR. A film with AC-3 audio therefore takes the unsegmented route, where
        // Transformer transcodes the audio for us — correct output, just no resume. See Remux.canCopyAudio.
        if (plan.isNotEmpty() && censorWomen && !removeMusic && !canCopyAudio(applicationContext, inputUri)) {
            Log.w(TAG, "source audio cannot be copied by MediaMuxer — falling back to the unsegmented route")
            plan = emptyList()
        }
        val segmented = censorWomen && plan.isNotEmpty()
        // The debug segment override also turns the audio scratch on, so the resumable separator can be
        // exercised on a clip short enough to kill and resume by hand — it is otherwise unreachable below
        // 30 min, which is far too long an iteration loop for the fiddliest code in the phase.
        val forcedSegments = inputData.getLong(KEY_SEGMENT_MS, 0L) > 0
        val resumableAudio = removeMusic && (durationMs >= Eta.CONFIRM_THRESHOLD_MS || forcedSegments)
        if (segmented) Log.i(TAG, "segmented: ${plan.size} segments over ${durationMs}ms key=$jobKey")

        // --- Preflight (BEFORE any heavy setForeground work) ---
        // Runs for EVERY shape, not just music removal: opening the source is the only way to learn
        // it is DRM'd/undecodable, and that throw used to escape doWork with no message at all.
        // tempCopies: combined writes a render temp AND a mux temp; the single-op shapes write one. The
        // segmented shape holds every rendered segment (~1x source) AND the concat output at once.
        Preflight.check(
            applicationContext, inputUri,
            needsAudio = removeMusic,
            tempCopies = when {
                segmented -> 2
                removeMusic && censorWomen -> 2
                else -> 1
            },
            // The separated-audio scratch: int16 stereo 44.1 kHz = 176 400 B per second of source.
            extraScratchBytes = if (resumableAudio || (segmented && removeMusic)) durationMs / 1000 * 176_400 else 0L,
        )?.let { return Result.failure(workDataOf(KEY_OUTPUT_MESSAGE to it)) }

        return when {
            segmented -> runSegmented(inputUri, plan, removeMusic, durationMs)
            removeMusic && censorWomen -> runCombined(inputUri)
            removeMusic -> runMusicOnly(inputUri, durationMs)
            else -> runCensorOnly(inputUri) // M1's unsegmented path
        }
    }

    /**
     * Phase 2's segmented censor route (`long-film-plan.md`), used whenever the source is long enough to
     * plan segments for. Three differences from [runCombined], all of them about surviving interruption:
     *
     * 1. **Analysis is per segment and checkpointed.** Each segment gets a fresh [FaceTracker] (which fixes
     *    its growth by construction) and writes `an-NNN.json` when it finishes. The EDL is still assembled
     *    GLOBALLY at the end, because the gate's hysteresis merges firings across boundaries — building it
     *    per segment would clip up to 1.5 s of censoring at every seam.
     * 2. **Rendering is per segment**, video-only, into `seg-NNN.mp4`; an already-rendered segment is
     *    skipped. Audio is muxed once at the end, since per-segment AAC cannot be concatenated.
     * 3. **The work directory survives a failure.** It is deleted on success and on a user cancel; a system
     *    stop (the 6 h foreground-service cap, an OOM kill, a reboot) leaves it for Resume to pick up.
     */
    private suspend fun runSegmented(
        inputUri: Uri,
        plan: List<RenderSegment>,
        removeMusic: Boolean,
        durationMs: Long,
    ): Result {
        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val audioTemp = File(workDir, "audio.m4a")
        val muxTemp = File(workDir, "mux.mp4")
        // Progress bands: analyze 0..25, render 25..50, separate 50..90, concat 90..99 (combined);
        // without music removal analyze/render get the room the separator would have used.
        val renderBase = if (removeMusic) 25 else 40
        val renderSpan = if (removeMusic) 25 else 50
        try {
            val edl = analyzeSegments(inputUri, plan, strictness, blurUnknownFaces, durationMs, 0, renderBase)
            renderSegments(inputUri, plan, edl, blurAmount, grayscale, renderBase, renderSpan)

            val audio = if (removeMusic) {
                val sep = stage(R.string.stage_separating)
                stats.stage("separate")
                AudioPipeline.removeMusic(
                    applicationContext, inputUri, keepStems, audioTemp,
                    onProgress = { p -> reportBand(sep, p, 50, 40) }, // 0..100 -> 50..90
                    isCancelled = { isStopped },
                    jobDir = workDir,
                )
                TrackSource.FromFile(audioTemp)
            } else {
                // Censor-only: the source audio track is copied verbatim, which is strictly better than
                // M1's per-export transmux — one continuous track, so no seam can drift against the video.
                TrackSource.FromUri(inputUri)
            }

            val joining = stage(R.string.stage_muxing)
            stats.stage("concat")
            Remux.concat(
                applicationContext,
                parts = plan.map { ConcatPart(Checkpoint.segmentFile(workDir, it.index), it.startMs) },
                audio = audio,
                outFile = muxTemp,
            ) { p -> reportBand(joining, p, 90, 9) } // 0..100 -> 90..99

            val displayName = outputName(inputUri)
            stats.stage("publish")
            val outputUri = publish(muxTemp, displayName)
            JobStore.delete(applicationContext, jobKey) // succeeded: nothing left to resume
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            // A stop is not automatically a cancel. Only the user asking to stop means "throw the work
            // away"; the 6 h cap, an lmkd kill and a reboot all arrive here too, and those are exactly the
            // cases this phase exists to survive. Pre-31 cannot tell them apart, so it keeps the work —
            // an orphan the sweep collects in 7 days is a far cheaper mistake than losing three hours.
            val cancelled = userCancelled()
            Log.i(TAG, "segmented job stopped: reason=${reasonName()} keepingWork=${!cancelled}")
            if (cancelled) runCatching { JobStore.delete(applicationContext, jobKey) }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "segmented job failed", t)
            // Work dir deliberately kept, and the UI is told so — this is what Resume resumes from.
            return Result.failure(
                workDataOf(
                    KEY_OUTPUT_MESSAGE to Preflight.messageFor(t),
                    KEY_RESUMABLE to true,
                ),
            )
        } finally {
            stats.finish("shape=segmented segments=${plan.size}")
            runCatching { Infer.close() }
            runCatching { if (muxTemp.exists()) muxTemp.delete() } // published or dead; never resumable
        }
    }

    /**
     * True only when the USER asked to stop. A stop is not automatically a cancel: the 6 h
     * foreground-service cap, an lmkd kill and a reboot all surface as cancellation too, and those are the
     * cases Phase 2 exists to survive. Pre-31 has no stop reason, so it answers false and keeps the work —
     * an orphan the 7-day sweep collects is a much cheaper mistake than deleting three hours of rendering.
     */
    private fun userCancelled(): Boolean =
        Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    private fun reasonName(): String = if (Build.VERSION.SDK_INT < 31) "unknown(pre-31)" else when (stopReason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "fgs_timeout"
        WorkInfo.STOP_REASON_NOT_STOPPED -> "not_stopped"
        else -> "code_$stopReason"
    }

    /**
     * Pass 1, per segment. Segments already carrying an `an-NNN.json` are skipped entirely — that is the
     * resume. Firings accumulate across every segment and the hysteresis runs ONCE over the whole
     * timeline, so a censor interval that straddles a seam is still one interval.
     */
    private suspend fun analyzeSegments(
        inputUri: Uri,
        plan: List<RenderSegment>,
        strictness: Int,
        blurUnknownFaces: Boolean,
        durationMs: Long,
        progressBase: Int,
        progressSpan: Int,
    ): Edl {
        val stage = stage(R.string.stage_analyzing)
        stats.stage("analyze")
        val firings = ArrayList<Long>()
        val faceTracks = ArrayList<FaceTrackEdl>()
        // Outside the segment loop on purpose: report() is an AWAITED setProgress (Room write) plus a
        // setForeground binder IPC, and the sampler calls it ~93 000 times over a film for ~40 distinct
        // values. Hoisted, it also kills the duplicate write at every seam and on the resume branch.
        var lastPct = -1
        for (seg in plan) {
            val done = Checkpoint.readAnalysis(workDir, seg.index)
            if (done != null) {
                firings += done.firingsMs
                faceTracks += done.edl.faceTracks
                Log.i(TAG, "analyze seg-${seg.index}: resumed from checkpoint " +
                    "(${done.firingsMs.size} firings, ${done.edl.faceTracks.size} tracks)")
                val pct = progressBase + (seg.index + 1) * progressSpan / plan.size
                if (pct != lastPct) { lastPct = pct; report(stage, pct) }
                continue
            }
            val segFirings = ArrayList<Long>()
            // Fresh per segment: this is what bounds FaceTracker's growth by construction, on top of the
            // per-track eviction Phase 1 added.
            val tracker = FaceTracker(applicationContext, blurUnknownFaces)
            var index = 0
            var tDetect = 0L; var tGate = 0L; val tWall = System.nanoTime()
            try {
                FrameSampler.sample(
                    applicationContext, inputUri, fps = 10f, maxDim = 640,
                    startMs = seg.startMs, endMs = seg.endMs,
                ) { bitmap, ptsMs ->
                    // perf-plan 1.3a: ML Kit works on its own executor while the gate runs here, so detect and
                    // gate now OVERLAP — tDetect times the await (the part that still blocks), and detect+gate
                    // can exceed wall. Awaiting inside the callback is what keeps the bitmap alive for both.
                    val task = tracker.detect(bitmap)
                    if (index % 2 == 0) {
                        val t1 = System.nanoTime()
                        val probs = Infer.nsfw(applicationContext, bitmap)
                        tGate += System.nanoTime() - t1
                        if (NsfwGate.fires(probs, strictness)) segFirings += ptsMs
                    }
                    val t0 = System.nanoTime(); val faces = Tasks.await(task); tDetect += System.nanoTime() - t0
                    tracker.onFaces(faces, bitmap, ptsMs)
                    index++
                    val within = ((ptsMs - seg.startMs).toFloat() / (seg.endMs - seg.startMs).coerceAtLeast(1))
                    val pct = (progressBase + ((seg.index + within.coerceIn(0f, 1f)) * progressSpan / plan.size).toInt())
                        .coerceIn(progressBase, progressBase + progressSpan)
                    if (pct != lastPct) { lastPct = pct; report(stage, pct) }
                }
                // perf-plan 1.2, re-read after 1.3b: decode+convert now runs alongside this callback, so wall
                // is max(producer, consumer) and no subtraction recovers the convert. These two are the
                // consumer side only — a pass where detect+gate is under wall is one the decoder is pacing.
                val wallMs = (System.nanoTime() - tWall) / 1_000_000
                val segTracks = tracker.finish()
                // The checkpoint goes in only once BOTH halves of this segment's analysis exist, and it is
                // written atomically — so a file under its final name always means a complete segment.
                Checkpoint.writeAnalysis(workDir, seg.index, segFirings, Edl(emptyList(), segTracks))
                firings += segFirings
                faceTracks += segTracks
                Log.i(TAG, "analyze seg-${seg.index} [${seg.startMs}..${seg.endMs}): " +
                    "firings=${segFirings.size} censorTracks=${segTracks.size} ${tracker.retention()} " +
                    "wall=${wallMs}ms detect=${tDetect / 1_000_000}ms gate=${tGate / 1_000_000}ms")
            } finally {
                runCatching { tracker.closeDetector() }
            }
        }
        // ONE hysteresis pass over the whole timeline — the reason firings are accumulated rather than
        // turned into intervals per segment.
        var intervals = NsfwGate.intervals(firings, durationMs)
        if (BuildConfig.DEBUG) {
            val forced = parseForceIntervals(inputData.getString(KEY_FORCE_INTERVALS))
            if (forced.isNotEmpty()) intervals = intervals + forced
        }
        Log.i(TAG, "pass1 segmented: gateFirings=${firings.size} intervalCount=${intervals.size} " +
            "censorFaceTracks=${faceTracks.size}")
        return Edl(intervals, faceTracks.sortedBy { it.startMs })
    }

    /** Pass 2, per segment. An existing `seg-NNN.mp4` is skipped; that plus [analyzeSegments] is the resume. */
    private suspend fun renderSegments(
        inputUri: Uri,
        plan: List<RenderSegment>,
        edl: Edl,
        blurAmount: Int,
        grayscale: Boolean,
        progressBase: Int,
        progressSpan: Int,
    ) {
        val stage = stage(R.string.stage_rendering)
        stats.stage("render")
        val meta = FrameSampler.probe(applicationContext, inputUri)
        for (seg in plan) {
            if (Checkpoint.isRendered(workDir, seg.index)) {
                Log.i(TAG, "render seg-${seg.index}: already done, skipping")
                report(stage, progressBase + (seg.index + 1) * progressSpan / plan.size)
                continue
            }
            // Export to `.part` and rename, so a killed export never leaves a file that looks complete.
            val part = File(workDir, "seg-%03d.mp4.part".format(seg.index))
            runCatching { part.delete() }
            RenderPipeline.renderCensor(
                applicationContext, inputUri, part, edl, blurAmount, grayscale, meta, segment = seg,
            ) { p ->
                val overall = progressBase + ((seg.index * 100 + p) * progressSpan / (plan.size * 100))
                stats.tick()
                setProgressAsync(workDataOf(KEY_PROGRESS to overall, KEY_STAGE to stage, KEY_ETA_MS to stats.etaMs(overall)))
                setForegroundAsync(foregroundInfo(stage, overall))
            }
            check(part.renameTo(Checkpoint.segmentFile(workDir, seg.index))) {
                "could not commit segment ${seg.index}"
            }
            Log.i(TAG, "render seg-${seg.index}: ${Checkpoint.segmentFile(workDir, seg.index).length()} bytes")
        }
    }

    // ---- Censor-only: M1's shape, extracted into a function ----
    private suspend fun runCensorOnly(inputUri: Uri): Result {
        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val tempFile = File(workDir, "render.mp4")
        val faceTracker = FaceTracker(applicationContext, blurUnknownFaces)
        try {
            val edl = analyze(inputUri, strictness, faceTracker)
            render(inputUri, tempFile, edl, blurAmount, grayscale)
            val displayName = outputName(inputUri)
            stats.stage("publish")
            val outputUri = publish(tempFile, displayName)
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            throw c // WorkManager cancellation — never swallow it
        } catch (t: Throwable) {
            Log.e(TAG, "censor job failed", t)
            return Result.failure(workDataOf(KEY_OUTPUT_MESSAGE to Preflight.messageFor(t)))
        } finally {
            stats.finish("shape=censor ${faceTracker.retention()}")
            runCatching { faceTracker.closeDetector() }
            runCatching { Infer.close() }
            runCatching { JobStore.delete(applicationContext, jobKey) }
        }
    }

    // ---- Music-only: audio 1..93, mux 93..99, video passthrough from the original Uri ----
    private suspend fun runMusicOnly(inputUri: Uri, durationMs: Long): Result {
        setForeground(foregroundInfo(stage(R.string.stage_separating), 1))

        val audioTemp = File(workDir, "audio.m4a")
        val muxTemp = File(workDir, "mux.mp4")
        // There is no video pass to segment here, so "long" only buys the resumable separator. Same
        // 30-minute threshold as everything else, so the product has one notion of a long job.
        val resumable = durationMs >= Eta.CONFIRM_THRESHOLD_MS
        try {
            val sep = stage(R.string.stage_separating)
            stats.stage("separate")
            AudioPipeline.removeMusic(
                applicationContext, inputUri, keepStems, audioTemp,
                onProgress = { p -> reportBand(sep, p, 1, 92) },   // 0..100 -> 1..93
                isCancelled = { isStopped },
                jobDir = if (resumable) workDir else null,
            )

            val mux = stage(R.string.stage_muxing)
            stats.stage("mux")
            Remux.mux(applicationContext, TrackSource.FromUri(inputUri), audioTemp, muxTemp,
                onProgress = { p -> reportBand(mux, p, 93, 6) })   // 0..100 -> 93..99

            val displayName = outputName(inputUri)
            stats.stage("publish")
            val outputUri = publish(muxTemp, displayName)
            JobStore.delete(applicationContext, jobKey)
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            // Same rule as the segmented route: only a user cancel throws the scratch away. On the
            // resumable path a system stop must leave audio.pcm + audio.json for Resume.
            if (!resumable || userCancelled()) runCatching { JobStore.delete(applicationContext, jobKey) }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "music job failed", t)
            if (!resumable) runCatching { JobStore.delete(applicationContext, jobKey) }
            return Result.failure(workDataOf(KEY_OUTPUT_MESSAGE to Preflight.messageFor(t)))
        } finally {
            stats.finish("shape=music resumable=$resumable")
            runCatching { if (muxTemp.exists()) muxTemp.delete() }
        }
    }

    // ---- Combined: analyze 0..25, render 25..50, separate 50..93, mux 93..99 ----
    private suspend fun runCombined(inputUri: Uri): Result {
        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val renderTemp = File(workDir, "render.mp4")
        val audioTemp = File(workDir, "audio.m4a")
        val muxTemp = File(workDir, "mux.mp4")
        val faceTracker = FaceTracker(applicationContext, blurUnknownFaces)
        try {
            val edl = analyze(inputUri, strictness, faceTracker, progressBase = 0, progressSpan = 25)
            render(inputUri, renderTemp, edl, blurAmount, grayscale, progressBase = 25, progressSpan = 25)

            val sep = stage(R.string.stage_separating)
            stats.stage("separate")
            AudioPipeline.removeMusic(
                applicationContext, inputUri, keepStems, audioTemp,
                onProgress = { p -> reportBand(sep, p, 50, 43) },  // 0..100 -> 50..93
                isCancelled = { isStopped },
            )

            val mux = stage(R.string.stage_muxing)
            stats.stage("mux")
            Remux.mux(applicationContext, TrackSource.FromFile(renderTemp), audioTemp, muxTemp,
                onProgress = { p -> reportBand(mux, p, 93, 6) })   // 0..100 -> 93..99

            val displayName = outputName(inputUri)
            stats.stage("publish")
            val outputUri = publish(muxTemp, displayName)
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "combined job failed", t)
            return Result.failure(workDataOf(KEY_OUTPUT_MESSAGE to Preflight.messageFor(t)))
        } finally {
            stats.finish("shape=combined ${faceTracker.retention()}")
            runCatching { faceTracker.closeDetector() }
            runCatching { Infer.close() }
            runCatching { JobStore.delete(applicationContext, jobKey) }
        }
    }

    /** Map a 0..100 sub-progress onto [base, base+span] and push it to WorkData + the notification. */
    private fun reportBand(stage: String, sub: Int, base: Int, span: Int) {
        val overall = (base + sub * span / 100).coerceIn(0, 100)
        stats.tick()
        setProgressAsync(workDataOf(KEY_PROGRESS to overall, KEY_STAGE to stage, KEY_ETA_MS to stats.etaMs(overall)))
        setForegroundAsync(foregroundInfo(stage, overall))
    }


    /**
     * Pass 1: sample once, run faces and the gate on every sampled frame, build the EDL. [pct]
     * becomes `progressBase + fraction*progressSpan`; the defaults reproduce the M1 0..50 band exactly.
     */
    private suspend fun analyze(
        uri: Uri, strictness: Int, faceTracker: FaceTracker,
        progressBase: Int = 0, progressSpan: Int = 50,
    ): Edl {
        val durationMs = FrameSampler.probe(applicationContext, uri).durationMs.coerceAtLeast(1L)
        val stage = stage(R.string.stage_analyzing)
        stats.stage("analyze")
        val firings = ArrayList<Long>()
        var index = 0
        var lastPct = -1
        var tDetect = 0L; var tGate = 0L; val tWall = System.nanoTime()
        FrameSampler.sample(applicationContext, uri, fps = 10f, maxDim = 640) { bitmap, ptsMs ->
            // Same overlapped sequence as analyzeSegments — see the comment there (perf-plan 1.3a).
            val task = faceTracker.detect(bitmap)
            if (index % 2 == 0) {
                val t1 = System.nanoTime()
                val probs = Infer.nsfw(applicationContext, bitmap)
                tGate += System.nanoTime() - t1
                if (NsfwGate.fires(probs, strictness)) firings += ptsMs
            }
            val t0 = System.nanoTime(); val faces = Tasks.await(task); tDetect += System.nanoTime() - t0
            faceTracker.onFaces(faces, bitmap, ptsMs)
            index++
            val pct = (progressBase + (ptsMs.toFloat() / durationMs) * progressSpan)
                .toInt().coerceIn(progressBase, progressBase + progressSpan)
            if (pct != lastPct) { lastPct = pct; report(stage, pct) }
            // Retention, live. On a feature-length film the end-of-pass numbers only arrive 70 minutes
            // in, and never at all if the job is cancelled — this is the line that shows the per-track
            // eviction actually holding a flat crop count instead of climbing toward the old ~2 900.
            // Every 1 200 sampled frames = every 2 min of source at 10 fps.
            if (index % 1_200 == 0) Log.i(TAG, "pass1 live at=${ptsMs}ms ${faceTracker.retention()}")
        }
        val wallMs = (System.nanoTime() - tWall) / 1_000_000 // sampling only; the vote below is its own stage

        var intervals = NsfwGate.intervals(firings, durationMs)
        // Debug E2E hook: force full-frame spans so SFW test assets still exercise pass 2 censoring.
        if (BuildConfig.DEBUG) {
            val forced = parseForceIntervals(inputData.getString(KEY_FORCE_INTERVALS))
            if (forced.isNotEmpty()) intervals = intervals + forced
        }
        // wall #3 of long-film-plan.md. Tracks now vote and recycle during the pass, so "vote" only
        // covers the handful still live at the end — it is kept as its own stage because the 2.7 min
        // this took on the 155-min soak is the before-number this Phase 1 item exists to move.
        stats.stage("vote")
        val faceTracks = faceTracker.finish()
        // Counts on their own line: a feature-length film produces hundreds of intervals, and logcat
        // truncates a message at ~4 kB — on the first 155-min soak that silently ate the face counts
        // off the end of the combined line, which were the whole point of logging it.
        Log.i(TAG, "pass1: gateFirings=${firings.size} intervalCount=${intervals.size} " +
            "censorFaceTracks=${faceTracks.size} ${faceTracker.retention()} " +
            "wall=${wallMs}ms detect=${tDetect / 1_000_000}ms gate=${tGate / 1_000_000}ms")
        Log.i(TAG, "pass1 intervals=$intervals")
        return Edl(intervals, faceTracks)
    }

    /**
     * Pass 2: render the EDL into [tempFile]; the transformer's 0..100 maps onto
     * `progressBase + p*progressSpan/100`. The defaults reproduce the M1 50..100 band exactly.
     */
    private suspend fun render(
        uri: Uri, tempFile: File, edl: Edl, blurAmount: Int, grayscale: Boolean,
        progressBase: Int = 50, progressSpan: Int = 50,
    ) {
        val stage = stage(R.string.stage_rendering)
        stats.stage("render")
        report(stage, progressBase)
        val meta = FrameSampler.probe(applicationContext, uri)
        RenderPipeline.renderCensor(applicationContext, uri, tempFile, edl, blurAmount, grayscale, meta) { p ->
            val overall = progressBase + p * progressSpan / 100
            stats.tick()
            // onProgress is non-suspend (runs on the transformer's Looper) — use the async variants.
            setProgressAsync(workDataOf(KEY_PROGRESS to overall, KEY_STAGE to stage, KEY_ETA_MS to stats.etaMs(overall)))
            setForegroundAsync(foregroundInfo(stage, overall))
        }
    }

    /**
     * Copy the temp into `Movies/Naqi` and return its uri. The blocking copy has no suspension point,
     * so a cancel that arrives mid-copy only surfaces after it: [isStopped] is re-checked before the
     * output is finalized, and any failure or cancel deletes the half-written row/file — a cancelled or
     * failed job must never leave output in `Movies/`.
     */
    private fun publish(tempFile: File, displayName: String): Uri {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Naqi")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                (resolver.openOutputStream(item) ?: error("MediaStore openOutputStream failed"))
                    .use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                if (isStopped) throw CancellationException("cancelled during publish")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(item, values, null, null)
                return item
            } catch (t: Throwable) {
                runCatching { resolver.delete(item, null, null) } // drop the un-finalized (still-pending) row
                throw t
            }
        }
        // API 26-28: write into the public Movies/Naqi dir (needs WRITE_EXTERNAL_STORAGE) and hand it to the scanner.
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Naqi").apply { mkdirs() }
        val dest = File(dir, displayName)
        try {
            tempFile.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (isStopped) throw CancellationException("cancelled during publish")
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(MIME_MP4), null)
            return Uri.fromFile(dest)
        } catch (t: Throwable) {
            dest.delete() // remove the partial/complete copy before the scanner ever sees it
            throw t
        }
    }

    /** Success path for every shape: post the done notification, then report the output to the UI. */
    private fun succeed(displayName: String, outputUri: Uri, inputUri: Uri): Result {
        JobNotifications.done(applicationContext, displayName, outputUri.toString(), inputUri.toString())
        return Result.success(workDataOf(KEY_OUTPUT_NAME to displayName, KEY_OUTPUT_URI to outputUri.toString()))
    }

    /** `<sourceNameNoExt>-naqi-<epochMillis>.mp4`; source name queried, falling back to "video". */
    private fun outputName(uri: Uri): String {
        val source = applicationContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "video"
        return "${source.substringBeforeLast('.')}-naqi-${System.currentTimeMillis()}.mp4"
    }

    private suspend fun report(stage: String, pct: Int) {
        stats.tick()
        setProgress(workDataOf(KEY_PROGRESS to pct, KEY_STAGE to stage, KEY_ETA_MS to stats.etaMs(pct)))
        setForeground(foregroundInfo(stage, pct))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(stage(R.string.stage_analyzing), 0)

    private fun stage(resId: Int): String = applicationContext.getString(resId)

    // The ETA is derived here rather than passed in, so every existing call site posts it for free.
    private fun foregroundInfo(stage: String, progress: Int) =
        JobNotifications.foregroundInfo(applicationContext, id, stage, progress, stats.etaMs(progress))

    companion object {
        const val KEY_REMOVE_MUSIC = "remove_music"
        const val KEY_CENSOR_WOMEN = "censor_women"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_STRICTNESS = "strictness"
        const val KEY_BLUR_AMOUNT = "blur_amount"
        const val KEY_GRAYSCALE = "grayscale"
        const val KEY_BLUR_UNKNOWN = "blur_unknown_faces"
        const val KEY_KEEP_STEMS = "keep_stems"
        const val KEY_FORCE_INTERVALS = "force_intervals"

        /**
         * Debug-only segment length override in ms. Any positive value forces the Phase 2 segmented route
         * regardless of duration — the only way to exercise multi-segment concat and kill/resume on a clip
         * short enough to iterate on. 0 (the default) means "decide from the source duration".
         */
        const val KEY_SEGMENT_MS = "segment_ms"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"

        /** Live remaining-time estimate in ms, refined from observed throughput; 0 = too early to say. */
        const val KEY_ETA_MS = "eta_ms"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_MESSAGE = "output_message"

        /**
         * Set on a failure that left completed segments on disk, so the UI can offer Resume. Re-running the
         * same (source, options) lands on the same [JobStore] key and skips everything already finished.
         */
        const val KEY_RESUMABLE = "resumable"
        const val UNIQUE_WORK = "naqi_filter_job"

        private const val TAG = "FilterWorker"
        private const val MIME_MP4 = "video/mp4"

        /** Parse "startMs-endMs,startMs-endMs"; bad segments are skipped. */
        private fun parseForceIntervals(spec: String?): List<LongRange> {
            if (spec.isNullOrBlank()) return emptyList()
            return spec.split(",").mapNotNull { part ->
                val nums = part.split("-")
                val start = nums.getOrNull(0)?.trim()?.toLongOrNull()
                val end = nums.getOrNull(1)?.trim()?.toLongOrNull()
                if (start != null && end != null) start..end else null
            }
        }
    }
}
