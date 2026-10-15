package com.jadbarakat.blooddonor.data

import kotlinx.serialization.Serializable

// Mirrors of the server DTOs in :server (api/Dtos.kt). Keep the field names in sync.

@Serializable
data class Area(val id: Int, val name: String, val district: String, val governorate: String)

/** Response of GET/PUT /api/v1/me. */
@Serializable
data class Profile(
    val email: String,
    val fullName: String,
    val phone: String,
    val bloodType: String? = null,
    val sex: String? = null,
    val areaId: Int? = null,
    val lastDonationDate: String? = null, // ISO date, e.g. 2026-03-01
    val isDonor: Boolean,
)

/** Body of PUT /api/v1/me. */
@Serializable
data class ProfileRequest(
    val fullName: String,
    val phone: String,
    val bloodType: String?,
    val sex: String?,
    val areaId: Int?,
    val lastDonationDate: String?,
    val isDonor: Boolean,
)

/** Error body sent by the server: {"error": "<code>", "message": "<text>"}. */
@Serializable
internal data class ErrorBody(val error: String, val message: String = "")

object ErrorCodes {
    const val PROFILE_NOT_FOUND = "profile_not_found"
    const val PHONE_TAKEN = "phone_taken"
    const val INVALID_PHONE = "invalid_phone"
    const val INVALID_FULL_NAME = "invalid_full_name"
    const val EMAIL_NOT_VERIFIED = "email_not_verified"
}

object Sex {
    const val MALE = "male"
    const val FEMALE = "female"
}

val BLOOD_TYPES = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
