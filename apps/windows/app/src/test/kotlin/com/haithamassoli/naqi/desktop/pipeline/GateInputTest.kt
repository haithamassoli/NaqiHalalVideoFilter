package com.haithamassoli.naqi.desktop.pipeline

import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.desktop.media.VideoDecoder
import com.haithamassoli.naqi.desktop.vision.NsfwClassifier
import com.haithamassoli.naqi.ml.NSFW_CLASSES
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacpp.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * What [Pipeline.analyze] hands the NSFW model must be Android's gate input: one nearest-neighbour stretch
 * of the SOURCE pixels to 224² (`FrameSampler.convertToTensor`'s maps and rotation table), not a resample
 * of the 640-px analysis frame, which Android measured as under-censoring. A 1280x720 clip of per-pixel
 * noise, lossless, makes any second resampling stage show: it averages the noise away. No models needed.
 */
class GateInputTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ffmpeg: String by lazy { Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }

    private fun ff(vararg args: String) {
        val p = ProcessBuilder(ffmpeg, "-y", "-v", "error", *args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { out }
    }

    private fun noiseClip(): File = File(tmp.root, "noise.mp4").also {
        ff("-f", "lavfi", "-i", "nullsrc=s=1280x720:r=10:d=0.5,format=yuv420p," +
            "geq=lum='random(1)*255':cb='random(2)*255':cr='random(3)*255'",
            "-c:v", "libx264", "-qp", "0", "-pix_fmt", "yuv420p", it.path)
    }

    /** The gate inputs analyze produces, by pts. */
    private fun gateInputs(clip: File): Map<Long, FloatArray> = runBlocking {
        val seen = LinkedHashMap<Long, FloatArray>()
        val buf = FloatBuffer.allocate(3 * 224 * 224)
        Pipeline.analyze(clip, VideoMeta(1280, 720, 0, 500, 10f), FilterOps(censorNsfw = true), null, { frame, rotation ->
            NsfwClassifier.fillInput(frame, rotation, buf)
            seen[frame.ptsMs] = FloatArray(buf.capacity()) { buf.get(it) }
            FloatArray(NSFW_CLASSES.size).also { it[NSFW_CLASSES.indexOf("neutral")] = 1f }
        }) {}
        seen
    }

    /** Every sampled frame at full size, UPRIGHT, by pts (no maxDim reached, so no scaling). */
    private fun uprightSources(clip: File): Map<Long, RgbFrame> = runBlocking {
        val out = LinkedHashMap<Long, RgbFrame>()
        VideoDecoder.sample(clip, 10f, maxDim = 100_000) { out[it.ptsMs] = RgbFrame(it.width, it.height, it.rgb.copyOf(), it.ptsMs) }
        out
    }

    /**
     * Android's tensor, written out here independently: output (ox, oy) reads STORED pixel
     * (gx[dx], gy[dy]) with `g[i] = i * side / 224` and (dx, dy) from its rotation table. The stored pixel is
     * looked up in the upright frame by undoing the clockwise [rotation].
     */
    private fun android(upright: RgbFrame, rotation: Int): FloatArray {
        val sw = if (rotation % 180 == 0) upright.width else upright.height // stored size
        val sh = if (rotation % 180 == 0) upright.height else upright.width
        val out = FloatArray(3 * 224 * 224)
        for (oy in 0 until 224) for (ox in 0 until 224) {
            val (dx, dy) = when (rotation) {
                90 -> oy to 223 - ox
                180 -> 223 - ox to 223 - oy
                270 -> 223 - oy to ox
                else -> ox to oy
            }
            val x = dx * sw / 224
            val y = dy * sh / 224
            val (ux, uy) = when (rotation) { // stored (x, y) -> upright, as rotateRgb maps it
                90 -> sh - 1 - y to x
                180 -> sw - 1 - x to sh - 1 - y
                270 -> y to sw - 1 - x
                else -> x to y
            }
            val s = 3 * (uy * upright.width + ux)
            for (c in 0..2) out[c * 224 * 224 + oy * 224 + ox] = (upright.rgb[s + c].toInt() and 0xFF) / 255f
        }
        return out
    }

    private fun check(clip: File, rotation: Int) {
        val gates = gateInputs(clip)
        val sources = uprightSources(clip)
        assertEquals("every second sample, from the first", sources.keys.filterIndexed { i, _ -> i % 2 == 0 }, gates.keys.toList())
        for ((pts, got) in gates) {
            val want = android(sources.getValue(pts), rotation)
            val mad = got.indices.sumOf { abs(got[it] - want[it]).toDouble() } / got.size
            // Same decoder, same pixels: only RGB conversion rounding may differ. A second resampling stage
            // (the 640-px analysis frame) averages this noise toward grey, a mean difference of ~0.2.
            assertTrue("rotation $rotation, pts $pts: mean |diff| $mad", mad < 0.01)
        }
    }

    @Test fun theGateSamplesSourcePixelsDirectly() = check(noiseClip(), 0)

    @Test fun andAppliesTheRotationInAndroidsWalk() {
        val rotated = File(tmp.root, "rotated.mp4")
        ff("-display_rotation", "-90", "-i", noiseClip().path, "-c", "copy", rotated.path) // 90° clockwise
        check(rotated, 90)
    }
}
