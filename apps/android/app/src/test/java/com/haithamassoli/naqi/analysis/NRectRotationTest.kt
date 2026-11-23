package com.haithamassoli.naqi.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Upright -> stored-space mapping used by pass 2. Fixture: an upright rect hugging the TOP-LEFT
 * corner. For rotation 90 (stored rotated 90 cw = upright), the upright top-left corner comes from
 * the stored frame's BOTTOM-LEFT; for 270 from the TOP-RIGHT; for 180 from the BOTTOM-RIGHT.
 */
class NRectRotationTest {

    private val topLeft = NRect(0.0f, 0.0f, 0.2f, 0.4f)

    private fun assertRect(expected: NRect, actual: NRect) {
        assertEquals(expected.left, actual.left, 1e-6f)
        assertEquals(expected.top, actual.top, 1e-6f)
        assertEquals(expected.right, actual.right, 1e-6f)
        assertEquals(expected.bottom, actual.bottom, 1e-6f)
    }

    @Test fun identityForUnrotated() = assertRect(topLeft, topLeft.toStoredSpace(0))

    @Test fun rotation90MapsToBottomLeft() =
        assertRect(NRect(0.0f, 0.8f, 0.4f, 1.0f), topLeft.toStoredSpace(90))

    @Test fun rotation270MapsToTopRight() =
        assertRect(NRect(0.6f, 0.0f, 1.0f, 0.2f), topLeft.toStoredSpace(270))

    @Test fun rotation180MapsToBottomRight() =
        assertRect(NRect(0.8f, 0.6f, 1.0f, 1.0f), topLeft.toStoredSpace(180))

    @Test fun negativeAndOverflowDegreesNormalize() {
        assertRect(topLeft.toStoredSpace(90), topLeft.toStoredSpace(-270))
        assertRect(topLeft.toStoredSpace(90), topLeft.toStoredSpace(450))
    }
}
