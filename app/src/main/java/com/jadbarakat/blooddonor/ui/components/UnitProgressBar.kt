package com.jadbarakat.blooddonor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/**
 * One segment per unit: donated (green), on the way (amber), still needed (grey), with a legend.
 * Counts above [units] are capped, donated first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitProgressBar(
    units: Int,
    donated: Int,
    onTheWay: Int,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
) {
    val total = units.coerceAtLeast(1)
    val done = donated.coerceIn(0, total)
    val going = onTheWay.coerceIn(0, total - done)
    val needed = total - done - going
    val extended = MaterialTheme.extendedColors
    val neededColor = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.progress_description, done, going, needed)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        ) {
            repeat(total) { i ->
                val color = when {
                    i < done -> extended.success
                    i < done + going -> extended.waitingBar
                    else -> neededColor
                }
                Box(Modifier.weight(1f).height(8.dp).background(color, CircleShape))
            }
        }
        if (showLegend) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendItem(extended.success, stringResource(R.string.progress_donated, done))
                LegendItem(extended.waitingBar, stringResource(R.string.progress_on_the_way, going))
                LegendItem(neededColor, stringResource(R.string.progress_needed, needed))
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
