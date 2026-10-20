package com.jadbarakat.blooddonor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = RedContainerLight,
    onPrimaryContainer = OnRedContainerLight,
    secondary = Red,
    onSecondary = Color.White,
    secondaryContainer = RedContainerLight,
    onSecondaryContainer = OnRedContainerLight,
    tertiary = TertiaryTextLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceVariantLight,
    surfaceTint = Color.Transparent,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = Red,
    onError = Color.White,
    errorContainer = RedContainerLight,
    onErrorContainer = OnRedContainerLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = RedDark,
    onPrimary = Color.White,
    primaryContainer = RedContainerDark,
    onPrimaryContainer = OnRedContainerDark,
    secondary = RedTextDark,
    onSecondary = Color.White,
    secondaryContainer = RedContainerDark,
    onSecondaryContainer = OnRedContainerDark,
    tertiary = TertiaryTextDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = SurfaceVariantDark,
    surfaceTint = Color.Transparent,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = RedTextDark,
    onError = Color.White,
    errorContainer = RedContainerDark,
    onErrorContainer = OnRedContainerDark,
)

/**
 * The brand theme. Dynamic colour is deliberately off so the red always shows;
 * light or dark follows the system setting.
 */
@Composable
fun BloodDonorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}

/** The extra semantic colours, e.g. `MaterialTheme.extendedColors.success`. */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
