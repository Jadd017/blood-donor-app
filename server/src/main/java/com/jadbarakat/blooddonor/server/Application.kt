package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.api.ApiError
import com.jadbarakat.blooddonor.server.api.ApiException
import com.jadbarakat.blooddonor.server.api.CallTokenVerifier
import com.jadbarakat.blooddonor.server.api.FIREBASE_AUTH
import com.jadbarakat.blooddonor.server.api.RequestCreatedListener
import com.jadbarakat.blooddonor.server.api.RequestEventListener
import com.jadbarakat.blooddonor.server.api.apiV1
import com.jadbarakat.blooddonor.server.api.installRateLimits
import com.jadbarakat.blooddonor.server.auth.AccountDeleter
import com.jadbarakat.blooddonor.server.auth.AuthUnavailableException
import com.jadbarakat.blooddonor.server.auth.TokenVerifier
import com.jadbarakat.blooddonor.server.auth.UserPrincipal
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.DeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.HospitalRepository
import com.jadbarakat.blooddonor.server.data.PledgeRepository
import com.jadbarakat.blooddonor.server.data.SafetyRepository
import com.jadbarakat.blooddonor.server.data.UserRepository
import com.jadbarakat.blooddonor.server.matching.MatchingConfig
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.sql.SQLException
import java.time.Clock
import java.time.ZoneId

/** Every request body is small JSON; anything bigger is rejected with 413 before it is read. */
const val MAX_BODY_BYTES = 16L * 1024

private suspend fun ApplicationCall.respondPayloadTooLarge() = respond(
    HttpStatusCode.PayloadTooLarge,
    ApiError("payload_too_large", "Request body is larger than $MAX_BODY_BYTES bytes"),
)

/** A JSON API for the app only: no browser may frame, sniff, cache or embed its responses. */
private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        with(call.response.headers) {
            append("X-Content-Type-Options", "nosniff")
            append(HttpHeaders.CacheControl, "no-store")
            append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            append("Referrer-Policy", "no-referrer")
            append(HttpHeaders.StrictTransportSecurity, "max-age=31536000")
        }
    }
}

/** All HTTP wiring. Takes its dependencies as parameters so tests can pass fakes. */
fun Application.module(
    verifier: TokenVerifier,
    users: UserRepository,
    areas: AreaRepository,
    hospitals: HospitalRepository,
    requests: BloodRequestRepository,
    deviceTokens: DeviceTokenRepository,
    pledges: PledgeRepository,
    safety: SafetyRepository,
    accountDeleter: AccountDeleter,
    onRequestCreated: RequestCreatedListener = RequestCreatedListener {},
    onRequestEvent: RequestEventListener = RequestEventListener {},
    config: MatchingConfig = MatchingConfig(),
    safetyConfig: SafetyConfig = SafetyConfig(),
    clock: Clock = Clock.system(ZoneId.of("Asia/Beirut")),
    healthCheck: () -> Boolean = { true },
    /** Reverse proxies whose X-Forwarded-For is believed for per-IP rate limits. */
    trustedProxies: Set<String> = emptySet(),
) {
    install(RequestBodyLimit) { bodyLimit { MAX_BODY_BYTES } }
    install(SecurityHeaders)
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true // e.g. a client-sent "email" is simply ignored
            explicitNulls = false    // omit null fields to keep responses small
        })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ApiError(e.code, e.message, e.details))
        }
        exception<BadRequestException> { call, e ->
            // A chunked body can hit the size limit mid-deserialization, where receive() wraps it.
            if (generateSequence<Throwable>(e) { it.cause }.any { it is PayloadTooLargeException }) {
                call.respondPayloadTooLarge()
            } else {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Request body is missing or malformed"))
            }
        }
        exception<PayloadTooLargeException> { call, _ -> call.respondPayloadTooLarge() }
        exception<AuthUnavailableException> { call, e ->
            call.application.environment.log.warn("Firebase token check unavailable: {}", e.cause?.javaClass?.name)
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("auth_unavailable", "Sign-in can't be checked right now, try again"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ApiError("unsupported_media_type", "Send JSON with Content-Type: application/json"),
            )
        }
        exception<Throwable> { call, e ->
            val log = call.application.environment.log
            // Database errors can quote the failing row (names, phones, emails): log only what kind it was.
            val sql = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            if (sql != null) log.error("Unhandled database error {} (SQLState {}) on {}", sql.javaClass.name, sql.sqlState, call.request.local.uri)
            else log.error("Unhandled error on ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Something went wrong"))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ApiError("unauthorized", "Missing or invalid Firebase ID token"))
        }
        // Only the rate limiter answers 429 without a body: errors thrown as ApiException
        // (too_many_open_requests, daily_limit) are already handled and never reach this.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ApiError("rate_limited", "Too many requests, try again in a minute"))
        }
    }
    val tokens = CallTokenVerifier(verifier)
    installRateLimits(safetyConfig, tokens, trustedProxies)
    install(Authentication) {
        bearer(FIREBASE_AUTH) {
            authenticate { credential ->
                val token = tokens.verify(this, credential.token) ?: return@authenticate null
                if (!token.emailVerified || token.email == null) {
                    throw ApiException(HttpStatusCode.Forbidden, "email_not_verified", "Verify your email address first")
                }
                UserPrincipal(token.uid, token.email.lowercase())
            }
        }
    }

    // A deleted account's next call must ask Firebase again (and be refused), not use the cached check.
    val forgetfulDeleter = AccountDeleter { uid ->
        accountDeleter.deleteFirebaseUser(uid)
        verifier.forget(uid)
    }

    routing {
        // For uptime checks: says nothing about what is behind it.
        get("/health") {
            val healthy = runCatching(healthCheck).getOrDefault(false)
            if (healthy) call.respondText("OK")
            else call.respondText("Unavailable", status = HttpStatusCode.ServiceUnavailable)
        }
        apiV1(
            users, areas, hospitals, requests, deviceTokens, pledges, safety, forgetfulDeleter,
            onRequestCreated, onRequestEvent, config, safetyConfig, clock,
        )
    }
}
