package com.jadbarakat.blooddonor.requests

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.ApiException
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.CreateBloodRequest
import com.jadbarakat.blooddonor.data.ErrorCodes
import com.jadbarakat.blooddonor.data.Hospital
import com.jadbarakat.blooddonor.data.RequestRepository
import com.jadbarakat.blooddonor.ui.UiMessage
import com.jadbarakat.blooddonor.ui.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which requests screen is shown on top of home. */
sealed interface RequestsScreen {
    /** Home, with the "My requests" list. */
    data object Home : RequestsScreen
    data object Create : RequestsScreen
    data class Detail(val id: Long) : RequestsScreen
}

val DEADLINE_HOURS = listOf(6, 12, 24, 48, 72)
const val DEFAULT_DEADLINE_HOURS = 24
val UNITS_RANGE = 1..10
const val MAX_NOTE_LENGTH = 200

data class RequestForm(
    val hospitalId: Int? = null,
    val bloodType: String? = null,
    val component: String? = null,
    val units: Int = 1,
    val hours: Int = DEFAULT_DEADLINE_HOURS,
    val note: String = "",
)

data class RequestsUiState(
    val screen: RequestsScreen = RequestsScreen.Home,
    // Home
    val myRequests: List<BloodRequest> = emptyList(),
    val isLoadingList: Boolean = false,
    val listError: UiMessage? = null,
    // Create
    val hospitals: List<Hospital> = emptyList(),
    val isLoadingHospitals: Boolean = false,
    val hospitalsError: UiMessage? = null,
    val form: RequestForm = RequestForm(),
    val hospitalError: UiMessage? = null,
    val bloodTypeError: UiMessage? = null,
    val componentError: UiMessage? = null,
    val noteError: UiMessage? = null,
    val showConfirm: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: UiMessage? = null,
    // Detail
    val detail: BloodRequest? = null,
    val isActing: Boolean = false,
    val detailError: UiMessage? = null,
) {
    fun hospitalById(id: Int?): Hospital? = hospitals.firstOrNull { it.id == id }
}

