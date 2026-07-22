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

    private companion object {
        const val EPS = 1e-5f
    }
}
