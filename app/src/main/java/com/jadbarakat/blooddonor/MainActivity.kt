package com.jadbarakat.blooddonor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jadbarakat.blooddonor.auth.AuthRoot
import com.jadbarakat.blooddonor.auth.AuthViewModel
import com.jadbarakat.blooddonor.notifications.BloodDonorMessagingService
import com.jadbarakat.blooddonor.profile.ProfileRoot
import com.jadbarakat.blooddonor.profile.ProfileViewModel
import com.jadbarakat.blooddonor.requests.RequestsViewModel
import com.jadbarakat.blooddonor.ui.theme.BloodDonorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BloodDonorApp).container
        handleNotificationTap(intent)
        val profileViewModelFactory = viewModelFactory {
            initializer { ProfileViewModel(container.profileRepository, container.notificationSettings, container.auth) }
        }
        val authViewModelFactory = viewModelFactory {
            initializer { AuthViewModel(container.auth, container.deviceTokens) }
        }
        val requestsViewModelFactory = viewModelFactory {
            initializer { RequestsViewModel(container.requestRepository, container.deviceTokens, container.auth) }
        }
        enableEdgeToEdge()
        setContent {
            BloodDonorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AuthRoot(
                        viewModel = viewModel<AuthViewModel>(factory = authViewModelFactory),
                        modifier = Modifier.padding(innerPadding),
                    ) { onSignOut, modifier ->
                        ProfileRoot(
                            viewModel = viewModel<ProfileViewModel>(factory = profileViewModelFactory),
                            requestsViewModel = viewModel<RequestsViewModel>(factory = requestsViewModelFactory),
                            notificationSettings = container.notificationSettings,
                            pendingDonorRequestId = container.pendingDonorRequestId,
                            onSignOut = onSignOut,
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    // The app is already running (singleTop) and a notification was tapped.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    /** A tapped request notification carries "requestId" (from FCM data or our own tap intent). */
    private fun handleNotificationTap(intent: Intent?) {
        val id = intent?.getStringExtra(BloodDonorMessagingService.EXTRA_REQUEST_ID)?.toLongOrNull() ?: return
        intent.removeExtra(BloodDonorMessagingService.EXTRA_REQUEST_ID) // don't reopen it on rotation
        (application as BloodDonorApp).container.pendingDonorRequestId.value = id
    }
}
