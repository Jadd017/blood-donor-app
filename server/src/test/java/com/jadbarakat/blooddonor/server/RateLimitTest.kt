package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)

    private val verifier = FakeTokenVerifier(
        listOf("alice", "bob").associate { "$it-token" to VerifiedToken("uid-$it", "$it@example.com", emailVerified = true) }
    )

    init {
        runBlocking {
            users.upsert("uid-alice", "alice@example.com", ProfileInput("Alice", "+96170111111", "O-", "male", 1, null, true))
            users.upsert("uid-bob", "bob@example.com", ProfileInput("Bob", "+96171222222", "O-", "male", 1, null, true))
        }
    }

    private fun test(config: SafetyConfig, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(
                verifier, users, areas, hospitals, requests, InMemoryDeviceTokenRepository(), pledges,
                InMemorySafetyRepository(users, requests, pledges), FakeAccountDeleter(), safetyConfig = config, clock = clock,
            )
        }
        block()
    }

    private val bloodTypes = listOf("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+")

    private suspend fun ApplicationTestBuilder.post(who: String, n: Int) = client.post("/api/v1/requests") {
        bearerAuth("$who-token")
        contentType(ContentType.Application.Json)
        setBody("""{"hospitalId":10,"bloodType":"${bloodTypes[n]}","component":"red_cells","unitsNeeded":1,"neededInHours":24}""")
    }

    private suspend fun assertRateLimited(response: HttpResponse) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("rate_limited", Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `posting requests has its own stricter limit, per user`() = test(
        RELAXED_POSTING.copy(rateLimitPostRequestPerMinute = 2),
    ) {
        assertEquals(HttpStatusCode.Created, post("alice", 0).status)
        assertEquals(HttpStatusCode.Created, post("alice", 1).status)
        assertRateLimited(post("alice", 2))
        // Another user isn't affected, and Alice can still read.
        assertEquals(HttpStatusCode.Created, post("bob", 0).status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/requests/mine") { bearerAuth("alice-token") }.status)
    }

    @Test
    fun `the general limit covers every authenticated route`() = test(RELAXED_POSTING.copy(rateLimitGeneralPerMinute = 3)) {
        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth("alice-token") }.status) }
        assertRateLimited(post("alice", 0))
        assertRateLimited(client.get("/api/v1/me") { bearerAuth("alice-token") })
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth("bob-token") }.status)
    }

    @Test
    fun `report, help and block routes are limited too`() = test(
        RELAXED_POSTING.copy(rateLimitReportPerMinute = 1, rateLimitHelpPerMinute = 1, rateLimitBlocksPerMinute = 1),
    ) {
        // The calls fail (no such request), but they still count.
        client.post("/api/v1/requests/1/report") { bearerAuth("alice-token") }
        assertRateLimited(client.post("/api/v1/requests/1/report") { bearerAuth("alice-token") })
        client.post("/api/v1/requests/1/help") { bearerAuth("alice-token") }
        assertRateLimited(client.post("/api/v1/requests/1/help") { bearerAuth("alice-token") })
        client.post("/api/v1/blocks/2") { bearerAuth("alice-token") }
        assertRateLimited(client.post("/api/v1/requests/1/block-requester") { bearerAuth("alice-token") })
    }

    @Test
    fun `signed-out calls are limited per IP`() = test(RELAXED_POSTING.copy(rateLimitGeneralPerMinute = 2)) {
        repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/areas").status) }
        assertRateLimited(client.get("/api/v1/areas"))
    }

    @Test
    fun `the default limits leave normal use alone`() = test(SafetyConfig()) {
        repeat(20) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth("alice-token") }.status) }
    }
}
