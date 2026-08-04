package com.haithamassoli.naqi.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.roundToInt

/**
 * M2 audio egress (android-media-spec stage d). Quantizes the DSP's interleaved f32 stereo 44.1 kHz to
 * int16 LE and AAC-LC encodes it at 192 kbps into a temp .m4a via framework [MediaCodec] + [MediaMuxer].
 *
 * **In 44.1, out 44.1 — no resampling anywhere in this class.** There used to be a 44 100 -> 48 000
 * `SonicAudioProcessor` session here. AAC-LC encodes 44.1 kHz natively, so it bought nothing and cost the
 * ~−27 dB in-band distortion `tasks.md:58` records: Sonic is 2-tap linear interpolation with no
 * anti-imaging filter, a playback-speed tool rather than an SRC. Deleting it is primarily a QUALITY fix
 * and `plan-v2` §5.9 (A6) ranks it as one; the encoder feed getting ~8 % shorter is a side effect.
 *
 * The muxer audio track is added ONLY from the encoder's INFO_OUTPUT_FORMAT_CHANGED output format (it
 * carries the AudioSpecificConfig); the standalone CODEC_CONFIG buffer is dropped. Encoder-input PTS is a
 * monotonic [RATE] sample counter anchored at [firstPtsUs] (the source's first audio PTS) so the track
 * shares the video epoch in the final mux; the ~2048-sample encoder priming is left uncompensated by
 * convention. Feed with [write], end with [finish], always [close]. Thread-confined.
 */
class AacWriter(tempM4a: File, private val firstPtsUs: Long) : AutoCloseable {

    private companion object {
        const val TIMEOUT_US = 10_000L

        /**
         * Separator rate in, encoder rate out, PTS clock — one number, which is the whole of A6. Also
         * [AudioDecoder]'s output rate and htdemucs's training rate, so nothing upstream converts either.
         */
        const val RATE = 44100
    }

    // Acquired together so a partial failure (encoder configure/start throwing, or the MediaMuxer
    // ctor throwing AFTER the encoder started) releases whatever was created — the caller never sees
    // a finished object, so close() would otherwise never run and the native codec would leak.
    private val encoder: MediaCodec
    private val muxer: MediaMuxer

    init {
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            mux = MediaMuxer(tempM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder = enc
            muxer = mux
        } catch (t: Throwable) {
            runCatching { enc?.stop() }
            runCatching { enc?.release() }
            runCatching { mux?.release() }
            throw t
        }
    }

    private val info = MediaCodec.BufferInfo()

    private var audioTrackIx = -1
    private var muxerStarted = false
    private var muxerStopped = false
    private var samplesOut = 0L // stereo frames handed to the encoder so far

    // Hot buffer, grown on demand and reused across every write.
    private var pcm16 = ByteArray(0)

    /** Feed one interleaved f32 stereo 44.1 kHz batch (already soft-clipped upstream). */
    fun write(interleaved: FloatArray, frames: Int) {
        if (frames == 0) return
        val n2 = frames * 2
        if (pcm16.size < n2 * 2) pcm16 = ByteArray(n2 * 2)
        var bi = 0
        for (i in 0 until n2) {
            // NaN has no int16 image and `roundToInt` throws on it rather than saturating, which
            // turned one corrupt sample out of the separator into a lost multi-minute job. The
            // separator now sanitizes its own output ([DemucsSeparator.nonFinite]); this stays as the
            // boundary guard, because this is where float stops being representable and every other
            // producer of this buffer would hit the same edge.
            val v = interleaved[i]
            val s = if (v.isFinite()) (v * 32767f).roundToInt().coerceIn(-32768, 32767) else 0
            pcm16[bi++] = (s and 0xFF).toByte()
            pcm16[bi++] = ((s shr 8) and 0xFF).toByte()
        }
        feedEncoder(pcm16, bi)
    }

    /** Signal encoder EOS, drain the tail to the muxer, and finalize the .m4a. */
    fun finish() {
        var inIx = encoder.dequeueInputBuffer(TIMEOUT_US)
        while (inIx < 0) { drainEncoder(false); inIx = encoder.dequeueInputBuffer(TIMEOUT_US) }
        encoder.queueInputBuffer(inIx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainEncoder(true)

        if (muxerStarted && !muxerStopped) { muxer.stop(); muxerStopped = true }
    }

    /** Release codec and muxer. Idempotent; safe after [finish] or on an error path. */
    override fun close() {
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { muxer.release() } // never stop() a muxer that didn't reach finish() — moov would be incomplete
    }

    private fun ptsUs(): Long = firstPtsUs + samplesOut * 1_000_000L / RATE

    private fun feedEncoder(pcm: ByteArray, size: Int) {
        var off = 0
        while (off < size) {
            // C4 (`perf-plan-v4` §5): the sibling below was fixed; this one still paid a blocking 10 ms
            // wait ~26 times per htdemucs chunk × 276 chunks ≈ 7 000 times per 643 s job, on the
            // separator's own thread, inside the unsplit residual. An input buffer is almost always
            // available — so ask for free first, and when it is not, drain (which is what releases one)
            // before waiting. **The blocking wait has to stay**: nothing else applies backpressure on
            // this side, unlike [drainEncoder] whose caller supplies it, so dropping it turns the loop
            // into a spin. Output is unchanged — same buffers, same sizes, same PTS.
            var inIx = encoder.dequeueInputBuffer(0)
            if (inIx < 0) {
                drainEncoder(false)
                inIx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIx < 0) continue
            }
            val ib = encoder.getInputBuffer(inIx)!!
            ib.clear()
            val n = minOf(size - off, ib.remaining()).let { it - it % 4 } // keep whole stereo int16 frames
            ib.put(pcm, off, n)
            val frames = n / 4 // stereo int16 = 4 bytes/frame
            encoder.queueInputBuffer(inIx, 0, n, ptsUs(), 0)
            samplesOut += frames
            off += n
            drainEncoder(false)
        }
    }

    /**
     * [endOfStream] also picks the dequeue timeout, and that is not a detail: mid-stream this is called
     * after EVERY input buffer and returns on the first TRY_AGAIN_LATER, so a blocking timeout was paid
     * ~10 000 times per pass — measured at **135 s to transcode a 193 s track on an S23 (1.43x realtime)**,
     * of which ~129 s was this sleep. Non-blocking there costs nothing: the caller's own
     * `dequeueInputBuffer(TIMEOUT_US)` is what applies backpressure, so this cannot spin. Draining EOS
     * still blocks, because there the encoder tail is exactly what we are waiting for.
     */
    private fun drainEncoder(endOfStream: Boolean) {
        while (true) {
            val outIx = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The CSD (AudioSpecificConfig) lives in THIS format — addTrack MUST use it, never encFmt.
                    audioTrackIx = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIx < 0 -> if (!endOfStream) return // TRY_AGAIN_LATER: done for now (keep polling on EOS)
                else -> {
                    val outBuf = encoder.getOutputBuffer(outIx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0 // CSD already delivered via addTrack — writing it would corrupt the .m4a
                    }
                    if (info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(audioTrackIx, outBuf, info)
                    }
                    encoder.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
