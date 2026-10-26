package com.jadbarakat.blooddonor.server.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What we trust from a verified ID token. */
data class VerifiedToken(val uid: String, val email: String?, val emailVerified: Boolean)

/** The authenticated caller. */
data class UserPrincipal(val uid: String, val email: String)

/** Firebase couldn't be asked whether a token is still valid (e.g. a network problem); answered with 503. */
class AuthUnavailableException(cause: Throwable) : RuntimeException("Token verification is unavailable", cause)

/** Verifies a Firebase ID token. Behind an interface so tests can use a fake. */
interface TokenVerifier {
    /**
     * Returns null if the token is invalid, expired, for another project, revoked, or its user is
     * disabled or deleted. Throws [AuthUnavailableException] if that can't be determined right now.
     */
    suspend fun verify(idToken: String): VerifiedToken?

    /** Forgets anything remembered about [uid], e.g. after the account was deleted. */
    fun forget(uid: String) {}
}

/**
 * Checks every token's signature and expiry, and asks Firebase whether the user was disabled,
 * deleted or had their sessions revoked at most once per [revocationCheckTtl] per uid.
 * [decode] verifies a token, with the remote revocation check when its second argument is true.
 */
class RevocationCheckingVerifier(
    private val decode: suspend (idToken: String, checkRevoked: Boolean) -> VerifiedToken?,
    private val clock: Clock = Clock.systemUTC(),
    private val revocationCheckTtl: Duration = Duration.ofMinutes(5),
    private val maxCachedUsers: Int = 10_000,
) : TokenVerifier {
    // uid -> when its revocation check last passed. Access-ordered, so the least recently seen users go first.
    private val checkedAt = object : LinkedHashMap<String, Instant>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>) = size > maxCachedUsers
    }

    override suspend fun verify(idToken: String): VerifiedToken? {
        val token = decode(idToken, false) ?: return null
        if (recentlyChecked(token.uid)) return token
        val checked = decode(idToken, true)
        if (checked == null) {
            forget(token.uid)
            return null
        }
        synchronized(checkedAt) { checkedAt[checked.uid] = clock.instant() }
        return checked
    }

    override fun forget(uid: String) {
        synchronized(checkedAt) { checkedAt.remove(uid) }
    }

    private fun recentlyChecked(uid: String): Boolean = synchronized(checkedAt) {
        val at = checkedAt[uid] ?: return false
        clock.instant().isBefore(at.plus(revocationCheckTtl))
    }
}

object FirebaseTokenVerifier {
    fun create(app: FirebaseApp): TokenVerifier {
        val auth = FirebaseAuth.getInstance(app)
        return RevocationCheckingVerifier(decode = { idToken, checkRevoked -> decode(auth, idToken, checkRevoked) })
    }

    private suspend fun decode(auth: FirebaseAuth, idToken: String, checkRevoked: Boolean): VerifiedToken? =
        withContext(Dispatchers.IO) {
            try {
                val token = auth.verifyIdToken(idToken, checkRevoked)
                VerifiedToken(token.uid, token.email, token.isEmailVerified)
            } catch (e: FirebaseAuthException) {
                if (rejectsToken(e.authErrorCode)) null else throw AuthUnavailableException(e)
            } catch (e: IllegalArgumentException) {
                null // empty or malformed token
            }
        }

    /** True if [code] says the token or its user is no good; anything else is Firebase being unreachable. */
    fun rejectsToken(code: AuthErrorCode?): Boolean = code in REJECTED

    private val REJECTED = setOf(
        AuthErrorCode.INVALID_ID_TOKEN,
        AuthErrorCode.EXPIRED_ID_TOKEN,
        AuthErrorCode.REVOKED_ID_TOKEN,
        AuthErrorCode.USER_DISABLED,
        AuthErrorCode.USER_NOT_FOUND,
        AuthErrorCode.TENANT_ID_MISMATCH,
    )
}

/** The one Firebase Admin app, shared by token verification and push messaging. */
fun initFirebase(credentialsPath: String, projectId: String): FirebaseApp {
    val file = File(credentialsPath)
    require(file.isFile) { "Firebase service account file not found: $credentialsPath" }
    val options = FirebaseOptions.builder()
        .setCredentials(file.inputStream().use { GoogleCredentials.fromStream(it) })
        .setProjectId(projectId)
        .build()
    return FirebaseApp.initializeApp(options)
}
