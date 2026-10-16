package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.Hospital
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
)
