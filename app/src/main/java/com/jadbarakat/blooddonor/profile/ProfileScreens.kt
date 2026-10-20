package com.jadbarakat.blooddonor.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jadbarakat.blooddonor.PendingRequest
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.Area
import com.jadbarakat.blooddonor.data.BLOOD_TYPES
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.DonorRequest
import com.jadbarakat.blooddonor.notifications.NotificationExplainerScreen
import com.jadbarakat.blooddonor.notifications.NotificationSettings
import com.jadbarakat.blooddonor.notifications.NotificationsOffHint
import com.jadbarakat.blooddonor.data.Profile
import com.jadbarakat.blooddonor.data.Sex
import com.jadbarakat.blooddonor.requests.CreateRequestScreen
import com.jadbarakat.blooddonor.requests.DonorRequestScreen
import com.jadbarakat.blooddonor.requests.ForMeSection
import com.jadbarakat.blooddonor.requests.MyRequestsSection
import com.jadbarakat.blooddonor.requests.RequestDetailScreen
import com.jadbarakat.blooddonor.requests.RequestsScreen
import com.jadbarakat.blooddonor.requests.RequestsUiState
import com.jadbarakat.blooddonor.requests.RequestsViewModel
import com.jadbarakat.blooddonor.settings.SettingsScreen
import com.jadbarakat.blooddonor.settings.SettingsViewModel
import com.jadbarakat.blooddonor.ui.components.AppCard
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.AppTopBar
import com.jadbarakat.blooddonor.ui.components.BloodTypeBadge
import com.jadbarakat.blooddonor.ui.components.ChoiceGrid
import com.jadbarakat.blooddonor.ui.components.ChoiceTile
import com.jadbarakat.blooddonor.ui.components.ErrorState
import com.jadbarakat.blooddonor.ui.components.InfoRow
import com.jadbarakat.blooddonor.ui.components.InlineMessage
import com.jadbarakat.blooddonor.ui.components.LoadingState
import com.jadbarakat.blooddonor.ui.components.NoticeCard
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.RoundIconButton
import com.jadbarakat.blooddonor.ui.components.SecondaryButton
import com.jadbarakat.blooddonor.ui.components.SectionHeader
import com.jadbarakat.blooddonor.ui.components.SuccessChip
import com.jadbarakat.blooddonor.ui.components.TertiaryButton
import com.jadbarakat.blooddonor.ui.components.TimeLeftChip
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.components.appTextFieldColors
import com.jadbarakat.blooddonor.ui.components.displayBloodType
import com.jadbarakat.blooddonor.ui.formatWhen
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.BloodDonorTheme
import com.jadbarakat.blooddonor.ui.theme.extendedColors
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Everything shown after sign-in: loading, setup/edit form, or the home summary. */
@Composable
fun ProfileRoot(
    viewModel: ProfileViewModel,
    requestsViewModel: RequestsViewModel,
    settingsViewModel: SettingsViewModel,
    notificationSettings: NotificationSettings,
    pendingRequest: MutableStateFlow<PendingRequest?>,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        is ProfileScreen.Home -> {
            LaunchedEffect(Unit) { requestsViewModel.onHomeShown() }
            // A tapped notification opens that request: my own, or the donor view.
            val pending by pendingRequest.collectAsStateWithLifecycle()
            LaunchedEffect(pending) {
                pending?.let {
                    if (it.asOwner) requestsViewModel.openOwnRequest(it.id) else requestsViewModel.openDonorRequest(it.id)
                    pendingRequest.value = null
                }
            }
            val requestsState by requestsViewModel.uiState.collectAsStateWithLifecycle()
            when (requestsState.screen) {
                RequestsScreen.Home -> if (uiState.askNotificationPermission) {
                    NotificationExplainerScreen(onDone = viewModel::notificationPromptDone, modifier = modifier)
                } else {
                    HomeScreen(
                        profile = screen.profile,
                        area = uiState.areaById(screen.profile.areaId),
                        requests = requestsState,
                        notificationSettings = notificationSettings,
                        onRequestBlood = requestsViewModel::openCreate,
                        onOpenRequest = requestsViewModel::openDetail,
                        onOpenDonorRequest = { requestsViewModel.openDonorRequest(it.id) },
                        onRetryRequests = requestsViewModel::refreshList,
                        onRetryForMe = requestsViewModel::refreshForMe,
                        onEditProfile = viewModel::editProfile,
                        onOpenSettings = viewModel::openSettings,
                        onDismissNotice = requestsViewModel::dismissHomeNotice,
                        onSignOut = onSignOut,
                        modifier = modifier,
                    )
                }
                RequestsScreen.Create -> {
                    BackHandler { requestsViewModel.backToHome() }
                    CreateRequestScreen(
                        uiState = requestsState,
                        areas = uiState.areas,
                        onFormChange = requestsViewModel::updateForm,
                        onRetryHospitals = requestsViewModel::loadHospitals,
                        onSubmit = requestsViewModel::submit,
                        onConfirm = requestsViewModel::confirmSubmit,
                        onDismissConfirm = requestsViewModel::dismissConfirm,
                        onBack = requestsViewModel::backToHome,
                        modifier = modifier,
                    )
                }
                is RequestsScreen.DonorDetail -> {
                    BackHandler { requestsViewModel.backToHome() }
                    DonorRequestScreen(
                        uiState = requestsState,
                        onHelp = requestsViewModel::help,
                        onWithdraw = requestsViewModel::withdrawHelp,
                        onDonationAnswer = requestsViewModel::confirmDonation,
                        onReport = requestsViewModel::report,
                        onBlockRequester = requestsViewModel::blockRequester,
                        onBack = requestsViewModel::backToHome,
                        modifier = modifier,
                    )
                }
                is RequestsScreen.Detail -> {
                    BackHandler { requestsViewModel.backToHome() }
                    RequestDetailScreen(
                        uiState = requestsState,
                        onFulfill = requestsViewModel::fulfill,
                        onCancel = requestsViewModel::cancel,
                        onConfirmHelper = requestsViewModel::confirmHelper,
                        onBlockHelper = requestsViewModel::blockHelper,
                        onBack = requestsViewModel::backToHome,
                        modifier = modifier,
                    )
                }
            }
        }
        is ProfileScreen.Settings -> {
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { settingsViewModel.load() }
            val close = { viewModel.closeSettings(settingsState.profile) }
            BackHandler(enabled = !settingsState.isDeleting) { close() }
            SettingsScreen(
                uiState = settingsState,
                onRetry = settingsViewModel::load,
                onUnblock = settingsViewModel::unblock,
                onDeleteAccount = settingsViewModel::deleteAccount,
                // The account is gone: sign out and return to the start.
                onAccountDeleted = onSignOut,
                onBack = close,
                modifier = modifier,
            )
        }
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
    LoadingState(modifier)
}

