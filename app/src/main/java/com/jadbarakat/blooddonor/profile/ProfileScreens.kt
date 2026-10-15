package com.jadbarakat.blooddonor.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.Area
import com.jadbarakat.blooddonor.data.BLOOD_TYPES
import com.jadbarakat.blooddonor.data.Profile
import com.jadbarakat.blooddonor.data.Sex
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.BloodDonorTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Everything shown after sign-in: loading, setup/edit form, or the home summary. */
@Composable
fun ProfileRoot(viewModel: ProfileViewModel, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onSignedIn() }

    when (val screen = uiState.screen) {
        ProfileScreen.Loading -> LoadingScreen(modifier)
        is ProfileScreen.LoadFailed -> LoadFailedScreen(
            message = screen.message.resolve(),
            onRetry = viewModel::retry,
            onSignOut = onSignOut,
            modifier = modifier,
        )
        is ProfileScreen.Home -> HomeScreen(
            profile = screen.profile,
            area = uiState.areaById(screen.profile.areaId),
            onEditProfile = viewModel::editProfile,
            onSignOut = onSignOut,
            modifier = modifier,
        )
        is ProfileScreen.Form -> {
            BackHandler(enabled = !screen.isNew) { viewModel.cancelEdit() }
            ProfileFormScreen(
                uiState = uiState,
                isNew = screen.isNew,
                onFormChange = viewModel::updateForm,
                onSave = viewModel::save,
                onCancel = viewModel::cancelEdit,
                onSignOut = onSignOut,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun LoadFailedScreen(message: String, onRetry: () -> Unit, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    ScreenColumn(modifier, centered = true) {
        Text(stringResource(R.string.load_failed_title), style = MaterialTheme.typography.headlineSmall)
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.button_retry))
        }
        TextButton(onClick = onSignOut) {
            Text(stringResource(R.string.signed_in_button_sign_out))
        }
    }
}

@Composable
fun HomeScreen(
    profile: Profile,
    area: Area?,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier, centered = true) {
        Text(
            text = stringResource(R.string.home_title, profile.fullName),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryRow(
                stringResource(R.string.profile_blood_type_label),
                profile.bloodType ?: stringResource(R.string.profile_blood_type_unknown),
            )
            SummaryRow(stringResource(R.string.profile_phone_label), profile.phone)
            SummaryRow(
                stringResource(R.string.profile_area_label),
                area?.let { stringResource(R.string.profile_area_value, it.name, it.governorate) }
                    ?: stringResource(R.string.home_not_set),
            )
            SummaryRow(
                stringResource(R.string.profile_last_donation_label),
                profile.lastDonationDate?.let { formatIsoDate(it) }
                    ?: stringResource(R.string.profile_last_donation_never),
            )
            SummaryRow(
                stringResource(R.string.home_notifications_label),
                stringResource(if (profile.isDonor) R.string.home_notifications_on else R.string.home_notifications_off),
            )
        }
        Button(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_button_edit_profile))
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.signed_in_button_sign_out))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
    }
}

