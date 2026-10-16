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

/** Item of GET /api/v1/hospitals. */
@Serializable
data class Hospital(val id: Int, val name: String, val areaId: Int)

/** Body of POST /api/v1/requests. The server computes the deadline from [neededInHours]. */
@Serializable
data class CreateBloodRequest(
    val hospitalId: Int,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededInHours: Int,
    val note: String?,
)

/** A blood request as returned by the /api/v1/requests endpoints. Timestamps are ISO instants. */
@Serializable
data class BloodRequest(
    val id: Long,
    val hospitalId: Int,
    val hospitalName: String,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val neededBy: String,
    val note: String? = null,
    val status: String,
    val createdAt: String,
    val closedAt: String? = null,
)

/** What a donor who was notified sees about a request (GET /requests/{id}, /requests/for-me). */
@Serializable
data class DonorRequest(
    val id: Long,
    val bloodType: String,
    val component: String,
    val unitsNeeded: Int,
    val hospitalName: String,
    val areaName: String,
    val neededBy: String,
    val note: String? = null,
    val status: String,
)

/** Body of PUT and DELETE /api/v1/me/device-token. */
@Serializable
data class DeviceTokenBody(val token: String)

/** Error body sent by the server: {"error": "<code>", "message": "<text>"}. */
@Serializable
internal data class ErrorBody(val error: String, val message: String = "")

object ErrorCodes {
    const val PROFILE_NOT_FOUND = "profile_not_found"
    const val PHONE_TAKEN = "phone_taken"
    const val INVALID_PHONE = "invalid_phone"
    const val INVALID_FULL_NAME = "invalid_full_name"
    const val EMAIL_NOT_VERIFIED = "email_not_verified"
    const val PROFILE_REQUIRED = "profile_required"
    const val UNKNOWN_HOSPITAL = "unknown_hospital"
    const val INVALID_NOTE = "invalid_note"
    const val NOTE_CONTAINS_PHONE = "note_contains_phone"
    const val NOTE_CONTAINS_URL = "note_contains_url"
    const val REQUEST_NOT_OPEN = "request_not_open"
    const val REQUEST_NOT_FOUND = "request_not_found"
}

object RequestStatus {
    const val OPEN = "open"
    const val FULFILLED = "fulfilled"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"
}

object Component {
    const val WHOLE_BLOOD = "whole_blood"
    const val RED_CELLS = "red_cells"
    const val PLATELETS = "platelets"
    const val PLASMA = "plasma"
    val ALL = listOf(WHOLE_BLOOD, RED_CELLS, PLATELETS, PLASMA)
}

object Sex {
    const val MALE = "male"
    const val FEMALE = "female"
}

val BLOOD_TYPES = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
