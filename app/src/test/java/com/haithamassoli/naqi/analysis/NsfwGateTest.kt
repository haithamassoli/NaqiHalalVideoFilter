package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the strictness table, the delta firing rule, and hysteresis merging. */
class NsfwGateTest {
    // NSFW_CLASSES index order: drawings=0, hentai=1, neutral=2, porn=3, sexy=4.
    private fun probs(drawings: Float, hentai: Float, neutral: Float, porn: Float, sexy: Float) =
        floatArrayOf(drawings, hentai, neutral, porn, sexy)

    @Test fun thresholdEndpointsAndMidpointPerClass() {
        // s=0 (t0 column)
        assertEquals(0.50f, NsfwGate.thr(0, 0), EPS) // drawings
        assertEquals(1.00f, NsfwGate.thr(1, 0), EPS) // hentai
        assertEquals(0.30f, NsfwGate.thr(2, 0), EPS) // neutral
        assertEquals(0.75f, NsfwGate.thr(3, 0), EPS) // porn
        assertEquals(0.90f, NsfwGate.thr(4, 0), EPS) // sexy
        // s=100 (t100 column)
        assertEquals(0.50f, NsfwGate.thr(0, 100), EPS)
        assertEquals(0.50f, NsfwGate.thr(1, 100), EPS)
        assertEquals(1.00f, NsfwGate.thr(2, 100), EPS)
        assertEquals(0.10f, NsfwGate.thr(3, 100), EPS)
        assertEquals(0.10f, NsfwGate.thr(4, 100), EPS)
        // s=50 (linear midpoint)
        assertEquals(0.50f, NsfwGate.thr(0, 50), EPS)
        assertEquals(0.75f, NsfwGate.thr(1, 50), EPS)
        assertEquals(0.65f, NsfwGate.thr(2, 50), EPS)
        assertEquals(0.425f, NsfwGate.thr(3, 50), EPS)
        assertEquals(0.50f, NsfwGate.thr(4, 50), EPS)
    }

    @Test fun strictnessClampsOutOfRange() {
        assertEquals(NsfwGate.thr(3, 0), NsfwGate.thr(3, -20), EPS)
        assertEquals(NsfwGate.thr(3, 100), NsfwGate.thr(3, 200), EPS)
    }

    @Test fun firesOnClearPorn() {
        // porn 0.6 clears its s=50 thr (0.425); every sfw margin is negative.
        assertTrue(NsfwGate.fires(probs(0.1f, 0.1f, 0.1f, 0.6f, 0.1f), 50))
    }

    @Test fun firesOnSexyClass() {
        // guards the sexy index (4): sexy 0.85 clears its s=50 thr (0.50).
        assertTrue(NsfwGate.fires(probs(0.0f, 0.0f, 0.1f, 0.0f, 0.85f), 50))
    }

    @Test fun neutralVetoAtLowStrictnessDisabledAt100() {
        // Constructed to isolate the veto (deliberately not a valid softmax): porn clears its
        // s=0 thr (margin +0.05), but neutral's low-strictness margin (+0.25) outranks it.
        val p = probs(0.0f, 0.0f, 0.55f, 0.80f, 0.0f)
        assertFalse(NsfwGate.fires(p, 0))  // sfwMax 0.25 > nsfwMax 0.05 → vetoed
        assertTrue(NsfwGate.fires(p, 100)) // neutral thr → 1.0 kills the veto → fires
    }

    @Test fun neutralContentDoesNotFire() {
        // realistic softmax dominated by neutral: nsfwMax < 0.
        assertFalse(NsfwGate.fires(probs(0.05f, 0.0f, 0.9f, 0.05f, 0.0f), 50))
    }

    @Test fun hentaiOnlyFiresAtHighStrictness() {
        // guards the hentai index (1): thr 1.0 at s=0 blocks it, thr 0.5 at s=100 lets it fire.
        val p = probs(0.0f, 0.9f, 0.05f, 0.0f, 0.0f)
        assertFalse(NsfwGate.fires(p, 0))
        assertTrue(NsfwGate.fires(p, 100))
    }

    @Test fun constantsExposed() {
        assertEquals(500L, NsfwGate.PRE_MS)
        assertEquals(1500L, NsfwGate.POST_MS)
    }

    @Test fun intervalsEmptyWhenNoFirings() {
        assertEquals(emptyList<LongRange>(), NsfwGate.intervals(emptyList(), 10_000L))
    }

    @Test fun intervalsMergeOverlapping() {
        // [500,2500] and [1000,3000] overlap.
        assertEquals(listOf(500L..3000L), NsfwGate.intervals(listOf(1000L, 1500L), 10_000L))
    }

    @Test fun intervalsMergeGapFreeAdjacent() {
        // 1000 → [500,2500]; 3001 → [2501,4501]; touching with no gap → merge.
        assertEquals(listOf(500L..4501L), NsfwGate.intervals(listOf(1000L, 3001L), 10_000L))
    }

    @Test fun intervalsKeepDisjoint() {
        // 1000 → [500,2500]; 3002 → [2502,4502]; 1 ms uncovered at 2501 → split.
        assertEquals(listOf(500L..2500L, 2502L..4502L), NsfwGate.intervals(listOf(1000L, 3002L), 10_000L))
    }

    @Test fun intervalsSortUnsortedInput() {
        assertEquals(listOf(500L..3000L), NsfwGate.intervals(listOf(1500L, 1000L), 10_000L))
    }

    @Test fun intervalsClampToZeroAndDuration() {
        assertEquals(listOf(0L..1700L), NsfwGate.intervals(listOf(200L), 10_000L))
        assertEquals(listOf(9300L..10_000L), NsfwGate.intervals(listOf(9800L), 10_000L))
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