@Composable
fun LoadFailedScreen(message: String, onRetry: () -> Unit, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    AppScreen(modifier = modifier, centered = true) {
        ErrorState(
            title = stringResource(R.string.load_failed_title),
            message = message,
            onRetry = onRetry,
            extra = {
                TertiaryButton(
                    text = stringResource(R.string.signed_in_button_sign_out),
                    onClick = onSignOut,
                    icon = R.drawable.ic_logout,
                )
            },
        )
    }
}

@Composable
fun HomeScreen(
    profile: Profile,
    area: Area?,
    requests: RequestsUiState,
    notificationSettings: NotificationSettings?,
    onRequestBlood: () -> Unit,
    onOpenRequest: (BloodRequest) -> Unit,
    onOpenDonorRequest: (DonorRequest) -> Unit,
    onRetryRequests: () -> Unit,
    onRetryForMe: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissNotice: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppScreen(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.home_title, profile.fullName.trim().substringBefore(' ')),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                area?.let {
                    Text(
                        stringResource(R.string.profile_area_value, it.name, it.governorate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RoundIconButton(
                icon = R.drawable.ic_settings,
                contentDescription = stringResource(R.string.home_button_settings),
                onClick = onOpenSettings,
            )
        }
        requests.homeNotice?.let { notice ->
            NoticeCard(title = notice.resolve(), tone = Tone.SUCCESS) {
                TertiaryButton(text = stringResource(R.string.button_ok), onClick = onDismissNotice)
            }
        }
        NotificationsPausedHint(profile)
        if (profile.isDonor && notificationSettings != null) {
            NotificationsOffHint(notificationSettings)
        }
        EligibilityCard(profile)
        PrimaryButton(
            text = stringResource(R.string.home_button_request_blood),
            onClick = onRequestBlood,
            large = true,
            icon = R.drawable.ic_drop,
            modifier = Modifier.fillMaxWidth(),
        )
        ForMeSection(uiState = requests, onOpen = onOpenDonorRequest, onRetry = onRetryForMe)
        MyRequestsSection(uiState = requests, onOpen = onOpenRequest, onRetry = onRetryRequests)

        SectionHeader(stringResource(R.string.home_profile_section))
        AppCard(spacing = 4.dp) {
            InfoRow(
                icon = R.drawable.ic_phone,
                title = profile.phone,
                subtitle = stringResource(R.string.profile_phone_label),
            )
            InfoRow(
                icon = R.drawable.ic_place,
                title = area?.let { stringResource(R.string.profile_area_value, it.name, it.governorate) }
                    ?: stringResource(R.string.home_not_set),
                subtitle = stringResource(R.string.profile_area_label),
            )
            InfoRow(
                icon = R.drawable.ic_event,
                title = profile.lastDonationDate?.let { formatIsoDate(it) }
                    ?: stringResource(R.string.profile_last_donation_never),
                subtitle = stringResource(R.string.profile_last_donation_label),
            )
            InfoRow(
                icon = R.drawable.ic_notifications,
                title = stringResource(if (profile.isDonor) R.string.home_notifications_on else R.string.home_notifications_off),
                subtitle = stringResource(R.string.home_notifications_label),
            )
        }
        SecondaryButton(
            text = stringResource(R.string.home_button_edit_profile),
            onClick = onEditProfile,
            icon = R.drawable.ic_edit,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            text = stringResource(R.string.home_button_settings),
            onClick = onOpenSettings,
            icon = R.drawable.ic_settings,
            modifier = Modifier.fillMaxWidth(),
        )
        TertiaryButton(
            text = stringResource(R.string.signed_in_button_sign_out),
            onClick = onSignOut,
            icon = R.drawable.ic_logout,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// Display only: the server's default donation gaps (DONATION_GAP_DAYS_* in MatchingConfig).
// The server decides who gets alerts; this only tells the donor roughly when they can give again.
private const val GAP_DAYS_MALE = 90L
private const val GAP_DAYS_OTHER = 120L

/** My blood type, and whether I can donate now or from about when. */
@Composable
private fun EligibilityCard(profile: Profile) {
    val today = LocalDate.now()
    val eligibleFrom = profile.lastDonationDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.plusDays(if (profile.sex == Sex.MALE) GAP_DAYS_MALE else GAP_DAYS_OTHER)
    val waiting = eligibleFrom != null && eligibleFrom.isAfter(today)

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BloodTypeBadge(profile.bloodType, mine = true, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (waiting) stringResource(R.string.home_eligibility_title_wait, formatDate(eligibleFrom!!))
                    else stringResource(R.string.home_eligibility_title_ready),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        waiting -> ChronoUnit.DAYS.between(today, eligibleFrom).toInt().let { days ->
                            pluralStringResource(R.plurals.home_eligibility_body_wait, days, days)
                        }
                        profile.bloodType == null -> stringResource(R.string.home_eligibility_body_no_type)
                        !profile.isDonor -> stringResource(R.string.home_eligibility_body_alerts_off)
                        else -> stringResource(R.string.home_eligibility_body_alerts, displayBloodType(profile.bloodType))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (waiting) {
            TimeLeftChip(stringResource(R.string.chip_resting))
        } else {
            SuccessChip(stringResource(R.string.chip_eligible))
        }
    }
}

/** Why this donor gets no alerts for now, and until when (no-shows with several requesters). */
@Composable
private fun NotificationsPausedHint(profile: Profile) {
    val until = profile.notificationsPausedUntil
        ?.takeIf { runCatching { Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false) }
        ?: return
    NoticeCard(
        title = stringResource(R.string.settings_notifications_paused, formatWhen(until) ?: until),
        body = stringResource(R.string.settings_notifications_paused_no_shows),
        tone = Tone.WARNING,
        icon = R.drawable.ic_notifications,
    )
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

    AppScreen(
        modifier = modifier,
        topBar = if (isNew) null else {
            { AppTopBar(title = stringResource(R.string.profile_title_edit), onBack = onCancel, backEnabled = enabled) }
        },
    ) {
        if (isNew) {
            Text(
                text = stringResource(R.string.profile_title_setup),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.profile_setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(stringResource(R.string.profile_section_about))
        OutlinedTextField(
            value = form.fullName,
            onValueChange = { v -> onFormChange { it.copy(fullName = v) } },
            label = { Text(stringResource(R.string.profile_full_name_label)) },
            isError = uiState.fullNameError != null,
            supportingText = uiState.fullNameError?.let { { Text(it.resolve()) } },
            singleLine = true,
            enabled = enabled,
            colors = appTextFieldColors(),
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
            colors = appTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        SexSelector(
            selected = form.sex,
            onSelected = { v -> onFormChange { it.copy(sex = v) } },
            error = uiState.sexError?.resolve(),
            enabled = enabled,
        )

        SectionHeader(stringResource(R.string.profile_blood_type_label))
        BloodTypePicker(
            selected = form.bloodType,
            onSelected = { v -> onFormChange { it.copy(bloodType = v) } },
            enabled = enabled,
        )

        SectionHeader(stringResource(R.string.profile_section_location))
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

        AppCard(contentPadding = 12.dp) {
            InfoRow(
                icon = R.drawable.ic_notifications,
                title = stringResource(R.string.profile_is_donor_label),
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.extendedColors.redText,
                action = {
                    Switch(
                        checked = form.isDonor,
                        onCheckedChange = { v -> onFormChange { it.copy(isDonor = v) } },
                        enabled = enabled,
                    )
                },
            )
        }

        uiState.saveError?.let { InlineMessage(it.resolve(), Tone.ERROR) }

        PrimaryButton(
            text = stringResource(R.string.profile_button_save),
            onClick = onSave,
            loading = uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isNew) {
            TertiaryButton(
                text = stringResource(R.string.signed_in_button_sign_out),
                onClick = onSignOut,
                enabled = enabled,
                icon = R.drawable.ic_logout,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            SecondaryButton(
                text = stringResource(R.string.profile_button_cancel),
                onClick = onCancel,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The 8 blood types in a 4×2 grid, plus "I don't know" (null). */
@Composable
private fun BloodTypePicker(selected: String?, onSelected: (String?) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceGrid(
            options = BLOOD_TYPES,
            columns = 4,
            selected = selected,
            onSelect = onSelected,
            label = { displayBloodType(it) },
            enabled = enabled,
            large = true,
        )
        ChoiceTile(
            text = stringResource(R.string.profile_blood_type_unknown),
            selected = selected == null,
            onClick = { onSelected(null) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SexSelector(selected: String?, onSelected: (String) -> Unit, error: String?, enabled: Boolean) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.profile_sex_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ChoiceGrid(
            options = listOf(Sex.MALE, Sex.FEMALE),
            columns = 2,
            selected = selected,
            onSelect = onSelected,
            label = { stringResource(if (it == Sex.MALE) R.string.profile_sex_male else R.string.profile_sex_female) },
            enabled = enabled,
        )
        if (error != null) InlineMessage(error, Tone.ERROR)
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
            leadingIcon = { Icon(painterResource(R.drawable.ic_place), contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = appTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            byGovernorate.forEach { (governorate, governorateAreas) ->
                Text(
                    text = governorate,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.extendedColors.redText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                governorateAreas.forEach { area ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(area.name, style = MaterialTheme.typography.bodyLarge)
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
            leadingIcon = { Icon(painterResource(R.drawable.ic_event), contentDescription = null) },
            colors = appTextFieldColors(),
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
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            DatePicker(state = pickerState, colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
        }
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
                bloodType = "O-",
                sex = Sex.MALE,
                areaId = 1,
                lastDonationDate = "2026-03-01",
                isDonor = true,
            ),
            area = Area(1, "Hamra", "Beirut", "Beirut"),
            requests = RequestsUiState(),
            notificationSettings = null,
            onRequestBlood = {},
            onOpenRequest = {},
            onOpenDonorRequest = {},
            onRetryRequests = {},
            onRetryForMe = {},
            onEditProfile = {},
            onOpenSettings = {},
            onDismissNotice = {},
            onSignOut = {},
        )
    }
}
