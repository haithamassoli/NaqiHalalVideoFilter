package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * perf-plan-v5 A2 claims the bulk-row rewrite of [FrameSampler.packNv21] is byte-for-byte the same as
 * the strided per-element version it replaced. `FrameSamplerConvertTest` cannot see that: it asserts
 * planar-vs-semi-planar agreement *within one build*, so a rewrite that changed both layouts the same
 * way would pass it untouched.
 *
 * This pins the claim against the actual pre-change implementation, copied verbatim from
 * `git show HEAD:…/FrameSampler.kt` — the only thing that can prove "bit-identical" rather than
 * "self-consistent". Delete it once A2 has shipped and been measured; it is a migration guard, not a
 * permanent spec.
 */
class PackNv21GoldenTest {

    /** The pre-A2 body, verbatim. Absolute gets, absolute puts, no duplicates, no scratch. */
    private fun packNv21Old(
        yBuf: ByteBuffer, yRow: Int, yPix: Int, yBase: Int,
        uBuf: ByteBuffer, uRow: Int, uPix: Int, uBase: Int,
        vBuf: ByteBuffer, vRow: Int, vPix: Int, vBase: Int,
        sxMap: IntArray, syMap: IntArray, out: ByteBuffer,
    ) {
        val w = sxMap.size
        val h = syMap.size
        for (oy in 0 until h) {
            val src = yBase + syMap[oy] * yRow
            val dst = oy * w
            for (ox in 0 until w) out.put(dst + ox, yBuf.get(src + sxMap[ox] * yPix))
        }
        val plane = w * h
        for (cy in 0 until h / 2) {
            val sy = syMap[cy * 2] shr 1
            val dst = plane + cy * w
            for (cx in 0 until w / 2) {
                val sx = sxMap[cx * 2] shr 1
                out.put(dst + cx * 2, vBuf.get(vBase + sy * vRow + sx * vPix))
                out.put(dst + cx * 2 + 1, uBuf.get(uBase + sy * uRow + sx * uPix))
            }
        }
    }

    private fun buf(n: Int, rnd: Random) =
        ByteBuffer.allocateDirect(n).apply { repeat(n) { put(it, rnd.nextInt(256).toByte()) } }

    /** Both real decoder layouts, at the real 1080p→640 scale and a few awkward small ones. */
    @Test
    fun `bulk pack is byte-identical to the pre-A2 strided pack`() {
        val rnd = Random(20260806)
        // srcW, srcH, dispW, dispH — the last pair is 1920x1080 -> 640x360, the shipping case.
        val shapes = listOf(
            intArrayOf(16, 12, 8, 6),
            intArrayOf(18, 14, 4, 4),
            intArrayOf(64, 48, 62, 46),   // barely downscaled: stride 1, spans nearly the whole row
            intArrayOf(1920, 1080, 640, 360),
        )
        for (s in shapes) {
            val (srcW, srcH, dispW, dispH) = listOf(s[0], s[1], s[2], s[3])
            val sxMap = IntArray(dispW) { it * srcW / dispW }
            val syMap = IntArray(dispH) { it * srcH / dispH }
            val outSize = dispW * dispH * 3 / 2

            for (semiPlanar in listOf(false, true)) {
                // Non-zero bases and padded row strides: both are what a real gralloc plane hands over.
                val yRow = srcW + 16
                val yBase = 3
                val y = buf(yBase + yRow * srcH + 16, rnd)

                val cRow = if (semiPlanar) srcW + 16 else srcW / 2 + 8
                val cPix = if (semiPlanar) 2 else 1
                val uBase = 5
                // Semi-planar: ONE interleaved plane, V and U one byte apart — the Qualcomm NV12 case.
                val vBase = if (semiPlanar) uBase + 1 else 7
                val cSize = maxOf(uBase, vBase) + cRow * (srcH / 2) + 16
                val u = buf(cSize, rnd)
                val v = if (semiPlanar) u else buf(cSize, rnd)

                val expected = ByteBuffer.allocateDirect(outSize)
                packNv21Old(
                    y, yRow, 1, yBase, u, cRow, cPix, uBase, v, cRow, cPix, vBase,
                    sxMap, syMap, expected,
                )
                val actual = ByteBuffer.allocateDirect(outSize)
                FrameSampler.packNv21(
                    y, yRow, 1, yBase, u, cRow, cPix, uBase, v, cRow, cPix, vBase,
                    sxMap, syMap, actual,
                )
                assertArrayEquals(
                    "shape=${srcW}x$srcH->${dispW}x$dispH semiPlanar=$semiPlanar",
                    ByteArray(outSize) { expected.get(it) },
                    ByteArray(outSize) { actual.get(it) },
                )
            }
        }
    }
}
