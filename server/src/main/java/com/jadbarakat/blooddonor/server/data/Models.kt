package com.jadbarakat.blooddonor.server.data

import java.time.LocalDate

data class Area(val id: Int, val name: String, val district: String, val governorate: String)

data class UserProfile(
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
