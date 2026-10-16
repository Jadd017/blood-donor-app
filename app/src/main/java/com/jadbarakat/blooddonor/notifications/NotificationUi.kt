package com.jadbarakat.blooddonor.notifications

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Shown once after the profile is saved, before the Android 13+ system prompt. */
@Composable
fun NotificationExplainerScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    // Whatever the answer, we're done: a denial is handled by the hint on home.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.notifications_explainer_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.notifications_explainer_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.notifications_explainer_allow)) }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.notifications_explainer_not_now))
        }
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

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.notifications_off_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.notifications_off_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                if (settings.needsRuntimePermission && !settings.askedForPermission) {
                    settings.askedForPermission = true
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(settings.settingsIntent())
                }
            }) { Text(stringResource(R.string.notifications_off_button)) }
        }
    }
}
