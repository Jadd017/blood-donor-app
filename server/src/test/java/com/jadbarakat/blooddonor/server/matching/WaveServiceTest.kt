package com.jadbarakat.blooddonor.server.matching

import com.jadbarakat.blooddonor.server.FakeDonor
import com.jadbarakat.blooddonor.server.FakeHospitalRepository
import com.jadbarakat.blooddonor.server.FakeMatchingRepository
import com.jadbarakat.blooddonor.server.FakePushSender
import com.jadbarakat.blooddonor.server.InMemoryBloodRequestRepository
import com.jadbarakat.blooddonor.server.InMemoryDeviceTokenRepository
import com.jadbarakat.blooddonor.server.InMemoryPledgeRepository
import com.jadbarakat.blooddonor.server.InMemoryUserRepository
import com.jadbarakat.blooddonor.server.MutableClock
import com.jadbarakat.blooddonor.server.SentNotification
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequestStatus
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveServiceTest {

    private val start = Instant.parse("2026-06-15T09:00:00Z")
    private val clock = MutableClock(start)
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val tokens = InMemoryDeviceTokenRepository()
    private val matching = FakeMatchingRepository(requests, tokens)
    private val push = FakePushSender()
    private var helpers = 0

    private fun service(config: MatchingConfig = MatchingConfig()) =
        WaveService(requests, matching, tokens, push, { helpers }, config, clock)

    private val requesterId = 1L

    /** A compatible O- donor [km] from the hospital, with one device token "t<id>". */
    private fun donor(id: Long, km: Double, bloodType: String = "O-") {
        matching.donors += FakeDonor(id, bloodType, km)
        tokens.tokens["t$id"] = id
    }

    private fun createRequest(
        bloodType: String = "O-",
        units: Int = 2,
        hours: Int = 24,
        note: String? = "Call 70123456 (should never be sent)",
    ): BloodRequest = runBlocking {
        requests.create(requesterId, NewBloodRequest(10, bloodType, "red_cells", units, hours, note), clock.instant())
    }

    private fun advance(minutes: Long) {
        clock.now = clock.now.plus(Duration.ofMinutes(minutes))
    }

    private fun notified(requestId: Long) = requests.notifications.filter { it.requestId == requestId }.map { it.userId }.toSet()

    @Test
    fun `wave 1 right away reaches compatible donors within 15 km`() = runBlocking {
        donor(2, km = 3.0)
        donor(3, km = 15.0)
        donor(4, km = 16.0)
        donor(5, km = 2.0, bloodType = "O+") // can't give red cells to O-
        val request = createRequest()

        val result = service().runDueWaveFor(request.id)!!
        assertEquals(1, result.wave)
        assertEquals(15.0, result.radiusKm)
        assertEquals(2, result.sent)
        assertEquals(setOf(2L, 3L), notified(request.id))
        assertEquals(1, requests.rows.getValue(request.id).wavesSent)
    }

    @Test
    fun `radius widens to 30, 60 km and then all of Lebanon`() = runBlocking {
        donor(2, km = 10.0)
        donor(3, km = 25.0)
        donor(4, km = 50.0)
        donor(5, km = 140.0)
        val request = createRequest()
        val waves = service()
        waves.runDueWaveFor(request.id)
        assertEquals(setOf(2L), notified(request.id))

        advance(29)
        assertTrue(waves.runDueWaves().isEmpty(), "wave 2 is not due before 30 minutes")

        advance(1)
        assertEquals(30.0, waves.runDueWaves().single().radiusKm)
        assertEquals(setOf(2L, 3L), notified(request.id))

        advance(30)
        assertEquals(60.0, waves.runDueWaves().single().radiusKm)
        assertEquals(setOf(2L, 3L, 4L), notified(request.id))

        advance(60)
        val last = waves.runDueWaves().single()
        assertEquals(4, last.wave)
        assertNull(last.radiusKm)
        assertEquals(setOf(2L, 3L, 4L, 5L), notified(request.id))

        advance(600)
        assertTrue(waves.runDueWaves().isEmpty(), "no waves after the last one")
    }

    @Test
    fun `a donor is notified at most once per request`() = runBlocking {
        donor(2, km = 5.0)
        val request = createRequest()
        val waves = service()
        waves.runDueWaveFor(request.id)
        advance(30)
        val wave2 = waves.runDueWaves().single()

        assertEquals(0, wave2.sent)
        assertEquals(1, push.sent.size)
        assertEquals(listOf(SentNotification(request.id, 2, 1, start)), requests.notifications)
    }

    @Test
    fun `wave 1 can only be claimed once`() = runBlocking {
        donor(2, km = 5.0)
        val request = createRequest()
        val waves = service()
        waves.runDueWaveFor(request.id)
        assertNull(waves.runDueWaveFor(request.id))
        assertTrue(waves.runDueWaves().isEmpty())
        assertEquals(1, push.sent.size)
    }

    @Test
    fun `a late job jumps straight to the latest due wave`() = runBlocking {
        donor(2, km = 50.0)
        val request = createRequest()
        val waves = service()
        waves.runDueWaveFor(request.id)
        advance(65) // waves 2 and 3 are both due
        val result = waves.runDueWaves().single()
        assertEquals(3, result.wave)
        assertEquals(setOf(2L), notified(request.id))
    }

    @Test
    fun `a donor gets at most 3 request notifications per 24 hours`() = runBlocking {
        donor(2, km = 5.0)
        val waves = service()
        repeat(3) {
            val r = createRequest()
            assertEquals(1, waves.runDueWaveFor(r.id)!!.sent)
            advance(60)
        }
        val fourth = createRequest()
        assertEquals(0, waves.runDueWaveFor(fourth.id)!!.sent)

        // Just over 24 h after the first notification, only 2 remain in the window: room again.
        val firstSent = requests.notifications.minOf { it.sentAt }
        clock.now = firstSent.plus(Duration.ofHours(24)).plusSeconds(1)
        val later = createRequest()
        assertEquals(1, waves.runDueWaveFor(later.id)!!.sent)
    }

    @Test
    fun `no waves once the request is not open`() = runBlocking {
        donor(2, km = 5.0)
        donor(3, km = 25.0)
        val waves = service()

        val cancelled = createRequest()
        requests.close(cancelled.id, RequestStatus.CANCELLED, clock.instant())
        assertNull(waves.runDueWaveFor(cancelled.id))

        val fulfilled = createRequest()
        waves.runDueWaveFor(fulfilled.id)
        requests.close(fulfilled.id, RequestStatus.FULFILLED, clock.instant())
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty())

        val shortLived = createRequest(hours = 6)
        waves.runDueWaveFor(shortLived.id)
        clock.now = shortLived.neededBy // expired, even before the expiry job runs
        assertNull(waves.runDueWaveFor(shortLived.id))
        assertFalse(3L in notified(shortLived.id))
    }

    @Test
    fun `no more waves once enough donors are helping`() = runBlocking {
        donor(2, km = 25.0)
        val request = createRequest(units = 2)
        val waves = service()
        waves.runDueWaveFor(request.id)
        helpers = 2
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty())
        assertEquals(1, requests.rows.getValue(request.id).wavesSent, "the wave is not used up")
    }

    @Test
    fun `committed donors stop the waves and a withdrawal resumes them`() = runBlocking {
        val users = InMemoryUserRepository()
        val pledges = InMemoryPledgeRepository(users, requests)
        val waves = WaveService(requests, matching, tokens, push, { pledges.helperCount(it) }, MatchingConfig(), clock)
        donor(2, km = 5.0)
        donor(3, km = 25.0)
        donor(4, km = 50.0)
        val request = createRequest(units = 1)
        waves.runDueWaveFor(request.id)
        assertEquals(setOf(2L), notified(request.id))

        pledges.commit(request.id, 2, clock.instant(), 2)
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty(), "1 of 1 donors committed: no wave 2")
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty(), "nor wave 3")
        assertEquals(setOf(2L), notified(request.id))

        pledges.withdraw(request.id, 2, clock.instant())
        val resumed = waves.runDueWaveFor(request.id)!!
        assertEquals(3, resumed.wave, "the latest due wave goes out right away")
        assertEquals(setOf(2L, 3L, 4L), notified(request.id))
    }

    @Test
    fun `a donated pledge still counts as a helper`() = runBlocking {
        val users = InMemoryUserRepository()
        val pledges = InMemoryPledgeRepository(users, requests)
        val donorId = users.upsert("uid-d", "d@example.com", ProfileInput("D", "+96171000000", "O-", "male", 1, null, true)).id
        val request = createRequest(units = 1)
        pledges.commit(request.id, donorId, clock.instant(), 2)
        pledges.confirmDonation(request.id, donorId, donated = true, today = LocalDate.of(2026, 6, 15), now = clock.instant())
        assertEquals(1, pledges.helperCount(request.id))
        pledges.commit(request.id, 99, clock.instant(), 2)
        assertEquals(2, pledges.helperCount(request.id))
    }

    @Test
    fun `at most the configured number of donors per wave, closest first`() = runBlocking {
        (2L..8L).forEach { donor(it, km = it.toDouble()) }
        val request = createRequest()
        val result = service(MatchingConfig(maxDonorsPerWave = 3)).runDueWaveFor(request.id)!!
        assertEquals(7, result.candidates)
        assertEquals(3, result.sent)
        assertEquals(setOf(2L, 3L, 4L), notified(request.id))
    }

    @Test
    fun `invalid tokens are deleted and undelivered donors are retried later`() = runBlocking {
        donor(2, km = 5.0)
        donor(3, km = 6.0)
        tokens.tokens["t2-old"] = 2 // a second, stale device
        push.invalidTokens += "t2-old"
        push.failingTokens += "t3" // a temporary failure
        val request = createRequest()
        val waves = service()

        val result = waves.runDueWaveFor(request.id)!!
        assertEquals(1, result.sent)
        assertEquals(1, result.failed)
        assertEquals(setOf(2L), notified(request.id), "donor 2 still got it on their other device")
        assertFalse("t2-old" in tokens.tokens)

        push.failingTokens.clear()
        advance(30)
        assertEquals(1, waves.runDueWaves().single().sent)
        assertEquals(setOf(2L, 3L), notified(request.id))
    }

    @Test
    fun `the notification has no note, requester or phone number`() {
        val request = createRequest(bloodType = "O-", note = "Call 70123456, ask for Jad")
        val message = WaveService.notificationFor(request)
        assertEquals("Urgent: O- red cells needed", message.title)
        assertEquals("Rizk Hospital, Beirut. Tap if you can help.", message.body)
        assertEquals(setOf("type", "view", "requestId", "bloodType", "component", "hospitalName", "areaName"), message.data.keys)
        val everything = message.title + message.body + message.data.values.joinToString()
        assertFalse("70123456" in everything)
        assertFalse("Jad" in everything)
        assertEquals(request.neededBy, message.expiresAt)
    }
}
