package com.haithamassoli.naqi.render

import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The ARGB -> shader-rgb unpack. A swapped shift here ships a blue censor bar where the user picked
 * navy — visible only by rendering a video, which is why it is checked on the JVM instead.
 */
class SolidColorTest {

    @Test
    fun `unpacks channels in r g b order`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), solidRgb(0xFFFF0000.toInt()), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), solidRgb(0xFF00FF00.toInt()), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), solidRgb(0xFF0000FF.toInt()), 0.001f)
    }

    /** Alpha is the mode flag, not a channel — it must not leak into the fill. */
    @Test
    fun `ignores alpha`() {
        assertArrayEquals(solidRgb(0xFF2C3E50.toInt()), solidRgb(0x002C3E50), 0.001f)
    }

    @Test
    fun `every offered swatch is opaque, so none of them reads as blur`() {
        FilterOps.SOLID_COLORS.forEach { assertNotEquals(FilterOps.BLUR, it) }
        assertEquals(FilterOps.BLUR, FilterOps().solidColor) // blur stays the default
    }
}
