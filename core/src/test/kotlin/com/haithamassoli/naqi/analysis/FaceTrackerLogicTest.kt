package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions that moved out of [FaceTracker]'s old end-of-pass `finish()` when Phase 1 of
 * `long-film-plan.md` made tracks evict during the pass, plus the gender verdict plan-censor-who §4.2
 * put back. All pure, so they are testable without ML Kit or a Context; the eviction plumbing and the
 * ORT vote around them are device-verified, not unit-tested.
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

    // --- The gender verdict (plan-censor-who §4.2/§6) ---------------------------------------------

    /**
     * **Load-bearing fail-safe: a track with no votes cast is CENSORED.** Every path that produces no
     * vote lands here — crop below the size floor, model not installed, every classification abstained,
     * VOTE_CAP spent on abstentions. If this test ever flips, Women/Men silently start EXPOSING every
     * face the classifier could not read, which is the one failure this feature must not have (§8).
     */
    @Test
    fun `zero votes censors in every selective mode`() {
        assertTrue(shouldCensor(femaleVotes = 0, maleVotes = 0, who = FilterOps.WOMEN))
        assertTrue(shouldCensor(femaleVotes = 0, maleVotes = 0, who = FilterOps.MEN))
    }

    /** A tie is uncertainty too, and uncertainty resolves toward covering. */
    @Test
    fun `a tied vote censors in every selective mode`() {
        assertTrue(shouldCensor(femaleVotes = 2, maleVotes = 2, who = FilterOps.WOMEN))
        assertTrue(shouldCensor(femaleVotes = 2, maleVotes = 2, who = FilterOps.MEN))
    }

    @Test
    fun `everyone censors whatever the counters say`() {
        assertTrue(shouldCensor(0, 0, FilterOps.EVERYONE))
        assertTrue(shouldCensor(5, 0, FilterOps.EVERYONE))
        assertTrue(shouldCensor(0, 5, FilterOps.EVERYONE))
    }

    @Test
    fun `none censors nothing whatever the counters say`() {
        assertFalse(shouldCensor(0, 0, FilterOps.NONE))
        assertFalse(shouldCensor(5, 0, FilterOps.NONE))
    }

    /** Women mode spares a track only when male votes STRICTLY win. */
    @Test
    fun `women mode censors the female majority and spares the male one`() {
        assertTrue(shouldCensor(femaleVotes = 4, maleVotes = 1, who = FilterOps.WOMEN))
        assertFalse(shouldCensor(femaleVotes = 1, maleVotes = 4, who = FilterOps.WOMEN))
    }

    /** Men mode is the exact mirror — same rule, other class. */
    @Test
    fun `men mode censors the male majority and spares the female one`() {
        assertTrue(shouldCensor(femaleVotes = 1, maleVotes = 4, who = FilterOps.MEN))
        assertFalse(shouldCensor(femaleVotes = 4, maleVotes = 1, who = FilterOps.MEN))
    }

    /**
     * §4.3's whole landing point: a spared track produces no span, so nothing downstream — EDL,
     * renderer, CensorEffect — needs to know the vote happened.
     */
    @Test
    fun `edlFor emits a span for a censored track and nothing for a spared one`() {
        val male = track(1_000L, 1_100L).apply { maleVotes = 3 }
        assertNull(edlFor(male, FilterOps.WOMEN))
        assertNotNull(edlFor(male, FilterOps.MEN))
        assertNotNull(edlFor(male, FilterOps.EVERYONE))
        assertEquals(950L, edlFor(male, FilterOps.MEN)!!.startMs)
    }
}
