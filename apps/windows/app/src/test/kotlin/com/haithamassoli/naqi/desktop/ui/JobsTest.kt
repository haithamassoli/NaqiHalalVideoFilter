package com.haithamassoli.naqi.desktop.ui

import com.haithamassoli.naqi.desktop.pipeline.JobOptions
import com.haithamassoli.naqi.desktop.pipeline.Stage
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobsTest {
    private val both = FilterOps(removeMusic = true, censorWho = FilterOps.EVERYONE)
    private val musicOnly = FilterOps(removeMusic = true, censorNsfw = false)

    @Test
    fun overallPercentRunsOnceAcrossTheStages() {
        assertEquals(0, overallPercent(both, Stage.ANALYZE, 0))
        assertEquals(9 * 100 / 98, overallPercent(both, Stage.MUSIC, 0))
        assertEquals(89 * 100 / 98, overallPercent(both, Stage.RENDER, 0))
        assertEquals(100, overallPercent(both, Stage.RENDER, 100))
        // Music only: separation is nearly the whole bar, the remux the last few points.
        assertEquals(97, overallPercent(musicOnly, Stage.RENDER, 0))
        // Out-of-range input never escapes 0..100.
        assertEquals(100, overallPercent(both, Stage.RENDER, 250))
    }

    @Test
    fun etaIsSilentUntilItMeansSomething() {
        assertEquals(0L, etaMs(elapsedMs = 60_000, percent = 2))
        assertEquals(0L, etaMs(elapsedMs = 1_000, percent = 50))
        assertEquals(60_000L, etaMs(elapsedMs = 60_000, percent = 50))
        assertEquals(0L, etaMs(elapsedMs = 60_000, percent = 100))
    }

    @Test
    fun defaultOutputNeverClobbers() {
        val dir = Files.createTempDirectory("naqi-out").toFile()
        val input = File(dir, "talk.mkv").apply { writeText("x") }
        assertEquals(File(dir, "talk-naqi.mp4"), defaultOutput(input))
        File(dir, "talk-naqi.mp4").writeText("earlier run")
        assertEquals(File(dir, "talk-naqi-2.mp4"), defaultOutput(input))
        // Claimed by a queued job that has not written it yet.
        assertEquals(File(dir, "talk-naqi-3.mp4"), defaultOutput(input, taken = listOf(File(dir, "talk-naqi-2.mp4"))))
        assertTrue(sameFile(input, File(dir, "./talk.mkv")))
    }

    @Test
    fun jobsRunOneAtATimeInOrder() = runBlocking {
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val queue = JobQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { job, _ ->
            synchronized(order) { order += "start ${job.input.name}" }
            if (job.input.name == "a") release.await()
            if (job.input.name == "c") error("boom")
            synchronized(order) { order += "end ${job.input.name}" }
        }
        fun opts(n: String) = JobOptions(File(n), File("$n.mp4"), both)
        val a = queue.submit(opts("a"))
        val b = queue.submit(opts("b"))
        val c = queue.submit(opts("c"))
        withTimeout(5_000) { while (a.state != JobState.RUNNING) delay(5) }
        assertEquals(JobState.QUEUED, b.state)
        release.complete(Unit)
        withTimeout(5_000) { while (!c.finished) delay(5) }
        assertEquals(listOf("start a", "end a", "start b", "end b", "start c"), order)
        assertEquals(JobState.DONE, b.state)
        assertEquals(JobState.FAILED, c.state)
        assertEquals("boom", c.error)
        assertEquals(listOf(c, b, a), queue.items.toList())
    }
}
