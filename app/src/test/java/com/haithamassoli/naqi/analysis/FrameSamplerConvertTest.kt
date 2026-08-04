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
 * plan-censor-who §4.1 added a third walk on top, tested at the bottom: [FrameSampler.cropToTensor]
 * (the NV21 above -> genderage's 96² crop), which is where the same rotation indexing is now applied a
 * second time, from the opposite direction.
 *
 * perf-plan-v4 A1 then SPLIT the gate walk in two — [FrameSampler.gatherGate] on the producer,
 * [FrameSampler.gateFromGathered] on the consumer — which turns [FrameSampler.convertToTensor] from a
 * production function into the executable specification of the pair. The equivalence test at the
 * bottom is this file's centrepiece: it is the only thing standing between the split and A4, the
 * variant that sampled the packed 640-px NV21 instead and measured 91.24 % censored-timeline recall.
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

    /** Luma AND chroma carry their own coordinates, so a wrong index in either axis is visible. */
    private fun luma(): ByteBuffer = ByteBuffer.allocate(base + yRow * srcH).also { buf ->
        for (sy in 0 until srcH) for (sx in 0 until srcW) buf.put(base + sy * yRow + sx, (10 * sy + sx).toByte())
    }

    // Both axes, deliberately: a column-only fixture leaves `cy` unpinned, so a gather that read the
    // wrong chroma ROW would return the same byte and every equivalence assertion below would still pass.
    private fun uValue(cx: Int, cy: Int = 0) = (10 + cx + 4 * cy).toByte()
    private fun vValue(cx: Int, cy: Int = 0) = (60 + cx + 4 * cy).toByte()

    /** Fully planar chroma: separate buffers, pixelStride 1. */
    private fun planarChroma(): Pair<ByteBuffer, ByteBuffer> {
        val u = ByteBuffer.allocate(base + cRow * (srcH / 2))
        val v = ByteBuffer.allocate(base + cRow * (srcH / 2))
        for (cy in 0 until srcH / 2) for (cx in 0 until srcW / 2) {
            u.put(base + cy * cRow + cx, uValue(cx, cy))
            v.put(base + cy * cRow + cx, vValue(cx, cy))
        }
        return u to v
    }

    /** Semi-planar NV12: one buffer, U at even offsets and V at odd, pixelStride 2 — the Qualcomm case. */
    private fun semiPlanarChroma(): ByteBuffer {
        val uv = ByteBuffer.allocate(base + cRow * (srcH / 2) + 1)
        for (cy in 0 until srcH / 2) for (cx in 0 until srcW / 2) {
            uv.put(base + cy * cRow + cx * 2, uValue(cx, cy))
            uv.put(base + cy * cRow + cx * 2 + 1, vValue(cx, cy))
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

    // --- the face crop (plan-censor-who §4.1) ---
    //
    // Same two risks as the gate walk, one step harder: this walk reads the NV21 the pack above WROTE
    // (unrotated, display-sized) but is addressed in UPRIGHT normalized coordinates, so a transposed
    // axis is invisible on a square frame and catastrophic on a real one. Every frame here is therefore
    // deliberately non-square, and the rotation expectation is built by rotating a grid rather than by
    // re-deriving the production case table.

    private val cropW = 16 // display (unrotated) frame — non-square on purpose
    private val cropH = 8

    /** Display-space NV21 exactly as [FrameSampler.packNv21] lays it out: luma plane, then V,U pairs. */
    private fun nv21(w: Int, h: Int, u: Int = 128, v: Int = 128, luma: (Int, Int) -> Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(w * h * 3 / 2)
        for (dy in 0 until h) for (dx in 0 until w) buf.put(dy * w + dx, luma(dx, dy).toByte())
        val plane = w * h
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            buf.put(plane + cy * w + cx * 2, v.toByte())
            buf.put(plane + cy * w + cx * 2 + 1, u.toByte())
        }
        return buf
    }

    private fun crop(side: Int = FrameSampler.CROP_SIDE) = ByteBuffer.allocateDirect(3 * side * side * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * Rotate a `[y][x]` grid 90 degrees CLOCKWISE — the direction Android's rotation metadata means, so
     * applying it `rotation/90` times to the stored frame gives what ML Kit calls upright. Written out
     * independently of [FrameSampler.cropToTensor]'s case table on purpose: that table is the subject.
     */
    private fun rotateCw(src: Array<IntArray>): Array<IntArray> {
        val h = src.size
        val w = src[0].size
        return Array(w) { y -> IntArray(h) { x -> src[h - 1 - x][y] } }
    }

    /** Output index -> upright pixel: the square crop's own geometry, restated so the test can predict it. */
    private fun cropMap(centre: Float, boxSide: Float, frameSide: Int): IntArray {
        val half = boxSide * 1.5f / 2f
        val step = half * 2f / FrameSampler.CROP_SIDE
        return IntArray(FrameSampler.CROP_SIDE) { (centre - half + it * step).toInt().coerceIn(0, frameSide - 1) }
    }

    /**
     * The assertion that catches a transposed axis. The display frame carries its own coordinates in
     * luma with neutral chroma (r = g = b = y), the upright frame is obtained by rotating that grid, and
     * every one of the 9 216 output pixels is then checked against the upright grid. A swapped dx/dy
     * survives the solid-colour test below and dies here.
     */
    @Test
    fun `crop maps upright box coordinates back to the right unrotated pixel`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val buf = nv21(cropW, cropH) { dx, dy -> dy * cropW + dx + 1 } // 1..128, unique per pixel
            var upright = Array(cropH) { dy -> IntArray(cropW) { dx -> dy * cropW + dx + 1 } }
            repeat(rotation / 90) { upright = rotateCw(upright) }
            val (uw, uh) = FrameSampler.uprightSize(cropW, cropH, rotation)
            // The sanity check the mapping rests on: at 90/270 the axes swap, so dx still indexes cropW.
            assertEquals("uprightW@$rotation", upright[0].size, uw)
            assertEquals("uprightH@$rotation", upright.size, uh)

            val out = crop()
            // Whole-frame box: the 1.5x square then overhangs the short axis, exercising the clamp too.
            FrameSampler.cropToTensor(buf, cropW, cropH, rotation, uw, uh, NRect(0f, 0f, 1f, 1f), out)

            // One SQUARE of side max(boxW, boxH) * 1.5 — so both axes share the long side, not their own.
            val boxSide = maxOf(uw, uh).toFloat()
            val uxMap = cropMap(uw / 2f, boxSide, uw)
            val uyMap = cropMap(uh / 2f, boxSide, uh)
            for (oy in 0 until FrameSampler.CROP_SIDE) for (ox in 0 until FrameSampler.CROP_SIDE) {
                assertEquals(
                    "r[$ox,$oy]@$rotation",
                    upright[uyMap[oy]][uxMap[ox]].toFloat(),
                    out.get(oy * FrameSampler.CROP_SIDE + ox),
                    1e-3f,
                )
            }
        }
    }

    /**
     * Colour anchor, and the one difference from the gate tensor: genderage's `input_std` is 1.0, so
     * these are 0..255 floats and NOT the gate's /255. Same BT.601 red (238, 15, 13) from Y=81 U=90
     * V=240, at every rotation, in all three planes.
     */
    @Test
    fun `crop is BT601 full range at 0 to 255, laid out R then G then B`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val buf = nv21(cropW, cropH, u = 90, v = 240) { _, _ -> 81 }
            val (uw, uh) = FrameSampler.uprightSize(cropW, cropH, rotation)
            val out = crop()
            FrameSampler.cropToTensor(buf, cropW, cropH, rotation, uw, uh, NRect(0.3f, 0.3f, 0.6f, 0.6f), out)
            val plane = FrameSampler.CROP_SIDE * FrameSampler.CROP_SIDE
            for (i in 0 until plane) {
                assertEquals("red[$i]@$rotation", 238f, out.get(i), 1e-3f)
                assertEquals("green[$i]@$rotation", 15f, out.get(plane + i), 1e-3f)
                assertEquals("blue[$i]@$rotation", 13f, out.get(2 * plane + i), 1e-3f)
            }
            assertEquals(0, out.position()) // absolute writes: the reused buffer never moves
        }
    }

    /**
     * A box hanging off the frame edge — a face half out of shot, which pass 1 produces constantly.
     * It must clamp to the edge pixel rather than throw, so on a solid frame the crop is still solid.
     */
    @Test
    fun `a box past the frame edge clamps instead of throwing`() {
        val buf = nv21(cropW, cropH, u = 90, v = 240) { _, _ -> 81 }
        val out = crop()
        FrameSampler.cropToTensor(buf, cropW, cropH, 90, cropH, cropW, NRect(0.85f, 0.9f, 1.3f, 1.4f), out)
        for (i in 0 until FrameSampler.CROP_SIDE * FrameSampler.CROP_SIDE) {
            assertEquals("red[$i]", 238f, out.get(i), 1e-3f)
        }
    }

    // --- the gate walk, split in two (perf-plan-v4 A1) ---
    //
    // The centrepiece. A1 moves the arithmetic half of the gate fill onto the consumer, so what the
    // model sees is now produced by [FrameSampler.gatherGate] followed by [FrameSampler.gateFromGathered]
    // rather than by [FrameSampler.convertToTensor] — which stays here as the reference the pair is
    // proved against. It reuses the decoder-plane builders above, at both chroma layouts, on the same
    // deliberately non-square 8x4 source, at all four rotations.
    //
    // Equality is EXACT, not a tolerance. Both paths run the same integer arithmetic, so any difference
    // at all is a defect — and a tolerance is precisely what would have let A4 through: its 640-px
    // resample moved single pixels, measured 91.24 % censored-timeline recall against a >= 99.20 % bar,
    // and under-censored 34.5 s of a 643 s clip.

    /** Whole-crop square stretch to 224², exactly as `toFrame` builds it (crop offset here is 0). */
    private val gx = IntArray(FrameSampler.GATE_SIDE) { it * srcW / FrameSampler.GATE_SIDE }
    private val gy = IntArray(FrameSampler.GATE_SIDE) { it * srcH / FrameSampler.GATE_SIDE }

    private fun gathered() =
        ByteBuffer.allocateDirect(3 * FrameSampler.GATE_SIDE * FrameSampler.GATE_SIDE)

    /** The reference tensor and the two-halves tensor over the same planes, in that order. */
    private fun bothPaths(planar: Boolean, rotation: Int): Pair<FloatBuffer, FloatBuffer> {
        val y = luma()
        val one = tensor(FrameSampler.GATE_SIDE)
        val two = tensor(FrameSampler.GATE_SIDE)
        val bytes = gathered()
        if (planar) {
            val (u, v) = planarChroma()
            FrameSampler.convertToTensor(
                y, yRow, 1, base, u, cRow, 1, base, v, cRow, 1, base, rotation, gx, gy, one,
            )
            FrameSampler.gatherGate(
                y, yRow, 1, base, u, cRow, 1, base, v, cRow, 1, base, rotation, gx, gy, bytes,
            )
        } else {
            val uv = semiPlanarChroma()
            FrameSampler.convertToTensor(
                y, yRow, 1, base, uv, cRow, 2, base, uv, cRow, 2, base + 1, rotation, gx, gy, one,
            )
            FrameSampler.gatherGate(
                y, yRow, 1, base, uv, cRow, 2, base, uv, cRow, 2, base + 1, rotation, gx, gy, bytes,
            )
        }
        FrameSampler.gateFromGathered(bytes, two)
        return one to two
    }

    /**
     * The test the split exists to pass: gather-then-convert is the one-pass walk, bit for bit, over all
     * 150 528 floats. It pins the source ADDRESSES (rotation case table, `sxMap`/`syMap` indexing, the
     * `shr 1` chroma subsampling) and the BT.601 coefficients together, which is the whole contract —
     * the gate's QA-tuned `NsfwGate.TABLE` thresholds sit on these exact numbers.
     */
    @Test
    fun `gather then convert is bit-identical to the one-pass walk`() {
        val n = 3 * FrameSampler.GATE_SIDE * FrameSampler.GATE_SIDE
        for (planar in booleanArrayOf(true, false)) for (rotation in intArrayOf(0, 90, 180, 270)) {
            val (one, two) = bothPaths(planar, rotation)
            assertArrayEquals(
                "planar=$planar rotation=$rotation",
                FloatArray(n) { one.get(it) },
                FloatArray(n) { two.get(it) },
                0f, // exact: a tolerance would hide the one failure mode that already shipped
            )
        }
    }

    /**
     * Absolute reads AND writes on both halves, checked from non-zero positions so a relative
     * `get()`/`put()` cannot pass. Load-bearing: the planes are the decoder's (ML Kit reads them too),
     * the gathered buffer is a ring slot the producer hands over, and ORT holds a tensor view over the
     * output for the whole pass.
     */
    @Test
    fun `neither half moves any buffer position`() {
        val y = luma().apply { position(7) }
        val (u, v) = planarChroma()
        u.position(2); v.position(1)
        val bytes = gathered().apply { position(11) }
        val out = tensor(FrameSampler.GATE_SIDE).apply { position(5) }
        FrameSampler.gatherGate(y, yRow, 1, base, u, cRow, 1, base, v, cRow, 1, base, 90, gx, gy, bytes)
        FrameSampler.gateFromGathered(bytes, out)
        assertEquals(7, y.position())
        assertEquals(2, u.position())
        assertEquals(1, v.position())
        assertEquals(11, bytes.position())
        assertEquals(5, out.position())
        assertEquals(3 * FrameSampler.GATE_SIDE * FrameSampler.GATE_SIDE, out.limit())
    }
}
