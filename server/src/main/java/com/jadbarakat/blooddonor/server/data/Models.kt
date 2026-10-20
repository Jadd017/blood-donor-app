package com.jadbarakat.blooddonor.server.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class Area(val id: Int, val name: String, val district: String, val governorate: String)

data class UserProfile(
    val id: Long,
    val firebaseUid: String,
    val email: String,
    val fullName: String,
    val phone: String,
    val bloodType: String?,
    val sex: String?,
    val areaId: Int?,
    val lastDonationDate: LocalDate?,
    val isDonor: Boolean,
)

/** Validated profile fields from PUT /me (everything except uid and email). */
data class ProfileInput(
    val fullName: String,
    val phone: String,
    val bloodType: String?,
    val sex: String?,
    val areaId: Int?,
    val lastDonationDate: LocalDate?,
    val isDonor: Boolean,
)

/** An active hospital, as shown in the hospital picker. */
data class Hospital(val id: Int, val name: String, val areaId: Int)

data class GeoPoint(val latitude: Double, val longitude: Double)

object PledgeStatus {
    const val COMMITTED = "committed"
    const val WITHDRAWN = "withdrawn"
    const val DONATED = "donated"
    const val NOT_DONATED = "not_donated"
}

/** A donor's "I can help" for one request. */
data class Pledge(
    val id: Long,
    val requestId: Long,
    val donorId: Long,
    val status: String,
    /** The latest time the donor committed (withdrawing and committing again resets it). */
    val committedAt: Instant,
    /** What the requester said after the blood bank told them: [RequesterOutcome] or null. */
    val requesterOutcome: String? = null,
    val requesterConfirmedAt: Instant? = null,
    /** True only when the requester confirmed the donation (the donor's own answer never sets it). */
    val confirmedDonation: Boolean = false,
)

object RequesterOutcome {
    const val DONATED = "donated"
    const val NO_SHOW = "no_show"
    val ALL = setOf(DONATED, NO_SHOW)
}

/**
 * Who counts as a helper. A pledge the requester confirmed as donated always counts. Otherwise a
 * committed (or self-reported donated) pledge counts only during its arrival window, which ends
 * [arrivalWindow] after committing or at the deadline, whichever comes first. The SQL in
 * ExposedPledgeRepository mirrors this.
 */
object HelperRules {
    fun arrivalEnds(committedAt: Instant, neededBy: Instant, arrivalWindow: Duration): Instant =
        minOf(committedAt.plus(arrivalWindow), neededBy)

    fun counts(
        status: String,
        requesterOutcome: String?,
        committedAt: Instant,
        neededBy: Instant,
        now: Instant,
        arrivalWindow: Duration,
    ): Boolean = when (requesterOutcome) {
        RequesterOutcome.DONATED -> true
        RequesterOutcome.NO_SHOW -> false
        else -> status in WAITING && now.isBefore(arrivalEnds(committedAt, neededBy, arrivalWindow))
    }

    /** Pledge statuses that may still count (the donor hasn't withdrawn or said no). */
    val WAITING = setOf(PledgeStatus.COMMITTED, PledgeStatus.DONATED)
}

/** The outcome of the requester confirming a helper. */
sealed interface HelperConfirmResult {
    data class Confirmed(val pledge: Pledge) : HelperConfirmResult
    data object NotFound : HelperConfirmResult
    data object Withdrawn : HelperConfirmResult
    data object AlreadyConfirmed : HelperConfirmResult
}

/** A pledge whose arrival window ended without the requester confirming it. */
data class HelperReminder(val pledgeId: Long, val requestId: Long, val requesterId: Long, val donorFullName: String)

/** A donor who committed (and didn't withdraw), as shown to the requester. */
data class Helper(
    val pledgeId: Long,
    val donorId: Long,
    val fullName: String,
    val bloodType: String?,
    val phone: String,
    val status: String,
    val committedAt: Instant,
    val requesterOutcome: String? = null,
)

sealed interface CommitResult {
    /** [isNew] is false if the donor had already committed to this request. */
    data class Committed(val pledge: Pledge, val isNew: Boolean) : CommitResult
    /** The donor already has the maximum number of committed pledges. */
    data object LimitReached : CommitResult
    /** The donor already said whether they donated for this request. */
    data object AlreadyConfirmed : CommitResult
}

object RequestStatus {
    const val OPEN = "open"
    const val FULFILLED = "fulfilled"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"
}

/** Validated fields from POST /requests. */
data class NewBloodRequest(
    val hospitalId: Int,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededInHours: Int,
    val note: String?,
)

data class BloodRequest(
    val id: Long,
    val requesterId: Long,
    val hospitalId: Int,
    val hospitalName: String,
    /** Name of the area the hospital is in. */
    val areaName: String,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededBy: Instant,
    val note: String?,
    /** As stored. Use [effective] before showing it: the expiry job only runs every few minutes. */
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
    /** How many notification waves have gone out (0 = none yet). */
    val wavesSent: Int = 0,
) {
    fun isOverdue(now: Instant): Boolean = !now.isBefore(neededBy)

    /** This request with an open-but-overdue status reported as expired. */
    fun effective(now: Instant): BloodRequest =
        if (status == RequestStatus.OPEN && isOverdue(now)) copy(status = RequestStatus.EXPIRED, closedAt = neededBy)
        else this
}
