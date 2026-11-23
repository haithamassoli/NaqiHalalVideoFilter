package com.haithamassoli.naqi.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * A1 (`plan-v2` §5.5): "is there music in this 2.6 s of audio?", answered by YAMNet, so
 * [DemucsSeparator] can pass a music-free chunk through instead of spending 2.1 s separating it.
 *
 * ~72 MMAC/s against htdemucs' 14.2 GFLOP/s — about **1 % of the separator's cost** — so the gate pays
 * for itself the first time it skips anything. Three inferences per chunk; measured 0.9–1.2 ms each on
 * an arm64 host, device figure unmeasured.
 *
 * **Everything in here is biased toward saying "music".** A missed music chunk is an audible product
 * failure the user cannot fix; a spurious separation costs one chunk of wall clock. That is why
 * [THRESHOLD] sits far below the score any real music gets, why the score is a MAX over frames rather
 * than a mean, and why `open` returns null (gate off, separate everything) on any problem at all rather
 * than guessing. The two-tier ±2-chunk dilation that goes with it lives in
 * [DemucsSeparator.separateChunk] — this class is stateless.
 *
 * Contract: SERIALIZED — one driver, one call at a time, like [HtdemucsSession]; [close] releases the
 * session. Not thread-CONFINED since perf-plan-v5 C10: both are driven from inside
 * [DemucsSeparator.feed], whose caller now suspends between batches, so the thread can change between
 * calls. `OrtSession.run` is itself thread-safe; the reused [input] buffer is what needs the ordering,
 * and coroutine dispatch supplies it.
 */
