package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.auth.AccountDeleter
import com.jadbarakat.blooddonor.server.auth.UserPrincipal
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.CommitResult
import com.jadbarakat.blooddonor.server.data.CreateRequestResult
import com.jadbarakat.blooddonor.server.data.DELETED_USER_ID
import com.jadbarakat.blooddonor.server.data.DeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.HelperConfirmResult
import com.jadbarakat.blooddonor.server.data.HelperRules
import com.jadbarakat.blooddonor.server.data.HospitalRepository
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.Pledge
import com.jadbarakat.blooddonor.server.data.PledgeRepository
import com.jadbarakat.blooddonor.server.data.PledgeStatus
import com.jadbarakat.blooddonor.server.data.PostingLimits
import com.jadbarakat.blooddonor.server.data.ReportReason
import com.jadbarakat.blooddonor.server.data.ReportResult
import com.jadbarakat.blooddonor.server.data.RequestStatus
import com.jadbarakat.blooddonor.server.data.RequesterOutcome
import com.jadbarakat.blooddonor.server.data.SafetyRepository
import com.jadbarakat.blooddonor.server.data.UserProfile
import com.jadbarakat.blooddonor.server.data.UserRepository
import com.jadbarakat.blooddonor.server.matching.Compatibility
import com.jadbarakat.blooddonor.server.matching.Eligibility
import com.jadbarakat.blooddonor.server.matching.MatchingConfig
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
import com.jadbarakat.blooddonor.server.safety.StrikeRules
import com.jadbarakat.blooddonor.server.safety.Suspension
import com.jadbarakat.blooddonor.server.validation.validate
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.time.toJavaDuration

const val FIREBASE_AUTH = "firebase"

/** A donor can be committed to at most this many open requests at a time. */
const val MAX_COMMITTED_PLEDGES = 2

const val MAX_REPORT_DETAILS = 200

/** Told about every new request, e.g. to start notifying donors. Must not block. */
fun interface RequestCreatedListener {
    fun onRequestCreated(requestId: Long)
}

