package com.jadbarakat.blooddonor.auth

import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface AuthState {
    data object SignedOut : AuthState
    data class NeedsVerification(val email: String) : AuthState
    data class SignedIn(val email: String) : AuthState
}

data class AuthUiState(
    val authState: AuthState = AuthState.SignedOut,
    val isLoading: Boolean = false,
    val error: UiMessage? = null,
    val info: UiMessage? = null,
)

class AuthViewModel(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState(authState = stateFor(auth.currentUser)))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun signUp(email: String, password: String) {
        val trimmedEmail = email.trim()
        validateEmail(trimmedEmail)?.let { return showError(it) }
        validatePassword(password)?.let { return showError(it) }
        runAuthAction {
            val user = auth.createUserWithEmailAndPassword(trimmedEmail, password).await().user
                ?: error("User is null after sign-up")
            // Move to the verify screen first so a failed send can be retried with "Resend email".
            _uiState.update { it.copy(authState = stateFor(user)) }
            user.sendEmailVerification().await()
        }
    }

    fun logIn(email: String, password: String) {
        val trimmedEmail = email.trim()
        validateEmail(trimmedEmail)?.let { return showError(it) }
        validatePassword(password)?.let { return showError(it) }
        runAuthAction {
            val user = auth.signInWithEmailAndPassword(trimmedEmail, password).await().user
            _uiState.update { it.copy(authState = stateFor(user)) }
        }
    }

    fun onGoogleSignInResult(result: GoogleSignInResult) {
        when (result) {
            is GoogleSignInResult.Success -> signInWithGoogle(result.idToken)
            GoogleSignInResult.Cancelled -> clearMessages()
            GoogleSignInResult.NoAccount -> showError(R.string.error_google_no_account)
            is GoogleSignInResult.Failure -> _uiState.update {
                it.copy(
                    error = result.message?.let { msg -> UiMessage(R.string.error_unknown, listOf(msg)) }
                        ?: UiMessage(R.string.error_unknown_generic),
                    info = null,
                )
            }
        }
    }

    private fun signInWithGoogle(idToken: String) {
        runAuthAction {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val user = auth.signInWithCredential(credential).await().user
                ?: error("User is null after Google sign-in")
            // Google accounts are verified by Google, so skip the verify screen.
            _uiState.update { it.copy(authState = AuthState.SignedIn(user.email.orEmpty())) }
        }
    }

    fun checkVerified() {
        val user = auth.currentUser ?: return signOut()
        runAuthAction {
            user.reload().await()
            val refreshed = auth.currentUser
            val newState = stateFor(refreshed)
            _uiState.update {
                it.copy(
                    authState = newState,
                    info = if (newState is AuthState.NeedsVerification) {
                        UiMessage(R.string.verify_still_unverified)
                    } else null,
                )
            }
        }
    }

    fun resendVerification() {
        val user = auth.currentUser ?: return signOut()
        runAuthAction {
            user.sendEmailVerification().await()
            _uiState.update {
                it.copy(info = UiMessage(R.string.verify_resent, listOf(user.email.orEmpty())))
            }
        }
    }

    fun sendPasswordReset(email: String) {
        val trimmedEmail = email.trim()
        validateEmail(trimmedEmail)?.let { return showError(it) }
        runAuthAction {
            auth.sendPasswordResetEmail(trimmedEmail).await()
            _uiState.update { it.copy(info = UiMessage(R.string.auth_password_reset_sent)) }
        }
    }

    fun signOut() {
        auth.signOut()
        _uiState.value = AuthUiState(authState = AuthState.SignedOut)
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, info = null) }
    }

    private fun runAuthAction(block: suspend () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.toUiMessage()) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun showError(@StringRes resId: Int) {
        _uiState.update { it.copy(error = UiMessage(resId), info = null) }
    }

    private fun validateEmail(email: String): Int? =
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            R.string.error_invalid_email
        } else null

    private fun validatePassword(password: String): Int? =
        if (password.length < MIN_PASSWORD_LENGTH) R.string.error_password_too_short else null

    private fun stateFor(user: FirebaseUser?): AuthState = when {
        user == null -> AuthState.SignedOut
        user.isEmailVerified || user.hasGoogleProvider() -> AuthState.SignedIn(user.email.orEmpty())
        else -> AuthState.NeedsVerification(user.email.orEmpty())
    }

    private fun FirebaseUser.hasGoogleProvider(): Boolean =
        providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }

    private fun Exception.toUiMessage(): UiMessage = when (this) {
        is FirebaseAuthUserCollisionException -> when (errorCode) {
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
                UiMessage(R.string.error_google_account_collision)
            else -> UiMessage(R.string.error_email_in_use)
        }
        is FirebaseAuthWeakPasswordException -> UiMessage(R.string.error_weak_password)
        is FirebaseAuthInvalidUserException -> when (errorCode) {
            "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN" -> UiMessage(R.string.error_session_expired)
            else -> UiMessage(R.string.error_user_not_found)
        }
        is FirebaseAuthInvalidCredentialsException -> when (errorCode) {
            "ERROR_INVALID_EMAIL" -> UiMessage(R.string.error_invalid_email)
            else -> UiMessage(R.string.error_invalid_credentials)
        }
        is FirebaseNetworkException -> UiMessage(R.string.error_network)
        is FirebaseTooManyRequestsException -> UiMessage(R.string.error_too_many_requests)
        else -> localizedMessage?.let { UiMessage(R.string.error_unknown, listOf(it)) }
            ?: UiMessage(R.string.error_unknown_generic)
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
