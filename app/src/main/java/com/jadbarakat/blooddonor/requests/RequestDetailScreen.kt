package com.jadbarakat.blooddonor.requests

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.Helper
import com.jadbarakat.blooddonor.data.HelperOutcome
import com.jadbarakat.blooddonor.data.HelperState
import com.jadbarakat.blooddonor.data.PledgeStatus
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.components.AppCard
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.AppTopBar
import com.jadbarakat.blooddonor.ui.components.BloodTypeBadge
import com.jadbarakat.blooddonor.ui.components.ChipStatus
import com.jadbarakat.blooddonor.ui.components.EmptyState
import com.jadbarakat.blooddonor.ui.components.ErrorState
import com.jadbarakat.blooddonor.ui.components.InfoChip
import com.jadbarakat.blooddonor.ui.components.InfoRow
import com.jadbarakat.blooddonor.ui.components.InlineMessage
import com.jadbarakat.blooddonor.ui.components.NoticeCard
import com.jadbarakat.blooddonor.ui.components.Pill
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.RequestCard
import com.jadbarakat.blooddonor.ui.components.SecondaryButton
import com.jadbarakat.blooddonor.ui.components.SectionHeader
import com.jadbarakat.blooddonor.ui.components.SectionLoading
import com.jadbarakat.blooddonor.ui.components.StatusChip
import com.jadbarakat.blooddonor.ui.components.TertiaryButton
import com.jadbarakat.blooddonor.ui.components.TimeLeftChip
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.components.UnitProgressBar
import com.jadbarakat.blooddonor.ui.components.displayBloodType
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.extendedColors
import java.time.Instant

