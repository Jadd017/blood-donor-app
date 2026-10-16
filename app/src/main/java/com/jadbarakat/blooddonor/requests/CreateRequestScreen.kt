package com.jadbarakat.blooddonor.requests

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.Area
import com.jadbarakat.blooddonor.data.BLOOD_TYPES
import com.jadbarakat.blooddonor.data.Component
import com.jadbarakat.blooddonor.data.Hospital
import com.jadbarakat.blooddonor.ui.resolve

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateRequestScreen(
    uiState: RequestsUiState,
    areas: List<Area>,
    onFormChange: ((RequestForm) -> RequestForm) -> Unit,
    onRetryHospitals: () -> Unit,
    onSubmit: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = uiState.form
    val enabled = !uiState.isSubmitting
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.request_create_title), style = MaterialTheme.typography.headlineSmall)

        // Hospital
        SectionLabel(stringResource(R.string.request_hospital_label))
        val hospital = uiState.hospitalById(form.hospitalId)
        OutlinedButton(
            onClick = { showPicker = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = hospital?.name ?: stringResource(R.string.request_hospital_choose),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FieldError(uiState.hospitalError?.resolve())

        // Patient blood type
        SectionLabel(stringResource(R.string.request_blood_type_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BLOOD_TYPES.forEach { type ->
                FilterChip(
                    selected = form.bloodType == type,
                    onClick = { onFormChange { it.copy(bloodType = type) } },
                    label = { Text(type) },
                    enabled = enabled,
                )
            }
        }
        FieldError(uiState.bloodTypeError?.resolve())

        // Component
        SectionLabel(stringResource(R.string.request_component_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Component.ALL.forEach { component ->
                FilterChip(
                    selected = form.component == component,
                    onClick = { onFormChange { it.copy(component = component) } },
                    label = { Text(stringResource(componentLabel(component))) },
                    enabled = enabled,
                )
            }
        }
        FieldError(uiState.componentError?.resolve())

        // Units
        SectionLabel(stringResource(R.string.request_units_label))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = { onFormChange { it.copy(units = it.units - 1) } },
                enabled = enabled && form.units > UNITS_RANGE.first,
            ) { Text(stringResource(R.string.request_units_minus)) }
            Text(
                text = form.units.toString(),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            OutlinedButton(
                onClick = { onFormChange { it.copy(units = it.units + 1) } },
                enabled = enabled && form.units < UNITS_RANGE.last,
            ) { Text(stringResource(R.string.request_units_plus)) }
        }

        // Deadline
        SectionLabel(stringResource(R.string.request_deadline_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DEADLINE_HOURS.forEach { hours ->
                FilterChip(
                    selected = form.hours == hours,
                    onClick = { onFormChange { it.copy(hours = hours) } },
                    label = { Text(stringResource(R.string.request_deadline_hours, hours)) },
                    enabled = enabled,
                )
            }
        }

        // Note
        OutlinedTextField(
            value = form.note,
            onValueChange = { value -> onFormChange { it.copy(note = value) } },
            label = { Text(stringResource(R.string.request_note_label)) },
            placeholder = { Text(stringResource(R.string.request_note_placeholder)) },
            supportingText = {
                Text(
                    uiState.noteError?.resolve()
                        ?: stringResource(R.string.request_note_counter, form.note.length, MAX_NOTE_LENGTH)
                )
            },
            isError = uiState.noteError != null,
            enabled = enabled,
            minLines = 2,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        uiState.submitError?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
            } else {
                Text(stringResource(R.string.request_button_post))
            }
        }
        TextButton(onClick = onBack, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.request_button_back))
        }
    }

    if (showPicker) {
        HospitalPickerDialog(
            hospitals = uiState.hospitals,
            areas = areas,
            isLoading = uiState.isLoadingHospitals,
            error = uiState.hospitalsError?.resolve(),
            onRetry = onRetryHospitals,
            onSelected = { selected ->
                onFormChange { it.copy(hospitalId = selected.id) }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    if (uiState.showConfirm) {
        AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text(stringResource(R.string.request_confirm_title)) },
            text = { Text(stringResource(R.string.request_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.request_confirm_post)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirm) { Text(stringResource(R.string.request_confirm_back)) }
            },
        )
    }
}

/** Full-screen searchable list of hospitals, grouped by area. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HospitalPickerDialog(
    hospitals: List<Hospital>,
    areas: List<Area>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelected: (Hospital) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Areas arrive sorted by governorate, then name; keep that order for the groups.
    val groups = remember(hospitals, areas, query) {
        val q = query.trim()
        val byArea = hospitals.groupBy { it.areaId }
        areas.mapNotNull { area ->
            val areaMatches = q.isNotEmpty() && area.name.contains(q, ignoreCase = true)
            val matching = byArea[area.id].orEmpty()
                .filter { q.isEmpty() || areaMatches || it.name.contains(q, ignoreCase = true) }
            if (matching.isEmpty()) null else area to matching
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 16.dp),
                ) {
                    Text(
                        stringResource(R.string.request_hospital_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.request_hospital_picker_close)) }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.request_hospital_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
                when {
                    isLoading -> Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { CircularProgressIndicator() }

                    error != null -> Column(
                        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error, textAlign = TextAlign.Center)
                        Button(onClick = onRetry) { Text(stringResource(R.string.button_retry)) }
                    }

                    groups.isEmpty() -> Text(
                        stringResource(R.string.request_hospital_no_results),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                    )

                    else -> LazyColumn(Modifier.weight(1f)) {
                        groups.forEach { (area, areaHospitals) ->
                            stickyHeader(key = "area-${area.id}") {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(R.string.profile_area_value, area.name, area.governorate),
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            items(areaHospitals, key = { it.id }) { hospital ->
                                Text(
                                    hospital.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelected(hospital) }
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.request_hospital_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FieldError(text: String?) {
    if (text != null) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
