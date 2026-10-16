package com.jadbarakat.blooddonor.ui

import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.ApiException
import com.jadbarakat.blooddonor.data.ErrorCodes

/** A generic user-facing message for a failed API call. */
fun Exception.toUiMessage(): UiMessage = when (this) {
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
