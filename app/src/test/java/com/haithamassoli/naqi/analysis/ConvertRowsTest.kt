package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * perf-plan 5.1. [FrameSampler.convertRows] is the YUV->RGB walk over a band of output rows. Phase 5's
 * fan-out over those bands was reverted (it moved wall 0 %), so production passes one band spanning the
 * whole image — but the property the split rests on is still worth pinning, because it is what makes
 * re-adding the fan-out cheap: **N bands produce byte-identical pixels to one band**. Checkable without
 * a device, since the function takes primitives and never an `android.media.Image`, so no Robolectric.
 *
 * The last test is the one that earns its keep regardless: nothing else in the repo pins the BT.601
 * arithmetic or the rotation indexing, both of which have been unverified since M1.
 *
 * Planes are built with deliberately awkward geometry: `rowStride > width`, chroma `pixelStride 2`
 * (semi-planar, U and V interleaved in one buffer) and a non-zero base, which is the layout that
 * would expose an index the extraction got wrong.
 */
class ConvertRowsTest {

    private val srcW = 37 // odd on purpose: chroma is sx/2, so an odd width exercises the last column
    private val srcH = 23
    private val yRowStride = srcW + 11 // padded rows, as a real decoder hands them over
    private val cRowStride = srcW + 9
    private val base = 7 // planes handed over at a non-zero position()

    /** Deterministic pseudo-random planes; NV12-ish: one buffer holds U and V interleaved. */
    private fun planes(): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val y = ByteBuffer.allocate(base + yRowStride * srcH)
        for (i in base until y.capacity()) y.put(i, ((i * 37) and 0xFF).toByte())
        val uv = ByteBuffer.allocate(base + cRowStride * ((srcH + 1) / 2) + 1)
        for (i in base until uv.capacity()) uv.put(i, ((i * 91 + 13) and 0xFF).toByte())
        return Triple(y, uv, uv) // v shares the buffer at base+1: pixelStride 2, the interleaved layout
    }

    private fun convert(rotation: Int, outScale: Int, workers: Int): IntArray {
        val (y, u, v) = planes()
        val dispW = srcW / outScale
        val dispH = srcH / outScale
        val sxMap = IntArray(dispW) { it * srcW / dispW }
        val syMap = IntArray(dispH) { it * srcH / dispH }
        val swap = rotation == 90 || rotation == 270
        val outW = if (swap) dispH else dispW
        val outH = if (swap) dispW else dispH
        val pixels = IntArray(outW * outH)

        // Same band arithmetic as toUprightBitmap: band size ceil(outH/W), bands ceil(outH/band).
        val bandRows = (outH + workers - 1) / workers
        val bands = (outH + bandRows - 1) / bandRows
        for (b in 0 until bands) {
            val start = b * bandRows
            FrameSampler.convertRows(
                y, yRowStride, 1, base,
                u, cRowStride, 2, base,
                v, cRowStride, 2, base + 1,
                rotation, sxMap, syMap, outW, pixels, start, minOf(start + bandRows, outH),
            )
        }
        return pixels
    }

    @Test
    fun `bands are byte-identical to a single pass, every rotation`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val serial = convert(rotation, outScale = 1, workers = 1)
            for (workers in intArrayOf(2, 4, 6)) {
                assertArrayEquals(
                    "rotation=$rotation workers=$workers",
                    serial, convert(rotation, outScale = 1, workers = workers),
                )
            }
        }
    }

    @Test
    fun `downscaled output splits identically too`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            assertArrayEquals(
                "rotation=$rotation",
                convert(rotation, outScale = 3, workers = 1),
                convert(rotation, outScale = 3, workers = 4),
            )
        }
    }

    /** `outH < workers`: the band arithmetic must produce fewer, 1-row bands, not empty or overlapping ones. */
    @Test
    fun `fewer rows than workers still covers every row exactly once`() {
        val (y, u, v) = planes()
        val sxMap = IntArray(5) { it }
        val syMap = IntArray(3) { it }
        val outW = 5
        val outH = 3
        val serial = IntArray(outW * outH)
        val split = IntArray(outW * outH)
        FrameSampler.convertRows(
            y, yRowStride, 1, base, u, cRowStride, 2, base, v, cRowStride, 2, base + 1,
            0, sxMap, syMap, outW, serial, 0, outH,
        )
        val bandRows = (outH + 8 - 1) / 8 // W=8 against 3 rows -> bandRows 1, bands 3
        val bands = (outH + bandRows - 1) / bandRows
        assertEquals(3, bands)
        for (b in 0 until bands) {
            FrameSampler.convertRows(
                y, yRowStride, 1, base, u, cRowStride, 2, base, v, cRowStride, 2, base + 1,
                0, sxMap, syMap, outW, split, b * bandRows, minOf((b + 1) * bandRows, outH),
            )
        }
        assertArrayEquals(serial, split)
    }

    /**
     * The split test above would pass just as happily on a convert that swapped U and V, so one anchor
     * on the colour maths itself: BT.601 full-range, a solid frame of Y=81 U=90 V=240 is red.
     */
    @Test
    fun `colour conversion is BT601 full range with U and V the right way round`() {
        val y = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 81) }
        val u = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 90.toByte()) }
        val v = ByteBuffer.allocate(16).also { for (i in 0 until 16) it.put(i, 240.toByte()) }
        val pixels = IntArray(4)
        FrameSampler.convertRows(
            y, 2, 1, 0, u, 2, 1, 0, v, 2, 1, 0,
            0, IntArray(2) { it }, IntArray(2) { it }, 2, pixels, 0, 2,
        )
        val p = pixels[0]
        assertEquals("alpha", 0xFF, (p ushr 24) and 0xFF)
        assertEquals("red", 238, (p ushr 16) and 0xFF)
        assertEquals("green", 15, (p ushr 8) and 0xFF)
        // 81 + ((1815 * -38) shr 10) = 81 - 68: `shr` on a negative floors toward -inf, so blue is 13,
        // not the 14 a truncating divide would give. Pinning it here is the point of this test.
        assertEquals("blue", 13, p and 0xFF)
        assertArrayEquals(intArrayOf(p, p, p, p), pixels) // solid in, solid out
    }
}
