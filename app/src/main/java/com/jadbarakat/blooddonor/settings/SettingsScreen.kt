package com.jadbarakat.blooddonor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.BlockedUser
import com.jadbarakat.blooddonor.data.Profile
import com.jadbarakat.blooddonor.requests.formatDateTime
import com.jadbarakat.blooddonor.ui.formatWhen
import com.jadbarakat.blooddonor.ui.postingSuspendedMessage
import com.jadbarakat.blooddonor.ui.resolve
import java.time.Instant

/** Blocked users, notification and posting status, and account deletion. */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onRetry: () -> Unit,
    onUnblock: (userId: Long) -> Unit,
    onDeleteAccount: () -> Unit,
    onAccountDeleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(uiState.accountDeleted) { if (uiState.accountDeleted) onAccountDeleted() }
    var showDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        val profile = uiState.profile
        when {
            uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            uiState.loadError != null -> {
                Text(uiState.loadError.resolve(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.button_retry)) }
            }
            profile != null -> {
                SectionTitle(stringResource(R.string.settings_notifications_section))
                NotificationStatus(profile)
                PostingStatus(profile)

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SectionTitle(stringResource(R.string.settings_blocked_section))
                BlockedUsers(uiState, onUnblock)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SectionTitle(stringResource(R.string.settings_account_section))
        Text(stringResource(R.string.settings_delete_explanation), style = MaterialTheme.typography.bodyMedium)
        uiState.deleteError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { showDelete = true },
            enabled = !uiState.isDeleting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isDeleting) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
            } else {
                Text(stringResource(R.string.settings_delete_button))
            }
        }

        TextButton(onClick = onBack, enabled = !uiState.isDeleting, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.request_button_back))
        }
    }

    if (showDelete) {
        DeleteAccountDialog(
            onConfirm = {
                showDelete = false
                onDeleteAccount()
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NotificationStatus(profile: Profile) {
    val pausedUntil = profile.notificationsPausedUntil?.takeIf { isFuture(it) }
    when {
        pausedUntil != null -> WarningCard(
            title = stringResource(R.string.settings_notifications_paused, formatWhen(pausedUntil) ?: pausedUntil),
            body = stringResource(R.string.settings_notifications_paused_no_shows),
        )
        !profile.isDonor -> Text(stringResource(R.string.settings_notifications_off), style = MaterialTheme.typography.bodyMedium)
        else -> Text(stringResource(R.string.settings_notifications_on), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PostingStatus(profile: Profile) {
    val until = when {
        profile.postingSuspendedPermanent -> "permanent"
        else -> profile.postingSuspendedUntil?.takeIf { isFuture(it) }
    } ?: return
    WarningCard(title = stringResource(R.string.settings_posting_suspended_title), body = postingSuspendedMessage(until).resolve())
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BlockedUsers(uiState: SettingsUiState, onUnblock: (Long) -> Unit) {
    uiState.blocksError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
    if (uiState.blocks.isEmpty()) {
        Text(
            stringResource(R.string.settings_blocked_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    uiState.blocks.forEach { blocked ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(blockedLabel(blocked), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onUnblock(blocked.userId) }, enabled = blocked.userId !in uiState.unblocking) {
                Text(stringResource(R.string.settings_unblock))
            }
        }
    }
}

/** A helper by name; a requester (whose identity I never saw) by the request's hospital and date. */
@Composable
private fun blockedLabel(blocked: BlockedUser): String = when {
    blocked.fullName != null -> blocked.fullName
    blocked.requestHospitalName != null -> stringResource(
        R.string.settings_blocked_requester,
        blocked.requestHospitalName,
        formatDateTime(blocked.requestCreatedAt) ?: blocked.requestCreatedAt.orEmpty(),
    )
    else -> stringResource(R.string.settings_blocked_someone)
}

/** A strong confirmation: the user must type [DELETE_CONFIRMATION_WORD]. */
@Composable
private fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_delete_confirm_message))
                Text(stringResource(R.string.settings_delete_confirm_type, DELETE_CONFIRMATION_WORD))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == DELETE_CONFIRMATION_WORD) {
                Text(stringResource(R.string.settings_delete_confirm_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.request_confirm_back)) } },
    )
}

private fun isFuture(iso: String): Boolean =
    runCatching { Instant.parse(iso).isAfter(Instant.now()) }.getOrDefault(false)
