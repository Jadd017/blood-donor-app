package com.jadbarakat.blooddonor.server.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What we trust from a verified ID token. */
data class VerifiedToken(val uid: String, val email: String?, val emailVerified: Boolean)

/** The authenticated caller. */
data class UserPrincipal(val uid: String, val email: String)

/** Verifies a Firebase ID token. Behind an interface so tests can use a fake. */
interface TokenVerifier {
    /** Returns null if the token is invalid, expired or for another project. */
    suspend fun verify(idToken: String): VerifiedToken?
}

class FirebaseTokenVerifier(private val auth: FirebaseAuth) : TokenVerifier {

    override suspend fun verify(idToken: String): VerifiedToken? = withContext(Dispatchers.IO) {
        try {
            val token = auth.verifyIdToken(idToken)
            VerifiedToken(token.uid, token.email, token.isEmailVerified)
        } catch (e: FirebaseAuthException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        fun create(credentialsPath: String, projectId: String): FirebaseTokenVerifier {
            val file = File(credentialsPath)
            require(file.isFile) { "Firebase service account file not found: $credentialsPath" }
            val options = FirebaseOptions.builder()
                .setCredentials(file.inputStream().use { GoogleCredentials.fromStream(it) })
                .setProjectId(projectId)
                .build()
            return FirebaseTokenVerifier(FirebaseAuth.getInstance(FirebaseApp.initializeApp(options)))
        }
    }
}
