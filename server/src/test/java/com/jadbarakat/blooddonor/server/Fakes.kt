package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.TokenVerifier
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.HospitalRepository
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequestStatus
import com.jadbarakat.blooddonor.server.data.UserProfile
import com.jadbarakat.blooddonor.server.data.UserRepository
import com.jadbarakat.blooddonor.server.data.emailTaken
import com.jadbarakat.blooddonor.server.data.phoneTaken
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Accepts any token registered in [tokens]; everything else is invalid. */
class FakeTokenVerifier(private val tokens: Map<String, VerifiedToken>) : TokenVerifier {
    override suspend fun verify(idToken: String): VerifiedToken? = tokens[idToken]
}

class FakeAreaRepository(private val areas: List<Area>) : AreaRepository {
    override suspend fun all() = areas
    override suspend fun exists(id: Int) = areas.any { it.id == id }
}

/** Mirrors the unique constraints of the real users table. */
class InMemoryUserRepository : UserRepository {
    val rows = mutableMapOf<String, UserProfile>()
    private var nextId = 1L

    override suspend fun findByUid(firebaseUid: String) = rows[firebaseUid]

    override suspend fun upsert(firebaseUid: String, email: String, input: ProfileInput): UserProfile {
        val others = rows.values.filter { it.firebaseUid != firebaseUid }
        if (others.any { it.phone == input.phone }) throw phoneTaken()
        if (others.any { it.email == email }) throw emailTaken()
        val profile = UserProfile(
            id = rows[firebaseUid]?.id ?: nextId++,
            firebaseUid = firebaseUid,
            email = email,
            fullName = input.fullName,
            phone = input.phone,
            bloodType = input.bloodType,
            sex = input.sex,
            areaId = input.areaId,
            lastDonationDate = input.lastDonationDate,
            isDonor = input.isDonor,
        )
        rows[firebaseUid] = profile
        return profile
    }
}

/** A clock tests can move forward. */
class MutableClock(var now: Instant, private val zone: ZoneId = ZoneId.of("Asia/Beirut")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
}

/** [hospitals] are the active ones; [inactive] exist but are hidden. */
class FakeHospitalRepository(
    private val hospitals: List<Hospital>,
    private val inactive: List<Hospital> = emptyList(),
) : HospitalRepository {
    override suspend fun all(areaId: Int?) =
        hospitals.filter { areaId == null || it.areaId == areaId }.sortedBy { it.name }

    override suspend fun findActive(id: Int) = hospitals.firstOrNull { it.id == id }

    fun name(id: Int) = (hospitals + inactive).first { it.id == id }.name
}

/** Mirrors the conditional updates of the real blood_requests queries. */
class InMemoryBloodRequestRepository(private val hospitals: FakeHospitalRepository) : BloodRequestRepository {
    val rows = mutableMapOf<Long, BloodRequest>()
    private var nextId = 1L

    override suspend fun create(requesterId: Long, input: NewBloodRequest, now: Instant): BloodRequest {
        val request = BloodRequest(
            id = nextId++,
            requesterId = requesterId,
            hospitalId = input.hospitalId,
            hospitalName = hospitals.name(input.hospitalId),
            bloodType = input.bloodType,
            component = input.component,
            unitsNeeded = input.unitsNeeded,
            neededBy = now.plusSeconds(input.neededInHours * 3600L),
            note = input.note,
            status = RequestStatus.OPEN,
            createdAt = now,
            closedAt = null,
        )
        rows[request.id] = request
        return request
    }

    override suspend fun findById(id: Long) = rows[id]

    override suspend fun listByRequester(requesterId: Long) =
        rows.values.filter { it.requesterId == requesterId }
            .sortedWith(compareByDescending<BloodRequest> { it.createdAt }.thenByDescending { it.id })

    override suspend fun close(id: Long, status: String, now: Instant): BloodRequest? {
        val request = rows[id] ?: return null
        if (request.status != RequestStatus.OPEN || request.isOverdue(now)) return null
        return request.copy(status = status, closedAt = now).also { rows[id] = it }
    }

    override suspend fun expireOverdue(now: Instant): Int {
        val overdue = rows.values.filter { it.status == RequestStatus.OPEN && it.isOverdue(now) }
        overdue.forEach { rows[it.id] = it.copy(status = RequestStatus.EXPIRED, closedAt = it.neededBy) }
        return overdue.size
    }
}
