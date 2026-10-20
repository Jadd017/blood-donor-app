package com.jadbarakat.blooddonor.server.matching

import com.jadbarakat.blooddonor.server.FakeDonor
import com.jadbarakat.blooddonor.server.FakeHospitalRepository
import com.jadbarakat.blooddonor.server.FakeMatchingRepository
import com.jadbarakat.blooddonor.server.FakePushSender
import com.jadbarakat.blooddonor.server.InMemoryBloodRequestRepository
import com.jadbarakat.blooddonor.server.InMemoryDeviceTokenRepository
import com.jadbarakat.blooddonor.server.InMemoryPledgeRepository
import com.jadbarakat.blooddonor.server.InMemorySafetyRepository
import com.jadbarakat.blooddonor.server.InMemoryUserRepository
import com.jadbarakat.blooddonor.server.MutableClock
import com.jadbarakat.blooddonor.server.SentNotification
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.CommitResult
import com.jadbarakat.blooddonor.server.data.PledgeStatus
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequesterOutcome
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
        WaveService(requests, matching, tokens, push, { _, _ -> helpers }, config, clock)

    private val requesterId = 1L
    private val window = Duration.ofHours(3)

    /** A service whose helper count comes from real (in-memory) pledges. */
    private fun withPledges(config: MatchingConfig = MatchingConfig()): Pair<WaveService, InMemoryPledgeRepository> {
        val users = InMemoryUserRepository()
        listOf(2L, 3L, 4L, 5L).forEach { id ->
            runBlocking { users.upsert("uid-$id", "$id@example.com", ProfileInput("Donor $id", "+9617100000$id", "O-", "male", 1, null, true)) }
        }
        val pledges = InMemoryPledgeRepository(users, requests)
        val waves = WaveService(requests, matching, tokens, push, { id, now -> pledges.helperCount(id, now, window) }, config, clock)
        return waves to pledges
    }

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
        helpers = 3 // 2 units + the buffer of 1
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty())
        assertEquals(1, requests.rows.getValue(request.id).wavesSent, "the wave is not used up")
    }

    @Test
    fun `committed donors stop the waves and a withdrawal resumes them`() = runBlocking {
        val (waves, pledges) = withPledges(MatchingConfig(helperBuffer = 0))
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
    fun `waves stop at units needed plus the buffer`() = runBlocking {
        donor(2, km = 25.0)
        donor(3, km = 50.0)
        val request = createRequest(units = 1)
        val waves = service() // buffer 1
        waves.runDueWaveFor(request.id)

        helpers = 1
        advance(30)
        assertEquals(2, waves.runDueWaves().single().wave, "1 helper for 1 unit: still looking for the backup donor")

        helpers = 2
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty(), "1 unit + 1 buffer reached")
        assertEquals(2, requests.rows.getValue(request.id).wavesSent)
    }

    @Test
    fun `a helper stops counting when the arrival window ends, and the waves resume`() = runBlocking {
        val (waves, pledges) = withPledges()
        donor(2, km = 5.0)
        donor(3, km = 6.0)
        donor(4, km = 140.0)
        val request = createRequest(units = 1)
        waves.runDueWaveFor(request.id)
        pledges.commit(request.id, 2, clock.instant(), 2)
        pledges.commit(request.id, 3, clock.instant(), 2)

        advance(120)
        assertTrue(waves.runDueWaves().isEmpty(), "2 helpers within their window: waves held")
        assertEquals(2, pledges.helperCount(request.id, clock.instant(), window))

        advance(59)
        assertTrue(waves.runDueWaves().isEmpty())
        advance(1) // 3 hours after committing
        assertEquals(0, pledges.helperCount(request.id, clock.instant(), window))
        assertEquals(4, waves.runDueWaves().single().wave, "the latest due wave goes out, no status change needed")
        assertTrue(4L in notified(request.id))
        assertEquals(PledgeStatus.COMMITTED, pledges.find(request.id, 2)!!.status)
    }

    @Test
    fun `the arrival window ends at the deadline if that comes first`() = runBlocking {
        val (_, pledges) = withPledges()
        val request = createRequest(units = 1, hours = 2)
        pledges.commit(request.id, 2, clock.instant(), 2)
        advance(119)
        assertEquals(1, pledges.helperCount(request.id, clock.instant(), window))
        advance(1)
        assertEquals(0, pledges.helperCount(request.id, clock.instant(), window))
    }

    @Test
    fun `a no-show stops counting right away and the waves resume`() = runBlocking {
        val (waves, pledges) = withPledges()
        donor(2, km = 5.0)
        donor(3, km = 6.0)
        donor(4, km = 25.0)
        val request = createRequest(units = 1)
        waves.runDueWaveFor(request.id)
        pledges.commit(request.id, 2, clock.instant(), 2)
        val second = (pledges.commit(request.id, 3, clock.instant(), 2) as CommitResult.Committed).pledge
        advance(30)
        assertTrue(waves.runDueWaves().isEmpty())

        pledges.confirmHelper(request.id, second.id, RequesterOutcome.NO_SHOW, LocalDate.of(2026, 6, 15), clock.instant())
        assertEquals(1, pledges.helperCount(request.id, clock.instant(), window))
        assertEquals(2, waves.runDueWaveFor(request.id)!!.wave)
        assertTrue(4L in notified(request.id))
        assertEquals(1, pledges.noShowCount(3))
        assertEquals(0, pledges.noShowCount(2))
    }

    @Test
    fun `a requester-confirmed donation counts after the window, a self-reported one doesn't`() = runBlocking {
        val (_, pledges) = withPledges()
        val request = createRequest(units = 2)
        val confirmed = (pledges.commit(request.id, 2, clock.instant(), 2) as CommitResult.Committed).pledge
        pledges.commit(request.id, 3, clock.instant(), 2)
        val today = LocalDate.of(2026, 6, 15)
        pledges.confirmHelper(request.id, confirmed.id, RequesterOutcome.DONATED, today, clock.instant())
        advance(180)
        pledges.confirmDonation(request.id, 3, donated = true, today = today, now = clock.instant())
        assertEquals(1, pledges.helperCount(request.id, clock.instant(), window), "only the requester-confirmed one")
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

    @Test
    fun `donors whose notifications are paused are skipped until the pause ends`() = runBlocking {
        matching.donors += FakeDonor(2, "O-", 3.0, notificationsPausedUntil = start.plus(Duration.ofDays(30)))
        tokens.tokens["t2"] = 2
        donor(3, km = 4.0)
        val request = createRequest()
        service().runDueWaveFor(request.id)
        assertEquals(setOf(3L), notified(request.id))

        clock.now = start.plus(Duration.ofDays(30))
        val later = createRequest(bloodType = "O-", hours = 24)
        service().runDueWaveFor(later.id)
        assertEquals(setOf(2L, 3L), notified(later.id))
    }

    @Test
    fun `blocks keep donor and requester apart in both directions`() = runBlocking {
        val users = InMemoryUserRepository()
        val safety = InMemorySafetyRepository(users, requests)
        val blockingMatching = FakeMatchingRepository(requests, tokens, safety)
        val waves = WaveService(requests, blockingMatching, tokens, push, { _, _ -> 0 }, MatchingConfig(), clock)
        listOf(2L, 3L, 4L).forEach { id ->
            blockingMatching.donors += FakeDonor(id, "O-", 2.0)
            tokens.tokens["t$id"] = id
        }
        safety.block(blockerId = 2, blockedId = requesterId, viaRequestId = null, clock.instant()) // donor blocked the requester
        safety.block(blockerId = requesterId, blockedId = 3, viaRequestId = null, clock.instant()) // requester blocked the donor
        val request = createRequest()
        waves.runDueWaveFor(request.id)
        assertEquals(setOf(4L), notified(request.id))
    }
}
