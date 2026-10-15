package com.jadbarakat.blooddonor

import com.google.firebase.auth.FirebaseAuth
import com.jadbarakat.blooddonor.data.ApiClient
import com.jadbarakat.blooddonor.data.ProfileRepository

/** Manual dependency wiring: one instance of each long-lived object for the whole app. */
class AppContainer {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val apiClient: ApiClient by lazy { ApiClient(BuildConfig.API_BASE_URL, auth) }
    val profileRepository: ProfileRepository by lazy { ProfileRepository(apiClient) }
}
