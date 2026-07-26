package com.haithamassoli.naqi.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.NaqiModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Streaming chunked overlap-add htdemucs driver per the demucs.onnx reference (`model_apply.cpp` /
 * `apply.py`, dsp-spec §3): feed interleaved f32 stereo 44.1 kHz in arbitrary batches; final samples
 * are emitted as soon as no later chunk can touch them (1-chunk lookahead), so memory stays O(SEGMENT)
 * regardless of track length.
 *
 * Reference behaviors replicated exactly:
 * - whole-track mono-mix mean/std normalization in, inverted on emit (apply.py "ref" norm — the
 *   per-segment norm is inside the ONNX graph and must NOT be repeated here);
 * - the 0.5 s zero pre-pad + shift trick with the deterministic `shift_offset = 0` draw;
 * - segments of [SEG] samples every [STRIDE] (25 % overlap), triangle-weighted overlap-add with
 *   weight-sum division (`TRANSITION_POWER = 1.0`);
 * - short tail chunks assembled per torch `TensorChunk.padded` (real left context, zeros past the
 *   end, outputs read back center-trimmed) — NOT the C++ centering bug documented in dsp-spec §3;
 * - kept-stem masked SPECTROGRAMS are summed before ONE iSTFT per chunk (linearity, dsp-spec §4),
 *   then the kept time branches are added; drums/bass are never kept.
 *
 * The PRD soft-clip guard (tanh knee at 0.95) is applied on the denormalized sum at emit time.
 * Contract: single-threaded (one worker drives feed/finish); [emit] receives interleaved stereo.
 */
