package com.jadbarakat.blooddonor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.BlockedUser
import com.jadbarakat.blooddonor.data.Profile
import com.jadbarakat.blooddonor.requests.formatDateTime
import com.jadbarakat.blooddonor.ui.components.AppCard
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.AppTopBar
import com.jadbarakat.blooddonor.ui.components.EmptyState
import com.jadbarakat.blooddonor.ui.components.ErrorState
import com.jadbarakat.blooddonor.ui.components.InfoRow
import com.jadbarakat.blooddonor.ui.components.InlineMessage
import com.jadbarakat.blooddonor.ui.components.NoticeCard
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.SecondaryButton
import com.jadbarakat.blooddonor.ui.components.SectionHeader
import com.jadbarakat.blooddonor.ui.components.SectionLoading
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.components.appTextFieldColors
import com.jadbarakat.blooddonor.ui.formatWhen
import com.jadbarakat.blooddonor.ui.postingSuspendedMessage
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.extendedColors
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

    AppScreen(
        modifier = modifier,
        topBar = {
            AppTopBar(title = stringResource(R.string.settings_title), onBack = onBack, backEnabled = !uiState.isDeleting)
        },
    ) {
        val profile = uiState.profile
        when {
            uiState.isLoading -> SectionLoading()
            uiState.loadError != null -> ErrorState(message = uiState.loadError.resolve(), onRetry = onRetry)
            profile != null -> {
                SectionHeader(stringResource(R.string.settings_notifications_section))
                NotificationStatus(profile)
                PostingStatus(profile)

                SectionHeader(stringResource(R.string.settings_blocked_section))
                BlockedUsers(uiState, onUnblock)
            }
        }

        SectionHeader(stringResource(R.string.settings_account_section))
        AppCard {
            InfoRow(
                icon = R.drawable.ic_delete,
                title = stringResource(R.string.settings_delete_card_title),
                subtitle = stringResource(R.string.settings_delete_explanation),
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.extendedColors.redText,
            )
            uiState.deleteError?.let { InlineMessage(it.resolve(), Tone.ERROR) }
            PrimaryButton(
                text = stringResource(R.string.settings_delete_button),
                onClick = { showDelete = true },
                loading = uiState.isDeleting,
                icon = R.drawable.ic_delete,
                modifier = Modifier.fillMaxWidth(),
            )
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
private fun NotificationStatus(profile: Profile) {
    val pausedUntil = profile.notificationsPausedUntil?.takeIf { isFuture(it) }
    if (pausedUntil != null) {
        NoticeCard(
            title = stringResource(R.string.settings_notifications_paused, formatWhen(pausedUntil) ?: pausedUntil),
            body = stringResource(R.string.settings_notifications_paused_no_shows),
            tone = Tone.WARNING,
            icon = R.drawable.ic_notifications,
        )
    } else {
        AppCard(contentPadding = 12.dp) {
            InfoRow(
                icon = R.drawable.ic_notifications,
                title = stringResource(if (profile.isDonor) R.string.home_notifications_on else R.string.home_notifications_off),
                subtitle = stringResource(
                    if (profile.isDonor) R.string.settings_notifications_on else R.string.settings_notifications_off,
                ),
                iconContainer = if (profile.isDonor) MaterialTheme.extendedColors.successContainer else MaterialTheme.colorScheme.surfaceVariant,
                iconTint = if (profile.isDonor) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PostingStatus(profile: Profile) {
    val until = when {
        profile.postingSuspendedPermanent -> "permanent"
        else -> profile.postingSuspendedUntil?.takeIf { isFuture(it) }
    } ?: return
    NoticeCard(
        title = stringResource(R.string.settings_posting_suspended_title),
        body = postingSuspendedMessage(until).resolve(),
        tone = Tone.ERROR,
        icon = R.drawable.ic_block,
    )
}

@Composable
private fun BlockedUsers(uiState: SettingsUiState, onUnblock: (Long) -> Unit) {
    uiState.blocksError?.let { InlineMessage(it.resolve(), Tone.ERROR) }
    if (uiState.blocks.isEmpty()) {
        EmptyState(icon = R.drawable.ic_shield, title = stringResource(R.string.settings_blocked_empty))
        return
    }
    AppCard(spacing = 4.dp, contentPadding = 12.dp) {
        uiState.blocks.forEachIndexed { index, blocked ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            InfoRow(
                icon = if (blocked.fullName != null) R.drawable.ic_person else R.drawable.ic_hospital,
                title = blockedLabel(blocked),
                action = {
                    SecondaryButton(
                        text = stringResource(R.string.settings_unblock),
                        onClick = { onUnblock(blocked.userId) },
                        enabled = blocked.userId !in uiState.unblocking,
                        compact = true,
                    )
                },
            )
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
                    colors = appTextFieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == DELETE_CONFIRMATION_WORD) {
                Text(stringResource(R.string.settings_delete_confirm_button), color = MaterialTheme.extendedColors.redText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.request_confirm_back)) } },
    )
}

private fun isFuture(iso: String): Boolean =
    runCatching { Instant.parse(iso).isAfter(Instant.now()) }.getOrDefault(false)
