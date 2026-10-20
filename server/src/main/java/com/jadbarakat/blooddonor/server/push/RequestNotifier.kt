package com.jadbarakat.blooddonor.server.push

import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.DeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.PledgeRepository
import com.jadbarakat.blooddonor.server.data.RequestStatus
import java.time.Clock
import java.time.Duration

/**
 * Status notifications about a request: to the requester when a donor commits or withdraws, or
 * when a helper's arrival window ends unconfirmed, and to committed donors when it closes.
 * Payloads never contain the note or any phone number.
 */
class RequestNotifier(
    private val requests: BloodRequestRepository,
    private val pledges: PledgeRepository,
    private val tokens: DeviceTokenRepository,
    private val push: PushSender,
    private val clock: Clock,
    private val arrivalWindow: Duration = Duration.ofHours(3),
) {
    suspend fun handle(event: RequestEvent) {
        val request = requests.findById(event.requestId)?.effective(clock.instant()) ?: return
        when (event) {
            is RequestEvent.DonorCommitted -> send(listOf(request.requesterId), donorCommitted(request, event.donorBloodType))
            is RequestEvent.DonorWithdrew -> send(listOf(request.requesterId), donorWithdrew(request))
            is RequestEvent.HelperNoShow -> Unit
            is RequestEvent.RequestClosed -> {
                if (request.status == RequestStatus.OPEN) return
                send(pledges.committedDonorIds(request.id), requestClosed(request))
            }
        }
    }

    /**
     * "Did <first name> donate?" to the requester, once per pledge whose arrival window ended
     * without a confirmation. Returns how many reminders were claimed.
     */
    suspend fun sendHelperReminders(): Int {
        val due = pledges.claimDueReminders(clock.instant(), arrivalWindow)
        for (reminder in due) {
            val request = requests.findById(reminder.requestId) ?: continue
            send(listOf(reminder.requesterId), confirmHelper(request, reminder.donorFullName))
        }
        return due.size
    }

    private suspend fun send(userIds: List<Long>, message: PushMessage) {
        val all = tokens.tokensFor(userIds).values.flatten()
        if (all.isEmpty()) return
        push.send(all, message).invalid.takeIf { it.isNotEmpty() }?.let { tokens.deleteTokens(it) }
    }

    private fun update(request: BloodRequest, type: String, view: String, title: String, body: String) = PushMessage(
        title = title,
        body = body,
        data = mapOf("type" to type, "view" to view, "requestId" to request.id.toString()),
        expiresAt = clock.instant().plus(Duration.ofDays(1)),
        channelId = REQUEST_UPDATES_CHANNEL,
    )

    fun donorCommitted(request: BloodRequest, donorBloodType: String) = update(
        request, "donor_committed", VIEW_REQUESTER,
        title = "A donor is on the way",
        body = "$donorBloodType donor for your request at ${request.hospitalName}",
    )

    fun donorWithdrew(request: BloodRequest) = update(
        request, "donor_withdrew", VIEW_REQUESTER,
        title = "A donor can no longer come",
        body = "Your request at ${request.hospitalName}",
    )

    fun confirmHelper(request: BloodRequest, donorFullName: String) = update(
        request, "confirm_helper", VIEW_REQUESTER,
        title = "Did ${donorFullName.trim().split(Regex("\\s+")).first()} donate?",
        body = "Confirm in the app.",
    )

    fun requestClosed(request: BloodRequest) =
        if (request.status == RequestStatus.FULFILLED) {
            update(
                request, "request_fulfilled", VIEW_DONOR,
                title = "Thank you!",
                body = "The request at ${request.hospitalName} is fulfilled.",
            )
        } else {
            update(
                request, "request_closed", VIEW_DONOR,
                title = "Request closed",
                body = "The request at ${request.hospitalName} is closed. Thank you for offering to help.",
            )
        }

    private companion object {
        const val VIEW_REQUESTER = "requester"
        const val VIEW_DONOR = "donor"
    }
}
