package com.jadbarakat.blooddonor.server.data

import com.jadbarakat.blooddonor.server.api.ApiException
import io.ktor.http.HttpStatusCode

interface AreaRepository {
    suspend fun all(): List<Area>
    suspend fun exists(id: Int): Boolean
}

interface UserRepository {
    suspend fun findByUid(firebaseUid: String): UserProfile?

    /**
     * Creates or updates the profile for [firebaseUid]. Throws [ApiException] 409
     * ("phone_taken" / "email_taken") when another user already has that value.
     */
    suspend fun upsert(firebaseUid: String, email: String, input: ProfileInput): UserProfile
}

fun phoneTaken() = ApiException(HttpStatusCode.Conflict, "phone_taken", "This phone number is already registered")
fun emailTaken() = ApiException(HttpStatusCode.Conflict, "email_taken", "This email is already registered")
