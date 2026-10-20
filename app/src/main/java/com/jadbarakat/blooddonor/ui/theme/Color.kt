package com.jadbarakat.blooddonor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand red and the light palette.
internal val Red = Color(0xFFB3122E)
internal val RedContainerLight = Color(0xFFFBE8EB)
internal val OnRedContainerLight = Color(0xFF7A0C1F)
internal val BackgroundLight = Color(0xFFF7F4F2)
internal val SurfaceLight = Color(0xFFFFFFFF)
internal val SurfaceVariantLight = Color(0xFFF1ECE9)
internal val OutlineLight = Color(0xFFE6DEDA)
internal val OnSurfaceLight = Color(0xFF1C1718)
internal val OnSurfaceVariantLight = Color(0xFF574D4F)
internal val TertiaryTextLight = Color(0xFF6E6365)

// Dark palette. The filled red is a little lighter, and red text/icons use a pink-red that reads on dark.
internal val RedDark = Color(0xFFC8243E)
internal val RedTextDark = Color(0xFFFF7A8A)
internal val RedContainerDark = Color(0xFF3D1A21)
internal val OnRedContainerDark = Color(0xFFFFB8C2)
internal val BackgroundDark = Color(0xFF141112)
internal val SurfaceDark = Color(0xFF1F1A1B)
internal val SurfaceVariantDark = Color(0xFF2A2324)
internal val OutlineDark = Color(0xFF332B2D)
internal val OnSurfaceDark = Color(0xFFF4EFEF)
internal val OnSurfaceVariantDark = Color(0xFFCFC5C6)
internal val TertiaryTextDark = Color(0xFFAEA2A4)

/** Colours Material's scheme has no slot for: success and waiting states, tertiary text, red text. */
@Immutable
data class ExtendedColors(
    /** Red for text and icons on surfaces (the filled red is too dark on a dark background). */
    val redText: Color,
    val tertiaryText: Color,
    /** Donated, fulfilled, "You're going". */
    val success: Color,
    val successContainer: Color,
    /** Time left, on the way. */
    val waiting: Color,
    val waitingContainer: Color,
    val waitingBar: Color,
)

internal val LightExtendedColors = ExtendedColors(
    redText = Red,
    tertiaryText = TertiaryTextLight,
    success = Color(0xFF1E6B45),
    successContainer = Color(0xFFE2F1E8),
    waiting = Color(0xFF8A4B00),
    waitingContainer = Color(0xFFFCEFD9),
    waitingBar = Color(0xFFE0A23A),
)

internal val DarkExtendedColors = ExtendedColors(
    redText = RedTextDark,
    tertiaryText = TertiaryTextDark,
    success = Color(0xFF6FD49C),
    successContainer = Color(0xFF173326),
    waiting = Color(0xFFF5B65A),
    waitingContainer = Color(0xFF3A2A12),
    waitingBar = Color(0xFFC98A2E),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
