package com.jadbarakat.blooddonor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// extraSmall/small: inputs and buttons; medium: dialogs' inner pieces; large/extraLarge: cards and dialogs.
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Spacing shared by every screen. */
object Dimens {
    val ScreenPadding = 20.dp
    val MinTouchTarget = 44.dp
    val CardPadding = 16.dp
}
