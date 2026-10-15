package com.jadbarakat.blooddonor.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.jadbarakat.blooddonor.R

/** Outcome of the Google account picker, handed to [AuthViewModel.onGoogleSignInResult]. */
sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data object NoAccount : GoogleSignInResult
    data class Failure(val message: String?) : GoogleSignInResult
}

/** Shows the Google account picker and returns the selected account's ID token. Needs an Activity context. */
suspend fun requestGoogleIdToken(activity: Activity): GoogleSignInResult {
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(activity.getString(R.string.default_web_client_id))
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    return try {
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleSignInResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            GoogleSignInResult.Failure("Unexpected credential type: ${credential.type}")
        }
    } catch (e: GetCredentialCancellationException) {
        GoogleSignInResult.Cancelled
    } catch (e: NoCredentialException) {
        GoogleSignInResult.NoAccount
    } catch (e: GetCredentialException) {
        GoogleSignInResult.Failure(e.message)
    } catch (e: GoogleIdTokenParsingException) {
        GoogleSignInResult.Failure(e.message)
    }
}

/** Clears the remembered account so the picker shows again on the next sign-in. */
suspend fun clearGoogleCredentialState(context: Context) {
    try {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    } catch (e: ClearCredentialException) {
        // Sign-out must still succeed; the worst case is the picker auto-selecting next time.
    }
}
