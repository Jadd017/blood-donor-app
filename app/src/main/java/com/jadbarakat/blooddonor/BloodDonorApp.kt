package com.jadbarakat.blooddonor

import android.app.Application
import com.jadbarakat.blooddonor.notifications.BloodDonorMessagingService

class BloodDonorApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Must exist before the first push arrives, even if the app is never opened.
        BloodDonorMessagingService.createChannel(this)
    }
}
