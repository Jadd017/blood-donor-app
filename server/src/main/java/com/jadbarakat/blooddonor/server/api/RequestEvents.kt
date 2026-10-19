package com.jadbarakat.blooddonor.server.api

/** Something happened to a request that people should be told about. */
sealed interface RequestEvent {
    val requestId: Long

    data class DonorCommitted(override val requestId: Long, val donorBloodType: String) : RequestEvent
    data class DonorWithdrew(override val requestId: Long) : RequestEvent
    /** Fulfilled, cancelled or expired. */
    data class RequestClosed(override val requestId: Long) : RequestEvent
}

/** Told about every [RequestEvent], e.g. to send push notifications. Must not block. */
fun interface RequestEventListener {
    fun onEvent(event: RequestEvent)
}