class DemucsSeparator(
    keepOther: Boolean,
    mean: Float,
    std: Float,
    private val totalFrames: Long,
    private val infer: (wav: FloatArray, spec: FloatArray) -> Pair<FloatArray, FloatArray>,
    private val onChunk: (done: Int, total: Int) -> Unit,
    private val emit: (interleaved: FloatArray, frames: Int) -> Unit,
) {
    private val keep = if (keepOther) intArrayOf(OTHER, VOCALS) else intArrayOf(VOCALS)
    private val mean = mean
    private val std = std.coerceAtLeast(1e-8f) // same scalar for normalize and denormalize

    private val shiftedLen = totalFrames + MAX_SHIFT // shift_offset=0: lead zeros only (reference trims these)
    private val totalChunks = ((shiftedLen + STRIDE - 1) / STRIDE).toInt()

    // Input ring: normalized planar samples addressed by absolute virtual position (lead zeros are
    // the ring's own zero-init; positions ≥ writePos and < 0 read as zero = TensorChunk out-of-range).
    private val inL = FloatArray(IN_CAP)
    private val inR = FloatArray(IN_CAP)
    private var writePos = MAX_SHIFT // virtual positions [0, MAX_SHIFT) are the pre-pad zeros

    // Output ring: weighted overlap-add accumulator + weight sums; cells are zeroed as they flush.
    private val outL = FloatArray(OUT_CAP)
    private val outR = FloatArray(OUT_CAP)
    private val wsum = FloatArray(OUT_CAP)
    private var flushPos = 0L // virtual position below which everything was emitted (or skipped as pre-pad)
    private var emitted = 0L

    private var chunksDone = 0
    private var nextChunkOff = 0L

    // Per-chunk scratch, allocated once (hot path — ~44 MB flows through per chunk on device).
    private val stft = Stft(NFFT, HOP)
    private val segL = FloatArray(SEG)
    private val segR = FloatArray(SEG)
    private val wav = FloatArray(2 * SEG)
    private val sumCac = FloatArray(4 * (NFFT / 2) * LE)
    private val waveL = FloatArray(SEG)
    private val waveR = FloatArray(SEG)
    private val emitBuf = FloatArray(2 * STRIDE)
    private val weight = FloatArray(SEG) { i ->
        // Triangle 1..half, half..1 divided by its max (model_apply.cpp:96-101); ^TRANSITION_POWER(=1) is a no-op.
        min(i + 1, SEG - i).toFloat() / (SEG / 2).toFloat()
    }

    /** Feed the next [frames] interleaved stereo samples; may synchronously run inference and emit. */
    fun feed(interleaved: FloatArray, frames: Int) {
        var src = 0
        var remaining = frames
        while (remaining > 0) {
            val n = min(remaining, SEG / 2) // slice so the ring never outruns unprocessed chunk reads
            for (i in 0 until n) {
                val cell = ((writePos + i) % IN_CAP).toInt()
                inL[cell] = (interleaved[2 * (src + i)] - mean) / std
                inR[cell] = (interleaved[2 * (src + i) + 1] - mean) / std
            }
            writePos += n
            src += n
            remaining -= n
            // Process every chunk whose full SEGMENT of input is now available; short tails wait for finish().
            while (nextChunkOff < shiftedLen && nextChunkOff + SEG <= writePos) processChunk()
        }
    }

    /** Process the remaining (short) chunks, flush the tail, and emit exactly [totalFrames] frames. */
    fun finish() {
        while (nextChunkOff < shiftedLen) processChunk()
        check(emitted == totalFrames) { "separator emitted $emitted of $totalFrames frames" }
    }

    private fun processChunk() {
        val off = nextChunkOff
        val clen = min(SEG.toLong(), shiftedLen - off).toInt()
        val delta = SEG - clen // > 0 only for tail chunks
        val readStart = off - delta / 2 // TensorChunk.padded: real left context, zeros out of range

        for (j in 0 until SEG) {
            val p = readStart + j
            if (p < 0 || p >= writePos) {
                segL[j] = 0f
                segR[j] = 0f
            } else {
                val cell = (p % IN_CAP).toInt()
                segL[j] = inL[cell]
                segR[j] = inR[cell]
            }
        }
        System.arraycopy(segL, 0, wav, 0, SEG)
        System.arraycopy(segR, 0, wav, SEG, SEG)

        val spec = stft.forward(segL, segR, SEG)
        val (specOut, timeOut) = infer(wav, spec)

        // Sum kept masked specs (one iSTFT total — dsp-spec §4), then kept time branches per sample.
        System.arraycopy(specOut, keep[0] * STEM_SPEC, sumCac, 0, STEM_SPEC)
        for (k in 1 until keep.size) {
            val base = keep[k] * STEM_SPEC
            for (i in 0 until STEM_SPEC) sumCac[i] += specOut[base + i]
        }
        stft.inverse(sumCac, SEG, waveL, waveR)

        // center_trim: output sample j (weight[j]) comes from segment position delta/2 + j.
        val read = delta / 2
        for (j in 0 until clen) {
            var tl = 0f
            var tr = 0f
            for (s in keep) {
                tl += timeOut[(2 * s) * SEG + read + j]
                tr += timeOut[(2 * s + 1) * SEG + read + j]
            }
            val g = weight[j]
            val cell = ((off + j) % OUT_CAP).toInt()
            outL[cell] += g * (waveL[read + j] + tl)
            outR[cell] += g * (waveR[read + j] + tr)
            wsum[cell] += g
        }

        nextChunkOff += STRIDE
        chunksDone++
        flush(min(nextChunkOff, shiftedLen)) // samples below the next chunk's start are final
        onChunk(chunksDone, totalChunks)
    }

    /** Emit finalized virtual positions [flushPos, limit) ∩ [MAX_SHIFT, MAX_SHIFT+totalFrames), zeroing cells. */
    private fun flush(limit: Long) {
        var n = 0
        while (flushPos < limit) {
            val cell = (flushPos % OUT_CAP).toInt()
            if (flushPos >= MAX_SHIFT && emitted < totalFrames) {
                val w = wsum[cell] // > 0: every emitted position is covered by ≥1 chunk
                emitBuf[2 * n] = softclip((outL[cell] / w) * std + mean)
                emitBuf[2 * n + 1] = softclip((outR[cell] / w) * std + mean)
                n++
                emitted++
            }
            outL[cell] = 0f
            outR[cell] = 0f
            wsum[cell] = 0f
            flushPos++
        }
        if (n > 0) emit(emitBuf, n)
    }

    // PRD soft-clip guard: transparent below 0.95, tanh knee bounding |y| < 1.0 (dsp-spec §6).
    private fun softclip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.95f) return x
        return sign(x) * (0.95f + tanh((a - 0.95) / 0.05).toFloat() * 0.05f)
    }

    companion object {
        /**
         * Segment geometry, fixed by the exported graph — change these only together with a matching
         * `scripts/htdemucs_export.py` re-export, or inference reads garbage.
         *
         * htdemucs' checkpoint segment is 7.8 s, but peak working set scales with it and 7.8 s measured
         * 3.24 GB RSS on an S23 — 2× over the PRD's 1.5 GB budget, and an OOM-kill on a 6 GB target
         * device. Measured on a 30 s clip (S23, peak RSS / wall-clock / deviation from the trained
         * 7.8 s output on the shipped vocals stem):
         *
         *   7.8 s  3.24 GB   1.4–4.2× realtime   reference
         *   3.9 s  1.61 GB   1.37× realtime      26.1 dB
         *   2.6 s  1.30 GB   1.33× realtime      24.2 dB   <- shipped
         *
         * 2.6 s is the only one that clears the budget with margin, and it costs ~2 dB of agreement
         * with the trained configuration for no speed penalty. ONNX-vs-torch conversion parity is
         * unaffected by segment length (f16 63.4/69.0 dB spec/wave at 2.6 s, vs 61.5/65.9 at 7.8 s).
         */
        const val SEG = 114_660            // int(2.6 s × 44100)
        const val STRIDE = 85_995          // int((1 − 0.25) × SEG) — apply.py truncates, so do we
        const val MAX_SHIFT = 22_050       // 0.5 s; deterministic shift_offset = 0 draw
        const val BINS = 2048              // NFFT/2 — the model drops the Nyquist bin
        const val LE = 112                 // ceil(SEG / HOP)
        const val STEM_SPEC = 4 * BINS * LE // one stem's CaC block in the model output
        private const val NFFT = 4096
        private const val HOP = 1024
        private const val OTHER = 2        // stem order: drums=0, bass=1, other=2, vocals=3
        private const val VOCALS = 3
        private const val IN_CAP = 2 * SEG          // retains ≥ 1.5×SEG lookback the tail chunks need
        private const val OUT_CAP = SEG + STRIDE    // unflushed span never exceeds one SEGMENT
    }
}

