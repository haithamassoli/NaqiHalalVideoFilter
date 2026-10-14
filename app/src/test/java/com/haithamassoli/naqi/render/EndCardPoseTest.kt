package com.haithamassoli.naqi.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end card's timeline, checked against the approved mockup's `drawFrame` at its key moments. */
class EndCardPoseTest {

    @Test fun startsAsTheLastVideoFrameWithNothingDrawn() {
        val p = EndCardPose(0f)
        assertEquals(0f, p.fade, 0f)
        assertEquals(0f, p.arc, 0f)
        assertEquals(0f, p.dropAlpha, 0f)
        assertEquals(0f, p.glow, 0f)
        assertEquals(0f, p.arabic, 0f)
        assertEquals(0f, p.english, 0f)
    }

    @Test fun everythingHasSettledWellBeforeTheTwoSecondsAreUp() {
        for (t in listOf(1.3f, 1.97f, 2f)) {
            val p = EndCardPose(t)
            assertEquals(1f, p.fade, 0f)
            assertEquals(1f, p.arc, 1e-6f)
            assertEquals(1f, p.dropAlpha, 0f)
            assertEquals(0f, p.dropOffset, 1e-5f)
            assertEquals(1f, p.arabic, 1e-6f)
            assertEquals(1f, p.english, 1e-6f)
        }
    }

    @Test fun theDropFallsInAndOvershootsBeforeItLands() {
        assertEquals(-18f, EndCardPose(0.35f).dropOffset, 1e-5f) // starts 18 mark units up
        assertEquals(1f, EndCardPose(0.47f).dropAlpha, 0f) // fully opaque a third of the way in
        // outBack passes 1 on the way, so the drop dips below its resting place before settling.
        val dips = (0..100).map { EndCardPose(0.35f + 0.35f * it / 100f).dropOffset }
        assertTrue(dips.any { it > 0.5f })
    }

    @Test fun easingEndpoints() {
        assertEquals(0f, outBack(0f), 1e-6f)
        assertEquals(1f, outBack(1f), 1e-6f)
        assertEquals(0f, outCubic(0f), 0f)
        assertEquals(1f, outCubic(1f), 0f)
        assertEquals(0f, seg(-1f, 0f, 1f), 0f)
        assertEquals(1f, seg(5f, 0f, 1f), 0f)
        assertEquals(0.5f, seg(0.1f, 0.05f, 0.15f), 1e-6f)
    }
}
