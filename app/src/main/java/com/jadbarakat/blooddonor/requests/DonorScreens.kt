package com.jadbarakat.blooddonor.requests

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.DonorRequest
import com.jadbarakat.blooddonor.data.MAX_REPORT_DETAILS
import com.jadbarakat.blooddonor.data.Pledge
import com.jadbarakat.blooddonor.data.PledgeStatus
import com.jadbarakat.blooddonor.data.ReportReason
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.components.AppCard
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.AppTopBar
import com.jadbarakat.blooddonor.ui.components.BloodTypeBadge
import com.jadbarakat.blooddonor.ui.components.ChoiceTile
import com.jadbarakat.blooddonor.ui.components.ErrorState
import com.jadbarakat.blooddonor.ui.components.InfoChip
import com.jadbarakat.blooddonor.ui.components.InfoRow
import com.jadbarakat.blooddonor.ui.components.InlineMessage
import com.jadbarakat.blooddonor.ui.components.MenuAction
import com.jadbarakat.blooddonor.ui.components.NoticeCard
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.RequestCard
import com.jadbarakat.blooddonor.ui.components.SecondaryButton
import com.jadbarakat.blooddonor.ui.components.SectionHeader
import com.jadbarakat.blooddonor.ui.components.SectionLoading
import com.jadbarakat.blooddonor.ui.components.StatusChip
import com.jadbarakat.blooddonor.ui.components.SuccessChip
import com.jadbarakat.blooddonor.ui.components.TimeLeftChip
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.components.appTextFieldColors
import com.jadbarakat.blooddonor.ui.components.displayBloodType
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.extendedColors
import java.time.Duration
import java.time.Instant

/** Must match DONATION_CONFIRM_AFTER on the server; the server has the final say. */
private val DONATION_CONFIRM_AFTER: Duration = Duration.ofHours(3)

/** "Requests you can help with" on home: open ones, plus closed ones I committed to. Hidden when there are none. */
@Composable
fun ForMeSection(
    uiState: RequestsUiState,
    onOpen: (DonorRequest) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val visible = uiState.forMe.filter { it.committed || it.effectiveStatus(now) == RequestStatus.OPEN }
    if (visible.isEmpty() && uiState.forMeError == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.home_for_me))
        if (visible.isEmpty()) {
            InlineMessage(uiState.forMeError!!.resolve(), Tone.ERROR)
            SecondaryButton(stringResource(R.string.button_retry), onClick = onRetry, compact = true)
        }
        visible.forEach { request ->
            val status = request.effectiveStatus(now)
            RequestCard(
                bloodType = request.bloodType,
                title = stringResource(
                    R.string.donor_request_title,
                    displayBloodType(request.bloodType),
                    stringResource(componentLabel(request.component)),
                ),
                subtitle = stringResource(R.string.donor_request_place, request.hospitalName, request.areaName),
                muted = status != RequestStatus.OPEN,
                onClick = { onOpen(request) },
            ) {
                if (request.committed) SuccessChip(stringResource(R.string.chip_going))
                if (status != RequestStatus.OPEN) StatusChip(requestChipStatus(status))
                timeLeftText(request.neededBy, now)?.let { TimeLeftChip(it) }
                InfoChip(pluralStringResource(R.plurals.units_count, request.unitsNeeded, request.unitsNeeded))
            }
        }
    }
}

private enum class DonorDialog { HELP, WITHDRAW, REPORT, BLOCK }

