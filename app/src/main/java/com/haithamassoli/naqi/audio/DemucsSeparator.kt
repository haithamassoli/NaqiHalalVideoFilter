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
 * - segments of [SEG] samples every [STRIDE] (10 % overlap — see [STRIDE]), triangle-weighted
 *   overlap-add with weight-sum division (`TRANSITION_POWER = 1.0`);
 * - short tail chunks assembled per torch `TensorChunk.padded` (real left context, zeros past the
 *   end, outputs read back center-trimmed) — NOT the C++ centering bug documented in dsp-spec §3;
 * - kept-stem masked SPECTROGRAMS are summed before ONE iSTFT per chunk (linearity, dsp-spec §4),
 *   then the kept time branches are added; drums/bass are never kept.
 *
 * Two things here are NOT the reference, both from `plan-v2`:
 * - **the track length is learned, not declared** (A3 / item 7.5) — see [estimatedFrames] and [framesFed];
 * - **a chunk with no music passes through** instead of being separated (A1) — see [musicScore] and
 *   [passthroughChunk]. That is a quality change as well as a speed one: in "vocals" mode the sound
 *   effects of every dialogue-only stretch survive instead of being destroyed with the music
 *   (`plan-v2` §5.10, against the tradeoff `prd-video-filter-android.md:80` documents and accepts).
 *
 * The PRD soft-clip guard (tanh knee at 0.95) is applied on the denormalized sum at emit time.
 * Contract: single-threaded (one worker drives feed/finish); [emit] receives interleaved stereo.
 */
