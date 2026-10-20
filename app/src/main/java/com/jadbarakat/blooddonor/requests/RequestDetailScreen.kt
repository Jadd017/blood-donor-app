package com.jadbarakat.blooddonor.requests

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.Helper
import com.jadbarakat.blooddonor.data.HelperOutcome
import com.jadbarakat.blooddonor.data.HelperState
import com.jadbarakat.blooddonor.data.PledgeStatus
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.resolve
import java.time.Instant

private enum class PendingAction { FULFILL, CANCEL }

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
    if (request == null) {
        // Opened by id (e.g. from a notification) and still loading, or loading failed.
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            uiState.detailError?.let {
                Text(it.resolve(), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 48.dp))
            } ?: CircularProgressIndicator(Modifier.padding(top = 48.dp))
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.request_button_back))
            }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.request_detail_title, request.bloodType),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            StatusChip(status)
        }
        timeLeftText(request, now)?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (request.hidden) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.request_hidden_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.request_hidden_message), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        DetailRow(stringResource(R.string.request_hospital_label), request.hospitalName)
        DetailRow(stringResource(R.string.request_blood_type_label), request.bloodType)
        DetailRow(stringResource(R.string.request_component_label), stringResource(componentLabel(request.component)))
        DetailRow(stringResource(R.string.request_units_label), request.unitsNeeded.toString())
        DetailRow(stringResource(R.string.request_needed_by_label), formatDateTime(request.neededBy) ?: request.neededBy)
        DetailRow(stringResource(R.string.request_created_label), formatDateTime(request.createdAt) ?: request.createdAt)
        if (status != RequestStatus.OPEN) {
            formatDateTime(request.closedAt ?: request.neededBy.takeIf { status == RequestStatus.EXPIRED })?.let {
                DetailRow(stringResource(R.string.request_closed_label), it)
            }
        }
        request.note?.let { DetailRow(stringResource(R.string.request_note_label), it) }

        request.committedCount?.let { committed ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val donated = request.confirmedDonatedCount ?: 0
                    Text(
                        stringResource(R.string.request_progress, donated, request.onTheWayCount ?: (committed - donated)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(
                        progress = { (committed.toFloat() / request.unitsNeeded).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (committed == 0 && status == RequestStatus.OPEN) {
                        Text(stringResource(R.string.request_matching_placeholder), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
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

        uiState.detailError?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        uiState.detailInfo?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }

        if (status == RequestStatus.OPEN) {
            Button(
                onClick = { pending = PendingAction.FULFILL },
                enabled = !uiState.isActing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isActing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                } else {
                    Text(stringResource(R.string.request_button_fulfill))
                }
            }
            OutlinedButton(
                onClick = { pending = PendingAction.CANCEL },
                enabled = !uiState.isActing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.request_button_cancel))
            }
        }
        TextButton(onClick = onBack, enabled = !uiState.isActing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.request_button_back))
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
        val (title, message, confirm) = when (action) {
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
            title = { Text(stringResource(title)) },
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.request_helpers_title), style = MaterialTheme.typography.titleMedium)
        if (helpers.any { it.canConfirm }) {
            Text(
                stringResource(R.string.request_helpers_confirm_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        helpers.forEach { helper ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(helper.fullName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(helper.bloodType, helperStatusText(helper)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = when (helper.state) {
                                    HelperState.DONATED -> MaterialTheme.colorScheme.primary
                                    HelperState.NO_SHOW -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        // No phone once the donor deleted their account.
                        if (helper.phone.isNotBlank()) {
                            OutlinedButton(onClick = { dial(context, helper.phone) }) {
                                Text(stringResource(R.string.request_helper_call))
                            }
                        }
                    }
                    if (helper.canConfirm) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onConfirm(helper.pledgeId, HelperOutcome.DONATED) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.request_helper_button_donated)) }
                            OutlinedButton(
                                onClick = { onConfirm(helper.pledgeId, HelperOutcome.NO_SHOW) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.request_helper_button_no_show)) }
                        }
                    }
                    helper.donorId?.let { donorId ->
                        val isBlocked = donorId in blocked
                        TextButton(
                            onClick = { onBlock(donorId) },
                            enabled = enabled && !isBlocked,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(if (isBlocked) R.string.block_helper_blocked else R.string.block_helper_button))
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.request_helpers_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** My confirmation if I gave one; otherwise whether they still count, plus the donor's own answer. */
@Composable
private fun helperStatusText(helper: Helper): String {
    val state = stringResource(
        when (helper.state) {
            HelperState.DONATED -> R.string.request_helper_state_donated
            HelperState.NO_SHOW -> R.string.request_helper_state_no_show
            HelperState.AWAITING_CONFIRMATION -> R.string.request_helper_state_awaiting
            else -> R.string.request_helper_state_on_the_way
        }
    )
    if (!helper.canConfirm) return state
    val says = when (helper.status) {
        PledgeStatus.DONATED -> stringResource(R.string.request_helper_says_donated)
        PledgeStatus.NOT_DONATED -> stringResource(R.string.request_helper_says_not_donated)
        else -> null
    }
    return listOfNotNull(state, says).joinToString(" · ")
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_my_requests), style = MaterialTheme.typography.titleMedium)
        when {
            uiState.myRequests.isEmpty() && uiState.isLoadingList ->
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

            uiState.myRequests.isEmpty() && uiState.listError != null -> {
                Text(uiState.listError.resolve(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.button_retry)) }
            }

            uiState.myRequests.isEmpty() -> Text(
                stringResource(R.string.home_my_requests_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> uiState.myRequests.forEach { request -> RequestCard(request, now, onClick = { onOpen(request) }) }
        }
    }
}

@Composable
private fun RequestCard(request: BloodRequest, now: Instant, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.request_card_title,
                        request.bloodType,
                        stringResource(componentLabel(request.component)),
                        request.unitsNeeded,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(request.effectiveStatus(now))
            }
            Text(request.hospitalName, style = MaterialTheme.typography.bodyMedium)
            timeLeftText(request, now)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
