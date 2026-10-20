package com.jadbarakat.blooddonor.data

import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RequestRepository(private val api: ApiClient) {

    // Hospitals change only with a server migration, so one download per app session is enough.
    private val hospitalsLock = Mutex()
    private var hospitals: List<Hospital>? = null

    suspend fun getHospitals(): List<Hospital> = hospitalsLock.withLock {
        hospitals ?: api.send(authenticated = true, configure = { url(HOSPITALS) }) { it.body<List<Hospital>>() }
            .also { hospitals = it }
    }

    suspend fun create(request: CreateBloodRequest): BloodRequest =
        api.send(
            authenticated = true,
            configure = {
                method = HttpMethod.Post
                url(REQUESTS)
                contentType(ContentType.Application.Json)
                setBody(request)
            },
        ) { it.body<BloodRequest>() }

    /** Newest first. */
    suspend fun getMine(): List<BloodRequest> =
        api.send(authenticated = true, configure = { url("$REQUESTS/mine") }) { it.body<List<BloodRequest>>() }

    suspend fun get(id: Long): BloodRequest =
        api.send(authenticated = true, configure = { url("$REQUESTS/$id") }) { it.body<BloodRequest>() }

    /** Open requests this user was notified about, newest first. */
    suspend fun getForMe(): List<DonorRequest> =
        api.send(authenticated = true, configure = { url("$REQUESTS/for-me") }) { it.body<List<DonorRequest>>() }

    /** A request this user was notified about, in the donor view (nothing about the requester). */
    suspend fun getAsDonor(id: Long): DonorRequest =
        api.send(authenticated = true, configure = { url("$REQUESTS/$id") }) { it.body<DonorRequest>() }

    /** "I can help". Committing again to the same request is harmless. */
    suspend fun help(id: Long): Pledge =
        api.send(
            authenticated = true,
            configure = {
                method = HttpMethod.Post
                url("$REQUESTS/$id/help")
            },
        ) { it.body<Pledge>() }

    /** "I can't make it anymore". */
    suspend fun withdrawHelp(id: Long) {
        api.send(
            authenticated = true,
            configure = {
                method = HttpMethod.Delete
                url("$REQUESTS/$id/help")
            },
        ) { }
    }

    /** Answers "Did you donate?"; returns the updated donor view. */
    suspend fun confirmDonation(id: Long, donated: Boolean): DonorRequest =
        api.send(
            authenticated = true,
            configure = {
                method = HttpMethod.Post
                url("$REQUESTS/$id/donation")
                contentType(ContentType.Application.Json)
                setBody(DonationBody(donated))
            },
        ) { it.body<DonorRequest>() }

    /** Confirms a helper of my request as donated or no-show ([HelperOutcome]); returns the updated request. */
    suspend fun confirmHelper(id: Long, pledgeId: Long, outcome: String): BloodRequest =
        api.send(
            authenticated = true,
            configure = {
                method = HttpMethod.Post
                url("$REQUESTS/$id/helpers/$pledgeId/confirm")
                contentType(ContentType.Application.Json)
                setBody(HelperConfirmBody(outcome))
            },
        ) { it.body<BloodRequest>() }

    suspend fun fulfill(id: Long): BloodRequest = post("$REQUESTS/$id/fulfill")

    suspend fun cancel(id: Long): BloodRequest = post("$REQUESTS/$id/cancel")

    private suspend fun post(path: String): BloodRequest =
        api.send(
            authenticated = true,
            configure = {
                method = HttpMethod.Post
                url(path)
            },
        ) { it.body<BloodRequest>() }

    private companion object {
        const val HOSPITALS = "api/v1/hospitals"
        const val REQUESTS = "api/v1/requests"
    }
}
