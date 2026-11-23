package com.haithamassoli.naqi.desktop.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Ported from apps/android .../ui/theme (Color.kt, Tokens.kt, Type.kt): same brand palette, spacing and
// shapes, so the two apps read as one product. Material 3 Expressive is Android-only; plain MaterialTheme
// with the same colour roles is the closest desktop equivalent.

private val NaqiJade = Color(0xFF1F6E5A)
private val NaqiJadeBright = Color(0xFF55C3A1)
private val NaqiDeep = Color(0xFF0C1512)
private val NaqiInk = Color(0xFF10201C)
private val NaqiPaper = Color(0xFFF5F7F3)

private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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

object NaqiTokens {
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 24.dp
    val space6 = 32.dp
    val space7 = 48.dp
    val radiusButton = 20.dp
    val shapeCard = RoundedCornerShape(24.dp)
    val shapeButton = RoundedCornerShape(radiusButton)
    val shapePill = RoundedCornerShape(999.dp)
    val gutter = 20.dp

    /** A desktop window can be wide; the phone-shaped column stays readable instead of stretching. */
    val maxContentWidth = 640.dp

    fun <T> expressiveSpring() = spring<T>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy)
}

private fun resourceBytes(name: String): ByteArray =
    checkNotNull(NaqiTokens::class.java.getResourceAsStream(name)) { "missing resource $name" }.use { it.readBytes() }

/** The brand icon from branding/, for the window and the About screen. */
val NaqiIconBitmap: ImageBitmap by lazy {
    org.jetbrains.skia.Image.makeFromEncoded(resourceBytes("naqi-icon.png")).toComposeImageBitmap()
}

// Thmanyah Sans, Arabic + Latin, the same three files Android bundles. Loaded from the classpath as bytes so
// the packaged app needs no font installed; there is no 600 weight, so SemiBold resolves to Bold as on Android.
private val ThmanyahSans by lazy {
    FontFamily(
        Font("thmanyah-regular", resourceBytes("thmanyah_sans_regular.otf"), FontWeight.Normal),
        Font("thmanyah-medium", resourceBytes("thmanyah_sans_medium.otf"), FontWeight.Medium),
        Font("thmanyah-bold", resourceBytes("thmanyah_sans_bold.otf"), FontWeight.Bold),
    )
}

private val NaqiTypography by lazy {
    val base = Typography()
    val f = ThmanyahSans
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displayMedium = base.displayMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = f),
        bodyMedium = base.bodyMedium.copy(fontFamily = f),
        bodySmall = base.bodySmall.copy(fontFamily = f),
        labelLarge = base.labelLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
        labelSmall = base.labelSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    )
}

@Composable
fun NaqiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NaqiTypography,
        content = content,
    )
}
