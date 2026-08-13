package com.auralis.crisisconnect.screens.settings

import android.app.Activity
import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.nearby.NearbyDiscoveryPreferences
import com.auralis.crisisconnect.screens.SettingsViewModel
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.PhoneNumberInput
import com.auralis.crisisconnect.ui.components.SmsOtpAutoFillEffect
import com.auralis.crisisconnect.ui.components.composeE164
import com.auralis.crisisconnect.ui.components.defaultCountryIso
import com.auralis.crisisconnect.ui.components.fallbackCountry
import com.auralis.crisisconnect.ui.components.rememberCountryList
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    profileViewModel: ProfileViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by profileViewModel.uiState.collectAsStateWithLifecycle()
    var nameDialogOpen by rememberSaveable { mutableStateOf(false) }
    var tempName by rememberSaveable { mutableStateOf("") }
    var emailLoginDialogOpen by rememberSaveable { mutableStateOf(false) }
    var emailInput by rememberSaveable { mutableStateOf("") }
    // Password kept in process-only memory (not Bundle) to avoid being
    // serialized to disk during save-state / process death.
    var passwordInput by remember { mutableStateOf("") }
    var emailLoginValidationRequested by rememberSaveable { mutableStateOf(false) }
    var emailPasswordVisible by rememberSaveable { mutableStateOf(false) }
    val emailLoginSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val snackbarHostState = remember { SnackbarHostState() }

    val signInClient = remember(context) { Identity.getSignInClient(context) }
    val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
    val activity = context as? Activity

    LaunchedEffect(nameDialogOpen) {
        if (nameDialogOpen) {
            tempName = uiState.username
        }
    }

    LaunchedEffect(emailLoginDialogOpen) {
        if (emailLoginDialogOpen) {
            emailInput = uiState.email
            passwordInput = ""
            emailLoginValidationRequested = false
            emailPasswordVisible = false
        }
        profileViewModel.clearEmailSignInError()
    }

    LaunchedEffect(uiState.username) {
        if (uiState.username.isNotBlank() && uiState.username != settingsViewModel.userName) {
            settingsViewModel.updateUserName(uiState.username)
        }
    }

    LaunchedEffect(Unit) {
        profileViewModel.events.collect { event ->
            when (event) {
                is ProfileEvent.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        withDismissAction = true,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val credential = signInClient.getSignInCredentialFromIntent(result.data)
            val idToken = credential.googleIdToken
            val displayName = credential.displayName ?: ""
            val emailAddress = credential.id ?: ""

            if (idToken != null) {
                profileViewModel.onGoogleSignInSuccess(idToken, displayName, emailAddress)
            } else {
                profileViewModel.onGoogleSignInFailed()
            }
        } else {
            profileViewModel.onGoogleSignInFailed()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            profileViewModel.onProfileImageSelected(it)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            AppBackTopBar(
                titleRes = R.string.profile_title,
                onNavigateBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                            MaterialTheme.colorScheme.background
                        ),
                        startY = 0f,
                        endY = 800f
                    )
                )
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileHeader(
                    state = uiState,
                    localDisplayName = settingsViewModel.userName,
                    onChangePhoto = { imagePicker.launch("image/*") },
                    onRemovePhoto = profileViewModel::removeProfilePhoto
                )
                ProfileInfoCard(uiState = uiState)
                // Phone sign-in / sign-up card (mirrors the welcome flow): available to regular
                // users while signed out, above the username. Firebase phone auth both registers
                // and signs in from the same verify-by-SMS flow.
                if (!uiState.isSignedIn) {
                    PhoneAuthCard(
                        profileViewModel = profileViewModel,
                        isLoading = uiState.isLoading
                    )
                }
                NameEditorCard(
                    name = uiState.username,
                    onEdit = { nameDialogOpen = true }
                )
                if (uiState.isSignedIn) {
                    if (profileViewModel.canApproveRole(uiState.role) && !uiState.verified) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Verified,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text(
                                    text = stringResource(R.string.profile_verify_server_managed),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.profile_signed_in_state),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    MedicalInfoCard()
                    // Rescue certificates are only issuable to authority roles (admin /
                    // field team) — the backend refuses everyone else, so ordinary
                    // (e.g. phone-verified) accounts should not see the card at all.
                    if (profileViewModel.canApproveRole(uiState.role)) {
                        ProfileCertificateCard(viewModel = profileViewModel)
                    }
                    OutlinedButton(
                        onClick = { profileViewModel.logout() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.profile_logout_button),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    DeleteAccountSection(
                        isDeleting = uiState.isDeletingAccount,
                        // The Activity is what lets a stale federated session reauthenticate in
                        // place instead of sending the user back through a full sign-out.
                        onConfirm = { profileViewModel.deleteAccountAndData(activity) }
                    )
                } else {
                    AuthorizedInstitutionNotice()

                    AuthProviderButtonGroup(
                        enabled = !uiState.isLoading,
                        onGoogleClick = {
                            if (googleWebClientId.isBlank()) {
                                profileViewModel.onGoogleSignInFailed(
                                    IllegalStateException("Google Sign-In client ID is not configured.")
                                )
                                return@AuthProviderButtonGroup
                            }
                            val request = GetSignInIntentRequest.Builder()
                                .setServerClientId(googleWebClientId)
                                .build()

                            signInClient.getSignInIntent(request)
                                .addOnSuccessListener { pendingIntent ->
                                    val req = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                    googleLauncher.launch(req)
                                }
                                .addOnFailureListener { e ->
                                    val error = if (e is ApiException) e else e
                                    profileViewModel.onGoogleSignInFailed(error)
                                }
                        },
                        onMicrosoftClick = {
                            activity?.let(profileViewModel::signInWithMicrosoft)
                                ?: profileViewModel.onAuthProviderUnavailable()
                        },
                        onAppleClick = {
                            activity?.let(profileViewModel::signInWithAppleProvider)
                                ?: profileViewModel.onAuthProviderUnavailable()
                        },
                        onEnterpriseClick = {
                            activity?.let(profileViewModel::signInWithEnterpriseSso)
                                ?: profileViewModel.onAuthProviderUnavailable()
                        },
                        onEmailClick = { emailLoginDialogOpen = true }
                    )
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (nameDialogOpen) {
        AlertDialog(
            onDismissRequest = { nameDialogOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val updatedName = tempName
                    profileViewModel.updateUsername(updatedName) { success ->
                        if (success) {
                            settingsViewModel.updateUserName(updatedName.trim())
                        }
                    }
                    nameDialogOpen = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { nameDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.edit_name)) },
            text = {
                TextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    placeholder = { Text(stringResource(R.string.enter_name)) },
                    singleLine = true
                )
            }
        )
    }

    if (emailLoginDialogOpen) {
        val focusManager = LocalFocusManager.current
        val normalizedEmail = emailInput.trim()
        val isEmailValid = normalizedEmail.isNotBlank() &&
            Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()
        val isPasswordValid = passwordInput.isNotBlank()
        val showEmailError = emailLoginValidationRequested && !isEmailValid
        val showPasswordError = emailLoginValidationRequested && !isPasswordValid
        val emailSignInError = uiState.emailSignInError

        fun submitEmailLogin() {
            emailLoginValidationRequested = true
            if (!isEmailValid || !isPasswordValid || uiState.isLoading) {
                return
            }
            profileViewModel.signInWithEmailPassword(
                email = normalizedEmail,
                password = passwordInput
            ) { success ->
                if (success) {
                    emailLoginDialogOpen = false
                    passwordInput = ""
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = {
                if (!uiState.isLoading) {
                    focusManager.clearFocus(force = true)
                    profileViewModel.clearEmailSignInError()
                    emailLoginDialogOpen = false
                }
            },
            sheetState = emailLoginSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.profile_email_login_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.profile_email_login_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!emailSignInError.isNullOrBlank()) {
                    Text(
                        text = emailSignInError,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        profileViewModel.clearEmailSignInError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_email)) },
                    isError = showEmailError,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Email,
                            contentDescription = null
                        )
                    },
                    supportingText = {
                        if (showEmailError) {
                            Text(text = stringResource(R.string.profile_email_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    shape = MaterialTheme.shapes.large
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        profileViewModel.clearEmailSignInError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_password_label)) },
                    isError = showPasswordError || !emailSignInError.isNullOrBlank(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { emailPasswordVisible = !emailPasswordVisible }) {
                            Icon(
                                imageVector = if (emailPasswordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = stringResource(
                                    if (emailPasswordVisible) {
                                        R.string.profile_password_hide
                                    } else {
                                        R.string.profile_password_show
                                    }
                                )
                            )
                        }
                    },
                    supportingText = {
                        if (showPasswordError) {
                            Text(text = stringResource(R.string.profile_password_required))
                        }
                    },
                    visualTransformation = if (emailPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        submitEmailLogin()
                    }),
                    shape = MaterialTheme.shapes.large
                )

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        submitEmailLogin()
                    },
                    enabled = !uiState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(R.string.profile_sign_in_action),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        profileViewModel.clearEmailSignInError()
                        emailLoginDialogOpen = false
                    },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

}

