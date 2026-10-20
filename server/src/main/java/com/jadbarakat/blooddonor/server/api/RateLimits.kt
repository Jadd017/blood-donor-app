package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.auth.TokenVerifier
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.util.AttributeKey
import kotlin.time.Duration.Companion.minutes

/** Named rate limits. Each counts per signed-in user, or per IP address when there is no valid token. */
object RateLimits {
    /** Unauthenticated API calls (GET /areas). */
    val PUBLIC = RateLimitName("public")
    /** Every authenticated API call. */
    val GENERAL = RateLimitName("general")
    val POST_REQUEST = RateLimitName("post-request")
    val HELP = RateLimitName("help")
    val REPORT = RateLimitName("report")
    val BLOCKS = RateLimitName("blocks")
}

/**
 * Verifies a call's Firebase ID token at most once. The rate limiter runs before authentication,
 * so it verifies the token itself to count per user; authentication then reuses the result.
 * Keying on the verified uid (not an unverified claim) means nobody can use up someone else's limit.
 */
class CallTokenVerifier(private val verifier: TokenVerifier) {
    private class Verified(val token: VerifiedToken?)

    private val key = AttributeKey<Verified>("VerifiedFirebaseToken")

    suspend fun verify(call: ApplicationCall, idToken: String): VerifiedToken? {
        call.attributes.getOrNull(key)?.let { return it.token }
        return verifier.verify(idToken).also { call.attributes.put(key, Verified(it)) }
    }

    /** The verified uid of the call's bearer token, if it has a valid one. */
    suspend fun uid(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        return verify(call, header.substring("Bearer ".length).trim())?.uid
    }
}

fun Application.installRateLimits(config: SafetyConfig, tokens: CallTokenVerifier) {
    // No global limit: it would count every call of every user behind one carrier IP together.
    install(RateLimit) {
        for ((name, perMinute) in listOf(
            RateLimits.PUBLIC to config.rateLimitGeneralPerMinute,
            RateLimits.GENERAL to config.rateLimitGeneralPerMinute,
            RateLimits.POST_REQUEST to config.rateLimitPostRequestPerMinute,
            RateLimits.HELP to config.rateLimitHelpPerMinute,
            RateLimits.REPORT to config.rateLimitReportPerMinute,
            RateLimits.BLOCKS to config.rateLimitBlocksPerMinute,
        )) {
            register(name) {
                rateLimiter(limit = perMinute, refillPeriod = 1.minutes)
                requestKey { call -> tokens.uid(call)?.let { "user:$it" } ?: "ip:${call.request.origin.remoteHost}" }
            }
        }
    }
}
