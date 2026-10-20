package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.BlockEntry
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.DELETED_USER_ID
import com.jadbarakat.blooddonor.server.data.Helper
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.UserProfile
import com.jadbarakat.blooddonor.server.safety.Suspension
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class AreaResponse(val id: Int, val name: String, val district: String, val governorate: String)

/** Body of PUT /me. Unknown fields (e.g. "email") are ignored on purpose. */
@Serializable
data class ProfileRequest(
    val fullName: String,
    val phone: String,
    val bloodType: String? = null,
    val sex: String? = null,
    val areaId: Int? = null,
    val lastDonationDate: String? = null, // ISO date, e.g. 2026-03-01
    val isDonor: Boolean = true,
)

@Serializable
data class ProfileResponse(
    val email: String,
    val fullName: String,
    val phone: String,
    val bloodType: String? = null,
    val sex: String? = null,
    val areaId: Int? = null,
    val lastDonationDate: String? = null,
    val isDonor: Boolean,
    /** Set while posting is suspended because of strikes (ISO instant); see [postingSuspendedPermanent]. */
    val postingSuspendedUntil: String? = null,
    val postingSuspendedPermanent: Boolean = false,
    /** Set while notifications are paused (ISO instant), with the reason (one of [PauseReason]). */
    val notificationsPausedUntil: String? = null,
    val notificationsPausedReason: String? = null,
)

object PauseReason {
    /** No-shows confirmed by several different requesters. */
    const val NO_SHOWS = "no_shows"
}

/** Deliberately small (no coordinates): the app downloads the whole list. */
@Serializable
data class HospitalResponse(val id: Int, val name: String, val areaId: Int)

/** Body of POST /requests. The deadline is relative so the phone's clock doesn't matter. */
@Serializable
data class CreateBloodRequestBody(
    val hospitalId: Int,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededInHours: Int,
    val note: String? = null,
)

@Serializable
data class BloodRequestResponse(
    val id: Long,
    val hospitalId: Int,
    val hospitalName: String,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededBy: String, // ISO instant, e.g. 2026-06-15T09:00:00Z
    val note: String? = null,
    val status: String,
    val createdAt: String,
    val closedAt: String? = null,
    /** True if the request was hidden after reports from several users. */
    val hidden: Boolean = false,
    /** Only in the owner's view: donors who committed and didn't withdraw, with their phones. */
    val helpers: List<HelperResponse>? = null,
    /** Only with [helpers]: how many donors count as helping right now ([confirmedDonatedCount] + [onTheWayCount]). */
    val committedCount: Int? = null,
    /** Only with [helpers]: donations the requester confirmed. */
    val confirmedDonatedCount: Int? = null,
    /** Only with [helpers]: unconfirmed donors still within their arrival window. */
    val onTheWayCount: Int? = null,
)

/** A donor helping with the owner's request. Only ever sent to the owner of that request. */
@Serializable
data class HelperResponse(
    val pledgeId: Long,
    /** For POST /blocks/{userId}. Absent when the donor deleted their account. */
    val donorId: Long? = null,
    val fullName: String,
    val bloodType: String? = null,
    val phone: String,
    /** The donor's side: committed, or what they answered to "Did you donate?" (donated, not_donated). */
    val status: String,
    val committedAt: String,
    /** When the donor stops counting unless the requester confirms them. */
    val arrivalEndsAt: String,
    /** The requester's side: one of [HelperState]. */
    val state: String,
)

object HelperState {
    /** The requester confirmed the donation. */
    const val DONATED = "donated"
    /** The requester said the donor didn't come. */
    const val NO_SHOW = "no_show"
    /** Unconfirmed and still within the arrival window: counts as helping. */
    const val ON_THE_WAY = "on_the_way"
    /** Unconfirmed, and the window ended (or the donor said they didn't donate): no longer counts. */
    const val AWAITING_CONFIRMATION = "awaiting_confirmation"
}

/** Body of POST /requests/{id}/helpers/{pledgeId}/confirm. [outcome] is "donated" or "no_show". */
@Serializable
data class HelperConfirmBody(val outcome: String)

/** Body of PUT and DELETE /me/device-token. */
@Serializable
data class DeviceTokenBody(val token: String)

