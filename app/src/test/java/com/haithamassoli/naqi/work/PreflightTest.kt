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

    /**
     * An unrecognized cause resolves to the generic message. It used to pass the throwable's own text
     * through to the screen — untranslated developer text the user can do nothing with.
     */
    @Test
    fun anythingElseFallsBackToTheGenericMessage() {
        assertEquals(Preflight.GENERIC, Preflight.messageFor(IllegalStateException("separator emitted 3 of 4 frames")))
        assertEquals(Preflight.GENERIC, Preflight.messageFor(RuntimeException()))
    }

    /** Every cause must be a real resource id — a 0 here would render as the generic fallback in the UI. */
    @Test
    fun everyCauseIsANonZeroResourceId() {
        val ids = listOf(
            Preflight.DRM, Preflight.UNREADABLE, Preflight.NO_VIDEO, Preflight.NO_AUDIO,
            Preflight.LOW_SPACE, Preflight.UNSUPPORTED_CODEC, Preflight.OUT_OF_SPACE, Preflight.GENERIC,
        )
        assertEquals(ids.size, ids.filter { it != 0 }.distinct().size)
    }

    /** ENOSPC surfaces as an IOException too — the space branch must not be shadowed by the IO branch. */
    @Test
    fun spaceBranchOutranksTheIoBranch() {
        assertEquals(Preflight.OUT_OF_SPACE, Preflight.messageFor(IOException("No space left on device")))
    }
}
