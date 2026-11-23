package com.haithamassoli.naqi.desktop.audio

import com.haithamassoli.naqi.desktop.Models
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real models on synthetic audio: 30 s of band-limited noise (the "speech"), with an arpeggio and a kick (the
 * "music") added from [MUSIC_FROM] on; see [clip]. Skips when the models are not staged (`./gradlew :app:stageModels`).
 */
class MusicRemovalTest {

    @Test
    fun noAudioStreamWritesNothing() = runBlocking {
        var wrote = false
        val had = MusicRemoval.removeMusic(
            decode = { false }, keepStems = "vocals", estFrames = 0, modelsDir = File("/nonexistent"),
            write = { _, _ -> wrote = true }, onProgress = {},
        )
        assertFalse(had)
        assertFalse(wrote)
    }

    /**
     * The gate forced on, so a 30 s clip exercises it (Android only gates 10 min or more). Chunks 0-3 hold
     * only noise and nothing within two chunks of them is music, so YAMNet must let them through untouched:
     * every sample covered by those chunks alone comes back as the input, up to the float rounding of
     * core's normalize / weighted overlap-add / denormalize. Not bit-exact: core divides by the chunk weight
     * sum and the track's std, which is the passthrough Android ships too.
     */
    @Test
    fun gatedRunPassesSpeechThroughAndRemovesTheMusic() = runBlocking {
        val models = modelsDir()
        val input = clip()
        val out = separate(input, models, gate = true, keepStems = "vocals")
        assertEquals("same length in samples", input.size, out.size)

        // Chunk 4 starts at virtual 4 * STRIDE = 412 776, i.e. 390 726 real frames, after the 0.5 s pre-pad.
        val clean = 390_000
        var maxErr = 0f
        for (i in 0 until 2 * clean) maxErr = maxOf(maxErr, abs(out[i] - input[i]))
        println("passthrough max |err| over the clean span: $maxErr")
        assertTrue("clean span passes through (max err $maxErr)", maxErr < 1e-5f)

        // How much of the music survives: the output's projection onto it (1 = all of it, 0 = none). Not a
        // residual against the speech, because htdemucs rightly drops noise from the vocals stem as well.
        val from = 2 * (MUSIC_FROM * RATE)
        val music = clip(speech = false)
        var dot = 0.0
        var norm = 0.0
        for (i in from until out.size) { dot += out[i].toDouble() * music[i]; norm += music[i].toDouble() * music[i] }
        val kept = dot / norm
        println("music kept: $kept, music-span rms ${rms(input, from, input.size)} -> ${rms(out, from, out.size)}")
        assertTrue("music removed ($kept kept)", abs(kept) < 0.1)
    }

    /** The default route for a 30 s clip: no gate, every chunk separated. Prints the realtime factor. */
    @Test
    fun ungatedRunKeepsTheLengthAndReportsRealtimeFactor() = runBlocking {
        val models = modelsDir()
        val input = clip()
        for (keep in listOf("vocals", "vocals_other")) {
            val t0 = System.nanoTime()
            val out = separate(input, models, gate = false, keepStems = keep)
            val wall = (System.nanoTime() - t0) / 1e9
            println("$keep: ${"%.2f".format(wall)} s for $SECONDS s of audio = ${"%.3f".format(wall / SECONDS)}x realtime")
            assertEquals("same length in samples ($keep)", input.size, out.size)
            assertTrue("finite output", out.all { it.isFinite() })
        }
    }

    private suspend fun separate(input: FloatArray, models: File, gate: Boolean, keepStems: String): FloatArray {
        val frames = input.size / 2
        val out = FloatArray(input.size)
        var cursor = 0
        var lastPct = -1
        val had = MusicRemoval.removeMusic(
            // Ragged 1 024-frame batches, like an AAC decoder's, through a reused array.
            decode = { onPcm ->
                val batch = FloatArray(2 * 1024)
                var fed = 0
                while (fed < frames) {
                    val n = minOf(1024, frames - fed)
                    System.arraycopy(input, 2 * fed, batch, 0, 2 * n)
                    onPcm(batch, n)
                    fed += n
                }
                true
            },
            keepStems = keepStems,
            estFrames = frames.toLong(),
            modelsDir = models,
            write = { buf, n ->
                System.arraycopy(buf, 0, out, cursor, 2 * n)
                cursor += 2 * n
            },
            onProgress = { assertTrue("progress moves forward", it >= lastPct); lastPct = it },
            gate = gate,
        )
        assertTrue(had)
        assertEquals("progress ends at 100", 100, lastPct)
        return out.copyOf(cursor)
    }

    private fun modelsDir(): File {
        assumeNotNull(Models.fileOrNull(Models.HTDEMUCS), Models.fileOrNull(Models.YAMNET))
        return Models.dir()
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /**
     * Interleaved stereo. The "speech" is MA(2) noise at ~0.06 RMS throughout, which YAMNet scores well
     * under its music threshold. The "music", from [MUSIC_FROM] on, is a plucked A-minor arpeggio (decaying
     * harmonics, an eighth note each) over a kick on every beat: htdemucs puts a steady sine chord partly in
     * the vocals stem, so the music has to sound like music.
     */
    private fun clip(speech: Boolean = true, music: Boolean = true): FloatArray {
        val frames = SECONDS * RATE
        val rnd = Random(42)
        val out = FloatArray(2 * frames)
        val notes = doubleArrayOf(220.0, 261.63, 329.63, 440.0)
        var pl = 0f
        var pr = 0f
        for (i in 0 until frames) {
            val l = (rnd.nextFloat() - 0.5f) * 0.3f
            val r = (rnd.nextFloat() - 0.5f) * 0.3f
            var m = 0.0
            if (music && i >= MUSIC_FROM * RATE) {
                val t = (i - MUSIC_FROM * RATE).toDouble() / RATE
                val note = (t / 0.25).toInt()
                val nt = t - note * 0.25 // time since this note's pluck
                val f = notes[note % notes.size]
                for (h in 1..5) m += 0.12 / h * sin(2 * PI * f * h * nt) * exp(-(4.0 + 2 * h) * nt)
                val bt = t % 0.5 // kick: a falling 120 -> 50 Hz thump
                m += 0.4 * sin(2 * PI * (120 * bt - 70 * bt * bt / 0.1).coerceAtLeast(0.0)) * exp(-25 * bt)
            }
            val k = if (speech) 0.5f else 0f
            out[2 * i] = k * (l + pl) + m.toFloat()
            out[2 * i + 1] = k * (r + pr) + m.toFloat()
            pl = l
            pr = r
        }
        return out
    }

    private companion object {
        const val RATE = 44_100
        const val SECONDS = 30
        const val MUSIC_FROM = 14
    }
}