private enum class PendingAction { FULFILL, CANCEL }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestDetailScreen(
    uiState: RequestsUiState,
    onFulfill: () -> Unit,
    onCancel: () -> Unit,
    onConfirmHelper: (pledgeId: Long, outcome: String) -> Unit,
    onBlockHelper: (donorId: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = uiState.detail
    val title = stringResource(R.string.request_detail_screen_title)
    if (request == null) {
        // Opened by id (e.g. from a notification) and still loading, or loading failed.
        AppScreen(modifier = modifier, topBar = { AppTopBar(title = title, onBack = onBack) }) {
            uiState.detailError?.let { ErrorState(message = it.resolve()) }
                ?: SectionLoading(Modifier.padding(top = 48.dp))
        }
        return
    }
    val now = rememberNow()
    val status = request.effectiveStatus(now)
    var pending by rememberSaveable { mutableStateOf<PendingAction?>(null) }
    // The helper being confirmed, and as what (HelperOutcome).
    var pendingHelper by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingOutcome by rememberSaveable { mutableStateOf<String?>(null) }
    // The helper (donor id) about to be blocked.
    var pendingBlock by rememberSaveable { mutableStateOf<Long?>(null) }

    AppScreen(
        modifier = modifier,
        topBar = { AppTopBar(title = title, onBack = onBack, backEnabled = !uiState.isActing) },
    ) {
        if (request.hidden) {
            NoticeCard(
                title = stringResource(R.string.request_hidden_title),
                body = stringResource(R.string.request_hidden_message),
                tone = Tone.ERROR,
            )
        }

        // Summary: what, where, how urgent, and how many donors are covered.
        AppCard(spacing = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BloodTypeBadge(request.bloodType, mine = true, size = 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.donor_request_title,
                            displayBloodType(request.bloodType),
                            stringResource(componentLabel(request.component)),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        request.hospitalName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(requestChipStatus(status))
                timeLeftText(request, now)?.let { TimeLeftChip(it) }
                InfoChip(pluralStringResource(R.plurals.units_count, request.unitsNeeded, request.unitsNeeded))
            }
            request.committedCount?.let { committed ->
                val donated = request.confirmedDonatedCount ?: 0
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                UnitProgressBar(
                    units = request.unitsNeeded,
                    donated = donated,
                    onTheWay = request.onTheWayCount ?: (committed - donated),
                )
                if (committed == 0 && status == RequestStatus.OPEN) {
                    Text(
                        stringResource(R.string.request_matching_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AppCard(spacing = 12.dp) {
            InfoRow(
                icon = R.drawable.ic_hospital,
                title = request.hospitalName,
                subtitle = stringResource(R.string.request_hospital_label),
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.extendedColors.redText,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            DetailRow(stringResource(R.string.request_needed_by_label), formatDateTime(request.neededBy) ?: request.neededBy)
            DetailRow(stringResource(R.string.request_created_label), formatDateTime(request.createdAt) ?: request.createdAt)
            if (status != RequestStatus.OPEN) {
                formatDateTime(request.closedAt ?: request.neededBy.takeIf { status == RequestStatus.EXPIRED })?.let {
                    DetailRow(stringResource(R.string.request_closed_label), it)
                }
            }
        }
        request.note?.let { NoteCard(it, title = stringResource(R.string.request_note_yours)) }

        if (request.helpers.isNotEmpty()) {
            HelpersList(
                helpers = request.helpers,
                blocked = uiState.blockedHelperIds,
                enabled = !uiState.isActing,
                onConfirm = { pledgeId, outcome ->
                    pendingHelper = pledgeId
                    pendingOutcome = outcome
                },
                onBlock = { pendingBlock = it },
            )
        }

        uiState.detailError?.let { InlineMessage(it.resolve(), Tone.ERROR) }
        uiState.detailInfo?.let { InlineMessage(it.resolve(), Tone.SUCCESS) }

        if (status == RequestStatus.OPEN) {
            PrimaryButton(
                text = stringResource(R.string.request_button_fulfill),
                onClick = { pending = PendingAction.FULFILL },
                loading = uiState.isActing,
                icon = R.drawable.ic_check,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.request_button_cancel),
                onClick = { pending = PendingAction.CANCEL },
                enabled = !uiState.isActing,
                destructive = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val confirming = request.helpers.firstOrNull { it.pledgeId == pendingHelper }
    val outcome = pendingOutcome
    if (confirming != null && outcome != null) {
        val firstName = confirming.fullName.trim().substringBefore(' ')
        val donated = outcome == HelperOutcome.DONATED
        val dismiss = {
            pendingHelper = null
            pendingOutcome = null
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(
                    stringResource(
                        if (donated) R.string.request_helper_donated_confirm_title else R.string.request_helper_no_show_confirm_title,
                        firstName,
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (donated) R.string.request_helper_donated_confirm_message else R.string.request_helper_no_show_confirm_message,
                        firstName,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    onConfirmHelper(confirming.pledgeId, outcome)
                }) {
                    Text(stringResource(if (donated) R.string.request_helper_button_donated else R.string.request_helper_button_no_show))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.request_confirm_back)) }
            },
        )
    }

    request.helpers.firstOrNull { it.donorId != null && it.donorId == pendingBlock }?.let { helper ->
        val firstName = helper.fullName.trim().substringBefore(' ')
        AlertDialog(
            onDismissRequest = { pendingBlock = null },
            title = { Text(stringResource(R.string.block_helper_confirm_title, firstName)) },
            text = { Text(stringResource(R.string.block_helper_confirm_message, firstName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBlock = null
                    helper.donorId?.let(onBlockHelper)
                }) { Text(stringResource(R.string.block_helper_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlock = null }) { Text(stringResource(R.string.request_confirm_back)) }
            },
        )
    }

    pending?.let { action ->
        val (dialogTitle, message, confirm) = when (action) {
            PendingAction.FULFILL -> Triple(
                R.string.request_fulfill_confirm_title,
                R.string.request_fulfill_confirm_message,
                R.string.request_button_fulfill,
            )
            PendingAction.CANCEL -> Triple(
                R.string.request_cancel_confirm_title,
                R.string.request_cancel_confirm_message,
                R.string.request_button_cancel,
            )
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(dialogTitle)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    if (action == PendingAction.FULFILL) onFulfill() else onCancel()
                }) { Text(stringResource(confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.request_confirm_back)) }
            },
        )
    }
}

/**
 * The donors who committed, each with a Call button and, until confirmed, "Donated" and "Didn't
 * come" buttons. Only the owner gets this data.
 */
@Composable
private fun HelpersList(
    helpers: List<Helper>,
    blocked: Set<Long>,
    enabled: Boolean,
    onConfirm: (pledgeId: Long, outcome: String) -> Unit,
    onBlock: (donorId: Long) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.request_helpers_title))
        if (helpers.any { it.canConfirm }) {
            InlineMessage(stringResource(R.string.request_helpers_confirm_hint), Tone.WARNING)
        }
        helpers.forEach { helper ->
            AppCard(spacing = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InitialAvatar(helper.fullName)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(helper.fullName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        helperSubtitle(helper)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // No phone once the donor deleted their account.
                    if (helper.phone.isNotBlank()) {
                        SecondaryButton(
                            text = stringResource(R.string.request_helper_call),
                            onClick = { dial(context, helper.phone) },
                            icon = R.drawable.ic_phone,
                            compact = true,
                        )
                    }
                }
                HelperStateChip(helper)
                if (helper.canConfirm) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = stringResource(R.string.request_helper_button_donated),
                            onClick = { onConfirm(helper.pledgeId, HelperOutcome.DONATED) },
                            enabled = enabled,
                            icon = R.drawable.ic_check,
                            containerColor = MaterialTheme.extendedColors.success,
                            contentColor = MaterialTheme.extendedColors.successContainer,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = stringResource(R.string.request_helper_button_no_show),
                            onClick = { onConfirm(helper.pledgeId, HelperOutcome.NO_SHOW) },
                            enabled = enabled,
                            icon = R.drawable.ic_close,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                helper.donorId?.let { donorId ->
                    val isBlocked = donorId in blocked
                    TertiaryButton(
                        text = stringResource(if (isBlocked) R.string.block_helper_blocked else R.string.block_helper_button),
                        onClick = { onBlock(donorId) },
                        enabled = enabled && !isBlocked,
                        icon = R.drawable.ic_block,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        PrivacyLine(stringResource(R.string.request_helpers_hint))
    }
}

/** A round tile with the helper's initial. */
@Composable
private fun InitialAvatar(name: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.extendedColors.redText,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** My side of this helper: donated, didn't come, on the way, or waiting for me. */
@Composable
private fun HelperStateChip(helper: Helper) {
    when (helper.state) {
        HelperState.DONATED -> StatusChip(ChipStatus.DONATED)
        HelperState.NO_SHOW -> InfoChip(stringResource(R.string.request_helper_state_no_show), icon = R.drawable.ic_close)
        HelperState.AWAITING_CONFIRMATION -> Pill(
            stringResource(R.string.request_helper_state_awaiting),
            MaterialTheme.extendedColors.waitingContainer,
            MaterialTheme.extendedColors.waiting,
            icon = R.drawable.ic_schedule,
        )
        else -> StatusChip(ChipStatus.ON_THE_WAY)
    }
}

/** The donor's blood type and, while I can still confirm, their own answer. */
@Composable
private fun helperSubtitle(helper: Helper): String? {
    val says = if (!helper.canConfirm) null else when (helper.status) {
        PledgeStatus.DONATED -> stringResource(R.string.request_helper_says_donated)
        PledgeStatus.NOT_DONATED -> stringResource(R.string.request_helper_says_not_donated)
        else -> null
    }
    return listOfNotNull(helper.bloodType?.let(::displayBloodType), says).joinToString(" · ").ifEmpty { null }
}

/** Opens the dialer with [phone] filled in; ACTION_DIAL needs no call permission. */
private fun dial(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
    } catch (_: ActivityNotFoundException) {
        // No dialer (e.g. a tablet); nothing useful to do.
    }
}

/** The "My requests" part of home. */
@Composable
fun MyRequestsSection(
    uiState: RequestsUiState,
    onOpen: (BloodRequest) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.home_my_requests))
        when {
            uiState.myRequests.isEmpty() && uiState.isLoadingList -> SectionLoading()

            uiState.myRequests.isEmpty() && uiState.listError != null -> {
                InlineMessage(uiState.listError.resolve(), Tone.ERROR)
                SecondaryButton(stringResource(R.string.button_retry), onClick = onRetry, compact = true)
            }

            uiState.myRequests.isEmpty() -> EmptyState(
                icon = R.drawable.ic_inbox,
                title = stringResource(R.string.home_my_requests_empty),
                body = stringResource(R.string.home_my_requests_empty_body),
            )

            else -> uiState.myRequests.forEach { request -> MyRequestCard(request, now, onClick = { onOpen(request) }) }
        }
    }
}

@Composable
private fun MyRequestCard(request: BloodRequest, now: Instant, onClick: () -> Unit) {
    val status = request.effectiveStatus(now)
    RequestCard(
        bloodType = request.bloodType,
        title = stringResource(
            R.string.donor_request_title,
            displayBloodType(request.bloodType),
            stringResource(componentLabel(request.component)),
        ),
        subtitle = request.hospitalName,
        mine = true,
        muted = status != RequestStatus.OPEN,
        onClick = onClick,
    ) {
        StatusChip(requestChipStatus(status))
        timeLeftText(request, now)?.let { TimeLeftChip(it) }
        InfoChip(pluralStringResource(R.plurals.units_count, request.unitsNeeded, request.unitsNeeded))
    }
}
