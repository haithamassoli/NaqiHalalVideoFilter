package com.haithamassoli.naqi.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutPointsTest {
    private val s = 1_000_000L
    private fun every(stepS: Long, untilS: Long) = (0..untilS step stepS).map { it * s }

    @Test fun shortVideoIsOnePart() = assertEquals(listOf(0L), cutPoints(every(2, 80), 80 * s, 90 * s))

    @Test fun cutsOnLatestKeyframeWithinLimit() {
        // Keyframes every 4 s: 88 s is the last one inside 90 s, then 176 s.
        assertEquals(listOf(0L, 88 * s, 176 * s), cutPoints(every(4, 200), 200 * s, 90 * s))
    }

    @Test fun exactMultipleNeedsNoExtraPart() = assertEquals(listOf(0L, 90 * s), cutPoints(every(10, 180), 180 * s, 90 * s))

    @Test fun keyframeGapLongerThanLimitRunsLongInsteadOfLooping() {
        assertEquals(listOf(0L, 120 * s), cutPoints(listOf(0L, 120 * s), 200 * s, 90 * s))
    }

    @Test fun noKeyframesAfterStartStops() = assertEquals(listOf(0L), cutPoints(listOf(0L), 500 * s, 90 * s))

    @Test fun requiresSplittingFollowsTheLimit() {
        val whatsapp = PublishPreset.ALL.first { it.id == "whatsapp-status" }
        assertFalse(whatsapp.requiresSplitting(90_000))
        assertTrue(whatsapp.requiresSplitting(90_001))
        assertFalse(PublishPreset.ALL.first { it.id == "tiktok" }.requiresSplitting(3_600_000))
    }
}
