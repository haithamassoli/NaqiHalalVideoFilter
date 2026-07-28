package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that moved out of [FaceTracker]'s old end-of-pass `finish()` when Phase 1 of
 * `long-film-plan.md` made tracks vote and evict during the pass. Both are pure, so they are testable
 * without ML Kit or a Context; the eviction plumbing around them is device-verified, not unit-tested.
 */
class FaceTrackerLogicTest {

    private fun track(gender: Gender, vararg ptsMs: Long) = FaceTrack(id = 1).apply {
        this.gender = gender
        ptsMs.forEach { samples += FaceSample(it, NRect(0.4f, 0.4f, 0.6f, 0.6f), frontal = true) }
    }

    @Test
    fun `female track is censored and its span is padded by half a sample gap`() {
        val edl = edlFor(track(Gender.FEMALE, 1_000L, 1_100L, 1_200L), blurUnknownFaces = false)!!
        assertEquals(950L, edl.startMs)
        assertEquals(1_250L, edl.endMs)
        assertEquals(3, edl.keyframes.size)
    }

    @Test
    fun `male track is never censored`() {
        assertNull(edlFor(track(Gender.MALE, 1_000L), blurUnknownFaces = false))
        assertNull(edlFor(track(Gender.MALE, 1_000L), blurUnknownFaces = true))
    }

    @Test
    fun `unknown track is censored only when blurUnknownFaces is on`() {
        assertNull(edlFor(track(Gender.UNKNOWN, 1_000L), blurUnknownFaces = false))
        assertTrue(edlFor(track(Gender.UNKNOWN, 1_000L), blurUnknownFaces = true) != null)
    }

    /** A track evicted before it ever collected a sample would otherwise blow up on samples.first(). */
    @Test
    fun `sampleless track emits nothing rather than throwing`() {
        assertNull(edlFor(track(Gender.FEMALE), blurUnknownFaces = true))
    }

    @Test
    fun `span start clamps at zero for a track that begins in the first frames`() {
        val edl = edlFor(track(Gender.FEMALE, 0L, 100L), blurUnknownFaces = false)!!
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

    // --- the two triggers ---

    @Test
    fun `a track still being seen with room for crops just waits`() {
        assertEquals(TrackAction.WAIT, trackAction(voted = false, cropCount = 0, lastSeenMs = 1_000L, nowMs = 1_000L))
        assertEquals(TrackAction.WAIT, trackAction(voted = false, cropCount = 4, lastSeenMs = 1_000L, nowMs = 1_500L))
    }

    /** Crops full ⇒ vote now and free the bitmaps; the track stays live because its samples still grow. */
    @Test
    fun `a full crop budget triggers the vote without evicting`() {
        assertEquals(TrackAction.VOTE, trackAction(voted = false, cropCount = 5, lastSeenMs = 1_000L, nowMs = 1_000L))
    }

    /** Already voted and still on screen: nothing to do. Re-voting would downgrade the verdict. */
    @Test
    fun `an already-voted live track is never voted again`() {
        assertEquals(TrackAction.WAIT, trackAction(voted = true, cropCount = 0, lastSeenMs = 1_000L, nowMs = 1_500L))
        assertEquals(TrackAction.WAIT, trackAction(voted = true, cropCount = 5, lastSeenMs = 1_000L, nowMs = 1_500L))
    }

    /** Eviction outranks voting — the evict branch votes on the way out, so a full-crop stale track must not stall. */
    @Test
    fun `going unseen evicts, whether or not the crop budget filled`() {
        assertEquals(TrackAction.EVICT, trackAction(voted = false, cropCount = 0, lastSeenMs = 1_000L, nowMs = 3_000L))
        assertEquals(TrackAction.EVICT, trackAction(voted = false, cropCount = 5, lastSeenMs = 1_000L, nowMs = 3_000L))
        assertEquals(TrackAction.EVICT, trackAction(voted = true, cropCount = 0, lastSeenMs = 1_000L, nowMs = 3_000L))
    }
}
