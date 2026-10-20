package com.jadbarakat.blooddonor.server.data

import com.jadbarakat.blooddonor.server.api.ApiException
import com.jadbarakat.blooddonor.server.matching.DonorCandidate
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

interface AreaRepository {
    suspend fun all(): List<Area>
    suspend fun exists(id: Int): Boolean
}

interface UserRepository {
    suspend fun findByUid(firebaseUid: String): UserProfile?

    suspend fun findById(id: Long): UserProfile?

    /**
     * Creates or updates the profile for [firebaseUid]. Throws [ApiException] 409
     * ("phone_taken" / "email_taken") when another user already has that value.
     */
    suspend fun upsert(firebaseUid: String, email: String, input: ProfileInput): UserProfile
}

interface HospitalRepository {
    /** Active hospitals sorted by name, optionally only those in [areaId]. */
    suspend fun all(areaId: Int? = null): List<Hospital>

    /** The hospital if it exists and is active, else null. */
    suspend fun findActive(id: Int): Hospital?

    /** Where hospital [id] is (active or not), for a map link. */
    suspend fun location(id: Int): GeoPoint?
}

/** Statuses are returned as stored; callers apply [BloodRequest.effective]. */
interface BloodRequestRepository {
    /** Inserts an open request created at [now], needed by [now] + neededInHours. */
    suspend fun create(requesterId: Long, input: NewBloodRequest, now: Instant): BloodRequest

    /**
     * Like [create], but only if the posting rules allow it, checked in this order: no duplicate open
     * request (same hospital, blood type and component), fewer than [PostingLimits.maxOpen] open
     * requests, fewer than [PostingLimits.maxPer24h] created in the last [PostingLimits.dailyWindow].
     * Atomic per user, so parallel posts can't get past a limit.
     */
    suspend fun createIfAllowed(requesterId: Long, input: NewBloodRequest, now: Instant, limits: PostingLimits): CreateRequestResult

    suspend fun findById(id: Long): BloodRequest?

    /** Newest first. */
    suspend fun listByRequester(requesterId: Long): List<BloodRequest>

    /**
     * Moves request [id] from open to [status], but only if it is open and not past needed_by at [now].
     * Returns the updated request, or null if it was not open.
     */
    suspend fun close(id: Long, status: String, now: Instant): BloodRequest?

    /** Marks every open request past needed_by at [now] as expired. Returns the ids that changed. */
    suspend fun expireOverdue(now: Instant): List<Long>

    /** True if [userId] was sent a notification about request [requestId]. */
    suspend fun isNotified(requestId: Long, userId: Long): Boolean

    /** Requests [userId] was notified about, newest first (any status; callers filter). */
    suspend fun listNotifiedTo(userId: Long): List<BloodRequest>
}

interface DeviceTokenRepository {
    /** Registers [token] for [userId], taking it over if another user had it (shared phone). */
    suspend fun upsert(userId: Long, token: String, now: Instant)

    /** Removes [token] if it belongs to [userId]. */
    suspend fun delete(userId: Long, token: String)

    suspend fun tokensFor(userIds: Collection<Long>): Map<Long, List<String>>

    /** Removes tokens FCM reported as no longer valid, whoever they belong to. */
    suspend fun deleteTokens(tokens: Collection<String>)
}

/** Queries used by the notification waves. */
interface MatchingRepository {
    /** Open requests, not past needed_by at [now], with fewer than [totalWaves] waves sent. */
    suspend fun requestsAwaitingWaves(now: Instant, totalWaves: Int): List<BloodRequest>

    /**
     * Atomically moves request [requestId] from [fromWave] to [toWave] waves sent, if it is still
     * open and not past needed_by. False if someone else claimed it first or it closed.
     */
    suspend fun claimWave(requestId: Long, fromWave: Int, toWave: Int, now: Instant): Boolean

    /**
     * Possible donors for a request: donors with one of [donorTypes] and a device token, who are not
     * the requester and weren't notified about this request yet, with their area within
     * [radiusMeters] of the hospital (null = anywhere). Donors whose notifications are paused at
     * [now], and donors who blocked the requester or were blocked by them, are left out. [since] is
     * the start of the 24 h window for [DonorCandidate.notificationsLast24h]. The final eligibility
     * check is done in Kotlin.
     */
    suspend fun candidates(
        request: BloodRequest,
        donorTypes: Set<String>,
        radiusMeters: Double?,
        since: Instant,
        now: Instant,
    ): List<DonorCandidate>

    /** Logs that [userIds] were notified about [requestId] in [wave]. Already-logged users are skipped. */
    suspend fun recordNotifications(requestId: Long, userIds: Collection<Long>, wave: Int, now: Instant)
}

/**
 * "I can help" pledges. Helpers of a request are its pledges that weren't withdrawn; which of them
 * count towards units_needed is decided by [HelperRules].
 */
