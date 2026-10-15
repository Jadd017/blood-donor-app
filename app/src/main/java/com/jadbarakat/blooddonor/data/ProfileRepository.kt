package com.jadbarakat.blooddonor.data

import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

class ProfileRepository(private val api: ApiClient) {

    /** The signed-in user's profile, or null if they haven't created one yet. */
    suspend fun getMe(): Profile? = try {
        api.send(authenticated = true, build = { url(ME) }) { it.body<Profile>() }
    } catch (e: ApiException.Http) {
        if (e.status == 404 && e.code == ErrorCodes.PROFILE_NOT_FOUND) null else throw e
    }

    suspend fun saveMe(request: ProfileRequest): Profile =
        api.send(
            authenticated = true,
            build = {
                method = HttpMethod.Put
                url(ME)
                contentType(ContentType.Application.Json)
                setBody(request)
            },
        ) { it.body<Profile>() }

    suspend fun getAreas(): List<Area> =
        api.send(authenticated = false, build = { url(AREAS) }) { it.body<List<Area>>() }

    private companion object {
        const val ME = "api/v1/me"
        const val AREAS = "api/v1/areas"
    }
}
