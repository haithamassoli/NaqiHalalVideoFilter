package com.haithamassoli.naqi.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place iterative radix-2 complex FFT (Cooley-Tukey, decimation-in-time). `n = re.size = im.size`
 * must be a power of two. Twiddle factors and the bit-reversal permutation are computed in double and
 * cached per size (only 64 and 4096 occur in M2); the sample data stays f32. [inverse] folds in the
 * `1/n` scaling exactly once — do NOT add a second `1/n` anywhere downstream (dsp-spec.md §8c gotcha 1).
 *
 * **The double twiddles and the per-butterfly promotion below stay, deliberately.** `plan-v2` §5.9 (A5)
 * proposes float tables and float butterflies to delete the conversions; the gate that change has to
 * clear is [DspTest], and DspTest has no room for it. A 4096-point radix-2 carried entirely in f32 lands
 * around 1e-4 relative error against ~1e-7 with the tables in double — and FOUR independent assertions
 * sit exactly AT 1e-4: `fftInverseRoundTrips` (atol 1e-4 on unit-magnitude data at n = 4096), both
 * golden comparisons (atol 1e-4 against the numpy f64 reference), and `fullSizeInteriorRoundTripHighSnr`
 * (> 80 dB interior SNR, and 80 dB *is* 1e-4). That is 1× of headroom, not the ~100× a numerics change
 * needs before it can be taken sight-unseen, and a tolerance is a gate, not a dial. The other half of
 * A5 — [Stft.forward]'s reused scratch — is taken, and is the bulk of the win. Revisit this half only
 * with a measured golden diff in hand, never by loosening the tolerance.
 *
 * Contract: driven by one [Stft] on one thread at a time (thread-confined, like [com.haithamassoli.naqi.ml.Infer]).
 */
object Fft {
    /** Forward transform `X[k] = Σ x[n]·exp(-2πi·kn/N)`, in place. */
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    /** Inverse transform `x[n] = (1/N)·Σ X[k]·exp(+2πi·kn/N)`, in place (includes the 1/N). */
    fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, inverse = true)

    private val cache = HashMap<Int, Tables>()
    private fun tables(n: Int) = cache.getOrPut(n) { Tables(n) }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        val t = tables(n)
        val rev = t.rev
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // Twiddles W[idx] = exp(-2πi·idx/n) are stored forward; the inverse conjugates (flips the imag sign).
        val cosT = t.cosTable
        val sinT = t.sinTable
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var base = 0
            while (base < n) {
                var idx = 0
                for (k in 0 until half) {
                    val wr = cosT[idx]
                    val wi = if (inverse) -sinT[idx] else sinT[idx]
                    val a = base + k
                    val b = a + half
                    // Promotion kept — see the accuracy arithmetic in this object's KDoc (A5, declined half).
                    val vr0 = re[b].toDouble()
                    val vi0 = im[b].toDouble()
                    val vr = vr0 * wr - vi0 * wi
                    val vi = vr0 * wi + vi0 * wr
                    val ur = re[a].toDouble()
                    val ui = im[a].toDouble()
                    re[a] = (ur + vr).toFloat(); im[a] = (ui + vi).toFloat()
                    re[b] = (ur - vr).toFloat(); im[b] = (ui - vi).toFloat()
                    idx += step
                }
                base += len
            }
            len = len shl 1
        }
        if (inverse) {
            val invN = 1.0 / n
            for (i in 0 until n) {
                re[i] = (re[i] * invN).toFloat()
                im[i] = (im[i] * invN).toFloat()
            }
        }
    }

    // Bit-reversal permutation + forward twiddles W[k] = exp(-2πi·k/n) for k in 0 until n/2.
    private class Tables(n: Int) {
        val rev = IntArray(n)
        val cosTable = DoubleArray(n / 2)
        val sinTable = DoubleArray(n / 2)

        init {
            var log = 0
            while ((1 shl log) < n) log++
            // Integer.reverse flips all 32 bits; shifting right by (32-log) keeps the low `log` of them,
            // which is exactly the log-bit reversal. n is a power of two, so log is its exact width.
            for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - log)
            for (k in 0 until n / 2) {
                val ang = -2.0 * PI * k / n
                cosTable[k] = cos(ang)
                sinTable[k] = sin(ang)
            }
        }
    }
}

