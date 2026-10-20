package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.ProfileInput
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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

/** Device tokens, the donor view of a request and GET /requests/for-me. */
class DonorRoutesTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val deviceTokens = InMemoryDeviceTokenRepository()
    private val created = mutableListOf<Long>()

    private val verifier = FakeTokenVerifier(
        mapOf(
            "alice-token" to VerifiedToken("uid-alice", "alice@example.com", emailVerified = true), // requester
            "bob-token" to VerifiedToken("uid-bob", "bob@example.com", emailVerified = true),       // notified donor
            "carol-token" to VerifiedToken("uid-carol", "carol@example.com", emailVerified = true), // not notified
            "dan-token" to VerifiedToken("uid-dan", "dan@example.com", emailVerified = true),       // no profile
        )
    )

    private val aliceId = runBlocking { users.upsert("uid-alice", "alice@example.com", profile("+96170123456")).id }
    private val bobId = runBlocking { users.upsert("uid-bob", "bob@example.com", profile("+96171123456")).id }

    init {
        runBlocking { users.upsert("uid-carol", "carol@example.com", profile("+96176123456")) }
    }

    private fun profile(phone: String) = ProfileInput("Someone", phone, "O-", "female", 1, null, true)

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(verifier, users, areas, hospitals, requests, deviceTokens, InMemoryPledgeRepository(users, requests), InMemorySafetyRepository(users, requests), FakeAccountDeleter(), onRequestCreated = { created += it }, safetyConfig = RELAXED_POSTING, clock = clock)
        }
        block()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun ApplicationTestBuilder.createRequest(note: String? = "ICU, 3rd floor", bloodType: String = "O-"): Long {
        val noteJson = note?.let { ""","note":"$it"""" }.orEmpty()
        val response = client.post("/api/v1/requests") {
            bearerAuth("alice-token")
            contentType(ContentType.Application.Json)
            setBody("""{"hospitalId":10,"bloodType":"$bloodType","component":"red_cells","unitsNeeded":2,"neededInHours":6$noteJson}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return json(response.bodyAsText())["id"]!!.jsonPrimitive.content.toLong()
    }

    private fun notify(requestId: Long, userId: Long) {
        requests.notifications += SentNotification(requestId, userId, 1, clock.instant())
    }

    private suspend fun ApplicationTestBuilder.tokenCall(method: String, token: String, body: String): HttpResponse {
        val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (method == "PUT") client.put("/api/v1/me/device-token", setup) else client.delete("/api/v1/me/device-token", setup)
    }

    // --- Device tokens ---

    @Test
    fun `device token can be registered, refreshed and removed`() = test {
        assertEquals(HttpStatusCode.NoContent, tokenCall("PUT", "bob-token", """{"token":"fcm-abc"}""").status)
        assertEquals(bobId, deviceTokens.tokens["fcm-abc"])
        assertEquals(HttpStatusCode.NoContent, tokenCall("PUT", "bob-token", """{"token":"fcm-abc"}""").status)
        assertEquals(1, deviceTokens.tokens.size)

        assertEquals(HttpStatusCode.NoContent, tokenCall("DELETE", "bob-token", """{"token":"fcm-abc"}""").status)
        assertTrue(deviceTokens.tokens.isEmpty())
    }

    @Test
    fun `a token moves to whoever signs in on the phone, and others cannot delete it`() = test {
        tokenCall("PUT", "bob-token", """{"token":"shared-phone"}""")
        tokenCall("PUT", "alice-token", """{"token":"shared-phone"}""")
        assertEquals(aliceId, deviceTokens.tokens["shared-phone"])

        tokenCall("DELETE", "bob-token", """{"token":"shared-phone"}""")
        assertEquals(aliceId, deviceTokens.tokens["shared-phone"])
    }

    @Test
    fun `device token needs a profile and a valid token`() = test {
        assertEquals(HttpStatusCode.Conflict, tokenCall("PUT", "dan-token", """{"token":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, tokenCall("PUT", "bob-token", """{"token":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, tokenCall("PUT", "bob-token", """{"token":"a b"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/me/device-token").status)
    }

    // --- New requests start the waves ---

    @Test
    fun `creating a request notifies the wave listener`() = test {
        val id = createRequest()
        assertEquals(listOf(id), created)
    }

    // --- Donor view ---

    @Test
    fun `a notified donor sees the request without anything about the requester`() = test {
        val id = createRequest()
        notify(id, bobId)

        val response = client.get("/api/v1/requests/$id") { bearerAuth("bob-token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(
            setOf("id", "bloodType", "component", "unitsNeeded", "hospitalName", "areaName", "neededBy", "note", "status"),
            body.keys,
        )
        assertEquals("Beirut", body["areaName"]!!.jsonPrimitive.content)
        assertEquals("ICU, 3rd floor", body["note"]!!.jsonPrimitive.content)
        assertTrue("requester" !in response.bodyAsText().lowercase())
        assertTrue("+961" !in response.bodyAsText())
    }

    @Test
    fun `the owner still gets the full view`() = test {
        val id = createRequest()
        val body = json(client.get("/api/v1/requests/$id") { bearerAuth("alice-token") }.bodyAsText())
        assertTrue("createdAt" in body)
        assertTrue("hospitalId" in body)
    }

    @Test
    fun `anyone who was not notified gets 404`() = test {
        val id = createRequest()
        notify(id, bobId)
        val response = client.get("/api/v1/requests/$id") { bearerAuth("carol-token") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("request_not_found", json(response.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a notified donor cannot fulfill or cancel`() = test {
        val id = createRequest()
        notify(id, bobId)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/requests/$id/fulfill") { bearerAuth("bob-token") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/requests/$id/cancel") { bearerAuth("bob-token") }.status)
    }

    @Test
    fun `the donor view shows the current status`() = test {
        val id = createRequest()
        notify(id, bobId)
        clock.now = clock.now.plus(Duration.ofHours(6))
        val body = json(client.get("/api/v1/requests/$id") { bearerAuth("bob-token") }.bodyAsText())
        assertEquals("expired", body["status"]!!.jsonPrimitive.content)
    }

    // --- for-me ---

    @Test
    fun `for-me lists open requests I was notified about, newest first`() = test {
        val first = createRequest()
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        val second = createRequest(note = null, bloodType = "A-")
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        val cancelled = createRequest(bloodType = "B-")
        val notMine = createRequest(bloodType = "AB-")
        listOf(first, second, cancelled).forEach { notify(it, bobId) }
        client.post("/api/v1/requests/$cancelled/cancel") { bearerAuth("alice-token") }

        val response = client.get("/api/v1/requests/for-me") { bearerAuth("bob-token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val ids = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
        assertEquals(listOf(second, first), ids)
        assertTrue(notMine !in ids)

        val carol = client.get("/api/v1/requests/for-me") { bearerAuth("carol-token") }.bodyAsText()
        assertEquals("[]", carol)
    }

    @Test
    fun `for-me drops requests that expired`() = test {
        val id = createRequest()
        notify(id, bobId)
        clock.now = clock.now.plus(Duration.ofHours(7))
        assertEquals("[]", client.get("/api/v1/requests/for-me") { bearerAuth("bob-token") }.bodyAsText())
    }
}
