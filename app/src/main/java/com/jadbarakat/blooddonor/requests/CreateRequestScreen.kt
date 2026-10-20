package com.jadbarakat.blooddonor.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import com.jadbarakat.blooddonor.ui.components.AppCard
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.AppTopBar
import com.jadbarakat.blooddonor.ui.components.ChoiceGrid
import com.jadbarakat.blooddonor.ui.components.ChoicePill
import com.jadbarakat.blooddonor.ui.components.EmptyState
import com.jadbarakat.blooddonor.ui.components.ErrorState
import com.jadbarakat.blooddonor.ui.components.InfoRow
import com.jadbarakat.blooddonor.ui.components.InlineMessage
import com.jadbarakat.blooddonor.ui.components.LoadingState
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.SectionHeader
import com.jadbarakat.blooddonor.ui.components.Stepper
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.components.appTextFieldColors
import com.jadbarakat.blooddonor.ui.components.displayBloodType
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.Dimens
import com.jadbarakat.blooddonor.ui.theme.extendedColors

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

    AppScreen(
        modifier = modifier,
        topBar = { AppTopBar(title = stringResource(R.string.request_create_title), onBack = onBack, backEnabled = enabled) },
    ) {
        // Hospital
        SectionHeader(stringResource(R.string.request_hospital_label))
        val hospital = uiState.hospitalById(form.hospitalId)
        val hospitalArea = hospital?.let { h -> areas.firstOrNull { it.id == h.areaId } }
        AppCard(
            onClick = { showPicker = true },
            enabled = enabled,
            contentPadding = 12.dp,
            border = BorderStroke(
                1.dp,
                if (uiState.hospitalError != null) MaterialTheme.extendedColors.redText else MaterialTheme.colorScheme.outline,
            ),
        ) {
            InfoRow(
                icon = R.drawable.ic_hospital,
                title = hospital?.name ?: stringResource(R.string.request_hospital_choose),
                subtitle = hospitalArea?.let { stringResource(R.string.profile_area_value, it.name, it.governorate) }
                    ?: stringResource(R.string.request_hospital_hint),
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.extendedColors.redText,
                action = {
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        FieldError(uiState.hospitalError?.resolve())

        // Patient blood type
        SectionHeader(stringResource(R.string.request_blood_type_label))
        ChoiceGrid(
            options = BLOOD_TYPES,
            columns = 4,
            selected = form.bloodType,
            onSelect = { type -> onFormChange { it.copy(bloodType = type) } },
            label = { displayBloodType(it) },
            enabled = enabled,
            large = true,
        )
        FieldError(uiState.bloodTypeError?.resolve())

        // Component
        SectionHeader(stringResource(R.string.request_component_label))
        ChoiceGrid(
            options = Component.ALL,
            columns = 2,
            selected = form.component,
            onSelect = { component -> onFormChange { it.copy(component = component) } },
            label = { stringResource(componentLabel(it)) },
            enabled = enabled,
        )
        FieldError(uiState.componentError?.resolve())

        // Units
        SectionHeader(stringResource(R.string.request_units_label))
        Stepper(
            value = form.units,
            valueLabel = pluralStringResource(R.plurals.units_count, form.units, form.units),
            onDecrement = { onFormChange { it.copy(units = it.units - 1) } },
            onIncrement = { onFormChange { it.copy(units = it.units + 1) } },
            canDecrement = enabled && form.units > UNITS_RANGE.first,
            canIncrement = enabled && form.units < UNITS_RANGE.last,
            decrementLabel = stringResource(R.string.request_units_less),
            incrementLabel = stringResource(R.string.request_units_more),
        )

        // Deadline
        SectionHeader(stringResource(R.string.request_deadline_label))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            DEADLINE_HOURS.forEach { hours ->
                ChoicePill(
                    text = stringResource(R.string.request_deadline_hours, hours),
                    selected = form.hours == hours,
                    onClick = { onFormChange { it.copy(hours = hours) } },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Note
        SectionHeader(stringResource(R.string.request_note_label))
        OutlinedTextField(
            value = form.note,
            onValueChange = { value -> onFormChange { it.copy(note = value) } },
            placeholder = { Text(stringResource(R.string.request_note_placeholder)) },
            supportingText = {
                Row(Modifier.fillMaxWidth()) {
                    Text(uiState.noteError?.resolve().orEmpty(), modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.request_note_counter, form.note.length, MAX_NOTE_LENGTH))
                }
            },
            isError = uiState.noteError != null,
            enabled = enabled,
            minLines = 3,
            colors = appTextFieldColors(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        uiState.submitError?.let { InlineMessage(it.resolve(), Tone.ERROR) }

        PrimaryButton(
            text = stringResource(R.string.request_button_post),
            onClick = onSubmit,
            loading = uiState.isSubmitting,
            icon = R.drawable.ic_drop,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.request_confirm_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.extendedColors.tertiaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                AppTopBar(
                    title = stringResource(R.string.request_hospital_picker_title),
                    onBack = onDismiss,
                    backIcon = R.drawable.ic_close,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.request_hospital_search)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    colors = appTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding).padding(bottom = 8.dp),
                )
                when {
                    isLoading -> LoadingState(Modifier.weight(1f))

                    error != null -> Column(
                        Modifier.weight(1f).fillMaxWidth().padding(Dimens.ScreenPadding),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ErrorState(message = error, onRetry = onRetry)
                    }

                    groups.isEmpty() -> Column(Modifier.weight(1f).padding(Dimens.ScreenPadding)) {
                        EmptyState(icon = R.drawable.ic_search, title = stringResource(R.string.request_hospital_no_results))
                    }

                    else -> LazyColumn(Modifier.weight(1f)) {
                        groups.forEach { (area, areaHospitals) ->
                            stickyHeader(key = "area-${area.id}") {
                                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(R.string.profile_area_value, area.name, area.governorate),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.extendedColors.redText,
                                        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                                    )
                                }
                            }
                            items(areaHospitals, key = { it.id }) { hospital ->
                                InfoRow(
                                    icon = R.drawable.ic_hospital,
                                    title = hospital.name,
                                    onClick = { onSelected(hospital) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.request_hospital_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendedColors.tertiaryText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldError(text: String?) {
    if (text != null) InlineMessage(text, Tone.ERROR)
}
