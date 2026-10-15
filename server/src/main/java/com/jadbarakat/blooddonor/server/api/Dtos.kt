package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.UserProfile
import kotlinx.serialization.Serializable

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
)

fun Area.toResponse() = AreaResponse(id, name, district, governorate)

fun UserProfile.toResponse() = ProfileResponse(
    email = email,
    fullName = fullName,
    phone = phone,
    bloodType = bloodType,
    sex = sex,
    areaId = areaId,
    lastDonationDate = lastDonationDate?.toString(),
    isDonor = isDonor,
)
