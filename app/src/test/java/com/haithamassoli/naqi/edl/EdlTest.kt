package com.haithamassoli.naqi.edl

import com.haithamassoli.naqi.analysis.NRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for EDL precedence, keyframe interpolation, and JSON round-trip. */
class EdlTest {
    private fun r(l: Float, t: Float, rt: Float, b: Float) = NRect(l, t, rt, b)

    // Interpolated rects can't be compared with data-class equality (float rounding), so compare per field.
    private fun assertRegions(expected: List<NRect>, actual: List<NRect>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].left, actual[i].left, EPS)
            assertEquals(expected[i].top, actual[i].top, EPS)
            assertEquals(expected[i].right, actual[i].right, EPS)
            assertEquals(expected[i].bottom, actual[i].bottom, EPS)
        }
    }

    @Test fun fullFrameInsideCensorIntervalInclusive() {
        val edl = Edl(listOf(1000L..2000L), emptyList())
        assertTrue(edl.fullFrameAt(1000))
        assertTrue(edl.fullFrameAt(1500))
        assertTrue(edl.fullFrameAt(2000))
        assertFalse(edl.fullFrameAt(999))
        assertFalse(edl.fullFrameAt(2001))
    }

    @Test fun precedenceFullFrameSuppressesRegions() {
        val track = FaceTrackEdl(0, 5000, listOf(0L to r(0f, 0f, 0.2f, 0.2f), 5000L to r(0f, 0f, 0.2f, 0.2f)))
        val edl = Edl(listOf(1000L..2000L), listOf(track))
        assertRegions(emptyList(), edl.regionsAt(1500))          // inside censor → empty (precedence)
        assertRegions(listOf(r(0f, 0f, 0.2f, 0.2f)), edl.regionsAt(3000)) // outside censor → region
    }

    @Test fun keyframeInterpolationMidpoint() {
        val track = FaceTrackEdl(0, 1000, listOf(0L to r(0f, 0f, 0.2f, 0.2f), 1000L to r(0.4f, 0.4f, 0.6f, 0.6f)))
        val edl = Edl(emptyList(), listOf(track))
        assertRegions(listOf(r(0.2f, 0.2f, 0.4f, 0.4f)), edl.regionsAt(500))
    }

    @Test fun keyframeEdgeClamping() {
        val track = FaceTrackEdl(0, 2000, listOf(500L to r(0.1f, 0.1f, 0.2f, 0.2f), 1500L to r(0.5f, 0.5f, 0.6f, 0.6f)))
        val edl = Edl(emptyList(), listOf(track))
        assertRegions(listOf(r(0.1f, 0.1f, 0.2f, 0.2f)), edl.regionsAt(100))  // before first kf → first
        assertRegions(listOf(r(0.5f, 0.5f, 0.6f, 0.6f)), edl.regionsAt(1900)) // after last kf → last
        assertRegions(listOf(r(0.3f, 0.3f, 0.4f, 0.4f)), edl.regionsAt(1000)) // midpoint
    }

    @Test fun regionsEmptyOutsideTrackSpan() {
        val track = FaceTrackEdl(1000, 2000, listOf(1000L to r(0f, 0f, 0.2f, 0.2f), 2000L to r(0f, 0f, 0.2f, 0.2f)))
        val edl = Edl(emptyList(), listOf(track))
        assertRegions(emptyList(), edl.regionsAt(500))
        assertRegions(emptyList(), edl.regionsAt(2500))
    }

    @Test fun multipleActiveTracksYieldMultipleRegions() {
        val a = FaceTrackEdl(0, 1000, listOf(0L to r(0f, 0f, 0.1f, 0.1f), 1000L to r(0f, 0f, 0.1f, 0.1f)))
        val b = FaceTrackEdl(0, 1000, listOf(0L to r(0.5f, 0.5f, 0.6f, 0.6f), 1000L to r(0.5f, 0.5f, 0.6f, 0.6f)))
        val edl = Edl(emptyList(), listOf(a, b))
        assertRegions(listOf(r(0f, 0f, 0.1f, 0.1f), r(0.5f, 0.5f, 0.6f, 0.6f)), edl.regionsAt(500))
    }

    @Test fun jsonRoundTrip() {
        val edl = Edl(
            listOf(0L..1700L, 2500L..4000L),
            listOf(
                FaceTrackEdl(0, 1000, listOf(0L to r(0.1f, 0.2f, 0.3f, 0.4f), 1000L to r(0.15f, 0.25f, 0.35f, 0.45f))),
                FaceTrackEdl(2000, 3000, listOf(2000L to r(0.5f, 0.5f, 0.7f, 0.7f))),
            ),
        )
        assertEquals(edl, Edl.fromJson(edl.toJson()))
    }

    @Test fun jsonRoundTripEmpty() {
        val edl = Edl(emptyList(), emptyList())
        assertEquals(edl, Edl.fromJson(edl.toJson()))
    }

    // --- whole-frame mode (plan-whole-frame-blur §3) ---

    private fun track(s: Long, e: Long) = FaceTrackEdl(s, e, listOf(s to r(0.1f, 0.1f, 0.2f, 0.2f)))

    @Test fun mergeRangesPassesThroughTrivialInput() {
        assertEquals(emptyList<LongRange>(), mergeRanges(emptyList()))
        assertEquals(listOf(5L..9L), mergeRanges(listOf(5L..9L)))
    }

    @Test fun mergeRangesSortsAndMergesOverlapping() {
        assertEquals(
            listOf(0L..2500L, 9000L..9500L),
            mergeRanges(listOf(9000L..9500L, 1000L..2500L, 0L..1500L), bridgeMs = 0L),
        )
    }

    /** The bridge is the anti-strobe rule: a gap of exactly BRIDGE_MS still merges, one ms more does not. */
    @Test fun mergeRangesBridgesGapsUpToBridgeMs() {
        assertEquals(listOf(0L..1000L, 1000L + BRIDGE_MS + 1..2000L),
            mergeRanges(listOf(0L..1000L, 1000L + BRIDGE_MS + 1..2000L)))
        assertEquals(listOf(0L..2000L), mergeRanges(listOf(0L..1000L, 1000L + BRIDGE_MS..2000L)))
    }

    /** A contained range must not extend the merged end backwards. */
    @Test fun mergeRangesKeepsOuterEndWhenNextIsContained() {
        assertEquals(listOf(0L..5000L), mergeRanges(listOf(0L..5000L, 1000L..2000L), bridgeMs = 0L))
    }

    @Test fun promotedTrackBlanksTheWholeFrameAndSuppressesRegions() {
        val tracks = listOf(track(1000, 2000))
        val edl = Edl(promoteFacesToFullFrame(emptyList(), tracks), tracks)
        assertTrue(edl.fullFrameAt(1500))
        assertEquals(emptyList<NRect>(), edl.regionsAt(1500)) // precedence: no rects under full frame
        assertFalse(edl.fullFrameAt(2500))
    }

    /** Flag off is byte-for-byte today's behaviour: the rect is still what gets censored. */
    @Test fun withoutPromotionTheRectStillCensors() {
        val tracks = listOf(track(1000, 2000))
        val edl = Edl(emptyList(), tracks)
        assertFalse(edl.fullFrameAt(1500))
        assertEquals(1, edl.regionsAt(1500).size)
    }

    /** Gate intervals and face spans collapse together — the reason fullFrameAt stays a short scan. */
    @Test fun promotionMergesGateIntervalsWithFaceSpans() {
        assertEquals(
            listOf(0L..3000L),
            promoteFacesToFullFrame(listOf(0L..1200L), listOf(track(1100, 2000), track(2100, 3000))),
        )
    }

    /** A one-sample track is a 100 ms span: 2-3 frames of the whole picture blinking. Never promote it. */
    @Test fun isolatedBlipIsNotPromotedAndKeepsItsRect() {
        val tracks = listOf(track(5000, 5100))
        val edl = Edl(promoteFacesToFullFrame(emptyList(), tracks), tracks)
        assertFalse(edl.fullFrameAt(5050))
        assertEquals(1, edl.regionsAt(5050).size) // still censored — by its own rect
    }

    /** The floor runs after the merge, so blips that bridge into a long enough span survive together. */
    @Test fun blipsThatMergePastTheFloorAreKept() {
        val spans = promoteFacesToFullFrame(emptyList(), listOf(track(5000, 5100), track(5400, 5500)))
        assertEquals(listOf(5000L..5500L), spans)
    }

    /**
     * Replays the real S23 EDL (tv1.webm, 643 s, whole-frame run): the three 100 ms and one 300 ms
     * spans go, the 600/701 ms ones stay, and nothing under the floor survives.
     */
    @Test fun measuredS23SpansLoseOnlyTheBlips() {
        val measured = listOf(
            308558L..308658L, 311161L..311761L, 485969L..486069L,
            581564L..581664L, 585368L..585668L, 587170L..587871L, 619319L..638903L,
        )
        val kept = mergeRanges(measured).filter { it.last - it.first >= MIN_FULL_MS }
        assertEquals(listOf(311161L..311761L, 587170L..587871L, 619319L..638903L), kept)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