/**
 * demucs out-of-graph STFT / iSTFT: `torch.stft(center=True, normalized=True, pad_mode="reflect",
 * periodic hann)` plus the demucs `_spec`/`_ispec` pad-and-slice (dsp-spec.md §1-§2; constants from
 * plan.md). Parameterized for the small-config golden test; production uses `Stft(4096, 1024)` on
 * `T = 343_980`.
 *
 * TORCH reflect padding is used throughout (mirror EXCLUDING the edge sample), NOT the C++ off-by-one.
 * The window is a periodic Hann (denominator `nfft`) and the OLA envelope (window sum-of-squares) are
 * both kept in double; only the per-sample signal / spectrum data is f32. The envelope depends solely
 * on the window and frame count, so it is precomputed once per `T` and shared by both channels.
 *
 * Contract: thread-confined (drives [Fft] on one thread). One instance is bound to one `nfft`/`hop`;
 * per-`T` scratch is cached, so reuse the instance for the fixed production `T` to avoid reallocation.
 */
class Stft(val nfft: Int = 4096, val hop: Int = 1024) {
    /** Model keeps `nfft/2` of the `nfft/2+1` rfft bins (the Nyquist bin is dropped). */
    val bins: Int get() = nfft / 2

    /** Model frame count for an input of length `T` = `ceil(T/hop)`. */
    fun le(T: Int): Int = (T + hop - 1) / hop

    // Periodic Hann (denominator nfft, NOT nfft-1) in double; forward/inverse normalize scalars (1/sqrt(nfft)).
    private val win = DoubleArray(nfft) { n -> 0.5 * (1.0 - cos(2.0 * PI * n / nfft)) }
    private val fwdScale = (1.0 / sqrt(nfft.toDouble())).toFloat()
    private val invScale = sqrt(nfft.toDouble()).toFloat()

    // Cached per-T scratch (production drives one fixed T; a test reuses one instance per T).
    private var scratchCache: Scratch? = null
    private fun scratch(T: Int): Scratch =
        scratchCache?.takeIf { it.t == T } ?: Scratch(T).also { scratchCache = it }

    /**
     * Planar segment (`ch0`/`ch1`, each length `T`) -> CaC spec `[4][bins][le]` flattened C-order:
     * channel-major, real-before-imag `[ch0.re, ch0.im, ch1.re, ch1.im]`, `1/sqrt(nfft)`-normalized,
     * Nyquist bin dropped, frames sliced `[2 : 2+le]`.
     *
     * **The returned array is this instance's per-`T` scratch, NOT a fresh allocation — consume it before
     * the next [forward] on the same instance.** That is a real contract change (a pure function became a
     * reused-buffer one) and it is worth it: this used to be `FloatArray(4 * bins * le)` per call, 3.67 MB
     * at the production size, ~17.5 GB of large-object churn over a feature film (`plan-v2` §5.9, A5).
     * Safe because every one of the `4 * bins * le` cells is overwritten on every call — both channels,
     * all bins, all kept frames — so nothing stale can survive, and because the sole production consumer
     * ([DemucsSeparator.inferChunk]) hands it straight to `infer`, whose first act is to copy it into a
     * direct buffer. The value is dead well before this method can run again.
     */
    fun forward(ch0: FloatArray, ch1: FloatArray, T: Int): FloatArray {
        val s = scratch(T)
        forwardChannel(ch0, reChan = 0, s, s.cac)
        forwardChannel(ch1, reChan = 2, s, s.cac)
        return s.cac
    }

    /**
     * Summed masked CaC spec `[4][bins][le]` -> planar waveform written into `out0`/`out1` (each length
     * `T`). One iSTFT per channel: rebuild the conjugate-symmetric spectrum (Nyquist and the four
     * boundary frames zero), overlap-add with the window, divide by the window sum-of-squares envelope,
     * then trim the center + level-A padding.
     */
    fun inverse(cac: FloatArray, T: Int, out0: FloatArray, out1: FloatArray) {
        val s = scratch(T)
        inverseChannel(cac, reChan = 0, s, out0)
        inverseChannel(cac, reChan = 2, s, out1)
    }

