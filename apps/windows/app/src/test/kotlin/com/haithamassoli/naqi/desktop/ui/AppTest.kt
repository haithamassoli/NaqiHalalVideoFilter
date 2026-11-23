package com.haithamassoli.naqi.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.haithamassoli.naqi.desktop.pipeline.JobOptions
import com.haithamassoli.naqi.desktop.pipeline.Stage
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The whole flow against a fake Pipeline.run: pick, set options, start, watch progress, cancel. */
@OptIn(ExperimentalTestApi::class)
class AppTest {
    private val dir = Files.createTempDirectory("naqi-ui").toFile()
    private val video = File(dir, "clip.mp4").apply { writeText("not really a video") }

    @Test
    fun setsOptionsAndStartsAJob() = runComposeUiTest {
        val started = CompletableDeferred<JobOptions>()
        val cancelled = CompletableDeferred<Unit>()
        val queue = JobQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { job, onProgress ->
            started.complete(job)
            onProgress(Stage.ANALYZE, 50)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        setContent { App(queue, Strings.EN, chooseVideo = { video }, chooseOutput = { null }) }

        // Pick: Continue waits for a video.
        onNodeWithText("Continue").assertIsNotEnabled()
        onNodeWithText("Pick a video").performClick()
        onNodeWithText("clip.mp4").assertExists()
        onNodeWithText("Remove music").performClick()
        onNodeWithText("Continue").assertIsEnabled().performClick()

        // Options.
        onNodeWithText("Men").performScrollTo().performClick()
        onNodeWithText("Frame").performScrollTo().performClick()
        onNodeWithText("Solid").performScrollTo().performClick()
        onNodeWithContentDescription("Navy").performScrollTo().performClick()
        onNodeWithText("Voices + sounds").performScrollTo().performClick()
        onNodeWithText("clip-naqi.mp4").performScrollTo().assertExists()
        onNodeWithText("Start").performClick()

        // Jobs: the fake got exactly what was set, and its progress shows.
        waitUntil(timeoutMillis = 5_000) { started.isCompleted }
        val job = started.getCompleted()
        assertEquals(video, job.input)
        assertEquals(File(dir, "clip-naqi.mp4"), job.output)
        val ops = job.ops
        assertTrue(ops.removeMusic)
        assertEquals("vocals_other", ops.keepStems)
        assertEquals(FilterOps.MEN, ops.censorWho)
        assertTrue(ops.wholeFrameBlur)
        assertEquals(FilterOps.SOLID_COLORS[3], ops.solidColor)
        assertTrue(ops.censorNsfw)
        assertEquals(FilterOps.DEFAULT_STRICTNESS, ops.strictness)

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextExists("Pass 1 — analyzing") }
        // Analyze owns 9 of the 98 points of a faces + music job, so 50 % of it is 4 % overall.
        onNodeWithText("4%").assertExists()
        onNodeWithText("Cancel").performClick()
        waitUntil(timeoutMillis = 5_000) { cancelled.isCompleted }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextExists("Cancelled") }
    }

    @Test
    fun aTypedNameWithoutExtensionNeverLandsOnAnExistingFile() = runComposeUiTest {
        File(dir, "holiday.mp4").writeText("last year's")
        val started = CompletableDeferred<JobOptions>()
        val queue = JobQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { job, _ -> started.complete(job) }
        setContent { App(queue, Strings.EN, chooseVideo = { video }, chooseOutput = { File(dir, "holiday") }) }
        onNodeWithText("Pick a video").performClick()
        onNodeWithText("Continue").performClick()
        onNodeWithText("Change…").performScrollTo().performClick()
        onNodeWithText("holiday-2.mp4").performScrollTo().assertExists()
        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { started.isCompleted }
        assertEquals(File(dir, "holiday-2.mp4"), started.getCompleted().output)
        assertEquals("last year's", File(dir, "holiday.mp4").readText())
    }

    @Test
    fun arabicIsTheArabicStrings()= runComposeUiTest {
        val queue = JobQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { _, _ -> }
        setContent { App(queue, Strings.forLocale(java.util.Locale.forLanguageTag("ar-SA")), chooseVideo = { video }) }
        onNodeWithText("نقي").assertExists()
        onNodeWithText("إزالة الموسيقى").assertExists()
        onNodeWithText("متابعة").assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextExists(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