class MusicGate(
    /**
     * Runs YAMNet on one [FRAME]-sample frame (rank 1 — the exported graph's input is [15600], not
     * [1,15600]) and copies its [CLASSES] scores into the array. The only model call in this class: the
     * ORT session is the platform's (Android's `MusicGateSession.kt`), the policy here is shared.
     */
    private val infer: (frame: FloatBuffer, scores: FloatArray) -> Unit,
    private val release: () -> Unit = {},
) : AutoCloseable {

    /** 16 kHz mono, reused across calls. Sized for the largest window [DemucsSeparator] can hand over. */
    private val mono16k = FloatArray(out16kLength(DemucsSeparator.SEG) + 1)

    /**
     * The model input, DIRECT so ORT wraps it instead of copying (same reason as [HtdemucsSession]'s
     * buffers), allocated once and refilled per frame.
     */
    private val input: FloatBuffer = ByteBuffer.allocateDirect(FRAME * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val scores = FloatArray(CLASSES)

    /**
     * How much music is in [frames] samples of mono 44.1 kHz audio — instrumental or sung. At
     * [THRESHOLD] or above the window IS music; below it the number is still meaningful, which is the
     * whole point of returning it.
     *
     * Scored as the max over 0.975 s YAMNet frames tiled across the window with the LAST frame flush
     * against the end, so every sample is covered by at least one frame (a plain 0.975 s stride would
     * leave the last 0.65 s of a 2.6 s chunk unscored, and htdemucs chunks are 2.34 s apart, so that
     * hole would fall between windows too).
     *
     * **The early exit at [THRESHOLD] survives C1 unchanged, and cannot change either caller's answer.**
     * Above the threshold nothing ever asks for more than "≥ THRESHOLD"; below it no frame
     * short-circuits, so the true max is returned exactly — and below is the only place
     * [DemucsSeparator]'s far-dilation tier reads it. Silence (peak under [SILENCE_PEAK]) and a window
     * too short to resample both score 0, i.e. the same "not music" they always did.
     */
    fun score(mono44k: FloatArray, frames: Int): Float {
        val n = resampleTo16k(mono44k, frames)
        if (n == 0) return 0f

        // Silence is free (`plan-v2` §5.5): under the floor there is nothing to separate, and no model
        // run can change that. -60 dBFS peak — anything a listener could hear is well above it, and room
        // tone on a film soundtrack sits around -50.
        var peak = 0f
        for (i in 0 until n) { val a = abs(mono16k[i]); if (a > peak) peak = a }
        if (peak < SILENCE_PEAK) return 0f

        var best = 0f
        var start = 0
        while (true) {
            val s = scoreFrame(start, n)
            if (s > best) best = s
            if (best >= THRESHOLD || start + FRAME >= n) return best
            start = minOf(start + FRAME, n - FRAME) // last frame flush with the end
        }
    }

    /**
     * 44 100 -> 16 000 by linear interpolation, exactly 441:160. Deliberately NOT a second
     * [androidx.media3.common.audio.SonicAudioProcessor]: measured over 74 frames of real qa-asset audio,
     * naive linear interpolation moves the gate score by at most 0.13 against ffmpeg's soxr and flips ONE
     * frame at a 0.5 threshold — a frame that scored 0.502, between neighbours at 0.79 and 0.72, which the
     * ±2-chunk dilation covers anyway. At [THRESHOLD] nothing flips. YAMNet's mel filterbank stops at
     * 7500 Hz, so what a naive resample folds in lands mostly outside the analysis band.
     *
     * @return the number of 16 kHz samples written to [mono16k].
     */
    private fun resampleTo16k(src: FloatArray, frames: Int): Int {
        if (frames <= 1) return 0
        val n = out16kLength(frames)
        for (i in 0 until n) {
            val x = i * RATIO
            val i0 = x.toInt()
            val f = (x - i0).toFloat()
            val a = src[i0]
            val b = if (i0 + 1 < frames) src[i0 + 1] else a
            mono16k[i] = a + (b - a) * f
        }
        return n
    }

    /** One YAMNet frame starting at [start], zero-padded when the window ends first. */
    private fun scoreFrame(start: Int, n: Int): Float {
        input.clear()
        val avail = minOf(FRAME, n - start)
        input.put(mono16k, start, avail)
        while (input.hasRemaining()) input.put(0f)
        input.rewind()
        infer(input, scores)
        var best = 0f
        for (r in MUSIC_RANGES) for (c in r) if (scores[c] > best) best = scores[c]
        return best
    }

    override fun close() {
        release()
    }

    companion object {
        /** YAMNet's locked input length: 0.975 s at 16 kHz. */
        const val FRAME = 15_600
        const val CLASSES = 521
        private const val RATIO = 44_100.0 / 16_000.0

        /**
         * The two contiguous AudioSet class ranges that mean "music", **inclusive at both ends**, verified
         * against the class map shipped inside Google's own SavedModel:
         * - `132..276` — the music block, `Music` through `Scary music`. 131 is `Whale vocalization` and
         *   277 is `Wind`, so the block is exactly this.
         * - `24..32` — vocal music, which AudioSet keeps separate: Singing, Choir, Yodeling, Chant, Mantra,
         *   Child singing, Synthetic singing, Rapping, Humming. Including it is not optional — it is the
         *   only thing that catches a-cappella singing, which the product must remove (`plan-v2` §5.5).
         */
        private val MUSIC_RANGES = listOf(132..276, 24..32)

        /**
         * Biased hard toward false positives, as §5.5 requires. Measured on this exact artifact: silence
         * scores 0.0000, white noise 0.0240, a synthesized chord 0.9880, and real qa-asset content is
         * strongly bimodal (median 0.006 on a speech-heavy vlog, 0.50–0.92 on a scored clip). 0.15 is ~6x
         * above the loudest non-music case measured and ~6x below the quietest music case, i.e. it sits in
         * the empty middle of that distribution — which is what makes the choice not delicate. Move it DOWN
         * if a QA clip ever keeps music; the cost of down is wall clock, the cost of up is the product.
         *
         * Not private since C1: [DemucsSeparator] holds SCORES in its dilation ring and so has to apply
         * this itself.
         */
        internal const val THRESHOLD = 0.15f

        /** -60 dBFS peak. Below this a chunk is silence and needs no model at all. */
        private const val SILENCE_PEAK = 0.001f

        /**
         * Audio this long or longer (in frames at 44.1 kHz) gets the gate; shorter clips
         * separate every chunk. YAMNet misses some quiet background music under speech (~4 s of a 4:29
         * vlog), which a short clip doesn't need to risk; on a long one the skips save minutes.
         */
        const val LONG_FRAMES = 10L * 60 * 44_100

        /** 16 kHz samples a [frames]-sample 44.1 kHz window produces; the last one interpolates in range. */
        private fun out16kLength(frames: Int): Int = ((frames - 1) / RATIO).toInt() + 1
    }
}