    private fun forwardChannel(ch: FloatArray, reChan: Int, s: Scratch, cac: FloatArray) {
        reflectPad(ch, s.t, s.padL, s.padR, s.sigA)              // level-A re-pad -> paddedSeg
        val center = nfft / 2
        reflectPad(s.sigA, s.paddedSeg, center, center, s.sigB) // torch.stft center pad -> paddedSeg+nfft
        val le = s.le
        val reBase = reChan * bins * le
        val imBase = (reChan + 1) * bins * le
        val re = s.frameRe
        val im = s.frameIm
        val sigB = s.sigB
        for (f in 2 until 2 + le) {                             // only the kept frames need transforming
            val start = f * hop
            for (i in 0 until nfft) {
                re[i] = (sigB[start + i] * win[i]).toFloat()
                im[i] = 0f
            }
            Fft.forward(re, im)
            val t = f - 2
            for (b in 0 until bins) {                           // keep bins 0..nfft/2-1, drop Nyquist
                cac[reBase + b * le + t] = re[b] * fwdScale
                cac[imBase + b * le + t] = im[b] * fwdScale
            }
        }
    }

    private fun inverseChannel(cac: FloatArray, reChan: Int, s: Scratch, out: FloatArray) {
        val ola = s.ola
        java.util.Arrays.fill(ola, 0.0)
        val le = s.le
        val reBase = reChan * bins * le
        val imBase = (reChan + 1) * bins * le
        val half = nfft / 2
        val re = s.frameRe
        val im = s.frameIm
        for (f in 2 until 2 + le) {                             // boundary frames 0,1,2+le..nframes stay zero
            java.util.Arrays.fill(re, 0f)                       // clears the Nyquist bin + prior FFT output
            java.util.Arrays.fill(im, 0f)
            val t = f - 2
            for (b in 0 until bins) {
                re[b] = cac[reBase + b * le + t] * invScale     // un-normalize (× sqrt(nfft))
                im[b] = cac[imBase + b * le + t] * invScale
            }
            for (b in 1 until half) {                           // Hermitian upper half: X[nfft-b] = conj(X[b])
                re[nfft - b] = re[b]
                im[nfft - b] = -im[b]
            }
            Fft.inverse(re, im)                                 // includes 1/nfft; result is real in re[]
            val start = f * hop
            for (i in 0 until nfft) {
                ola[start + i] += re[i].toDouble() * win[i]
            }
        }
        val env = s.env
        val offset = half + s.padL                             // trim center pad + level-A front pad
        for (k in 0 until s.t) {
            out[k] = (ola[offset + k] / (env[offset + k] + 1e-8)).toFloat()
        }
    }

    // Torch reflect pad (mirror EXCLUDING the edge sample). Requires dst.size >= srcLen + l + r and
    // srcLen > max(l, r) (always true for the M2 sizes; the pad1d short-signal guard never triggers).
    private fun reflectPad(src: FloatArray, srcLen: Int, l: Int, r: Int, dst: FloatArray) {
        for (j in 0 until l) dst[j] = src[l - j]               // dst[0..l-1] = src[l], src[l-1], .., src[1]
        System.arraycopy(src, 0, dst, l, srcLen)
        for (k in 0 until r) dst[l + srcLen + k] = src[srcLen - 2 - k]
    }

    // Per-T buffers + precomputed OLA envelope. Inner class so the envelope can read the shared window.
    private inner class Scratch(val t: Int) {
        val le = le(t)
        val padL = hop / 2 * 3
        val padR = padL + le * hop - t
        val paddedSeg = t + padL + padR                        // = 2*padL + le*hop = (le+3)*hop
        val nframes = paddedSeg / hop + 1                      // = le + 4
        val sigA = FloatArray(paddedSeg)
        val sigB = FloatArray(paddedSeg + nfft)
        val frameRe = FloatArray(nfft)
        val frameIm = FloatArray(nfft)
        val ola = DoubleArray(paddedSeg + nfft)
        val env = DoubleArray(paddedSeg + nfft)
        val cac = FloatArray(4 * bins * le)                    // [forward]'s output, reused — see its KDoc

        init {
            for (f in 0 until nframes) {                       // window sum-of-squares over ALL frames
                val start = f * hop
                for (i in 0 until nfft) env[start + i] += win[i] * win[i]
            }
        }
    }
}
