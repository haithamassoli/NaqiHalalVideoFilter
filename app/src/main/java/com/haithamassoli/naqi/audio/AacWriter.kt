package com.haithamassoli.naqi.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * M2 audio egress (android-media-spec stage d). Streams the DSP's interleaved f32 stereo 44.1k through
 * a single [SonicAudioProcessor] float session (44100 -> 48000), quantizes to int16 LE, and AAC-LC
 * encodes at 192 kbps into a temp .m4a via framework [MediaCodec] + [MediaMuxer].
 *
 * The muxer audio track is added ONLY from the encoder's INFO_OUTPUT_FORMAT_CHANGED output format (it
 * carries the AudioSpecificConfig); the standalone CODEC_CONFIG buffer is dropped. Encoder-input PTS is
 * a monotonic 48 kHz sample counter anchored at [firstPtsUs] (the source's first audio PTS) so the track
 * shares the video epoch in the final mux; the ~2048-sample encoder priming is left uncompensated by
 * convention. Feed with [write], end with [finish], always [close]. Thread-confined.
 */
class AacWriter(tempM4a: File, private val firstPtsUs: Long) : AutoCloseable {

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val IN_RATE = 44100
        const val OUT_RATE = 48000
    }

    private val sonic = SonicAudioProcessor().apply {
        setOutputSampleRateHz(OUT_RATE) // OUTPUT rate — set BEFORE configure
        configure(AudioProcessor.AudioFormat(IN_RATE, 2, C.ENCODING_PCM_FLOAT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
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
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_RATE, 2).apply {
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
            runCatching { sonic.reset() }
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
    private var samples48k = 0L // stereo frames handed to the encoder so far

    // Hot buffers, grown on demand and reused across every write.
    private var inputDirect: ByteBuffer? = null
    private var pcm16 = ByteArray(0)

    /** Feed one interleaved f32 stereo 44.1k batch (already soft-clipped upstream). */
    fun write(interleaved: FloatArray, frames: Int) {
        if (frames == 0) return
        val n2 = frames * 2
        val need = n2 * 4
        var idb = inputDirect
        if (idb == null || idb.capacity() < need) {
            idb = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            inputDirect = idb
        }
        idb.clear()
        idb.asFloatBuffer().put(interleaved, 0, n2)
        idb.position(0).limit(need)
        while (idb.hasRemaining()) { // queueInput may consume partially — interleave drains
            sonic.queueInput(idb)
            drainSonicToEncoder()
        }
    }

    /** Flush the resampler tail, signal encoder EOS, drain to the muxer, and finalize the .m4a. */
    fun finish() {
        sonic.queueEndOfStream()
        while (!sonic.isEnded()) if (!drainSonicToEncoder()) break

        var inIx = encoder.dequeueInputBuffer(TIMEOUT_US)
        while (inIx < 0) { drainEncoder(false); inIx = encoder.dequeueInputBuffer(TIMEOUT_US) }
        encoder.queueInputBuffer(inIx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainEncoder(true)

        if (muxerStarted && !muxerStopped) { muxer.stop(); muxerStopped = true }
    }

    /** Release codec/muxer/resampler. Idempotent; safe after [finish] or on an error path. */
    override fun close() {
        runCatching { sonic.reset() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { muxer.release() } // never stop() a muxer that didn't reach finish() — moov would be incomplete
    }

    private fun ptsUs(): Long = firstPtsUs + samples48k * 1_000_000L / OUT_RATE

    // Drain available Sonic output, quantize to int16 LE, and push into the encoder. Returns whether
    // anything was consumed (bounds the finish() tail loop).
    private fun drainSonicToEncoder(): Boolean {
        var any = false
        while (true) {
            val out = sonic.getOutput()
            val bytes = out.remaining()
            if (bytes == 0) break
            any = true
            val floats = bytes / 4
            val fb = out.order(ByteOrder.nativeOrder()).asFloatBuffer()
            if (pcm16.size < floats * 2) pcm16 = ByteArray(floats * 2)
            var bi = 0
            for (i in 0 until floats) {
                val s = (fb.get(i) * 32767f).roundToInt().coerceIn(-32768, 32767)
                pcm16[bi++] = (s and 0xFF).toByte()
                pcm16[bi++] = ((s shr 8) and 0xFF).toByte()
            }
            feedEncoder(pcm16, floats * 2)
        }
        return any
    }

    private fun feedEncoder(pcm: ByteArray, size: Int) {
        var off = 0
        while (off < size) {
            val inIx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIx < 0) { drainEncoder(false); continue }
            val ib = encoder.getInputBuffer(inIx)!!
            ib.clear()
            val n = minOf(size - off, ib.remaining()).let { it - it % 4 } // keep whole stereo int16 frames
            ib.put(pcm, off, n)
            val frames = n / 4 // stereo int16 = 4 bytes/frame
            encoder.queueInputBuffer(inIx, 0, n, ptsUs(), 0)
            samples48k += frames
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
