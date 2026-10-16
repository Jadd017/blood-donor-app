package com.jadbarakat.blooddonor.server.data

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
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededBy: Instant,
    val note: String?,
    /** As stored. Use [effective] before showing it: the expiry job only runs every few minutes. */
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
) {
    fun isOverdue(now: Instant): Boolean = !now.isBefore(neededBy)

    /** This request with an open-but-overdue status reported as expired. */
    fun effective(now: Instant): BloodRequest =
        if (status == RequestStatus.OPEN && isOverdue(now)) copy(status = RequestStatus.EXPIRED, closedAt = neededBy)
        else this
}
