package com.haithamassoli.naqi.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.haithamassoli.naqi.media.requireTrackIndex
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * M2 audio ingest. Decodes ANY source audio track (AAC/Opus/MP3/Vorbis — the codec CSD rides in
 * the extractor track format) to PCM16, hand-mixes to stereo, and resamples to 44100 Hz float via a
 * single [SonicAudioProcessor] session, emitting interleaved f32 stereo 44.1k batches.
 *
 * Two passes share one private [decode] routine (see android-media-spec §a/§b): [stats] samples the
 * track for the mono-mix mean/std (O(1) memory) that [DemucsSeparator] needs, then [stream] decodes it
 * in full, forwarding batches to the DSP. Both poll [isCancelled] once per decode dequeue and throw
 * [CancellationException] on cancel. Thread-confined (one worker at a time).
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L
    private const val SAMPLE_RATE = 44100

    /** -3 dB, the ITU-R BS.775 coefficient for folding center/surrounds into a stereo pair. */
    private const val HALF_POWER = 0.70710678f

    /**
     * Windows the A3 stats pass decodes, and how long each is. 20 x 2 s = 40 s of audio, evenly spaced
     * with the first at 0 and the last flush against the end, against a full decode of the whole track.
     *
     * ponytail: fixed geometry for every source. It is a mean and a std of a signal whose std is what the
     * separator divides by; the plan's bar is "within a fraction of a dB of the full-track value", and 40 s
     * spread across a film clears that with room. Set [STATS_WINDOWS] to 0 to restore the full pass — that
     * is the whole of the revert, and it is what a QA run diffs against.
     */
    private const val STATS_WINDOWS = 20
    private const val WINDOW_US = 2_000_000L

    /**
     * Whole-track summary. **[frames] is the authoritative total frame count, or 0 when it is not known**
     * — which is what [stats] now returns, because since A3 (`plan-v2` §5.7) it no longer decodes the
     * whole track and so cannot count it. The true length is learned by [DemucsSeparator] from the stream
     * it is fed ([DemucsSeparator.framesFed]); the resumable path in [AudioPipeline] persists that value
     * here once a complete run has resolved it, and treats 0 as "the separator has more to do".
     */
    class Stats(val frames: Long, val mean: Float, val std: Float, val firstPtsUs: Long)

    /**
     * Mono-mix mean/std from a stratified sample of the track (A3, `plan-v2` §5.7), plus the source's
     * first audio-sample PTS. Throws when nothing at all decodes — an empty or corrupt audio track.
     *
     * The normalization these two scalars drive is exactly invertible (applied on feed, undone on emit),
     * so their only job is presenting the model with roughly unit-variance input. Decoding the whole
     * 155-minute soundtrack a second time to compute them was ~5 min of a ~258 min job, and a visible
     * chunk of the 13 s fixed overhead on a 30 s clip.
     *
     * The sample uses ONE codec across every seek, flushed between windows, and deliberately skips the
     * [SonicAudioProcessor] — mean and std are rate-agnostic, and no resampler means no half-fed
     * resampler to reset after each flush. The channel fold is the shared one, which is NOT optional: a
     * 5.1 source folded differently here than in [stream] would hand the model input at the wrong level,
     * and this graph is fp16.
     */
    fun stats(context: Context, uri: Uri, isCancelled: () -> Boolean): Stats {
        var sum = 0.0
        var sumsq = 0.0
        var count = 0L
        val firstPtsUs = decode(context, uri, isCancelled, sampled = true) { interleaved, frames ->
            for (i in 0 until frames) {
                val m = (interleaved[2 * i] + interleaved[2 * i + 1]).toDouble() * 0.5
                sum += m
                sumsq += m * m
            }
            count += frames
        }
        if (count == 0L) error("Could not decode any audio from this video.")
        val mean = sum / count
        // Sample std with N-1 (Bessel); matches the python normalization the model was trained on.
        val variance = if (count > 1) ((sumsq - sum * sum / count) / (count - 1)).coerceAtLeast(0.0) else 0.0
        return Stats(0L, mean.toFloat(), sqrt(variance).toFloat(), firstPtsUs)
    }

    /**
     * Frames the CONTAINER claims the audio track holds, at [SAMPLE_RATE]; 0 when it will not say.
     *
     * An estimate, and used only as one: the progress denominator, which is cosmetic. The authoritative
     * count comes from [DemucsSeparator.framesFed] after the stream has actually been decoded.
     */
    fun estimateFrames(context: Context, uri: Uri): Long {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(context, uri, null)
            val f = ext.getTrackFormat(ext.requireTrackIndex("audio/"))
            if (!f.containsKey(MediaFormat.KEY_DURATION)) 0L
            else f.getLong(MediaFormat.KEY_DURATION) * SAMPLE_RATE / 1_000_000L
        } catch (_: Throwable) {
            0L // undecidable duration: the bar just runs off the container's word for it, or not at all
        } finally {
            runCatching { ext.release() }
        }
    }

    /** Decode+resample the whole track, streaming interleaved f32 stereo 44.1k batches to [sink]. */
    fun stream(context: Context, uri: Uri, isCancelled: () -> Boolean, sink: (FloatArray, Int) -> Unit) {
        decode(context, uri, isCancelled, sampled = false, sink = sink)
    }

    /**
     * The shared decode+resample loop. Mirrors [com.haithamassoli.naqi.analysis.FrameSampler.sample]'s
     * non-blocking-input / blocking-output dequeue idiom, adapted for audio. Reads the AUTHORITATIVE
     * rate/channels from INFO_OUTPUT_FORMAT_CHANGED (HE-AAC/Opus rewrite them), converts PCM16 to float
     * (/32768f), hand-fixes channels to stereo (mono duplicated, >2ch takes the first two — a 44100-only
     * Sonic bypass is independent of the channel fix), and drives ONE Sonic float session per stream.
     * Returns the source's first audio-sample PTS (0 when the track is empty).
     *
     * [sampled] runs the A3 stats geometry instead: [STATS_WINDOWS] seeks through one codec rather than
     * one pass over everything, and no resampler. It falls back to the full pass on a track too short for
     * sampling to pay, or one whose duration the container will not state.
     */
    private fun decode(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean,
        sampled: Boolean,
        sink: (FloatArray, Int) -> Unit,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var sonic: SonicAudioProcessor? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = extractor.requireTrackIndex("audio/")
            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val codecInst = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = codecInst
            codecInst.configure(trackFormat, null, null, 0) // CSD (Opus/Vorbis/AAC) rides in trackFormat
            codecInst.start()

            val windows = if (sampled) statsWindows(trackFormat) else null
            val info = MediaCodec.BufferInfo()
            var firstPtsUs = 0L
            var haveFirst = false
            // Provisional until INFO_OUTPUT_FORMAT_CHANGED (which normally precedes any output buffer).
            var pcmRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SAMPLE_RATE
            var pcmChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var resolved = false
            var useSonic = false

            // Hot buffers, preallocated and grown on demand (reused across every decoded buffer).
            var stereo = FloatArray(0)
            var outFloat = FloatArray(0)
            var inputDirect: ByteBuffer? = null

            // Resolve the resample decision once, from the authoritative rate; build the Sonic session.
            // A sampled pass never resamples: mean and std do not care about the rate, and skipping Sonic
            // is what lets the codec be flushed between seeks with nothing left holding partial state.
            fun ensureResolved() {
                if (resolved) return
                resolved = true
                useSonic = windows == null && pcmRate != SAMPLE_RATE
                if (useSonic) {
                    sonic = SonicAudioProcessor().apply {
                        setOutputSampleRateHz(SAMPLE_RATE) // OUTPUT rate — set BEFORE configure
                        configure(AudioProcessor.AudioFormat(pcmRate, 2, C.ENCODING_PCM_FLOAT))
                        flush(AudioProcessor.StreamMetadata.DEFAULT)
                    }
                }
            }

            // Drain all currently-available Sonic output to the sink; returns whether anything was consumed.
            fun drainSonic(): Boolean {
                val s = sonic ?: return false
                var any = false
                while (true) {
                    val out = s.getOutput()
                    val bytes = out.remaining()
                    if (bytes == 0) break
                    any = true
                    val floats = bytes / 4
                    if (outFloat.size < floats) outFloat = FloatArray(floats)
                    out.order(ByteOrder.nativeOrder()).asFloatBuffer().get(outFloat, 0, floats)
                    sink(outFloat, floats / 2)
                }
                return any
            }

            // PCM16 decoded buffer -> f32 stereo -> Sonic (or bypass at 44100) -> sink.
            fun process(pcm: ByteBuffer) {
                ensureResolved()
                val shorts = pcm.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = shorts.remaining() / pcmChannels
                if (frames == 0) return
                val n2 = frames * 2
                if (stereo.size < n2) stereo = FloatArray(n2)
                when (pcmChannels) {
                    1 -> for (f in 0 until frames) {
                        val v = shorts.get(f) / 32768f
                        stereo[2 * f] = v
                        stereo[2 * f + 1] = v
                    }
                    2 -> for (i in 0 until n2) stereo[i] = shorts.get(i) / 32768f
                    // >2ch: ITU-R BS.775 downmix. Taking ch0/ch1 verbatim would drop the CENTER
                    // channel, and a 5.1 film mixes nearly all of its dialogue discretely into
                    // center — the vocals stem would come back empty on exactly the content this
                    // feature exists for. Decoder PCM is WAV order (L R C LFE Ls Rs); LFE is the
                    // one channel dropped on purpose. Level is irrelevant downstream: the separator
                    // normalizes by the whole-track std and denormalizes by the same scalar.
                    else -> {
                        val c = if (pcmChannels > 2) 2 else -1
                        val ls = if (pcmChannels > 4) 4 else -1
                        val rs = if (pcmChannels > 5) 5 else -1
                        for (f in 0 until frames) {
                            val b = f * pcmChannels
                            val ctr = if (c >= 0) HALF_POWER * shorts.get(b + c) else 0f
                            val sl = if (ls >= 0) HALF_POWER * shorts.get(b + ls) else 0f
                            val sr = if (rs >= 0) HALF_POWER * shorts.get(b + rs) else 0f
                            stereo[2 * f] = (shorts.get(b) + ctr + sl) / 32768f
                            stereo[2 * f + 1] = (shorts.get(b + 1) + ctr + sr) / 32768f
                        }
                    }
                }
                if (!useSonic) { sink(stereo, frames); return } // already 44100 stereo
                val need = n2 * 4
                var idb = inputDirect
                if (idb == null || idb.capacity() < need) {
                    idb = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
                    inputDirect = idb
                }
                idb.clear()
                idb.asFloatBuffer().put(stereo, 0, n2)
                idb.position(0).limit(need)
                val s = sonic!!
                while (idb.hasRemaining()) { // queueInput may consume partially — interleave drains
                    s.queueInput(idb)
                    drainSonic()
                }
            }

            /**
             * Decode input up to [endUs] and drain what it produces. `Long.MAX_VALUE` is the whole track:
             * input is closed with an EOS flag and the loop runs until the codec echoes it back, so the
             * decoder's own tail is recovered. A bounded window instead stops feeding at [endUs] and ends
             * on the first TRY_AGAIN_LATER — the handful of frames still inside the codec are left there
             * rather than EOS'd out, because an EOS would have to be undone with a flush before the next
             * seek, and a few ms of a 2 s window cannot move a mean or a std.
             */
            fun pump(endUs: Long) {
                val full = endUs == Long.MAX_VALUE
                var inputDone = false
                var done = false
                while (!done) {
                    if (isCancelled()) throw CancellationException()

                    if (!inputDone) {
                        val pts = extractor.sampleTime
                        // Past the window. NOT an end-of-track test: `sampleTime` is -1 at the end of a
                        // track, but it is also legitimately NEGATIVE on the first sample of any source
                        // carrying an AAC encoder-priming edit — measured -21333 us on
                        // qa-assets/test-video.mp4, i.e. most AAC-in-MP4. Testing `pts < 0` here read that
                        // priming sample as EOS and skipped the ENTIRE track, so every such source failed
                        // with "Could not decode any audio from this video". `readSampleData` returning -1
                        // is the only unambiguous exhaustion signal, and it is what decides EOS below.
                        // (AudioPipeline.firstAudioPtsUs documents the same negative-PTS case.)
                        if (pts >= endUs) {
                            inputDone = true
                        } else {
                            val inIx = codecInst.dequeueInputBuffer(0) // non-blocking; drain output when full
                            if (inIx >= 0) {
                                val size = extractor.readSampleData(codecInst.getInputBuffer(inIx)!!, 0)
                                if (size < 0) {
                                    codecInst.queueInputBuffer(inIx, 0, 0, 0, if (full) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                    inputDone = true
                                } else {
                                    if (!haveFirst) { firstPtsUs = pts; haveFirst = true } // anchor for stage (d) PTS
                                    codecInst.queueInputBuffer(inIx, 0, size, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIx = codecInst.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codecInst.outputFormat
                            pcmRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) // AUTHORITATIVE (SBR/PS/Opus)
                            pcmChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outIx < 0 -> if (inputDone && !full) done = true // window drained
                        else -> {
                            if (info.size > 0) {
                                val pcm = codecInst.getOutputBuffer(outIx)!!
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                process(pcm) // final buffer may carry size>0 AND the EOS flag — consume then stop
                            }
                            codecInst.releaseOutputBuffer(outIx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        }
                    }
                }
            }

            if (windows == null) {
                pump(Long.MAX_VALUE)
                sonic?.let { s ->
                    s.queueEndOfStream()
                    while (!s.isEnded()) if (!drainSonic()) break // flush the resampler tail
                }
            } else {
                for (w in windows) {
                    if (isCancelled()) throw CancellationException()
                    extractor.seekTo(w, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    // ONE codec across every seek, per `plan-v2` §5.7 — configuring a new decoder 20 times
                    // costs more than the windows themselves. flush() is what makes reuse legal; in
                    // SYNCHRONOUS mode it needs no start() to resume, the next dequeue does that.
                    codecInst.flush()
                    // Measured from where the seek LANDED, not from where it was aimed: on a track whose
                    // sync samples are sparse the extractor can come to rest past the target, and a fixed
                    // `w + WINDOW_US` bound would then decode nothing at all from that window.
                    pump(maxOf(w, extractor.sampleTime) + WINDOW_US)
                }
            }
            return firstPtsUs
        } finally {
            runCatching { sonic?.reset() }
            codec?.let {
                runCatching { it.stop() } // stop() throws if the codec already errored; release regardless
                it.release()
            }
            extractor.release()
        }
    }

    /**
     * Seek targets for the A3 stats pass, or **null meaning "decode the whole track"** — which is the
     * answer whenever sampling would not actually save anything: a container that will not state a
     * duration (seeking blind through it is guesswork), and a track short enough that the windows would
     * cover more than half of it anyway. The threshold is that ratio, not a tuned number.
     *
     * Evenly spaced, first at 0 and last flush against the end, so the sample brackets the whole track
     * rather than its middle — a film's quietest and loudest minutes are usually its first and last.
     */
    private fun statsWindows(trackFormat: MediaFormat): LongArray? {
        if (STATS_WINDOWS <= 1) return null
        if (!trackFormat.containsKey(MediaFormat.KEY_DURATION)) return null
        val durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION)
        if (durationUs <= 2 * STATS_WINDOWS * WINDOW_US) return null
        val span = durationUs - WINDOW_US
        return LongArray(STATS_WINDOWS) { k -> k * span / (STATS_WINDOWS - 1) }
    }
}
