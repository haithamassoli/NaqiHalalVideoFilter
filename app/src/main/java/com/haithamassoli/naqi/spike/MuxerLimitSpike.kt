package com.haithamassoli.naqi.spike

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase-0 spike (`long-film-plan.md`): **does framework `MediaMuxer` still produce a readable MP4 past
 * 4 GiB?**
 *
 * Why it is worth a probe rather than a guess: a 2 h combined output lands around 4–5 GB, and Phase 2's
 * segment concatenation is *also* one big `MediaMuxer` write — so if 4 GiB is a wall, segmentation does
 * not clear it and the answer becomes a bitrate cap or a split output, which is a product decision.
 * Free to answer now, expensive to discover after Phase 2 is built on top of it.
 *
 * **Hypothesis this exists to confirm or refute** (AOSP `MPEG4Writer`, not verified here): the writer
 * emits 32-bit chunk offsets (`stco`) by default and only switches to 64-bit (`co64`) when it has been
 * told the file will be large — and `MediaMuxer` exposes no way to tell it. If that is right, samples
 * written after the file passes 4 GiB get truncated offsets and the tail of the file is undecodable
 * even though muxing "succeeded". Treat the above as a guess until this probe's log says otherwise.
 *
 * Method: copy one real video track (its CSD rides in the extractor's format, exactly as [com.haithamassoli.naqi.audio.Remux]
 * does) round and round from [sourcePath], advancing PTS each pass, until the output passes
 * [targetBytes]. Then prove the result readable *past the boundary* by seeking back to the sample that
 * crossed 4 GiB and walking to the end. Muxing without the readback would prove nothing: the failure
 * mode being hunted is a file that writes fine and reads back wrong.
 *
 * Debug-only, triggered from `MainActivity`'s autorun hook — the device is the only place this can be
 * answered, and a 4.5 GiB write has no business in a release build:
 * ```
 * adb shell am start -n com.haithamassoli.naqi/.MainActivity --activity-single-top --ez muxer_limit_probe true
 * adb logcat -s MuxerLimitSpike
 * ```
 */
object MuxerLimitSpike {

    const val TAG = "MuxerLimitSpike"

    /** The boundary in question: where 32-bit chunk offsets stop being able to address the file. */
    private const val FOUR_GIB = 4L * 1024 * 1024 * 1024

    /** Comfortably past the boundary — enough tail to seek into, without writing more than needed. */
    private const val DEFAULT_TARGET = FOUR_GIB + 512L * 1024 * 1024

