package com.jadbarakat.blooddonor.server.jobs

import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Marks open requests past their deadline as expired, every [interval], until the server stops.
 * Reads already report overdue requests as expired, so a missed run only delays the stored value.
 */
fun Application.startExpiryJob(requests: BloodRequestRepository, clock: Clock, interval: Duration = 5.minutes) {
    launch {
        while (isActive) {
            try {
                val expired = requests.expireOverdue(clock.instant())
                if (expired > 0) log.info("Marked {} blood request(s) as expired", expired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Expiry job failed, will retry in $interval", e)
            }
            delay(interval)
        }
    }
}
