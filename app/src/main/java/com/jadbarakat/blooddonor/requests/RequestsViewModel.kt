package com.jadbarakat.blooddonor.requests

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.AccountRepository
import com.jadbarakat.blooddonor.data.ApiException
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.CreateBloodRequest
import com.jadbarakat.blooddonor.data.DonorRequest
import com.jadbarakat.blooddonor.data.ErrorCodes
import com.jadbarakat.blooddonor.data.Hospital
import com.jadbarakat.blooddonor.data.MAX_REPORT_DETAILS
import com.jadbarakat.blooddonor.data.RequestRepository
import com.jadbarakat.blooddonor.notifications.DeviceTokenManager
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

    /** A request someone else posted, as seen by a donor who was notified about it. */
    data class DonorDetail(val id: Long) : RequestsScreen
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
    val forMe: List<DonorRequest> = emptyList(),
    val isLoadingForMe: Boolean = false,
    val forMeError: UiMessage? = null,
    /** A one-off note on home, e.g. after blocking a requester. */
    val homeNotice: UiMessage? = null,
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
    val detailInfo: UiMessage? = null,
    /** Helpers I blocked from this screen (by donor id), so their Block button shows it. */
    val blockedHelperIds: Set<Long> = emptySet(),
    // Donor detail
    val donorDetail: DonorRequest? = null,
    val isLoadingDonorDetail: Boolean = false,
    val donorDetailError: UiMessage? = null,
    val donorDetailInfo: UiMessage? = null,
    /** Committing, withdrawing, answering "Did you donate?", reporting or blocking. */
    val isDonorActing: Boolean = false,
) {
    fun hospitalById(id: Int?): Hospital? = hospitals.firstOrNull { it.id == id }
}

