package com.auralis.crisisconnect.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auralis.crisisconnect.messaging.MessagingBootstrap
import com.auralis.crisisconnect.nearby.NearbyDiscoveryPreferences
import com.auralis.crisisconnect.screens.settings.ProfileViewModel
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.Country
import com.auralis.crisisconnect.ui.components.PhoneNumberInput
import com.auralis.crisisconnect.ui.components.SmsOtpAutoFillEffect
import com.auralis.crisisconnect.ui.components.composeE164
import com.auralis.crisisconnect.ui.components.defaultCountryIso
import com.auralis.crisisconnect.ui.components.fallbackCountry
import com.auralis.crisisconnect.ui.components.rememberCountryList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WELCOME_STEP_COUNT = 5
private const val WELCOME_STEP_INTRO = 0
private const val WELCOME_STEP_NAME = 1
private const val WELCOME_STEP_INTERNET = 2
private const val WELCOME_STEP_PERMISSIONS = 3
private const val WELCOME_STEP_PRIVACY = 4

/** UI-agnostic marker so [WelcomeReadinessItem] can carry a recognizable glyph per permission. */
internal enum class WelcomeReadinessIcon {
    NearbyLinks,
    Location,
    Notifications
}

/**
 * Vertical-density multiplier so the flow fits short/old screens without scrolling
 * while staying full-size on modern phones. Derived once from the available height
 * (see [rememberWelcomeScale]) and read by spacing/decoration sizes via [scaled].
 */
private val LocalWelcomeScale = staticCompositionLocalOf { 1f }

/** Reference content height of a typical modern phone; shorter screens scale down. */
private val WELCOME_REFERENCE_HEIGHT = 800.dp

private fun computeWelcomeScale(availableHeight: Dp): Float =
    (availableHeight / WELCOME_REFERENCE_HEIGHT).coerceIn(0.78f, 1f)

