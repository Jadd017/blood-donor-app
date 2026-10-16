package com.jadbarakat.blooddonor.requests

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.resolve
import java.time.Instant

private enum class PendingAction { FULFILL, CANCEL }

@Composable
fun RequestDetailScreen(
    uiState: RequestsUiState,
    onFulfill: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = uiState.detail ?: return
    val now = rememberNow()
    val status = request.effectiveStatus(now)
    var pending by rememberSaveable { mutableStateOf<PendingAction?>(null) }

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

        if (status == RequestStatus.OPEN) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.request_matching_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        uiState.detailError?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
