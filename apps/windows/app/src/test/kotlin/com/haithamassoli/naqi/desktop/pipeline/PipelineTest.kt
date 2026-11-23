package com.haithamassoli.naqi.desktop.pipeline

import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.desktop.media.AudioDecoder
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.desktop.media.VideoDecoder
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacpp.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The whole job, end to end through [Pipeline.run] with the real models, on a clip made here with the bundled
 * ffmpeg: 8 s, 640x640 so the analysis frame is the stored frame, Sally Ride's portrait (`vision/portrait.jpg`,
 * public domain) on screen from 2 s to 6 s, an orange square in the corner, and a plucked music bed. Her face
 * is ~130 px, well above MIN_FACE_PX, so the gender vote really runs. Skipped when the models are not staged.
 */
class PipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ffmpeg: String by lazy { Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }
    private val ffprobe: String by lazy { Loader.load(org.bytedeco.ffmpeg.ffprobe::class.java) }
    private lateinit var input: File

    // In the 640x640 frame: her face (ModelsInVisionTest's box scaled by 640/625 and moved 64 px right),
    // shrunk a little so it is all face; her shirt, far below any padded face rect; the orange square.
    private val face = intArrayOf(285, 165, 375, 285)
    private val shirt = intArrayOf(200, 520, 440, 620)
    private val square = intArrayOf(570, 0, 640, 70)

    @Before fun clip() {
        assumeTrue(listOf(Models.YUNET, Models.GENDERAGE, Models.HTDEMUCS).all { Models.fileOrNull(it) != null })
        val portrait = tmp.newFile("portrait.jpg")
        portrait.writeBytes(javaClass.getResource("/vision/portrait.jpg")!!.readBytes())
        input = File(tmp.newFolder("work"), "in.mp4")
        run(ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "color=c=0x2a3d52:s=640x640:r=30:d=8",
            "-loop", "1", "-i", portrait.path,
            "-f", "lavfi", "-i", "aevalsrc=exprs='0.4*sin(2*PI*(220+55*floor(mod(t*4,4)))*t)*exp(-6*mod(t,0.25))':s=48000:d=8",
            "-filter_complex", "[1]scale=-2:640[p];[0]drawbox=x=580:y=10:w=50:h=50:c=orange:t=fill[bg];" +
                "[bg][p]overlay=x=64:y=0:enable='between(t,2,6)'[v]",
            "-map", "[v]", "-map", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
            "-c:a", "aac", "-ac", "2", "-t", "8", input.path)
    }

    @Test fun musicAndWomenWithAFillLeaveTheOriginalAndKeepTheTiming() = runBlocking {
        val before = sha(input)
        val output = File(input.parentFile, "out.mp4")
        val ops = FilterOps(removeMusic = true, keepStems = "vocals", censorWho = FilterOps.WOMEN,
            censorNsfw = false, solidColor = FilterOps.SOLID_COLORS[1])
        val seen = mutableListOf<Pair<Stage, Int>>()
        Pipeline.run(JobOptions(input, output, ops)) { s, p -> seen += s to p }

        assertEquals(listOf(Stage.ANALYZE, Stage.MUSIC, Stage.RENDER), seen.map { it.first }.distinct())
        for (s in Stage.entries) assertEquals("$s ends at 100", 100, seen.last { it.first == s }.second)
        assertEquals("the original is untouched", before, sha(input))
        assertEquals("nothing but the output is left", setOf("in.mp4", "out.mp4"), input.parentFile.list()!!.toSet())

        // Every frame keeps its own pts, and the audio starts where the source's did with the same length
        // (within one AAC frame of encoder padding at the end).
        assertEquals(ptsList(input), ptsList(output))
        assertEquals(startMs(input, "a"), startMs(output, "a"))
        val (inFrames, outFrames) = audioFrames(input) to audioFrames(output)
        assertTrue("audio $inFrames -> $outFrames", outFrames - inFrames in 0..1024)

        val (a, b) = frameAt(input, 4000) to frameAt(output, 4000)
        assertTrue("her face is filled", diff(a, b, face) > 40)
        assertTrue("her shirt is not", diff(a, b, shirt) < 6)
        assertTrue("nothing before she appears", diff(frameAt(input, 1000), frameAt(output, 1000), face) < 6)
    }

    @Test fun menSparesHerSoTheVideoIsCopied() = runBlocking {
        val output = File(input.parentFile, "out.mp4")
        Pipeline.run(JobOptions(input, output, FilterOps(censorWho = FilterOps.MEN, censorNsfw = false))) { _, _ -> }
        // An empty EDL is a stream copy, so the decoded picture is bit-identical.
        assertTrue(frameAt(input, 4000).rgb.contentEquals(frameAt(output, 4000).rgb))
    }

    @Test fun wholeFrameCoversEverythingOnlyWhileAFaceShows() = runBlocking {
        val output = File(input.parentFile, "out.mp4")
        Pipeline.run(JobOptions(input, output, FilterOps(censorWho = FilterOps.EVERYONE, wholeFrameBlur = true, censorNsfw = false))) { _, _ -> }
        assertTrue("the corner square is blurred too", diff(frameAt(input, 4000), frameAt(output, 4000), square) > 15)
        for (t in listOf(1000L, 7000L)) assertTrue("untouched at $t", diff(frameAt(input, t), frameAt(output, t), square) < 6)
    }

    @Test fun cancelLeavesNoOutputAndNoScratch() = runBlocking {
        val output = File(input.parentFile, "out.mp4")
        val inMusic = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            Pipeline.run(JobOptions(input, output, FilterOps(removeMusic = true, censorWho = FilterOps.EVERYONE))) { s, _ ->
                if (s == Stage.MUSIC) inMusic.complete(Unit)
            }
        }
        inMusic.await()
        job.cancel()
        job.join()
        assertFalse(output.exists())
        assertEquals(setOf("in.mp4"), input.parentFile.list()!!.toSet())
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}\n$out" }
        return out
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun startMs(f: File, type: String): Long =
        run(ffprobe, "-v", "error", "-select_streams", "$type:0", "-show_entries", "stream=start_time", "-of", "csv=p=0", f.path)
            .trim().trimEnd(',').toDouble().let { Math.round(it * 1000) }

    /** A slot every ms, so every frame is taken. */
    private suspend fun ptsList(f: File): List<Long> = mutableListOf<Long>().also { l -> VideoDecoder.sample(f, 1000f) { l += it.ptsMs } }

    private suspend fun frameAt(f: File, ms: Long): RgbFrame {
        var found: RgbFrame? = null
        VideoDecoder.sample(f, 1000f) { if (it.ptsMs == ms) found = RgbFrame(it.width, it.height, it.rgb.copyOf(), it.ptsMs) }
        return checkNotNull(found) { "no frame at $ms ms in $f" }
    }

    private suspend fun audioFrames(f: File): Long {
        var n = 0L
        assertTrue(AudioDecoder.decode(f) { _, frames -> n += frames })
        return n
    }

    /** Mean absolute difference per channel inside the box (x0, y0, x1, y1). */
    private fun diff(a: RgbFrame, b: RgbFrame, box: IntArray): Double {
        var sum = 0L
        for (y in box[1] until box[3]) for (x in box[0] until box[2]) for (c in 0..2) {
            val i = 3 * (y * a.width + x) + c
            sum += abs((a.rgb[i].toInt() and 0xFF) - (b.rgb[i].toInt() and 0xFF))
        }
        return sum.toDouble() / ((box[2] - box[0]) * (box[3] - box[1]) * 3)
    }
}