/** Scales a spacing/decoration dimension by the current vertical-density multiplier. */
@Composable
private fun scaled(value: Dp): Dp = value * LocalWelcomeScale.current

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun WelcomeScreen(
    fullName: String,
    onFullNameChange: (String) -> Unit,
    isTermsAccepted: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    readinessItems: List<WelcomeReadinessItem>,
    arePermissionsSatisfied: Boolean,
    permissionActionLabel: String?,
    completeActionLabel: String,
    blockerDetailText: String?,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onResolveBlocker: () -> Unit,
    onRequestPermission: (WelcomeReadinessItem) -> Unit,
    onComplete: () -> Unit
) {
    var currentStep by rememberSaveable { mutableIntStateOf(WELCOME_STEP_INTRO) }
    BackHandler(enabled = currentStep > WELCOME_STEP_INTRO) {
        currentStep -= 1
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // The picked photo is persisted immediately to the same store ProfileScreen
    // reads from, so it carries over once onboarding finishes.
    var profileBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        val existing = withContext(Dispatchers.IO) { ProfileImageStorage.loadProfileImage(context) }
        if (existing != null) {
            profileBitmap = existing
        }
    }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        ProfileImageStorage.decodeProfileImage(context, uri)?.also { decoded ->
                            ProfileImageStorage.saveProfileImage(context, decoded)
                        }
                    }.getOrNull()
                }
                if (bitmap != null) {
                    profileBitmap = bitmap
                }
            }
        }
    }

    // Internet step's phone verification is hoisted here so the single bottom action can
    // drive the whole flow (Skip → Send code → Verify → Continue) instead of a separate
    // in-form button. The step itself is presentational.
    val profileViewModel: ProfileViewModel = viewModel()
    val internetActivity = remember(context) { context.findWelcomeActivity() }
    val internetCountries = rememberCountryList()
    var internetSelectedIso by rememberSaveable { mutableStateOf(defaultCountryIso(context)) }
    var internetNationalNumber by rememberSaveable { mutableStateOf("") }
    var internetCode by rememberSaveable { mutableStateOf("") }
    var internetCodeSent by rememberSaveable { mutableStateOf(false) }
    var internetServerCodeSent by rememberSaveable { mutableStateOf(false) }
    var internetIsBusy by rememberSaveable { mutableStateOf(false) }
    var internetErrorText by rememberSaveable { mutableStateOf<String?>(null) }
    var internetVerifiedPhone by rememberSaveable { mutableStateOf<String?>(null) }
    val internetCountry = remember(internetSelectedIso, internetCountries) {
        internetCountries.firstOrNull { it.iso.equals(internetSelectedIso, ignoreCase = true) }
            ?: internetCountries.firstOrNull { it.iso == "US" }
            ?: internetCountries.firstOrNull()
            ?: fallbackCountry()
    }

    fun internetMarkVerified(number: String) {
        internetVerifiedPhone = number
        coroutineScope.launch {
            // Republish our identity key as discoverable by this phone number.
            runCatching { MessagingBootstrap.ensureRegistered(context, phone = number) }
            // Now that the user has a number, default "let nearby contacts find me" on (nearby
            // discovery only works for people who already have your number). Respects a prior
            // explicit choice. RfcommForegroundService hosts discovery and follows this
            // preference once it is up (after onboarding + Bluetooth permissions).
            runCatching { NearbyDiscoveryPreferences.enableByDefaultIfUnset(context) }
        }
    }

    fun internetSendCode() {
        val hostActivity = internetActivity
        if (hostActivity == null) {
            internetErrorText = context.getString(R.string.welcome_internet_phone_unavailable)
            return
        }
        internetIsBusy = true
        internetErrorText = null
        internetServerCodeSent = false
        val e164 = composeE164(internetCountry, internetNationalNumber)
        profileViewModel.startPhoneSignIn(
            activity = hostActivity,
            phoneNumber = e164,
            onServerCodeSent = { internetServerCodeSent = true },
            onCodeSent = { internetIsBusy = false; internetCodeSent = true },
            onResult = { success -> internetIsBusy = false; if (success) internetMarkVerified(e164) },
            onError = { message -> internetErrorText = message }
        )
    }

    fun internetVerifyCode() {
        internetIsBusy = true
        internetErrorText = null
        val e164 = composeE164(internetCountry, internetNationalNumber)
        profileViewModel.confirmPhoneSignInCode(
            code = internetCode.trim(),
            verifiedNumberE164 = e164,
            onResult = { success ->
                internetIsBusy = false
                if (success) {
                    internetMarkVerified(e164)
                } else if (internetErrorText == null) {
                    // onError delivers the real reason first; this is only the fallback.
                    internetErrorText = context.getString(R.string.welcome_internet_code_error)
                }
            },
            onError = { message -> internetErrorText = message }
        )
    }

    // The single primary action, shared by the bottom button and the keyboard IME action.
    fun performInternetPrimary() {
        when {
            internetVerifiedPhone != null -> currentStep += 1
            internetCodeSent -> internetVerifyCode()
            internetNationalNumber.isNotBlank() -> internetSendCode()
            else -> currentStep += 1
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            val isWide = maxWidth >= 700.dp
            val horizontalPadding = if (isWide) 40.dp else 24.dp
            val contentWidth = if (isWide) 560.dp else 480.dp
            // Scale vertical density to the available height so short/old screens fit
            // without scrolling, while modern phones keep the full-size spacing.
            val welcomeScale = computeWelcomeScale(maxHeight)

            CompositionLocalProvider(LocalWelcomeScale provides welcomeScale) {
            // The gradient above intentionally fills the whole window (behind the
            // status and navigation bars). Insets are applied here, per edge, so the
            // header clears the status bar / cutout and the action bar clears the
            // navigation bar and IME — without leaving the gradient flat-edged.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                WelcomeHeader(
                    currentStep = currentStep,
                    modifier = Modifier
                        .widthIn(max = contentWidth)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(top = scaled(14.dp), bottom = 4.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val offset = if (forward) 1 else -1
                            (fadeIn(tween(durationMillis = 220)) +
                                slideInHorizontally(tween(durationMillis = 260)) { full ->
                                    offset * full / 6
                                }) togetherWith
                                (fadeOut(tween(durationMillis = 150)) +
                                    slideOutHorizontally(tween(durationMillis = 200)) { full ->
                                        -offset * full / 6
                                    })
                        },
                        label = "welcome_step"
                    ) { step ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(scaled(8.dp)))
                            when (step) {
                                WELCOME_STEP_NAME -> WelcomeNameStep(
                                    fullName = fullName,
                                    onFullNameChange = onFullNameChange,
                                    profileBitmap = profileBitmap,
                                    onPickPhoto = { photoPicker.launch("image/*") },
                                    modifier = Modifier.widthIn(max = contentWidth)
                                )

                                WELCOME_STEP_INTERNET -> WelcomeInternetStep(
                                    country = internetCountry,
                                    onCountryChange = { internetSelectedIso = it.iso; internetErrorText = null },
                                    nationalNumber = internetNationalNumber,
                                    onNationalNumberChange = { internetNationalNumber = it; internetErrorText = null },
                                    countries = internetCountries,
                                    code = internetCode,
                                    onCodeChange = { internetCode = it; internetErrorText = null },
                                    codeSent = internetCodeSent,
                                    serverCodeSent = internetServerCodeSent,
                                    isBusy = internetIsBusy,
                                    errorText = internetErrorText,
                                    verifiedPhone = internetVerifiedPhone,
                                    onImeAction = { performInternetPrimary() },
                                    modifier = Modifier.widthIn(max = contentWidth)
                                )

                                WELCOME_STEP_PERMISSIONS -> WelcomePermissionsStep(
                                    readinessItems = readinessItems,
                                    blockerDetailText = blockerDetailText,
                                    onRequestPermission = onRequestPermission,
                                    modifier = Modifier.widthIn(max = contentWidth)
                                )

                                WELCOME_STEP_PRIVACY -> WelcomePrivacyStep(
                                    isTermsAccepted = isTermsAccepted,
                                    onTermsAcceptedChange = onTermsAcceptedChange,
                                    onOpenTerms = onOpenTerms,
                                    onOpenPrivacy = onOpenPrivacy,
                                    modifier = Modifier.widthIn(max = contentWidth)
                                )

                                else -> WelcomeIntroStep(
                                    modifier = Modifier.widthIn(max = contentWidth)
                                )
                            }
                            Spacer(modifier = Modifier.height(scaled(12.dp)))
                        }
                    }
                }

                WelcomeNavigation(
                    primaryActionLabel = when (currentStep) {
                        WELCOME_STEP_PRIVACY -> completeActionLabel
                        WELCOME_STEP_INTERNET -> when {
                            internetVerifiedPhone != null -> stringResource(R.string.welcome_internet_continue_button)
                            internetCodeSent -> stringResource(R.string.welcome_internet_verify_button)
                            // Typed content drives the bar, not focus: an auto-focused but
                            // still-empty field keeps Skip (and Back) available.
                            internetNationalNumber.isNotBlank() ->
                                stringResource(R.string.welcome_internet_send_code_button)
                            else -> stringResource(R.string.welcome_internet_skip_button)
                        }
                        WELCOME_STEP_PERMISSIONS -> if (arePermissionsSatisfied) {
                            stringResource(R.string.welcome_next_button)
                        } else {
                            permissionActionLabel ?: stringResource(R.string.welcome_next_button)
                        }
                        else -> stringResource(R.string.welcome_next_button)
                    },
                    isPrimaryActionEnabled = when (currentStep) {
                        WELCOME_STEP_NAME -> fullName.isNotBlank()
                        WELCOME_STEP_INTERNET -> !internetIsBusy &&
                            (internetVerifiedPhone != null || !internetCodeSent || internetCode.isNotBlank())
                        WELCOME_STEP_PERMISSIONS -> arePermissionsSatisfied || permissionActionLabel != null
                        WELCOME_STEP_PRIVACY -> isTermsAccepted && arePermissionsSatisfied
                        else -> true
                    },
                    isPrimaryActionLoading = currentStep == WELCOME_STEP_INTERNET && internetIsBusy,
                    // The back button steps out of the phone-entry commitment, so it hides once
                    // the user has typed a number or a code is in flight (until verified).
                    isBackVisible = currentStep > WELCOME_STEP_INTRO && !(
                        currentStep == WELCOME_STEP_INTERNET &&
                            internetVerifiedPhone == null &&
                            (internetNationalNumber.isNotBlank() || internetCodeSent)
                        ),
                    onBack = {
                        if (currentStep > WELCOME_STEP_INTRO) {
                            currentStep -= 1
                        }
                    },
                    onPrimaryAction = {
                        when (currentStep) {
                            WELCOME_STEP_PRIVACY -> onComplete()
                            WELCOME_STEP_INTERNET -> performInternetPrimary()
                            WELCOME_STEP_PERMISSIONS -> if (arePermissionsSatisfied) {
                                currentStep = WELCOME_STEP_PRIVACY
                            } else {
                                onResolveBlocker()
                            }
                            else -> currentStep += 1
                        }
                    },
                    modifier = Modifier
                        .widthIn(max = contentWidth)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(top = 8.dp, bottom = 12.dp)
                )
            }
            }
        }
    }
}

