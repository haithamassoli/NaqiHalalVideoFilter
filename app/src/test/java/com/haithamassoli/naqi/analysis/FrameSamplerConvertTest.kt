package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * plan-v2 §5.1/§5.2. The two pixel walks that replaced the 640-px ARGB bitmap: [FrameSampler.packNv21]
 * (decoder planes -> ML Kit's NV21) and [FrameSampler.convertToTensor] (decoder planes -> the gate's
 * 224² NCHW float tensor). Both take primitives rather than an `android.media.Image`, so this needs no
 * Robolectric.
 *
 * It replaces `ConvertRowsTest`, whose subject (`convertRows`, the ARGB walk) was deleted with the
 * bitmap. Its two load-bearing assertions are carried over unchanged in substance:
 *
 * - **the BT.601 arithmetic**, which the gate's QA-tuned strictness thresholds sit on top of, and
 * - **the rotation indexing**, which decides where every EDL rect lands. That one matters more than
 *   it did: rotation used to be applied to the bitmap BOTH consumers saw, and is now applied only
 *   here, with ML Kit rotating its own copy from the `rotationDegrees` [FrameSampler.uprightSize]
 *   describes. If the two disagreed, a rotated video would be blurred in the wrong place.
 *
 * Planes are built with deliberately awkward geometry — `rowStride > width`, a non-zero base, and
 * chroma in both the planar (pixelStride 1) and semi-planar (pixelStride 2, one shared buffer)
 * layouts a `COLOR_FormatYUV420Flexible` decoder can hand over.
 */
class FrameSamplerConvertTest {

    // --- upright size: the coordinate space every EDL rect is normalized against ---

    @Test
    fun `upright size swaps the axes for 90 and 270 only`() {
        assertEquals(1920 to 1080, FrameSampler.uprightSize(1920, 1080, 0))
        assertEquals(1080 to 1920, FrameSampler.uprightSize(1920, 1080, 90))
        assertEquals(1920 to 1080, FrameSampler.uprightSize(1920, 1080, 180))
        assertEquals(1080 to 1920, FrameSampler.uprightSize(1920, 1080, 270))
    }

    @Test
    fun `upright size normalizes negative and overflow degrees`() {
        assertEquals(FrameSampler.uprightSize(640, 360, 90), FrameSampler.uprightSize(640, 360, -270))
        assertEquals(FrameSampler.uprightSize(640, 360, 90), FrameSampler.uprightSize(640, 360, 450))
    }

    // --- the gate tensor walk ---

