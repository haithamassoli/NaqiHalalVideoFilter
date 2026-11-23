package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class PersonDetectorTest {
    private val out = FloatBuffer.allocate(116 * 8400)
    private fun anchor(i: Int, cx: Float, cy: Float, w: Float, h: Float, person: Float) {
        for ((a, v) in listOf(cx, cy, w, h, person).withIndex()) out.put(a * 8400 + i, v)
    }

    @Test fun keepsPersonAnchorsAfterClassFilterAndNms() {
        anchor(0, 200f, 300f, 100f, 200f, 0.9f)
        anchor(1, 205f, 300f, 100f, 200f, 0.8f) // same person, suppressed at IoU > 0.7
        anchor(2, 500f, 300f, 50f, 100f, 0.6f)
        anchor(3, 100f, 100f, 50f, 50f, 0.7f)
        out.put((4 + 3) * 8400 + 3, 0.75f)       // a dog outscores person there
        anchor(4, 300f, 500f, 50f, 50f, 0.25f)   // at the floor, not above it
        val kept = PersonDetector.decode(out, null)
        assertEquals(listOf(NRect(150f, 200f, 250f, 400f), NRect(475f, 250f, 525f, 350f)), kept.map { it.first })
        assertTrue(kept.all { it.second == null })
    }

    @Test fun preciseModeSamplesThePrototypeInsideTheUprightBox() {
        anchor(0, 200f, 300f, 100f, 200f, 0.9f)
        out.put(84 * 8400, 1f) // first coefficient selects prototype 0
        val proto = FloatBuffer.allocate(32 * 160 * 160)
        // Prototype 0 is positive left of letterbox x 200 (prototype column 50).
        for (y in 0 until 160) for (x in 0 until 160) proto.put(y * 160 + x, if (x < 50) 1f else -1f)
        val (box, builder) = PersonDetector.decode(out, proto).single()
        assertEquals(NRect(150f, 200f, 250f, 400f), box)
        // A 360x640 portrait letterboxes at scale 1 with 140 px on the left: upright x 10..110.
        val upright = NRect(10f / 360, 200f / 640, 110f / 360, 400f / 640)
        val mask = builder!!(upright, 360, 640, 1f, 140, 0)
        assertEquals(16, mask.width)
        assertEquals(32, mask.height)
        assertEquals(upright, mask.rect)
        for (y in listOf(0, 31)) assertEquals(List(8) { true } + List(8) { false }, (0 until 16).map { mask[it, y] })
        assertTrue(!mask[16, 0] && !mask[-1, 0])
        assertNull(PersonDetector.decode(out, null).single().second)
    }
}