@Composable
private fun WelcomeHeader(
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(16.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The app logo is already a finished, self-contained icon, so wrapping
            // it in a tinted/elevated square made it look like an icon-inside-a-box
            // (a badge on a badge). Render the artwork directly for a cleaner header.
            Image(
                painter = painterResource(R.drawable.dcslogo),
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // The brand tagline only earns its place on the first screen; repeating
                // it on every step competes with each step's own heading.
                if (currentStep == WELCOME_STEP_INTRO) {
                    Text(
                        text = stringResource(R.string.welcome_brand_eyebrow),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        WelcomeStepIndicator(currentStep = currentStep)
    }
}

@Composable
private fun WelcomeStepIndicator(
    currentStep: Int
) {
    // The numeric "step X of Y" chip was removed for a cleaner header, so expose the
    // same information to screen readers here instead of losing it entirely.
    val progressDescription = stringResource(
        R.string.welcome_step_counter,
        currentStep + 1,
        WELCOME_STEP_COUNT
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = progressDescription },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(WELCOME_STEP_COUNT) { index ->
            val isCompletedOrCurrent = index <= currentStep
            val barColor by animateColorAsState(
                targetValue = if (isCompletedOrCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = tween(durationMillis = 280),
                label = "welcome_step_bar"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun WelcomeIntroStep(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(20.dp)),
        horizontalAlignment = Alignment.Start
    ) {
        WelcomeStepHeading(
            eyebrow = stringResource(R.string.welcome_intro_eyebrow),
            title = stringResource(R.string.welcome_headline),
            body = stringResource(R.string.welcome_body)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(scaled(10.dp))
        ) {
            WelcomeCapabilityCard(
                icon = Icons.Filled.People,
                title = stringResource(R.string.welcome_capability_nearby_title),
                description = stringResource(R.string.welcome_capability_nearby_description)
            )
            WelcomeCapabilityCard(
                icon = Icons.Filled.Link,
                title = stringResource(R.string.welcome_capability_offline_title),
                description = stringResource(R.string.welcome_capability_offline_description)
            )
            WelcomeCapabilityCard(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.welcome_capability_alerts_title),
                description = stringResource(R.string.welcome_capability_alerts_description)
            )
        }
    }
}

private tailrec fun Context.findWelcomeActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findWelcomeActivity()
    else -> null
}

/**
 * Onboarding page introducing the internet-messaging capability (low bandwidth, keeps the
 * cell network from collapsing, E2E-encrypted) with an optional phone verification. Purely
 * presentational: the phone state and the send/verify actions are hoisted into [WelcomeScreen]
 * so the single bottom button drives the whole flow. [onImeAction] triggers that same primary
 * action from the keyboard.
 */
@Composable
private fun WelcomeInternetStep(
    country: Country,
    onCountryChange: (Country) -> Unit,
    nationalNumber: String,
    onNationalNumberChange: (String) -> Unit,
    countries: List<Country>,
    code: String,
    onCodeChange: (String) -> Unit,
    codeSent: Boolean,
    serverCodeSent: Boolean,
    isBusy: Boolean,
    errorText: String?,
    verifiedPhone: String?,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    // User Consent is deliberately limited to the server/Twilio OTP path. Firebase Phone
    // Auth owns the same SMS_RETRIEVED_ACTION with a different payload shape; running both
    // listeners together can crash firebase-auth before the user can enter the code.
    SmsOtpAutoFillEffect(
        isListening = serverCodeSent && codeSent && verifiedPhone == null,
        onCodeReceived = onCodeChange
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(16.dp)),
        horizontalAlignment = Alignment.Start
    ) {
        WelcomeStepHeading(
            eyebrow = stringResource(R.string.welcome_internet_eyebrow),
            title = stringResource(R.string.welcome_internet_title),
            body = stringResource(R.string.welcome_internet_body)
        )
        // The three benefits repeat what the heading body already says, so collapsing
        // them into a single compact card (one icon row each, no restated descriptions)
        // keeps this step on one screen alongside the phone form — matching the density
        // of the privacy step's trust card.
        WelcomeInternetBenefitsCard()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.welcome_internet_phone_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.welcome_internet_phone_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (verifiedPhone != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.welcome_internet_phone_verified, verifiedPhone),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                PhoneNumberInput(
                    country = country,
                    onCountryChange = onCountryChange,
                    nationalNumber = nationalNumber,
                    onNationalNumberChange = onNationalNumberChange,
                    countries = countries,
                    enabled = !codeSent && !isBusy,
                    isError = errorText != null,
                    imeAction = ImeAction.Done,
                    onImeAction = onImeAction
                )
                if (codeSent) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCodeChange,
                        // ContentType.SmsOtpCode lets the OS/keyboard offer the incoming
                        // SMS code for one-tap auto-fill (Android 12+). Firebase's own
                        // auto-retrieval still completes verification hands-free where
                        // available; this covers the manual path.
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.SmsOtpCode },
                        label = { Text(stringResource(R.string.welcome_internet_code_label)) },
                        leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                        singleLine = true,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                errorText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Compact single-card summary of the three internet-messaging benefits (low data, eases
 * the cell network, E2E). Reuses [WelcomeTrustRow]'s icon-row density so the benefits sit
 * in one card instead of three, freeing the vertical room the phone form needs.
 */
@Composable
private fun WelcomeInternetBenefitsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(scaled(14.dp)),
            verticalArrangement = Arrangement.spacedBy(scaled(12.dp))
        ) {
            WelcomeTrustRow(
                icon = Icons.Filled.Public,
                text = stringResource(R.string.welcome_internet_capability_lowbw_title)
            )
            WelcomeTrustRow(
                icon = Icons.Filled.Shield,
                text = stringResource(R.string.welcome_internet_capability_basestation_title)
            )
            WelcomeTrustRow(
                icon = Icons.Filled.Lock,
                text = stringResource(R.string.welcome_internet_capability_e2e_title)
            )
        }
    }
}

