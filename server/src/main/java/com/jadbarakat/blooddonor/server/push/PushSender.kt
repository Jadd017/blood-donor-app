package com.jadbarakat.blooddonor.server.push

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

const val URGENT_REQUESTS_CHANNEL = "urgent_requests"
const val REQUEST_UPDATES_CHANNEL = "request_updates"

/** A push notification. [data] is delivered to the app as key-value pairs. */
data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String>,
    /** Don't deliver after this (e.g. the request's deadline). */
    val expiresAt: Instant,
    /** The Android notification channel (created by the app). */
    val channelId: String = URGENT_REQUESTS_CHANNEL,
)

data class PushResult(
    val delivered: Set<String>,
    /** Tokens FCM says are invalid or unregistered; they should be deleted. */
    val invalid: Set<String>,
    /** Tokens that failed for another (possibly temporary) reason. */
    val failed: Set<String>,
)

/** Sends push notifications. Behind an interface so tests can use a fake. */
interface PushSender {
    suspend fun send(tokens: List<String>, message: PushMessage): PushResult
}

class FcmPushSender(private val messaging: FirebaseMessaging) : PushSender {

    override suspend fun send(tokens: List<String>, message: PushMessage): PushResult = withContext(Dispatchers.IO) {
        val delivered = mutableSetOf<String>()
        val invalid = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val ttl = Duration.between(Instant.now(), message.expiresAt).toMillis().coerceIn(60_000, MAX_TTL_MILLIS)
        // FCM accepts at most 500 messages per call.
        for (batch in tokens.distinct().chunked(500)) {
            val responses = messaging.sendEach(batch.map { build(it, message, ttl) }).responses
            batch.zip(responses).forEach { (token, response) ->
                when {
                    response.isSuccessful -> delivered += token
                    response.exception.isInvalidToken() -> invalid += token
                    else -> failed += token
                }
            }
        }
        PushResult(delivered, invalid, failed)
    }

    // setToken is deprecated in favour of setFid (Firebase Installation IDs). The app still sends
    // classic registration tokens, so this must stay setToken until both sides switch together.
    @Suppress("DEPRECATION")
    private fun build(token: String, message: PushMessage, ttlMillis: Long): Message =
        Message.builder()
            .setToken(token)
            .setNotification(Notification.builder().setTitle(message.title).setBody(message.body).build())
            .putAllData(message.data)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setTtl(ttlMillis)
                    .setNotification(AndroidNotification.builder().setChannelId(message.channelId).build())
                    .build()
            )
            .build()

    private fun FirebaseMessagingException?.isInvalidToken(): Boolean =
        this?.messagingErrorCode in setOf(
            MessagingErrorCode.UNREGISTERED,
            MessagingErrorCode.INVALID_ARGUMENT, // a malformed token; our payloads are fixed and valid
            MessagingErrorCode.SENDER_ID_MISMATCH,
        )

    private companion object {
        const val MAX_TTL_MILLIS = 28L * 24 * 3600 * 1000 // FCM's maximum, 4 weeks
    }
}
