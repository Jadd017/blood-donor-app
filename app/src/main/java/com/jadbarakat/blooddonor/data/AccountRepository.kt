package com.jadbarakat.blooddonor.data

import io.ktor.client.call.body
import io.ktor.client.request.url
import io.ktor.http.HttpMethod

/** Blocked users and account deletion. */
class AccountRepository(private val api: ApiClient) {

    /** Everyone I blocked, newest first. */
    suspend fun getBlocks(): List<BlockedUser> =
        api.send(authenticated = true, configure = { url(BLOCKS) }) { it.body<List<BlockedUser>>() }

    /** Blocks a helper on one of my requests (by [Helper.donorId]). */
    suspend fun block(userId: Long) = send(HttpMethod.Post, "$BLOCKS/$userId")

    suspend fun unblock(userId: Long) = send(HttpMethod.Delete, "$BLOCKS/$userId")

    /** Deletes my account on the server, including the Firebase user. Safe to call again. */
    suspend fun deleteAccount() = send(HttpMethod.Delete, ME)

    private suspend fun send(httpMethod: HttpMethod, path: String) {
        api.send(
            authenticated = true,
            configure = {
                method = httpMethod
                url(path)
            },
        ) { }
    }

    private companion object {
        const val ME = "api/v1/me"
        const val BLOCKS = "api/v1/blocks"
    }
}
