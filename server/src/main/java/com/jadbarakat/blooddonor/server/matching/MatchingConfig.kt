package com.jadbarakat.blooddonor.server.matching

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Tunable matching and notification settings. Defaults are the production values; each can be
 * overridden with the environment variable named next to it (see server/.env.example).
 */
data class MatchingConfig(
    /** Days a donor must wait after donating. The whole-blood gap is used for all components for now. */
    val gapDaysMale: Int = 90,          // DONATION_GAP_DAYS_MALE
    val gapDaysFemale: Int = 120,       // DONATION_GAP_DAYS_FEMALE
    val gapDaysUnknownSex: Int = 120,   // DONATION_GAP_DAYS_UNKNOWN_SEX
    /** One entry per wave: when it starts (after the request is created) and how far it reaches. */
    val waves: List<Wave> = listOf(
        Wave(after = Duration.ZERO, radiusKm = 15.0),   // WAVE_1_RADIUS_KM
        Wave(after = 30.minutes, radiusKm = 30.0),      // WAVE_2_AFTER_MINUTES, WAVE_2_RADIUS_KM
        Wave(after = 60.minutes, radiusKm = 60.0),      // WAVE_3_...
        Wave(after = 120.minutes, radiusKm = null),     // WAVE_4_AFTER_MINUTES; null radius = all of Lebanon
    ),
    val maxDonorsPerWave: Int = 50,           // MAX_DONORS_PER_WAVE
    val maxNotificationsPer24h: Int = 3,      // MAX_NOTIFICATIONS_PER_24H
    val jobInterval: Duration = 2.minutes,    // JOB_INTERVAL_MINUTES
    /** A committed donor counts as a helper for this long after committing (or until the deadline). */
    val arrivalWindow: Duration = 3.hours,    // ARRIVAL_WINDOW_MINUTES
    /** Waves stop once helpers reach units_needed + this, in case someone doesn't show up. */
    val helperBuffer: Int = 1,                // HELPER_BUFFER
) {
    data class Wave(val after: Duration, val radiusKm: Double?)

    init {
        require(waves.isNotEmpty() && waves.first().after == Duration.ZERO) { "Wave 1 must start immediately" }
        require(waves.zipWithNext().all { (a, b) -> b.after > a.after }) { "Waves must start in order" }
        require(maxDonorsPerWave > 0 && maxNotificationsPer24h > 0) { "Limits must be positive" }
        require(arrivalWindow.isPositive()) { "ARRIVAL_WINDOW_MINUTES must be positive" }
        require(helperBuffer >= 0) { "HELPER_BUFFER can't be negative" }
    }

    fun gapDays(sex: String?): Int = when (sex) {
        "male" -> gapDaysMale
        "female" -> gapDaysFemale
        else -> gapDaysUnknownSex
    }

    companion object {
        fun fromEnv(env: (String) -> String?): MatchingConfig {
            val defaults = MatchingConfig()
            fun int(name: String, default: Int) = env(name)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$name must be a whole number")
            } ?: default
            fun dbl(name: String, default: Double?) = env(name)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toDoubleOrNull() ?: throw IllegalArgumentException("$name must be a number")
            } ?: default
            return MatchingConfig(
                gapDaysMale = int("DONATION_GAP_DAYS_MALE", defaults.gapDaysMale),
                gapDaysFemale = int("DONATION_GAP_DAYS_FEMALE", defaults.gapDaysFemale),
                gapDaysUnknownSex = int("DONATION_GAP_DAYS_UNKNOWN_SEX", defaults.gapDaysUnknownSex),
                waves = defaults.waves.mapIndexed { i, wave ->
                    val n = i + 1
                    Wave(
                        after = if (n == 1) Duration.ZERO
                        else int("WAVE_${n}_AFTER_MINUTES", wave.after.inWholeMinutes.toInt()).minutes,
                        radiusKm = if (wave.radiusKm == null) null else dbl("WAVE_${n}_RADIUS_KM", wave.radiusKm),
                    )
                },
                maxDonorsPerWave = int("MAX_DONORS_PER_WAVE", defaults.maxDonorsPerWave),
                maxNotificationsPer24h = int("MAX_NOTIFICATIONS_PER_24H", defaults.maxNotificationsPer24h),
                jobInterval = int("JOB_INTERVAL_MINUTES", defaults.jobInterval.inWholeMinutes.toInt()).minutes,
                arrivalWindow = int("ARRIVAL_WINDOW_MINUTES", defaults.arrivalWindow.inWholeMinutes.toInt()).minutes,
                helperBuffer = int("HELPER_BUFFER", defaults.helperBuffer),
            )
        }
    }
}
