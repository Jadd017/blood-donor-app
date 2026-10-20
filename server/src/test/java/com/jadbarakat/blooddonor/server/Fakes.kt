package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.AccountDeleter
import com.jadbarakat.blooddonor.server.auth.TokenVerifier
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.AccountDeletion
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.BlockEntry
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.CommitResult
import com.jadbarakat.blooddonor.server.data.CreateRequestResult
import com.jadbarakat.blooddonor.server.data.DELETED_HELPER_NAME
import com.jadbarakat.blooddonor.server.data.DELETED_USER_ID
import com.jadbarakat.blooddonor.server.data.PostingLimits
import com.jadbarakat.blooddonor.server.data.ReportResult
import com.jadbarakat.blooddonor.server.data.SafetyRepository
import com.jadbarakat.blooddonor.server.data.GeoPoint
import com.jadbarakat.blooddonor.server.data.Helper
import com.jadbarakat.blooddonor.server.data.HelperConfirmResult
import com.jadbarakat.blooddonor.server.data.HelperReminder
import com.jadbarakat.blooddonor.server.data.HelperRules
import com.jadbarakat.blooddonor.server.data.RequesterOutcome
import com.jadbarakat.blooddonor.server.data.Pledge
import com.jadbarakat.blooddonor.server.data.PledgeRepository
import com.jadbarakat.blooddonor.server.data.PledgeStatus
import com.jadbarakat.blooddonor.server.data.DeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.MatchingRepository
import com.jadbarakat.blooddonor.server.matching.DonorCandidate
import com.jadbarakat.blooddonor.server.push.PushMessage
import com.jadbarakat.blooddonor.server.push.PushResult
import com.jadbarakat.blooddonor.server.push.PushSender
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Posting limits high enough not to matter, for tests that aren't about the posting rules. */
val RELAXED_POSTING = SafetyConfig(maxOpenRequests = 50, maxOpenRequestsNewAccount = 50, maxRequestsPer24h = 50)

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

    override suspend fun findById(id: Long) = rows.values.firstOrNull { it.id == id }

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
            createdAt = rows[firebaseUid]?.createdAt ?: Instant.EPOCH,
            notificationsPausedUntil = rows[firebaseUid]?.notificationsPausedUntil,
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

    /** Every hospital is at the same made-up spot in Beirut. */
    override suspend fun location(id: Int) = GeoPoint(33.8938, 35.5018).takeIf { (hospitals + inactive).any { it.id == id } }

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

    override suspend fun createIfAllowed(
        requesterId: Long,
        input: NewBloodRequest,
        now: Instant,
        limits: PostingLimits,
    ): CreateRequestResult {
        val mine = rows.values.filter { it.requesterId == requesterId }
        val open = mine.filter { it.status == RequestStatus.OPEN && !it.isOverdue(now) }
        if (open.any { it.hospitalId == input.hospitalId && it.bloodType == input.bloodType && it.component == input.component }) {
            return CreateRequestResult.Duplicate
        }
        if (open.size >= limits.maxOpen) return CreateRequestResult.TooManyOpen(limits.maxOpen)
        val recent = mine.map { it.createdAt }.filter { it.isAfter(now.minus(limits.dailyWindow)) }.sorted()
        if (recent.size >= limits.maxPer24h) {
            return CreateRequestResult.DailyLimit(recent[recent.size - limits.maxPer24h].plus(limits.dailyWindow))
        }
        return CreateRequestResult.Created(create(requesterId, input, now))
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

    override suspend fun expireOverdue(now: Instant): List<Long> {
        val overdue = rows.values.filter { it.status == RequestStatus.OPEN && it.isOverdue(now) }
        overdue.forEach { rows[it.id] = it.copy(status = RequestStatus.EXPIRED, closedAt = it.neededBy) }
        return overdue.map { it.id }
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
    val notificationsPausedUntil: Instant? = null,
)

/** Mirrors the SQL filters of ExposedMatchingRepository over in-memory rows. */
class FakeMatchingRepository(
    private val requests: InMemoryBloodRequestRepository,
    private val tokens: InMemoryDeviceTokenRepository,
    private val safety: InMemorySafetyRepository? = null,
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
        now: Instant,
    ): List<DonorCandidate> =
        donors
            .filter { it.isDonor && it.bloodType in donorTypes && it.userId != request.requesterId }
            .filter { donor -> donor.notificationsPausedUntil?.isAfter(now) != true }
            .filter { donor -> safety?.blocked(donor.userId, request.requesterId) != true }
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

/** Mirrors ExposedPledgeRepository: one row per donor and request; confirming a donation updates the user. */
class InMemoryPledgeRepository(
    private val users: InMemoryUserRepository,
    private val requests: InMemoryBloodRequestRepository,
) : PledgeRepository {
    val rows = mutableListOf<Pledge>()

    /** reminder_sent_at, by pledge id. */
    val remindersSent = mutableMapOf<Long, Instant>()
    private var nextId = 1L

    override suspend fun commit(requestId: Long, donorId: Long, now: Instant, maxCommitted: Int): CommitResult {
        val existing = find(requestId, donorId)
        when (existing?.status) {
            PledgeStatus.COMMITTED -> return CommitResult.Committed(existing, isNew = false)
            PledgeStatus.DONATED, PledgeStatus.NOT_DONATED -> return CommitResult.AlreadyConfirmed
        }
        val active = rows.count { pledge ->
            val request = requests.rows[pledge.requestId]
            pledge.donorId == donorId && pledge.status == PledgeStatus.COMMITTED &&
                request != null && request.status == RequestStatus.OPEN && request.neededBy.isAfter(now)
        }
        if (active >= maxCommitted) return CommitResult.LimitReached
        rows.remove(existing)
        existing?.let { remindersSent.remove(it.id) }
        val pledge = Pledge(existing?.id ?: nextId++, requestId, donorId, PledgeStatus.COMMITTED, now)
        rows += pledge
        return CommitResult.Committed(pledge, isNew = true)
    }

    override suspend fun withdraw(requestId: Long, donorId: Long, now: Instant): Boolean =
        find(requestId, donorId)?.requesterOutcome == null && setStatus(requestId, donorId, PledgeStatus.WITHDRAWN) != null

    override suspend fun find(requestId: Long, donorId: Long) =
        rows.firstOrNull { it.requestId == requestId && it.donorId == donorId }

    override suspend fun helpers(requestId: Long): List<Helper> =
        rows.filter { it.requestId == requestId && it.status != PledgeStatus.WITHDRAWN }
            .sortedWith(compareBy<Pledge> { it.committedAt }.thenBy { it.id })
            .map { pledge ->
                val user = users.findById(pledge.donorId)
                Helper(
                    pledge.id, pledge.donorId, user?.fullName ?: DELETED_HELPER_NAME, user?.bloodType, user?.phone.orEmpty(),
                    pledge.status, pledge.committedAt, pledge.requesterOutcome,
                )
            }

    override suspend fun helperCount(requestId: Long, now: Instant, arrivalWindow: Duration): Int {
        val neededBy = requests.rows[requestId]?.neededBy ?: return 0
        return rows.count {
            it.requestId == requestId &&
                HelperRules.counts(it.status, it.requesterOutcome, it.committedAt, neededBy, now, arrivalWindow)
        }
    }

    override suspend fun committedDonorIds(requestId: Long) =
        rows.filter { it.requestId == requestId && it.status == PledgeStatus.COMMITTED }.map { it.donorId }

    override suspend fun committedRequestIds(donorId: Long) =
        rows.filter { it.donorId == donorId && it.status == PledgeStatus.COMMITTED }.map { it.requestId }.toSet()

    override suspend fun confirmDonation(requestId: Long, donorId: Long, donated: Boolean, today: LocalDate, now: Instant): Pledge? {
        val pledge = setStatus(requestId, donorId, if (donated) PledgeStatus.DONATED else PledgeStatus.NOT_DONATED) ?: return null
        if (donated) updateLastDonation(donorId, today)
        return pledge
    }

    override suspend fun confirmHelper(requestId: Long, pledgeId: Long, outcome: String, today: LocalDate, now: Instant): HelperConfirmResult {
        val index = rows.indexOfFirst { it.id == pledgeId && it.requestId == requestId }
        if (index < 0) return HelperConfirmResult.NotFound
        val pledge = rows[index]
        if (pledge.requesterOutcome != null) return HelperConfirmResult.AlreadyConfirmed
        if (pledge.status == PledgeStatus.WITHDRAWN) return HelperConfirmResult.Withdrawn
        val donated = outcome == RequesterOutcome.DONATED
        val updated = pledge.copy(
            status = if (donated && pledge.status == PledgeStatus.COMMITTED) PledgeStatus.DONATED else pledge.status,
            requesterOutcome = outcome,
            requesterConfirmedAt = now,
            confirmedDonation = donated,
        )
        rows[index] = updated
        if (donated) updateLastDonation(pledge.donorId, today)
        return HelperConfirmResult.Confirmed(updated)
    }

    override suspend fun claimDueReminders(now: Instant, arrivalWindow: Duration): List<HelperReminder> =
        rows.mapNotNull { pledge ->
            val request = requests.rows[pledge.requestId] ?: return@mapNotNull null
            val due = pledge.id !in remindersSent && pledge.requesterOutcome == null &&
                pledge.status in HelperRules.WAITING && request.status != RequestStatus.CANCELLED &&
                !HelperRules.arrivalEnds(pledge.committedAt, request.neededBy, arrivalWindow).isAfter(now)
            if (!due) return@mapNotNull null
            remindersSent[pledge.id] = now
            val donor = users.findById(pledge.donorId) ?: return@mapNotNull null
            if (request.requesterId == DELETED_USER_ID) return@mapNotNull null
            HelperReminder(pledge.id, pledge.requestId, request.requesterId, donor.fullName)
        }

    override suspend fun noShowCount(donorId: Long) =
        rows.count { it.donorId == donorId && it.requesterOutcome == RequesterOutcome.NO_SHOW }

    private fun updateLastDonation(donorId: Long, today: LocalDate) {
        val entry = users.rows.entries.first { it.value.id == donorId }
        val last = entry.value.lastDonationDate
        if (last == null || last.isBefore(today)) entry.setValue(entry.value.copy(lastDonationDate = today))
    }

    /** Committed -> [status]; null if there was no committed pledge. */
    private fun setStatus(requestId: Long, donorId: Long, status: String): Pledge? {
        val index = rows.indexOfFirst { it.requestId == requestId && it.donorId == donorId && it.status == PledgeStatus.COMMITTED }
        if (index < 0) return null
        return rows[index].copy(status = status).also { rows[index] = it }
    }
}

/** Mirrors ExposedSafetyRepository over the other in-memory tables. */
class InMemorySafetyRepository(
    private val users: InMemoryUserRepository,
    private val requests: InMemoryBloodRequestRepository,
    private val pledges: InMemoryPledgeRepository? = null,
    private val tokens: InMemoryDeviceTokenRepository? = null,
) : SafetyRepository {
    data class ReportRow(val requestId: Long, val reporterId: Long, val reason: String, val details: String?, val createdAt: Instant)
    data class StrikeRow(val userId: Long, val requestId: Long?, val createdAt: Instant)
    data class BlockRow(val blockerId: Long, val blockedId: Long, val viaRequestId: Long?, val createdAt: Instant)

    val reports = mutableListOf<ReportRow>()
    val strikes = mutableListOf<StrikeRow>()
    val blockRows = mutableListOf<BlockRow>()

    fun blocked(a: Long, b: Long) = blockRows.any { (it.blockerId == a && it.blockedId == b) || (it.blockerId == b && it.blockedId == a) }

    override suspend fun strikeTimes(userId: Long) = strikes.filter { it.userId == userId }.map { it.createdAt }.sorted()

    override suspend fun report(requestId: Long, reporterId: Long, reason: String, details: String?, now: Instant, reportsToHide: Int): ReportResult {
        if (reports.any { it.requestId == requestId && it.reporterId == reporterId }) return ReportResult.AlreadyReported
        reports += ReportRow(requestId, reporterId, reason, details, now)
        val request = requests.rows.getValue(requestId)
        if (request.hidden) return ReportResult.Reported(hidden = false)
        if (reports.filter { it.requestId == requestId }.map { it.reporterId }.distinct().size < reportsToHide) {
            return ReportResult.Reported(hidden = false)
        }
        requests.rows[requestId] = request.copy(
            hidden = true,
            status = if (request.status == RequestStatus.OPEN) RequestStatus.CANCELLED else request.status,
            closedAt = request.closedAt ?: now,
        )
        if (request.requesterId != DELETED_USER_ID && strikes.none { it.requestId == requestId }) {
            strikes += StrikeRow(request.requesterId, requestId, now)
        }
        return ReportResult.Reported(hidden = true)
    }

    override suspend fun pauseIfNoShows(donorId: Long, since: Instant, minRequesters: Int, now: Instant, pauseUntil: Instant): Instant? {
        val entry = users.rows.entries.firstOrNull { it.value.id == donorId } ?: return null
        if (entry.value.notificationsPausedUntil?.isAfter(now) == true) return null
        val requesters = pledges!!.rows
            .filter { it.donorId == donorId && it.requesterOutcome == RequesterOutcome.NO_SHOW && !it.requesterConfirmedAt!!.isBefore(since) }
            .mapNotNull { requests.rows[it.requestId]?.requesterId?.takeIf { id -> id != DELETED_USER_ID } }
            .toSet()
        if (requesters.size < minRequesters) return null
        entry.setValue(entry.value.copy(notificationsPausedUntil = pauseUntil))
        return pauseUntil
    }

    override suspend fun block(blockerId: Long, blockedId: Long, viaRequestId: Long?, now: Instant) {
        if (blockRows.none { it.blockerId == blockerId && it.blockedId == blockedId }) {
            blockRows += BlockRow(blockerId, blockedId, viaRequestId, now)
        }
    }

    override suspend fun unblock(blockerId: Long, blockedId: Long) {
        blockRows.removeAll { it.blockerId == blockerId && it.blockedId == blockedId }
    }

    override suspend fun blocks(blockerId: Long): List<BlockEntry> =
        blockRows.filter { it.blockerId == blockerId }
            .sortedWith(compareByDescending<BlockRow> { it.createdAt }.thenBy { it.blockedId })
            .mapNotNull { row ->
                val user = users.findById(row.blockedId) ?: return@mapNotNull null
                val request = row.viaRequestId?.let { requests.rows[it] }
                BlockEntry(
                    blockedId = row.blockedId,
                    fullName = user.fullName.takeIf { row.viaRequestId == null },
                    requestHospitalName = request?.hospitalName,
                    requestCreatedAt = request?.createdAt,
                    createdAt = row.createdAt,
                )
            }

    override suspend fun blockedEitherWay(userId: Long): Set<Long> =
        blockRows.mapNotNull {
            when (userId) {
                it.blockerId -> it.blockedId
                it.blockedId -> it.blockerId
                else -> null
            }
        }.toSet()

    override suspend fun helpedRequester(donorId: Long, requesterId: Long) =
        pledges!!.rows.any { it.donorId == donorId && requests.rows[it.requestId]?.requesterId == requesterId }

    override suspend fun deleteAccount(userId: Long, now: Instant): AccountDeletion? {
        val uid = users.rows.entries.firstOrNull { it.value.id == userId }?.key ?: return null
        val cancelled = requests.rows.values.filter { it.requesterId == userId && it.status == RequestStatus.OPEN }.map { it.id }
        cancelled.forEach { id -> requests.rows[id] = requests.rows.getValue(id).copy(status = RequestStatus.CANCELLED, closedAt = now) }
        val pledgeRows = pledges?.rows ?: mutableListOf()
        val withdrawn = pledgeRows.filter { it.donorId == userId && it.status == PledgeStatus.COMMITTED && it.requesterOutcome == null }
        pledgeRows.replaceAll { if (it in withdrawn) it.copy(status = PledgeStatus.WITHDRAWN) else it }
        tokens?.tokens?.values?.removeAll { it == userId }
        blockRows.removeAll { it.blockerId == userId || it.blockedId == userId }
        reports.removeAll { it.reporterId == userId }
        // What ON DELETE CASCADE / SET NULL do in the real tables:
        strikes.removeAll { it.userId == userId }
        requests.notifications.removeAll { it.userId == userId }
        requests.rows.replaceAll { _, r -> if (r.requesterId == userId) r.copy(requesterId = DELETED_USER_ID, note = null) else r }
        pledgeRows.replaceAll { if (it.donorId == userId) it.copy(donorId = DELETED_USER_ID) else it }
        users.rows.remove(uid)
        return AccountDeletion(cancelled, withdrawn.map { it.requestId }.distinct())
    }
}

/** Records the Firebase users it deleted. Fails while [failing] is true, like a Firebase outage. */
class FakeAccountDeleter : AccountDeleter {
    val deleted = mutableListOf<String>()
    var failing = false

    override suspend fun deleteFirebaseUser(uid: String) {
        if (failing) throw IllegalStateException("Firebase unavailable")
        deleted += uid
    }
}
