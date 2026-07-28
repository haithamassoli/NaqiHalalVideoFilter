package com.haithamassoli.naqi.spike

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.audio.ConcatPart
import com.haithamassoli.naqi.audio.Remux
import com.haithamassoli.naqi.audio.TrackSource
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.media.firstTrackIndex
import com.haithamassoli.naqi.media.requireTrackIndex
import com.haithamassoli.naqi.render.RenderPipeline
import com.haithamassoli.naqi.render.RenderSegment
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase-2 blocking spike (`long-film-plan.md`): **can two separately-exported segments be concatenated
 * into one decodable file?** Everything else in Phase 2 is wasted work if the answer is no.
 *
 * Three things are being asked at once, because they are all answered by the same two exports and it is
 * cheaper to run one probe than three:
 *
 * 1. **CSD equality.** The concat assumes two `Transformer` exports with identical settings emit
 *    identical SPS/PPS. `MediaMuxer` accepts one format per track, so a mismatch means the tail of the
 *    file cannot be decoded. Usually true, not guaranteed.
 * 2. **Are clipped exports rebased to 0, and frame-accurate?** The media3 1.10.1 sources say yes on both
 *    counts — `ExoAssetLoaderVideoRenderer.java:185` subtracts `streamStartPositionUs` from every frame,
 *    and :186 drops frames whose rebased timestamp went negative. That is the reading the whole concat
 *    offset scheme rests on, so it gets measured rather than trusted: this probe prints each segment's
 *    first and last PTS.
 * 3. **Does the EDL offset work?** The same rebasing means [com.haithamassoli.naqi.render.CensorGlEffect]
 *    sees clip-relative time, so a whole-timeline EDL has to be shifted per segment. The probe censors
 *    [CENSOR_FROM_MS]..[CENSOR_TO_MS], a window that falls ONLY inside the second segment — if the offset
 *    were wrong the blur would land in the first segment instead, which is visible in the output file.
 *
 * The verdict is a real decode of the joined file, not a format comparison: the failure mode being hunted
 * is a file that muxes fine and decodes wrong.
 *
 * Lessons carried from [MuxerLimitSpike], which produced two false results before it produced a real one:
 * the autorun hook re-fires on activity recreation (hence [running]), and sample PTS are NOT monotonic on
 * any stream with B-frames, so nothing here asserts that they are.
 *
 * ```
 * adb shell am start -n com.haithamassoli.naqi/.MainActivity --activity-single-top \
 *   --ez segment_concat_probe true -e probe_source /data/data/com.haithamassoli.naqi/files/test-video.mp4
 * adb logcat -s SegmentConcatSpike
 * ```
 * The joined file is left at `filesDir/segment-concat-probe.mp4` on purpose — the censor placement is
 * checked by pulling it, which no on-device assertion can do as well as looking at the pixels.
 */
object SegmentConcatSpike {

    const val TAG = "SegmentConcatSpike"

    private const val SEG_A_MS = 5_000L
    private const val SEG_B_MS = 10_000L

    /** A full-frame censor window wholly inside the SECOND segment — the EDL-offset test. */
    private const val CENSOR_FROM_MS = 6_000L
    private const val CENSOR_TO_MS = 8_000L

    private val running = AtomicBoolean(false)

