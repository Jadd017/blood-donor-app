package com.jadbarakat.blooddonor.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jadbarakat.blooddonor.BloodDonorApp
import com.jadbarakat.blooddonor.MainActivity
import com.jadbarakat.blooddonor.R

/**
 * Receives FCM messages. While the app is in the background the system shows our notifications by
 * itself (using the default channel from the manifest) and a tap opens [MainActivity] with the
 * message data as extras; while it is open, [onMessageReceived] shows them.
 */
class BloodDonorMessagingService : FirebaseMessagingService() {

    // Deprecated with the token API; see DeviceTokenManager for why we still use tokens.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        (application as BloodDonorApp).container.deviceTokens.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val requestId = message.data[EXTRA_REQUEST_ID]?.toLongOrNull() ?: return
        val title = message.notification?.title ?: return
        val body = message.notification?.body.orEmpty()
        // Older servers sent new requests without a type.
        val type = message.data[EXTRA_TYPE] ?: TYPE_NEW_REQUEST
        val view = message.data[EXTRA_VIEW] ?: VIEW_DONOR
        showRequestNotification(this, requestId, type, view, title, body)
    }

    companion object {
        const val CHANNEL_URGENT_REQUESTS = "urgent_requests"
        const val CHANNEL_REQUEST_UPDATES = "request_updates"

        /** Set by FCM (from the message data) or by us on the notification's tap intent. */
        const val EXTRA_REQUEST_ID = "requestId"

        /** Which screen a tap opens: [VIEW_REQUESTER] (my own request) or [VIEW_DONOR]. */
        const val EXTRA_VIEW = "view"
        const val VIEW_REQUESTER = "requester"
        const val VIEW_DONOR = "donor"

        /** new_request, donor_committed, donor_withdrew, request_fulfilled or request_closed. */
        private const val EXTRA_TYPE = "type"
        private const val TYPE_NEW_REQUEST = "new_request"

        /** Creates both channels; safe to call repeatedly. */
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val urgent = NotificationChannel(
                CHANNEL_URGENT_REQUESTS,
                context.getString(R.string.notification_channel_urgent_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_urgent_description) }
            val updates = NotificationChannel(
                CHANNEL_REQUEST_UPDATES,
                context.getString(R.string.notification_channel_updates_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_updates_description) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(urgent, updates))
        }

        private fun showRequestNotification(
            context: Context,
            requestId: Long,
            type: String,
            view: String,
            title: String,
            body: String,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return

            val tap = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_REQUEST_ID, requestId.toString())
                putExtra(EXTRA_VIEW, view)
            }
            val isOwner = view == VIEW_REQUESTER
            val pendingIntent = PendingIntent.getActivity(
                context,
                (requestId * 2 + if (isOwner) 1 else 0).toInt(), // one PendingIntent per request and view
                tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val channel = if (type == TYPE_NEW_REQUEST) CHANNEL_URGENT_REQUESTS else CHANNEL_REQUEST_UPDATES
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_blood_drop)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            try {
                // One notification per request and type: a newer one of the same kind replaces it.
                NotificationManagerCompat.from(context).notify("$TAG:$type", requestId.toInt(), notification)
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call.
            }
        }

        private const val TAG = "blood_request"
    }
}
