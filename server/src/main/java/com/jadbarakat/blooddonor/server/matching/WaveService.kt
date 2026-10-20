package com.jadbarakat.blooddonor.server.matching

import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.DeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.MatchingRepository
import com.jadbarakat.blooddonor.server.data.RequestStatus
import com.jadbarakat.blooddonor.server.push.PushMessage
import com.jadbarakat.blooddonor.server.push.PushSender
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.time.toJavaDuration

/**
 * How many donors count as helping with a request at [now] (see HelperRules). Once it reaches
 * units_needed + the buffer, due waves are held back, not used up, so they go out if a donor
 * withdraws, doesn't show up or their arrival window ends.
 */
fun interface HelperCounter {
    suspend fun helpersFor(requestId: Long, now: Instant): Int
}

data class WaveResult(
    val requestId: Long,
    val wave: Int,
    val radiusKm: Double?,
    /** Eligible donors found (before the per-wave limit). */
    val candidates: Int,
    /** Donors at least one of whose devices received the notification. */
    val sent: Int,
    /** Selected donors none of whose devices received it. */
    val failed: Int,
)

/**
 * Notifies matching donors in waves that reach further over time (15 km right away, then
 * 30 km, 60 km and all of Lebanon), while the request is open and still needs helpers.
 */
class WaveService(
    private val requests: BloodRequestRepository,
    private val matching: MatchingRepository,
    private val tokens: DeviceTokenRepository,
    private val push: PushSender,
    private val helpers: HelperCounter,
    private val config: MatchingConfig,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(WaveService::class.java)

    /** Sends every wave that is due. Called by the background job. */
    suspend fun runDueWaves(): List<WaveResult> {
        val now = clock.instant()
        return matching.requestsAwaitingWaves(now, config.waves.size).mapNotNull { request ->
            try {
                sendDueWave(request, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Wave for request {} failed", request.id, e)
                null
            }
        }
    }

    /** Sends the first wave for a new request (called right after it is created). */
    suspend fun runDueWaveFor(requestId: Long): WaveResult? {
        val request = requests.findById(requestId) ?: return null
        return sendDueWave(request, clock.instant())
    }

    /**
     * The wave number that should have been sent by [now], if it is later than the ones already sent.
     * If the job fell behind, it jumps straight to the latest due wave (its radius covers the earlier ones).
     */
    fun dueWave(request: BloodRequest, now: Instant): Int? {
        val due = config.waves.indexOfLast { !now.isBefore(request.createdAt.plus(it.after.toJavaDuration())) } + 1
        return due.takeIf { it > request.wavesSent }
    }

    private suspend fun sendDueWave(request: BloodRequest, now: Instant): WaveResult? {
        if (request.effective(now).status != RequestStatus.OPEN) return null
        val wave = dueWave(request, now) ?: return null
        if (helpers.helpersFor(request.id, now) >= request.unitsNeeded + config.helperBuffer) return null
        if (!matching.claimWave(request.id, request.wavesSent, wave, now)) return null

        val radiusKm = config.waves[wave - 1].radiusKm
        val donorTypes = Compatibility.donorTypesFor(request.bloodType, request.component)
        val found = matching.candidates(request, donorTypes, radiusKm?.times(1000), now.minusSeconds(24 * 3600), now)
        val today = LocalDate.ofInstant(now, clock.zone)
        val eligible = found.filter { Eligibility.isEligible(it, request.requesterId, donorTypes, today, config) }
        val selected = Eligibility.select(eligible, request.requesterId, donorTypes, today, config)

        val tokensByUser = tokens.tokensFor(selected.map { it.userId })
        val allTokens = tokensByUser.values.flatten()
        val result = if (allTokens.isEmpty()) null else push.send(allTokens, notificationFor(request))
        val reached = tokensByUser.filterValues { userTokens -> userTokens.any { it in result?.delivered.orEmpty() } }.keys

        matching.recordNotifications(request.id, reached, wave, now)
        result?.invalid?.takeIf { it.isNotEmpty() }?.let { tokens.deleteTokens(it) }

        val outcome = WaveResult(request.id, wave, radiusKm, eligible.size, reached.size, selected.size - reached.size)
        log.info(
            "Wave {} for request {}: radius {}, candidates {}, sent {}, failed {}",
            wave, request.id, radiusKm?.let { "$it km" } ?: "all of Lebanon", outcome.candidates, outcome.sent, outcome.failed,
        )
        return outcome
    }

    companion object {
        /** The notification for a request. Never contains the note, the requester or any phone number. */
        fun notificationFor(request: BloodRequest) = PushMessage(
            title = "Urgent: ${request.bloodType} ${componentName(request.component)} needed",
            body = "${request.hospitalName}, ${request.areaName}. Tap if you can help.",
            data = mapOf(
                "type" to "new_request",
                "view" to "donor",
                "requestId" to request.id.toString(),
                "bloodType" to request.bloodType,
                "component" to request.component,
                "hospitalName" to request.hospitalName,
                "areaName" to request.areaName,
            ),
            expiresAt = request.neededBy,
        )

        private fun componentName(component: String) = when (component) {
            Compatibility.WHOLE_BLOOD -> "whole blood"
            Compatibility.RED_CELLS -> "red cells"
            Compatibility.PLATELETS -> "platelets"
            Compatibility.PLASMA -> "plasma"
            else -> "blood"
        }
    }
}
