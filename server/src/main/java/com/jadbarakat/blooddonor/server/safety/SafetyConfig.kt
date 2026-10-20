package com.jadbarakat.blooddonor.server.safety

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Automatic anti-spam and safety thresholds. Defaults are the production values; each can be
 * overridden with the environment variable named next to it (see server/.env.example).
 */
data class SafetyConfig(
    /** Open requests a user may have at once. */
    val maxOpenRequests: Int = 2,                 // MAX_OPEN_REQUESTS
    /** Accounts younger than [newAccountAge] may only have this many open requests. */
    val maxOpenRequestsNewAccount: Int = 1,       // MAX_OPEN_REQUESTS_NEW_ACCOUNT
    val newAccountAge: Duration = 7.days,         // NEW_ACCOUNT_DAYS
    /** Requests a user may create in any rolling 24 hours. */
    val maxRequestsPer24h: Int = 3,               // MAX_REQUESTS_PER_24H
    /** Reports from different users that hide a request and give its requester a strike. */
    val reportsToHide: Int = 3,                   // REPORTS_TO_HIDE
    /** Posting suspension after the 1st strike, and after a 2nd within [secondStrikeWindow]. */
    val firstStrikeSuspension: Duration = 7.days,   // FIRST_STRIKE_SUSPENSION_DAYS
    val secondStrikeSuspension: Duration = 30.days, // SECOND_STRIKE_SUSPENSION_DAYS
    val secondStrikeWindow: Duration = 90.days,     // SECOND_STRIKE_WINDOW_DAYS
    /** This many strikes ever suspend posting for good. */
    val strikesForPermanentSuspension: Int = 3,     // STRIKES_FOR_PERMANENT_SUSPENSION
    /** No-shows from this many DIFFERENT requesters within [noShowWindow] pause notifications for [noShowPause]. */
    val noShowRequestersToPause: Int = 3,         // NO_SHOW_REQUESTERS_TO_PAUSE
    val noShowWindow: Duration = 90.days,         // NO_SHOW_WINDOW_DAYS
    val noShowPause: Duration = 30.days,          // NO_SHOW_PAUSE_DAYS
    /** Calls per minute and user (or IP when signed out): overall, and for the actions that can be abused. */
    val rateLimitGeneralPerMinute: Int = 120,     // RATE_LIMIT_GENERAL_PER_MINUTE
    val rateLimitPostRequestPerMinute: Int = 5,   // RATE_LIMIT_POST_REQUEST_PER_MINUTE
    val rateLimitHelpPerMinute: Int = 10,         // RATE_LIMIT_HELP_PER_MINUTE
    val rateLimitReportPerMinute: Int = 5,        // RATE_LIMIT_REPORT_PER_MINUTE
    val rateLimitBlocksPerMinute: Int = 20,       // RATE_LIMIT_BLOCKS_PER_MINUTE
) {
    /** The rolling window of [maxRequestsPer24h]. */
    val dailyWindow: Duration get() = 24.hours

    init {
        require(maxOpenRequests > 0 && maxOpenRequestsNewAccount > 0 && maxRequestsPer24h > 0) { "Posting limits must be positive" }
        require(reportsToHide > 0 && strikesForPermanentSuspension > 1 && noShowRequestersToPause > 1) {
            "Report, strike and no-show thresholds are too low"
        }
        require(
            listOf(rateLimitGeneralPerMinute, rateLimitPostRequestPerMinute, rateLimitHelpPerMinute,
                rateLimitReportPerMinute, rateLimitBlocksPerMinute).all { it > 0 }
        ) { "Rate limits must be positive" }
    }

    companion object {
        fun fromEnv(env: (String) -> String?): SafetyConfig {
            val d = SafetyConfig()
            fun int(name: String, default: Int) = env(name)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$name must be a whole number")
            } ?: default
            fun days(name: String, default: Duration) = int(name, default.inWholeDays.toInt()).days
            return SafetyConfig(
                maxOpenRequests = int("MAX_OPEN_REQUESTS", d.maxOpenRequests),
                maxOpenRequestsNewAccount = int("MAX_OPEN_REQUESTS_NEW_ACCOUNT", d.maxOpenRequestsNewAccount),
                newAccountAge = days("NEW_ACCOUNT_DAYS", d.newAccountAge),
                maxRequestsPer24h = int("MAX_REQUESTS_PER_24H", d.maxRequestsPer24h),
                reportsToHide = int("REPORTS_TO_HIDE", d.reportsToHide),
                firstStrikeSuspension = days("FIRST_STRIKE_SUSPENSION_DAYS", d.firstStrikeSuspension),
                secondStrikeSuspension = days("SECOND_STRIKE_SUSPENSION_DAYS", d.secondStrikeSuspension),
                secondStrikeWindow = days("SECOND_STRIKE_WINDOW_DAYS", d.secondStrikeWindow),
                strikesForPermanentSuspension = int("STRIKES_FOR_PERMANENT_SUSPENSION", d.strikesForPermanentSuspension),
                noShowRequestersToPause = int("NO_SHOW_REQUESTERS_TO_PAUSE", d.noShowRequestersToPause),
                noShowWindow = days("NO_SHOW_WINDOW_DAYS", d.noShowWindow),
                noShowPause = days("NO_SHOW_PAUSE_DAYS", d.noShowPause),
                rateLimitGeneralPerMinute = int("RATE_LIMIT_GENERAL_PER_MINUTE", d.rateLimitGeneralPerMinute),
                rateLimitPostRequestPerMinute = int("RATE_LIMIT_POST_REQUEST_PER_MINUTE", d.rateLimitPostRequestPerMinute),
                rateLimitHelpPerMinute = int("RATE_LIMIT_HELP_PER_MINUTE", d.rateLimitHelpPerMinute),
                rateLimitReportPerMinute = int("RATE_LIMIT_REPORT_PER_MINUTE", d.rateLimitReportPerMinute),
                rateLimitBlocksPerMinute = int("RATE_LIMIT_BLOCKS_PER_MINUTE", d.rateLimitBlocksPerMinute),
            )
        }
    }
}
