package com.haithamassoli.naqi.render

import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.edl.BodyMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskRasterTest {
    // An upright 8x8 grid over the whole frame with only the top-left cell (0..0.125) set: grown by one
    // cell and sampled bilinearly it stays solid to 0.1875, is half at 0.25 and gone by 0.3125.
    private val mask = BodyMask(NRect(0f, 0f, 1f, 1f), 8, 8, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))

    @Test fun aSilhouetteLandsInTheSameUprightCornerInEveryRotation() {
        val w = 160
        val h = 90
        for (rotation in listOf(0, 90, 180, 270)) {
            val out = ByteArray(w * h)
            rasterizeMasks(listOf(mask), rotation, w, h, out)
            fun at(u: Float, v: Float): Int {
                val (sx, sy) = when (rotation) { 90 -> v to 1 - u; 180 -> 1 - u to 1 - v; 270 -> 1 - v to u; else -> u to v }
                val c = (sx * w).toInt().coerceAtMost(w - 1)
                val r = ((1 - sy) * h).toInt().coerceAtMost(h - 1) // GL rows run bottom-up
                return out[r * w + c].toInt() and 255
            }
            assertEquals("$rotation", 255, at(.08f, .08f))
            assertEquals("$rotation", 255, at(.18f, .12f)) // the model's own cell edge stays solid
            val ramp = at(.25f, .1f)
            assertTrue("$rotation ramp $ramp", ramp in 100..160)
            for ((u, v) in listOf(.8f to .8f, .8f to .1f, .1f to .8f, .5f to .5f, .34f to .1f)) {
                assertEquals("$rotation ($u, $v)", 0, at(u, v))
            }
        }
    }

    @Test fun overlappingSilhouettesUnionWithMax() {
        val out = ByteArray(16 * 16)
        val other = BodyMask(NRect(0f, 0f, 1f, 1f), 8, 8, ByteArray(8).also { it[7] = 0x80.toByte() })
        rasterizeMasks(listOf(mask, other), 0, 16, 16, out)
        assertEquals(255, out[15 * 16].toInt() and 255) // top-left (GL top row)
        assertEquals(255, out[15].toInt() and 255)      // bottom-right (GL bottom row)
    }
}
