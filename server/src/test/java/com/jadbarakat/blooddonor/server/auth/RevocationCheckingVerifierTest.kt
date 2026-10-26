package com.jadbarakat.blooddonor.server.auth

import com.google.firebase.auth.AuthErrorCode
import com.jadbarakat.blooddonor.server.MutableClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevocationCheckingVerifierTest {

    private val clock = MutableClock(Instant.parse("2026-06-15T09:00:00Z"))

    /** Like Firebase: "<uid>-token" is a valid token; the remote check fails for [revoked] users. */
    private inner class FakeFirebase {
        val revoked = mutableSetOf<String>()
        var down = false
        var remoteChecks = 0

        suspend fun decode(idToken: String, checkRevoked: Boolean): VerifiedToken? {
            val uid = idToken.removeSuffix("-token").takeIf { idToken.endsWith("-token") } ?: return null
            if (checkRevoked) {
                remoteChecks++
                if (down) throw AuthUnavailableException(IllegalStateException("network"))
                if (uid in revoked) return null
            }
            return VerifiedToken(uid, "$uid@example.com", emailVerified = true)
        }
    }

    private val firebase = FakeFirebase()

    private fun verifier(maxCachedUsers: Int = 10_000) =
        RevocationCheckingVerifier(firebase::decode, clock, Duration.ofMinutes(5), maxCachedUsers)

    @Test
    fun `the revocation check runs once per user every 5 minutes`() = runBlocking {
        val verifier = verifier()
        repeat(3) { assertEquals("alice", verifier.verify("alice-token")?.uid) }
        assertEquals(1, firebase.remoteChecks)

        clock.now = clock.now.plus(Duration.ofMinutes(5))
        verifier.verify("alice-token")
        assertEquals(2, firebase.remoteChecks)

        verifier.verify("bob-token")
        assertEquals(3, firebase.remoteChecks, "each user is checked on their own")
    }

    @Test
    fun `revoked, disabled or deleted users are refused once the cache expires`() = runBlocking {
        val verifier = verifier()
        verifier.verify("alice-token")
        firebase.revoked += "alice"
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        assertNull(verifier.verify("alice-token"))
        assertNull(verifier.verify("alice-token"), "a refusal is not cached as a pass")
        assertEquals(3, firebase.remoteChecks)
    }

    @Test
    fun `forget makes the next call ask Firebase again`() = runBlocking {
        val verifier = verifier()
        verifier.verify("alice-token")
        firebase.revoked += "alice"
        verifier.forget("alice")
        assertNull(verifier.verify("alice-token"))
    }

    @Test
    fun `invalid tokens never reach the remote check`() = runBlocking {
        assertNull(verifier().verify("garbage"))
        assertEquals(0, firebase.remoteChecks)
    }

    @Test
    fun `an outage is reported, not treated as a bad token`() {
        firebase.down = true
        assertFailsWith<AuthUnavailableException> { runBlocking { verifier().verify("alice-token") } }
    }

    @Test
    fun `the cache is bounded`() = runBlocking {
        val verifier = verifier(maxCachedUsers = 2)
        verifier.verify("alice-token")
        verifier.verify("bob-token")
        verifier.verify("carol-token") // evicts alice, the least recently seen
        verifier.verify("alice-token")
        assertEquals(4, firebase.remoteChecks)
    }

    @Test
    fun `token and user problems are refusals, anything else is an outage`() {
        for (code in listOf(
            AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND,
            AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.INVALID_ID_TOKEN,
        )) {
            assertTrue(FirebaseTokenVerifier.rejectsToken(code), "$code")
        }
        assertFalse(FirebaseTokenVerifier.rejectsToken(null))
        assertFalse(FirebaseTokenVerifier.rejectsToken(AuthErrorCode.CERTIFICATE_FETCH_FAILED))
    }
}