    private fun tensor(side: Int) = ByteBuffer.allocateDirect(3 * side * side * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * A 2x2 luma with a distinct value per corner and neutral chroma (U = V = 128 ⇒ r = g = b = y), so
     * the R plane of the tensor reads back as the source luma and the rotation cases are legible.
     */
    private fun rotate(rotation: Int): IntArray {
        val yRow = 3 // padded rows
        val base = 5
        val y = ByteBuffer.allocate(base + yRow * 2)
        y.put(base + 0, 10); y.put(base + 1, 20)             // display row 0: A B
        y.put(base + yRow + 0, 30); y.put(base + yRow + 1, 40) // display row 1: C D
        val c = ByteBuffer.allocate(4).also { for (i in 0 until 4) it.put(i, 128.toByte()) }
        val out = tensor(2)
        FrameSampler.convertToTensor(
            y, yRow, 1, base, c, 2, 1, 0, c, 2, 1, 0,
            rotation, IntArray(2) { it }, IntArray(2) { it }, out,
        )
        // R plane only: 4 upright pixels, row-major, back in 0..255.
        return IntArray(4) { Math.round(out.get(it) * 255f) }
    }

    /**
     * Android's rotation metadata means "rotate the stored frame this many degrees CLOCKWISE to
     * display it", so upright is the display orientation. Stored `[[A,B],[C,D]]` rotated 90 cw is
     * `[[C,A],[D,B]]`; 180 is `[[D,C],[B,A]]`; 270 is `[[B,D],[A,C]]`.
     */
    @Test
    fun `rotation maps upright output back to the right stored pixel`() {
        assertArrayEquals(intArrayOf(10, 20, 30, 40), rotate(0))
        assertArrayEquals(intArrayOf(30, 10, 40, 20), rotate(90))
        assertArrayEquals(intArrayOf(40, 30, 20, 10), rotate(180))
        assertArrayEquals(intArrayOf(20, 40, 10, 30), rotate(270))
    }

    /**
     * The colour anchor carried over from `ConvertRowsTest`: BT.601 full range, U and V the right way
     * round, and the /255 scale — a solid frame of Y=81 U=90 V=240 is red (238, 15, 13). The blue
     * value pins the `shr` on a negative flooring toward -inf: 81 + ((1815 * -38) shr 10) = 13, not
     * the 14 a truncating divide would give.
     */
    @Test
    fun `tensor is BT601 full range, scaled by 255, laid out R then G then B`() {
        val y = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 81) }
        val u = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 90.toByte()) }
        val v = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 240.toByte()) }
        val out = tensor(2)
        FrameSampler.convertToTensor(
            y, 2, 1, 0, u, 2, 1, 0, v, 2, 1, 0,
            0, IntArray(2) { it }, IntArray(2) { it }, out,
        )
        for (i in 0 until 4) {
            assertEquals("red[$i]", 238f / 255f, out.get(i), 1e-6f)
            assertEquals("green[$i]", 15f / 255f, out.get(4 + i), 1e-6f)
            assertEquals("blue[$i]", 13f / 255f, out.get(8 + i), 1e-6f)
        }
    }

    /** Absolute writes only: a reused buffer (and ORT's tensor over it) must never see its position move. */
    @Test
    fun `tensor fill leaves the buffer position untouched`() {
        val y = ByteBuffer.allocate(16)
        val c = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 128.toByte()) }
        val out: FloatBuffer = tensor(2)
        FrameSampler.convertToTensor(
            y, 2, 1, 0, c, 2, 1, 0, c, 2, 1, 0,
            0, IntArray(2) { it }, IntArray(2) { it }, out,
        )
        assertEquals(0, out.position())
        assertEquals(3 * 2 * 2, out.limit())
    }

    // --- the NV21 repack ---

    private val srcW = 8
    private val srcH = 4
    private val yRow = 10 // padded rows, as a real decoder hands them over
    private val cRow = 9
    private val base = 3  // planes handed over at a non-zero position()

    /** Luma carries its own coordinates so a wrong index is visible; chroma carries its column. */
    private fun luma(): ByteBuffer = ByteBuffer.allocate(base + yRow * srcH).also { buf ->
        for (sy in 0 until srcH) for (sx in 0 until srcW) buf.put(base + sy * yRow + sx, (10 * sy + sx).toByte())
    }

    private fun uValue(cx: Int) = (10 + cx).toByte()
    private fun vValue(cx: Int) = (60 + cx).toByte()

    /** Fully planar chroma: separate buffers, pixelStride 1. */
    private fun planarChroma(): Pair<ByteBuffer, ByteBuffer> {
        val u = ByteBuffer.allocate(base + cRow * (srcH / 2))
        val v = ByteBuffer.allocate(base + cRow * (srcH / 2))
        for (cy in 0 until srcH / 2) for (cx in 0 until srcW / 2) {
            u.put(base + cy * cRow + cx, uValue(cx))
            v.put(base + cy * cRow + cx, vValue(cx))
        }
        return u to v
    }

    /** Semi-planar NV12: one buffer, U at even offsets and V at odd, pixelStride 2 — the Qualcomm case. */
    private fun semiPlanarChroma(): ByteBuffer {
        val uv = ByteBuffer.allocate(base + cRow * (srcH / 2) + 1)
        for (cy in 0 until srcH / 2) for (cx in 0 until srcW / 2) {
            uv.put(base + cy * cRow + cx * 2, uValue(cx))
            uv.put(base + cy * cRow + cx * 2 + 1, vValue(cx))
        }
        return uv
    }

    private val sxMap = intArrayOf(0, 2, 4, 6) // 2x downscale
    private val syMap = intArrayOf(0, 2)

    private fun packed(planar: Boolean): ByteArray {
        val y = luma()
        val out = ByteBuffer.allocateDirect(sxMap.size * syMap.size * 3 / 2)
        if (planar) {
            val (u, v) = planarChroma()
            FrameSampler.packNv21(y, yRow, 1, base, u, cRow, 1, base, v, cRow, 1, base, sxMap, syMap, out)
        } else {
            val uv = semiPlanarChroma()
            FrameSampler.packNv21(y, yRow, 1, base, uv, cRow, 2, base, uv, cRow, 2, base + 1, sxMap, syMap, out)
        }
        return ByteArray(out.capacity()) { out.get(it) }
    }

    @Test
    fun `luma is a nearest-neighbour gather at the map indices`() {
        val out = packed(planar = true)
        for (oy in syMap.indices) for (ox in sxMap.indices) {
            assertEquals(
                "luma[$ox,$oy]",
                (10 * syMap[oy] + sxMap[ox]).toByte(),
                out[oy * sxMap.size + ox],
            )
        }
    }

    /**
     * NV21, not NV12: the half-resolution plane is V,U pairs. Getting this backwards swaps red and
     * blue in everything ML Kit sees, which face detection survives and nothing else would catch.
     */
    @Test
    fun `chroma is written V then U, one pair per 2x2 output block`() {
        val out = packed(planar = true)
        val plane = sxMap.size * syMap.size
        // Output is 4x2, so one chroma row of two pairs, sampled at source chroma columns 0 and 2.
        assertEquals("V0", vValue(0), out[plane])
        assertEquals("U0", uValue(0), out[plane + 1])
        assertEquals("V1", vValue(2), out[plane + 2])
        assertEquals("U1", uValue(2), out[plane + 3])
        assertEquals(plane * 3 / 2, out.size)
    }

    /** One repack, both decoder layouts: the pixel strides are already parameters of the read. */
    @Test
    fun `planar and semi-planar chroma pack to identical NV21`() {
        assertArrayEquals(packed(planar = true), packed(planar = false))
    }
}
