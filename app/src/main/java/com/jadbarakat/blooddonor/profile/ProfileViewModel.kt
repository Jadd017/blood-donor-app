package com.jadbarakat.blooddonor.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.ApiException
import com.jadbarakat.blooddonor.data.Area
import com.jadbarakat.blooddonor.data.ErrorCodes
import com.jadbarakat.blooddonor.data.Profile
import com.jadbarakat.blooddonor.data.ProfileRepository
import com.jadbarakat.blooddonor.data.ProfileRequest
import com.jadbarakat.blooddonor.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Which screen to show once the user is signed in. */
sealed interface ProfileScreen {
    data object Loading : ProfileScreen

    /** Loading failed; [message] explains why and the UI offers Retry. */
    data class LoadFailed(val message: UiMessage) : ProfileScreen

    data class Home(val profile: Profile) : ProfileScreen

    /** The profile form; [original] is the saved profile when editing, null for first-time setup. */
    data class Form(val original: Profile?) : ProfileScreen {
        val isNew: Boolean get() = original == null
    }
}

data class ProfileForm(
    val fullName: String = "",
    val phone: String = "",
    val bloodType: String? = null, // null = "I don't know"
    val sex: String? = null,
    val areaId: Int? = null,
    val lastDonationDate: LocalDate? = null, // null = never / don't remember
    val isDonor: Boolean = true,
)

data class ProfileUiState(
    val screen: ProfileScreen = ProfileScreen.Loading,
    val form: ProfileForm = ProfileForm(),
    val areas: List<Area> = emptyList(),
    val isSaving: Boolean = false,
    val fullNameError: UiMessage? = null,
    val phoneError: UiMessage? = null,
    val sexError: UiMessage? = null,
    val areaError: UiMessage? = null,
    val saveError: UiMessage? = null,
) {
    fun areaById(id: Int?): Area? = areas.firstOrNull { it.id == id }
}

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var loadedUid: String? = null
    private var loadJob: Job? = null

    /** Called whenever the signed-in content appears; loads once per user. */
    fun onSignedIn() {
        val uid = auth.currentUser?.uid ?: return
        if (uid == loadedUid) return
        loadedUid = uid
        _uiState.value = ProfileUiState()
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(screen = ProfileScreen.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val (profile, areas) = coroutineScope {
                    val me = async { repository.getMe() }
                    val areas = async { repository.getAreas() }
                    me.await() to areas.await()
                }
                val sortedAreas = areas.sortedWith(compareBy({ it.governorate }, { it.name }))
                _uiState.update {
                    if (profile != null) {
                        it.copy(areas = sortedAreas, screen = ProfileScreen.Home(profile))
                    } else {
                        it.copy(
                            areas = sortedAreas,
                            form = ProfileForm(fullName = auth.currentUser?.displayName.orEmpty().trim()),
                            screen = ProfileScreen.Form(original = null),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(screen = ProfileScreen.LoadFailed(e.toUiMessage())) }
            }
        }
    }

    fun editProfile() {
        val profile = (_uiState.value.screen as? ProfileScreen.Home)?.profile ?: return
        _uiState.update {
            it.copy(
                form = profile.toForm(),
                screen = ProfileScreen.Form(original = profile),
            ).withoutErrors()
        }
    }

    fun cancelEdit() {
        val state = _uiState.value
        if (state.isSaving) return
        val original = (state.screen as? ProfileScreen.Form)?.original ?: return
        _uiState.update { it.copy(screen = ProfileScreen.Home(original)).withoutErrors() }
    }

    fun updateForm(transform: (ProfileForm) -> ProfileForm) {
        _uiState.update { state ->
            val old = state.form
            val new = transform(old)
            state.copy(
                form = new,
                fullNameError = state.fullNameError.takeIf { new.fullName == old.fullName },
                phoneError = state.phoneError.takeIf { new.phone == old.phone },
                sexError = state.sexError.takeIf { new.sex == old.sex },
                areaError = state.areaError.takeIf { new.areaId == old.areaId },
                saveError = null,
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.screen !is ProfileScreen.Form) return
        val form = state.form
        val fullNameError = if (form.fullName.trim().length < 2) UiMessage(R.string.profile_error_name_required) else null
        val phoneError = if (form.phone.isBlank()) UiMessage(R.string.profile_error_phone_required) else null
        val sexError = if (form.sex == null) UiMessage(R.string.profile_error_sex_required) else null
        val areaError = if (form.areaId == null) UiMessage(R.string.profile_error_area_required) else null
        if (listOf(fullNameError, phoneError, sexError, areaError).any { it != null }) {
            _uiState.update {
                it.copy(
                    fullNameError = fullNameError,
                    phoneError = phoneError,
                    sexError = sexError,
                    areaError = areaError,
                    saveError = null,
                )
            }
            return
        }

        _uiState.update { it.copy(isSaving = true).withoutErrors() }
        viewModelScope.launch {
            try {
                val saved = repository.saveMe(form.toRequest())
                _uiState.update { it.copy(screen = ProfileScreen.Home(saved)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Http) {
                _uiState.update {
                    when (e.code) {
                        ErrorCodes.PHONE_TAKEN -> it.copy(phoneError = UiMessage(R.string.profile_error_phone_taken))
                        ErrorCodes.INVALID_PHONE -> it.copy(phoneError = UiMessage(R.string.profile_error_phone_invalid))
                        ErrorCodes.INVALID_FULL_NAME -> it.copy(fullNameError = UiMessage(R.string.profile_error_name_invalid))
                        else -> it.copy(saveError = e.toUiMessage())
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(saveError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun ProfileUiState.withoutErrors() = copy(
        fullNameError = null,
        phoneError = null,
        sexError = null,
        areaError = null,
        saveError = null,
    )

    private fun Profile.toForm() = ProfileForm(
        fullName = fullName,
        phone = phone,
        bloodType = bloodType,
        sex = sex,
        areaId = areaId,
        lastDonationDate = lastDonationDate?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                null
            }
        },
        isDonor = isDonor,
    )

    private fun ProfileForm.toRequest() = ProfileRequest(
        fullName = fullName.trim(),
        phone = phone.trim(),
        bloodType = bloodType,
        sex = sex,
        areaId = areaId,
        lastDonationDate = lastDonationDate?.toString(), // LocalDate.toString() is ISO yyyy-MM-dd
        isDonor = isDonor,
    )

    private fun Exception.toUiMessage(): UiMessage = when (this) {
        is ApiException.Unreachable -> UiMessage(R.string.api_error_unreachable)
        is ApiException.NotSignedIn -> UiMessage(R.string.error_session_expired)
        is ApiException.Http -> when {
            code == ErrorCodes.EMAIL_NOT_VERIFIED -> UiMessage(R.string.api_error_email_not_verified)
            status == 401 -> UiMessage(R.string.error_session_expired)
            status >= 500 -> UiMessage(R.string.api_error_server)
            else -> UiMessage(R.string.api_error_request, listOf(code))
        }
        else -> UiMessage(R.string.error_unknown_generic)
    }
}