class DemucsSeparator(
    keepOther: Boolean,
    mean: Float,
    std: Float,
    /**
     * Frames the CONTAINER says the track holds. **A progress denominator and nothing else** (A3,
     * `plan-v2` §5.7). It used to be `totalFrames` and it fixed the entire chunk grid, the emitted-frame
     * cap and the tail geometry — which meant a stream that delivered fewer frames than declared was
     * silently zero-padded to look complete (correctness item 7.5). The end of the grid now resolves in
     * [finish] from [framesFed], so this may be wrong in either direction and the only consequence is a
     * progress bar that finishes slightly early or late.
     */
    private val estimatedFrames: Long,
    /**
     * One chunk through the model -> (masked spec, time branch) for the KEPT stems only, **compacted**:
     * `keptStems(keepOther).size` contiguous blocks in that array's order, NOT the graph's four (A4,
     * `plan-v2` §5.9). Both arrays may be reused across calls; this class consumes them within the chunk.
     */
    private val infer: (wav: FloatArray, spec: FloatArray) -> Pair<FloatArray, FloatArray>,
    private val onChunk: (done: Int, total: Int) -> Unit,
    /**
     * Frames an interrupted earlier run already wrote to the PCM scratch (`long-film-plan.md` Phase 2).
     * Their inference is skipped and they are not re-emitted; everything from [resumeFrames] on is
     * bit-identical to an uninterrupted run, provided the same input is fed again.
     *
     * Declared before [emit] so [emit] stays the trailing parameter its callers pass as a lambda.
     */
    private val resumeFrames: Long = 0L,
    /**
     * A1 (`plan-v2` §5.5): "how much music is in this window of mono 44.1 kHz audio?" — null disables the
     * gate and every chunk is separated, which is also what happens when the model is not installed.
     * A SCORE rather than the old boolean since C1, because the far half of the dilation now depends on
     * how much a chunk has in it and not only on whether it cleared the gate — see [separateChunk].
     *
     * The callback takes DENORMALIZED mono (real amplitudes), because a music classifier and a silence
     * floor both need the signal the user would hear, not this class's unit-variance view of it. It is a
     * lambda rather than a `MusicGate` so this class stays Android-free for its JVM tests.
     */
    private val musicScore: ((mono: FloatArray, frames: Int) -> Float)? = null,
    private val emit: (interleaved: FloatArray, frames: Int) -> Unit,
) {
    // A4: the absolute stem ids are the SESSION's business now (it does the compaction on the way out of
    // ORT), so all this class needs is how many blocks came back. Derived through the same [keptStems]
    // the caller used to build the session, so the two cannot drift apart.
    private val nKeep = keptStems(keepOther).size
    private val mean = mean
    private val std = std.coerceAtLeast(1e-8f) // same scalar for normalize and denormalize

    private val totalChunks = ((estimatedFrames + MAX_SHIFT + STRIDE - 1) / STRIDE).toInt()

    // Input ring: normalized planar samples addressed by absolute virtual position (lead zeros are
    // the ring's own zero-init; positions ≥ writePos and < 0 read as zero = TensorChunk out-of-range).
    private val inL = FloatArray(IN_CAP)
    private val inR = FloatArray(IN_CAP)
    private var writePos = MAX_SHIFT // virtual positions [0, MAX_SHIFT) are the pre-pad zeros

    /**
     * One past the last real input position — i.e. the end of the chunk grid. `Long.MAX_VALUE` while
     * input is still arriving, resolved to [writePos] by [finish]. This is A3/7.5 in one line: the
     * length is LEARNED from the stream instead of declared, so a decode that stops early produces a
     * shorter output rather than a full-length one padded with silence.
     */
    private var endPos = Long.MAX_VALUE

    // Output ring: weighted overlap-add accumulator + weight sums; cells are zeroed as they flush.
    private val outL = FloatArray(OUT_CAP)
    private val outR = FloatArray(OUT_CAP)
    private val wsum = FloatArray(OUT_CAP)
    private var flushPos = 0L // virtual position below which everything was emitted (or skipped as pre-pad)
    private var emitted = 0L

    /** Chunks the grid has advanced past, inferred or passed through. Read by the caller for the A1 log. */
    var chunksDone = 0
        private set

    private var nextChunkOff = 0L

    init {
        /*
         * `ceil(SEG/STRIDE) == 2` — at most two chunks cover any one output position. This was a comment;
         * `plan-v2` §5.6 asks for it as an assertion, because it is what an overlap change silently
         * breaks and THREE things ride on it:
         *
         * - [skipChunks] under-skips (safe, re-runs a chunk) but never over-skips (a hole in the ring)
         *   only while `floor(R/STRIDE) - floor((R−SEG)/STRIDE) <= 2`, which is this bound exactly.
         * - [OUT_CAP] `= SEG + STRIDE` is the span of the two live chunks. With three live it would alias.
         *   217_854 cells at 10 % vs 200_655 at 25 % — the ring auto-sizes off STRIDE, it just got roomier.
         * - [IN_CAP] `= 2*SEG + LOOKAHEAD` covers the worst-case lookback. [feed] slices at SEG/2 and
         *   fires at most ONE chunk per slice (STRIDE > SEG/2), so `writePos < off + SEG + LOOKAHEAD +
         *   SEG/2` when a chunk fires, and [inferChunk] reads back to `off − delta/2 >= off − SEG/2` —
         *   span < 2*SEG + LOOKAHEAD. At [finish] the un-fired chunks satisfy `off > writePos − SEG −
         *   LOOKAHEAD`, so their span is < 1.5*SEG + LOOKAHEAD, which is smaller. Raising STRIDE only
         *   fires chunks less often; it cannot widen either window.
         *
         * SEG/STRIDE was 1.333 at 25 % and is 1.111 at the shipped 10 %; 50 % would be exactly 2.0, which
         * still holds. It is overlap ABOVE 50 % that pushes the ceiling to 3 and breaks all three at once.
         */
        check(STRIDE < SEG && SEG <= 2 * STRIDE) { "ceil(SEG/STRIDE) must be 2: SEG=$SEG STRIDE=$STRIDE" }
    }

    /**
     * Chunks whose inference can be skipped on a resume.
     *
     * An emitted frame `e` lives at virtual position `MAX_SHIFT + e`, and chunk `c` covers positions
     * `[c*STRIDE, c*STRIDE + clen)`. `ceil(SEG/STRIDE) = 2` (asserted above), so any one output position
     * is covered by at most TWO chunks — which means exactly ONE chunk before the resume point has to be
     * re-run to rebuild the overlap-add ring for the first frame we still owe. A checkpoint is always
     * taken at a chunk boundary, where this is exactly `chunksDone - 1`.
     *
     * ponytail: the exactly-minimal form is `floorDiv(MAX_SHIFT + resumeFrames - SEG, STRIDE) + 1`.
     * Identical at every value a checkpoint can actually hold, and off-boundary this form re-runs at most
     * one extra chunk (2.6 s of audio), so it is not worth the negative-numerator care.
     */
    private val skipChunks = ((MAX_SHIFT + resumeFrames) / STRIDE - 1).coerceAtLeast(0L).toInt()

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

    // A1 gate state. One chunk's window, denormalized and folded to mono, plus the last few scores.
    private val gateMono = FloatArray(SEG)
    private val gateScore = FloatArray(GATE_RING)
    private var gateFrom = Int.MAX_VALUE // lowest chunk index whose score is in [gateScore]
    private var gateTo = -1              // highest, inclusive

    /** Chunks the A1 gate passed through instead of separating. Read by the caller for the skip-rate log. */
    var skippedChunks = 0
        private set

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
            // Process every chunk whose full segment AND its [LOOKAHEAD] are available; the rest wait for
            // finish(). The old `nextChunkOff < shiftedLen` guard is gone with the declared length — the
            // condition below already implies `nextChunkOff < writePos <= endPos`.
            while (nextChunkOff + SEG + LOOKAHEAD <= writePos) processChunk()
        }
    }

    /**
     * Frames the stream actually delivered, i.e. what [feed] was handed in total (correctness item 7.5).
     * Read after [finish]: this is the authoritative length of the output, and the caller compares it
     * against the container's estimate to surface a short decode (this class stays Android-free for its
     * JVM tests, so it cannot log one itself).
     */
    val framesFed: Long get() = (writePos - MAX_SHIFT).toLong()

    /**
     * Samples the model returned as NaN or ±Inf, replaced with silence by [finite]. Read after [finish]
     * and logged by the caller — a non-zero count means the separated audio has holes in it.
     *
     * **Not a theoretical guard.** The shipped graph is `htdemucs_s26_f16.onnx`, i.e. fp16, and the
     * input is normalized by whole-track std — so a passage far louder than the track average pushes
     * intermediate activations toward fp16's ~65504 ceiling and a chunk can come back non-finite.
     * Observed 2026-07-29 on a 10.5-minute source: the run died at the very end, after 6.5 minutes of
     * separation, with `IllegalArgumentException: Cannot round NaN value` from `AacWriter`'s int16
     * quantizer. Silence is the only defensible value for a sample that has none, and losing a few
     * samples must never cost the whole job.
     */
    var nonFinite = 0L
        private set

    /**
     * Wall nanos spent inside each phase of a chunk, accumulated over the run and read by the caller
     * (this class stays Android-free for its JVM tests, so it cannot log them itself).
     *
     * `plan-v2` §5.9 asked for the first three before anyone sized A5: the STFT sits inside a stage that
     * had never been timed internally, so its share "could be 2 % or 15 %" and the plan refused to rank
     * the work until someone knew which. M2 (`perf-plan-v4` §2) adds the rest, because those three still
     * left **110 699 ms unattributed — 24.6 % of a 449 376 ms stage** — and on a music-removal job that
     * stage alone IS the wall (§1 shape C), so its residual pays 1:1. What is still outside all of them
     * is the caller's: the decode driving [feed], the thermal yield between chunks, and session creation.
     */
    var stftNs = 0L
        private set

    /** ORT inference only, i.e. [infer]. See [stftNs]. */
    var inferNs = 0L
        private set

    /** iSTFT + the spec sum + the weighted overlap-add loop. See [stftNs]. */
    var olaNs = 0L
        private set

    /** The A1 gate: [scoreChunk]'s denormalized mono fold plus the YAMNet runs inside it. See [stftNs]. */
    var gateNs = 0L
        private set

    /** [inferChunk]'s input-ring gather, i.e. everything it does before the STFT. See [stftNs]. */
    var gatherNs = 0L
        private set

    /** [flush]'s divide / soft-clip loop, WITHOUT the [emit] call — that one is [encodeNs]. See [stftNs]. */
    var flushNs = 0L
        private set

    /**
     * Time inside [emit], i.e. the caller's AAC encoder (short clips) or int16 scratch write (films).
     * Split out of [flushNs] because C4 aims at the encoder and nothing else in flush. See [stftNs].
     */
    var encodeNs = 0L
        private set

    /** NaN/±Inf out of inference becomes silence, and is counted. See [nonFinite]. */
    private fun finite(x: Float): Float {
        if (x.isFinite()) return x
        nonFinite++
        return 0f
    }

    /**
     * Resolve the true length from what was actually fed, process the remaining (short) chunks, and flush
     * the tail. After this, `emitted == framesFed` by construction — the walk below covers exactly
     * `[MAX_SHIFT, endPos)` and nothing caps it, which is why the old `shortfall` equality check is gone
     * rather than relaxed: it compared `emitted` against a number that no longer exists. The mismatch
     * worth surfacing (item 7.5) is [framesFed] against the container's estimate, and that is the
     * caller's to log because only the caller has the estimate.
     */
    fun finish() {
        endPos = writePos.toLong()
        if (framesFed <= 0L) return // nothing decoded: emit nothing rather than infer a chunk of silence
        while (nextChunkOff < endPos) processChunk()
    }

    /**
     * Advance the chunk grid by one. On a resume the DSP is skipped for the first [skipChunks] chunks, but
     * the four bookkeeping lines still run: `flushPos` and `emitted` have to walk the skipped span or the
     * resume point drifts and the whole chunk grid moves. The flush's cell zeroing is harmless over a
     * skipped span (nothing was accumulated there), and its window is always the NEXT chunk's start, so it
     * can never erase a cell the first inferred chunk writes.
     */
    private fun processChunk() {
        if (chunksDone >= skipChunks) {
            if (separateChunk(chunksDone)) inferChunk(nextChunkOff)
            else { skippedChunks++; passthroughChunk(nextChunkOff) }
        }
        nextChunkOff += STRIDE
        chunksDone++
        flush(min(nextChunkOff, endPos)) // samples below the next chunk's start are final
        // Skipped chunks must not report progress: on a film resumed near the end that would fire hundreds
        // of times in a second, spam the per-chunk log, call thermalYield with nothing hot, and — worst —
        // poison JobStats.etaMs, which extrapolates from elapsed-vs-percent. One call at the transition
        // hands the UI the correct resumed percentage.
        // max(): totalChunks is a container-duration ESTIMATE since A3, so a track that outruns it must
        // not hand the caller done > total and push the bar past its band.
        if (chunksDone >= skipChunks) onChunk(chunksDone, maxOf(totalChunks, chunksDone))
    }

    /**
     * A1 (`plan-v2` §5.5): does chunk [c] have to go through the model, or can its input pass through?
     *
     * Music-free chunks are separated anyway unless the gate is confident about the [DILATE] chunks on
     * EITHER side too — fades, stings and quiet bars are where a frame-level classifier is least sure,
     * and a missed music chunk is an audible product failure where a spurious separation costs one chunk
     * of wall clock. Everything here fails toward separating.
     *
     * **The dilation is TWO-TIER since C1** (`perf-plan-v4` §5), and the split is by distance:
     * - **±1 is hard.** A music chunk one away forces separation whatever [c] itself scored — a fade out
     *   of music lands here, and so does the chunk boundary in the middle of a sting.
     * - **±2 only rescues a chunk that has something in it**, i.e. one scoring [DILATE2_MIN_SCORE] or
     *   more. Two chunks away is 4.7 s; a chunk that quiet, that far from any music, has nothing in it
     *   for the separator to take out. That constant carries the measurement.
     *
     * Scoring only ever moves FORWARD, and it has to: [feed] holds `2*SEG + LOOKAHEAD` of input, which at
     * the moment chunk `c` fires covers chunk `c+DILATE`'s window exactly (that is what [LOOKAHEAD] buys)
     * and does NOT reach back to chunk `c-DILATE`'s. The backward half of the dilation is therefore served
     * from REMEMBERED decisions, and a chunk that was never scored counts as music. On a fresh run that
     * never happens — chunk 0 scores 0..DILATE and everything below 0 is silence. It happens after a
     * RESUME, where the first processed chunk lands mid-film with no history, and costs [DILATE] chunks of
     * unnecessary separation once per resume.
     */
    private fun separateChunk(c: Int): Boolean {
        val gate = musicScore ?: return true
        var i = maxOf(gateTo + 1, c)
        while (i <= c + DILATE) {
            if (gateFrom == Int.MAX_VALUE) gateFrom = i
            val t0 = System.nanoTime()
            gateScore[i % GATE_RING] = scoreChunk(i, gate)
            gateNs += System.nanoTime() - t0
            gateTo = i
            i++
        }
        for (k in (c - DILATE)..(c + DILATE)) {
            if (k < 0) continue                 // before the film starts: there is no music there
            if (k < gateFrom) return true       // never scored (start of a run, or a resume) -> separate
            if (gateScore[k % GATE_RING] < MusicGate.THRESHOLD) continue // `const`, so inlined — no Android here
            // C1's two tiers. Reading [c]'s own score needs no guard: [gateFrom] is the first chunk this
            // separator was ever asked about, i.e. <= c, so an unscored [c] is impossible here — and a
            // resume's unscored BACKWARD ring already returned true on the line above.
            if (k in c - 1..c + 1 || gateScore[c % GATE_RING] >= DILATE2_MIN_SCORE) return true
        }
        return false
    }

    /**
     * Fill [gateMono] with chunk [c]'s window, denormalized and folded to mono, and score it. Only the
     * REAL samples are handed over — a tail chunk hanging past the end of the stream would otherwise be
     * scored mostly on zeros and read as silence.
     */
    private fun scoreChunk(c: Int, gate: (FloatArray, Int) -> Float): Float {
        val off = c.toLong() * STRIDE
        val n = min(SEG.toLong(), writePos - off).coerceAtLeast(0L).toInt()
        if (n == 0) return 0f // entirely past the end of the stream: nothing there to separate
        for (j in 0 until n) {
            // Denormalize the mono fold in one step: ((l*std+mean) + (r*std+mean)) / 2. The pre-pad
            // positions below MAX_SHIFT are the ring's own zeros and denormalize to `mean`, which is the
            // DC of the track — right, and inaudible either way.
            val cell = ((off + j) % IN_CAP).toInt()
            gateMono[j] = 0.5f * (inL[cell] + inR[cell]) * std + mean
        }
        return gate(gateMono, n)
    }

    private fun inferChunk(off: Long) {
        val clen = min(SEG.toLong(), endPos - off).toInt()
        val delta = SEG - clen // > 0 only for tail chunks
        val readStart = off - delta / 2 // TensorChunk.padded: real left context, zeros out of range

        val tg = System.nanoTime()
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

        // A handful of nanoTime reads against a ~2 s chunk cost nothing, and they are the only way anyone
        // sizes A5 for real: `plan-v2` §5.9 flags that this whole stage is un-instrumented internally, so
        // the STFT work "could be 2 % or 15 %". See [stftNs].
        val t0 = System.nanoTime()
        val spec = stft.forward(segL, segR, SEG)
        val t1 = System.nanoTime()
        val (specOut, timeOut) = infer(wav, spec)
        val t2 = System.nanoTime()

        // Sum kept masked specs (one iSTFT total — dsp-spec §4), then kept time branches per sample.
        // A4: both arrays hold ONLY the kept stems, packed 0..nKeep-1, so the index is the compacted one
        // and never the stem id.
        System.arraycopy(specOut, 0, sumCac, 0, STEM_SPEC)
        for (k in 1 until nKeep) {
            val base = k * STEM_SPEC
            for (i in 0 until STEM_SPEC) sumCac[i] += specOut[base + i]
        }
        stft.inverse(sumCac, SEG, waveL, waveR)

        // center_trim: output sample j (weight[j]) comes from segment position delta/2 + j.
        val read = delta / 2
        for (j in 0 until clen) {
            var tl = 0f
            var tr = 0f
            for (k in 0 until nKeep) {
                tl += timeOut[(2 * k) * SEG + read + j]
                tr += timeOut[(2 * k + 1) * SEG + read + j]
            }
            val g = weight[j]
            val cell = ((off + j) % OUT_CAP).toInt()
            outL[cell] += g * (waveL[read + j] + tl)
            outR[cell] += g * (waveR[read + j] + tr)
            wsum[cell] += g
        }
        gatherNs += t0 - tg
        stftNs += t1 - t0
        inferNs += t2 - t1
        olaNs += System.nanoTime() - t2
    }

    /**
     * A1: the same overlap-add write as [inferChunk], with the chunk's own input in place of the model's
     * output. Deliberately NOT a separate bypass that copies input straight to the sink:
     *
     * - identical `weight[j]` / `wsum[cell]` bookkeeping, so [flush]'s `outL/wsum` divide is unchanged and
     *   a fully-skipped run reconstructs the input exactly (Σ g·x / Σ g == x);
     * - at a skipped/separated boundary the triangular window becomes a natural crossfade instead of a
     *   hard seam, because the two chunks that cover that position contribute under the same weights.
     *
     * Positions are the same as [inferChunk]'s: output `off + j` reads segment position `delta/2 + j`,
     * i.e. absolute position `off + j`, so this reads the ring at `off + j` with no trim arithmetic.
     */
    private fun passthroughChunk(off: Long) {
        val clen = min(SEG.toLong(), endPos - off).toInt()
        val t0 = System.nanoTime()
        for (j in 0 until clen) {
            val p = off + j
            val g = weight[j]
            val cell = (p % OUT_CAP).toInt()
            if (p < writePos) { // past the fed stream reads as zero, exactly as inferChunk's gather does
                val src = (p % IN_CAP).toInt()
                outL[cell] += g * inL[src]
                outR[cell] += g * inR[src]
            }
            wsum[cell] += g
        }
        olaNs += System.nanoTime() - t0
    }

    /** Emit finalized virtual positions [flushPos, limit) ∩ [MAX_SHIFT, endPos), zeroing cells. */
    private fun flush(limit: Long) {
        val t0 = System.nanoTime()
        var n = 0
        while (flushPos < limit) {
            val cell = (flushPos % OUT_CAP).toInt()
            // No `emitted < totalFrames` cap any more (A3): every caller passes `limit <= endPos`, so the
            // walk stops at the last real input position on its own and `emitted` lands on [framesFed].
            if (flushPos >= MAX_SHIFT) {
                // The discard for a resume lives HERE, not in the sink: over a skipped span nothing was
                // accumulated, so wsum is 0 and the divide below would be 0f/0f = NaN. Skipping the whole
                // computation also avoids millions of pointless divide+softclip pairs and keeps the sink a
                // dumb append. `emitted` still advances — it is the grid position, not a write count.
                if (emitted >= resumeFrames) {
                    val w = wsum[cell] // > 0: every emitted position is covered by ≥1 chunk
                    emitBuf[2 * n] = finite(softclip((outL[cell] / w) * std + mean))
                    emitBuf[2 * n + 1] = finite(softclip((outR[cell] / w) * std + mean))
                    n++
                }
                emitted++
            }
            outL[cell] = 0f
            outR[cell] = 0f
            wsum[cell] = 0f
            flushPos++
        }
        val t1 = System.nanoTime()
        flushNs += t1 - t0
        if (n > 0) { emit(emitBuf, n); encodeNs += System.nanoTime() - t1 }
    }

    companion object {
        /**
         * The stems this driver keeps, as absolute indices into the graph's four, **ascending**.
         *
         * Public because [HtdemucsSession] is constructed with it (A4 — the session copies only these
         * slices out of ORT) and the driver derives its own block count from it, so there is one
         * definition and no way for the two to disagree. Ascending matters twice: the session's
         * `FloatBuffer` reads then only ever walk forward, and compacted index `k` means the same stem in
         * the spec array and the time array.
         */
        fun keptStems(keepOther: Boolean): IntArray =
            if (keepOther) intArrayOf(OTHER, VOCALS) else intArrayOf(VOCALS)

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

        /**
         * **10 % overlap, down from `apply.py`'s 25 % default** (`plan-v2` §5.6, A2). Chunk count scales
         * as `1/(1 − overlap)`, so this is 16.7 % fewer inferences — a 1.20× throughput win on the stage
         * that is 55–65 % of a film job, for one constant. `int(0.90 × 114_660) = 103_194` exactly; the
         * reference truncates, so do we. The overlap-add stays mathematically sound at any overlap below
         * 50 % because [weight] is normalized and [wsum] divides the window sum out (TRANSITION_POWER = 1).
         *
         * The 25 % was inherited unexamined, and the model's own author calls it a guess — the Demucs
         * README: *"the default of 0.25 … can probably be reduced to 0.1 to improve speed a bit."*
         * Reference implementations do not agree with each other either (UVR treats 50 % as best quality;
         * BandIt evaluates at 91.7 % overlap).
         *
         * **The §5.6 gate HAS now been run, and its verdict is that the gate itself cannot decide this.**
         * Wave agreement against the 25 % reference, `test-video.mp4`, whole output, plus a control that
         * moves the chunk grid while holding the overlap at 25 % ([MAX_SHIFT] 22_050 → 33_075):
         *
         *   identical config, re-run          ∞ dB (bit-exact — the pipeline is deterministic)
         *   25 % overlap, grid phase +0.25 s  18.46 dB   <- CONTROL: overlap unchanged
         *   20 % overlap                      16.39 dB
         *   10 % overlap  (shipped)           15.94 dB
         *
         * The control is the finding. Moving the grid by a quarter of a second, with the overlap
         * untouched, already costs 18.5 dB — so "agreement with the 25 % reference" is dominated by
         * htdemucs' sensitivity to WHERE the chunk boundaries fall, not by how much the chunks overlap.
         * Dropping to 10 % costs only ~2.5 dB beyond that floor, and 20 % measures the same as 10 % while
         * buying nothing. A re-gridded reference cannot separate seam quality from grid phase, so the
         * metric `plan-v2` proposed is not a pass/fail on its own.
         *
         * Kept at 10 % on that reading plus the author's own endorsement. What would actually settle it is
         * a listening test on music with transients and quiet passages — not another dB number. If it ever
         * fails one, this line is the entire revert; nothing else here hardcodes the overlap.
         */
        const val STRIDE = 103_194
        const val MAX_SHIFT = 22_050       // 0.5 s; deterministic shift_offset = 0 draw
        const val BINS = 2048              // NFFT/2 — the model drops the Nyquist bin
        const val LE = 112                 // ceil(SEG / HOP)
        const val STEM_SPEC = 4 * BINS * LE // one stem's CaC block in the model output
        private const val NFFT = 4096
        private const val HOP = 1024
        private const val OTHER = 2        // stem order: drums=0, bass=1, other=2, vocals=3
        private const val VOCALS = 3

        /**
         * A1 (`plan-v2` §5.5): "never gate off within ±[DILATE] chunks of any music-positive frame". The
         * decision for chunk `c` therefore cannot be taken until `c + DILATE` has been scored, so the
         * separator runs [LOOKAHEAD] samples BEHIND the stream — a chunk fires only once the input for
         * `c + DILATE` is also in the ring.
         *
         * Kept unconditional, even with no gate installed. A second firing schedule for the ungated case
         * would double the surface of the most delicate code in the app to save 1.7 MB of ring and a
         * 4.8 s lag on the emit stream, neither of which anything downstream can observe.
         */
        private const val DILATE = 2
        private const val LOOKAHEAD = DILATE * STRIDE

        /**
         * C1 (`perf-plan-v4` §5): the gate score a chunk needs of its OWN before the ±2 tier of [DILATE]
         * will rescue it. Below this it is separated only when music sits within ±1. **The ±1 tier is
         * unconditional and this does not touch it**, which is the half of the dilation that catches
         * fades. [LOOKAHEAD] is unchanged too — it already covers ±[DILATE] in both tiers.
         *
         * **Measured, do not re-derive.** Replicated off-device against the shipped gate on the real audio
         * of `qa-assets/tv1.webm`: **141/276 chunks skipped against the device's 136/276** (−40 000 ms,
         * 8.9 % of a 449 376 ms stage) at **271/276 chunk agreement** — i.e. the two rules disagree on
         * exactly the 5 chunks this tier newly drops, and every one of those scored **≤ 0.0139, below
         * white noise**, which measures 0.0240 on this exact YAMNet artifact.
         * ([MusicGate.THRESHOLD], i.e. what counts as music at all, is 0.15.)
         *
         * `perf-plan-v4` §5's C1 row says "the 20 extra dropped chunks score ≤ 0.0139". That 20 does not
         * reconcile with its own 141-vs-136 and 271/276 in the same sentence, both of which give 5; the
         * bound on the scores is the load-bearing half and is quoted above unchanged. Settle it from the
         * device's own `music gate: skipped N/276` line rather than from either number.
         *
         * This is the miss-rate dial of the product, so it moves in one direction easily and the other
         * only with a listening test: a missed music chunk is an audible failure the user cannot fix, a
         * spurious separation costs one chunk of wall clock. Set it to 0f to get the old one-tier
         * dilation back — that is the whole revert.
         */
        private const val DILATE2_MIN_SCORE = 0.02f

        /** Scores kept for the ±[DILATE] window; anything above `2*DILATE + 1` (=5) works. */
        private const val GATE_RING = 8

        private const val IN_CAP = 2 * SEG + LOOKAHEAD // worst-case span at a chunk fire — see the init check
        private const val OUT_CAP = SEG + STRIDE       // exactly the span of the two chunks that can be live
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
 *
 * [keep] is the ascending set of stems to read back, from [DemucsSeparator.keptStems]. Only those come
 * out of ORT and the outputs are compacted to them (A4, `plan-v2` §5.9): the graph emits all four either
 * way, but drums and bass are 14.7 MB of spec + 3.7 MB of time per chunk that the caller discarded on
 * arrival, so copying them was pure memcpy for the bin.
 */
class HtdemucsSession(context: Context, private val keep: IntArray) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val specOut = FloatArray(keep.size * DemucsSeparator.STEM_SPEC)
    private val timeOut = FloatArray(keep.size * 2 * DemucsSeparator.SEG)
    private val wavDirect: FloatBuffer = ByteBuffer.allocateDirect(2 * DemucsSeparator.SEG * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val specDirect: FloatBuffer = ByteBuffer.allocateDirect(DemucsSeparator.STEM_SPEC * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        val file = ModelSmoke.modelFile(context, NaqiModel.HTDEMUCS)
            ?: error("htdemucs model not bundled — run scripts/fetch-models.sh")
        // C2 (`perf-plan-v4` §5, setOptimizedModelFilePath) was implemented here and REMOVED after M2
        // measured what §5 could only call "NOT ESTIMABLE until M2". On an S23 over the 643 s tv1.webm:
        //
        //     sessionCreateMs = 881          i.e. 0.23 % of a 385 420 ms `separate` stage
        //     serialized graph = 157.6 MB    against the 87.9 MB .onnx it is built from
        //     peak RSS         = 1 267 532 -> 1 401 700 KB
        //
        // The graph is nearly 2x the model because ORT demotes the fp16 initializers to fp32 at load
        // (`perf-plan-v4` §6.1) and serializes the demoted form. So the trade was 157 MB of the user's
        // storage plus 10.6 % of a RAM budget lmkd has already killed this app over, to save at most
        // 0.9 s per session create — and even the film path, which pays it once per resume, cannot turn
        // that into a number worth the disk. Do not re-add it without a measurement that beats these.
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
                    // One buffer view per output — `floatBuffer` hands back a fresh view each call, so
                    // taking it once is what makes the position() walk below mean anything. Stems are one
                    // contiguous block each in both outputs, and [keep] is ascending, so this only ever
                    // seeks forward.
                    val fb = tensor.floatBuffer
                    when ((tensor.info as TensorInfo).shape.size) {
                        5 -> for (k in keep.indices) {       // [1,4,4,BINS,LE] masked spec
                            fb.position(keep[k] * DemucsSeparator.STEM_SPEC)
                            fb.get(specOut, k * DemucsSeparator.STEM_SPEC, DemucsSeparator.STEM_SPEC)
                        }
                        4 -> for (k in keep.indices) {       // [1,4,2,SEG] time branch
                            fb.position(keep[k] * 2 * DemucsSeparator.SEG)
                            fb.get(timeOut, k * 2 * DemucsSeparator.SEG, 2 * DemucsSeparator.SEG)
                        }
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

    // Deliberately NOT com.haithamassoli.naqi.ml.imageSessionOptions: that one is XNNPACK + a single
    // intra-op thread, and neither is right here. Plus memory clamps — the default BFC arena +
    // memory-pattern planning retain multi-GB high-water allocations across htdemucs runs (lmkd
    // killed the app at 5.6 GB RSS without these two).
    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        // CPU EP (multi-threaded), NOT XNNPACK: XNNPACK's fp16 kernels corrupt this f16 graph's
        // spectral branch on-device (broadband-noise stems; time branch survives) — same family of
        // fp16 defects that already disqualified XNNPACK for the NSFW model in M0.
        // Arena OFF: with the arena, each run's high-water stays resident across every chunk and
        // Samsung's global memory watchdog kills the app; without it RSS drops back between chunks
        // and long jobs survive. The peak itself is intrinsic to the graph (NOT thread scratch —
        // 1 thread peaks HIGHER), which is why the graph was re-exported at the 2.6 s [SEG] it ships at
        // today (M3 first went to 3.9 s; `perf-plan-v4` §6.2 then measured 2.6 s as the OPTIMUM rather
        // than a RAM compromise — 7.8 s costs +20.6 % compute per second of audio as well as 3.24 GB).
        // Neither of those two is a dial. The two below are — see their declarations.
        setIntraOpNumThreads(INTRA_OP_THREADS)
        addConfigEntry("session.intra_op.allow_spinning", ALLOW_SPINNING)
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }

    companion object {

        /**
         * The two dials of this stage, both SWEPT on an S23 2026-07-28 (`perf-plan.md` Phase 3.1/3.2),
         * median per-chunk ms over chunks 3-7:
         *
         *     threads 8 (=availableProcessors) 2244 | 6 **2136** | 4 2155      spinning 0 **2244** | 1 2305
         *
         * 6 wins by ~5 % because every intra-op barrier waits on the slowest thread and the S23's little
         * cores are it — 4 of 6's five chunks came in under 8's fastest. Capped rather than hardcoded so a
         * 4-core device still gets 4. Spinning stays "0": it was chosen for power and is ALSO faster here,
         * so there is nothing left to trade. Peak RSS moved <0.2 % across every config.
         */
        private val INTRA_OP_THREADS = Runtime.getRuntime().availableProcessors().coerceAtMost(6)
        private const val ALLOW_SPINNING = "0"
    }
}

/**
 * PRD soft-clip guard: transparent below 0.95, tanh knee bounding |y| < 1.0 (dsp-spec §6).
 *
 * File-level rather than a member of [DemucsSeparator] because it is the ENCODER's precondition, not
 * part of separation: [AacWriter.write] documents "already soft-clipped upstream", and this was the only
 * thing satisfying it. [AudioPipeline.transcodeToAac] runs the same decoder into the same encoder with no
 * separator in between, so it needs the identical guard. Still Android-free, so the JVM tests are unaffected.
 */
internal fun softclip(x: Float): Float {
    val a = abs(x)
    if (a <= 0.95f) return x
    return sign(x) * (0.95f + tanh((a - 0.95) / 0.05).toFloat() * 0.05f)
}
