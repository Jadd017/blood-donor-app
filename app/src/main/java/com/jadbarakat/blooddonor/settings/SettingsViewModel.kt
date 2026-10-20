package com.jadbarakat.blooddonor.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jadbarakat.blooddonor.data.AccountRepository
import com.jadbarakat.blooddonor.data.BlockedUser
import com.jadbarakat.blooddonor.data.Profile
import com.jadbarakat.blooddonor.data.ProfileRepository
import com.jadbarakat.blooddonor.ui.UiMessage
import com.jadbarakat.blooddonor.ui.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The word the user must type to delete their account. */
const val DELETE_CONFIRMATION_WORD = "DELETE"

data class SettingsUiState(
    /** Fresh from the server, for the notification and posting status. */
    val profile: Profile? = null,
    val blocks: List<BlockedUser> = emptyList(),
    val isLoading: Boolean = false,
    val loadError: UiMessage? = null,
    /** Users being unblocked right now. */
    val unblocking: Set<Long> = emptySet(),
    val blocksError: UiMessage? = null,
    val isDeleting: Boolean = false,
    val deleteError: UiMessage? = null,
    /** Set once the server deleted the account; the UI then signs out. */
    val accountDeleted: Boolean = false,
)

class SettingsViewModel(
    private val profiles: ProfileRepository,
    private val accounts: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Called each time the screen opens. */
    fun load() {
        loadJob?.cancel()
        _uiState.value = SettingsUiState(isLoading = true)
        loadJob = viewModelScope.launch {
            try {
                val (profile, blocks) = coroutineScope {
                    val me = async { profiles.getMe() }
                    val blocks = async { accounts.getBlocks() }
                    me.await() to blocks.await()
                }
                _uiState.update { it.copy(profile = profile, blocks = blocks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading settings failed", e)
                _uiState.update { it.copy(loadError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun unblock(userId: Long) {
        if (userId in _uiState.value.unblocking) return
        _uiState.update { it.copy(unblocking = it.unblocking + userId, blocksError = null) }
        viewModelScope.launch {
            try {
                accounts.unblock(userId)
                _uiState.update { state -> state.copy(blocks = state.blocks.filterNot { it.userId == userId }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unblocking $userId failed", e)
                _uiState.update { it.copy(blocksError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(unblocking = it.unblocking - userId) }
            }
        }
    }

    /** Deletes the account on the server. Retrying after a failure is safe. */
    fun deleteAccount() {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isDeleting = true, deleteError = null) }
        viewModelScope.launch {
            try {
                accounts.deleteAccount()
                _uiState.update { it.copy(accountDeleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deleting the account failed", e)
                _uiState.update { it.copy(deleteError = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isDeleting = false) }
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
