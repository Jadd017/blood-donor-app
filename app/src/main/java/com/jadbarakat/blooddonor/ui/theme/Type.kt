package com.jadbarakat.blooddonor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jadbarakat.blooddonor.R

val Manrope = FontFamily(
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/** Bundled for the Google sign-in button only, as Google's branding guidelines require Roboto Medium. */
val RobotoMedium = FontFamily(Font(R.font.roboto_medium, FontWeight.Medium))

private fun manrope(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

// The design's scale, mapped onto Material's slots so every component picks it up:
// display 30/36 800, title 22/28 800, section 17/24 800, body strong 15/22 700,
// body 15/22 600, caption 13/18 600, chip 12 700.
private val Display = manrope(30, 36, FontWeight.ExtraBold)
private val Title = manrope(22, 28, FontWeight.ExtraBold)
private val Section = manrope(17, 24, FontWeight.ExtraBold)
private val BodyStrong = manrope(15, 22, FontWeight.Bold)
private val Body = manrope(15, 22, FontWeight.SemiBold)
private val Caption = manrope(13, 18, FontWeight.SemiBold)
private val Chip = manrope(12, 16, FontWeight.Bold)

val Typography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,
    headlineLarge = Display,
    headlineMedium = Title,
    headlineSmall = Title,
    titleLarge = Title,
    titleMedium = Section,
    titleSmall = BodyStrong,
    bodyLarge = Body,
    bodyMedium = Body,
    bodySmall = Caption,
    labelLarge = BodyStrong,
    labelMedium = Chip,
    labelSmall = Chip,
)
