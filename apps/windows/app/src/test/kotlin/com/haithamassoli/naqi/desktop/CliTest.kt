package com.haithamassoli.naqi.desktop

import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CliTest {
    private val dir = Files.createTempDirectory("naqi-cli").toFile()
    private val input = File(dir, "in.mp4").apply { writeText("x") }
    private val base = listOf("--input", input.path, "--output", File(dir, "out.mp4").path)

    @Test
    fun everyOptionMapsOntoFilterOps() {
        val ops = parseCli(base + listOf("--music", "vocals+other", "--faces", "women", "--style", "fill:navy,whole", "--nsfw", "70")).options.ops
        assertTrue(ops.removeMusic)
        assertEquals("vocals_other", ops.keepStems)
        assertEquals(FilterOps.WOMEN, ops.censorWho)
        assertEquals(FilterOps.SOLID_COLORS[3], ops.solidColor)
        assertTrue(ops.wholeFrameBlur)
        assertTrue(ops.censorNsfw)
        assertEquals(70, ops.strictness)

        val blur = parseCli(base + listOf("--faces", "everyone", "--style", "blur:30,gray")).options.ops
        assertEquals(FilterOps.BLUR, blur.solidColor)
        assertEquals(30, blur.blurAmount)
        assertTrue(blur.grayscale)
        assertFalse("no --nsfw means no scene gate", blur.censorNsfw)
        assertFalse(blur.removeMusic)
    }

    @Test
    fun badArgumentsAreRefused() {
        fun bad(vararg extra: String) = assertThrows(IllegalArgumentException::class.java) { parseCli(base + extra) }
        bad() // nothing to do
        bad("--music", "drums")
        bad("--faces", "cats")
        bad("--nsfw", "101")
        bad("--faces", "men", "--style", "fill:pink")
        bad("--faces", "men", "--bogus", "1")
        assertThrows(IllegalArgumentException::class.java) {
            parseCli(listOf("--input", input.path, "--output", input.path, "--music", "vocals"))
        }
        assertEquals(2, runCli(listOf("--music", "vocals")))
    }

    @Test
    fun anExistingOutputNeedsOverwrite() {
        File(dir, "out.mp4").writeText("an earlier result")
        assertThrows(IllegalArgumentException::class.java) { parseCli(base + listOf("--music", "vocals")) }
        assertEquals(2, runCli(base + listOf("--music", "vocals")))
        assertTrue(parseCli(base + listOf("--music", "vocals", "--overwrite")).overwrite)
        assertFalse(parseCli(listOf("--input", input.path, "--output", File(dir, "new.mp4").path, "--music", "vocals")).overwrite)
    }
}
