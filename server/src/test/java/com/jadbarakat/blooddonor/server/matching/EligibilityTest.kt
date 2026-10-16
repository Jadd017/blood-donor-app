package com.jadbarakat.blooddonor.server.matching

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class EligibilityTest {

    private val config = MatchingConfig()
    private val today = LocalDate.of(2026, 6, 15)
    private val requesterId = 1L
    private val oNeg = setOf("O-")

    private fun candidate(
        userId: Long = 2,
        bloodType: String? = "O-",
        sex: String? = "male",
        lastDonation: LocalDate? = null,
        isDonor: Boolean = true,
        hasToken: Boolean = true,
        distanceKm: Double = 5.0,
        recent: Int = 0,
    ) = DonorCandidate(userId, isDonor, bloodType, sex, lastDonation, hasToken, distanceKm * 1000, recent)

    private fun eligible(c: DonorCandidate) = Eligibility.isEligible(c, requesterId, oNeg, today, config)

    @Test
    fun `male gap is 90 days`() {
        assertFalse(eligible(candidate(sex = "male", lastDonation = today.minusDays(89))))
        assertTrue(eligible(candidate(sex = "male", lastDonation = today.minusDays(90))))
    }

    @Test
    fun `female gap is 120 days`() {
        assertFalse(eligible(candidate(sex = "female", lastDonation = today.minusDays(119))))
        assertTrue(eligible(candidate(sex = "female", lastDonation = today.minusDays(120))))
    }

    @Test
    fun `unknown sex uses the longer 120 day gap`() {
        assertFalse(eligible(candidate(sex = null, lastDonation = today.minusDays(90))))
        assertFalse(eligible(candidate(sex = null, lastDonation = today.minusDays(119))))
        assertTrue(eligible(candidate(sex = null, lastDonation = today.minusDays(120))))
    }

    @Test
    fun `never donated is eligible, donated today is not`() {
        assertTrue(eligible(candidate(lastDonation = null)))
        assertFalse(eligible(candidate(lastDonation = today)))
    }

    @Test
    fun `gaps come from the config`() {
        val short = MatchingConfig(gapDaysMale = 56)
        assertTrue(Eligibility.isEligible(candidate(lastDonation = today.minusDays(56)), requesterId, oNeg, today, short))
    }

    @Test
    fun `requester is never their own donor`() {
        assertFalse(eligible(candidate(userId = requesterId)))
    }

    @Test
    fun `non-donors, unknown or incompatible blood types and no device are excluded`() {
        assertFalse(eligible(candidate(isDonor = false)))
        assertFalse(eligible(candidate(bloodType = null)))
        assertFalse(eligible(candidate(bloodType = "O+")))
        assertFalse(eligible(candidate(hasToken = false)))
    }

    @Test
    fun `at most 3 notifications per 24 hours`() {
        assertTrue(eligible(candidate(recent = 2)))
        assertFalse(eligible(candidate(recent = 3)))
    }

    @Test
    fun `select takes the closest eligible donors up to the wave limit`() {
        val small = MatchingConfig(maxDonorsPerWave = 2)
        val picked = Eligibility.select(
            listOf(
                candidate(userId = 10, distanceKm = 9.0),
                candidate(userId = 11, distanceKm = 1.0),
                candidate(userId = 12, distanceKm = 0.5, recent = 3), // capped
                candidate(userId = 13, distanceKm = 4.0),
            ),
            requesterId, oNeg, today, small,
        )
        assertEquals(listOf(11L, 13L), picked.map { it.userId })
    }

    @Test
    fun `config can be overridden from the environment`() {
        val env = mapOf("DONATION_GAP_DAYS_MALE" to "60", "WAVE_2_AFTER_MINUTES" to "20", "WAVE_2_RADIUS_KM" to "25")
        val config = MatchingConfig.fromEnv { env[it] }
        assertEquals(60, config.gapDaysMale)
        assertEquals(120, config.gapDaysFemale)
        assertEquals(MatchingConfig.Wave(20.minutes, 25.0), config.waves[1])
        assertEquals(null, config.waves[3].radiusKm)
        assertFailsWith<IllegalArgumentException> { MatchingConfig.fromEnv { if (it == "MAX_DONORS_PER_WAVE") "lots" else null } }
    }
}
