package com.jadbarakat.blooddonor

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
import com.jadbarakat.blooddonor.profile.ProfileRoot
import com.jadbarakat.blooddonor.profile.ProfileViewModel
import com.jadbarakat.blooddonor.ui.theme.BloodDonorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BloodDonorApp).container
        val profileViewModelFactory = viewModelFactory {
            initializer { ProfileViewModel(container.profileRepository, container.auth) }
        }
        enableEdgeToEdge()
        setContent {
            BloodDonorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AuthRoot(
                        viewModel = viewModel<AuthViewModel>(),
                        modifier = Modifier.padding(innerPadding),
                    ) { onSignOut, modifier ->
                        ProfileRoot(
                            viewModel = viewModel<ProfileViewModel>(factory = profileViewModelFactory),
                            onSignOut = onSignOut,
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }
}
