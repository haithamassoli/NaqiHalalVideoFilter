package com.haithamassoli.naqi.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Brand tokens beyond the Material3 roles. Expressive design tokens.
object NaqiTokens {
    // Spacing scale (4dp base)
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 24.dp
    val space6 = 32.dp
    val space7 = 48.dp

    // M3 Expressive Shapes — soft, organic, friendly. Only radiusButton is also reached for on its own
    // (call sites that clip to it directly); the rest are inlined into the shape, which is what the
    // scale existed to produce. The four radii nothing used at all are gone.
    val radiusButton = 20.dp

    val shapeCard = RoundedCornerShape(24.dp)
    val shapeButton = RoundedCornerShape(radiusButton)
    val shapePill = RoundedCornerShape(999.dp)

    // M3 Expressive Spring Motion Specs
    fun <T> expressiveSpring(
        stiffness: Float = Spring.StiffnessMediumLow,
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
    ) = spring<T>(stiffness = stiffness, dampingRatio = dampingRatio)

    // Edge padding for the screen gutter
    val gutter = 20.dp
}

