package com.jadbarakat.blooddonor.auth

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.theme.BloodDonorTheme
import kotlinx.coroutines.launch

/** Picks the screen from the current auth state. */
@Composable
fun AuthRoot(viewModel: AuthViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The account picker needs an Activity, so it runs here and only the result goes to the ViewModel.
    val onGoogleSignIn: () -> Unit = {
        if (activity != null) {
            scope.launch { viewModel.onGoogleSignInResult(requestGoogleIdToken(activity)) }
        }
    }
    val onSignOut: () -> Unit = {
        scope.launch {
            clearGoogleCredentialState(context)
            viewModel.signOut()
        }
    }

    when (val state = uiState.authState) {
        AuthState.SignedOut -> AuthScreen(
            uiState = uiState,
            onSignUp = viewModel::signUp,
            onLogIn = viewModel::logIn,
            onForgotPassword = viewModel::sendPasswordReset,
            onModeChanged = viewModel::clearMessages,
            onGoogleSignIn = onGoogleSignIn,
            modifier = modifier,
        )
        is AuthState.NeedsVerification -> VerifyEmailScreen(
            email = state.email,
            uiState = uiState,
            onCheckVerified = viewModel::checkVerified,
            onResend = viewModel::resendVerification,
            onUseDifferentAccount = onSignOut,
            modifier = modifier,
        )
        is AuthState.SignedIn -> SignedInScreen(
            email = state.email,
            onSignOut = onSignOut,
            modifier = modifier,
        )
    }
}

@Composable
fun AuthScreen(
    uiState: AuthUiState,
    onSignUp: (String, String) -> Unit,
    onLogIn: (String, String) -> Unit,
    onForgotPassword: (String) -> Unit,
    onModeChanged: () -> Unit,
    onGoogleSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSignUp by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val enabled = !uiState.isLoading

    ScreenColumn(modifier) {
        Text(
            text = stringResource(if (isSignUp) R.string.auth_title_sign_up else R.string.auth_title_log_in),
            style = MaterialTheme.typography.headlineMedium,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_email_label)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.auth_password_label)) },
            supportingText = if (isSignUp) {
                { Text(stringResource(R.string.auth_password_hint)) }
            } else null,
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Messages(uiState)

        Button(
            onClick = { if (isSignUp) onSignUp(email, password) else onLogIn(email, password) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonContent(
                text = stringResource(if (isSignUp) R.string.auth_button_sign_up else R.string.auth_button_log_in),
                isLoading = uiState.isLoading,
            )
        }

        if (!isSignUp) {
            TextButton(onClick = { onForgotPassword(email) }, enabled = enabled) {
                Text(stringResource(R.string.auth_forgot_password))
            }
        }

        TextButton(
            onClick = {
                isSignUp = !isSignUp
                onModeChanged()
            },
            enabled = enabled,
        ) {
            Text(stringResource(if (isSignUp) R.string.auth_toggle_to_log_in else R.string.auth_toggle_to_sign_up))
        }

        OrDivider()

        OutlinedButton(onClick = onGoogleSignIn, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_continue_with_google))
        }
    }
}

@Composable
fun VerifyEmailScreen(
    email: String,
    uiState: AuthUiState,
    onCheckVerified: () -> Unit,
    onResend: () -> Unit,
    onUseDifferentAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !uiState.isLoading

    ScreenColumn(modifier) {
        Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.verify_body, email),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        Messages(uiState)

        Button(onClick = onCheckVerified, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            ButtonContent(stringResource(R.string.verify_button_done), uiState.isLoading)
        }
        OutlinedButton(onClick = onResend, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.verify_button_resend))
        }
        TextButton(onClick = onUseDifferentAccount, enabled = enabled) {
            Text(stringResource(R.string.verify_button_different_account))
        }
    }
}

@Composable
fun SignedInScreen(email: String, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    ScreenColumn(modifier) {
        Text(stringResource(R.string.signed_in_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.signed_in_body, email), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.signed_in_button_sign_out))
        }
    }
}

@Composable
private fun ScreenColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun Messages(uiState: AuthUiState) {
    uiState.error?.let {
        Text(
            text = it.resolve(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    uiState.info?.let {
        Text(
            text = it.resolve(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.auth_or),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ButtonContent(text: String, isLoading: Boolean) {
    if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        Text(text)
    }
}

@Composable
private fun UiMessage.resolve(): String = stringResource(resId, *args.toTypedArray())

@Preview(showBackground = true)
@Composable
private fun AuthScreenPreview() {
    BloodDonorTheme {
        AuthScreen(AuthUiState(), { _, _ -> }, { _, _ -> }, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun VerifyEmailScreenPreview() {
    BloodDonorTheme {
        VerifyEmailScreen("donor@example.com", AuthUiState(), {}, {}, {})
    }
}
