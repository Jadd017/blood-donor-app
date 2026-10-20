package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.DELETED_HELPER_NAME
import com.jadbarakat.blooddonor.server.data.DELETED_USER_ID
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.PledgeStatus
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequestStatus
import com.jadbarakat.blooddonor.server.data.RequesterOutcome
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DELETE /me: what is removed, what is kept anonymously, and retries. */
class DeleteAccountTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)
    private val tokens = InMemoryDeviceTokenRepository()
    private val safety = InMemorySafetyRepository(users, requests, pledges, tokens)
    private val deleter = FakeAccountDeleter()
    private val events = mutableListOf<RequestEvent>()

    private val verifier = FakeTokenVerifier(
        listOf("alice", "bob", "carol").associate { "$it-token" to VerifiedToken("uid-$it", "$it@example.com", emailVerified = true) }
    )

    private fun user(name: String, fullName: String, phone: String) =
        runBlocking { users.upsert("uid-$name", "$name@example.com", ProfileInput(fullName, phone, "O-", "male", 1, null, true)).id }

    private val aliceId = user("alice", "Alice Haddad", "+96170111111")
    private val bobId = user("bob", "Bob Khoury", "+96171222222")
    private val carolId = user("carol", "Carol Saad", "+96176333333")

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(verifier, users, areas, hospitals, requests, tokens, pledges, safety, deleter, onRequestEvent = { events += it }, clock = clock)
        }
        block()
    }

    private fun create(requester: Long, bloodType: String, note: String? = null) = runBlocking {
        requests.create(requester, NewBloodRequest(10, bloodType, "red_cells", 2, 24, note), clock.instant()).id
    }

    private suspend fun ApplicationTestBuilder.deleteMe(who: String) = client.delete("/api/v1/me") { bearerAuth("$who-token") }

    @Test
    fun `deleting a requester cancels their open requests and keeps them without the person`() = test {
        val open = create(aliceId, "O-", note = "Ask for Alice, +96170111111")
        val fulfilled = create(aliceId, "A-")
        requests.close(fulfilled, RequestStatus.FULFILLED, clock.instant())
        pledges.commit(open, bobId, clock.instant(), 2)
        tokens.tokens["alice-phone"] = aliceId
        requests.notifications += SentNotification(open, bobId, 1, clock.instant())

        assertEquals(HttpStatusCode.NoContent, deleteMe("alice").status)

        assertNull(users.findByUid("uid-alice"))
        assertEquals(listOf("uid-alice"), deleter.deleted)
        assertFalse("alice-phone" in tokens.tokens)
        // Requests stay for the donors' history, cancelled if open, without the requester or the note.
        val kept = requests.rows.getValue(open)
        assertEquals(RequestStatus.CANCELLED, kept.status)
        assertEquals(DELETED_USER_ID, kept.requesterId)
        assertNull(kept.note)
        assertEquals(RequestStatus.FULFILLED, requests.rows.getValue(fulfilled).status)
        assertEquals(DELETED_USER_ID, requests.rows.getValue(fulfilled).requesterId)
        // Bob's pledge is untouched, and he is told the request closed.
        assertEquals(PledgeStatus.COMMITTED, pledges.find(open, bobId)!!.status)
        assertEquals(listOf<RequestEvent>(RequestEvent.RequestClosed(open)), events)
    }

    @Test
    fun `deleting a donor withdraws open pledges and keeps confirmed ones anonymously`() = test {
        val waiting = create(aliceId, "O-")
        val donated = create(carolId, "O-")
        pledges.commit(waiting, bobId, clock.instant(), 2)
        pledges.commit(donated, bobId, clock.instant(), 2)
        val confirmed = pledges.find(donated, bobId)!!
        pledges.confirmHelper(donated, confirmed.id, RequesterOutcome.DONATED, LocalDate.parse("2026-06-15"), clock.instant())

        assertEquals(HttpStatusCode.NoContent, deleteMe("bob").status)

        val withdrawn = pledges.rows.single { it.requestId == waiting }
        assertEquals(PledgeStatus.WITHDRAWN, withdrawn.status)
        assertEquals(DELETED_USER_ID, withdrawn.donorId)
        val kept = pledges.rows.single { it.requestId == donated }
        assertEquals(DELETED_USER_ID, kept.donorId)
        assertTrue(kept.confirmedDonation)
        // The requester can be told, and the next wave may go out.
        assertEquals(listOf<RequestEvent>(RequestEvent.DonorWithdrew(waiting)), events)

        // Carol still sees the donation, but no longer who it was.
        val view = Json.parseToJsonElement(client.get("/api/v1/requests/$donated") { bearerAuth("carol-token") }.bodyAsText()).jsonObject
        val helper = view["helpers"]!!.jsonArray.single().jsonObject
        assertEquals(DELETED_HELPER_NAME, helper["fullName"]!!.jsonPrimitive.content)
        assertEquals("", helper["phone"]!!.jsonPrimitive.content)
        assertNull(helper["donorId"])
        assertEquals("donated", helper["state"]!!.jsonPrimitive.content)
        assertFalse("Bob" in view.toString() || "+96171" in view.toString())
    }

    @Test
    fun `blocks, reports and strikes go with the account`() = test {
        val id = create(aliceId, "O-")
        safety.block(bobId, aliceId, id, clock.instant())
        safety.block(carolId, bobId, null, clock.instant())
        safety.block(carolId, aliceId, null, clock.instant())
        safety.reports += InMemorySafetyRepository.ReportRow(id, bobId, "fake", null, clock.instant())
        safety.strikes += InMemorySafetyRepository.StrikeRow(bobId, null, clock.instant())

        deleteMe("bob")

        assertEquals(listOf(carolId to aliceId), safety.blockRows.map { it.blockerId to it.blockedId })
        assertTrue(safety.reports.isEmpty())
        assertTrue(safety.strikes.isEmpty())
    }

    @Test
    fun `deletion is safe to retry, including after a Firebase failure`() = test {
        val id = create(aliceId, "O-")
        deleter.failing = true
        assertEquals(HttpStatusCode.InternalServerError, deleteMe("alice").status)
        // The database part is already done...
        assertNull(users.findByUid("uid-alice"))
        assertEquals(RequestStatus.CANCELLED, requests.rows.getValue(id).status)

        // ...so a retry only deletes the Firebase user.
        deleter.failing = false
        events.clear()
        assertEquals(HttpStatusCode.NoContent, deleteMe("alice").status)
        assertEquals(HttpStatusCode.NoContent, deleteMe("alice").status)
        assertEquals(listOf("uid-alice", "uid-alice"), deleter.deleted)
        assertTrue(events.isEmpty())
    }
}