/**
 * ORT session owner for [NaqiModel.HTDEMUCS] — provides the real `infer` for [DemucsSeparator].
 * CPU-EP session options (see [sessionOptions]). Inputs are matched to the graph by rank (wav =
 * rank 3, spec = rank 4), outputs likewise (spec = rank 5, time = rank 4). Input tensors are backed
 * by two DIRECT buffers allocated once and reused every chunk — passing heap arrays makes ORT
 * allocateDirect ~14 MB per call, and that non-movable churn OOMs ART's 256 MB heap mid-job. The
 * returned arrays are REUSED across calls too — the caller must consume them before the next
 * [infer] (DemucsSeparator does, within the same chunk).
 */
class HtdemucsSession(context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val specOut = FloatArray(4 * DemucsSeparator.STEM_SPEC)
    private val timeOut = FloatArray(4 * 2 * DemucsSeparator.SEG)
    private val wavDirect: FloatBuffer = ByteBuffer.allocateDirect(2 * DemucsSeparator.SEG * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val specDirect: FloatBuffer = ByteBuffer.allocateDirect(DemucsSeparator.STEM_SPEC * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        val file = ModelSmoke.modelFile(context, NaqiModel.HTDEMUCS)
            ?: error("htdemucs model not bundled — run scripts/fetch-models.sh")
        session = sessionOptions().use { env.createSession(file.absolutePath, it) }
    }

    fun infer(wav: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
        wavDirect.clear()
        wavDirect.put(wav)
        wavDirect.rewind()
        specDirect.clear()
        specDirect.put(spec)
        specDirect.rewind()
        val feeds = HashMap<String, OnnxTensor>(2)
        try {
            for ((name, info) in session.inputInfo) {
                feeds[name] = when ((info.info as TensorInfo).shape.size) {
                    3 -> OnnxTensor.createTensor(env, wavDirect, longArrayOf(1, 2, DemucsSeparator.SEG.toLong()))
                    4 -> OnnxTensor.createTensor(
                        env, specDirect,
                        longArrayOf(1, 4, DemucsSeparator.BINS.toLong(), DemucsSeparator.LE.toLong()),
                    )
                    else -> error("unexpected htdemucs input rank for $name")
                }
            }
            session.run(feeds).use { result ->
                for (i in 0 until result.size()) {
                    val tensor = result[i] as OnnxTensor
                    when ((tensor.info as TensorInfo).shape.size) {
                        5 -> tensor.floatBuffer.get(specOut) // [1,4,4,2048,336] masked spec
                        4 -> tensor.floatBuffer.get(timeOut) // [1,4,2,343980] time branch
                        else -> error("unexpected htdemucs output rank")
                    }
                }
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
        return specOut to timeOut
    }

    override fun close() {
        session.close()
    }

    // Mirror of Infer.sessionOptions (XNNPACK EP, single intra-op thread, spinning disabled) PLUS
    // memory clamps: the default BFC arena + memory-pattern planning retain multi-GB high-water
    // allocations across htdemucs runs (lmkd killed the app at 5.6 GB RSS without these two).
    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        // CPU EP (multi-threaded), NOT XNNPACK: XNNPACK's fp16 kernels corrupt this f16 graph's
        // spectral branch on-device (broadband-noise stems; time branch survives) — same family of
        // fp16 defects that already disqualified XNNPACK for the NSFW model in M0.
        // Arena OFF: with the arena, each run's high-water stays resident across every chunk and
        // Samsung's global memory watchdog kills the app; without it RSS drops back between chunks
        // and long jobs survive. The peak itself is intrinsic to the graph (NOT thread scratch —
        // 1 thread peaks HIGHER), which is why M3 re-exported at a 3.9 s segment; see [SEG].
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
        addConfigEntry("session.intra_op.allow_spinning", "0")
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }
}
