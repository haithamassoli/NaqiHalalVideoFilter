package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.model.FilterOps
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The pass-2 compositor: Android's `CensorEffect` (its GLSL composite and blur passes) on CPU RGB.
 * Same maths, same constants: a separable Gaussian at a downscaled resolution, sigma keyed on the short
 * side, an opaque solid fill that wins over everything, BT.709 grayscale, and a feather that ramps
 * OUTWARD from each rect so softening never uncovers a pixel the hard rect covered.
 *
 * No region cap: `MAX_REGIONS` exists on Android only because a GLSL uniform array has a fixed size, and
 * its overflow spans exist only to make up for that cap. A CPU loop has neither limit.
 */
object Censor {
    /**
     * Paint [frame] in place. [fullFrame] covers everything; otherwise each of [regions] (normalized to
     * this frame as stored, i.e. already `NRect.toStoredSpace(rotation)`) is covered. Style is FilterOps':
     * [solidColor] != `FilterOps.BLUR` is an opaque ARGB fill; else blur at [blurAmount] (0..100),
     * desaturated when [grayscale].
     */
    fun apply(
        frame: RgbFrame,
        regions: List<NRect>,
        fullFrame: Boolean,
        blurAmount: Int,
        grayscale: Boolean,
        solidColor: Int,
    ) {
        if (!fullFrame && regions.isEmpty()) return
        val w = frame.width
        val h = frame.height
        val solid = solidColor != FilterOps.BLUR
        // Android's FilterWorker turns the one no-op style (no blur, no gray, no fill) into a light blur
        // rather than render a censor that changes nothing. Done here because this is the only place every
        // caller passes through.
        val amount = if (blurAmount > 0 || grayscale || solid) blurAmount.coerceAtMost(100) else MIN_EFFECTIVE_BLUR
        val blurOn = amount > 0 && !solid

        // Each region with its feather band: 15% of its own size per axis, floored (Android's shader).
        val rects = regions.filter { it.width > 0f && it.height > 0f }.map {
            floatArrayOf(it.left, it.top, it.right, it.bottom,
                max(it.width * FEATHER, MIN_FEATHER), max(it.height * FEATHER, MIN_FEATHER))
        }
        // Only the pixels a mask can reach are touched.
        var x0 = 0; var y0 = 0; var x1 = w; var y1 = h
        if (!fullFrame) {
            if (rects.isEmpty()) return
            x0 = floor(rects.minOf { it[0] - it[4] } * w).toInt().coerceIn(0, w)
            y0 = floor(rects.minOf { it[1] - it[5] } * h).toInt().coerceIn(0, h)
            x1 = ceil(rects.maxOf { it[2] + it[4] } * w).toInt().coerceIn(0, w)
            y1 = ceil(rects.maxOf { it[3] + it[5] } * h).toInt().coerceIn(0, h)
            if (x0 >= x1 || y0 >= y1) return
        }
        val blurred = if (blurOn) Blur(frame, amount, x0, y0, x1, y1) else null
        val sr = ((solidColor shr 16) and 0xFF).toFloat()
        val sg = ((solidColor shr 8) and 0xFF).toFloat()
        val sb = (solidColor and 0xFF).toFloat()
        val rgb = frame.rgb
        val base = FloatArray(3)
        for (y in y0 until y1) {
            val v = (y + 0.5f) / h
            for (x in x0 until x1) {
                var mask = if (fullFrame) 1f else 0f
                if (!fullFrame) {
                    val u = (x + 0.5f) / w
                    for (k in rects.indices) {
                        val r = rects[k]
                        val m = smoothstep(r[0] - r[4], r[0], u) * (1f - smoothstep(r[2], r[2] + r[4], u)) *
                            smoothstep(r[1] - r[5], r[1], v) * (1f - smoothstep(r[3], r[3] + r[5], v))
                        if (m > mask) mask = m
                        if (mask >= 1f) break
                    }
                    if (mask <= 0f) continue
                }
                val i = 3 * (y * w + x)
                if (solid) {
                    base[0] = sr; base[1] = sg; base[2] = sb
                } else {
                    if (blurred != null) blurred.sample(x, y, base)
                    else for (c in 0..2) base[c] = (rgb[i + c].toInt() and 0xFF).toFloat()
                    if (grayscale) {
                        val luma = 0.2126f * base[0] + 0.7152f * base[1] + 0.0722f * base[2] // BT.709
                        base[0] = luma; base[1] = luma; base[2] = luma
                    }
                }
                for (c in 0..2) {
                    val o = (rgb[i + c].toInt() and 0xFF).toFloat()
                    val out = if (mask >= 1f) base[c] else o + (base[c] - o) * mask
                    rgb[i + c] = (out + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
    }

    /**
     * The blurred picture over the window [x0, x1) × [y0, y1), at Android's downscaled resolution:
     * box-averaged down by `d`, then the separable Gaussian, read back with bilinear filtering like the
     * shader's texture fetch. The low-res window is grown by the kernel radius, so every tap a covered
     * pixel needs is real image, and edges clamp only at the frame's own edge, as GL_CLAMP_TO_EDGE does.
     */
    private class Blur(frame: RgbFrame, amount: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        private val w = frame.width
        private val h = frame.height
        private val lowW: Int
        private val lowH: Int
        private val lx0: Int
        private val ly0: Int
        private val ww: Int
        private val wh: Int
        private val px: FloatArray

        init {
            // CensorEffect.configure: sigma in full-res px, 40 at blur 100 on a 1080 short side; the smallest
            // downscale in {1,2,4,8} keeping the low-res sigma <= 4.
            val sigmaPx = max(0.1f, amount / 100f * 40f * (min(w, h) / 1080f))
            val d = intArrayOf(1, 2, 4, 8).firstOrNull { sigmaPx / it <= 4f } ?: 8
            lowW = max(1, w / d)
            lowH = max(1, h / d)
            val sigma = sigmaPx / d
            val radius = min(MAX_RADIUS, ceil(2.5f * sigma).toInt()).coerceAtLeast(1)
            val kernel = FloatArray(radius + 1) { exp(-(it * it) / (2f * sigma * sigma)) }
            val sum = kernel[0] + 2f * (1..radius).sumOf { kernel[it].toDouble() }.toFloat()
            for (k in kernel.indices) kernel[k] /= sum

            lx0 = (floor(x0.toFloat() * lowW / w).toInt() - 1 - radius).coerceAtLeast(0)
            ly0 = (floor(y0.toFloat() * lowH / h).toInt() - 1 - radius).coerceAtLeast(0)
            val lx1 = (ceil(x1.toFloat() * lowW / w).toInt() + 1 + radius).coerceAtMost(lowW)
            val ly1 = (ceil(y1.toFloat() * lowH / h).toInt() + 1 + radius).coerceAtMost(lowH)
            ww = lx1 - lx0
            wh = ly1 - ly0

            // Box-average down.
            val low = FloatArray(3 * ww * wh)
            val rgb = frame.rgb
            for (ly in 0 until wh) {
                val sy0 = (ly + ly0) * h / lowH
                val sy1 = max(sy0 + 1, (ly + ly0 + 1) * h / lowH)
                for (lx in 0 until ww) {
                    val sx0 = (lx + lx0) * w / lowW
                    val sx1 = max(sx0 + 1, (lx + lx0 + 1) * w / lowW)
                    var r = 0; var g = 0; var b = 0
                    for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                        val i = 3 * (sy * w + sx)
                        r += rgb[i].toInt() and 0xFF
                        g += rgb[i + 1].toInt() and 0xFF
                        b += rgb[i + 2].toInt() and 0xFF
                    }
                    val n = ((sy1 - sy0) * (sx1 - sx0)).toFloat()
                    val o = 3 * (ly * ww + lx)
                    low[o] = r / n; low[o + 1] = g / n; low[o + 2] = b / n
                }
            }
            // Horizontal then vertical, clamping at the window's edges (see the class KDoc for why that is
            // exact). Both passes run tap-outer, element-inner over whole rows, edge-replicated, so the
            // inner loop is a plain multiply-add the JIT vectorizes. Measured on a 1080p whole frame at
            // blur 10 (full scale, radius 10, the worst case): 417 ms per-pixel-taps -> 157 ms this way.
            // Default blur 60 runs at 1/8 scale: ~30 ms, mostly the composite.
            val row = 3 * ww
            val tmp = FloatArray(low.size)
            val padded = FloatArray(3 * (ww + 2 * radius))
            for (ly in 0 until wh) {
                val o = ly * row
                for (k in 0 until radius) {
                    for (c in 0..2) {
                        padded[3 * k + c] = low[o + c]
                        padded[3 * (radius + ww + k) + c] = low[o + row - 3 + c]
                    }
                }
                System.arraycopy(low, o, padded, 3 * radius, row)
                for (k in -radius..radius) {
                    val kw = kernel[kotlin.math.abs(k)]
                    val s = 3 * (radius + k)
                    for (j in 0 until row) tmp[o + j] += kw * padded[s + j]
                }
            }
            px = FloatArray(low.size)
            for (ly in 0 until wh) {
                val o = ly * row
                for (k in -radius..radius) {
                    val kw = kernel[kotlin.math.abs(k)]
                    val s = (ly + k).coerceIn(0, wh - 1) * row
                    for (j in 0 until row) px[o + j] += kw * tmp[s + j]
                }
            }
        }

        /** Bilinear read at full-res pixel ([x], [y])'s centre, into [out] (0..255 floats). */
        fun sample(x: Int, y: Int, out: FloatArray) {
            val gx = ((x + 0.5f) * lowW / w - 0.5f - lx0).coerceIn(0f, (ww - 1).toFloat())
            val gy = ((y + 0.5f) * lowH / h - 0.5f - ly0).coerceIn(0f, (wh - 1).toFloat())
            val ix = gx.toInt()
            val iy = gy.toInt()
            val fx = gx - ix
            val fy = gy - iy
            val ix1 = min(ix + 1, ww - 1)
            val iy1 = min(iy + 1, wh - 1)
            for (c in 0..2) {
                val a = px[3 * (iy * ww + ix) + c] * (1 - fx) + px[3 * (iy * ww + ix1) + c] * fx
                val b = px[3 * (iy1 * ww + ix) + c] * (1 - fx) + px[3 * (iy1 * ww + ix1) + c] * fx
                out[c] = a * (1 - fy) + b * fy
            }
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Android's FilterWorker.MIN_EFFECTIVE_BLUR: 10 px of sigma at 1080p. */
    private const val MIN_EFFECTIVE_BLUR = 25

    /** CensorEffect's Gaussian half-width cap. */
    private const val MAX_RADIUS = 10

    /** CensorEffect's feather: 15% of the region, never under 0.002 of the frame. */
    private const val FEATHER = 0.15f
    private const val MIN_FEATHER = 0.002f
}