class RequestsViewModel(
    private val repository: RequestRepository,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestsUiState())
    val uiState: StateFlow<RequestsUiState> = _uiState.asStateFlow()

    private var loadedUid: String? = null
    private var listJob: Job? = null

    /** Called whenever home appears; resets for a new user, otherwise refreshes the list. */
    fun onHomeShown() {
        val uid = auth.currentUser?.uid ?: return
        if (uid != loadedUid) {
            loadedUid = uid
            listJob?.cancel()
            _uiState.value = RequestsUiState()
        }
        refreshList()
    }

    fun refreshList() {
        listJob?.cancel()
        _uiState.update { it.copy(isLoadingList = true, listError = null) }
        listJob = viewModelScope.launch {
            try {
                val mine = repository.getMine()
                _uiState.update { it.copy(myRequests = mine) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading my requests failed", e)
                _uiState.update { it.copy(listError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isLoadingList = false) }
            }
        }
    }

    // --- Create ---

    fun openCreate() {
        _uiState.update {
            it.copy(screen = RequestsScreen.Create, form = RequestForm(), showConfirm = false).withoutFormErrors()
        }
        loadHospitals()
    }

    fun loadHospitals() {
        val state = _uiState.value
        if (state.isLoadingHospitals || state.hospitals.isNotEmpty()) return
        _uiState.update { it.copy(isLoadingHospitals = true, hospitalsError = null) }
        viewModelScope.launch {
            try {
                val hospitals = repository.getHospitals()
                _uiState.update { it.copy(hospitals = hospitals) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading hospitals failed", e)
                _uiState.update { it.copy(hospitalsError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isLoadingHospitals = false) }
            }
        }
    }

    fun updateForm(transform: (RequestForm) -> RequestForm) {
        _uiState.update { state ->
            val old = state.form
            val new = transform(old).let { it.copy(note = it.note.take(MAX_NOTE_LENGTH)) }
            state.copy(
                form = new,
                hospitalError = state.hospitalError.takeIf { new.hospitalId == old.hospitalId },
                bloodTypeError = state.bloodTypeError.takeIf { new.bloodType == old.bloodType },
                componentError = state.componentError.takeIf { new.component == old.component },
                noteError = state.noteError.takeIf { new.note == old.note },
                submitError = null,
            )
        }
    }

    /** Checks the form and, if it is complete, asks for confirmation. */
    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        val form = state.form
        val hospitalError = if (form.hospitalId == null) UiMessage(R.string.request_error_hospital_required) else null
        val bloodTypeError = if (form.bloodType == null) UiMessage(R.string.request_error_blood_type_required) else null
        val componentError = if (form.component == null) UiMessage(R.string.request_error_component_required) else null
        _uiState.update {
            it.copy(
                hospitalError = hospitalError,
                bloodTypeError = bloodTypeError,
                componentError = componentError,
                submitError = null,
                showConfirm = listOf(hospitalError, bloodTypeError, componentError).all { e -> e == null },
            )
        }
    }

    fun dismissConfirm() = _uiState.update { it.copy(showConfirm = false) }

    fun confirmSubmit() {
        val state = _uiState.value
        val form = state.form
        if (state.isSubmitting || form.hospitalId == null || form.bloodType == null || form.component == null) return
        _uiState.update { it.copy(showConfirm = false, isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                val created = repository.create(
                    CreateBloodRequest(
                        hospitalId = form.hospitalId,
                        bloodType = form.bloodType,
                        component = form.component,
                        unitsNeeded = form.units,
                        neededInHours = form.hours,
                        note = form.note.trim().ifEmpty { null },
                    )
                )
                _uiState.update {
                    it.copy(
                        screen = RequestsScreen.Detail(created.id),
                        detail = created,
                        detailError = null,
                        myRequests = listOf(created) + it.myRequests,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Http) {
                _uiState.update {
                    when (e.code) {
                        ErrorCodes.NOTE_CONTAINS_PHONE -> it.copy(noteError = UiMessage(R.string.request_error_note_phone))
                        ErrorCodes.NOTE_CONTAINS_URL -> it.copy(noteError = UiMessage(R.string.request_error_note_url))
                        ErrorCodes.INVALID_NOTE -> it.copy(noteError = UiMessage(R.string.request_error_note_too_long))
                        ErrorCodes.UNKNOWN_HOSPITAL -> it.copy(hospitalError = UiMessage(R.string.request_error_hospital_unknown))
                        ErrorCodes.PROFILE_REQUIRED -> it.copy(submitError = UiMessage(R.string.request_error_profile_required))
                        else -> it.copy(submitError = e.toUiMessage())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Creating request failed", e)
                _uiState.update { it.copy(submitError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    // --- Detail ---

    fun openDetail(request: BloodRequest) {
        _uiState.update {
            it.copy(screen = RequestsScreen.Detail(request.id), detail = request, detailError = null)
        }
        // The list item is complete; refresh it in case it changed since the list was loaded.
        viewModelScope.launch {
            try {
                val fresh = repository.get(request.id)
                _uiState.update { if (it.detail?.id == fresh.id) it.copy(detail = fresh).withUpdated(fresh) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing request ${request.id} failed", e)
            }
        }
    }

    fun fulfill() = act { repository.fulfill(it) }

    fun cancel() = act { repository.cancel(it) }

    private fun act(call: suspend (Long) -> BloodRequest) {
        val state = _uiState.value
        val id = state.detail?.id ?: return
        if (state.isActing) return
        _uiState.update { it.copy(isActing = true, detailError = null) }
        viewModelScope.launch {
            try {
                val updated = call(id)
                _uiState.update { it.copy(detail = updated).withUpdated(updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Http) {
                if (e.code == ErrorCodes.REQUEST_NOT_OPEN) {
                    // Expired or changed elsewhere: show the current state.
                    _uiState.update { it.copy(detailError = UiMessage(R.string.request_error_not_open)) }
                    runCatching { repository.get(id) }.getOrNull()?.let { fresh ->
                        _uiState.update { it.copy(detail = fresh).withUpdated(fresh) }
                    }
                } else {
                    _uiState.update { it.copy(detailError = e.toUiMessage()) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Updating request $id failed", e)
                _uiState.update { it.copy(detailError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isActing = false) }
            }
        }
    }

    /** Back from Create or Detail to home. */
    fun backToHome() {
        val state = _uiState.value
        if (state.isSubmitting || state.isActing) return
        _uiState.update { it.copy(screen = RequestsScreen.Home, showConfirm = false, detailError = null) }
    }

    private fun RequestsUiState.withUpdated(request: BloodRequest) =
        copy(myRequests = myRequests.map { if (it.id == request.id) request else it })

    private fun RequestsUiState.withoutFormErrors() = copy(
        hospitalError = null,
        bloodTypeError = null,
        componentError = null,
        noteError = null,
        submitError = null,
    )

    private companion object {
        const val TAG = "RequestsViewModel"
    }
}
