package com.jadbarakat.blooddonor.server.safety

import java.time.Instant
import kotlin.time.toJavaDuration

/** A posting suspension: until [until], or for good when [permanent]. */
data class Suspension(val until: Instant?, val permanent: Boolean)

/**
 * How strikes suspend posting. Worked out from the strike history on every check, so nothing
 * has to be stored or expired:
 * - 1st strike: [SafetyConfig.firstStrikeSuspension] from that strike;
 * - 2nd strike within [SafetyConfig.secondStrikeWindow] of the previous one: [SafetyConfig.secondStrikeSuspension]
 *   (a 2nd strike after that window counts like a 1st);
 * - [SafetyConfig.strikesForPermanentSuspension] strikes ever: permanent.
 */
object StrikeRules {
    fun suspension(strikeTimes: List<Instant>, now: Instant, config: SafetyConfig): Suspension? {
        if (strikeTimes.isEmpty()) return null
        if (strikeTimes.size >= config.strikesForPermanentSuspension) return Suspension(until = null, permanent = true)
        val sorted = strikeTimes.sorted()
        val latest = sorted.last()
        val previous = sorted.getOrNull(sorted.size - 2)
        val repeat = previous != null && !latest.isAfter(previous.plus(config.secondStrikeWindow.toJavaDuration()))
        val length = if (repeat) config.secondStrikeSuspension else config.firstStrikeSuspension
        val until = latest.plus(length.toJavaDuration())
        return if (now.isBefore(until)) Suspension(until, permanent = false) else null
    }
}
