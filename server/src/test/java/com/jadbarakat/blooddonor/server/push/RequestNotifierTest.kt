package com.jadbarakat.blooddonor.server.push

import com.jadbarakat.blooddonor.server.FakeHospitalRepository
import com.jadbarakat.blooddonor.server.InMemoryBloodRequestRepository
import com.jadbarakat.blooddonor.server.InMemoryDeviceTokenRepository
import com.jadbarakat.blooddonor.server.InMemoryPledgeRepository
import com.jadbarakat.blooddonor.server.InMemoryUserRepository
import com.jadbarakat.blooddonor.server.FakePushSender
import com.jadbarakat.blooddonor.server.MutableClock
import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequestStatus
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestNotifierTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)
    private val tokens = InMemoryDeviceTokenRepository()
    private val push = FakePushSender()
    private val notifier = RequestNotifier(requests, pledges, tokens, push, clock)

    private fun user(name: String, phone: String) = runBlocking {
        users.upsert("uid-$name", "$name@example.com", ProfileInput("$name Haddad", phone, "O-", "male", 1, null, true)).id
            .also { tokens.tokens["t-$name"] = it }
    }

    private val requester = user("alice", "+96170111111")
    private val bob = user("bob", "+96171222222")
    private val carol = user("carol", "+96176333333")

    private val request = runBlocking {
        requests.create(requester, NewBloodRequest(10, "O-", "red_cells", 2, 6, "Call +96170111111"), clock.instant())
    }

    private fun sentTo(index: Int) = push.sent[index].first
    private fun message(index: Int) = push.sent[index].second

    private fun assertNoPrivateData(message: PushMessage) {
        val everything = message.title + message.body + message.data.values.joinToString()
        assertFalse("+961" in everything)
        assertFalse("Haddad" in everything)
        assertEquals(setOf("type", "view", "requestId"), message.data.keys)
        assertEquals(REQUEST_UPDATES_CHANNEL, message.channelId)
    }

    @Test
    fun `the requester is told when a donor commits or withdraws`() = runBlocking {
        notifier.handle(RequestEvent.DonorCommitted(request.id, "O-"))
        assertEquals(listOf("t-alice"), sentTo(0))
        assertEquals("A donor is on the way", message(0).title)
        assertEquals("O- donor for your request at Rizk Hospital", message(0).body)
        assertEquals("donor_committed", message(0).data["type"])
        assertEquals("requester", message(0).data["view"])
        assertNoPrivateData(message(0))

        notifier.handle(RequestEvent.DonorWithdrew(request.id))
        assertEquals(listOf("t-alice"), sentTo(1))
        assertEquals("A donor can no longer come", message(1).title)
        assertNoPrivateData(message(1))
    }

    @Test
    fun `committed donors, and only they, hear that the request is fulfilled`() = runBlocking {
        pledges.commit(request.id, bob, clock.instant(), 2)
        pledges.commit(request.id, carol, clock.instant(), 2)
        pledges.withdraw(request.id, carol, clock.instant())
        requests.close(request.id, RequestStatus.FULFILLED, clock.instant())

        notifier.handle(RequestEvent.RequestClosed(request.id))
        assertEquals(listOf("t-bob"), sentTo(0))
        assertEquals("Thank you!", message(0).title)
        assertEquals("The request at Rizk Hospital is fulfilled.", message(0).body)
        assertEquals("donor", message(0).data["view"])
        assertNoPrivateData(message(0))
    }

    @Test
    fun `cancelled and expired requests get the closed message`() = runBlocking {
        pledges.commit(request.id, bob, clock.instant(), 2)
        clock.now = request.neededBy
        requests.expireOverdue(clock.instant())

        notifier.handle(RequestEvent.RequestClosed(request.id))
        assertEquals("The request at Rizk Hospital is closed. Thank you for offering to help.", message(0).body)
        assertEquals("request_closed", message(0).data["type"])
    }

    @Test
    fun `nothing is sent for a request that is still open`() = runBlocking {
        pledges.commit(request.id, bob, clock.instant(), 2)
        notifier.handle(RequestEvent.RequestClosed(request.id))
        assertTrue(push.sent.isEmpty())
    }
}
