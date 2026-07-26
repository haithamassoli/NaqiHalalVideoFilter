package com.haithamassoli.naqi.work

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * [Preflight.messageFor] is the last thing between a pipeline throw and what the user reads, and its
 * branches are ordered (specific causes shadow the generic tail). These pin that order.
 */
class PreflightTest {

    @Test
    fun outOfSpaceWins() {
        assertEquals(Preflight.OUT_OF_SPACE, Preflight.messageFor(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun drmIsDetectedThroughTheCauseChain() {
        val wrapped = IllegalStateException("render failed", IllegalStateException("MediaCodec crypto error"))
        assertEquals(Preflight.DRM, Preflight.messageFor(wrapped))
    }

    @Test
    fun codecFailuresReportAsUnsupported() {
        assertEquals(Preflight.UNSUPPORTED_CODEC, Preflight.messageFor(IllegalArgumentException("Failed to create decoder for video/av01")))
    }

    @Test
    fun ioFailuresReportAsUnreadable() {
        assertEquals(Preflight.UNREADABLE, Preflight.messageFor(FileNotFoundException("/data/x.mp4")))
        assertEquals(Preflight.UNREADABLE, Preflight.messageFor(IOException("Failed to instantiate extractor")))
    }

    @Test
    fun anythingElseKeepsItsOwnMessageAndNeverReturnsBlank() {
        assertEquals("separator emitted 3 of 4 frames", Preflight.messageFor(IllegalStateException("separator emitted 3 of 4 frames")))
        assertEquals(Preflight.GENERIC, Preflight.messageFor(RuntimeException()))
    }

    /** ENOSPC surfaces as an IOException too — the space branch must not be shadowed by the IO branch. */
    @Test
    fun spaceBranchOutranksTheIoBranch() {
        assertEquals(Preflight.OUT_OF_SPACE, Preflight.messageFor(IOException("No space left on device")))
    }
}
