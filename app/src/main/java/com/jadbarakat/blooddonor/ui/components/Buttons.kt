package com.jadbarakat.blooddonor.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/**
 * The main action on a screen: solid red, 52 dp tall (64 dp when [large]).
 * While [loading] it shows a spinner and ignores taps.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    large: Boolean = false,
    @DrawableRes icon: Int? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = if (large) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            // Keep the colour while loading, so the button doesn't flash grey.
            disabledContainerColor = if (loading) containerColor else MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = if (loading) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier.defaultMinSize(minHeight = if (large) 64.dp else 52.dp),
    ) {
        ButtonContent(text, icon, loading, if (large) 22.dp else 20.dp, large)
    }
}

/** A secondary action: outlined, same size as [PrimaryButton]. [destructive] makes the text red. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    destructive: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    val content = if (destructive) MaterialTheme.extendedColors.redText
    else MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = content,
        ),
        contentPadding = if (compact) PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        else PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier.defaultMinSize(minHeight = if (compact) 44.dp else 52.dp),
    ) {
        ButtonContent(text, icon, loading, 18.dp, large = false)
    }
}

/** A low-emphasis text action (e.g. "Not now", "Sign out"), at least 44 dp tall. */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    @DrawableRes icon: Int? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
    ) {
        ButtonContent(text, icon, loading = false, iconSize = 18.dp, large = false)
    }
}

@Composable
private fun ButtonContent(text: String, @DrawableRes icon: Int?, loading: Boolean, iconSize: Dp, large: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            // Invisible while loading, so the button keeps its size.
            modifier = if (loading) Modifier.alpha(0f) else Modifier,
        ) {
            icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(iconSize)) }
            Text(
                text,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (loading) {
            CircularProgressIndicator(
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