    /**
     * Blocking; call it off the main thread. Never throws: an exception from the muxer IS the finding,
     * so it is caught, logged with the byte offset it happened at, and reported as the verdict.
     */
    fun run(context: Context, sourcePath: String, targetBytes: Long = DEFAULT_TARGET) {
        // The autorun hook fires from onCreate, and an activity recreation re-fires it with the same
        // intent. Without this, two probes write the SAME output file and the readback reports tens of
        // thousands of bogus anomalies — observed on the first run, and it invalidated that result.
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "probe already running — ignoring duplicate start")
            return
        }
        try {
            probe(context, sourcePath, targetBytes)
        } finally {
            running.set(false)
        }
    }

    private val running = AtomicBoolean(false)

    private fun probe(context: Context, sourcePath: String, targetBytes: Long) {
        val out = File(context.cacheDir, "muxer-limit-probe.mp4")
        val freeBefore = context.cacheDir.usableSpace
        Log.i(TAG, "start source=$sourcePath target=$targetBytes freeBytes=$freeBefore")
        if (freeBefore < targetBytes + 512L * 1024 * 1024) {
            Log.e(TAG, "VERDICT=SKIPPED not enough free space: need ~${targetBytes + 512L * 1024 * 1024}, have $freeBefore")
            return
        }
        try {
            val written = write(sourcePath, out, targetBytes) ?: return
            verify(out, written)
        } catch (t: Throwable) {
            Log.e(TAG, "VERDICT=THREW unexpected failure outside the muxer", t)
        } finally {
            // 4.5 GiB of probe data is not something to leave on someone's phone.
            runCatching { if (out.exists()) out.delete() }
            Log.i(TAG, "cleaned up, freeBytes=${context.cacheDir.usableSpace}")
        }
    }

    /** What the write pass observed, for the readback to check against. */
    private class Written(
        val bytes: Long,
        val samples: Long,
        /** PTS of the first sample whose data landed beyond 4 GiB — the one 32-bit offsets can't address. */
        val crossPtsUs: Long,
        val samplesAfterCross: Long,
        val lastPtsUs: Long,
    )

    private fun write(sourcePath: String, out: File, targetBytes: Long): Written? {
        val ext = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ext.setDataSource(sourcePath)
            val trackIx = (0 until ext.trackCount).firstOrNull {
                ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run { Log.e(TAG, "VERDICT=SKIPPED no video track in $sourcePath"); return null }
            ext.selectTrack(trackIx)
            val format = ext.getTrackFormat(trackIx)
            // One pass of the source; +1 frame so PTS strictly increases across the seam between passes.
            val passUs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L) + 33_333L

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format) // CSD (avcC) rides in the format, same as Remux
            muxer.start()

            var buf = ByteBuffer.allocate(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1 shl 16)
                else 1 shl 20,
            )
            val info = MediaCodec.BufferInfo()
            var bytes = 0L
            var samples = 0L
            var baseUs = 0L
            var crossPtsUs = -1L
            var samplesAfterCross = 0L
            var lastPtsUs = 0L
            val startedAt = System.currentTimeMillis()

            while (bytes < targetBytes) {
                var size = -1
                while (true) {
                    try {
                        size = ext.readSampleData(buf, 0); break
                    } catch (_: IllegalArgumentException) {
                        buf = ByteBuffer.allocate(buf.capacity() * 2) // failed read doesn't advance — safe to retry
                    }
                }
                if (size < 0) { // source exhausted — loop it, carrying PTS forward
                    baseUs += passUs
                    ext.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    continue
                }
                val ptsUs = baseUs + ext.sampleTime
                info.offset = 0
                info.size = size
                info.presentationTimeUs = ptsUs
                info.flags = if (ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                try {
                    muxer.writeSampleData(outTrack, buf, info)
                } catch (t: Throwable) {
                    Log.e(TAG, "VERDICT=WRITE_FAILED at bytes=$bytes samples=$samples pts=${ptsUs}us — " +
                        "MediaMuxer refused to write past this point", t)
                    return null
                }
                bytes += size
                samples++
                lastPtsUs = ptsUs
                if (crossPtsUs < 0 && bytes > FOUR_GIB) {
                    crossPtsUs = ptsUs
                    Log.i(TAG, "crossed 4 GiB at sample=$samples pts=${ptsUs}us")
                }
                if (crossPtsUs >= 0) samplesAfterCross++
                ext.advance()
                if (samples % 20_000L == 0L) Log.i(TAG, "…$bytes bytes, $samples samples")
            }

            try {
                muxer.stop() // writes the moov, incl. the chunk-offset table this probe is about
            } catch (t: Throwable) {
                Log.e(TAG, "VERDICT=STOP_FAILED muxed $bytes bytes but could not finalize the file", t)
                return null
            }
            val elapsed = System.currentTimeMillis() - startedAt
            Log.i(TAG, "wrote bytes=$bytes samples=$samples onDisk=${out.length()} elapsedMs=$elapsed")
            return Written(bytes, samples, crossPtsUs, samplesAfterCross, lastPtsUs)
        } finally {
            runCatching { muxer?.release() }
            runCatching { ext.release() }
        }
    }

    /**
     * The actual test: can the tail still be decoded? Seeks back to the sample that crossed 4 GiB and
     * walks to the end, then reports whether the moov used 64-bit (`co64`) or 32-bit (`stco`) chunk
     * offsets — the direct explanation for whichever result the walk produced.
     */
    private fun verify(out: File, w: Written) {
        Log.i(TAG, "offsetTable=${chunkOffsetBox(out)}")
        if (w.crossPtsUs < 0) { Log.e(TAG, "VERDICT=INCONCLUSIVE never crossed 4 GiB"); return }
        val ext = MediaExtractor()
        try {
            ext.setDataSource(out.absolutePath)
            ext.selectTrack(0)
            ext.seekTo(w.crossPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (ext.sampleTime < 0) {
                Log.e(TAG, "VERDICT=BROKEN seek to the post-4GiB region (${w.crossPtsUs}us) landed at EOS")
                return
            }
            var buf = ByteBuffer.allocate(1 shl 21)
            var read = 0L
            var maxPtsUs = -1L
            var zeroSized = 0L
            while (true) {
                var size = -1
                while (true) {
                    try {
                        size = ext.readSampleData(buf, 0); break
                    } catch (_: IllegalArgumentException) {
                        buf = ByteBuffer.allocate(buf.capacity() * 2)
                    }
                }
                if (size < 0) break
                if (size == 0) zeroSized++ // a garbage chunk offset would surface as an empty sample
                // NOT checked: monotonically increasing PTS. Samples come back in DECODE order, and any
                // H.264 stream with B-frames legitimately walks its PTS backwards — an earlier version
                // of this probe called that corruption and reported a false BROKEN on a healthy file.
                if (ext.sampleTime > maxPtsUs) maxPtsUs = ext.sampleTime
                read++
                ext.advance()
            }
            // Allow a little slack: the seek lands on the sync sample at or before the crossing, so the
            // walk legitimately covers a few more samples than were written after it.
            val reachedEnd = maxPtsUs >= w.lastPtsUs - 1_000_000L
            val countSane = read >= w.samplesAfterCross
            Log.i(TAG, "readback samplesRead=$read expected≈${w.samplesAfterCross} maxPts=${maxPtsUs}us " +
                "expectedLastPts=${w.lastPtsUs}us zeroSized=$zeroSized")
            if (reachedEnd && countSane && zeroSized == 0L && read > 0) {
                Log.i(TAG, "VERDICT=OK MediaMuxer produced a ${w.bytes}-byte MP4 that reads correctly past 4 GiB")
            } else {
                Log.e(TAG, "VERDICT=BROKEN the tail past 4 GiB did not read back intact " +
                    "(reachedEnd=$reachedEnd countSane=$countSane zeroSized=$zeroSized samplesRead=$read)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VERDICT=BROKEN could not even open/parse the >4 GiB output", t)
        } finally {
            runCatching { ext.release() }
        }
    }

    /**
     * Which chunk-offset box the writer emitted, by scanning the tail for the 4CC. `moov` trails `mdat`
     * in a MediaMuxer file, so the last 64 MiB is plenty; "unknown" if it isn't found there, which
     * costs nothing — the readback above is the verdict, this is the explanation.
     */
    private fun chunkOffsetBox(out: File): String = runCatching {
        val span = minOf(out.length(), 64L * 1024 * 1024).toInt()
        val tail = ByteArray(span)
        RandomAccessFile(out, "r").use { it.seek(out.length() - span); it.readFully(tail) }
        val text = String(tail, Charsets.ISO_8859_1)
        when {
            text.contains("co64") -> "co64 (64-bit offsets)"
            text.contains("stco") -> "stco (32-bit offsets)"
            else -> "unknown (no stco/co64 in the last ${span}B)"
        }
    }.getOrDefault("unknown (tail scan failed)")
}
