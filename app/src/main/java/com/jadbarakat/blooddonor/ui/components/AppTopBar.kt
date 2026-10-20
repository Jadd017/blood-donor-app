package com.jadbarakat.blooddonor.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.theme.Dimens
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/** One entry of the top bar's overflow menu. */
data class MenuAction(
    val label: String,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * A rounded back button, the title, and an optional overflow menu.
 * [onBack] null hides the back button.
 */
@Composable
fun AppTopBar(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    @DrawableRes backIcon: Int = R.drawable.ic_arrow_back,
    menu: List<MenuAction> = emptyList(),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
    ) {
        if (onBack != null) {
            RoundIconButton(
                icon = backIcon,
                contentDescription = stringResource(R.string.request_button_back),
                onClick = onBack,
                enabled = backEnabled,
            )
        }
        Text(
            text = title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(Modifier.size(0.dp))
    }
}

/** A 44 dp outlined, rounded-square icon button. */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.size(Dimens.MinTouchTarget),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun OverflowMenu(actions: List<MenuAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(
            icon = R.drawable.ic_more_vert,
            contentDescription = stringResource(R.string.menu_more),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 2.dp,
        ) {
            actions.forEach { action ->
                val color = if (action.destructive) MaterialTheme.extendedColors.redText else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(action.label, style = MaterialTheme.typography.bodyLarge, color = color) },
                    leadingIcon = { Icon(painterResource(action.icon), contentDescription = null, tint = color) },
                    enabled = action.enabled,
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                )
            }
        }
    }
}