/**
 * A request someone else posted, seen by a donor who was notified. Before committing it shows
 * nothing about the requester; after "I can help" it shows their first name and where to go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DonorRequestScreen(
    uiState: RequestsUiState,
    onHelp: () -> Unit,
    onWithdraw: () -> Unit,
    onDonationAnswer: (donated: Boolean) -> Unit,
    onReport: (reason: String, details: String) -> Unit,
    onBlockRequester: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = uiState.donorDetail
    val now = rememberNow()
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<DonorDialog?>(null) }
    // Safety: report a problem, or never hear from this requester again.
    val menu = if (request == null) emptyList() else listOf(
        MenuAction(
            label = stringResource(R.string.report_button),
            icon = R.drawable.ic_flag,
            onClick = { dialog = DonorDialog.REPORT },
            enabled = !uiState.isDonorActing,
        ),
        MenuAction(
            label = stringResource(R.string.block_requester_button),
            icon = R.drawable.ic_block,
            onClick = { dialog = DonorDialog.BLOCK },
            enabled = !uiState.isDonorActing,
            destructive = true,
        ),
    )

    AppScreen(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.donor_request_screen_title),
                onBack = onBack,
                backEnabled = !uiState.isDonorActing,
                menu = menu,
            )
        },
    ) {
        when {
            request != null -> {
                val status = request.effectiveStatus(now)
                val pledge = request.myPledge

                // Hero: the blood type, what's needed, and how urgent.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BloodTypeBadge(request.bloodType, size = 96.dp)
                    Text(
                        stringResource(
                            R.string.donor_request_title,
                            displayBloodType(request.bloodType),
                            stringResource(componentLabel(request.component)),
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusChip(requestChipStatus(status))
                        timeLeftText(request.neededBy, now)?.let { TimeLeftChip(it) }
                        InfoChip(pluralStringResource(R.plurals.units_count, request.unitsNeeded, request.unitsNeeded))
                    }
                }

                if (pledge != null) {
                    PledgeCard(
                        pledge = pledge,
                        requestOpen = status == RequestStatus.OPEN,
                        canAnswer = pledge.canAnswerDonation(status, now),
                        isActing = uiState.isDonorActing,
                        onWithdraw = { dialog = DonorDialog.WITHDRAW },
                        onDonationAnswer = onDonationAnswer,
                    )
                }

                AppCard(spacing = 12.dp) {
                    InfoRow(
                        icon = R.drawable.ic_hospital,
                        title = request.hospitalName,
                        subtitle = request.areaName,
                        iconContainer = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.extendedColors.redText,
                        // Coordinates come with my pledge, so Maps appears once I committed.
                        action = if (pledge?.status == PledgeStatus.COMMITTED && pledge.hospitalLatitude != null && pledge.hospitalLongitude != null && status == RequestStatus.OPEN) {
                            {
                                SecondaryButton(
                                    text = stringResource(R.string.donor_button_maps_short),
                                    onClick = { openMaps(context, pledge.hospitalLatitude, pledge.hospitalLongitude, pledge.hospitalName) },
                                    icon = R.drawable.ic_place,
                                    compact = true,
                                )
                            }
                        } else null,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    DetailRow(stringResource(R.string.request_component_label), stringResource(componentLabel(request.component)))
                    DetailRow(stringResource(R.string.request_units_label), request.unitsNeeded.toString())
                    DetailRow(stringResource(R.string.request_needed_by_label), formatDateTime(request.neededBy) ?: request.neededBy)
                }

                request.note?.let { NoteCard(it) }

                uiState.donorDetailError?.let { InlineMessage(it.resolve(), Tone.ERROR) }
                uiState.donorDetailInfo?.let { InlineMessage(it.resolve(), Tone.SUCCESS) }

                when {
                    pledge != null -> Unit
                    status == RequestStatus.OPEN -> {
                        PrimaryButton(
                            text = stringResource(R.string.donor_button_can_help),
                            onClick = { dialog = DonorDialog.HELP },
                            loading = uiState.isDonorActing,
                            large = true,
                            icon = R.drawable.ic_drop,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrivacyLine(stringResource(R.string.donor_privacy_line))
                    }
                    else -> InlineMessage(stringResource(R.string.donor_request_closed), Tone.INFO)
                }

                when (dialog) {
                    DonorDialog.HELP -> ConfirmDialog(
                        title = stringResource(R.string.donor_help_confirm_title),
                        message = stringResource(
                            R.string.donor_help_confirm_message,
                            request.hospitalName,
                            formatDateTime(request.neededBy) ?: request.neededBy,
                        ),
                        confirm = stringResource(R.string.donor_help_confirm_button),
                        dismiss = stringResource(R.string.request_confirm_back),
                        onConfirm = { dialog = null; onHelp() },
                        onDismiss = { dialog = null },
                    )
                    DonorDialog.WITHDRAW -> ConfirmDialog(
                        title = stringResource(R.string.donor_withdraw_confirm_title),
                        message = stringResource(R.string.donor_withdraw_confirm_message),
                        confirm = stringResource(R.string.donor_withdraw_confirm_button),
                        dismiss = stringResource(R.string.donor_withdraw_confirm_back),
                        onConfirm = { dialog = null; onWithdraw() },
                        onDismiss = { dialog = null },
                    )
                    DonorDialog.REPORT -> ReportDialog(
                        onSend = { reason, details -> dialog = null; onReport(reason, details) },
                        onDismiss = { dialog = null },
                    )
                    DonorDialog.BLOCK -> ConfirmDialog(
                        title = stringResource(R.string.block_requester_confirm_title),
                        message = stringResource(R.string.block_requester_confirm_message),
                        confirm = stringResource(R.string.block_requester_confirm_button),
                        dismiss = stringResource(R.string.request_confirm_back),
                        onConfirm = { dialog = null; onBlockRequester() },
                        onDismiss = { dialog = null },
                    )
                    null -> Unit
                }
            }

            uiState.donorDetailError != null -> ErrorState(message = uiState.donorDetailError.resolve())

            else -> SectionLoading(Modifier.padding(top = 48.dp))
        }
    }
}

/** After "I can help": who is waiting, and "Did you donate?" when it's time. */
@Composable
private fun PledgeCard(
    pledge: Pledge,
    requestOpen: Boolean,
    canAnswer: Boolean,
    isActing: Boolean,
    onWithdraw: () -> Unit,
    onDonationAnswer: (Boolean) -> Unit,
) {
    when (pledge.status) {
        PledgeStatus.DONATED -> NoticeCard(
            title = stringResource(R.string.donor_donated_thanks),
            tone = Tone.SUCCESS,
            icon = R.drawable.ic_drop,
        )
        PledgeStatus.NOT_DONATED -> NoticeCard(title = stringResource(R.string.donor_not_donated_thanks), tone = Tone.INFO)
        else -> AppCard(color = MaterialTheme.extendedColors.successContainer, border = null) {
            SuccessChip(stringResource(R.string.chip_going))
            Text(
                if (pledge.requesterFirstName.isBlank()) stringResource(R.string.donor_committed_title_no_name)
                else stringResource(R.string.donor_committed_title, pledge.requesterFirstName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.donor_committed_reminder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (canAnswer) {
                Text(
                    stringResource(R.string.donor_donated_question),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.donor_donated_yes),
                        onClick = { onDonationAnswer(true) },
                        enabled = !isActing,
                        containerColor = MaterialTheme.extendedColors.success,
                        contentColor = MaterialTheme.extendedColors.successContainer,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.donor_donated_no),
                        onClick = { onDonationAnswer(false) },
                        enabled = !isActing,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (requestOpen) {
                SecondaryButton(
                    text = stringResource(R.string.donor_button_withdraw),
                    onClick = onWithdraw,
                    enabled = !isActing,
                    destructive = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The request's note, under [title]. */
@Composable
internal fun NoteCard(note: String, title: String = stringResource(R.string.request_note_title)) {
    AppCard(color = MaterialTheme.colorScheme.surfaceVariant, border = null, spacing = 4.dp) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(note, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A small lock and a line explaining what is shared. */
@Composable
internal fun PrivacyLine(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = null,
            tint = MaterialTheme.extendedColors.tertiaryText,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.tertiaryText)
    }
}

/** Picks a reason (required) and optional details; "Send report" is the confirmation. */
@Composable
private fun ReportDialog(onSend: (reason: String, details: String) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyMedium)
                ReportReason.ALL.forEach { option ->
                    ChoiceTile(
                        text = stringResource(reportReasonLabel(option)),
                        selected = reason == option,
                        onClick = { reason = option },
                        minHeight = 44.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(MAX_REPORT_DETAILS) },
                    label = { Text(stringResource(R.string.report_details_label)) },
                    supportingText = { Text(stringResource(R.string.request_note_counter, details.length, MAX_REPORT_DETAILS)) },
                    minLines = 2,
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { reason?.let { onSend(it, details) } }, enabled = reason != null) {
                Text(stringResource(R.string.report_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.request_confirm_back)) } },
    )
}

@StringRes
private fun reportReasonLabel(reason: String): Int = when (reason) {
    ReportReason.FAKE -> R.string.report_reason_fake
    ReportReason.MONEY -> R.string.report_reason_money
    ReportReason.INAPPROPRIATE -> R.string.report_reason_inappropriate
    else -> R.string.report_reason_other
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

/** Opens the hospital in a maps app; falls back to Google Maps in the browser. */
private fun openMaps(context: Context, latitude: Double, longitude: Double, name: String) {
    val geo = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(name)})")
    val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
    for (uri in listOf(geo, web)) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        }
    }
    Toast.makeText(context, R.string.donor_maps_unavailable, Toast.LENGTH_SHORT).show()
}

/** The server's answer, or locally once the request closed or 3 hours passed (the list may be old). */
private fun Pledge.canAnswerDonation(requestStatus: String, now: Instant): Boolean {
    if (status != PledgeStatus.COMMITTED) return false
    if (canConfirmDonation || requestStatus != RequestStatus.OPEN) return true
    val committed = runCatching { Instant.parse(committedAt) }.getOrNull() ?: return false
    return !now.isBefore(committed.plus(DONATION_CONFIRM_AFTER))
}

private fun DonorRequest.effectiveStatus(now: Instant): String {
    val deadline = runCatching { Instant.parse(neededBy) }.getOrNull()
    return if (status == RequestStatus.OPEN && deadline != null && !now.isBefore(deadline)) RequestStatus.EXPIRED else status
}
