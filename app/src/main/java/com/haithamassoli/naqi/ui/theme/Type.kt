package com.haithamassoli.naqi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.haithamassoli.naqi.R

// Thmanyah Sans — Arabic + Latin (incl. both digit sets, ~ and —), bundled for offline use.
// ponytail: only the three weights the scale asks for are bundled; the family also
// ships Light/Black — drop the .otf in res/font and add a line here if a screen needs one.
// No 600 weight exists, so the SemiBold slots below resolve to Bold (700).
val ThmanyahSans = FontFamily(
    Font(R.font.thmanyah_sans_regular, FontWeight.Normal),
    Font(R.font.thmanyah_sans_medium, FontWeight.Medium),
    Font(R.font.thmanyah_sans_bold, FontWeight.Bold),
)

// Type scale: calm, generous line-height, tight tracking on large sizes.
private val base = Typography()
val Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displayMedium = base.displayMedium.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    displaySmall = base.displaySmall.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = ThmanyahSans),
    bodyMedium = base.bodyMedium.copy(fontFamily = ThmanyahSans),
    bodySmall = base.bodySmall.copy(fontFamily = ThmanyahSans),
    labelLarge = base.labelLarge.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    labelSmall = base.labelSmall.copy(fontFamily = ThmanyahSans, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)
