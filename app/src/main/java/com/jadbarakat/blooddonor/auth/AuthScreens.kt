package com.jadbarakat.blooddonor.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.ui.components.AppScreen
import com.jadbarakat.blooddonor.ui.components.AppTopBar
import com.jadbarakat.blooddonor.ui.components.IconTile
import com.jadbarakat.blooddonor.ui.components.InlineMessage
import com.jadbarakat.blooddonor.ui.components.PrimaryButton
import com.jadbarakat.blooddonor.ui.components.SecondaryButton
import com.jadbarakat.blooddonor.ui.components.TertiaryButton
import com.jadbarakat.blooddonor.ui.components.Tone
import com.jadbarakat.blooddonor.ui.components.appTextFieldColors
import com.jadbarakat.blooddonor.ui.resolve
import com.jadbarakat.blooddonor.ui.theme.BloodDonorTheme
import com.jadbarakat.blooddonor.ui.theme.Dimens
import com.jadbarakat.blooddonor.ui.theme.RobotoMedium
import com.jadbarakat.blooddonor.ui.theme.extendedColors
import kotlinx.coroutines.launch

/**
 * Picks the screen from the current auth state. Once the user is signed in (and verified),
 * [signedInContent] takes over; it gets the sign-out action and the modifier.
 */
@Composable
fun AuthRoot(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    signedInContent: @Composable (onSignOut: () -> Unit, modifier: Modifier) -> Unit,
) {
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
        is AuthState.SignedIn -> signedInContent(onSignOut, modifier)
    }
}

/**
 * Signed out: the welcome screen (logo, headline, "Continue with email" and Google), then the
 * email sign-up / log-in form. Which one shows is local to this screen.
 */
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
    var showEmailForm by rememberSaveable { mutableStateOf(false) }
    if (showEmailForm) {
        val back = {
            showEmailForm = false
            onModeChanged()
        }
        BackHandler(enabled = !uiState.isLoading, onBack = back)
        EmailForm(uiState, onSignUp, onLogIn, onForgotPassword, onModeChanged, onBack = back, modifier = modifier)
    } else {
        WelcomeScreen(
            uiState = uiState,
            onContinueWithEmail = {
                showEmailForm = true
                onModeChanged()
            },
            onGoogleSignIn = onGoogleSignIn,
            modifier = modifier,
        )
    }
}

@Composable
private fun WelcomeScreen(
    uiState: AuthUiState,
    onContinueWithEmail: () -> Unit,
    onGoogleSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !uiState.isLoading
    // At least screen-high, so the spacers centre the headline and push the buttons down;
    // it scrolls on small screens or with large fonts.
    BoxWithConstraints(modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))
        LogoTile()
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.extendedColors.redText,
        )
        Text(
            stringResource(R.string.welcome_headline),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Messages(uiState)
        PrimaryButton(
            text = stringResource(R.string.welcome_continue_email),
            onClick = onContinueWithEmail,
            enabled = enabled,
            icon = R.drawable.ic_mail,
            modifier = Modifier.fillMaxWidth(),
        )
        GoogleSignInButton(onClick = onGoogleSignIn, enabled = enabled, loading = uiState.isLoading)
        Text(
            stringResource(R.string.welcome_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.extendedColors.tertiaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    }
}

/** The red rounded tile with the white drop. */
@Composable
private fun LogoTile() {
    IconTile(
        R.drawable.ic_drop,
        size = 72.dp,
        iconSize = 40.dp,
        container = MaterialTheme.colorScheme.primary,
        tint = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(22.dp),
    )
}

@Composable
private fun EmailForm(
    uiState: AuthUiState,
    onSignUp: (String, String) -> Unit,
    onLogIn: (String, String) -> Unit,
    onForgotPassword: (String) -> Unit,
    onModeChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSignUp by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val enabled = !uiState.isLoading

    AppScreen(
        modifier = modifier,
        topBar = { AppTopBar(title = null, onBack = onBack, backEnabled = enabled) },
    ) {
        Text(
            text = stringResource(if (isSignUp) R.string.auth_title_sign_up else R.string.auth_title_log_in),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_email_label)) },
            singleLine = true,
            enabled = enabled,
            colors = appTextFieldColors(),
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
            colors = appTextFieldColors(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Messages(uiState)

        PrimaryButton(
            text = stringResource(if (isSignUp) R.string.auth_button_sign_up else R.string.auth_button_log_in),
            onClick = { if (isSignUp) onSignUp(email, password) else onLogIn(email, password) },
            loading = uiState.isLoading,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isSignUp) {
                TertiaryButton(
                    text = stringResource(R.string.auth_forgot_password),
                    onClick = { onForgotPassword(email) },
                    enabled = enabled,
                )
            }
            TertiaryButton(
                text = stringResource(if (isSignUp) R.string.auth_toggle_to_log_in else R.string.auth_toggle_to_sign_up),
                onClick = {
                    isSignUp = !isSignUp
                    onModeChanged()
                },
                enabled = enabled,
                color = MaterialTheme.extendedColors.redText,
            )
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

    AppScreen(modifier = modifier, centered = true) {
        IconTile(
            R.drawable.ic_mail,
            size = 72.dp,
            iconSize = 34.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.extendedColors.redText,
            shape = RoundedCornerShape(22.dp),
        )
        Text(
            stringResource(R.string.verify_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.verify_body, email),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Messages(uiState)

        PrimaryButton(
            text = stringResource(R.string.verify_button_done),
            onClick = onCheckVerified,
            loading = uiState.isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            text = stringResource(R.string.verify_button_resend),
            onClick = onResend,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        TertiaryButton(
            text = stringResource(R.string.verify_button_different_account),
            onClick = onUseDifferentAccount,
            enabled = enabled,
        )
    }
}

@Composable
private fun Messages(uiState: AuthUiState) {
    uiState.error?.let { InlineMessage(it.resolve(), Tone.ERROR) }
    uiState.info?.let { InlineMessage(it.resolve(), Tone.SUCCESS) }
}

/**
 * "Continue with Google", following Google's sign-in branding guidelines: the standard "G" logo,
 * Roboto Medium 14 sp (bundled, so phone font settings can't change it), and Google's light
 * (white, #747775 outline) or dark (#131314, #8E918F outline) colours. Pill shape and full width
 * are allowed variants.
 */
@Composable
private fun GoogleSignInButton(onClick: () -> Unit, enabled: Boolean, loading: Boolean) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF131314) else Color.White
    val border = if (dark) Color(0xFF8E918F) else Color(0xFF747775)
    val text = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = text,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            // Google's disabled style is 38% opacity; not while our own sign-in is running.
            .alpha(if (enabled || loading) 1f else 0.38f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Image(painterResource(R.drawable.ic_google_logo), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.auth_continue_with_google),
                style = TextStyle(
                    fontFamily = RobotoMedium,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                ),
            )
        }
    }
}

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
