package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeRoutesTest {

    private val users = InMemoryUserRepository()

    // "Today" is 2026-06-15 in Beirut for every test.
    private val clock = Clock.fixed(Instant.parse("2026-06-15T09:00:00Z"), ZoneId.of("Asia/Beirut"))

    private val verifier = FakeTokenVerifier(
        mapOf(
            "alice-token" to VerifiedToken("uid-alice", "Alice@Example.com", emailVerified = true),
            "bob-token" to VerifiedToken("uid-bob", "bob@example.com", emailVerified = true),
            "unverified-token" to VerifiedToken("uid-carol", "carol@example.com", emailVerified = false),
        )
    )

    private val areas = FakeAreaRepository(
        listOf(
            Area(1, "Beirut", "Beirut", "Beirut"),
            Area(2, "Baabda", "Baabda", "Mount Lebanon"),
        )
    )

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val hospitals = FakeHospitalRepository(emptyList())
        val requests = InMemoryBloodRequestRepository(hospitals)
        application {
            module(verifier, users, areas, hospitals, requests, InMemoryDeviceTokenRepository(), InMemoryPledgeRepository(users, requests), clock = clock)
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.putMe(token: String, body: String): HttpResponse =
        client.put("/api/v1/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun profileJson(
        phone: String = "70 123 456",
        fullName: String = "Alice Haddad",
        extra: String = "",
    ) = """{"fullName":"$fullName","phone":"$phone","bloodType":"O+","sex":"female","areaId":1,"lastDonationDate":"2026-01-10"$extra}"""

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status)
        val body = json(response.bodyAsText())
        assertEquals(code, body["error"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("message"))
    }

    @Test
    fun `areas are public and have no coordinates`() = test {
        val response = client.get("/api/v1/areas")
        assertEquals(HttpStatusCode.OK, response.status)
        val first = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
        assertEquals(setOf("id", "name", "district", "governorate"), first.keys)
    }

    @Test
    fun `me without token is 401`() = test {
        assertError(client.get("/api/v1/me"), HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun `me with invalid token is 401`() = test {
        assertError(client.get("/api/v1/me") { bearerAuth("forged") }, HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun `unverified email is 403`() = test {
        val response = client.get("/api/v1/me") { bearerAuth("unverified-token") }
        assertError(response, HttpStatusCode.Forbidden, "email_not_verified")
    }

    @Test
    fun `me before creating a profile is 404`() = test {
        val response = client.get("/api/v1/me") { bearerAuth("alice-token") }
        assertError(response, HttpStatusCode.NotFound, "profile_not_found")
    }

    @Test
    fun `put creates profile then get returns it`() = test {
        val put = putMe("alice-token", profileJson())
        assertEquals(HttpStatusCode.OK, put.status)

        val get = client.get("/api/v1/me") { bearerAuth("alice-token") }
        assertEquals(HttpStatusCode.OK, get.status)
        val body = json(get.bodyAsText())
        assertEquals("alice@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("Alice Haddad", body["fullName"]!!.jsonPrimitive.content)
        assertEquals("+96170123456", body["phone"]!!.jsonPrimitive.content)
        assertEquals("O+", body["bloodType"]!!.jsonPrimitive.content)
        assertEquals("2026-01-10", body["lastDonationDate"]!!.jsonPrimitive.content)
        assertEquals("true", body["isDonor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `put updates existing profile and omits nulls`() = test {
        putMe("alice-token", profileJson())
        val response = putMe(
            "alice-token",
            """{"fullName":"Alice H.","phone":"+961 71 123456","isDonor":false}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals("Alice H.", body["fullName"]!!.jsonPrimitive.content)
        assertEquals("+96171123456", body["phone"]!!.jsonPrimitive.content)
        assertEquals("false", body["isDonor"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("bloodType"))
        assertEquals(1, users.rows.size)
    }

    @Test
    fun `email and uid in the body are ignored`() = test {
        val response = putMe(
            "alice-token",
            profileJson(extra = ""","email":"hacker@evil.com","firebaseUid":"uid-bob""""),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val stored = users.rows.getValue("uid-alice")
        assertEquals("alice@example.com", stored.email)
        assertFalse(users.rows.containsKey("uid-bob"))
    }

    @Test
    fun `invalid phone is 400`() = test {
        assertError(putMe("alice-token", profileJson(phone = "01 123456")), HttpStatusCode.BadRequest, "invalid_phone")
    }

    @Test
    fun `unknown area is 400`() = test {
        val body = """{"fullName":"Alice Haddad","phone":"70123456","areaId":999}"""
        assertError(putMe("alice-token", body), HttpStatusCode.BadRequest, "unknown_area")
    }

    @Test
    fun `future last donation date is 400`() = test {
        val body = """{"fullName":"Alice Haddad","phone":"70123456","lastDonationDate":"2026-06-16"}"""
        assertError(putMe("alice-token", body), HttpStatusCode.BadRequest, "invalid_last_donation_date")
    }

    @Test
    fun `donation date of today is accepted`() = test {
        val body = """{"fullName":"Alice Haddad","phone":"70123456","lastDonationDate":"2026-06-15"}"""
        assertEquals(HttpStatusCode.OK, putMe("alice-token", body).status)
    }

    @Test
    fun `full name length is checked`() = test {
        assertError(putMe("alice-token", profileJson(fullName = "A")), HttpStatusCode.BadRequest, "invalid_full_name")
        assertError(putMe("alice-token", profileJson(fullName = "x".repeat(61))), HttpStatusCode.BadRequest, "invalid_full_name")
        assertEquals(HttpStatusCode.OK, putMe("alice-token", profileJson(fullName = "x".repeat(60))).status)
    }

    @Test
    fun `invalid blood type is 400`() = test {
        val body = """{"fullName":"Alice Haddad","phone":"70123456","bloodType":"C+"}"""
        assertError(putMe("alice-token", body), HttpStatusCode.BadRequest, "invalid_blood_type")
    }

    @Test
    fun `malformed body is 400`() = test {
        assertError(putMe("alice-token", """{"fullName":"""), HttpStatusCode.BadRequest, "invalid_body")
        assertError(putMe("alice-token", """{"phone":"70123456"}"""), HttpStatusCode.BadRequest, "invalid_body")
    }

    @Test
    fun `phone taken by another user is 409`() = test {
        assertEquals(HttpStatusCode.OK, putMe("alice-token", profileJson(phone = "70 123 456")).status)
        // Same number written differently still collides after normalization.
        assertError(putMe("bob-token", profileJson(phone = "+96170123456")), HttpStatusCode.Conflict, "phone_taken")
    }

    @Test
    fun `responses contain no stack traces`() = test {
        val text = putMe("alice-token", "not json").bodyAsText()
        assertFalse("Exception" in text)
        assertFalse("at io.ktor" in text)
    }
}
