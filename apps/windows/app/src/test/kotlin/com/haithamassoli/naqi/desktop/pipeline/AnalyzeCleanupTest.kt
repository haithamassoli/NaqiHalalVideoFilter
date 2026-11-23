package com.haithamassoli.naqi.desktop.pipeline

import ai.onnxruntime.OrtSession
import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.desktop.vision.SessionCleanupTest.Companion.assertAllClosed
import com.haithamassoli.naqi.desktop.vision.openedSessions
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Analyze opens the face models, then the NSFW one fails: the face sessions must still be closed. */
class AnalyzeCleanupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = mutableListOf<OrtSession>()

    @Before fun log() {
        assumeTrue(listOf(Models.YUNET, Models.GENDERAGE).all { Models.fileOrNull(it) != null })
        openedSessions = opened
    }

    @After fun unlog() {
        openedSessions = null
    }

    @Test fun aCorruptNsfwModelClosesTheFaceSessionsOpenedBeforeIt() {
        val dir = tmp.newFolder("models")
        for (m in listOf(Models.YUNET, Models.GENDERAGE)) File(Models.dir(), m).copyTo(File(dir, m))
        File(dir, Models.NSFW).writeText("not an onnx model")
        val ops = FilterOps(censorWho = FilterOps.WOMEN, censorNsfw = true)
        val failed = runCatching {
            // It fails before decoding, so the input is never read.
            runBlocking { Pipeline.analyze(tmp.newFile("in.mp4"), VideoMeta(640, 360, 0, 1000, 30f), ops, dir) {} }
        }.exceptionOrNull()
        assertTrue("the corrupt model must fail: $failed", failed != null)
        assertEquals(2, opened.size) // YuNet, genderage
        assertAllClosed(opened)
    }
}
