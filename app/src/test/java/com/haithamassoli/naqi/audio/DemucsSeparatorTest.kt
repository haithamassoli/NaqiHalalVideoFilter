package com.haithamassoli.naqi.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM tests of the chunked overlap-add driver with fake inference lambdas (never ORT).
 * An "identity" model — masked spec of the vocals stem = the input spec, zero time branch — must
 * reproduce the input through normalize → STFT → sum → iSTFT → weighted OLA → denormalize, which
 * exercises every seam of the driver except the real model.
 */
class DemucsSeparatorTest {

    private val SEG = DemucsSeparator.SEG
    private val STRIDE = DemucsSeparator.STRIDE
    private val MAX_SHIFT = DemucsSeparator.MAX_SHIFT
    private val STEM_SPEC = DemucsSeparator.STEM_SPEC

    /** Fake infer that copies the input spec into [stemWeights] fractions of each stem's block. */
    private class SpecFake(private val stemWeights: Map<Int, Float>) {
        val specOut = FloatArray(4 * DemucsSeparator.STEM_SPEC)
        val timeOut = FloatArray(4 * 2 * DemucsSeparator.SEG)
        fun infer(wav: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
            for ((stem, w) in stemWeights) {
                val base = stem * spec.size
                for (i in spec.indices) specOut[base + i] = w * spec[i]
            }
            return specOut to timeOut
        }
    }

    private fun stats(x: FloatArray, frames: Int): Pair<Float, Float> {
        var m = 0.0
        for (i in 0 until frames) m += 0.5 * (x[2 * i] + x[2 * i + 1])
        val mean = m / frames
        var v = 0.0
        for (i in 0 until frames) {
            val d = 0.5 * (x[2 * i] + x[2 * i + 1]) - mean
            v += d * d
        }
        return mean.toFloat() to sqrt(v / (frames - 1)).toFloat()
    }

    private fun snrDb(ref: FloatArray, got: FloatArray, n: Int, refScale: Float = 1f): Double {
        var se = 0.0
        var s = 0.0
        for (i in 0 until n) {
            val r = ref[i] * refScale
            val d = r - got[i]
            s += r * r
            se += d * d
        }
        return 10.0 * log10(s / (se + 1e-30))
    }

    /** Drive a separator over [input] ([frames] interleaved stereo) in ragged batches; returns output. */
    private fun run(
        input: FloatArray,
        frames: Int,
        keepOther: Boolean,
        infer: (FloatArray, FloatArray) -> Pair<FloatArray, FloatArray>,
        batch: Int = 3333,
        onChunk: (Int, Int) -> Unit = { _, _ -> },
    ): FloatArray {
        val (mean, std) = stats(input, frames)
        val out = FloatArray(2 * frames)
        var cursor = 0
        val sep = DemucsSeparator(keepOther, mean, std, frames.toLong(), infer, onChunk) { buf, n ->
            System.arraycopy(buf, 0, out, cursor, 2 * n)
            cursor += 2 * n
        }
        var fed = 0
        val slice = FloatArray(2 * batch)
        while (fed < frames) {
            val n = minOf(batch, frames - fed)
            System.arraycopy(input, 2 * fed, slice, 0, 2 * n)
            sep.feed(slice, n)
            fed += n
        }
        sep.finish()
        assertEquals(2 * frames, cursor)
        return out
    }

    /**
     * Band-limited noise: MA(2)-filtered per channel, which has an exact spectral null at Nyquist.
     * The model contract drops the Nyquist bin (dsp-spec §1c) — the reference loses it too — so an
     * identity round-trip of full-band white noise is capped near 10·log10(nfft) ≈ 36 dB by design;
     * band-limited input keeps the identity test sensitive to real driver bugs instead.
     */
    private fun noise(frames: Int, seed: Long = 7): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(2 * frames)
        var prevL = 0f
        var prevR = 0f
        for (i in 0 until frames) {
            val l = rnd.nextFloat() - 0.5f
            val r = rnd.nextFloat() - 0.5f
            out[2 * i] = 0.5f * (l + prevL)
            out[2 * i + 1] = 0.5f * (r + prevR)
            prevL = l
            prevR = r
        }
        return out
    }

    @Test
    fun identityModelRoundTrips() {
        val n = 700_000 // several full chunks plus a short tail
        val input = noise(n)
        val out = run(input, n, keepOther = false, infer = SpecFake(mapOf(3 to 1f))::infer)
        val snr = snrDb(input, out, 2 * n)
        assertTrue("identity SNR $snr dB", snr > 60.0)
    }

    @Test
    fun normalizationRoundTrips() {
        val n = 400_000
        val input = FloatArray(2 * n)
        for (i in 0 until n) {
            val v = 0.25f + 0.3f * sin(2.0 * Math.PI * 440.0 * i / 44100.0).toFloat()
            input[2 * i] = v
            input[2 * i + 1] = 0.8f * v + 0.05f
        }
        val out = run(input, n, keepOther = false, infer = SpecFake(mapOf(3 to 1f))::infer)
        val snr = snrDb(input, out, 2 * n)
        assertTrue("norm SNR $snr dB", snr > 60.0)
    }

    @Test
    fun keepOtherSumsBothStems() {
        val n = 400_000
        val input = noise(n, seed = 11)
        val fake = SpecFake(mapOf(2 to 0.25f, 3 to 0.75f))
        val both = run(input, n, keepOther = true, infer = fake::infer)
        assertTrue("vocals+other SNR", snrDb(input, both, 2 * n) > 60.0)

        val vocalsOnly = run(input, n, keepOther = false, infer = fake::infer)
        assertTrue("vocals-only 0.75x SNR", snrDb(input, vocalsOnly, 2 * n, refScale = 0.75f) > 60.0)
    }

    @Test
    fun shortInputSingleChunk() {
        val n = 30_000 // < one segment even with the shift pre-pad
        val input = noise(n, seed = 3)
        var total = 0
        val out = run(input, n, keepOther = false, infer = SpecFake(mapOf(3 to 1f))::infer,
            onChunk = { _, t -> total = t })
        assertEquals(1, total)
        for (v in out) assertTrue("finite", v.isFinite())
        assertTrue("short SNR", snrDb(input, out, 2 * n) > 60.0)
    }

    @Test
    fun timeBranchPassesThrough() {
        val n = 500_000
        val input = noise(n, seed = 5)
        val specOut = FloatArray(4 * STEM_SPEC) // stays zero
        val timeOut = FloatArray(4 * 2 * SEG)
        val infer = { wav: FloatArray, _: FloatArray ->
            System.arraycopy(wav, 0, timeOut, (2 * 3) * SEG, SEG)      // vocals ch0
            System.arraycopy(wav, SEG, timeOut, (2 * 3 + 1) * SEG, SEG) // vocals ch1
            specOut to timeOut
        }
        val out = run(input, n, keepOther = false, infer = infer)
        assertTrue("time-branch SNR", snrDb(input, out, 2 * n) > 60.0)
    }

    @Test
    fun progressIsMonotonicAndComplete() {
        val n = 700_000
        val calls = ArrayList<Pair<Int, Int>>()
        run(noise(n, seed = 9), n, keepOther = false, infer = SpecFake(mapOf(3 to 1f))::infer,
            onChunk = { d, t -> calls.add(d to t) })
        val expectedTotal = ((n + MAX_SHIFT + STRIDE - 1L) / STRIDE).toInt()
        assertEquals(expectedTotal, calls.size)
        calls.forEachIndexed { i, (d, t) ->
            assertEquals(i + 1, d)
            assertEquals(expectedTotal, t)
        }
    }
}
