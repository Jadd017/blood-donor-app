package com.jadbarakat.blooddonor.server.jobs

import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.matching.WaveService
import com.jadbarakat.blooddonor.server.push.RequestNotifier
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.time.Duration

/**
 * Every [interval], until the server stops:
 * 1. marks open requests past their deadline as expired (reads already treat them as expired,
 *    so a missed run only delays the stored value) and tells their committed donors, then
 * 2. sends the notification waves that are due.
 */
fun Application.startBackgroundJobs(
    requests: BloodRequestRepository,
    waves: WaveService,
    notifier: RequestNotifier,
    clock: Clock,
    interval: Duration,
) {
    launch {
        while (isActive) {
            runStep("Expiry") {
                val expired = requests.expireOverdue(clock.instant())
                if (expired.isNotEmpty()) log.info("Marked {} blood request(s) as expired", expired.size)
                for (id in expired) {
                    runStep("Expiry notification for request $id") { notifier.handle(RequestEvent.RequestClosed(id)) }
                }
            }
            runStep("Notification waves") { waves.runDueWaves() }
            delay(interval)
        }
    }
}

private suspend fun Application.runStep(name: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("$name job failed, will retry on the next run", e)
    }
}
