package com.jadbarakat.blooddonor.server.safety

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrikeRulesTest {

    private val config = SafetyConfig()
    private val t0 = Instant.parse("2026-01-01T10:00:00Z")

    private fun days(n: Long) = Duration.ofDays(n)
    private fun suspension(strikes: List<Instant>, now: Instant) = StrikeRules.suspension(strikes, now, config)

    @Test
    fun `no strikes, no suspension`() {
        assertNull(suspension(emptyList(), t0))
    }

    @Test
    fun `first strike suspends posting for 7 days`() {
        assertEquals(Suspension(t0.plus(days(7)), permanent = false), suspension(listOf(t0), t0.plus(days(1))))
        assertNull(suspension(listOf(t0), t0.plus(days(7))))
    }

    @Test
    fun `second strike within 90 days suspends for 30 days`() {
        val second = t0.plus(days(90))
        assertEquals(Suspension(second.plus(days(30)), permanent = false), suspension(listOf(t0, second), second))
        assertNull(suspension(listOf(t0, second), second.plus(days(30))))
    }

    @Test
    fun `second strike after 90 days counts like a first one`() {
        val second = t0.plus(days(91))
        assertEquals(Suspension(second.plus(days(7)), permanent = false), suspension(listOf(second, t0), second))
    }

    @Test
    fun `third strike ever is permanent, however far apart`() {
        val strikes = listOf(t0, t0.plus(days(200)), t0.plus(days(400)))
        assertEquals(Suspension(until = null, permanent = true), suspension(strikes, t0.plus(days(4000))))
    }

    @Test
    fun `thresholds come from the config`() {
        val custom = SafetyConfig(firstStrikeSuspension = kotlin.time.Duration.parse("1d"), strikesForPermanentSuspension = 2)
        assertEquals(t0.plus(days(1)), StrikeRules.suspension(listOf(t0), t0, custom)?.until)
        assertEquals(true, StrikeRules.suspension(listOf(t0, t0.plus(days(500))), t0, custom)?.permanent)
    }

    @Test
    fun `settings are read from the environment`() {
        val env = mapOf("MAX_OPEN_REQUESTS" to "4", "NEW_ACCOUNT_DAYS" to "3", "REPORTS_TO_HIDE" to "5")
        val parsed = SafetyConfig.fromEnv { env[it] }
        assertEquals(4, parsed.maxOpenRequests)
        assertEquals(3, parsed.newAccountAge.inWholeDays)
        assertEquals(5, parsed.reportsToHide)
        assertEquals(SafetyConfig().maxRequestsPer24h, parsed.maxRequestsPer24h)
    }
}
