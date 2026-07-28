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
 * Two passes share one private [decode] routine (see android-media-spec §a/§b): [stats] runs it once
 * accumulating the whole-track mono-mix mean/std (O(1) memory) that [DemucsSeparator] needs, then
 * [stream] runs it again forwarding batches to the DSP. Both poll [isCancelled] once per decode
 * dequeue and throw [CancellationException] on cancel. Thread-confined (one worker at a time).
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L
    private const val SAMPLE_RATE = 44100

    /** -3 dB, the ITU-R BS.775 coefficient for folding center/surrounds into a stereo pair. */
    private const val HALF_POWER = 0.70710678f

    /** Whole-track summary from the stats pass. [frames] == 0 means the track decoded to nothing. */
    class Stats(val frames: Long, val mean: Float, val std: Float, val firstPtsUs: Long)

    /** Decode+resample the whole track once, accumulating mono-mix mean/std in double accumulators. */
    fun stats(context: Context, uri: Uri, isCancelled: () -> Boolean): Stats {
        var sum = 0.0
        var sumsq = 0.0
        var count = 0L
        val firstPtsUs = decode(context, uri, isCancelled) { interleaved, frames ->
            for (i in 0 until frames) {
                val m = (interleaved[2 * i] + interleaved[2 * i + 1]).toDouble() * 0.5
                sum += m
                sumsq += m * m
            }
            count += frames
        }
        if (count == 0L) return Stats(0L, 0f, 0f, 0L) // empty/corrupt audio — caller fails with a message
        val mean = sum / count
        // Whole-track std with N-1 (Bessel); matches the python normalization the model was trained on.
        val variance = if (count > 1) ((sumsq - sum * sum / count) / (count - 1)).coerceAtLeast(0.0) else 0.0
        return Stats(count, mean.toFloat(), sqrt(variance).toFloat(), firstPtsUs)
    }

    /** Decode+resample again, streaming interleaved f32 stereo 44.1k batches to [sink]. */
    fun stream(context: Context, uri: Uri, isCancelled: () -> Boolean, sink: (FloatArray, Int) -> Unit) {
        decode(context, uri, isCancelled, sink)
    }

    /**
     * The shared decode+resample loop. Mirrors [com.haithamassoli.naqi.analysis.FrameSampler.sample]'s
     * non-blocking-input / blocking-output dequeue idiom, adapted for audio. Reads the AUTHORITATIVE
     * rate/channels from INFO_OUTPUT_FORMAT_CHANGED (HE-AAC/Opus rewrite them), converts PCM16 to float
     * (/32768f), hand-fixes channels to stereo (mono duplicated, >2ch takes the first two — a 44100-only
     * Sonic bypass is independent of the channel fix), and drives ONE Sonic float session per stream.
     * Returns the source's first audio-sample PTS (0 when the track is empty).
     */
    private fun decode(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean,
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

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
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
            fun ensureResolved() {
                if (resolved) return
                resolved = true
                useSonic = pcmRate != SAMPLE_RATE
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

            while (!outputDone) {
                if (isCancelled()) throw CancellationException()

                if (!inputDone) {
                    val inIx = codecInst.dequeueInputBuffer(0) // non-blocking; drain output when full
                    if (inIx >= 0) {
                        val size = extractor.readSampleData(codecInst.getInputBuffer(inIx)!!, 0)
                        if (size < 0) {
                            codecInst.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            if (!haveFirst) { firstPtsUs = pts; haveFirst = true } // anchor for stage (d) PTS
                            codecInst.queueInputBuffer(inIx, 0, size, pts, 0)
                            extractor.advance()
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
                    outIx < 0 -> Unit // TRY_AGAIN_LATER / BUFFERS_CHANGED — ignore
                    else -> {
                        if (info.size > 0) {
                            val pcm = codecInst.getOutputBuffer(outIx)!!
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            process(pcm) // final buffer may carry size>0 AND the EOS flag — consume then stop
                        }
                        codecInst.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            sonic?.let { s ->
                s.queueEndOfStream()
                while (!s.isEnded()) if (!drainSonic()) break // flush the resampler tail
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

}
