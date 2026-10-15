package com.jadbarakat.blooddonor

import android.app.Application

class BloodDonorApp : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