/**
 * Phone sign-in / sign-up card, mirroring the welcome flow's phone step: pick a country, type the
 * number, get an SMS code and verify it. Firebase phone auth both registers a new user and signs an
 * existing one in from this single verify-by-SMS flow. Shown for signed-out users above the username.
 */
@Composable
private fun PhoneAuthCard(
    profileViewModel: ProfileViewModel,
    isLoading: Boolean
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val countries = rememberCountryList()
    var selectedIso by rememberSaveable { mutableStateOf(defaultCountryIso(context)) }
    var nationalNumber by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var serverCodeSent by rememberSaveable { mutableStateOf(false) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    val country = remember(selectedIso, countries) {
        countries.firstOrNull { it.iso.equals(selectedIso, ignoreCase = true) }
            ?: countries.firstOrNull { it.iso == "US" }
            ?: countries.firstOrNull()
            ?: fallbackCountry()
    }

    // User Consent is safe only for the server/Twilio OTP path. Firebase Phone Auth listens to
    // the same broadcast action with a different payload and must be allowed to own it alone.
    SmsOtpAutoFillEffect(
        isListening = serverCodeSent && codeSent,
        onCodeReceived = { code = it }
    )

    // Phone added from the profile too (not only the welcome flow) → default "let nearby contacts
    // find me" on, respecting a prior explicit choice. RfcommForegroundService hosts discovery and
    // reacts to this preference on its own. Mirrors the welcome flow's internetMarkVerified.
    fun onPhoneVerified() {
        scope.launch {
            runCatching { NearbyDiscoveryPreferences.enableByDefaultIfUnset(context) }
        }
    }

    fun submit() {
        errorText = null
        val hostActivity = activity
        if (hostActivity == null) {
            profileViewModel.onAuthProviderUnavailable()
            return
        }
        val e164 = composeE164(country, nationalNumber)
        if (!codeSent) {
            serverCodeSent = false
            profileViewModel.startPhoneSignIn(
                activity = hostActivity,
                phoneNumber = e164,
                onServerCodeSent = { serverCodeSent = true },
                onCodeSent = { codeSent = true },
                onResult = { success -> if (success) onPhoneVerified() },
                onError = { errorText = it }
            )
        } else {
            profileViewModel.confirmPhoneSignInCode(
                code = code.trim(),
                verifiedNumberE164 = e164,
                onError = { errorText = it },
                onResult = { success -> if (success) onPhoneVerified() }
            )
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Phone,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.profile_phone_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            PhoneNumberInput(
                country = country,
                onCountryChange = { selectedIso = it.iso },
                nationalNumber = nationalNumber,
                onNationalNumberChange = { nationalNumber = it; errorText = null },
                countries = countries,
                enabled = !codeSent && !isLoading,
                isError = errorText != null,
                imeAction = ImeAction.Done,
                onImeAction = {
                    focusManager.clearFocus()
                    submit()
                }
            )

            if (codeSent) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter(Char::isDigit).take(8)
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.welcome_internet_code_label)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
                    },
                    isError = errorText != null,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        submit()
                    }),
                    shape = MaterialTheme.shapes.large
                )
            }

            errorText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Only surface the send-code button once a number has been typed; it stays visible
            // through the verify-code phase.
            if (codeSent || nationalNumber.isNotBlank()) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        submit()
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(
                            if (codeSent) {
                                R.string.profile_phone_verify_code_action
                            } else {
                                R.string.profile_phone_send_code_action
                            }
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private enum class AuthProviderBrand {
    GOOGLE,
    MICROSOFT,
    APPLE,
    PHONE,
    ENTERPRISE,
    EMAIL
}

@Composable
private fun AuthProviderButtonGroup(
    enabled: Boolean,
    onGoogleClick: () -> Unit,
    onMicrosoftClick: () -> Unit,
    onAppleClick: () -> Unit,
    onEnterpriseClick: () -> Unit,
    onEmailClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.profile_login_card_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 1. E-posta ile Giriş (Primary)
            Button(
                onClick = onEmailClick,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.profile_login_email_button),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // 2. Bölücü Çizgi (Divider) — phone (SMS) sign-in moved to its own card above,
            // so this card is only the institution/social providers.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                Text(
                    text = " " + stringResource(R.string.profile_login_or_separator).lowercase(Locale.getDefault()) + " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }

            // 4. Sosyal İkon Satırı (Google, Microsoft, Apple)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SocialLoginIconButton(
                    brand = AuthProviderBrand.GOOGLE,
                    enabled = enabled,
                    onClick = onGoogleClick
                )
                Spacer(modifier = Modifier.width(20.dp))
                SocialLoginIconButton(
                    brand = AuthProviderBrand.MICROSOFT,
                    enabled = enabled,
                    onClick = onMicrosoftClick
                )
                Spacer(modifier = Modifier.width(20.dp))
                SocialLoginIconButton(
                    brand = AuthProviderBrand.APPLE,
                    enabled = enabled,
                    onClick = onAppleClick
                )
            }

            // 5. Kurumsal SSO (Outlined Button)
            OutlinedButton(
                onClick = onEnterpriseClick,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.profile_login_enterprise_sso_button),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialLoginIconButton(
    brand: AuthProviderBrand,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(60.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.5.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        ),
        shadowElevation = 1.5.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (brand) {
                AuthProviderBrand.GOOGLE -> Image(
                    painter = painterResource(R.drawable.ic_brand_google),
                    contentDescription = stringResource(R.string.profile_login_google_button),
                    modifier = Modifier.size(24.dp)
                )
                AuthProviderBrand.MICROSOFT -> Image(
                    painter = painterResource(R.drawable.ic_brand_microsoft),
                    contentDescription = stringResource(R.string.profile_login_microsoft_button),
                    modifier = Modifier.size(22.dp)
                )
                AuthProviderBrand.APPLE -> Image(
                    painter = painterResource(R.drawable.ic_brand_apple),
                    contentDescription = stringResource(R.string.profile_login_apple_button),
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
                else -> {}
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    state: ProfileUiState,
    localDisplayName: String,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        )
                    )
                )
                .padding(vertical = 32.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .border(
                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        CircleShape
                    )
                    .clickable { onChangePhoto() },
                contentAlignment = Alignment.Center
            ) {
                val bitmap = state.profileBitmap
                val avatarDisplayName = state.username.ifBlank {
                    localDisplayName.ifBlank {
                        state.email.substringBefore("@")
                    }
                }
                val avatarContentDescription = if (bitmap != null) {
                    stringResource(R.string.profile_photo_content_description)
                } else {
                    stringResource(R.string.profile_default_avatar_content_description)
                }
                ContactAvatar(
                    displayName = avatarDisplayName,
                    stableKey = "local_profile_avatar",
                    bitmap = bitmap,
                    photoUrl = state.photoUrl,
                    modifier = Modifier
                        .size(128.dp)
                        .semantics { contentDescription = avatarContentDescription },
                    textStyle = MaterialTheme.typography.headlineMedium
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val username = state.username.ifBlank { stringResource(R.string.profile_username_empty) }
                Text(
                    text = username,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                if (state.email.isNotBlank()) {
                    Text(
                        text = state.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else if (state.phoneNumber.isNotBlank()) {
                    Text(
                        text = state.phoneNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            TextButton(onClick = onChangePhoto) {
                Text(text = stringResource(R.string.profile_change_photo))
            }

            if (state.profileBitmap != null || !state.photoUrl.isNullOrBlank()) {
                TextButton(onClick = onRemovePhoto) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.profile_remove_photo),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.role.isNotBlank()) {
                    ProfileTag(
                        icon = Icons.Outlined.Work,
                        label = state.role.uppercase()
                    )
                }
                if (state.isSignedIn) {
                    val verificationIcon = if (state.verified) {
                        Icons.Filled.Verified
                    } else {
                        Icons.Filled.Close
                    }
                    val verificationTint = if (state.verified) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }

                    ProfileTag(
                        icon = verificationIcon,
                        label = if (state.verified) {
                            stringResource(R.string.profile_verified_yes)
                        } else {
                            stringResource(R.string.profile_verified_no)
                        },
                        tint = verificationTint
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileInfoCard(uiState: ProfileUiState) {
    val normalizedRole = uiState.role.trim().lowercase(Locale.US)
    val infoItems = buildList {
        add(
            stringResource(R.string.profile_username) to
                uiState.username.ifBlank { stringResource(R.string.profile_username_empty) }
        )
        add(
            stringResource(R.string.profile_email) to
                uiState.email.ifBlank { stringResource(R.string.profile_email_empty) }
        )
        if (uiState.phoneNumber.isNotBlank()) {
            add(stringResource(R.string.profile_phone) to uiState.phoneNumber)
        }
        add(
            stringResource(R.string.profile_country) to
                uiState.country.ifBlank { stringResource(R.string.profile_country_empty) }
        )
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.profile_account_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            infoItems.forEach { (label, value) ->
                ProfileInfoRow(label = label, value = value)
            }

            if (uiState.isSignedIn && (normalizedRole == "admin" || normalizedRole == "fieldteam")) {
                ProfileInfoRow(
                    label = stringResource(R.string.profile_agency),
                    value = uiState.agency.ifBlank { stringResource(R.string.profile_agency_empty) }
                )
                ProfileInfoRow(
                    label = stringResource(R.string.profile_role),
                    value = normalizedRole.uppercase(Locale.US)
                )
                ProfileVerificationRow(
                    label = stringResource(R.string.profile_verified),
                    isVerified = uiState.verified
                )
            }
        }
    }
}

@Composable
private fun NameEditorCard(
    name: String,
    onEdit: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile_username),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = name.ifBlank { stringResource(R.string.enter_name) },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.edit))
            }
        }
    }
}

