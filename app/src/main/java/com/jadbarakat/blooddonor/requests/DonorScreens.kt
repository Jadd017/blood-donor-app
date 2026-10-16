package com.jadbarakat.blooddonor.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.DonorRequest
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.resolve
import java.time.Instant

/** "Requests you can help with" on home. Hidden when there are none. */
@Composable
fun ForMeSection(
    uiState: RequestsUiState,
    onOpen: (DonorRequest) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val open = uiState.forMe.filter { it.effectiveStatus(now) == RequestStatus.OPEN }
    if (open.isEmpty() && uiState.forMeError == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_for_me), style = MaterialTheme.typography.titleMedium)
        if (open.isEmpty()) {
            Text(uiState.forMeError!!.resolve(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.button_retry)) }
        }
        open.forEach { request ->
            Card(
                onClick = { onOpen(request) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            R.string.request_card_title,
                            request.bloodType,
                            stringResource(componentLabel(request.component)),
                            request.unitsNeeded,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.donor_request_place, request.hospitalName, request.areaName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    timeLeftText(request.neededBy, now)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** A request someone else posted, seen by a donor who was notified. Nothing about the requester. */
@Composable
fun DonorRequestScreen(uiState: RequestsUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val request = uiState.donorDetail
    val now = rememberNow()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            request != null -> {
                val status = request.effectiveStatus(now)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.donor_request_title,
                            request.bloodType,
                            stringResource(componentLabel(request.component)),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(status)
                }
                timeLeftText(request.neededBy, now)?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                DetailRow(stringResource(R.string.request_hospital_label), request.hospitalName)
                DetailRow(stringResource(R.string.donor_request_area_label), request.areaName)
                DetailRow(stringResource(R.string.request_blood_type_label), request.bloodType)
                DetailRow(stringResource(R.string.request_component_label), stringResource(componentLabel(request.component)))
                DetailRow(stringResource(R.string.request_units_label), request.unitsNeeded.toString())
                DetailRow(stringResource(R.string.request_needed_by_label), formatDateTime(request.neededBy) ?: request.neededBy)
                request.note?.let { DetailRow(stringResource(R.string.request_note_label), it) }

                if (status == RequestStatus.OPEN) {
                    // Phase 4c turns this into the real response.
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.donor_button_can_help_soon))
                    }
                } else {
                    Text(
                        stringResource(R.string.donor_request_closed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                uiState.donorDetailError?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            uiState.donorDetailError != null -> Text(
                uiState.donorDetailError.resolve(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )

            else -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 48.dp))
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.request_button_back))
        }
    }
}

private fun DonorRequest.effectiveStatus(now: Instant): String {
    val deadline = runCatching { Instant.parse(neededBy) }.getOrNull()
    return if (status == RequestStatus.OPEN && deadline != null && !now.isBefore(deadline)) RequestStatus.EXPIRED else status
}
