package com.haithamassoli.naqi.desktop.vision

import ai.onnxruntime.OrtSession
import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.model.FilterOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A model that fails to load must not strand the sessions opened before it. Uses the real YuNet (skipped
 * when it is not staged) next to a corrupt genderage, and reads ORT's own `closed` flag on each session.
 */
class SessionCleanupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = mutableListOf<OrtSession>()

    @Before fun log() {
        assumeTrue(Models.fileOrNull(Models.YUNET) != null)
        openedSessions = opened
    }

    @After fun unlog() {
        openedSessions = null
    }

    @Test fun anUnreadableGenderModelClosesTheDetectorItOpened() {
        val dir = tmp.newFolder("models")
        File(Models.dir(), Models.YUNET).copyTo(File(dir, Models.YUNET))
        File(dir, Models.GENDERAGE).writeText("not an onnx model")
        val failed = runCatching { FaceAnalyzer(FilterOps.WOMEN, dir) }.exceptionOrNull()
        assertTrue("the corrupt model must fail: $failed", failed != null)
        assertEquals(1, opened.size) // YuNet
        assertAllClosed(opened)
    }

    companion object {
        fun isClosed(s: OrtSession): Boolean =
            OrtSession::class.java.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(s)

        fun assertAllClosed(sessions: List<OrtSession>) =
            sessions.forEachIndexed { i, s -> assertTrue("session $i is still open", isClosed(s)) }
    }
}
