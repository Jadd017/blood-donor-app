package com.jadbarakat.blooddonor.server.data

import com.jadbarakat.blooddonor.server.api.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Instant

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

interface HospitalRepository {
    /** Active hospitals sorted by name, optionally only those in [areaId]. */
    suspend fun all(areaId: Int? = null): List<Hospital>

    /** The hospital if it exists and is active, else null. */
    suspend fun findActive(id: Int): Hospital?
}

/** Statuses are returned as stored; callers apply [BloodRequest.effective]. */
interface BloodRequestRepository {
    /** Inserts an open request created at [now], needed by [now] + neededInHours. */
    suspend fun create(requesterId: Long, input: NewBloodRequest, now: Instant): BloodRequest

    suspend fun findById(id: Long): BloodRequest?

    /** Newest first. */
    suspend fun listByRequester(requesterId: Long): List<BloodRequest>

    /**
     * Moves request [id] from open to [status], but only if it is open and not past needed_by at [now].
     * Returns the updated request, or null if it was not open.
     */
    suspend fun close(id: Long, status: String, now: Instant): BloodRequest?

    /** Marks every open request past needed_by at [now] as expired. Returns how many changed. */
    suspend fun expireOverdue(now: Instant): Int
}

fun phoneTaken() = ApiException(HttpStatusCode.Conflict, "phone_taken", "This phone number is already registered")
fun emailTaken() = ApiException(HttpStatusCode.Conflict, "email_taken", "This email is already registered")
