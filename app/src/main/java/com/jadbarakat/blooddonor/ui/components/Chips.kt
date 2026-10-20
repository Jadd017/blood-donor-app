package com.jadbarakat.blooddonor.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/** A fully rounded label. */
@Composable
fun Pill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        ) {
            icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(14.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** What a [StatusChip] can say. */
enum class ChipStatus { OPEN, ON_THE_WAY, DONATED, FULFILLED, EXPIRED, CANCELLED }

@Composable
fun StatusChip(status: ChipStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = MaterialTheme.extendedColors
    val (container, content, label) = when (status) {
        ChipStatus.OPEN -> Triple(colors.primaryContainer, extended.redText, R.string.request_status_open)
        ChipStatus.ON_THE_WAY -> Triple(extended.waitingContainer, extended.waiting, R.string.chip_on_the_way)
        ChipStatus.DONATED -> Triple(extended.successContainer, extended.success, R.string.chip_donated)
        ChipStatus.FULFILLED -> Triple(extended.successContainer, extended.success, R.string.request_status_fulfilled)
        ChipStatus.EXPIRED -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, R.string.request_status_expired)
        ChipStatus.CANCELLED -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, R.string.request_status_cancelled)
    }
    Pill(stringResource(label), container, content, modifier)
}

/** "5 h 20 min left", in the waiting colours with a clock. */
@Composable
fun TimeLeftChip(text: String, modifier: Modifier = Modifier) {
    val extended = MaterialTheme.extendedColors
    Pill(text, extended.waitingContainer, extended.waiting, modifier, icon = R.drawable.ic_schedule)
}

/** A neutral fact, e.g. "2 units" or "Red cells". */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    Pill(text, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, modifier, icon)
}

/** "You're going" and other good news, in the success colours. */
@Composable
fun SuccessChip(text: String, modifier: Modifier = Modifier) {
    val extended = MaterialTheme.extendedColors
    Pill(text, extended.successContainer, extended.success, modifier, icon = R.drawable.ic_check)
}
