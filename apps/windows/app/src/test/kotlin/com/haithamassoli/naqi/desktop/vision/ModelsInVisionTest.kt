package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.analysis.NsfwGate
import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.ml.NSFW_CLASSES
import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import javax.imageio.ImageIO

/**
 * The real models on one real face. Skipped when the models are not staged (`./gradlew :app:stageModels`).
 *
 * `portrait.jpg` is Sally Ride's 1984 NASA portrait (public domain, NASA photo S84-37256), the 500 px
 * Wikimedia Commons thumbnail of https://commons.wikimedia.org/wiki/File:Sally_Ride_(1984).jpg.
 * Her face is at about (196, 144)–(324, 292) of 500x625, measured with the same decode in Python.
 */
class ModelsInVisionTest {
    private val dir: File = Models.dir()

    private fun need(vararg names: String) = assumeTrue(names.all { File(dir, it).isFile })

    private fun portrait(scale: Int = 1): RgbFrame {
        val src = ImageIO.read(javaClass.getResource("/vision/portrait.jpg"))
        val img = if (scale == 1) src else BufferedImage(src.width * scale, src.height * scale, BufferedImage.TYPE_INT_RGB)
            .also { it.createGraphics().drawImage(src, 0, 0, it.width, it.height, null) }
        val rgb = ByteArray(img.width * img.height * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val i = 3 * (y * img.width + x)
            rgb[i] = (p shr 16).toByte(); rgb[i + 1] = (p shr 8).toByte(); rgb[i + 2] = p.toByte()
        }
        return RgbFrame(img.width, img.height, rgb, 0L)
    }

    private val face = NRect(196 / 500f, 144 / 625f, 324 / 500f, 292 / 625f)

    @Test fun yunetFindsTheOneFaceWhereItIs() {
        need(Models.YUNET)
        YuNet(File(dir, Models.YUNET)).use { yunet ->
            // As given, and scaled past 640 so the downscale-into-the-canvas path runs too.
            for (scale in 1..2) {
                val found = yunet.detect(portrait(scale))
                assertEquals("scale $scale: $found", 1, found.size)
                assertNear(face, found[0], 0.03f)
            }
        }
    }

    @Test fun genderageVotesFemaleOnTheCropAndroidWouldCut() {
        need(Models.GENDERAGE)
        GenderVoter(File(dir, Models.GENDERAGE)).use { assertEquals(-1, it.vote(portrait(), face)) }
    }

    @Test fun faceAnalyzerCensorsHerUnderWomenAndEveryoneAndSparesHerUnderMen() {
        need(Models.YUNET, Models.GENDERAGE)
        val frame = portrait()
        for ((who, tracks) in listOf(FilterOps.WOMEN to 1, FilterOps.EVERYONE to 1, FilterOps.MEN to 0)) {
            FaceAnalyzer(who, dir).use { a ->
                for (i in 0..2) a.onFrame(RgbFrame(frame.width, frame.height, frame.rgb, i * 100L))
                val edls = a.finish()
                assertEquals(who, tracks, edls.size)
                if (tracks == 1) assertEquals(0L to 250L, edls[0].startMs to edls[0].endMs)
            }
        }
    }

    @Test fun nsfwGivesFiveProbabilitiesAndTheStrictnessTableDecides() {
        need(Models.NSFW)
        val probs = NsfwClassifier(dir).use { it.classify(portrait()) }
        assertEquals(NSFW_CLASSES.size, probs.size)
        assertEquals(1f, probs.sum(), 0.02f)
        // A clothed portrait: neutral wins (~0.85), with some "sexy" (~0.14).
        assertEquals("neutral", NSFW_CLASSES[probs.indices.maxBy { probs[it] }])
        assertFalse(NsfwGate.fires(probs, 0))
        assertFalse(NsfwGate.fires(probs, FilterOps.DEFAULT_STRICTNESS))
        // At 100, sexy's threshold is 0.10 and neutral's veto is off (threshold 1.0): a bare shoulder-up
        // portrait is covered. That is the table's intent for the strictest setting, not a model error.
        assertTrue(NsfwGate.fires(probs, 100))
    }

    @Test fun genderCropIsAndroidsSquareOfOneAndAHalfFaces() {
        // A 4x2 frame whose pixel value is its x; a box over pixel (1, 0)..(2, 1) gives a 3-px square
        // centred at (1.5, 0.5), so the crop walks x from 0 to 2 and clamps y at the edges.
        val rgb = ByteArray(4 * 2 * 3) { i -> ((i / 3) % 4).toByte() }
        val out = FloatBuffer.allocate(3 * 96 * 96)
        GenderVoter.cropToTensor(RgbFrame(4, 2, rgb, 0L), NRect(0.25f, 0f, 0.5f, 0.5f), out)
        assertEquals(0f, out.get(0), 0f)          // top-left of the crop: x = 0
        assertEquals(2f, out.get(95), 0f)         // top-right: x = 2
        assertEquals(1f, out.get(48), 0f)         // middle: x = 1
    }

    private fun assertNear(expected: NRect, actual: NRect, tol: Float) {
        assertEquals("left", expected.left, actual.left, tol)
        assertEquals("top", expected.top, actual.top, tol)
        assertEquals("right", expected.right, actual.right, tol)
        assertEquals("bottom", expected.bottom, actual.bottom, tol)
    }
}
