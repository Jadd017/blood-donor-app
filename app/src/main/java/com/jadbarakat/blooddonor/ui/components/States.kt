package com.jadbarakat.blooddonor.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/** The colour family of a message or notice. */
enum class Tone { ERROR, WARNING, SUCCESS, INFO }

private data class ToneColors(val container: Color, val content: Color, @DrawableRes val icon: Int)

@Composable
private fun toneColors(tone: Tone): ToneColors {
    val colors = MaterialTheme.colorScheme
    val extended = MaterialTheme.extendedColors
    return when (tone) {
        Tone.ERROR -> ToneColors(colors.primaryContainer, colors.onPrimaryContainer, R.drawable.ic_error)
        Tone.WARNING -> ToneColors(extended.waitingContainer, extended.waiting, R.drawable.ic_info)
        Tone.SUCCESS -> ToneColors(extended.successContainer, extended.success, R.drawable.ic_check)
        Tone.INFO -> ToneColors(colors.surfaceVariant, colors.onSurfaceVariant, R.drawable.ic_info)
    }
}

/** A spinner in the middle of the available space. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
    }
}

/** A small inline spinner for a section that is loading. */
@Composable
fun SectionLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

/** Icon, title, message and (optional) a retry button, centred. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(
            R.drawable.ic_error,
            size = 64.dp,
            iconSize = 30.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.extendedColors.redText,
        )
        title?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onRetry?.let {
            PrimaryButton(stringResource(R.string.button_retry), onClick = it, modifier = Modifier.fillMaxWidth())
        }
        extra?.invoke()
    }
}

/** Shown where a list has nothing to show yet. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    AppCard(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A one-line result or error under a form or an action, with an icon. */
@Composable
fun InlineMessage(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val colors = toneColors(tone)
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(painterResource(colors.icon), contentDescription = null, tint = colors.content, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (tone == Tone.INFO) MaterialTheme.colorScheme.onSurfaceVariant else colors.content)
    }
}

/**
 * A coloured card with a title, an optional body and optional actions,
 * e.g. "Alerts are paused" or "This request was hidden".
 */
@Composable
fun NoticeCard(
    title: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    body: String? = null,
    @DrawableRes icon: Int? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = toneColors(tone)
    AppCard(modifier = modifier, color = colors.container, border = null, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon ?: colors.icon), contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.content)
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        actions?.invoke()
    }
}
