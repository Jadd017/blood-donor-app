package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.TokenVerifier
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.DeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.MatchingRepository
import com.jadbarakat.blooddonor.server.matching.DonorCandidate
import com.jadbarakat.blooddonor.server.push.PushMessage
import com.jadbarakat.blooddonor.server.push.PushResult
import com.jadbarakat.blooddonor.server.push.PushSender
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
import java.time.LocalDate
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
    private val areaNames: Map<Int, String> = emptyMap(),
) : HospitalRepository {
    override suspend fun all(areaId: Int?) =
        hospitals.filter { areaId == null || it.areaId == areaId }.sortedBy { it.name }

    override suspend fun findActive(id: Int) = hospitals.firstOrNull { it.id == id }

    fun name(id: Int) = (hospitals + inactive).first { it.id == id }.name

    fun areaName(id: Int) = (hospitals + inactive).first { it.id == id }.areaId.let { areaNames[it] ?: "Area $it" }
}

/** Mirrors the conditional updates of the real blood_requests queries. */
data class SentNotification(val requestId: Long, val userId: Long, val wave: Int, val sentAt: Instant)

class InMemoryBloodRequestRepository(private val hospitals: FakeHospitalRepository) : BloodRequestRepository {
    val rows = mutableMapOf<Long, BloodRequest>()

    /** The request_notifications table. */
    val notifications = mutableListOf<SentNotification>()
    private var nextId = 1L

    override suspend fun create(requesterId: Long, input: NewBloodRequest, now: Instant): BloodRequest {
        val request = BloodRequest(
            id = nextId++,
            requesterId = requesterId,
            hospitalId = input.hospitalId,
            hospitalName = hospitals.name(input.hospitalId),
            areaName = hospitals.areaName(input.hospitalId),
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

    override suspend fun isNotified(requestId: Long, userId: Long) =
        notifications.any { it.requestId == requestId && it.userId == userId }

    override suspend fun listNotifiedTo(userId: Long): List<BloodRequest> {
        val ids = notifications.filter { it.userId == userId }.map { it.requestId }.toSet()
        return rows.values.filter { it.id in ids }
            .sortedWith(compareByDescending<BloodRequest> { it.createdAt }.thenByDescending { it.id })
    }
}

/** The device_tokens table: token -> user id. */
class InMemoryDeviceTokenRepository : DeviceTokenRepository {
    val tokens = mutableMapOf<String, Long>()

    override suspend fun upsert(userId: Long, token: String, now: Instant) {
        tokens[token] = userId
    }

    override suspend fun delete(userId: Long, token: String) {
        if (tokens[token] == userId) tokens.remove(token)
    }

    override suspend fun tokensFor(userIds: Collection<Long>) =
        tokens.entries.filter { it.value in userIds }.groupBy({ it.value }, { it.key })

    override suspend fun deleteTokens(tokens: Collection<String>) {
        tokens.forEach { this.tokens.remove(it) }
    }
}

/** A donor known to [FakeMatchingRepository], with their distance (km) to every hospital. */
data class FakeDonor(
    val userId: Long,
    val bloodType: String?,
    val distanceKm: Double,
    val sex: String? = "male",
    val lastDonationDate: LocalDate? = null,
    val isDonor: Boolean = true,
)

/** Mirrors the SQL filters of ExposedMatchingRepository over in-memory rows. */
class FakeMatchingRepository(
    private val requests: InMemoryBloodRequestRepository,
    private val tokens: InMemoryDeviceTokenRepository,
) : MatchingRepository {
    val donors = mutableListOf<FakeDonor>()

    override suspend fun requestsAwaitingWaves(now: Instant, totalWaves: Int) =
        requests.rows.values
            .filter { it.status == RequestStatus.OPEN && it.neededBy.isAfter(now) && it.wavesSent < totalWaves }
            .sortedBy { it.createdAt }

    override suspend fun claimWave(requestId: Long, fromWave: Int, toWave: Int, now: Instant): Boolean {
        val request = requests.rows[requestId] ?: return false
        if (request.wavesSent != fromWave || request.status != RequestStatus.OPEN || !request.neededBy.isAfter(now)) {
            return false
        }
        requests.rows[requestId] = request.copy(wavesSent = toWave)
        return true
    }

    override suspend fun candidates(
        request: BloodRequest,
        donorTypes: Set<String>,
        radiusMeters: Double?,
        since: Instant,
    ): List<DonorCandidate> =
        donors
            .filter { it.isDonor && it.bloodType in donorTypes && it.userId != request.requesterId }
            .filter { donor -> tokens.tokens.containsValue(donor.userId) }
            .filter { donor -> requests.notifications.none { it.requestId == request.id && it.userId == donor.userId } }
            .filter { radiusMeters == null || it.distanceKm * 1000 <= radiusMeters }
            .map { donor ->
                DonorCandidate(
                    userId = donor.userId,
                    isDonor = donor.isDonor,
                    bloodType = donor.bloodType,
                    sex = donor.sex,
                    lastDonationDate = donor.lastDonationDate,
                    hasDeviceToken = true,
                    distanceMeters = donor.distanceKm * 1000,
                    notificationsLast24h = requests.notifications.count { it.userId == donor.userId && !it.sentAt.isBefore(since) },
                )
            }

    override suspend fun recordNotifications(requestId: Long, userIds: Collection<Long>, wave: Int, now: Instant) {
        for (userId in userIds) {
            if (requests.notifications.none { it.requestId == requestId && it.userId == userId }) {
                requests.notifications += SentNotification(requestId, userId, wave, now)
            }
        }
    }
}

/** Records every push. Tokens in [invalidTokens] or [failingTokens] fail the way FCM would. */
class FakePushSender : PushSender {
    val sent = mutableListOf<Pair<List<String>, PushMessage>>()
    val invalidTokens = mutableSetOf<String>()
    val failingTokens = mutableSetOf<String>()

    override suspend fun send(tokens: List<String>, message: PushMessage): PushResult {
        sent += tokens to message
        val invalid = tokens.filter { it in invalidTokens }.toSet()
        val failed = tokens.filter { it in failingTokens }.toSet()
        return PushResult(delivered = tokens.toSet() - invalid - failed, invalid = invalid, failed = failed)
    }
}
