package com.jadbarakat.blooddonor.server.matching

import java.time.LocalDate

/** A possible donor for one request, as found by the matching query. */
data class DonorCandidate(
    val userId: Long,
    val isDonor: Boolean,
    val bloodType: String?,
    val sex: String?,
    val lastDonationDate: LocalDate?,
    val hasDeviceToken: Boolean,
    /** From the donor's area centroid to the hospital. */
    val distanceMeters: Double,
    /** Request notifications this donor received in the last 24 hours. */
    val notificationsLast24h: Int,
)

object Eligibility {

    /** True once at least the gap for the donor's sex has passed since [lastDonation] (or they never donated). */
    fun hasRecovered(lastDonation: LocalDate?, sex: String?, today: LocalDate, config: MatchingConfig): Boolean =
        lastDonation == null || !lastDonation.plusDays(config.gapDays(sex).toLong()).isAfter(today)

    fun isEligible(
        candidate: DonorCandidate,
        requesterId: Long,
        donorTypes: Set<String>,
        today: LocalDate,
        config: MatchingConfig,
    ): Boolean =
        candidate.isDonor &&
            candidate.bloodType != null &&
            candidate.bloodType in donorTypes &&
            candidate.userId != requesterId &&
            candidate.hasDeviceToken &&
            candidate.notificationsLast24h < config.maxNotificationsPer24h &&
            hasRecovered(candidate.lastDonationDate, candidate.sex, today, config)

    /** The donors to notify in one wave: eligible ones, closest first, at most [MatchingConfig.maxDonorsPerWave]. */
    fun select(
        candidates: List<DonorCandidate>,
        requesterId: Long,
        donorTypes: Set<String>,
        today: LocalDate,
        config: MatchingConfig,
    ): List<DonorCandidate> =
        candidates.asSequence()
            .filter { isEligible(it, requesterId, donorTypes, today, config) }
            .sortedWith(compareBy<DonorCandidate> { it.distanceMeters }.thenBy { it.userId })
            .take(config.maxDonorsPerWave)
            .toList()
}
