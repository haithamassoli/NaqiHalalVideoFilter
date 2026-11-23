package com.haithamassoli.naqi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

// Dynamic color is deliberately not offered: the brand identity must win over the wallpaper palette.
// Material 3 Expressive theme applies spring motion physics and expressive component styles.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NaqiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}