/** What a notified donor sees about a request: nothing about the requester. */
@Serializable
data class DonorRequestResponse(
    val id: Long,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val hospitalName: String,
    val areaName: String,
    val neededBy: String,
    val note: String? = null,
    val status: String,
    /** Only in GET /requests/for-me: whether I have a committed pledge for it. */
    val committed: Boolean? = null,
    /** Only in GET /requests/{id}, when I committed (and didn't withdraw). */
    val myPledge: PledgeResponse? = null,
)

/** What a committed donor needs to get there. Never the requester's phone. */
@Serializable
data class PledgeResponse(
    val status: String,
    val committedAt: String,
    /** True once the donor may answer "Did you donate?". */
    val canConfirmDonation: Boolean,
    val requesterFirstName: String,
    val hospitalName: String,
    val areaName: String,
    val hospitalLatitude: Double? = null,
    val hospitalLongitude: Double? = null,
    val neededBy: String,
)

/** Body of POST /requests/{id}/donation. */
@Serializable
data class DonationBody(val donated: Boolean)

/** Body of POST /requests/{id}/report. [reason] is fake, money, inappropriate or other. */
@Serializable
data class ReportBody(val reason: String, val details: String? = null)

/**
 * Someone I blocked. [fullName] only for people I already knew (helpers on my requests); a
 * requester blocked from a donor view is shown by [requestHospitalName] and [requestCreatedAt].
 */
@Serializable
data class BlockResponse(
    val userId: Long,
    val fullName: String? = null,
    val requestHospitalName: String? = null,
    val requestCreatedAt: String? = null,
    val createdAt: String,
)

fun Area.toResponse() = AreaResponse(id, name, district, governorate)

/** [suspension] is the current posting suspension; a notification pause is only shown while it lasts at [now]. */
fun UserProfile.toResponse(suspension: Suspension?, now: Instant): ProfileResponse {
    val pausedUntil = notificationsPausedUntil?.takeIf { it.isAfter(now) }
    return ProfileResponse(
        email = email,
        fullName = fullName,
        phone = phone,
        bloodType = bloodType,
        sex = sex,
        areaId = areaId,
        lastDonationDate = lastDonationDate?.toString(),
        isDonor = isDonor,
        postingSuspendedUntil = suspension?.until?.toString(),
        postingSuspendedPermanent = suspension?.permanent == true,
        notificationsPausedUntil = pausedUntil?.toString(),
        notificationsPausedReason = pausedUntil?.let { PauseReason.NO_SHOWS },
    )
}

fun Hospital.toResponse() = HospitalResponse(id, name, areaId)

fun BloodRequest.toResponse() = BloodRequestResponse(
    id = id,
    hospitalId = hospitalId,
    hospitalName = hospitalName,
    bloodType = bloodType,
    component = component,
    unitsNeeded = unitsNeeded,
    neededBy = neededBy.toString(),
    note = note,
    status = status,
    createdAt = createdAt.toString(),
    closedAt = closedAt?.toString(),
    hidden = hidden,
)

fun Helper.toResponse(arrivalEndsAt: Instant, state: String) = HelperResponse(
    pledgeId = pledgeId,
    donorId = donorId.takeIf { it != DELETED_USER_ID },
    fullName = fullName,
    bloodType = bloodType,
    phone = phone,
    status = status,
    committedAt = committedAt.toString(),
    arrivalEndsAt = arrivalEndsAt.toString(),
    state = state,
)

fun BlockEntry.toResponse() = BlockResponse(
    userId = blockedId,
    fullName = fullName,
    requestHospitalName = requestHospitalName,
    requestCreatedAt = requestCreatedAt?.toString(),
    createdAt = createdAt.toString(),
)

fun BloodRequest.toDonorResponse(committed: Boolean? = null, myPledge: PledgeResponse? = null) = DonorRequestResponse(
    id = id,
    bloodType = bloodType,
    component = component,
    unitsNeeded = unitsNeeded,
    hospitalName = hospitalName,
    areaName = areaName,
    neededBy = neededBy.toString(),
    note = note,
    status = status,
    committed = committed,
    myPledge = myPledge,
)
