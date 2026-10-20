package com.jadbarakat.blooddonor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/**
 * One option in a grid (blood type, component, sex): outlined when off,
 * red outline and tint when selected. Announced as a radio button.
 */
@Composable
fun ChoiceTile(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    large: Boolean = false,
    minHeight: Dp = if (large) 56.dp else 48.dp,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val colors = MaterialTheme.colorScheme
    val red = MaterialTheme.extendedColors.redText
    Surface(
        shape = shape,
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) red else colors.onSurface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) red else colors.outline),
        modifier = modifier
            .heightIn(min = minHeight)
            .alpha(if (enabled) 1f else 0.5f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text,
                    style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) red else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A fully rounded option, e.g. the deadline pills. */
@Composable
fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ChoiceTile(
        text = text,
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        minHeight = 44.dp,
        shape = CircleShape,
        modifier = modifier,
    )
}

/** A grid of [ChoiceTile]s, [columns] per row. */
@Composable
fun <T> ChoiceGrid(
    options: List<T>,
    columns: Int,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
    subtitle: (@Composable (T) -> String?)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    ChoiceTile(
                        text = label(option),
                        subtitle = subtitle?.invoke(option),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        large = large,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row's tiles the same width as the others.
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** A − value + stepper with 44 dp round buttons. */
@Composable
fun Stepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
    decrementLabel: String,
    incrementLabel: String,
    modifier: Modifier = Modifier,
    valueLabel: String = value.toString(),
) {
    AppCard(modifier = modifier, contentPadding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(R.drawable.ic_remove, decrementLabel, onDecrement, canDecrement)
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StepButton(R.drawable.ic_add, incrementLabel, onIncrement, canIncrement)
        }
    }
}

@Composable
private fun StepButton(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.extendedColors.redText else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp))
        }
    }
}