@Composable
private fun AuthorizedInstitutionNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 2.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.profile_login_authorized_only_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.profile_login_authorized_only_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ProfileVerificationRow(
    label: String,
    isVerified: Boolean
) {
    val statusText = if (isVerified) {
        stringResource(R.string.profile_verified_yes)
    } else {
        stringResource(R.string.profile_verified_no)
    }
    val statusColor = if (isVerified) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val statusIcon = if (isVerified) {
        Icons.Filled.Verified
    } else {
        Icons.Filled.Close
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }
    }
}

/**
 * Account + data deletion, the path Google Play requires of any app that lets users create an
 * account (and the reason the store listing can stop saying "data can't be deleted").
 *
 * Type-to-confirm rather than a plain "are you sure": this erase cannot be undone and the button
 * sits on a screen a user may hand to a rescuer, so an accidental double-tap must not be enough.
 * The word is localised — a Turkish user should not have to type an English one.
 */
// internal rather than private so DeleteAccountSectionTest can drive the type-to-confirm gate
// directly. The gate is the only thing standing between a stray tap and an irreversible erase, so it
// is worth a test that does not need a signed-in account to reach it.
@Composable
internal fun DeleteAccountSection(
    isDeleting: Boolean,
    onConfirm: () -> Unit
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var typedConfirmation by rememberSaveable { mutableStateOf("") }
    val confirmationWord = stringResource(R.string.profile_delete_account_confirm_word)

    TextButton(
        onClick = {
            typedConfirmation = ""
            showDialog = true
        },
        enabled = !isDeleting,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        if (isDeleting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.profile_delete_account_progress))
        } else {
            Icon(
                imageVector = Icons.Outlined.DeleteForever,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.profile_delete_account_button))
        }
    }

    if (!showDialog) {
        return
    }

    AlertDialog(
        onDismissRequest = { showDialog = false },
        icon = {
            Icon(
                imageVector = Icons.Outlined.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(text = stringResource(R.string.profile_delete_account_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.profile_delete_account_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.profile_delete_account_dialog_confirm_hint,
                        confirmationWord
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = typedConfirmation,
                    onValueChange = { typedConfirmation = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showDialog = false
                    onConfirm()
                },
                enabled = typedConfirmation.trim().equals(confirmationWord, ignoreCase = true),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = stringResource(R.string.profile_delete_account_dialog_confirm),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ProfileTag(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        contentColor = tint,
        shape = CircleShape,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
