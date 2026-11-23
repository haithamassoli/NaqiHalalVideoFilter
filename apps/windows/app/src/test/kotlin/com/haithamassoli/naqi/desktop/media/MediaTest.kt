package com.haithamassoli.naqi.desktop.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bytedeco.javacpp.Loader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Every fixture is made here with the bundled ffmpeg, so the tests need nothing but the build. */
class MediaTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ffmpeg: String = Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)
    private val ffprobe: String = Loader.load(org.bytedeco.ffmpeg.ffprobe::class.java)

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}\n$out" }
        return out
    }

    private fun ff(vararg args: String) = run(ffmpeg, "-y", "-v", "error", *args)

    /** ffprobe's frame pts of the first video stream, in ms. */
    private fun framePtsMs(f: File): List<Long> =
        run(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", f.path)
            .lines().filter { it.isNotBlank() }.map { Math.round(it.trim().trimEnd(',').toDouble() * 1000) }

    /** ffprobe's start_time of the first stream of [type] ("v"/"a"), in ms. */
    private fun startMs(f: File, type: String): Long =
        run(ffprobe, "-v", "error", "-select_streams", "$type:0", "-show_entries", "stream=start_time", "-of", "csv=p=0", f.path)
            .trim().trimEnd(',').toDouble().let { Math.round(it * 1000) }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun clip(name: String = "in.mp4", seconds: Int = 2): File = tmp.root.resolve(name).also {
        ff("-f", "lavfi", "-i", "testsrc=size=320x240:rate=30:duration=$seconds",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$seconds",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", it.path)
    }

    /** Three frames at exactly 0, 120 and 480 ms: a 1000 fps source thinned by `select`, kept VFR. */
    private fun vfrClip(): File = tmp.root.resolve("vfr.mp4").also {
        ff("-f", "lavfi", "-i", "testsrc=size=160x120:rate=1000:duration=0.6",
            "-vf", "select='eq(n\\,0)+eq(n\\,120)+eq(n\\,480)'", "-fps_mode", "vfr",
            "-video_track_timescale", "1000", "-c:v", "libx264", "-pix_fmt", "yuv420p", it.path)
    }

    private suspend fun sampled(f: File, fps: Float = 10f, maxDim: Int = 640): List<RgbFrame> {
        val out = mutableListOf<RgbFrame>()
        VideoDecoder.sample(f, fps, maxDim) { out += RgbFrame(it.width, it.height, it.rgb.copyOf(), it.ptsMs) }
        return out
    }

    @Test
    fun slotsFollowAndroidsAnchoredRule() {
        val s = Slots(10f)
        assertEquals((0L..900L step 100).toList(), (0L until 1000L step 10).filter { s.take(it * 1000) })
        // Anchored to the first frame, not to 0; and a gap resyncs to pts + interval instead of bursting.
        val g = Slots(10f)
        assertEquals(listOf(30L, 530L, 630L), listOf(30L, 80L, 530L, 560L, 630L).filter { g.take(it * 1000) })
    }

    @Test
    fun analysisSizeMatchesAndroid() {
        assertEquals(640 to 360, analysisSize(1920, 1080, 640))
        assertEquals(360 to 640, analysisSize(1080, 1920, 640))
        assertEquals(100 to 50, analysisSize(100, 51, 640)) // never up, always even
    }

    @Test
    fun probeReadsTheContainer() {
        val m = Probe.probe(clip())
        assertEquals(320, m.width)
        assertEquals(240, m.height)
        assertEquals(0, m.rotationDegrees)
        assertEquals(30f, m.fps, 0.01f)
        assertTrue(m.durationMs in 1990L..2100L)
    }

    @Test
    fun sampleCarriesTheRealPtsOfAVfrClip() = runBlocking {
        val vfr = vfrClip()
        assertEquals(listOf(0L, 120L, 480L), framePtsMs(vfr)) // the fixture is what it claims
        val frames = sampled(vfr)
        assertEquals(listOf(0L, 120L, 480L), frames.map { it.ptsMs })
        assertTrue(frames.all { it.width == 160 && it.height == 120 && it.rgb.size == 160 * 120 * 3 })
    }

    @Test
    fun sampleTakesTenSlotsASecondAtThirtyFps() = runBlocking {
        val frames = sampled(clip(), maxDim = 160)
        // 30 fps → pts 0, 33, 66, 100, 133 … : Android's rule takes 0, 100 (≥100), 200, …, 1900.
        assertEquals(20, frames.size)
        assertEquals(listOf(0L, 100L, 200L), frames.take(3).map { it.ptsMs })
        assertTrue(frames.all { it.width == 160 && it.height == 120 })
    }

    @Test
    fun rotatedClipIsSampledUprightAndRenderedWithItsRotation() = runBlocking {
        // Stored 128x64, red block in the stored top-left; then tagged "rotate 90° counter-clockwise".
        val plain = tmp.root.resolve("plain.mp4")
        ff("-f", "lavfi", "-i", "color=black:size=128x64:rate=10:duration=1",
            "-vf", "drawbox=x=0:y=0:w=32:h=32:color=red:t=fill", "-c:v", "libx264", "-pix_fmt", "yuv420p", plain.path)
        val rotated = tmp.root.resolve("rotated.mp4")
        ff("-display_rotation", "90", "-i", plain.path, "-c", "copy", rotated.path)
        val hash = sha(rotated)

        assertEquals(270, Probe.probe(rotated).rotationDegrees) // 90° CCW is 270° clockwise (Android's convention)
        val f = sampled(rotated).first()
        assertEquals(64 to 128, f.width to f.height)
        fun px(x: Int, y: Int) = (y * f.width + x) * 3
        // Rotating CCW takes the stored top-left to the upright bottom-left.
        val red = px(8, 120)
        assertTrue("red at bottom-left", (f.rgb[red].toInt() and 0xFF) > 180 && (f.rgb[red + 1].toInt() and 0xFF) < 70)
        assertTrue("black at top-left", (f.rgb[px(8, 8)].toInt() and 0xFF) < 40)

        val sizes = mutableSetOf<Pair<Int, Int>>()
        val edited = tmp.root.resolve("edited.mp4")
        Renderer.render(rotated, edited, AudioOut.Keep, { sizes += it.width to it.height }) {}
        assertEquals(setOf(128 to 64), sizes) // the renderer edits STORED frames
        assertEquals(270, Probe.probe(edited).rotationDegrees)
        val copied = tmp.root.resolve("copied.mp4")
        Renderer.render(rotated, copied, AudioOut.Keep, null) {}
        assertEquals(270, Probe.probe(copied).rotationDegrees)
        assertEquals(hash, sha(rotated))
    }

    @Test
    fun clockwiseRotationToo() {
        val plain = tmp.root.resolve("p.mp4")
        ff("-f", "lavfi", "-i", "color=black:size=64x32:rate=10:duration=0.3", "-c:v", "libx264", "-pix_fmt", "yuv420p", plain.path)
        val r = tmp.root.resolve("r.mp4")
        ff("-display_rotation", "-90", "-i", plain.path, "-c", "copy", r.path)
        assertEquals(90, Probe.probe(r).rotationDegrees)
    }

    @Test
    fun reencodeKeepsEveryOriginalPtsAndLeavesTheInputAlone() = runBlocking {
        val vfr = vfrClip()
        val hash = sha(vfr)
        val seen = mutableListOf<Long>()
        val out = tmp.root.resolve("out.mp4")
        var last = -1
        Renderer.render(vfr, out, AudioOut.Keep, { f ->
            seen += f.ptsMs
            f.rgb.fill(0) // a real edit: the whole frame goes black
        }) { last = it }
        assertEquals(listOf(0L, 120L, 480L), seen)
        assertEquals(listOf(0L, 120L, 480L), framePtsMs(out))
        assertEquals(listOf(0L, 120L, 480L), sampled(out).map { it.ptsMs })
        assertTrue(sampled(out).all { fr -> fr.rgb.all { (it.toInt() and 0xFF) < 24 } })
        assertEquals(100, last)
        assertEquals(hash, sha(vfr))

        val copied = tmp.root.resolve("copy.mp4")
        Renderer.render(vfr, copied, AudioOut.Drop, null) {}
        assertEquals(listOf(0L, 120L, 480L), framePtsMs(copied))
        assertEquals(hash, sha(vfr))
    }

    @Test
    fun audioOffsetSurvivesCopyAndReplace() = runBlocking {
        val src = tmp.root.resolve("offset.mp4")
        ff("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=2",
            "-itsoffset", "0.5", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1.5",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", src.path)
        val hash = sha(src)
        assertEquals(0L, startMs(src, "v"))
        val srcAudioStart = startMs(src, "a")
        assertTrue("fixture audio starts late: $srcAudioStart", abs(srcAudioStart - 500) <= 30)
        val srcPts = framePtsMs(src)

        val kept = tmp.root.resolve("kept.mp4")
        Renderer.render(src, kept, AudioOut.Keep, null) {}
        assertEquals(srcAudioStart, startMs(kept, "a"))
        assertEquals(srcPts, framePtsMs(kept))

        // Music removal's path: decode → (separate) → AacWriter → Replace.
        val m4a = tmp.root.resolve("music.m4a")
        var decoded = 0L
        AacWriter(m4a).use { w -> assertTrue(AudioDecoder.decode(src) { pcm, n -> decoded += n; w.write(pcm, n) }) }
        // 1.5 s, plus up to two AAC frames: FFmpeg's MP4 demuxer trims neither the end padding nor, behind
        // an empty edit, the priming. Whatever it hands over starts at the stream's start_time, which is
        // where Replace puts it back, so the offset below still has to match to the millisecond.
        assertTrue("decoded $decoded", decoded in 66_150L..66_150L + 2048)
        val replaced = tmp.root.resolve("replaced.mp4")
        Renderer.render(src, replaced, AudioOut.Replace(m4a), { }) {}
        // The .m4a's sample 0 lands exactly where the source audio began. Its own encoder priming (one AAC
        // frame, 1024 samples) sits just before it: FFmpeg's MP4 muxer can trim priming only at a track
        // that starts at 0, exactly as `ffmpeg -itsoffset … -c copy` leaves it.
        val priming = 1024 * 1000L / 44_100
        assertTrue("replaced audio at ${startMs(replaced, "a")}", abs(startMs(replaced, "a") - (srcAudioStart - priming)) <= 2)
        assertEquals(srcPts, framePtsMs(replaced))
        assertEquals(hash, sha(src))
    }

    @Test
    fun audioDecodesTo441StereoWithEveryFrame() = runBlocking {
        val wav = tmp.root.resolve("a.wav")
        ff("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2", "-c:a", "pcm_s16le", wav.path)
        var frames = 0L
        var mono = true
        assertTrue(AudioDecoder.decode(wav) { pcm, n ->
            frames += n
            for (i in 0 until n) if (pcm[2 * i] != pcm[2 * i + 1]) mono = false
        })
        assertEquals(88_200L, frames) // 96 000 samples at 48 kHz, resampled, tail flushed
        assertTrue("mono is duplicated into both channels", mono)

        val silent = tmp.root.resolve("silent.mp4")
        ff("-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=0.3", "-c:v", "libx264", "-pix_fmt", "yuv420p", silent.path)
        assertFalse(AudioDecoder.decode(silent) { _, _ -> fail("no audio to hand over") })
    }

    @Test
    fun aacWriterRoundTripsSampleCountAndLevel() = runBlocking {
        val n = 66_227 // not a multiple of the 1024-sample AAC frame
        val pcm = FloatArray(n * 2) { i -> 0.5f * sin(2 * Math.PI * 440 * (i / 2) / 44_100).toFloat() }
        val m4a = tmp.root.resolve("rt.m4a")
        AacWriter(m4a).use { w ->
            var off = 0
            while (off < n) { val k = minOf(4000, n - off); w.write(pcm.copyOfRange(off * 2, (off + k) * 2), k); off += k }
        }
        var count = 0L
        var sumSq = 0.0
        AudioDecoder.decode(m4a) { b, k -> count += k; for (i in 0 until k * 2) sumSq += b[i] * b[i] }
        // The priming is trimmed (edit list); the last frame's padding is not, as with any FFmpeg MP4 read.
        assertTrue("decoded $count of $n", count in n.toLong() until n + 1024L)
        assertEquals(0.5 / sqrt(2.0), sqrt(sumSq / (count * 2)), 0.02)
    }

    @Test
    fun outputEqualToInputIsRefused() = runBlocking {
        val input = clip()
        val hash = sha(input)
        val same = File(input.parentFile, "./sub/../${input.name}")
        tmp.root.resolve("sub").mkdir()
        try {
            Renderer.render(input, same, AudioOut.Keep, null) {}
            fail("rendered over its own input")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(hash, sha(input))
    }

    @Test
    fun failureAndCancelLeaveNoPartialFile() = runBlocking {
        val input = clip()
        val out = tmp.root.resolve("never.mp4")
        try {
            Renderer.render(input, out, AudioOut.Keep, { if (it.ptsMs > 500) error("boom") }) {}
            fail("the edit's failure was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        val job = async { Renderer.render(input, out, AudioOut.Keep, { if (it.ptsMs > 500) this.cancel() }) {} }
        try {
            job.await()
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse(out.exists())
        assertArrayEquals(arrayOf("in.mp4"), tmp.root.list()!!.sortedArray())
    }

    @Test
    fun anExistingOutputIsReplacedOnlyWhenAskedTo() = runBlocking {
        val input = clip()
        val out = tmp.root.resolve("out.mp4").apply { writeText("an earlier result") }
        try {
            Renderer.render(input, out, AudioOut.Keep, null) {}
            fail("replaced an existing file")
        } catch (_: FileAlreadyExistsException) {
        }
        assertEquals("an earlier result", out.readText())
        withContext(OverwriteOutput) { Renderer.render(input, out, AudioOut.Keep, null) {} }
        assertEquals(320, Probe.probe(out).width)
        assertArrayEquals(arrayOf("in.mp4", "out.mp4"), tmp.root.list()!!.sortedArray())
    }

    @Test
    fun aCancelBeforeTheRenameLeavesNothingAndOneAfterItIsDone() = runBlocking {
        val input = clip()
        val frames = framePtsMs(input).size
        // The last frame comes out of the decoder's drain, after the final read: cancelling there must still
        // leave no file (it used to be published anyway, with the job reported cancelled).
        val out = tmp.root.resolve("out.mp4")
        var seen = 0
        val drain = async { Renderer.render(input, out, AudioOut.Keep, { if (++seen == frames) this.cancel() }) {} }
        try {
            drain.await()
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse(out.exists())
        assertArrayEquals(arrayOf("in.mp4"), tmp.root.list()!!.sortedArray())

        // 100 comes once the output is in place: from there a cancel cannot undo the job, so render returns.
        var returned = false
        launch { Renderer.render(input, out, AudioOut.Keep, null) { if (it == 100) this.cancel() }; returned = true }.join()
        assertTrue("render threw after publishing", returned)
        assertEquals(320, Probe.probe(out).width)
    }

    @Test
    fun swsGetsFourZeroedPlaneSlots() {
        SwsPlanes().use { p ->
            assertEquals(4L, p.data.capacity())
            assertEquals(4L, p.stride.capacity())
            for (i in 1L..3L) {
                assertNull(p.data.get(i))
                assertEquals(0, p.stride.get(i))
            }
        }
    }

    @Test
    fun aTimestampGapInTheAudioSurvivesReencoding() = runBlocking {
        // 1 s of tone, a 3 s hole in the timestamps, 1 s more; mu-law, which MP4 cannot carry, so Keep
        // re-encodes it through AudioDecoder + AacWriter, the same path as music removal.
        val src = tmp.root.resolve("gap.mkv")
        ff("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=5",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2",
            "-filter:a", "asetpts=PTS+if(gte(T\\,1)\\,3/TB\\,0)", "-ac", "2",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "pcm_mulaw", src.path)
        val packets = run(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts_time,duration_time",
            "-of", "csv=p=0", src.path).lines().filter { it.isNotBlank() }
            .map { l -> l.split(',').let { it[0].toDouble() to it[1].toDouble() } }
        val resumeS = packets.zipWithNext().first { (a, b) -> b.first - a.first > 1.0 }.second.first
        val endS = packets.last().let { it.first + it.second }
        assertTrue("fixture has its gap: $resumeS", resumeS > 3.9)

        val out = tmp.root.resolve("out.mp4")
        Renderer.render(src, out, AudioOut.Keep, null) {}
        val raw = tmp.root.resolve("out.raw")
        ff("-i", out.path, "-map", "0:a", "-ac", "1", "-ar", "44100", "-f", "f32le", raw.path)
        val pcm = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(pcm.remaining()).also { pcm.get(it) }
        val startS = startMs(out, "a") / 1000.0
        // The tone resumes at the first sound after a second of silence.
        fun sound(i: Int) = abs(samples[i]) > 0.01f
        val resumeAt = (44_100 until samples.size).firstOrNull { i -> sound(i) && (1..44_100).none { sound(i - it) } } ?: -1
        val aacFrameS = 1024 / 44_100.0
        assertEquals(resumeS, startS + resumeAt / 44_100.0, aacFrameS)
        assertEquals(endS, startS + samples.size / 44_100.0, aacFrameS)
    }

    @Test
    fun aTimestampDiscontinuityInAnMpegTsNeitherInsertsSilenceNorDropsAudio() = runBlocking {
        // Codex re-review, finding 6: three MPEG-TS segments back to back, the second's clock 60 s ahead and
        // the third's reset to where the first began, as a broadcast splice or a concatenated recording has.
        // It used to come out as 60 s of silence with the third segment dropped.
        val tones = listOf(440 to 1.0, 880 to 1.5, 1320 to 2.0)
        val parts = tones.mapIndexed { i, (hz, s) ->
            tmp.root.resolve("p$i.ts").also {
                ff("-f", "lavfi", "-i", "sine=frequency=$hz:sample_rate=44100:duration=$s", "-c:a", "aac",
                    *(if (i == 1) arrayOf("-output_ts_offset", "60") else emptyArray()), "-f", "mpegts", it.path)
            }
        }
        val ts = tmp.root.resolve("splice.ts")
        ts.writeBytes(parts.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() })

        val left = ArrayList<Float>()
        assertTrue(AudioDecoder.decode(ts) { pcm, n -> for (i in 0 until n) left += pcm[2 * i] })
        // Every segment, once: their sum, plus at most two AAC frames each (priming and end padding, which
        // MPEG-TS has no way to trim).
        val sum = tones.sumOf { it.second * 44_100 }.toInt()
        assertTrue("decoded ${left.size} of $sum", left.size in sum..sum + 3 * 2048)
        // No inserted hole: never 100 ms of silence in a row.
        var run = 0
        var longest = 0
        for (x in left) { run = if (abs(x) < 1e-3f) run + 1 else 0; longest = maxOf(longest, run) }
        assertTrue("silent for $longest samples", longest < 4_410)
        // And in order: the tone over the middle of each segment is that segment's.
        var at = 0.0
        for ((hz, s) in tones) {
            val from = ((at + s / 4) * 44_100).toInt()
            val to = ((at + s * 3 / 4) * 44_100).toInt()
            val crossings = (from + 1 until to).count { (left[it - 1] < 0f) != (left[it] < 0f) }
            assertEquals("tone at ${at + s / 2} s", hz.toDouble(), crossings / 2.0 / ((to - from) / 44_100.0), hz * 0.05)
            at += s
        }
    }
}
