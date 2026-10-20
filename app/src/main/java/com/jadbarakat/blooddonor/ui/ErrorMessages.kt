package com.jadbarakat.blooddonor.ui

import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.ApiException
import com.jadbarakat.blooddonor.data.ErrorCodes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A generic user-facing message for a failed API call. */
fun Exception.toUiMessage(): UiMessage = when (this) {
    is ApiException.Unreachable -> UiMessage(R.string.api_error_unreachable)
    is ApiException.NotSignedIn -> UiMessage(R.string.error_session_expired)
    is ApiException.Http -> when {
        code == ErrorCodes.EMAIL_NOT_VERIFIED -> UiMessage(R.string.api_error_email_not_verified)
        code == ErrorCodes.TOO_MANY_OPEN_REQUESTS -> tooManyOpenMessage()
        code == ErrorCodes.DAILY_LIMIT -> dailyLimitMessage()
        code == ErrorCodes.DUPLICATE_REQUEST -> UiMessage(R.string.api_error_duplicate_request)
        code == ErrorCodes.POSTING_SUSPENDED -> postingSuspendedMessage(details["until"])
        code == ErrorCodes.RATE_LIMITED -> UiMessage(R.string.api_error_rate_limited)
        code == ErrorCodes.BLOCKED -> UiMessage(R.string.api_error_blocked)
        status == 401 -> UiMessage(R.string.error_session_expired)
        status >= 500 -> UiMessage(R.string.api_error_server)
        else -> UiMessage(R.string.api_error_request, listOf(code))
    }
    else -> UiMessage(R.string.error_unknown_generic)
}

private fun ApiException.Http.tooManyOpenMessage(): UiMessage {
    val limit = details["limit"] ?: "2"
    val newAccountUntil = formatWhen(details["newAccountUntil"])
    return if (details["newAccount"] == "true" && newAccountUntil != null) {
        UiMessage(R.string.api_error_too_many_open_new_account, listOf(limit, newAccountUntil))
    } else {
        UiMessage(R.string.api_error_too_many_open, listOf(limit))
    }
}

private fun ApiException.Http.dailyLimitMessage(): UiMessage {
    val limit = details["limit"] ?: "3"
    val retryAt = formatWhen(details["retryAt"])
    return if (retryAt != null) UiMessage(R.string.api_error_daily_limit_until, listOf(limit, retryAt))
    else UiMessage(R.string.api_error_daily_limit, listOf(limit))
}

/** [until] is an ISO instant or "permanent", as in posting_suspended errors. */
fun postingSuspendedMessage(until: String?): UiMessage {
    val formatted = formatWhen(until)
    return when {
        until == "permanent" -> UiMessage(R.string.api_error_posting_suspended_permanent)
        formatted != null -> UiMessage(R.string.api_error_posting_suspended_until, listOf(formatted))
        else -> UiMessage(R.string.api_error_posting_suspended)
    }
}

/** "13:45" later today, otherwise e.g. "Jun 22, 2026, 13:45", in the phone's time zone. Null if [iso] isn't an instant. */
fun formatWhen(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val at = instant.atZone(zone)
    return if (at.toLocalDate() == now.atZone(zone).toLocalDate()) {
        at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } else {
        at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}
