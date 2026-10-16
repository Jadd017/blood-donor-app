package com.jadbarakat.blooddonor.notifications

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.jadbarakat.blooddonor.data.ApiClient
import com.jadbarakat.blooddonor.data.DeviceTokenBody
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Keeps the server's copy of this install's FCM token in sync with who is signed in.
 *
 * Uses FCM registration tokens (getToken/deleteToken), which firebase-messaging 25.x deprecates in
 * favour of Firebase Installation IDs (register()/onRegistered, opt-in via the
 * firebase_messaging_installation_id_enabled manifest flag, sent with Message.setFid on the server).
 * Tokens remain the default mode and work; switching means changing app and server together.
 */
@Suppress("DEPRECATION")
class DeviceTokenManager(
    private val api: ApiClient,
    private val auth: FirebaseAuth,
    private val messaging: () -> FirebaseMessaging = { FirebaseMessaging.getInstance() },
) {
    // For token refreshes that arrive in the messaging service, outside any screen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sends the current token to the server. Call once the signed-in user's profile exists. */
    suspend fun register() {
        if (auth.currentUser == null) return
        try {
            send(HttpMethod.Put, messaging().token.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not fatal: we try again on the next app start or token refresh.
            Log.w(TAG, "Registering the device token failed", e)
        }
    }

    /** FCM issued a new token (e.g. after reinstalling or clearing data). */
    fun onNewToken(token: String) {
        if (auth.currentUser == null) return
        scope.launch {
            try {
                send(HttpMethod.Put, token)
            } catch (e: Exception) {
                Log.w(TAG, "Sending the refreshed device token failed", e)
            }
        }
    }

    /** Tells the server to stop notifying this phone. Call before signing out (it needs the ID token). */
    suspend fun unregister() {
        try {
            send(HttpMethod.Delete, messaging().token.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unregistering the device token failed", e)
        }
    }

    /** Discards this install's token, so the next account gets a fresh one. Call after signing out. */
    suspend fun deleteLocalToken() {
        try {
            messaging().deleteToken().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Deleting the local FCM token failed", e)
        }
    }

    private suspend fun send(method: HttpMethod, token: String) {
        api.send(
            authenticated = true,
            configure = {
                this.method = method
                url(DEVICE_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(DeviceTokenBody(token))
            },
        ) { }
    }

    private companion object {
        const val TAG = "DeviceTokenManager"
        const val DEVICE_TOKEN = "api/v1/me/device-token"
    }
}