    /** Blocking; call it off the main thread. Never throws — a failure IS the finding. */
    fun run(context: Context, sourcePath: String) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "probe already running — ignoring duplicate start")
            return
        }
        try {
            probe(context, sourcePath)
        } catch (t: Throwable) {
            Log.e(TAG, "VERDICT=THREW", t)
        } finally {
            running.set(false)
        }
    }

    private fun probe(context: Context, sourcePath: String) {
        val source = File(sourcePath)
        if (!source.exists()) {
            Log.e(TAG, "VERDICT=SKIPPED no such source: $sourcePath")
            return
        }
        val uri = Uri.fromFile(source)
        val dir = File(context.filesDir, "segment-concat-probe").apply { mkdirs() }
        val segA = File(dir, "seg-0.mp4")
        val segB = File(dir, "seg-1.mp4")
        val joined = File(context.filesDir, "segment-concat-probe.mp4")
        listOf(segA, segB, joined).forEach { it.delete() }

        val meta = FrameSampler.probe(context, uri)
        Log.i(TAG, "start source=$sourcePath ${meta.width}x${meta.height} rot=${meta.rotationDegrees} " +
            "durationMs=${meta.durationMs} fps=${meta.fps}")
        if (meta.durationMs < SEG_B_MS) {
            Log.e(TAG, "VERDICT=SKIPPED source is ${meta.durationMs}ms, need >= ${SEG_B_MS}ms")
            return
        }

        // ONE whole-timeline EDL, exactly as the real segmented job will have. Each segment export shifts
        // it by its own start; nothing here rewrites the EDL per segment.
        val edl = Edl(listOf(CENSOR_FROM_MS..CENSOR_TO_MS), emptyList())

        runBlocking {
            for ((seg, out) in listOf(
                RenderSegment(0, 0L, SEG_A_MS) to segA,
                RenderSegment(1, SEG_A_MS, SEG_B_MS) to segB,
            )) {
                val startedAt = System.currentTimeMillis()
                RenderPipeline.renderCensor(
                    context, uri, out, edl, blurAmount = 60, grayscale = false, meta = meta, segment = seg,
                ) { }
                Log.i(TAG, "exported seg-${seg.index} [${seg.startMs}..${seg.endMs}) " +
                    "bytes=${out.length()} elapsedMs=${System.currentTimeMillis() - startedAt}")
            }
        }

        val a = summarize(segA) ?: run { Log.e(TAG, "VERDICT=BROKEN seg-0 has no readable video track"); return }
        val b = summarize(segB) ?: run { Log.e(TAG, "VERDICT=BROKEN seg-1 has no readable video track"); return }
        Log.i(TAG, "seg-0 $a")
        Log.i(TAG, "seg-1 $b")

        // (2) Rebasing + frame accuracy. Both segments should start at ~0 and span ~5 s.
        val rebased = b.firstPtsUs < 500_000L
        Log.i(TAG, "REBASED=$rebased (seg-1 firstPts=${b.firstPtsUs}us — clip-relative if ~0, " +
            "source-absolute if ~${SEG_A_MS * 1000})")

        // (1) CSD equality — the question the concat's single track format depends on.
        val csdEqual = a.csd0 == b.csd0 && a.csd1 == b.csd1
        val geometryEqual = a.width == b.width && a.height == b.height && a.mime == b.mime
        Log.i(TAG, "CSD_EQUAL=$csdEqual GEOMETRY_EQUAL=$geometryEqual")
        if (!csdEqual) Log.w(TAG, "csd0 a=${a.csd0} b=${b.csd0}\ncsd1 a=${a.csd1} b=${b.csd1}")

        // (3) The joined file, through the production concat — audio copied from the source, which is what
        // the segmented censor-only shape does (per-segment AAC cannot be concatenated).
        val hasAudio = hasAudio(context, uri)
        try {
            runBlocking {
                Remux.concat(
                    context,
                    parts = listOf(ConcatPart(segA, 0L), ConcatPart(segB, SEG_A_MS)),
                    audio = if (hasAudio) TrackSource.FromUri(uri) else null,
                    outFile = joined,
                ) { }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VERDICT=BROKEN concat itself failed", t)
            return
        }
        Log.i(TAG, "joined bytes=${joined.length()} audioTrack=$hasAudio")

        // The verdict: decode every frame of the joined file. A CSD mismatch that the format comparison
        // above somehow missed shows up here as a decoder error or as missing frames past the seam.
        val decoded = decodeAll(joined)
        val expected = a.samples + b.samples
        val ok = csdEqual && geometryEqual && rebased && decoded.frames >= expected - 2 && decoded.error == null
        if (ok) {
            Log.i(TAG, "VERDICT=OK joined ${a.samples}+${b.samples} samples into ${joined.length()} bytes; " +
                "decoded ${decoded.frames} frames, maxPts=${decoded.maxPtsUs}us. " +
                "Pull segment-concat-probe.mp4 and confirm the blur sits at ${CENSOR_FROM_MS}..${CENSOR_TO_MS}ms.")
        } else {
            Log.e(TAG, "VERDICT=BROKEN csdEqual=$csdEqual geometryEqual=$geometryEqual rebased=$rebased " +
                "decodedFrames=${decoded.frames} expected≈$expected decodeError=${decoded.error}")
        }
        // Segments are disposable; the joined file is the artifact to inspect.
        runCatching { dir.deleteRecursively() }
    }

    private class Summary(
        val mime: String, val width: Int, val height: Int,
        val csd0: String, val csd1: String,
        val samples: Long, val firstPtsUs: Long, val maxPtsUs: Long,
    ) {
        override fun toString() = "$mime ${width}x$height samples=$samples firstPts=${firstPtsUs}us " +
            "maxPts=${maxPtsUs}us csd0=${csd0.take(48)} csd1=${csd1.take(24)}"
    }

    private fun hasAudio(context: Context, uri: Uri): Boolean {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(context, uri, null)
            ext.firstTrackIndex("audio/") != null
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { ext.release() }
        }
    }

    /** Track format + a full sample walk. PTS order is not assumed anywhere (B-frames). */
    private fun summarize(file: File): Summary? {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(file.absolutePath)
            val ix = ext.firstTrackIndex("video/") ?: return null
            ext.selectTrack(ix)
            val f = ext.getTrackFormat(ix)
            var buf = ByteBuffer.allocate(1 shl 21)
            var samples = 0L
            var firstPtsUs = -1L
            var maxPtsUs = -1L
            while (true) {
                var size: Int
                while (true) {
                    try {
                        size = ext.readSampleData(buf, 0); break
                    } catch (_: IllegalArgumentException) {
                        buf = ByteBuffer.allocate(buf.capacity() * 2)
                    }
                }
                if (size < 0) break
                val pts = ext.sampleTime
                if (firstPtsUs < 0) firstPtsUs = pts
                if (pts > maxPtsUs) maxPtsUs = pts
                samples++
                ext.advance()
            }
            return Summary(
                mime = f.getString(MediaFormat.KEY_MIME).orEmpty(),
                width = f.getInteger(MediaFormat.KEY_WIDTH),
                height = f.getInteger(MediaFormat.KEY_HEIGHT),
                csd0 = hex(f, "csd-0"), csd1 = hex(f, "csd-1"),
                samples = samples, firstPtsUs = firstPtsUs, maxPtsUs = maxPtsUs,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "could not summarize ${file.name}", t)
            return null
        } finally {
            runCatching { ext.release() }
        }
    }

    private fun hex(f: MediaFormat, key: String): String {
        val b = runCatching { f.getByteBuffer(key) }.getOrNull() ?: return "absent"
        val d = b.duplicate()
        val a = ByteArray(d.remaining())
        d.get(a)
        return a.joinToString("") { "%02x".format(it) }
    }

    private class Decoded(val frames: Long, val maxPtsUs: Long, val error: String?)

    /**
     * Decode the whole video track. This is the verdict: a decoder configured from segment 0's CSD has to
     * cope with segment 1's samples, which is exactly what a real player will do.
     */
    private fun decodeAll(file: File): Decoded {
        val ext = MediaExtractor()
        var codec: MediaCodec? = null
        var frames = 0L
        var maxPtsUs = -1L
        try {
            ext.setDataSource(file.absolutePath)
            val ix = ext.requireTrackIndex("video/")
            ext.selectTrack(ix)
            val format = ext.getTrackFormat(ix)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(0)
                    if (inIx >= 0) {
                        val size = ext.readSampleData(codec.getInputBuffer(inIx)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIx >= 0) {
                    if (info.size > 0) {
                        frames++
                        if (info.presentationTimeUs > maxPtsUs) maxPtsUs = info.presentationTimeUs
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return Decoded(frames, maxPtsUs, null)
        } catch (t: Throwable) {
            return Decoded(frames, maxPtsUs, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            runCatching { ext.release() }
        }
    }
}