@Composable
private fun WelcomeNameStep(
    fullName: String,
    onFullNameChange: (String) -> Unit,
    profileBitmap: Bitmap?,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(20.dp)),
        horizontalAlignment = Alignment.Start
    ) {
        WelcomeStepHeading(
            eyebrow = stringResource(R.string.welcome_name_eyebrow),
            title = stringResource(R.string.welcome_profile_title),
            body = stringResource(R.string.welcome_profile_description)
        )

        WelcomeAvatarPicker(
            fullName = fullName,
            profileBitmap = profileBitmap,
            onPickPhoto = onPickPhoto,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = fullName,
            onValueChange = onFullNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.full_name_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            text = stringResource(R.string.welcome_name_preview_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WelcomeAvatarPicker(
    fullName: String,
    profileBitmap: Bitmap?,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photoActionLabel = stringResource(
        if (profileBitmap == null) R.string.welcome_add_photo else R.string.welcome_change_photo
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(scaled(116.dp)),
            contentAlignment = Alignment.Center
        ) {
            // The whole avatar is the single labeled tap target; the camera dot below
            // is decorative so screen readers announce one clear "add/change photo".
            ContactAvatar(
                displayName = fullName,
                stableKey = "local_profile_avatar",
                bitmap = profileBitmap,
                modifier = Modifier
                    .size(scaled(104.dp))
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
                    .clip(CircleShape)
                    .clickable(onClick = onPickPhoto)
                    .semantics { contentDescription = photoActionLabel },
                textStyle = MaterialTheme.typography.headlineMedium
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        TextButton(onClick = onPickPhoto) {
            Text(
                text = stringResource(
                    if (profileBitmap == null) {
                        R.string.welcome_add_photo
                    } else {
                        R.string.welcome_change_photo
                    }
                )
            )
        }
    }
}

@Composable
private fun WelcomePermissionsStep(
    readinessItems: List<WelcomeReadinessItem>,
    blockerDetailText: String?,
    onRequestPermission: (WelcomeReadinessItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(14.dp)),
        horizontalAlignment = Alignment.Start
    ) {
        WelcomeStepHeading(
            eyebrow = stringResource(R.string.welcome_ready_eyebrow),
            title = stringResource(R.string.welcome_setup_title),
            body = stringResource(R.string.welcome_setup_body)
        )
        WelcomeReadinessSection(
            readinessItems = readinessItems,
            onRequestPermission = onRequestPermission
        )
        blockerDetailText?.let { detailText ->
            WelcomeWarningBanner(detailText = detailText)
        }
    }
}

@Composable
private fun WelcomePrivacyStep(
    isTermsAccepted: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(16.dp)),
        horizontalAlignment = Alignment.Start
    ) {
        WelcomeStepHeading(
            eyebrow = stringResource(R.string.welcome_privacy_eyebrow),
            title = stringResource(R.string.welcome_privacy_title),
            body = stringResource(R.string.welcome_privacy_body)
        )
        WelcomeTrustCard()
        LegalConsentRow(
            isTermsAccepted = isTermsAccepted,
            onTermsAcceptedChange = onTermsAcceptedChange,
            onOpenTerms = onOpenTerms,
            onOpenPrivacy = onOpenPrivacy
        )
    }
}

@Composable
private fun WelcomeTrustCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(scaled(16.dp)),
            verticalArrangement = Arrangement.spacedBy(scaled(12.dp))
        ) {
            Text(
                text = stringResource(R.string.welcome_trust_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            WelcomeTrustRow(
                icon = Icons.Filled.Code,
                text = stringResource(R.string.welcome_trust_open_source)
            )
            WelcomeTrustRow(
                icon = Icons.Filled.Lock,
                text = stringResource(R.string.welcome_trust_no_ads)
            )
            WelcomeTrustRow(
                icon = Icons.Filled.Shield,
                text = stringResource(R.string.welcome_trust_emergency)
            )
            Text(
                text = stringResource(R.string.welcome_trust_diagnostics_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WelcomeTrustRow(
    icon: ImageVector,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WelcomeStepHeading(
    eyebrow: String,
    title: String,
    body: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(scaled(8.dp))
    ) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WelcomeNavigation(
    primaryActionLabel: String,
    isPrimaryActionEnabled: Boolean,
    isBackVisible: Boolean,
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimaryActionLoading: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // When back is hidden the primary button takes the full width (no placeholder gap),
        // which reads as a focused, single call to action.
        if (isBackVisible) {
            TextButton(
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 96.dp),
                onClick = onBack,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.welcome_back_button))
            }
        }
        Button(
            onClick = onPrimaryAction,
            enabled = isPrimaryActionEnabled && !isPrimaryActionLoading,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 1.dp,
                pressedElevation = 0.dp
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (isPrimaryActionLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = primaryActionLabel,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun WelcomeCapabilityCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(scaled(14.dp)),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WelcomeIconBadge(
                icon = icon,
                shape = RoundedCornerShape(12.dp),
                size = scaled(44.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WelcomeIconBadge(
    icon: ImageVector,
    shape: androidx.compose.ui.graphics.Shape,
    size: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier.size(size),
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(10.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun LegalConsentRow(
    isTermsAccepted: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = isTermsAccepted,
                onCheckedChange = onTermsAcceptedChange
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.welcome_legal_acceptance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onOpenTerms,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.welcome_terms_link_label))
                    }
                    TextButton(
                        onClick = onOpenPrivacy,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.welcome_privacy_link_label))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeReadinessSection(
    readinessItems: List<WelcomeReadinessItem>,
    onRequestPermission: (WelcomeReadinessItem) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(scaled(8.dp))
    ) {
        readinessItems.forEach { item ->
            WelcomeReadinessRow(
                item = item,
                onRequestPermission = { onRequestPermission(item) }
            )
        }
    }
}

@Composable
private fun WelcomeReadinessRow(
    item: WelcomeReadinessItem,
    onRequestPermission: () -> Unit
) {
    val statusColor = welcomeStatusColor(isReady = item.isReady)
    val typeIcon = when (item.icon) {
        WelcomeReadinessIcon.NearbyLinks -> Icons.Filled.Bluetooth
        WelcomeReadinessIcon.Location -> Icons.Filled.LocationOn
        WelcomeReadinessIcon.Notifications -> Icons.Filled.Notifications
    }
    // A not-ready card is the tap target that triggers the system permission prompt;
    // once granted it is informational only (no ripple, no action).
    Surface(
        onClick = onRequestPermission,
        enabled = !item.isReady,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(scaled(14.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(scaled(42.dp)),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            WelcomeStatusPill(isReady = item.isReady, statusColor = statusColor)
        }
    }
}

@Composable
private fun WelcomeStatusPill(
    isReady: Boolean,
    statusColor: Color
) {
    Surface(
        shape = CircleShape,
        color = statusColor.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isReady) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = statusColor
            )
            Text(
                text = stringResource(
                    if (isReady) {
                        R.string.welcome_status_ready
                    } else {
                        R.string.welcome_status_action_needed
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = statusColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WelcomeWarningBanner(
    detailText: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * Ready / action-needed accent that stays legible in both light and dark themes.
 * Derived from the surface luminance so it tracks the *applied* theme even when the
 * user forces light/dark independently of the system setting.
 */
@Composable
private fun welcomeStatusColor(isReady: Boolean): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when {
        isReady && isDark -> Color(0xFF6FD08C)
        isReady -> Color(0xFF2E7D32)
        isDark -> Color(0xFFFF8A80)
        else -> Color(0xFFB71C1C)
    }
}