fun Route.apiV1(
    users: UserRepository,
    areas: AreaRepository,
    hospitals: HospitalRepository,
    requests: BloodRequestRepository,
    deviceTokens: DeviceTokenRepository,
    pledges: PledgeRepository,
    safety: SafetyRepository,
    accountDeleter: AccountDeleter,
    onRequestCreated: RequestCreatedListener,
    onRequestEvent: RequestEventListener,
    config: MatchingConfig,
    safetyConfig: SafetyConfig,
    clock: Clock,
) {
    val arrivalWindow = config.arrivalWindow.toJavaDuration()

    suspend fun suspension(userId: Long, now: Instant) = StrikeRules.suspension(safety.strikeTimes(userId), now, safetyConfig)

    /** The profile with the user's current posting suspension and notification pause. */
    suspend fun profileResponse(profile: UserProfile): ProfileResponse {
        val now = clock.instant()
        return profile.toResponse(suspension(profile.id, now), now)
    }

    /** The posting rules for POST /requests (the duplicate and limit checks run atomically when inserting). */
    suspend fun createRequest(profile: UserProfile, input: NewBloodRequest): BloodRequest {
        val now = clock.instant()
        suspension(profile.id, now)?.let { throw postingSuspended(it) }
        val newAccountUntil = profile.createdAt.plus(safetyConfig.newAccountAge.toJavaDuration())
        val newAccount = now.isBefore(newAccountUntil)
        val limits = PostingLimits(
            maxOpen = if (newAccount) safetyConfig.maxOpenRequestsNewAccount else safetyConfig.maxOpenRequests,
            maxPer24h = safetyConfig.maxRequestsPer24h,
            dailyWindow = safetyConfig.dailyWindow.toJavaDuration(),
        )
        return when (val result = requests.createIfAllowed(profile.id, input, now, limits)) {
            is CreateRequestResult.Created -> result.request
            CreateRequestResult.Duplicate -> throw ApiException(
                HttpStatusCode.Conflict, "duplicate_request",
                "You already have an open request for this hospital, blood type and component",
            )
            is CreateRequestResult.TooManyOpen -> throw ApiException(
                HttpStatusCode.TooManyRequests, "too_many_open_requests",
                if (newAccount) {
                    "New accounts can have ${result.limit} open request(s) at a time during their first " +
                        "${safetyConfig.newAccountAge.inWholeDays} days"
                } else {
                    "You can have at most ${result.limit} open requests at a time"
                },
                buildMap {
                    put("limit", result.limit.toString())
                    put("newAccount", newAccount.toString())
                    if (newAccount) put("newAccountUntil", newAccountUntil.toString())
                },
            )
            is CreateRequestResult.DailyLimit -> throw ApiException(
                HttpStatusCode.TooManyRequests, "daily_limit",
                "You can create at most ${limits.maxPer24h} requests in 24 hours",
                mapOf("limit" to limits.maxPer24h.toString(), "retryAt" to result.retryAt.toString()),
            )
        }
    }

    /** A request [profile] may report or block the requester of: they were notified about it or committed to it. */
    suspend fun RoutingCall.requestSeenAsDonor(profile: UserProfile, ownMessage: String): BloodRequest {
        val request = parameters["id"]?.toLongOrNull()?.let { requests.findById(it) } ?: throw requestNotFound()
        if (request.requesterId == profile.id) throw ApiException(HttpStatusCode.Conflict, "own_request", ownMessage)
        if (!requests.isNotified(request.id, profile.id) && pledges.find(request.id, profile.id) == null) {
            throw requestNotFound()
        }
        return request
    }

    /** The owner's view, with the helpers' contact details and who still counts as helping. */
    suspend fun ownerView(request: BloodRequest): BloodRequestResponse {
        val now = clock.instant()
        val helpers = pledges.helpers(request.id).map { helper ->
            val state = when {
                helper.requesterOutcome == RequesterOutcome.DONATED -> HelperState.DONATED
                helper.requesterOutcome == RequesterOutcome.NO_SHOW -> HelperState.NO_SHOW
                HelperRules.counts(helper.status, null, helper.committedAt, request.neededBy, now, arrivalWindow) ->
                    HelperState.ON_THE_WAY
                else -> HelperState.AWAITING_CONFIRMATION
            }
            helper.toResponse(HelperRules.arrivalEnds(helper.committedAt, request.neededBy, arrivalWindow), state)
        }
        val donated = helpers.count { it.state == HelperState.DONATED }
        val onTheWay = helpers.count { it.state == HelperState.ON_THE_WAY }
        return request.toResponse().copy(
            helpers = helpers,
            committedCount = donated + onTheWay,
            confirmedDonatedCount = donated,
            onTheWayCount = onTheWay,
        )
    }

    /** What a donor sees about their own pledge; null once withdrawn. */
    suspend fun pledgeView(request: BloodRequest, pledge: Pledge): PledgeResponse? {
        if (pledge.status == PledgeStatus.WITHDRAWN) return null
        val location = hospitals.location(request.hospitalId)
        return PledgeResponse(
            status = pledge.status,
            committedAt = pledge.committedAt.toString(),
            canConfirmDonation = canConfirmDonation(request, pledge, clock.instant(), arrivalWindow),
            requesterFirstName = users.findById(request.requesterId)?.fullName?.firstName().orEmpty(),
            hospitalName = request.hospitalName,
            areaName = request.areaName,
            hospitalLatitude = location?.latitude,
            hospitalLongitude = location?.longitude,
            neededBy = request.neededBy.toString(),
        )
    }

    suspend fun donorView(request: BloodRequest, donorId: Long): DonorRequestResponse =
        request.toDonorResponse(myPledge = pledges.find(request.id, donorId)?.let { pledgeView(request, it) })

    suspend fun RoutingCall.closeOwnedRequest(status: String) {
        val request = ownedRequest(users, requests)
        val closed = requests.close(request.id, status, clock.instant()) ?: throw requestNotOpen()
        onRequestEvent.onEvent(RequestEvent.RequestClosed(closed.id))
        respond(ownerView(closed))
    }

    route("/api/v1") {
        rateLimit(RateLimits.PUBLIC) {
            get("/areas") {
                call.respond(areas.all().map { it.toResponse() })
            }
        }

        authenticate(FIREBASE_AUTH) {
            rateLimit(RateLimits.GENERAL) {
                get("/me") {
                    val profile = users.findByUid(call.user().uid)
                        ?: throw ApiException(HttpStatusCode.NotFound, "profile_not_found", "No profile yet, create it with PUT /me")
                    call.respond(profileResponse(profile))
                }

                put("/me") {
                    val user = call.user()
                    val input = call.receive<ProfileRequest>().validate(LocalDate.now(clock))
                    if (input.areaId != null && !areas.exists(input.areaId)) {
                        throw badRequest("unknown_area", "areaId ${input.areaId} does not exist")
                    }
                    // uid and email come only from the verified token.
                    call.respond(profileResponse(users.upsert(user.uid, user.email, input)))
                }

                // Account deletion (Google Play requirement). Safe to retry: once the profile is gone,
                // only the Firebase user is left to delete.
                delete("/me") {
                    val user = call.user()
                    val profile = users.findByUid(user.uid)
                    val deletion = profile?.let { safety.deleteAccount(it.id, clock.instant()) }
                    if (deletion != null) {
                        deletion.cancelledRequestIds.forEach { onRequestEvent.onEvent(RequestEvent.RequestClosed(it)) }
                        deletion.withdrawnRequestIds.forEach { onRequestEvent.onEvent(RequestEvent.DonorWithdrew(it)) }
                    }
                    accountDeleter.deleteFirebaseUser(user.uid)
                    call.respond(HttpStatusCode.NoContent)
                }

                route("/blocks") {
                    get {
                        val profile = call.requireProfile(users)
                        call.respond(safety.blocks(profile.id).map { it.toResponse() })
                    }

                    rateLimit(RateLimits.BLOCKS) {
                        // Blocks someone whose identity the user already saw: a helper on one of their
                        // requests. A requester is blocked from the request (POST /requests/{id}/block-requester).
                        post("/{userId}") {
                            val profile = call.requireProfile(users)
                            val target = call.parameters["userId"]?.toLongOrNull() ?: throw userNotFound()
                            if (target == profile.id) throw badRequest("cannot_block_self", "You can't block yourself")
                            if (!safety.helpedRequester(donorId = target, requesterId = profile.id)) throw userNotFound()
                            safety.block(profile.id, target, viaRequestId = null, clock.instant())
                            call.respond(HttpStatusCode.NoContent)
                        }

                        delete("/{userId}") {
                            val profile = call.requireProfile(users)
                            val target = call.parameters["userId"]?.toLongOrNull() ?: throw userNotFound()
                            safety.unblock(profile.id, target)
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }

                route("/me/device-token") {
                    put {
                        val profile = call.requireProfile(users)
                        val token = call.receive<DeviceTokenBody>().validToken()
                        deviceTokens.upsert(profile.id, token, clock.instant())
                        call.respond(HttpStatusCode.NoContent)
                    }

                    delete {
                        val profile = call.requireProfile(users)
                        deviceTokens.delete(profile.id, call.receive<DeviceTokenBody>().validToken())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                get("/hospitals") {
                    call.requireProfile(users)
                    val areaId = call.request.queryParameters["areaId"]?.let {
                        it.toIntOrNull() ?: throw badRequest("invalid_area_id", "areaId must be a number")
                    }
                    call.respond(hospitals.all(areaId).map { it.toResponse() })
                }

                route("/requests") {
                    rateLimit(RateLimits.POST_REQUEST) {
                        post {
                            val profile = call.requireProfile(users)
                            val input = call.receive<CreateBloodRequestBody>().validate()
                            if (hospitals.findActive(input.hospitalId) == null) {
                                throw badRequest("unknown_hospital", "hospitalId ${input.hospitalId} does not exist")
                            }
                            val created = createRequest(profile, input)
                            onRequestCreated.onRequestCreated(created.id)
                            call.respond(HttpStatusCode.Created, created.toResponse())
                        }
                    }

                    get("/mine") {
                        val profile = call.requireProfile(users)
                        val now = clock.instant()
                        call.respond(requests.listByRequester(profile.id).map { it.effective(now).toResponse() })
                    }

                    get("/for-me") {
                        val profile = call.requireProfile(users)
                        val now = clock.instant()
                        val committed = pledges.committedRequestIds(profile.id)
                        val blocked = safety.blockedEitherWay(profile.id)
                        // Open requests (unless the requester and I blocked each other), plus closed ones I
                        // committed to that still wait for "Did you donate?". Hidden requests never show.
                        val visible = requests.listNotifiedTo(profile.id)
                            .map { it.effective(now) }
                            .filter { !it.hidden }
                            .filter { (it.status == RequestStatus.OPEN && it.requesterId !in blocked) || it.id in committed }
                        call.respond(visible.map { it.toDonorResponse(committed = it.id in committed) })
                    }

                    // The owner sees everything; a donor who was notified sees the donor view; anyone else gets 404.
                    get("/{id}") {
                        val profile = call.requireProfile(users)
                        val request = call.parameters["id"]?.toLongOrNull()?.let { requests.findById(it) }?.effective(clock.instant())
                        when {
                            request == null -> throw requestNotFound()
                            request.requesterId == profile.id -> call.respond(ownerView(request))
                            requests.isNotified(request.id, profile.id) -> call.respond(donorView(request, profile.id))
                            else -> throw requestNotFound()
                        }
                    }

                    post("/{id}/fulfill") {
                        call.closeOwnedRequest(RequestStatus.FULFILLED)
                    }

                    post("/{id}/cancel") {
                        call.closeOwnedRequest(RequestStatus.CANCELLED)
                    }

                    rateLimit(RateLimits.REPORT) {
                        post("/{id}/report") {
                            val profile = call.requireProfile(users)
                            val request = call.requestSeenAsDonor(profile, "You can't report your own request")
                            val body = call.receive<ReportBody>()
                            if (body.reason !in ReportReason.ALL) {
                                throw badRequest("invalid_reason", "reason must be one of ${ReportReason.ALL.joinToString()}")
                            }
                            val details = body.details?.trim()?.takeIf { it.isNotEmpty() }
                            if (details != null && details.length > MAX_REPORT_DETAILS) {
                                throw badRequest("details_too_long", "details can be at most $MAX_REPORT_DETAILS characters")
                            }
                            val result = safety.report(request.id, profile.id, body.reason, details, clock.instant(), safetyConfig.reportsToHide)
                            when (result) {
                                ReportResult.AlreadyReported -> throw ApiException(
                                    HttpStatusCode.Conflict, "already_reported", "You already reported this request",
                                )
                                is ReportResult.Reported -> if (result.hidden) onRequestEvent.onEvent(RequestEvent.RequestHidden(request.id))
                            }
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }

                    // A donor blocks the requester without ever learning who they are.
                    rateLimit(RateLimits.BLOCKS) {
                        post("/{id}/block-requester") {
                            val profile = call.requireProfile(users)
                            val request = call.requestSeenAsDonor(profile, "You can't block yourself")
                            if (request.requesterId != DELETED_USER_ID) {
                                safety.block(profile.id, request.requesterId, viaRequestId = request.id, clock.instant())
                            }
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }

                    // The requester confirms a helper after the blood bank tells them, open or closed.
                    post("/{id}/helpers/{pledgeId}/confirm") {
                        val request = call.ownedRequest(users, requests).effective(clock.instant())
                        val pledgeId = call.parameters["pledgeId"]?.toLongOrNull() ?: throw helperNotFound()
                        val outcome = call.receive<HelperConfirmBody>().outcome
                        if (outcome !in RequesterOutcome.ALL) throw badRequest("invalid_outcome", "outcome must be donated or no_show")
                        when (val result = pledges.confirmHelper(request.id, pledgeId, outcome, LocalDate.now(clock), clock.instant())) {
                            is HelperConfirmResult.Confirmed -> if (outcome == RequesterOutcome.NO_SHOW) {
                                // No-shows from several different requesters pause the donor's notifications.
                                val now = clock.instant()
                                safety.pauseIfNoShows(
                                    donorId = result.pledge.donorId,
                                    since = now.minus(safetyConfig.noShowWindow.toJavaDuration()),
                                    minRequesters = safetyConfig.noShowRequestersToPause,
                                    now = now,
                                    pauseUntil = now.plus(safetyConfig.noShowPause.toJavaDuration()),
                                )
                                onRequestEvent.onEvent(RequestEvent.HelperNoShow(request.id))
                            }
                            HelperConfirmResult.NotFound -> throw helperNotFound()
                            HelperConfirmResult.Withdrawn -> throw ApiException(
                                HttpStatusCode.Conflict, "helper_withdrew", "This donor withdrew",
                            )
                            HelperConfirmResult.AlreadyConfirmed -> throw ApiException(
                                HttpStatusCode.Conflict, "helper_already_confirmed", "You already confirmed this donor",
                            )
                        }
                        call.respond(ownerView(request))
                    }

                    rateLimit(RateLimits.HELP) {
                        route("/{id}/help") {
                            // "I can help": the donor commits to go to the hospital.
                            post {
                                val profile = call.requireProfile(users)
                                val request = call.requestForDonor(profile, requests, clock)
                                if (request.status != RequestStatus.OPEN) throw requestNotOpen()
                                if (request.requesterId in safety.blockedEitherWay(profile.id)) {
                                    throw ApiException(HttpStatusCode.Forbidden, "blocked", "You can't help with this request")
                                }
                                val bloodType = profile.bloodType
                                val donorTypes = Compatibility.donorTypesFor(request.bloodType, request.component)
                                if (!profile.isDonor || bloodType == null || bloodType !in donorTypes) {
                                    throw ApiException(HttpStatusCode.Conflict, "not_eligible", "Your blood type can't be used for this request")
                                }
                                if (!Eligibility.hasRecovered(profile.lastDonationDate, profile.sex, LocalDate.now(clock), config)) {
                                    throw ApiException(HttpStatusCode.Conflict, "donation_too_recent", "Your last donation was too recent")
                                }
                                when (val result = pledges.commit(request.id, profile.id, clock.instant(), MAX_COMMITTED_PLEDGES)) {
                                    is CommitResult.Committed -> {
                                        if (result.isNew) onRequestEvent.onEvent(RequestEvent.DonorCommitted(request.id, bloodType))
                                        call.respond(pledgeView(request, result.pledge)!!)
                                    }
                                    CommitResult.LimitReached -> throw ApiException(
                                        HttpStatusCode.Conflict, "pledge_limit",
                                        "You can commit to at most $MAX_COMMITTED_PLEDGES requests at a time",
                                    )
                                    CommitResult.AlreadyConfirmed -> throw ApiException(
                                        HttpStatusCode.Conflict, "pledge_closed", "You already said whether you donated for this request",
                                    )
                                }
                            }

                            // "I can't make it anymore".
                            delete {
                                val profile = call.requireProfile(users)
                                val request = call.requestForDonor(profile, requests, clock)
                                if (request.status != RequestStatus.OPEN) throw requestNotOpen()
                                if (pledges.find(request.id, profile.id)?.requesterOutcome != null) {
                                    throw ApiException(HttpStatusCode.Conflict, "pledge_closed", "The requester already confirmed your pledge")
                                }
                                if (!pledges.withdraw(request.id, profile.id, clock.instant())) throw noPledge()
                                onRequestEvent.onEvent(RequestEvent.DonorWithdrew(request.id))
                                call.respond(HttpStatusCode.NoContent)
                            }
                        }
                    }

                    // "Did you donate?" Only updates the donor's own last donation date, never confirmed_donation.
                    post("/{id}/donation") {
                        val profile = call.requireProfile(users)
                        val request = call.requestForDonor(profile, requests, clock)
                        val donated = call.receive<DonationBody>().donated
                        val pledge = pledges.find(request.id, profile.id)
                        if (pledge == null || pledge.status != PledgeStatus.COMMITTED) throw noPledge()
                        if (!canConfirmDonation(request, pledge, clock.instant(), arrivalWindow)) {
                            throw ApiException(
                                HttpStatusCode.Conflict, "too_early",
                                "You can answer once the request closes or your arrival window ends",
                            )
                        }
                        pledges.confirmDonation(request.id, profile.id, donated, LocalDate.now(clock), clock.instant())
                            ?: throw noPledge()
                        call.respond(donorView(request, profile.id))
                    }
                }
            }
        }
    }
}

private fun RoutingCall.user(): UserPrincipal = principal<UserPrincipal>()!!

private suspend fun RoutingCall.requireProfile(users: UserRepository): UserProfile =
    users.findByUid(user().uid)
        ?: throw ApiException(HttpStatusCode.Conflict, "profile_required", "Create your profile first with PUT /me")

/** The request in the {id} path segment. Someone else's request is reported as missing, not forbidden. */
private suspend fun RoutingCall.ownedRequest(users: UserRepository, requests: BloodRequestRepository): BloodRequest {
    val profile = requireProfile(users)
    val request = parameters["id"]?.toLongOrNull()?.let { requests.findById(it) }
    if (request == null || request.requesterId != profile.id) throw requestNotFound()
    return request
}

/**
 * The request in the {id} path segment, for a donor action: 404 unless the user was notified about it.
 * Its status is the effective one.
 */
private suspend fun RoutingCall.requestForDonor(profile: UserProfile, requests: BloodRequestRepository, clock: Clock): BloodRequest {
    val request = parameters["id"]?.toLongOrNull()?.let { requests.findById(it) }?.effective(clock.instant())
        ?: throw requestNotFound()
    if (request.requesterId == profile.id) {
        throw ApiException(HttpStatusCode.Conflict, "own_request", "You can't help with your own request")
    }
    if (!requests.isNotified(request.id, profile.id)) throw requestNotFound()
    return request
}

/** True once a committed donor may answer "Did you donate?": the request closed or the arrival window ended. */
fun canConfirmDonation(request: BloodRequest, pledge: Pledge, now: Instant, arrivalWindow: java.time.Duration): Boolean =
    pledge.status == PledgeStatus.COMMITTED &&
        (request.effective(now).status != RequestStatus.OPEN || !now.isBefore(pledge.committedAt.plus(arrivalWindow)))

/** The first word of a full name: the only part of the requester's name a donor sees. */
private fun String.firstName(): String = trim().split(Regex("\\s+")).first()

private fun userNotFound() = ApiException(HttpStatusCode.NotFound, "user_not_found", "No such user")

private fun postingSuspended(suspension: Suspension) = ApiException(
    HttpStatusCode.Forbidden, "posting_suspended",
    if (suspension.permanent) "Posting is permanently suspended for this account"
    else "Posting is suspended until ${suspension.until}",
    mapOf("until" to (suspension.until?.toString() ?: "permanent")),
)

private fun requestNotFound() = ApiException(HttpStatusCode.NotFound, "request_not_found", "No such request")

private fun requestNotOpen() = ApiException(HttpStatusCode.Conflict, "request_not_open", "Only open requests can be changed")

private fun helperNotFound() = ApiException(HttpStatusCode.NotFound, "helper_not_found", "No such helper for this request")

private fun noPledge() = ApiException(HttpStatusCode.Conflict, "no_pledge", "You haven't committed to this request")

private fun DeviceTokenBody.validToken(): String =
    token.trim().takeIf { it.isNotEmpty() && it.length <= 4096 && it.none(Char::isWhitespace) }
        ?: throw badRequest("invalid_token", "token must be a non-empty FCM registration token")
