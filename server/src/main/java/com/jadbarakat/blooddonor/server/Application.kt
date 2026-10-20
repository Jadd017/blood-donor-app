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
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.ZoneId

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
) {
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
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Request body is missing or malformed"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ApiError("unsupported_media_type", "Send JSON with Content-Type: application/json"),
            )
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error on ${call.request.local.uri}", e)
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
    installRateLimits(safetyConfig, tokens)
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

    routing {
        get("/health") {
            call.respondText(
                if (healthCheck()) "Server OK, database OK"
                else "Server OK, database NOT reachable"
            )
        }
        apiV1(
            users, areas, hospitals, requests, deviceTokens, pledges, safety, accountDeleter,
            onRequestCreated, onRequestEvent, config, safetyConfig, clock,
        )
    }
}
