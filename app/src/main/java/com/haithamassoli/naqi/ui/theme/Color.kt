package com.haithamassoli.naqi.ui.theme

import androidx.compose.ui.graphics.Color

// Brand raw palette — "filtered water / clarity". Jade for trust + purity;
// ink/paper for a clean, calm reading surface.
val NaqiJade = Color(0xFF1F6E5A)
val NaqiJadeBright = Color(0xFF55C3A1)
val NaqiDeep = Color(0xFF0C1512)
val NaqiInk = Color(0xFF10201C)
val NaqiPaper = Color(0xFFF5F7F3)

internal val LightColors = androidx.compose.material3.lightColorScheme(
    primary = NaqiJade,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8E9D3),
    onPrimaryContainer = Color(0xFF00251A),
    secondary = Color(0xFF4B635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9DC),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF7C5A34),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5DDBB),
    onTertiaryContainer = Color(0xFF2A1800),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = NaqiPaper,
    onBackground = NaqiInk,
    surface = NaqiPaper,
    onSurface = NaqiInk,
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4A45),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4F0),
    surfaceContainer = Color(0xFFECF1ED),
    surfaceContainerHigh = Color(0xFFE6ECE8),
    surfaceContainerHighest = Color(0xFFE0E7E2),
    outline = Color(0xFF6F7A74),
    outlineVariant = Color(0xFFBFC9C3),
    inverseSurface = Color(0xFF2B322F),
    inverseOnSurface = Color(0xFFECF1ED),
    inversePrimary = NaqiJadeBright,
    scrim = Color(0xFF000000),
)

internal val DarkColors = androidx.compose.material3.darkColorScheme(
    primary = NaqiJadeBright,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF005440),
    onPrimaryContainer = Color(0xFFA8E9D3),
    secondary = Color(0xFFB1CCBF),
    onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF344B42),
    onSecondaryContainer = Color(0xFFCDE9DC),
    tertiary = Color(0xFFE7C08C),
    onTertiary = Color(0xFF452B08),
    tertiaryContainer = Color(0xFF5F421F),
    onTertiaryContainer = Color(0xFFF5DDBB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NaqiDeep,
    onBackground = Color(0xFFDEE8E2),
    surface = NaqiDeep,
    onSurface = Color(0xFFDEE8E2),
    surfaceVariant = Color(0xFF3F4A45),
    onSurfaceVariant = Color(0xFFBEC9C2),
    surfaceContainerLowest = Color(0xFF070E0C),
    surfaceContainerLow = Color(0xFF141D19),
    surfaceContainer = Color(0xFF182420),
    surfaceContainerHigh = Color(0xFF222E2A),
    surfaceContainerHighest = Color(0xFF2D3935),
    outline = Color(0xFF89948D),
    outlineVariant = Color(0xFF3F4A45),
    inverseSurface = Color(0xFFDEE8E2),
    inverseOnSurface = Color(0xFF2B322F),
    inversePrimary = NaqiJade,
    scrim = Color(0xFF000000),
)
