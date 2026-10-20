package com.jadbarakat.blooddonor.notifications

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.components.AppCard
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.IconTile
import com.jadbarakat.blooddonor.ui.components.NoticeCard
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.SecondaryButton
import com.jadbarakat.blooddonor.ui.components.TertiaryButton
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.theme.extendedColors

/** Shown once after the profile is saved, before the Android 13+ system prompt. */
@Composable
fun NotificationExplainerScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    // Whatever the answer, we're done: a denial is handled by the hint on home.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    AppScreen(modifier = modifier, centered = true) {
        IconTile(
            R.drawable.ic_notifications,
            size = 72.dp,
            iconSize = 34.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.extendedColors.redText,
            shape = RoundedCornerShape(22.dp),
        )
        Text(
            stringResource(R.string.notifications_explainer_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.notifications_explainer_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AppCard {
            ExplainerPoint(R.drawable.ic_drop, stringResource(R.string.notifications_explainer_point_match))
            ExplainerPoint(R.drawable.ic_place, stringResource(R.string.notifications_explainer_point_nearby))
            ExplainerPoint(R.drawable.ic_lock, stringResource(R.string.notifications_explainer_point_private))
        }
        PrimaryButton(
            text = stringResource(R.string.notifications_explainer_allow),
            onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            icon = R.drawable.ic_notifications,
            modifier = Modifier.fillMaxWidth(),
        )
        TertiaryButton(
            text = stringResource(R.string.notifications_explainer_not_now),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExplainerPoint(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(
            icon,
            size = 36.dp,
            iconSize = 18.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.extendedColors.redText,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A card on home while notifications are off, so donors know they won't hear about requests.
 * Its button asks for the permission if we never did, otherwise opens the system settings.
 */
@Composable
fun NotificationsOffHint(settings: NotificationSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.areEnabled()) }
    // Re-check whenever we come back, e.g. from the settings screen.
    LifecycleResumeEffect(settings) {
        enabled = settings.areEnabled()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = settings.areEnabled()
    }
    if (enabled) return

    NoticeCard(
        title = stringResource(R.string.notifications_off_title),
        body = stringResource(R.string.notifications_off_body),
        tone = Tone.WARNING,
        icon = R.drawable.ic_notifications,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = stringResource(R.string.notifications_off_button),
                onClick = {
                    if (settings.needsRuntimePermission && !settings.askedForPermission) {
                        settings.askedForPermission = true
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(settings.settingsIntent())
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
