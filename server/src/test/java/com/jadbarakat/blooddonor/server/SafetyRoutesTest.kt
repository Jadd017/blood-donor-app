package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequestStatus
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Posting rules, reports and strikes, and the no-show pause. */
class SafetyRoutesTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(
        listOf(Hospital(10, "Rizk Hospital", areaId = 1), Hospital(11, "AUBMC", areaId = 1)),
        areaNames = mapOf(1 to "Beirut"),
    )
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)
    private val safety = InMemorySafetyRepository(users, requests, pledges)
    private val events = mutableListOf<RequestEvent>()

    private val names = listOf("alice", "bob", "carol", "dave", "erin", "frank", "gina", "hadi")
    private val verifier = FakeTokenVerifier(names.associate { "$it-token" to VerifiedToken("uid-$it", "$it@example.com", emailVerified = true) })

    /** Creates [name]'s profile, made [age] ago. */
    private fun user(name: String, age: Duration = Duration.ofDays(30)): Long = runBlocking {
        val phone = "+9617" + "%07d".format(names.indexOf(name) + 1)
        val profile = users.upsert("uid-$name", "$name@example.com", ProfileInput("${name.replaceFirstChar { it.uppercase() }} Test", phone, "O-", "male", 1, null, true))
        users.rows["uid-$name"] = profile.copy(createdAt = clock.instant().minus(age))
        profile.id
    }

    private val ids = names.associateWith { user(it) }

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(
                verifier, users, areas, hospitals, requests, InMemoryDeviceTokenRepository(), pledges, safety, FakeAccountDeleter(),
                onRequestEvent = { events += it }, clock = clock,
            )
        }
        block()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.post(who: String, hospitalId: Int = 10, bloodType: String = "O-", component: String = "red_cells") =
        client.post("/api/v1/requests") {
            bearerAuth("$who-token")
            contentType(ContentType.Application.Json)
            setBody("""{"hospitalId":$hospitalId,"bloodType":"$bloodType","component":"$component","unitsNeeded":1,"neededInHours":24}""")
        }

    private suspend fun ApplicationTestBuilder.postId(who: String, bloodType: String = "O-"): Long {
        val response = post(who, bloodType = bloodType)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json(response.bodyAsText()).str("id").toLong()
    }

    private suspend fun ApplicationTestBuilder.report(id: Long, who: String, reason: String = "fake", details: String? = null) =
        client.post("/api/v1/requests/$id/report") {
            bearerAuth("$who-token")
            contentType(ContentType.Application.Json)
            setBody(if (details == null) """{"reason":"$reason"}""" else """{"reason":"$reason","details":${Json.encodeToString(details)}}""")
        }

    private suspend fun ApplicationTestBuilder.me(who: String) = json(client.get("/api/v1/me") { bearerAuth("$who-token") }.bodyAsText())

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String): JsonObject {
        val body = json(response.bodyAsText())
        assertEquals(status, response.status, body.toString())
        assertEquals(code, body.str("error"))
        return body
    }

    private fun details(body: JsonObject) = body["details"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun notify(requestId: Long, vararg who: String) =
        who.forEach { requests.notifications += SentNotification(requestId, ids.getValue(it), 1, clock.instant()) }

    // --- Posting rules ---

    @Test
    fun `a brand new account can post right away`() = test {
        user("hadi", age = Duration.ZERO)
        postId("hadi")
    }

    @Test
    fun `accounts younger than 7 days have at most 1 open request`() = test {
        user("hadi", age = Duration.ofDays(6))
        postId("hadi")
        val body = assertError(post("hadi", bloodType = "A+"), HttpStatusCode.TooManyRequests, "too_many_open_requests")
        val details = details(body)
        assertEquals("1", details["limit"])
        assertEquals("true", details["newAccount"])
        assertEquals("2026-06-16T09:00:00Z", details["newAccountUntil"])

        // Closing the open one frees the slot again.
        val open = requests.rows.values.single { it.requesterId == ids.getValue("hadi") }
        client.post("/api/v1/requests/${open.id}/cancel") { bearerAuth("hadi-token") }
        postId("hadi", bloodType = "A+")
    }

    @Test
    fun `from 7 days old an account may have 2 open requests`() = test {
        user("hadi", age = Duration.ofDays(7))
        postId("hadi")
        postId("hadi", bloodType = "A+")
        val body = assertError(post("hadi", bloodType = "B+"), HttpStatusCode.TooManyRequests, "too_many_open_requests")
        assertEquals(mapOf("limit" to "2", "newAccount" to "false"), details(body))
    }

    @Test
    fun `at most 3 requests in any rolling 24 hours`() = test {
        val first = postId("alice")
        clock.now = clock.now.plus(Duration.ofHours(1))
        client.post("/api/v1/requests/$first/cancel") { bearerAuth("alice-token") }
        val second = postId("alice", bloodType = "A+")
        client.post("/api/v1/requests/$second/fulfill") { bearerAuth("alice-token") }
        postId("alice", bloodType = "B+")

        clock.now = clock.now.plus(Duration.ofHours(1))
        val body = assertError(post("alice", bloodType = "AB+"), HttpStatusCode.TooManyRequests, "daily_limit")
        // The first one leaves the window 24 h after it was created.
        assertEquals(mapOf("limit" to "3", "retryAt" to "2026-06-16T09:00:00Z"), details(body))

        clock.now = Instant.parse("2026-06-16T09:00:00Z")
        postId("alice", bloodType = "AB+")
    }

    @Test
    fun `the same open request twice is a duplicate`() = test {
        val first = postId("alice")
        assertError(post("alice"), HttpStatusCode.Conflict, "duplicate_request")
        // A different hospital, blood type or component is not a duplicate...
        assertEquals(HttpStatusCode.Created, post("alice", hospitalId = 11).status)
        client.post("/api/v1/requests/$first/cancel") { bearerAuth("alice-token") }
        // ...and neither is the same request once the first one closed.
        assertEquals(HttpStatusCode.Created, post("alice").status)
    }

    @Test
    fun `another user's identical request is not a duplicate`() = test {
        postId("alice")
        postId("bob")
    }

    @Test
    fun `a strike suspends posting and the profile says until when`() = test {
        safety.strikes += InMemorySafetyRepository.StrikeRow(ids.getValue("alice"), null, clock.instant())
        val body = assertError(post("alice"), HttpStatusCode.Forbidden, "posting_suspended")
        assertEquals(mapOf("until" to "2026-06-22T09:00:00Z"), details(body))
        assertEquals("2026-06-22T09:00:00Z", me("alice").str("postingSuspendedUntil"))

        clock.now = Instant.parse("2026-06-22T09:00:00Z")
        postId("alice")
        assertNull(me("alice")["postingSuspendedUntil"])
    }

    @Test
    fun `three strikes suspend posting for good`() = test {
        repeat(3) { safety.strikes += InMemorySafetyRepository.StrikeRow(ids.getValue("alice"), null, clock.instant().minus(Duration.ofDays(400L * it))) }
        val body = assertError(post("alice"), HttpStatusCode.Forbidden, "posting_suspended")
        assertEquals(mapOf("until" to "permanent"), details(body))
        assertTrue(me("alice")["postingSuspendedPermanent"]!!.jsonPrimitive.boolean)
    }

    // --- Reports ---

    @Test
    fun `notified and committed users can report, with a reason and short details`() = test {
        val id = postId("alice")
        notify(id, "bob", "carol")
        pledges.commit(id, ids.getValue("carol"), clock.instant(), 2)

        assertEquals(HttpStatusCode.NoContent, report(id, "bob", "money", "Asked me to pay").status)
        assertEquals(HttpStatusCode.NoContent, report(id, "carol", "inappropriate").status)
        assertEquals(listOf("money", "inappropriate"), safety.reports.map { it.reason })
        assertEquals("Asked me to pay", safety.reports.first().details)
        assertFalse(requests.rows.getValue(id).hidden)
    }

    @Test
    fun `report checks who is reporting and what they send`() = test {
        val id = postId("alice")
        notify(id, "bob")
        assertError(report(id, "alice"), HttpStatusCode.Conflict, "own_request")
        assertError(report(id, "dave"), HttpStatusCode.NotFound, "request_not_found")
        assertError(report(999, "bob"), HttpStatusCode.NotFound, "request_not_found")
        assertError(report(id, "bob", reason = "spam"), HttpStatusCode.BadRequest, "invalid_reason")
        assertError(report(id, "bob", details = "x".repeat(201)), HttpStatusCode.BadRequest, "details_too_long")
        assertEquals(HttpStatusCode.NoContent, report(id, "bob", details = "x".repeat(200)).status)
        assertError(report(id, "bob", reason = "other"), HttpStatusCode.Conflict, "already_reported")
        assertEquals(1, safety.reports.size)
    }

    @Test
    fun `3 reports from different users hide the request, close it and give a strike`() = test {
        val id = postId("alice")
        notify(id, "bob", "carol", "dave", "erin")
        pledges.commit(id, ids.getValue("erin"), clock.instant(), 2)

        report(id, "bob")
        report(id, "bob", "money") // the same user again doesn't count
        report(id, "carol")
        assertFalse(requests.rows.getValue(id).hidden)
        assertTrue(safety.strikes.isEmpty())

        assertEquals(HttpStatusCode.NoContent, report(id, "dave").status)
        val hidden = requests.rows.getValue(id)
        assertTrue(hidden.hidden)
        assertEquals(RequestStatus.CANCELLED, hidden.status)
        assertEquals(clock.instant(), hidden.closedAt)
        assertEquals(listOf(ids.getValue("alice")), safety.strikes.map { it.userId })
        assertEquals(listOf<RequestEvent>(RequestEvent.RequestHidden(id)), events)

        // Gone from every donor's list, even the committed donor's.
        for (donor in listOf("bob", "erin")) {
            assertEquals("[]", client.get("/api/v1/requests/for-me") { bearerAuth("$donor-token") }.bodyAsText())
        }
        // The requester sees it as hidden, and posting is suspended.
        val mine = Json.parseToJsonElement(client.get("/api/v1/requests/mine") { bearerAuth("alice-token") }.bodyAsText()).jsonArray
        assertTrue(mine.single().jsonObject["hidden"]!!.jsonPrimitive.boolean)
        assertEquals("2026-06-22T09:00:00Z", me("alice").str("postingSuspendedUntil"))

        // A 4th report is recorded but gives no second strike.
        notify(id, "frank")
        assertEquals(HttpStatusCode.NoContent, report(id, "frank").status)
        assertEquals(1, safety.strikes.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `a second hidden request within 90 days suspends posting for 30 days`() = test {
        for ((index, bloodType) in listOf("O-", "A-").withIndex()) {
            if (index == 1) clock.now = clock.now.plus(Duration.ofDays(10))
            val id = requests.create(ids.getValue("alice"), NewBloodRequest(10, bloodType, "red_cells", 1, 24, null), clock.instant()).id
            notify(id, "bob", "carol", "dave")
            listOf("bob", "carol", "dave").forEach { report(id, it) }
        }
        assertEquals(2, safety.strikes.size)
        assertEquals("2026-07-25T09:00:00Z", me("alice").str("postingSuspendedUntil"))
    }

    // --- No-show pause ---

    /** [requester] posts a request, [donor] commits and the requester says they didn't come. */
    private suspend fun ApplicationTestBuilder.noShow(requester: String, donor: String = "bob", bloodType: String = "O-") {
        val id = requests.create(ids.getValue(requester), NewBloodRequest(10, bloodType, "red_cells", 1, 24, null), clock.instant()).id
        notify(id, donor)
        val pledge = (pledges.commit(id, ids.getValue(donor), clock.instant(), 2) as com.jadbarakat.blooddonor.server.data.CommitResult.Committed).pledge
        val response = client.post("/api/v1/requests/$id/helpers/${pledge.id}/confirm") {
            bearerAuth("$requester-token")
            contentType(ContentType.Application.Json)
            setBody("""{"outcome":"no_show"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        // Close it so the pledge stops counting towards the donor's 2 active pledges.
        requests.close(id, RequestStatus.CANCELLED, clock.instant())
    }

    @Test
    fun `no-shows from 3 different requesters within 90 days pause notifications for 30 days`() = test {
        noShow("alice")
        noShow("carol")
        assertNull(me("bob")["notificationsPausedUntil"])
        noShow("dave")

        val bob = me("bob")
        assertEquals("2026-07-15T09:00:00Z", bob.str("notificationsPausedUntil"))
        assertEquals("no_shows", bob.str("notificationsPausedReason"))
        assertEquals(Instant.parse("2026-07-15T09:00:00Z"), users.findById(ids.getValue("bob"))!!.notificationsPausedUntil)

        clock.now = Instant.parse("2026-07-15T09:00:00Z")
        assertNull(me("bob")["notificationsPausedUntil"])
    }

    @Test
    fun `a single requester can never trigger the pause alone`() = test {
        listOf("O-", "A-", "B-", "AB-").forEach { noShow("alice", bloodType = it) }
        noShow("carol")
        assertNull(users.findById(ids.getValue("bob"))!!.notificationsPausedUntil)
        assertNull(me("bob")["notificationsPausedUntil"])
    }

    @Test
    fun `no-shows older than 90 days don't count towards the pause`() = test {
        noShow("alice")
        clock.now = clock.now.plus(Duration.ofDays(91))
        noShow("carol")
        noShow("dave")
        assertNull(users.findById(ids.getValue("bob"))!!.notificationsPausedUntil)
        noShow("erin")
        assertEquals(clock.instant().plus(Duration.ofDays(30)), users.findById(ids.getValue("bob"))!!.notificationsPausedUntil)
    }
}
