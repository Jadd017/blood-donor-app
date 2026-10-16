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