class RequestsViewModel(
    private val repository: RequestRepository,
    private val accounts: AccountRepository,
    private val deviceTokens: DeviceTokenManager,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestsUiState())
    val uiState: StateFlow<RequestsUiState> = _uiState.asStateFlow()

    private var loadedUid: String? = null
    private var listJob: Job? = null
    private var forMeJob: Job? = null
    private var donorDetailJob: Job? = null

    /** Called whenever home appears (the profile exists by then); resets for a new user, otherwise refreshes. */
    fun onHomeShown() {
        val uid = auth.currentUser?.uid ?: return
        if (uid != loadedUid) {
            loadedUid = uid
            listJob?.cancel()
            forMeJob?.cancel()
            _uiState.value = RequestsUiState()
            // Once per user per app start; token refreshes are handled by the messaging service.
            viewModelScope.launch { deviceTokens.register() }
        }
        refreshList()
        refreshForMe()
    }

    fun refreshForMe() {
        forMeJob?.cancel()
        _uiState.update { it.copy(isLoadingForMe = true, forMeError = null) }
        forMeJob = viewModelScope.launch {
            try {
                val forMe = repository.getForMe()
                _uiState.update { it.copy(forMe = forMe) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading requests for me failed", e)
                _uiState.update { it.copy(forMeError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isLoadingForMe = false) }
            }
        }
    }

    /** Opens the donor view of a request, e.g. from the home list or a tapped notification. */
    fun openDonorRequest(id: Long) {
        val known = _uiState.value.forMe.firstOrNull { it.id == id }
        _uiState.update {
            it.copy(
                screen = RequestsScreen.DonorDetail(id),
                donorDetail = known,
                isLoadingDonorDetail = known == null,
                donorDetailError = null,
                donorDetailInfo = null,
            )
        }
        donorDetailJob?.cancel()
        donorDetailJob = viewModelScope.launch {
            try {
                val fresh = repository.getAsDonor(id)
                _uiState.update { if (it.screen == RequestsScreen.DonorDetail(id)) it.copy(donorDetail = fresh) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading request $id for a donor failed", e)
                val message = if (e is ApiException.Http && e.code == ErrorCodes.REQUEST_NOT_FOUND) {
                    UiMessage(R.string.donor_request_not_available)
                } else {
                    e.toUiMessage()
                }
                _uiState.update { if (it.screen == RequestsScreen.DonorDetail(id)) it.copy(donorDetailError = message) else it }
            } finally {
                _uiState.update { it.copy(isLoadingDonorDetail = false) }
            }
        }
    }

    /** "I can help" on the open donor view. */
    fun help() = donorAct("Committing") { id ->
        val pledge = repository.help(id)
        _uiState.update { state ->
            state.copy(
                donorDetail = state.donorDetail?.takeIf { it.id == id }?.copy(myPledge = pledge, committed = true),
                forMe = state.forMe.map { if (it.id == id) it.copy(committed = true) else it },
            )
        }
    }

    /** "I can't make it anymore". */
    fun withdrawHelp() = donorAct("Withdrawing") { id ->
        repository.withdrawHelp(id)
        _uiState.update { state ->
            state.copy(
                donorDetail = state.donorDetail?.takeIf { it.id == id }?.copy(myPledge = null, committed = false),
                forMe = state.forMe.map { if (it.id == id) it.copy(committed = false) else it },
            )
        }
    }

    /** Answers "Did you donate?". */
    fun confirmDonation(donated: Boolean) = donorAct("Confirming donation") { id ->
        val updated = repository.confirmDonation(id, donated)
        _uiState.update { if (it.donorDetail?.id == id) it.copy(donorDetail = updated) else it }
        refreshForMe()
    }

    /** Reports the open donor view's request; [details] is optional. */
    fun report(reason: String, details: String) = donorAct("Reporting") { id ->
        repository.report(id, reason, details.trim().take(MAX_REPORT_DETAILS).ifEmpty { null })
        _uiState.update { it.copy(donorDetailInfo = UiMessage(R.string.report_sent)) }
    }

    /** Blocks whoever posted the open donor view's request, then goes home, where it no longer shows. */
    fun blockRequester() = donorAct("Blocking the requester") { id ->
        repository.blockRequester(id)
        _uiState.update { state ->
            state.copy(
                screen = RequestsScreen.Home,
                homeNotice = UiMessage(R.string.block_requester_done),
                forMe = state.forMe.filterNot { it.id == id && !it.committed },
            )
        }
        refreshForMe()
    }

    fun dismissHomeNotice() = _uiState.update { it.copy(homeNotice = null) }

    private fun donorAct(what: String, call: suspend (Long) -> Unit) {
        val state = _uiState.value
        val id = (state.screen as? RequestsScreen.DonorDetail)?.id ?: return
        if (state.isDonorActing) return
        _uiState.update { it.copy(isDonorActing = true, donorDetailError = null, donorDetailInfo = null) }
        viewModelScope.launch {
            try {
                call(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Http) {
                val message = when (e.code) {
                    ErrorCodes.OWN_REQUEST -> UiMessage(R.string.donor_error_own_request)
                    ErrorCodes.NOT_ELIGIBLE -> UiMessage(R.string.donor_error_not_eligible)
                    ErrorCodes.DONATION_TOO_RECENT -> UiMessage(R.string.donor_error_too_recent)
                    ErrorCodes.PLEDGE_LIMIT -> UiMessage(R.string.donor_error_pledge_limit)
                    ErrorCodes.TOO_EARLY -> UiMessage(R.string.donor_error_too_early)
                    ErrorCodes.REQUEST_NOT_OPEN -> UiMessage(R.string.request_error_not_open)
                    ErrorCodes.NO_PLEDGE, ErrorCodes.PLEDGE_CLOSED -> UiMessage(R.string.donor_error_changed)
                    ErrorCodes.ALREADY_REPORTED -> UiMessage(R.string.report_error_already)
                    else -> e.toUiMessage()
                }
                _uiState.update { it.copy(donorDetailError = message) }
                // The request or pledge may have changed elsewhere: show the current state.
                runCatching { repository.getAsDonor(id) }.getOrNull()?.let { fresh ->
                    _uiState.update { if (it.screen == RequestsScreen.DonorDetail(id)) it.copy(donorDetail = fresh) else it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$what for request $id failed", e)
                _uiState.update { it.copy(donorDetailError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isDonorActing = false) }
            }
        }
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
            it.copy(screen = RequestsScreen.Detail(request.id), detail = request, detailError = null, detailInfo = null)
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

    /** Opens my own request by id, e.g. from a tapped notification. */
    fun openOwnRequest(id: Long) {
        val known = _uiState.value.myRequests.firstOrNull { it.id == id }
        _uiState.update { it.copy(screen = RequestsScreen.Detail(id), detail = known, detailError = null, detailInfo = null) }
        viewModelScope.launch {
            try {
                val fresh = repository.get(id)
                _uiState.update { if (it.screen == RequestsScreen.Detail(id)) it.copy(detail = fresh).withUpdated(fresh) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading request $id failed", e)
                _uiState.update { if (it.screen == RequestsScreen.Detail(id)) it.copy(detailError = e.toUiMessage()) else it }
            }
        }
    }

    fun fulfill() = act { repository.fulfill(it) }

    fun cancel() = act { repository.cancel(it) }

    /** "Donated" or "Didn't come" for one helper ([com.jadbarakat.blooddonor.data.HelperOutcome]). */
    fun confirmHelper(pledgeId: Long, outcome: String) = act { repository.confirmHelper(it, pledgeId, outcome) }

    /** Blocks a helper of my request: they won't be notified about my requests or able to help again. */
    fun blockHelper(donorId: Long) {
        val state = _uiState.value
        if (state.isActing) return
        _uiState.update { it.copy(isActing = true, detailError = null, detailInfo = null) }
        viewModelScope.launch {
            try {
                accounts.block(donorId)
                _uiState.update {
                    it.copy(blockedHelperIds = it.blockedHelperIds + donorId, detailInfo = UiMessage(R.string.block_helper_done))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Blocking helper $donorId failed", e)
                _uiState.update { it.copy(detailError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isActing = false) }
            }
        }
    }

    private fun act(call: suspend (Long) -> BloodRequest) {
        val state = _uiState.value
        val id = state.detail?.id ?: return
        if (state.isActing) return
        _uiState.update { it.copy(isActing = true, detailError = null, detailInfo = null) }
        viewModelScope.launch {
            try {
                val updated = call(id)
                _uiState.update { it.copy(detail = updated).withUpdated(updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Http) {
                val changed = when (e.code) {
                    ErrorCodes.REQUEST_NOT_OPEN -> UiMessage(R.string.request_error_not_open)
                    ErrorCodes.HELPER_NOT_FOUND, ErrorCodes.HELPER_WITHDREW, ErrorCodes.HELPER_ALREADY_CONFIRMED ->
                        UiMessage(R.string.request_error_helper_changed)
                    else -> null
                }
                if (changed != null) {
                    // Expired or changed elsewhere: show the current state.
                    _uiState.update { it.copy(detailError = changed) }
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
        if (state.isSubmitting || state.isActing || state.isDonorActing) return
        _uiState.update {
            it.copy(
                screen = RequestsScreen.Home,
                showConfirm = false,
                detailError = null,
                detailInfo = null,
                donorDetailError = null,
                donorDetailInfo = null,
            )
        }
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
