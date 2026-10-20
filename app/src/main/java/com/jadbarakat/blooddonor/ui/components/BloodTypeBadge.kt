package com.jadbarakat.blooddonor.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/**
 * A blood type for display, with a real minus sign ("A−"). Only for the UI:
 * API values and anything sent to the server keep the ASCII "A-".
 */
fun displayBloodType(type: String): String = type.replace('-', '−')

/**
 * A blood type in a rounded square. Solid red when someone needs it ([mine] = false),
 * a red tint for my own blood type or my own request.
 * A null [type] (unknown) shows "?".
 */
@Composable
fun BloodTypeBadge(
    type: String?,
    modifier: Modifier = Modifier,
    mine: Boolean = false,
    size: Dp = 48.dp,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.3f),
        color = if (mine) colors.primaryContainer else colors.primary,
        contentColor = if (mine) MaterialTheme.extendedColors.redText else colors.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = type?.let(::displayBloodType) ?: "?",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    // Scales with the badge: 17 sp at 48 dp, 34 sp for the 96 dp hero.
                    fontSize = (size.value * 0.36f).sp,
                    lineHeight = (size.value * 0.42f).sp,
                ),
                maxLines = 1,
            )
        }
    }
}