interface PledgeRepository {
    /**
     * Commits [donorId] to [requestId] at [now]. Atomic per donor, so parallel calls can't exceed
     * [maxCommitted] committed pledges on requests still open at [now]. A withdrawn pledge is reused.
     */
    suspend fun commit(requestId: Long, donorId: Long, now: Instant, maxCommitted: Int): CommitResult

    /**
     * Committed -> withdrawn. False if the donor had no committed pledge for the request, or the
     * requester already confirmed it.
     */
    suspend fun withdraw(requestId: Long, donorId: Long, now: Instant): Boolean

    suspend fun find(requestId: Long, donorId: Long): Pledge?

    /** Pledges that weren't withdrawn, earliest commit first. */
    suspend fun helpers(requestId: Long): List<Helper>

    /** Pledges that count as helpers at [now] (see [HelperRules]). */
    suspend fun helperCount(requestId: Long, now: Instant, arrivalWindow: Duration): Int

    suspend fun committedDonorIds(requestId: Long): List<Long>

    /** Requests [donorId] has a committed pledge for. */
    suspend fun committedRequestIds(donorId: Long): Set<Long>

    /**
     * The donor's own "Did you donate?": committed -> donated or not_donated. If [donated], also sets
     * the donor's last donation date to [today] (unless it is already later). Never sets
     * confirmed_donation. Null if the donor had no committed pledge.
     */
    suspend fun confirmDonation(requestId: Long, donorId: Long, donated: Boolean, today: LocalDate, now: Instant): Pledge?

    /**
     * The requester's confirmation of pledge [pledgeId] of [requestId], once per pledge. [outcome] is a
     * [RequesterOutcome]. "donated" also sets confirmed_donation, the donor's last donation date
     * (unless already later) and, if the donor hasn't answered yet, the pledge status to donated.
     */
    suspend fun confirmHelper(requestId: Long, pledgeId: Long, outcome: String, today: LocalDate, now: Instant): HelperConfirmResult

    /**
     * Marks every pledge whose arrival window ended by [now] without a requester confirmation (and
     * whose request wasn't cancelled) as reminded, and returns them. Each pledge is returned once.
     */
    suspend fun claimDueReminders(now: Instant, arrivalWindow: Duration): List<HelperReminder>

    /** How many times the requester said [donorId] didn't come. Not exposed yet (Phase 5). */
    suspend fun noShowCount(donorId: Long): Int
}

/** Reports, strikes, the no-show pause, blocks and account deletion. */
interface SafetyRepository {
    /** When each of [userId]'s strikes was given. */
    suspend fun strikeTimes(userId: Long): List<Instant>

    /**
     * Records [reporterId]'s report of [requestId]. When the request reaches [reportsToHide] reports,
     * in the same transaction: hides it, closes it as cancelled if it was open, and gives its
     * requester a strike. That happens once per request.
     */
    suspend fun report(requestId: Long, reporterId: Long, reason: String, details: String?, now: Instant, reportsToHide: Int): ReportResult

    /**
     * After a no-show for [donorId]: if no-shows from at least [minRequesters] different requesters
     * were confirmed since [since], and notifications aren't paused at [now] already, pauses them
     * until [pauseUntil]. Returns the new pause end, or null if nothing changed.
     */
    suspend fun pauseIfNoShows(donorId: Long, since: Instant, minRequesters: Int, now: Instant, pauseUntil: Instant): Instant?

    /** [blockerId] blocks [blockedId]. Blocking again keeps the first block. */
    suspend fun block(blockerId: Long, blockedId: Long, viaRequestId: Long?, now: Instant)

    suspend fun unblock(blockerId: Long, blockedId: Long)

    /** Everyone [blockerId] blocked, newest first. */
    suspend fun blocks(blockerId: Long): List<BlockEntry>

    /** Users [userId] blocked or was blocked by. */
    suspend fun blockedEitherWay(userId: Long): Set<Long>

    /** True if [donorId] committed (and didn't withdraw) to a request by [requesterId] at some point. */
    suspend fun helpedRequester(donorId: Long, requesterId: Long): Boolean

    /**
     * Deletes user [userId] in one transaction: cancels their open requests, withdraws their
     * committed pledges, deletes their device tokens, blocks (both ways), reports they made and the
     * notes of their requests, then the users row. Their requests and pledges stay, with the user id
     * set to NULL. Returns null if the user no longer exists.
     */
    suspend fun deleteAccount(userId: Long, now: Instant): AccountDeletion?
}

fun phoneTaken() = ApiException(HttpStatusCode.Conflict, "phone_taken", "This phone number is already registered")
fun emailTaken() = ApiException(HttpStatusCode.Conflict, "email_taken", "This email is already registered")