@Composable
fun ProfileFormScreen(
    uiState: ProfileUiState,
    isNew: Boolean,
    onFormChange: ((ProfileForm) -> ProfileForm) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = uiState.form
    val enabled = !uiState.isSaving

    ScreenColumn(modifier, centered = false) {
        Text(
            text = stringResource(if (isNew) R.string.profile_title_setup else R.string.profile_title_edit),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (isNew) {
            Text(stringResource(R.string.profile_setup_intro), style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedTextField(
            value = form.fullName,
            onValueChange = { v -> onFormChange { it.copy(fullName = v) } },
            label = { Text(stringResource(R.string.profile_full_name_label)) },
            isError = uiState.fullNameError != null,
            supportingText = uiState.fullNameError?.let { { Text(it.resolve()) } },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.phone,
            onValueChange = { v -> onFormChange { it.copy(phone = v) } },
            label = { Text(stringResource(R.string.profile_phone_label)) },
            placeholder = { Text(stringResource(R.string.profile_phone_placeholder)) },
            isError = uiState.phoneError != null,
            supportingText = {
                Text(uiState.phoneError?.resolve() ?: stringResource(R.string.profile_phone_privacy_note))
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        BloodTypeDropdown(
            selected = form.bloodType,
            onSelected = { v -> onFormChange { it.copy(bloodType = v) } },
            enabled = enabled,
        )

        SexSelector(
            selected = form.sex,
            onSelected = { v -> onFormChange { it.copy(sex = v) } },
            error = uiState.sexError?.resolve(),
            enabled = enabled,
        )

        AreaDropdown(
            areas = uiState.areas,
            selected = uiState.areaById(form.areaId),
            onSelected = { v -> onFormChange { it.copy(areaId = v.id) } },
            error = uiState.areaError?.resolve(),
            enabled = enabled,
        )

        LastDonationField(
            date = form.lastDonationDate,
            onDateChange = { v -> onFormChange { it.copy(lastDonationDate = v) } },
            enabled = enabled,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.profile_is_donor_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = form.isDonor,
                onCheckedChange = { v -> onFormChange { it.copy(isDonor = v) } },
                enabled = enabled,
            )
        }

        uiState.saveError?.let {
            Text(
                text = it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.profile_button_save))
            }
        }
        if (isNew) {
            TextButton(onClick = onSignOut, enabled = enabled) {
                Text(stringResource(R.string.signed_in_button_sign_out))
            }
        } else {
            OutlinedButton(onClick = onCancel, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_button_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BloodTypeDropdown(selected: String?, onSelected: (String?) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.profile_blood_type_unknown)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected ?: unknown,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.profile_blood_type_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (BLOOD_TYPES + null).forEach { type ->
                DropdownMenuItem(
                    text = { Text(type ?: unknown) },
                    onClick = {
                        onSelected(type)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun SexSelector(selected: String?, onSelected: (String) -> Unit, error: String?, enabled: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.profile_sex_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(Sex.MALE to R.string.profile_sex_male, Sex.FEMALE to R.string.profile_sex_female)
                .forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.selectable(
                            selected = selected == value,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(value) },
                        ),
                    ) {
                        RadioButton(selected = selected == value, onClick = null, enabled = enabled)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp, end = 8.dp))
                    }
                }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaDropdown(
    areas: List<Area>,
    selected: Area?,
    onSelected: (Area) -> Unit,
    error: String?,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    // Areas arrive sorted by governorate, then name.
    val byGovernorate = remember(areas) { areas.groupBy { it.governorate } }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected?.let { stringResource(R.string.profile_area_value, it.name, it.governorate) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.profile_area_label)) },
            placeholder = { Text(stringResource(R.string.profile_area_placeholder)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            byGovernorate.forEach { (governorate, governorateAreas) ->
                Text(
                    text = governorate,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                governorateAreas.forEach { area ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(area.name)
                                Text(
                                    text = area.district,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelected(area)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LastDonationField(date: LocalDate?, onDateChange: (LocalDate?) -> Unit, enabled: Boolean) {
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.let { formatDate(it) } ?: stringResource(R.string.profile_last_donation_never),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.profile_last_donation_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only text field swallows taps, so an overlay opens the picker.
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { showPicker = true },
        )
    }

    if (showPicker) {
        val today = LocalDate.now()
        val todayMillis = today.toUtcMillis()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date?.toUtcMillis(),
            yearRange = (today.year - 80)..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDateChange(it.toLocalDate()) }
                        showPicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) { Text(stringResource(R.string.button_ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDateChange(null)
                        showPicker = false
                    }) { Text(stringResource(R.string.profile_last_donation_never)) }
                    TextButton(onClick = { showPicker = false }) {
                        Text(stringResource(R.string.profile_button_cancel))
                    }
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun ScreenColumn(modifier: Modifier, centered: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = if (centered) {
            Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        } else {
            Arrangement.spacedBy(12.dp)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

// The date picker works in UTC-midnight millis.
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun formatIsoDate(iso: String): String =
    runCatching { formatDate(LocalDate.parse(iso)) }.getOrDefault(iso)

@Preview(showBackground = true)
@Composable
private fun ProfileFormPreview() {
    BloodDonorTheme {
        ProfileFormScreen(
            uiState = ProfileUiState(form = ProfileForm(fullName = "Jad Barakat")),
            isNew = true,
            onFormChange = {},
            onSave = {},
            onCancel = {},
            onSignOut = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    BloodDonorTheme {
        HomeScreen(
            profile = Profile(
                email = "donor@example.com",
                fullName = "Jad Barakat",
                phone = "+96171123456",
                bloodType = "O+",
                sex = Sex.MALE,
                areaId = 1,
                lastDonationDate = "2026-03-01",
                isDonor = true,
            ),
            area = Area(1, "Hamra", "Beirut", "Beirut"),
            onEditProfile = {},
            onSignOut = {},
        )
    }
}
