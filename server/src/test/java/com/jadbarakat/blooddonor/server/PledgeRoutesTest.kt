package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.PledgeStatus
import com.jadbarakat.blooddonor.server.data.ProfileInput
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "I can help": committing, withdrawing, helper contact details and donation confirmation. */
class PledgeRoutesTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z")) // 12:00 in Beirut
    private val today = LocalDate.parse("2026-06-15")
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)
    private val events = mutableListOf<RequestEvent>()

    private val verifier = FakeTokenVerifier(
        listOf("alice", "bob", "carol", "erin", "frank").associate {
            "$it-token" to VerifiedToken("uid-$it", "$it@example.com", emailVerified = true)
        }
    )

    private fun user(name: String, fullName: String, phone: String, bloodType: String, lastDonation: LocalDate? = null) =
        runBlocking { users.upsert("uid-$name", "$name@example.com", ProfileInput(fullName, phone, bloodType, "male", 1, lastDonation, true)).id }

    private val aliceId = user("alice", "Alice Haddad", "+96170111111", "A+")               // requester
    private val bobId = user("bob", "Bob Khoury", "+96171222222", "O-")                     // notified, eligible
    private val erinId = user("erin", "Erin Nassar", "+96178444444", "O+")                  // can't give to O-
    private val frankId = user("frank", "Frank Aoun", "+96179555555", "O-", today.minusDays(30)) // donated recently

    init {
        user("carol", "Carol Saad", "+96176333333", "O-") // not notified
    }

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(verifier, users, areas, hospitals, requests, InMemoryDeviceTokenRepository(), pledges, onRequestEvent = { events += it }, clock = clock)
        }
        block()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    /** An O- red cells request by Alice; [notified] users were sent the notification. */
    private suspend fun ApplicationTestBuilder.createRequest(units: Int = 2, notified: List<Long> = listOf(bobId, erinId, frankId)): Long {
        val response = client.post("/api/v1/requests") {
            bearerAuth("alice-token")
            contentType(ContentType.Application.Json)
            setBody("""{"hospitalId":10,"bloodType":"O-","component":"red_cells","unitsNeeded":$units,"neededInHours":6,"note":"ICU, 3rd floor"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val id = json(response.bodyAsText()).str("id").toLong()
        notified.forEach { requests.notifications += SentNotification(id, it, 1, clock.instant()) }
        return id
    }

    private suspend fun ApplicationTestBuilder.help(id: Long, who: String = "bob") =
        client.post("/api/v1/requests/$id/help") { bearerAuth("$who-token") }

    private suspend fun ApplicationTestBuilder.withdraw(id: Long, who: String = "bob") =
        client.delete("/api/v1/requests/$id/help") { bearerAuth("$who-token") }

    private suspend fun ApplicationTestBuilder.donation(id: Long, donated: Boolean, who: String = "bob") =
        client.post("/api/v1/requests/$id/donation") {
            bearerAuth("$who-token")
            contentType(ContentType.Application.Json)
            setBody("""{"donated":$donated}""")
        }

    private suspend fun ApplicationTestBuilder.view(id: Long, who: String) =
        json(client.get("/api/v1/requests/$id") { bearerAuth("$who-token") }.bodyAsText())

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, json(response.bodyAsText()).str("error"))
    }

    // --- Committing ---

    @Test
    fun `a notified donor commits and gets the requester's first name and where to go, never a phone`() = test {
        val id = createRequest()
        val response = help(id)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals("Alice", body.str("requesterFirstName"))
        assertEquals("Rizk Hospital", body.str("hospitalName"))
        assertEquals("Beirut", body.str("areaName"))
        assertEquals(33.8938, body["hospitalLatitude"]!!.jsonPrimitive.content.toDouble())
        assertEquals(requests.rows.getValue(id).neededBy.toString(), body.str("neededBy"))
        assertEquals("committed", body.str("status"))
        assertFalse(body["canConfirmDonation"]!!.jsonPrimitive.boolean)
        val text = response.bodyAsText()
        assertFalse("+961" in text)
        assertFalse("Haddad" in text)
        assertEquals(listOf<RequestEvent>(RequestEvent.DonorCommitted(id, "O-")), events)
    }

    @Test
    fun `committing twice is harmless`() = test {
        val id = createRequest()
        help(id)
        assertEquals(HttpStatusCode.OK, help(id).status)
        assertEquals(1, pledges.rows.size)
        assertEquals(1, events.size, "the requester is told only once")
    }

    @Test
    fun `only donors who were notified can commit`() = test {
        val id = createRequest()
        assertError(help(id, "carol"), HttpStatusCode.NotFound, "request_not_found")
        assertError(help(999), HttpStatusCode.NotFound, "request_not_found")
    }

    @Test
    fun `the requester can't commit to their own request`() = test {
        val id = createRequest()
        assertError(help(id, "alice"), HttpStatusCode.Conflict, "own_request")
    }

    @Test
    fun `ineligible donors can't commit`() = test {
        val id = createRequest()
        assertError(help(id, "erin"), HttpStatusCode.Conflict, "not_eligible")
        assertError(help(id, "frank"), HttpStatusCode.Conflict, "donation_too_recent")
        assertTrue(pledges.rows.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `no commits once the request is closed or expired`() = test {
        val cancelled = createRequest()
        client.post("/api/v1/requests/$cancelled/cancel") { bearerAuth("alice-token") }
        assertError(help(cancelled), HttpStatusCode.Conflict, "request_not_open")

        val expiring = createRequest()
        clock.now = requests.rows.getValue(expiring).neededBy
        assertError(help(expiring), HttpStatusCode.Conflict, "request_not_open")
    }

    @Test
    fun `a donor can be committed to at most 2 open requests`() = test {
        val (first, second, third) = List(3) { createRequest() }
        assertEquals(HttpStatusCode.OK, help(first).status)
        assertEquals(HttpStatusCode.OK, help(second).status)
        assertError(help(third), HttpStatusCode.Conflict, "pledge_limit")

        withdraw(first)
        assertEquals(HttpStatusCode.OK, help(third).status)

        // A closed request no longer counts, even before the donor answers "Did you donate?".
        val fourth = createRequest()
        assertError(help(fourth), HttpStatusCode.Conflict, "pledge_limit")
        client.post("/api/v1/requests/$second/fulfill") { bearerAuth("alice-token") }
        assertEquals(HttpStatusCode.OK, help(fourth).status)
    }

    // --- What the requester sees ---

    @Test
    fun `only the owner sees the helpers' phone numbers`() = test {
        val id = createRequest()
        help(id)

        val owner = view(id, "alice")
        val helper = owner["helpers"]!!.jsonArray.single().jsonObject
        assertEquals("Bob Khoury", helper.str("fullName"))
        assertEquals("O-", helper.str("bloodType"))
        assertEquals("+96171222222", helper.str("phone"))
        assertEquals(clock.instant().toString(), helper.str("committedAt"))
        assertEquals(1, owner["committedCount"]!!.jsonPrimitive.int)
        assertEquals(2, owner["unitsNeeded"]!!.jsonPrimitive.int)

        // Bob (a committed donor), Erin (notified, not committed) and Carol (not notified) see no phones.
        for (who in listOf("bob", "erin", "carol")) {
            val text = client.get("/api/v1/requests/$id") { bearerAuth("$who-token") }.bodyAsText()
            assertFalse("+961" in text, "$who must not see any phone number")
            assertFalse("helpers" in text)
        }
        // Lists never include helpers.
        assertFalse("+96171222222" in client.get("/api/v1/requests/mine") { bearerAuth("alice-token") }.bodyAsText())
        assertFalse("+961" in client.get("/api/v1/requests/for-me") { bearerAuth("bob-token") }.bodyAsText())
    }

    @Test
    fun `a donor who withdraws disappears from the requester's list`() = test {
        val id = createRequest()
        help(id)
        assertEquals(HttpStatusCode.NoContent, withdraw(id).status)

        val owner = view(id, "alice")
        assertTrue(owner["helpers"]!!.jsonArray.isEmpty())
        assertEquals(0, owner["committedCount"]!!.jsonPrimitive.int)
        assertNull(view(id, "bob")["myPledge"])
        assertEquals(RequestEvent.DonorWithdrew(id), events.last())

        assertError(withdraw(id), HttpStatusCode.Conflict, "no_pledge")
        // And can commit again.
        assertEquals(HttpStatusCode.OK, help(id).status)
        assertEquals(1, view(id, "alice")["committedCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `withdrawing is only possible while the request is open`() = test {
        val id = createRequest()
        help(id)
        client.post("/api/v1/requests/$id/fulfill") { bearerAuth("alice-token") }
        assertError(withdraw(id), HttpStatusCode.Conflict, "request_not_open")
    }

    @Test
    fun `closing a request tells its committed donors and returns the helpers`() = test {
        val id = createRequest()
        help(id)
        val response = client.post("/api/v1/requests/$id/fulfill") { bearerAuth("alice-token") }
        assertEquals("+96171222222", json(response.bodyAsText())["helpers"]!!.jsonArray.single().jsonObject.str("phone"))
        assertEquals(RequestEvent.RequestClosed(id), events.last())
    }

    // --- Did you donate? ---

    @Test
    fun `donation can be confirmed 3 hours after committing and updates the last donation date`() = test {
        val id = createRequest()
        help(id)
        assertError(donation(id, true), HttpStatusCode.Conflict, "too_early")

        clock.now = clock.now.plus(Duration.ofHours(3))
        assertTrue(view(id, "bob")["myPledge"]!!.jsonObject["canConfirmDonation"]!!.jsonPrimitive.boolean)

        val response = donation(id, true)
        assertEquals(HttpStatusCode.OK, response.status)
        val pledge = json(response.bodyAsText())["myPledge"]!!.jsonObject
        assertEquals("donated", pledge.str("status"))
        assertFalse(pledge["canConfirmDonation"]!!.jsonPrimitive.boolean)
        assertEquals(today, users.findById(bobId)!!.lastDonationDate)

        // Still a helper for the requester, and can't answer twice.
        assertEquals("donated", view(id, "alice")["helpers"]!!.jsonArray.single().jsonObject.str("status"))
        assertError(donation(id, false), HttpStatusCode.Conflict, "no_pledge")
    }

    @Test
    fun `donation can be confirmed as soon as the request closes, and no means not_donated`() = test {
        val id = createRequest()
        help(id)
        client.post("/api/v1/requests/$id/cancel") { bearerAuth("alice-token") }

        assertEquals(HttpStatusCode.OK, donation(id, false).status)
        assertEquals(PledgeStatus.NOT_DONATED, pledges.find(id, bobId)!!.status)
        assertNull(users.findById(bobId)!!.lastDonationDate)
        assertEquals(0, view(id, "alice")["committedCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `only a committed donor can answer`() = test {
        val id = createRequest()
        client.post("/api/v1/requests/$id/cancel") { bearerAuth("alice-token") }
        assertError(donation(id, true), HttpStatusCode.Conflict, "no_pledge")
        assertError(donation(id, true, "carol"), HttpStatusCode.NotFound, "request_not_found")
    }

    // --- for-me ---

    @Test
    fun `for-me marks committed requests and keeps closed ones waiting for an answer`() = test {
        val committed = createRequest()
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        val other = createRequest()
        help(committed)

        fun ids(text: String) = Json.parseToJsonElement(text).jsonArray.associate {
            it.jsonObject.str("id").toLong() to it.jsonObject["committed"]!!.jsonPrimitive.boolean
        }
        assertEquals(mapOf(other to false, committed to true), ids(client.get("/api/v1/requests/for-me") { bearerAuth("bob-token") }.bodyAsText()))

        client.post("/api/v1/requests/$committed/fulfill") { bearerAuth("alice-token") }
        client.post("/api/v1/requests/$other/fulfill") { bearerAuth("alice-token") }
        assertEquals(mapOf(committed to true), ids(client.get("/api/v1/requests/for-me") { bearerAuth("bob-token") }.bodyAsText()))

        clock.now = clock.now.plus(Duration.ofMinutes(1))
        donation(committed, true)
        assertEquals("[]", client.get("/api/v1/requests/for-me") { bearerAuth("bob-token") }.bodyAsText())
    }
}
