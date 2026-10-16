package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestRoutesTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(
        listOf(Area(1, "Beirut", "Beirut", "Beirut"), Area(2, "Baabda", "Baabda", "Mount Lebanon")),
    )
    private val hospitals = FakeHospitalRepository(
        hospitals = listOf(
            Hospital(10, "Rizk Hospital", areaId = 1),
            Hospital(11, "American University Hospital", areaId = 1),
            Hospital(20, "Hayek Hospital", areaId = 2),
        ),
        inactive = listOf(Hospital(99, "Closed Clinic", areaId = 1)),
    )
    private val requests = InMemoryBloodRequestRepository(hospitals)

    private val verifier = FakeTokenVerifier(
        mapOf(
            "alice-token" to VerifiedToken("uid-alice", "alice@example.com", emailVerified = true),
            "bob-token" to VerifiedToken("uid-bob", "bob@example.com", emailVerified = true),
            "noprofile-token" to VerifiedToken("uid-dan", "dan@example.com", emailVerified = true),
        )
    )

    init {
        runBlocking {
            users.upsert("uid-alice", "alice@example.com", profile("+96170123456"))
            users.upsert("uid-bob", "bob@example.com", profile("+96171123456"))
        }
    }

    private fun profile(phone: String) = ProfileInput("Someone", phone, "O+", "female", 1, null, true)

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(verifier, users, areas, hospitals, requests, clock) }
        block()
    }

    private fun body(
        hospitalId: Int = 10,
        bloodType: String = "A-",
        component: String = "platelets",
        units: Int = 2,
        hours: Int = 24,
        note: String? = null,
    ): String {
        val noteJson = note?.let { ""","note":${Json.encodeToString(it)}""" }.orEmpty()
        return """{"hospitalId":$hospitalId,"bloodType":"$bloodType","component":"$component","unitsNeeded":$units,"neededInHours":$hours$noteJson}"""
    }

    private suspend fun ApplicationTestBuilder.create(token: String = "alice-token", json: String = body()): HttpResponse =
        client.post("/api/v1/requests") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private suspend fun ApplicationTestBuilder.createId(token: String = "alice-token", json: String = body()): Long {
        val response = create(token, json)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json(response.bodyAsText())["id"]!!.jsonPrimitive.content.toLong()
    }

    private suspend fun ApplicationTestBuilder.action(id: Long, action: String, token: String = "alice-token") =
        client.post("/api/v1/requests/$id/$action") { bearerAuth(token) }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, json(response.bodyAsText()).str("error"))
    }

    // --- Auth and profile ---

    @Test
    fun `routes need a token`() = test {
        assertError(client.get("/api/v1/hospitals"), HttpStatusCode.Unauthorized, "unauthorized")
        assertError(client.get("/api/v1/requests/mine"), HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun `routes need a profile`() = test {
        val token = "noprofile-token"
        assertError(client.get("/api/v1/hospitals") { bearerAuth(token) }, HttpStatusCode.Conflict, "profile_required")
        assertError(create(token), HttpStatusCode.Conflict, "profile_required")
        assertError(client.get("/api/v1/requests/mine") { bearerAuth(token) }, HttpStatusCode.Conflict, "profile_required")
        assertError(client.get("/api/v1/requests/1") { bearerAuth(token) }, HttpStatusCode.Conflict, "profile_required")
    }

    // --- Hospitals ---

    @Test
    fun `hospitals are small, sorted and only active`() = test {
        val response = client.get("/api/v1/hospitals") { bearerAuth("alice-token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("American University Hospital", "Hayek Hospital", "Rizk Hospital"), list.map { it.str("name") })
        assertEquals(setOf("id", "name", "areaId"), list[0].keys)
    }

    @Test
    fun `hospitals can be filtered by area`() = test {
        val response = client.get("/api/v1/hospitals?areaId=2") { bearerAuth("alice-token") }
        val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(listOf("Hayek Hospital"), list.map { it.jsonObject.str("name") })
        // Area 1 also has an inactive hospital, which must stay hidden.
        val beirut = client.get("/api/v1/hospitals?areaId=1") { bearerAuth("alice-token") }.bodyAsText()
        assertEquals(
            listOf("American University Hospital", "Rizk Hospital"),
            Json.parseToJsonElement(beirut).jsonArray.map { it.jsonObject.str("name") },
        )
        assertError(
            client.get("/api/v1/hospitals?areaId=abc") { bearerAuth("alice-token") },
            HttpStatusCode.BadRequest,
            "invalid_area_id",
        )
    }

    // --- Create and validation ---

    @Test
    fun `create returns the full open request`() = test {
        val response = create(json = body(note = "  Patient in ICU, ask for Dr. Haddad  "))
        assertEquals(HttpStatusCode.Created, response.status)
        val request = json(response.bodyAsText())
        assertEquals("Rizk Hospital", request.str("hospitalName"))
        assertEquals("A-", request.str("bloodType"))
        assertEquals("platelets", request.str("component"))
        assertEquals("2", request.str("unitsNeeded"))
        assertEquals("open", request.str("status"))
        assertEquals("2026-06-15T09:00:00Z", request.str("createdAt"))
        assertEquals("2026-06-16T09:00:00Z", request.str("neededBy"))
        assertEquals("Patient in ICU, ask for Dr. Haddad", request.str("note"))
        assertTrue("closedAt" !in request)
    }

    @Test
    fun `deadline is computed by the server within 6 to 72 hours`() = test {
        val six = json(create(json = body(hours = 6)).bodyAsText())
        assertEquals("2026-06-15T15:00:00Z", six.str("neededBy"))
        val max = json(create(json = body(hours = 72)).bodyAsText())
        assertEquals("2026-06-18T09:00:00Z", max.str("neededBy"))
        assertError(create(json = body(hours = 5)), HttpStatusCode.BadRequest, "invalid_needed_by")
        assertError(create(json = body(hours = 73)), HttpStatusCode.BadRequest, "invalid_needed_by")
    }

    @Test
    fun `blood type is required and must be one of the 8`() = test {
        assertError(create(json = body(bloodType = "C+")), HttpStatusCode.BadRequest, "invalid_blood_type")
        assertError(create(json = body(bloodType = "unknown")), HttpStatusCode.BadRequest, "invalid_blood_type")
        val missing = """{"hospitalId":10,"component":"plasma","unitsNeeded":1,"neededInHours":24}"""
        assertError(create(json = missing), HttpStatusCode.BadRequest, "invalid_body")
    }

    @Test
    fun `component must be known`() = test {
        assertError(create(json = body(component = "blood")), HttpStatusCode.BadRequest, "invalid_component")
        for (component in listOf("whole_blood", "red_cells", "platelets", "plasma")) {
            assertEquals(HttpStatusCode.Created, create(json = body(component = component)).status)
        }
    }

    @Test
    fun `units must be 1 to 10`() = test {
        assertError(create(json = body(units = 0)), HttpStatusCode.BadRequest, "invalid_units")
        assertError(create(json = body(units = 11)), HttpStatusCode.BadRequest, "invalid_units")
        assertEquals(HttpStatusCode.Created, create(json = body(units = 1)).status)
        assertEquals(HttpStatusCode.Created, create(json = body(units = 10)).status)
    }

    @Test
    fun `note length and contents are checked`() = test {
        assertEquals(HttpStatusCode.Created, create(json = body(note = "x".repeat(200))).status)
        assertError(create(json = body(note = "x".repeat(201))), HttpStatusCode.BadRequest, "invalid_note")
        assertError(create(json = body(note = "Call me on 03 123 456")), HttpStatusCode.BadRequest, "note_contains_phone")
        assertError(create(json = body(note = "Details at www.example.com")), HttpStatusCode.BadRequest, "note_contains_url")
        // Blank notes are stored as no note.
        val blank = json(create(json = body(note = "   ")).bodyAsText())
        assertTrue("note" !in blank)
    }

    @Test
    fun `unknown or inactive hospital is 400`() = test {
        assertError(create(json = body(hospitalId = 12345)), HttpStatusCode.BadRequest, "unknown_hospital")
        assertError(create(json = body(hospitalId = 99)), HttpStatusCode.BadRequest, "unknown_hospital")
    }

    // --- Listing and details ---

    @Test
    fun `mine lists only my requests newest first`() = test {
        val first = createId()
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        val second = createId(json = body(hospitalId = 20))
        createId(token = "bob-token")

        val response = client.get("/api/v1/requests/mine") { bearerAuth("alice-token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf(second, first), list.map { it.str("id").toLong() })
        assertEquals("Hayek Hospital", list[0].str("hospitalName"))
        assertEquals("open", list[0].str("status"))
    }

    @Test
    fun `details are only for the owner`() = test {
        val id = createId()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/requests/$id") { bearerAuth("alice-token") }.status)
        assertError(client.get("/api/v1/requests/$id") { bearerAuth("bob-token") }, HttpStatusCode.NotFound, "request_not_found")
        assertError(client.get("/api/v1/requests/999") { bearerAuth("alice-token") }, HttpStatusCode.NotFound, "request_not_found")
        assertError(client.get("/api/v1/requests/abc") { bearerAuth("alice-token") }, HttpStatusCode.NotFound, "request_not_found")
    }

    // --- Fulfill / cancel ---

    @Test
    fun `owner can fulfill an open request`() = test {
        val id = createId()
        clock.now = clock.now.plus(Duration.ofHours(1))
        val response = action(id, "fulfill")
        assertEquals(HttpStatusCode.OK, response.status)
        val request = json(response.bodyAsText())
        assertEquals("fulfilled", request.str("status"))
        assertEquals("2026-06-15T10:00:00Z", request.str("closedAt"))
    }

    @Test
    fun `owner can cancel an open request`() = test {
        val id = createId()
        assertEquals("cancelled", json(action(id, "cancel").bodyAsText()).str("status"))
    }

    @Test
    fun `another user cannot fulfill or cancel`() = test {
        val id = createId()
        assertError(action(id, "fulfill", token = "bob-token"), HttpStatusCode.NotFound, "request_not_found")
        assertError(action(id, "cancel", token = "bob-token"), HttpStatusCode.NotFound, "request_not_found")
        assertEquals(RequestStatus.OPEN, requests.rows.getValue(id).status)
    }

    @Test
    fun `closed requests cannot change again`() = test {
        val fulfilled = createId()
        action(fulfilled, "fulfill")
        assertError(action(fulfilled, "cancel"), HttpStatusCode.Conflict, "request_not_open")
        assertError(action(fulfilled, "fulfill"), HttpStatusCode.Conflict, "request_not_open")

        val cancelled = createId()
        action(cancelled, "cancel")
        assertError(action(cancelled, "fulfill"), HttpStatusCode.Conflict, "request_not_open")
    }

    // --- Expiry ---

    @Test
    fun `overdue request reads as expired before the job runs`() = test {
        val id = createId(json = body(hours = 6))
        clock.now = clock.now.plus(Duration.ofHours(6))

        val details = json(client.get("/api/v1/requests/$id") { bearerAuth("alice-token") }.bodyAsText())
        assertEquals("expired", details.str("status"))
        assertEquals("2026-06-15T15:00:00Z", details.str("closedAt"))
        val mine = client.get("/api/v1/requests/mine") { bearerAuth("alice-token") }.bodyAsText()
        assertEquals("expired", Json.parseToJsonElement(mine).jsonArray[0].jsonObject.str("status"))
        // Still open in storage: only reads are adjusted.
        assertEquals(RequestStatus.OPEN, requests.rows.getValue(id).status)
    }

    @Test
    fun `overdue request cannot be fulfilled or cancelled`() = test {
        val id = createId(json = body(hours = 6))
        clock.now = clock.now.plus(Duration.ofHours(7))
        assertError(action(id, "fulfill"), HttpStatusCode.Conflict, "request_not_open")
        assertError(action(id, "cancel"), HttpStatusCode.Conflict, "request_not_open")
    }

    @Test
    fun `request is still open one second before the deadline`() = test {
        val id = createId(json = body(hours = 6))
        clock.now = clock.now.plus(Duration.ofHours(6)).minusSeconds(1)
        assertEquals("open", json(client.get("/api/v1/requests/$id") { bearerAuth("alice-token") }.bodyAsText()).str("status"))
        assertEquals(HttpStatusCode.OK, action(id, "fulfill").status)
    }

    @Test
    fun `expiry job stores expired status only for overdue open requests`() = test {
        val short = createId(json = body(hours = 6))
        val long = createId(json = body(hours = 24))
        val cancelled = createId(json = body(hours = 6))
        action(cancelled, "cancel")

        clock.now = clock.now.plus(Duration.ofHours(8))
        assertEquals(1, requests.expireOverdue(clock.instant()))

        assertEquals(RequestStatus.EXPIRED, requests.rows.getValue(short).status)
        assertEquals(Instant.parse("2026-06-15T15:00:00Z"), requests.rows.getValue(short).closedAt)
        assertEquals(RequestStatus.OPEN, requests.rows.getValue(long).status)
        assertEquals(RequestStatus.CANCELLED, requests.rows.getValue(cancelled).status)
        // Running again changes nothing.
        assertEquals(0, requests.expireOverdue(clock.instant()))
    }
}
