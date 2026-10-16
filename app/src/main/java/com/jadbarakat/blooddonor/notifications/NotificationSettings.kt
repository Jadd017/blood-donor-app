package com.jadbarakat.blooddonor.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/** Whether this phone can show request notifications, and whether we already asked. */
class NotificationSettings(private val context: Context) {

    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    /** Android 13+ needs the POST_NOTIFICATIONS runtime permission. */
    val needsRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** True if notifications are allowed for the app and the urgent channel isn't switched off. */
    fun areEnabled(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = manager.getNotificationChannel(BloodDonorMessagingService.CHANNEL_URGENT_REQUESTS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Whether we've shown the explanation and the system prompt once already. */
    var askedForPermission: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED, value) }

    /** The system screen where the user can turn our notifications back on. */
    fun settingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val KEY_ASKED = "asked_for_permission"
    }
}
