package com.jadbarakat.blooddonor

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.jadbarakat.blooddonor.data.ApiClient
import com.jadbarakat.blooddonor.data.ProfileRepository
import com.jadbarakat.blooddonor.data.RequestRepository
import com.jadbarakat.blooddonor.notifications.DeviceTokenManager
import com.jadbarakat.blooddonor.notifications.NotificationSettings
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual dependency wiring: one instance of each long-lived object for the whole app. */
class AppContainer(context: Context) {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val apiClient: ApiClient by lazy { ApiClient(BuildConfig.API_BASE_URL, auth) }
    val profileRepository: ProfileRepository by lazy { ProfileRepository(apiClient) }
    val requestRepository: RequestRepository by lazy { RequestRepository(apiClient) }
    val deviceTokens: DeviceTokenManager by lazy { DeviceTokenManager(apiClient, auth) }
    val notificationSettings = NotificationSettings(context.applicationContext)

    /** A request to open in the donor view, set when a notification is tapped. */
    val pendingDonorRequestId = MutableStateFlow<Long?>(null)
}
