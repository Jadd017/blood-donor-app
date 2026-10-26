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
    /** Every call to an authenticated route, including ones whose token turns out to be missing or invalid. */
    val GENERAL = RateLimitName("general")
    /** PUT and DELETE /me. */
    val PROFILE = RateLimitName("profile")
    val DEVICE_TOKEN = RateLimitName("device-token")
    /** Fulfill, cancel, confirm a helper, "Did you donate?". */
    val WRITE = RateLimitName("write")
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

/**
 * The caller's IP address. X-Forwarded-For is only believed when the direct peer is one of
 * [trustedProxies] (our own reverse proxy); otherwise anyone could pick a fresh IP per call.
 * The client is the right-most forwarded address that isn't one of our proxies.
 */
fun clientIp(peer: String, forwardedFor: String?, trustedProxies: Set<String>): String {
    if (peer !in trustedProxies || forwardedFor.isNullOrBlank()) return peer
    return forwardedFor.split(',').map { it.trim() }.lastOrNull { it.isNotEmpty() && it !in trustedProxies } ?: peer
}

fun Application.installRateLimits(config: SafetyConfig, tokens: CallTokenVerifier, trustedProxies: Set<String>) {
    // No global limit: it would count every call of every user behind one carrier IP together.
    install(RateLimit) {
        for ((name, perMinute) in listOf(
            RateLimits.PUBLIC to config.rateLimitGeneralPerMinute,
            RateLimits.GENERAL to config.rateLimitGeneralPerMinute,
            RateLimits.POST_REQUEST to config.rateLimitPostRequestPerMinute,
            RateLimits.HELP to config.rateLimitHelpPerMinute,
            RateLimits.REPORT to config.rateLimitReportPerMinute,
            RateLimits.BLOCKS to config.rateLimitBlocksPerMinute,
            RateLimits.PROFILE to config.rateLimitProfilePerMinute,
            RateLimits.DEVICE_TOKEN to config.rateLimitDeviceTokenPerMinute,
            RateLimits.WRITE to config.rateLimitWritePerMinute,
        )) {
            register(name) {
                rateLimiter(limit = perMinute, refillPeriod = 1.minutes)
                requestKey { call ->
                    tokens.uid(call)?.let { "user:$it" }
                        ?: "ip:" + clientIp(call.request.origin.remoteAddress, call.request.headers[HttpHeaders.XForwardedFor], trustedProxies)
                }
            }
        }
    }
}
