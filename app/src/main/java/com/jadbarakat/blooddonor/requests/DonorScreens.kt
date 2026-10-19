package com.jadbarakat.blooddonor.requests

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.DonorRequest
import com.jadbarakat.blooddonor.data.Pledge
import com.jadbarakat.blooddonor.data.PledgeStatus
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.resolve
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_for_me), style = MaterialTheme.typography.titleMedium)
        if (visible.isEmpty()) {
            Text(uiState.forMeError!!.resolve(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.button_retry)) }
        }
        visible.forEach { request ->
            val status = request.effectiveStatus(now)
            Card(
                onClick = { onOpen(request) },
                colors = CardDefaults.cardColors(
                    containerColor = if (status == RequestStatus.OPEN) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                        if (status != RequestStatus.OPEN) StatusChip(status)
                    }
                    Text(
                        stringResource(R.string.donor_request_place, request.hospitalName, request.areaName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    timeLeftText(request.neededBy, now)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (request.committed) HelpingBadge()
                }
            }
        }
    }
}

@Composable
private fun HelpingBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(R.string.donor_badge_helping),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private enum class DonorDialog { HELP, WITHDRAW }

/**
 * A request someone else posted, seen by a donor who was notified. Before committing it shows
 * nothing about the requester; after "I can help" it shows their first name and where to go.
 */
@Composable
fun DonorRequestScreen(
    uiState: RequestsUiState,
    onHelp: () -> Unit,
    onWithdraw: () -> Unit,
    onDonationAnswer: (donated: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = uiState.donorDetail
    val now = rememberNow()
    var dialog by rememberSaveable { mutableStateOf<DonorDialog?>(null) }
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
                val pledge = request.myPledge
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

                DetailRow(stringResource(R.string.request_hospital_label), request.hospitalName)
                DetailRow(stringResource(R.string.donor_request_area_label), request.areaName)
                DetailRow(stringResource(R.string.request_blood_type_label), request.bloodType)
                DetailRow(stringResource(R.string.request_component_label), stringResource(componentLabel(request.component)))
                DetailRow(stringResource(R.string.request_units_label), request.unitsNeeded.toString())
                DetailRow(stringResource(R.string.request_needed_by_label), formatDateTime(request.neededBy) ?: request.neededBy)
                request.note?.let { DetailRow(stringResource(R.string.request_note_label), it) }

                uiState.donorDetailError?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                when {
                    pledge != null -> Unit
                    status == RequestStatus.OPEN -> Button(
                        onClick = { dialog = DonorDialog.HELP },
                        enabled = !uiState.isDonorActing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isDonorActing) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                        } else {
                            Text(stringResource(R.string.donor_button_can_help))
                        }
                    }
                    else -> Text(
                        stringResource(R.string.donor_request_closed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    null -> Unit
                }
            }

            uiState.donorDetailError != null -> Text(
                uiState.donorDetailError.resolve(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )

            else -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 48.dp))
        }
        TextButton(onClick = onBack, enabled = !uiState.isDonorActing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.request_button_back))
        }
    }
}

/** After "I can help": who is waiting, where to go, and "Did you donate?" when it's time. */
@Composable
private fun PledgeCard(
    pledge: Pledge,
    requestOpen: Boolean,
    canAnswer: Boolean,
    isActing: Boolean,
    onWithdraw: () -> Unit,
    onDonationAnswer: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val now = rememberNow()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (pledge.status) {
                PledgeStatus.DONATED -> Text(stringResource(R.string.donor_donated_thanks), style = MaterialTheme.typography.bodyLarge)
                PledgeStatus.NOT_DONATED -> Text(stringResource(R.string.donor_not_donated_thanks), style = MaterialTheme.typography.bodyLarge)
                else -> {
                    Text(
                        if (pledge.requesterFirstName.isBlank()) stringResource(R.string.donor_committed_title_no_name)
                        else stringResource(R.string.donor_committed_title, pledge.requesterFirstName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.donor_request_place, pledge.hospitalName, pledge.areaName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    timeLeftText(pledge.neededBy, now)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.donor_committed_reminder), style = MaterialTheme.typography.bodyMedium)
                    if (pledge.hospitalLatitude != null && pledge.hospitalLongitude != null && requestOpen) {
                        Button(
                            onClick = { openMaps(context, pledge.hospitalLatitude, pledge.hospitalLongitude, pledge.hospitalName) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.donor_button_open_maps)) }
                    }
                    if (canAnswer) {
                        Text(stringResource(R.string.donor_donated_question), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDonationAnswer(true) }, enabled = !isActing, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.donor_donated_yes))
                            }
                            OutlinedButton(onClick = { onDonationAnswer(false) }, enabled = !isActing, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.donor_donated_no))
                            }
                        }
                    }
                    if (requestOpen) {
                        OutlinedButton(onClick = onWithdraw, enabled = !isActing, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.donor_button_withdraw))
                        }
                    }
                }
            }
        }
    }
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
