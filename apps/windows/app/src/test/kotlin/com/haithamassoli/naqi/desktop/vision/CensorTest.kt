package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pixel tests of the compositor on synthetic frames. */
class CensorTest {
    /** A 1-px checkerboard of black and white: as sharp as an image gets, so any blur shows. */
    private fun checker(w: Int = 100, h: Int = 100) = RgbFrame(w, h, ByteArray(w * h * 3) { i ->
        val p = i / 3
        if ((p % w + p / w) % 2 == 0) 0 else 255.toByte()
    }, 0L)

    private fun RgbFrame.px(x: Int, y: Int, c: Int = 0) = rgb[3 * (y * width + x) + c].toInt() and 0xFF

    // 0.3..0.7 of a 100-px frame: pixels 30..69 inside; the feather band is 15% of 0.4 = 6 px outside.
    private val region = NRect(0.3f, 0.3f, 0.7f, 0.7f)

    @Test fun solidFillIsExactInsideAndLeavesEverythingPastTheFeatherUntouched() {
        val navy = FilterOps.SOLID_COLORS[3]
        val f = checker()
        val before = f.rgb.copyOf()
        Censor.apply(f, listOf(region), fullFrame = false, blurAmount = 60, grayscale = true, solidColor = navy)
        for (y in 30..69) for (x in 30..69) {
            assertEquals(0x2C, f.px(x, y, 0)); assertEquals(0x3E, f.px(x, y, 1)); assertEquals(0x50, f.px(x, y, 2))
        }
        for (y in 0 until 100) for (x in 0 until 100) {
            if (x in 24..75 && y in 24..75) continue
            for (c in 0..2) assertEquals(before[3 * (y * 100 + x) + c], f.rgb[3 * (y * 100 + x) + c])
        }
        // In the band the fill fades out: part way between the original and navy, never past the original.
        val edge = f.px(27, 50)
        val orig = before[3 * (50 * 100 + 27)].toInt() and 0xFF
        assertTrue("feathered $edge vs $orig", edge != orig && edge != 0x2C)
    }

    @Test fun blurFlattensTheRegionAndOnlyTheRegion() {
        val f = checker()
        val before = f.rgb.copyOf()
        Censor.apply(f, listOf(region), false, blurAmount = 60, grayscale = false, solidColor = FilterOps.BLUR)
        for (y in 30..69) for (x in 30..69) for (c in 0..2) {
            assertTrue("($x,$y) = ${f.px(x, y, c)}", f.px(x, y, c) in 110..145) // 0/255 averaged toward 127
        }
        for (y in 0 until 100) for (x in 0 until 24) for (c in 0..2) {
            assertEquals(before[3 * (y * 100 + x) + c], f.rgb[3 * (y * 100 + x) + c])
        }
    }

    @Test fun grayscaleMakesChannelsEqualWithBt709Weights() {
        val f = RgbFrame(10, 10, ByteArray(300) { i -> if (i % 3 == 0) 200.toByte() else 0 }, 0L) // pure red 200
        Censor.apply(f, emptyList(), fullFrame = true, blurAmount = 60, grayscale = true, solidColor = FilterOps.BLUR)
        val luma = Math.round(0.2126f * 200) // a flat frame blurs to itself
        for (i in 0 until 300) assertEquals(luma, f.rgb[i].toInt() and 0xFF)
    }

    @Test fun wholeFrameCoversEveryPixel() {
        val f = checker()
        Censor.apply(f, emptyList(), fullFrame = true, blurAmount = 0, grayscale = false, solidColor = FilterOps.SOLID_COLORS[2])
        assertTrue(f.rgb.all { it == 255.toByte() })
    }

    @Test fun nothingActiveIsANoOp() {
        val f = checker()
        val before = f.rgb.copyOf()
        Censor.apply(f, emptyList(), false, 60, true, FilterOps.BLUR)
        assertArrayEquals(before, f.rgb)
    }

    @Test fun theNoOpStyleStillBlurs() {
        // blur 0, no gray, no fill would censor nothing; Android bumps it to a light blur and so does this.
        val f = checker()
        Censor.apply(f, listOf(region), false, blurAmount = 0, grayscale = false, solidColor = FilterOps.BLUR)
        assertTrue(f.px(50, 50) in 60..195)
    }

    @Test fun aRegionIsBlurredExactlyAsTheWholeFrameIs() {
        // 400x300 at blur 100: sigma 11 px, so the blur runs at 1/4 size with a 7-tap radius. A region only
        // blurs a window around itself; that window must not change a covered pixel's value.
        val noise = java.util.Random(7)
        val src = ByteArray(400 * 300 * 3).also { noise.nextBytes(it) }
        val part = RgbFrame(400, 300, src.copyOf(), 0L)
        val whole = RgbFrame(400, 300, src.copyOf(), 0L)
        val r = NRect(0.4f, 0.4f, 0.6f, 0.6f)
        Censor.apply(part, listOf(r), false, 100, false, FilterOps.BLUR)
        Censor.apply(whole, emptyList(), true, 100, false, FilterOps.BLUR)
        for (y in 120 until 180) for (x in 160 until 240) for (c in 0..2) {
            assertEquals(whole.px(x, y, c), part.px(x, y, c))
        }
    }

    @Test fun rectsPastTheFrameEdgeAreClipped() {
        val f = checker()
        Censor.apply(f, listOf(NRect(-0.2f, 0.8f, 0.3f, 1.3f)), false, 60, false, FilterOps.SOLID_COLORS[1])
        assertEquals(0, f.px(0, 99))
        assertEquals(0, f.px(29, 80))
    }
}
