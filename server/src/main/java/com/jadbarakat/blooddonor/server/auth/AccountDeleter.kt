package com.jadbarakat.blooddonor.server.auth

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deletes the Firebase Auth user. Behind an interface so tests can use a fake. */
fun interface AccountDeleter {
    /** Deletes Firebase user [uid]. A user that no longer exists counts as deleted, so retries are safe. */
    suspend fun deleteFirebaseUser(uid: String)
}

class FirebaseAccountDeleter(private val auth: FirebaseAuth) : AccountDeleter {

    override suspend fun deleteFirebaseUser(uid: String) = withContext(Dispatchers.IO) {
        try {
            auth.deleteUser(uid)
        } catch (e: FirebaseAuthException) {
            if (e.authErrorCode != AuthErrorCode.USER_NOT_FOUND) throw e
        }
    }

    companion object {
        fun create(app: FirebaseApp) = FirebaseAccountDeleter(FirebaseAuth.getInstance(app))
    }
}
