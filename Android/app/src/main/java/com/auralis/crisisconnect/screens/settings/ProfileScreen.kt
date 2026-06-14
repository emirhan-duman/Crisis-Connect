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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.auralis.crisisconnect.screens.SettingsViewModel
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.collect
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
    var phoneLoginDialogOpen by rememberSaveable { mutableStateOf(false) }
    var phoneInput by rememberSaveable { mutableStateOf("") }
    var phoneCodeInput by rememberSaveable { mutableStateOf("") }
    var phoneCodeSent by rememberSaveable { mutableStateOf(false) }
    var phoneLoginValidationRequested by rememberSaveable { mutableStateOf(false) }
    val emailLoginSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val phoneLoginSheetState = rememberModalBottomSheetState(
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

    LaunchedEffect(phoneLoginDialogOpen) {
        if (phoneLoginDialogOpen) {
            phoneInput = uiState.phoneNumber
            phoneCodeInput = ""
            phoneCodeSent = false
            phoneLoginValidationRequested = false
        }
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
                    onChangePhoto = { imagePicker.launch("image/*") }
                )
                ProfileInfoCard(uiState = uiState)
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
                    ProfileCertificateCard(viewModel = profileViewModel)
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
                        onPhoneClick = { phoneLoginDialogOpen = true },
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

    if (phoneLoginDialogOpen) {
        val focusManager = LocalFocusManager.current
        val normalizedPhone = phoneInput.trim()
        val normalizedCode = phoneCodeInput.trim()
        val isPhoneValid = normalizedPhone.startsWith("+") &&
            normalizedPhone.length in 8..16 &&
            normalizedPhone.drop(1).all { it.isDigit() }
        val isCodeValid = normalizedCode.length >= 4
        val showPhoneError = phoneLoginValidationRequested && !isPhoneValid
        val showCodeError = phoneLoginValidationRequested && phoneCodeSent && !isCodeValid

        fun closePhoneSheet() {
            phoneLoginDialogOpen = false
            phoneInput = ""
            phoneCodeInput = ""
            phoneCodeSent = false
            phoneLoginValidationRequested = false
        }

        fun submitPhoneLogin() {
            phoneLoginValidationRequested = true
            if (uiState.isLoading) return
            val hostActivity = activity
            if (hostActivity == null) {
                profileViewModel.onAuthProviderUnavailable()
                return
            }
            if (!phoneCodeSent) {
                if (!isPhoneValid) return
                profileViewModel.startPhoneSignIn(
                    activity = hostActivity,
                    phoneNumber = normalizedPhone,
                    onCodeSent = {
                        phoneCodeSent = true
                        phoneLoginValidationRequested = false
                    },
                    onResult = { success ->
                        if (success) closePhoneSheet()
                    }
                )
                return
            }

            if (!isCodeValid) return
            profileViewModel.confirmPhoneSignInCode(normalizedCode) { success ->
                if (success) closePhoneSheet()
            }
        }

        ModalBottomSheet(
            onDismissRequest = {
                if (!uiState.isLoading) {
                    focusManager.clearFocus(force = true)
                    closePhoneSheet()
                }
            },
            sheetState = phoneLoginSheetState,
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
                            imageVector = Icons.Filled.Phone,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.profile_phone_login_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.profile_phone_login_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = {
                        phoneInput = it
                        if (!phoneCodeSent) {
                            phoneLoginValidationRequested = false
                        }
                    },
                    enabled = !phoneCodeSent && !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_phone_number_label)) },
                    isError = showPhoneError,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Phone,
                            contentDescription = null
                        )
                    },
                    supportingText = {
                        if (showPhoneError) {
                            Text(text = stringResource(R.string.profile_phone_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = if (phoneCodeSent) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            submitPhoneLogin()
                        }
                    ),
                    shape = MaterialTheme.shapes.large
                )

                if (phoneCodeSent) {
                    OutlinedTextField(
                        value = phoneCodeInput,
                        onValueChange = {
                            phoneCodeInput = it.filter(Char::isDigit).take(8)
                            phoneLoginValidationRequested = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.profile_phone_code_label)) },
                        isError = showCodeError,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null
                            )
                        },
                        supportingText = {
                            if (showCodeError) {
                                Text(text = stringResource(R.string.profile_phone_code_invalid))
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            submitPhoneLogin()
                        }),
                        shape = MaterialTheme.shapes.large
                    )
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        submitPhoneLogin()
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
                        text = stringResource(
                            if (phoneCodeSent) {
                                R.string.profile_phone_verify_code_action
                            } else {
                                R.string.profile_phone_send_code_action
                            }
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        closePhoneSheet()
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
    onPhoneClick: () -> Unit,
    onEnterpriseClick: () -> Unit,
    onEmailClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProviderSignInButton(
            text = stringResource(R.string.profile_login_button),
            brand = AuthProviderBrand.GOOGLE,
            enabled = enabled,
            onClick = onGoogleClick
        )
        ProviderSignInButton(
            text = stringResource(R.string.profile_login_microsoft_button),
            brand = AuthProviderBrand.MICROSOFT,
            enabled = enabled,
            onClick = onMicrosoftClick
        )
        ProviderSignInButton(
            text = stringResource(R.string.profile_login_apple_button),
            brand = AuthProviderBrand.APPLE,
            enabled = enabled,
            onClick = onAppleClick
        )
        ProviderSignInButton(
            text = stringResource(R.string.profile_login_phone_button),
            brand = AuthProviderBrand.PHONE,
            enabled = enabled,
            onClick = onPhoneClick
        )
        ProviderSignInButton(
            text = stringResource(R.string.profile_login_enterprise_sso_button),
            brand = AuthProviderBrand.ENTERPRISE,
            enabled = enabled,
            onClick = onEnterpriseClick
        )
        ProviderSignInButton(
            text = stringResource(R.string.profile_login_email_button),
            brand = AuthProviderBrand.EMAIL,
            enabled = enabled,
            onClick = onEmailClick
        )
    }
}

@Composable
private fun ProviderSignInButton(
    text: String,
    brand: AuthProviderBrand,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp).copy(alpha = 0.48f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderBrandBadge(brand = brand)
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.width(34.dp))
        }
    }
}

@Composable
private fun ProviderBrandBadge(brand: AuthProviderBrand) {
    val isBrandMark = brand == AuthProviderBrand.GOOGLE ||
        brand == AuthProviderBrand.MICROSOFT ||
        brand == AuthProviderBrand.APPLE
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = if (isBrandMark) {
            Color.White
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (brand) {
                AuthProviderBrand.GOOGLE -> Image(
                    painter = painterResource(R.drawable.ic_brand_google),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                AuthProviderBrand.MICROSOFT -> Image(
                    painter = painterResource(R.drawable.ic_brand_microsoft),
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                AuthProviderBrand.APPLE -> Image(
                    painter = painterResource(R.drawable.ic_brand_apple),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                AuthProviderBrand.PHONE -> Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                AuthProviderBrand.ENTERPRISE -> Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                AuthProviderBrand.EMAIL -> Icon(
                    imageVector = Icons.Outlined.Email,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    state: ProfileUiState,
    localDisplayName: String,
    onChangePhoto: () -> Unit
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
                val avatarDisplayName = localDisplayName.ifBlank {
                    state.username.ifBlank {
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

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.profile_login_authorized_only_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.profile_login_authorized_only_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
