package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.api.HELPER_PHONES_VISIBLE_AFTER_CLOSE
import com.jadbarakat.blooddonor.server.api.MIN_TIME_BEFORE_CONFIRMING_HELPER
import com.jadbarakat.blooddonor.server.auth.AuthUnavailableException
import com.jadbarakat.blooddonor.server.auth.RevocationCheckingVerifier
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.Hospital
import com.jadbarakat.blooddonor.server.data.MAX_DEVICE_TOKENS_PER_USER
import com.jadbarakat.blooddonor.server.data.NewBloodRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.RequestStatus
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** Authentication, input limits, privacy rules and HTTP hardening (see SECURITY.md). */
class SecurityRoutesTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))
    private val today = LocalDate.of(2026, 6, 15)
    private val users = InMemoryUserRepository()
    private val areas = FakeAreaRepository(listOf(Area(1, "Beirut", "Beirut", "Beirut")))
    private val hospitals = FakeHospitalRepository(listOf(Hospital(10, "Rizk Hospital", areaId = 1)), areaNames = mapOf(1 to "Beirut"))
    private val requests = InMemoryBloodRequestRepository(hospitals)
    private val pledges = InMemoryPledgeRepository(users, requests)
    private val safety = InMemorySafetyRepository(users, requests, pledges)
    private val deviceTokens = InMemoryDeviceTokenRepository()
    private val accountDeleter = FakeAccountDeleter()

    // Firebase as the real verifier sees it: "<name>-token" is valid; the remote check refuses
    // revoked or disabled users and users whose account was deleted.
    private val revoked = mutableSetOf<String>()
    private var firebaseDown = false
    private val verifier = RevocationCheckingVerifier(
        decode = { idToken, checkRevoked ->
            val name = idToken.removeSuffix("-token").takeIf { idToken.endsWith("-token") }
            if (name == null) {
                null
            } else if (checkRevoked && firebaseDown) {
                throw AuthUnavailableException(IllegalStateException("network"))
            } else if (checkRevoked && ("uid-$name" in revoked || "uid-$name" in accountDeleter.deleted)) {
                null
            } else {
                VerifiedToken("uid-$name", "$name@example.com", emailVerified = true)
            }
        },
        clock = clock,
    )

    private fun user(name: String, fullName: String, phone: String) =
        runBlocking { users.upsert("uid-$name", "$name@example.com", ProfileInput(fullName, phone, "O-", "male", 1, null, true)).id }

    private val aliceId = user("alice", "Alice Haddad", "+96170111111") // requester
    private val bobId = user("bob", "Bob Khoury", "+96171222222")       // notified donor
    private val carolId = user("carol", "Carol Saad", "+96176333333")   // never involved

    private fun test(
        safetyConfig: SafetyConfig = SafetyConfig(),
        healthCheck: () -> Boolean = { true },
        trustedProxies: Set<String> = emptySet(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            module(
                verifier, users, areas, hospitals, requests, deviceTokens, pledges, safety, accountDeleter,
                safetyConfig = safetyConfig, clock = clock, healthCheck = healthCheck, trustedProxies = trustedProxies,
            )
        }
        block()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        val text = response.bodyAsText()
        assertEquals(status, response.status, text)
        assertEquals(code, json(text)["error"]!!.jsonPrimitive.content)
        assertFalse("Exception" in text || "\tat " in text, "no internals in errors: $text")
    }

    /** An O- request by Alice that Bob was notified about. */
    private fun aliceRequest(note: String? = "ICU, 3rd floor"): Long {
        val id = runBlocking { requests.create(aliceId, NewBloodRequest(10, "O-", "red_cells", 1, 24, note), clock.instant()).id }
        requests.notifications += SentNotification(id, bobId, 1, clock.instant())
        return id
    }

    private suspend fun ApplicationTestBuilder.call(method: String, path: String, who: String, body: String? = null): HttpResponse {
        val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth("$who-token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "POST" -> client.post(path, setup)
            "PUT" -> client.put(path, setup)
            "DELETE" -> client.delete(path, setup)
            else -> client.get(path, setup)
        }
    }

    private fun profileJson(fullName: String = "Bob Khoury", lastDonationDate: String? = null) =
        """{"fullName":${Json.encodeToString(fullName)},"phone":"71 222 222","bloodType":"O-","sex":"male","areaId":1""" +
            (lastDonationDate?.let { ""","lastDonationDate":"$it"""" } ?: "") + "}"

    private fun requestJson(note: String) =
        """{"hospitalId":10,"bloodType":"O-","component":"red_cells","unitsNeeded":1,"neededInHours":24,"note":${Json.encodeToString(note)}}"""

    private fun fcmToken(n: Int) = "token$n:APA91b" + "x".repeat(150)

    // --- Firebase tokens ---

    @Test
    fun `a revoked or disabled user gets 401`() = test {
        revoked += "uid-bob"
        assertError(call("GET", "/api/v1/me", "bob"), HttpStatusCode.Unauthorized, "unauthorized")
        assertEquals(HttpStatusCode.OK, call("GET", "/api/v1/me", "alice").status)
    }

    @Test
    fun `a deleted account's token stops working at once, despite the cache`() = test {
        assertEquals(HttpStatusCode.OK, call("GET", "/api/v1/me", "bob").status)
        assertEquals(HttpStatusCode.NoContent, call("DELETE", "/api/v1/me", "bob").status)
        assertError(call("GET", "/api/v1/me", "bob"), HttpStatusCode.Unauthorized, "unauthorized")
        assertError(call("PUT", "/api/v1/me", "bob", profileJson()), HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun `a Firebase outage is 503, not a sign-out`() = test {
        firebaseDown = true
        assertError(call("GET", "/api/v1/me", "bob"), HttpStatusCode.ServiceUnavailable, "auth_unavailable")
    }

    // --- Body size ---

    @Test
    fun `bodies over 16 KB are refused with 413`() = test {
        val huge = profileJson(fullName = "B".repeat(MAX_BODY_BYTES.toInt()))
        assertError(call("PUT", "/api/v1/me", "bob", huge), HttpStatusCode.PayloadTooLarge, "payload_too_large")
        assertEquals("Bob Khoury", users.findById(bobId)!!.fullName)
    }

    @Test
    fun `a chunked body without Content-Length is cut off too`() = test {
        val bytes = profileJson(fullName = "B".repeat(MAX_BODY_BYTES.toInt())).toByteArray()
        val response = client.put("/api/v1/me") {
            bearerAuth("bob-token")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(bytes)
            })
        }
        assertError(response, HttpStatusCode.PayloadTooLarge, "payload_too_large")
    }

    // --- Free text ---

    @Test
    fun `names with control or invisible characters are refused`() = test {
        for (name in listOf("Bob‮yruohK", "Bob\u0000Khoury", "Bob\nKhoury", "Bob​Khoury", "Bob\tKhoury")) {
            assertError(call("PUT", "/api/v1/me", "bob", profileJson(fullName = name)), HttpStatusCode.BadRequest, "invalid_full_name")
        }
        assertEquals("Bob Khoury", users.findById(bobId)!!.fullName)
    }

    @Test
    fun `runs of spaces in a name are collapsed and Arabic with joiners is fine`() = test {
        assertEquals(HttpStatusCode.OK, call("PUT", "/api/v1/me", "bob", profileJson(fullName = "Bob    Khoury")).status)
        assertEquals("Bob Khoury", users.findById(bobId)!!.fullName)
        assertEquals(HttpStatusCode.OK, call("PUT", "/api/v1/me", "bob", profileJson(fullName = "جاد‌بركات")).status)
    }

    @Test
    fun `notes and report details may have line breaks but no hidden characters`() = test {
        assertError(call("POST", "/api/v1/requests", "carol", requestJson("Room‮ 12")), HttpStatusCode.BadRequest, "invalid_note")
        assertEquals(HttpStatusCode.Created, call("POST", "/api/v1/requests", "carol", requestJson("ICU\n3rd floor")).status)

        val id = aliceRequest()
        assertError(
            call("POST", "/api/v1/requests/$id/report", "bob", """{"reason":"other","details":"spam\u0000"}"""),
            HttpStatusCode.BadRequest, "invalid_details",
        )
        assertEquals(
            HttpStatusCode.NoContent,
            call("POST", "/api/v1/requests/$id/report", "bob", """{"reason":"other","details":"line 1\nline 2"}""").status,
        )
    }

    @Test
    fun `a last donation date more than 10 years ago is refused`() = test {
        for (date in listOf("-999999999-01-01", "0001-01-01", today.minusYears(10).minusDays(1).toString())) {
            assertError(
                call("PUT", "/api/v1/me", "bob", profileJson(lastDonationDate = date)),
                HttpStatusCode.BadRequest, "invalid_last_donation_date",
            )
        }
        assertEquals(HttpStatusCode.OK, call("PUT", "/api/v1/me", "bob", profileJson(lastDonationDate = today.minusYears(10).toString())).status)
    }

    // --- Ids and tokens ---

    @Test
    fun `zero, negative and non-numeric ids are not found`() = test {
        val id = aliceRequest()
        for (bad in listOf("0", "-1", "abc", "99999999999999999999")) {
            assertError(call("GET", "/api/v1/requests/$bad", "alice"), HttpStatusCode.NotFound, "request_not_found")
            assertError(call("POST", "/api/v1/requests/$bad/help", "bob"), HttpStatusCode.NotFound, "request_not_found")
            assertError(call("POST", "/api/v1/blocks/$bad", "alice"), HttpStatusCode.NotFound, "user_not_found")
            assertError(
                call("POST", "/api/v1/requests/$id/helpers/$bad/confirm", "alice", """{"outcome":"donated"}"""),
                HttpStatusCode.NotFound, "helper_not_found",
            )
        }
        assertError(call("GET", "/api/v1/hospitals?areaId=0", "alice"), HttpStatusCode.BadRequest, "invalid_area_id")
    }

    @Test
    fun `device tokens must look like FCM tokens`() = test {
        for (bad in listOf("abc", "x".repeat(513), "token/with/slashes:APA91b-0000000000", "tok en:APA91b-0000000000")) {
            assertError(call("PUT", "/api/v1/me/device-token", "bob", """{"token":"$bad"}"""), HttpStatusCode.BadRequest, "invalid_token")
        }
        assertEquals(HttpStatusCode.NoContent, call("PUT", "/api/v1/me/device-token", "bob", """{"token":"${fcmToken(1)}"}""").status)
    }

    @Test
    fun `a user keeps only their 5 most recent device tokens`() = test {
        for (n in 1..MAX_DEVICE_TOKENS_PER_USER + 1) {
            call("PUT", "/api/v1/me/device-token", "bob", """{"token":"${fcmToken(n)}"}""")
        }
        val bobs = deviceTokens.tokens.filterValues { it == bobId }.keys
        assertEquals((2..MAX_DEVICE_TOKENS_PER_USER + 1).map { fcmToken(it) }.toSet(), bobs)

        // Refreshing an old token makes it the newest again.
        call("PUT", "/api/v1/me/device-token", "bob", """{"token":"${fcmToken(2)}"}""")
        call("PUT", "/api/v1/me/device-token", "bob", """{"token":"${fcmToken(7)}"}""")
        assertTrue(fcmToken(2) in deviceTokens.tokens)
        assertFalse(fcmToken(3) in deviceTokens.tokens)
    }

    // --- Other people's data ---

    @Test
    fun `hidden requests disappear for donors, except their own pledge without the note`() = test {
        val seen = aliceRequest()
        val committed = aliceRequest()
        assertEquals(HttpStatusCode.OK, call("POST", "/api/v1/requests/$committed/help", "bob").status)
        for (id in listOf(seen, committed)) {
            requests.rows[id] = requests.rows[id]!!.copy(hidden = true, status = RequestStatus.CANCELLED, closedAt = clock.instant())
        }

        assertError(call("GET", "/api/v1/requests/$seen", "bob"), HttpStatusCode.NotFound, "request_not_found")
        val mine = call("GET", "/api/v1/requests/$committed", "bob")
        assertEquals(HttpStatusCode.OK, mine.status)
        val view = json(mine.bodyAsText())
        assertNull(view["note"])
        assertTrue(view.containsKey("myPledge"))
        // The owner still sees their own request.
        assertEquals(HttpStatusCode.OK, call("GET", "/api/v1/requests/$seen", "alice").status)
    }

    @Test
    fun `others can't answer, confirm or unblock on someone else's behalf`() = test {
        val id = aliceRequest()
        call("POST", "/api/v1/requests/$id/help", "bob")
        clock.now = clock.now.plus(MIN_TIME_BEFORE_CONFIRMING_HELPER)
        val pledge = runBlocking { pledges.find(id, bobId)!!.id }

        assertError(call("POST", "/api/v1/requests/$id/donation", "carol", """{"donated":true}"""), HttpStatusCode.NotFound, "request_not_found")
        assertError(
            call("POST", "/api/v1/requests/$id/helpers/$pledge/confirm", "carol", """{"outcome":"no_show"}"""),
            HttpStatusCode.NotFound, "request_not_found",
        )
        assertNull(pledges.find(id, bobId)!!.requesterOutcome)

        runBlocking { safety.block(aliceId, bobId, null, clock.instant()) }
        assertEquals(HttpStatusCode.NoContent, call("DELETE", "/api/v1/blocks/$bobId", "carol").status)
        assertTrue(safety.blocked(aliceId, bobId), "Carol can only remove her own blocks")
    }

    @Test
    fun `a helper can't be confirmed in the first 30 minutes unless the request closed`() = test {
        val id = aliceRequest()
        call("POST", "/api/v1/requests/$id/help", "bob")
        val pledge = runBlocking { pledges.find(id, bobId)!!.id }
        assertError(
            call("POST", "/api/v1/requests/$id/helpers/$pledge/confirm", "alice", """{"outcome":"donated"}"""),
            HttpStatusCode.Conflict, "too_early",
        )
        assertNull(users.findById(bobId)!!.lastDonationDate)

        assertEquals(HttpStatusCode.OK, call("POST", "/api/v1/requests/$id/fulfill", "alice").status)
        assertEquals(
            HttpStatusCode.OK,
            call("POST", "/api/v1/requests/$id/helpers/$pledge/confirm", "alice", """{"outcome":"donated"}""").status,
        )
    }

    private suspend fun ApplicationTestBuilder.helperPhone(id: Long): String =
        json(call("GET", "/api/v1/requests/$id", "alice").bodyAsText())["helpers"]!!.jsonArray.single().jsonObject["phone"]!!.jsonPrimitive.content

    @Test
    fun `a helper's phone is hidden once either of them blocked the other`() = test {
        val id = aliceRequest()
        call("POST", "/api/v1/requests/$id/help", "bob")
        assertEquals("+96171222222", helperPhone(id))

        call("POST", "/api/v1/requests/$id/block-requester", "bob")
        assertEquals("", helperPhone(id))
        val helper = json(call("GET", "/api/v1/requests/$id", "alice").bodyAsText())["helpers"]!!.jsonArray.single().jsonObject
        assertEquals("Bob Khoury", helper["fullName"]!!.jsonPrimitive.content, "only the phone goes")
    }

    @Test
    fun `helpers' phones are hidden a week after the request closed`() = test {
        val id = aliceRequest()
        call("POST", "/api/v1/requests/$id/help", "bob")
        call("POST", "/api/v1/requests/$id/fulfill", "alice")
        clock.now = clock.now.plus(HELPER_PHONES_VISIBLE_AFTER_CLOSE).minusSeconds(1)
        assertEquals("+96171222222", helperPhone(id))
        clock.now = clock.now.plusSeconds(1)
        assertEquals("", helperPhone(id))
    }

    // --- Rate limits ---

    @Test
    fun `calls with a missing or invalid token are rate limited per IP`() = test(SafetyConfig(rateLimitGeneralPerMinute = 2)) {
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth("garbage") }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/me") { bearerAuth("garbage") }.status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/me").status)
        assertEquals(HttpStatusCode.OK, call("GET", "/api/v1/me", "bob").status, "signed-in users count on their own")
    }

    @Test
    fun `a spoofed X-Forwarded-For doesn't get a fresh limit`() = test(SafetyConfig(rateLimitGeneralPerMinute = 2)) {
        val statuses = (1..3).map { n -> client.get("/api/v1/me") { header(HttpHeaders.XForwardedFor, "198.51.100.$n") }.status }
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    @Test
    fun `behind a trusted proxy each client IP has its own limit`() =
        test(SafetyConfig(rateLimitGeneralPerMinute = 1), trustedProxies = setOf("localhost", "127.0.0.1")) {
            fun get(ip: String) = runBlocking { client.get("/api/v1/me") { header(HttpHeaders.XForwardedFor, ip) }.status }
            assertEquals(HttpStatusCode.Unauthorized, get("198.51.100.1"))
            assertEquals(HttpStatusCode.Unauthorized, get("198.51.100.2"))
            assertEquals(HttpStatusCode.TooManyRequests, get("198.51.100.1"))
        }

    @Test
    fun `profile, device-token and write routes have their own limits`() =
        test(SafetyConfig(rateLimitProfilePerMinute = 1, rateLimitDeviceTokenPerMinute = 1, rateLimitWritePerMinute = 1)) {
            assertEquals(HttpStatusCode.OK, call("PUT", "/api/v1/me", "bob", profileJson()).status)
            assertEquals(HttpStatusCode.TooManyRequests, call("PUT", "/api/v1/me", "bob", profileJson()).status)

            assertEquals(HttpStatusCode.NoContent, call("PUT", "/api/v1/me/device-token", "bob", """{"token":"${fcmToken(1)}"}""").status)
            assertEquals(HttpStatusCode.TooManyRequests, call("DELETE", "/api/v1/me/device-token", "bob", """{"token":"${fcmToken(1)}"}""").status)

            val first = aliceRequest()
            val second = aliceRequest()
            assertEquals(HttpStatusCode.OK, call("POST", "/api/v1/requests/$first/cancel", "alice").status)
            assertEquals(HttpStatusCode.TooManyRequests, call("POST", "/api/v1/requests/$second/fulfill", "alice").status)
            assertEquals(HttpStatusCode.OK, call("GET", "/api/v1/requests/mine", "alice").status, "reads aren't affected")
        }

    // --- HTTP ---

    @Test
    fun `responses carry security headers and no CORS`() = test {
        for (response in listOf(
            client.get("/api/v1/areas") { header(HttpHeaders.Origin, "https://evil.example") },
            call("GET", "/api/v1/me", "bob"),
            client.get("/health"),
        )) {
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("default-src 'none'; frame-ancestors 'none'", response.headers["Content-Security-Policy"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertTrue(response.headers[HttpHeaders.StrictTransportSecurity]!!.startsWith("max-age="))
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
        val preflight = client.options("/api/v1/me") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertNull(preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertFalse(preflight.status.value in 200..299)
    }

    @Test
    fun `health says OK or Unavailable and nothing else`() {
        test { assertEquals("OK", client.get("/health").bodyAsText()) }
        test(healthCheck = { false }) {
            val response = client.get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("Unavailable", response.bodyAsText())
        }
        test(healthCheck = { throw IllegalStateException("db password wrong") }) {
            val response = client.get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("Unavailable", response.bodyAsText())
        }
    }
}
