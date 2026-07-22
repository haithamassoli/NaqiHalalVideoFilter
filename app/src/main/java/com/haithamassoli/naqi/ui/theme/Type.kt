@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.haithamassoli.naqi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.haithamassoli.naqi.R

// Readex Pro — one variable family (Arabic + Latin), bundled for offline use.
// wght axis drives real weight interpolation; no synthetic bolding.
private fun readex(weight: FontWeight) = Font(
    resId = R.font.readex_pro,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val ReadexPro = FontFamily(
    readex(FontWeight.Light),
    readex(FontWeight.Normal),
    readex(FontWeight.Medium),
    readex(FontWeight.SemiBold),
    readex(FontWeight.Bold),
)

// Type scale: calm, generous line-height, tight tracking on large sizes.
private val base = Typography()
val Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displayMedium = base.displayMedium.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    displaySmall = base.displaySmall.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = ReadexPro, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = ReadexPro, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = ReadexPro, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = ReadexPro),
    bodyMedium = base.bodyMedium.copy(fontFamily = ReadexPro),
    bodySmall = base.bodySmall.copy(fontFamily = ReadexPro),
    labelLarge = base.labelLarge.copy(fontFamily = ReadexPro, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = ReadexPro, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    labelSmall = base.labelSmall.copy(fontFamily = ReadexPro, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)
