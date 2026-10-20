package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Blocking: from the donor view (by request), from the helper list (by user), and its effects. */
class BlockRoutesTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)
    private val safety = InMemorySafetyRepository(users, requests, pledges)

    private val verifier = FakeTokenVerifier(
        listOf("alice", "bob", "carol").associate { "$it-token" to VerifiedToken("uid-$it", "$it@example.com", emailVerified = true) }
    )

    private fun user(name: String, fullName: String, phone: String) =
        runBlocking { users.upsert("uid-$name", "$name@example.com", ProfileInput(fullName, phone, "O-", "male", 1, null, true)).id }

    private val aliceId = user("alice", "Alice Haddad", "+96170111111") // requester
    private val bobId = user("bob", "Bob Khoury", "+96171222222")       // donor
    private val carolId = user("carol", "Carol Saad", "+96176333333")   // never involved with Alice

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(verifier, users, areas, hospitals, requests, InMemoryDeviceTokenRepository(), pledges, safety, FakeAccountDeleter(), clock = clock) }
        block()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun array(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

    /** An O- request by Alice that Bob was notified about. */
    private fun aliceRequest(bloodType: String = "O-"): Long {
        val id = runBlocking { requests.create(aliceId, NewBloodRequest(10, bloodType, "red_cells", 1, 24, null), clock.instant()).id }
        requests.notifications += SentNotification(id, bobId, 1, clock.instant())
        return id
    }

    private suspend fun ApplicationTestBuilder.call(method: String, path: String, who: String): HttpResponse = when (method) {
        "POST" -> client.post(path) { bearerAuth("$who-token") }
        "DELETE" -> client.delete(path) { bearerAuth("$who-token") }
        else -> client.get(path) { bearerAuth("$who-token") }
    }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status, response.bodyAsText())
        assertEquals(code, json(response.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a donor blocks the requester from the request without learning who it is`() = test {
        val id = aliceRequest()
        assertEquals(HttpStatusCode.NoContent, call("POST", "/api/v1/requests/$id/block-requester", "bob").status)
        assertTrue(safety.blocked(bobId, aliceId))

        val list = call("GET", "/api/v1/blocks", "bob").bodyAsText()
        assertFalse("Alice" in list || "Haddad" in list || "+961" in list, list)
        val entry = array(list).single().jsonObject
        assertEquals(aliceId, entry["userId"]!!.jsonPrimitive.long)
        assertEquals("Rizk Hospital", entry["requestHospitalName"]!!.jsonPrimitive.content)
        assertEquals("2026-06-15T09:00:00Z", entry["requestCreatedAt"]!!.jsonPrimitive.content)
        assertNull(entry["fullName"])
    }

    @Test
    fun `only someone who saw the request can block its requester`() = test {
        val id = aliceRequest()
        assertError(call("POST", "/api/v1/requests/$id/block-requester", "carol"), HttpStatusCode.NotFound, "request_not_found")
        assertError(call("POST", "/api/v1/requests/$id/block-requester", "alice"), HttpStatusCode.Conflict, "own_request")
        assertTrue(safety.blockRows.isEmpty())
    }

    @Test
    fun `blocked requests disappear from for-me and can't be helped with, both ways`() = test {
        val first = aliceRequest()
        call("POST", "/api/v1/requests/$first/block-requester", "bob")
        val second = aliceRequest(bloodType = "A-")

        assertEquals("[]", call("GET", "/api/v1/requests/for-me", "bob").bodyAsText())
        assertError(call("POST", "/api/v1/requests/$second/help", "bob"), HttpStatusCode.Forbidden, "blocked")

        // Alice blocking Bob has the same effect.
        call("DELETE", "/api/v1/blocks/$aliceId", "bob")
        assertEquals(2, array(call("GET", "/api/v1/requests/for-me", "bob").bodyAsText()).size)
        safety.block(aliceId, bobId, null, clock.instant())
        assertEquals("[]", call("GET", "/api/v1/requests/for-me", "bob").bodyAsText())
        assertError(call("POST", "/api/v1/requests/$second/help", "bob"), HttpStatusCode.Forbidden, "blocked")
    }

    @Test
    fun `a requester blocks a helper by user id, and sees their name in the list`() = test {
        val id = aliceRequest()
        assertEquals(HttpStatusCode.OK, call("POST", "/api/v1/requests/$id/help", "bob").status)
        val helper = json(call("GET", "/api/v1/requests/$id", "alice").bodyAsText())["helpers"]!!.jsonArray.single().jsonObject
        assertEquals(bobId, helper["donorId"]!!.jsonPrimitive.long)

        assertEquals(HttpStatusCode.NoContent, call("POST", "/api/v1/blocks/$bobId", "alice").status)
        assertEquals(HttpStatusCode.NoContent, call("POST", "/api/v1/blocks/$bobId", "alice").status) // again: no-op
        val entry = array(call("GET", "/api/v1/blocks", "alice").bodyAsText()).single().jsonObject
        assertEquals("Bob Khoury", entry["fullName"]!!.jsonPrimitive.content)
        assertNull(entry["requestHospitalName"])

        assertEquals(HttpStatusCode.NoContent, call("DELETE", "/api/v1/blocks/$bobId", "alice").status)
        assertEquals("[]", call("GET", "/api/v1/blocks", "alice").bodyAsText())
    }

    @Test
    fun `users can't be blocked by id unless they helped you`() = test {
        // Otherwise anyone could look up names by blocking arbitrary ids.
        assertError(call("POST", "/api/v1/blocks/$carolId", "alice"), HttpStatusCode.NotFound, "user_not_found")
        assertError(call("POST", "/api/v1/blocks/999", "alice"), HttpStatusCode.NotFound, "user_not_found")
        assertError(call("POST", "/api/v1/blocks/abc", "alice"), HttpStatusCode.NotFound, "user_not_found")
        assertError(call("POST", "/api/v1/blocks/$aliceId", "alice"), HttpStatusCode.BadRequest, "cannot_block_self")
        assertTrue(safety.blockRows.isEmpty())
    }
}
