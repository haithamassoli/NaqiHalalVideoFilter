package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions that moved out of [FaceTracker]'s old end-of-pass `finish()` when Phase 1 of
 * `long-film-plan.md` made tracks evict during the pass. Both are pure, so they are testable without
 * ML Kit or a Context; the eviction plumbing around them is device-verified, not unit-tested.
 *
 * The gender-vote cases these tests used to carry are gone with the vote itself (plan-v2 §5.4): there
 * is no censor DECISION left to assert, only the span construction, so `every track with samples
 * produces a span` is now what the first test pins.
 */
class FaceTrackerLogicTest {

    private fun track(vararg ptsMs: Long) = FaceTrack(id = 1).apply {
        ptsMs.forEach { samples += FaceSample(it, NRect(0.4f, 0.4f, 0.6f, 0.6f)) }
    }

    @Test
    fun `a track is censored and its span is padded by half a sample gap`() {
        val edl = edlFor(track(1_000L, 1_100L, 1_200L))!!
        assertEquals(950L, edl.startMs)
        assertEquals(1_250L, edl.endMs)
        assertEquals(3, edl.keyframes.size)
    }

    /** V4: no verdict, no opt-in, no "unknown" bucket — one sample is enough to be censored. */
    @Test
    fun `a single-sample track still produces a span`() {
        val edl = edlFor(track(1_000L))!!
        assertEquals(950L, edl.startMs)
        assertEquals(1_050L, edl.endMs)
        assertEquals(1, edl.keyframes.size)
    }

    /** A track evicted before it ever collected a sample would otherwise blow up on samples.first(). */
    @Test
    fun `sampleless track emits nothing rather than throwing`() {
        assertNull(edlFor(track()))
    }

    @Test
    fun `span start clamps at zero for a track that begins in the first frames`() {
        val edl = edlFor(track(0L, 100L))!!
        assertEquals(0L, edl.startMs)
    }

    /** Keyframe rects are padded 25% per side and clamped, so an edge face cannot leave the frame. */
    @Test
    fun `keyframe rects are padded and clamped to the frame`() {
        val padded = padRect(NRect(0.4f, 0.4f, 0.6f, 0.6f))
        assertEquals(0.35f, padded.left, 1e-6f)
        assertEquals(0.65f, padded.right, 1e-6f)
        val clamped = padRect(NRect(0.0f, 0.0f, 1.0f, 1.0f))
        assertEquals(0.0f, clamped.left, 1e-6f)
        assertEquals(1.0f, clamped.bottom, 1e-6f)
    }

    /**
     * The eviction gap is in SOURCE time (sampled pts), not wall clock — a track is over when the video
     * has moved on past it, however fast or slow the pass is running.
     */
    @Test
    fun `a track goes stale only after the eviction gap of source time`() {
        assertFalse(isStale(lastSeenMs = 10_000L, nowMs = 10_000L))
        assertFalse(isStale(lastSeenMs = 10_000L, nowMs = 11_900L))
        assertTrue(isStale(lastSeenMs = 10_000L, nowMs = 12_000L))
        assertTrue(isStale(lastSeenMs = 10_000L, nowMs = 30_000L))
    }
}
