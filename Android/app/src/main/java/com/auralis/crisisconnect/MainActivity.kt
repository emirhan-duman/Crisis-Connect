package com.auralis.crisisconnect

import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.screens.MainScreen
import com.auralis.crisisconnect.screens.AddFromContactsScreen
import com.auralis.crisisconnect.screens.NewChatScreen
import com.auralis.crisisconnect.screens.authority.AuthorityChannelsScreen
import com.auralis.crisisconnect.screens.authority.AuthorityChannelThreadScreen
import com.auralis.crisisconnect.screens.authority.AuthorityContactPickerScreen
import com.auralis.crisisconnect.screens.Guide.GuideMainScreen
import com.auralis.crisisconnect.screens.ToolsMainScreen
import com.auralis.crisisconnect.screens.SettingsScreen
import com.auralis.crisisconnect.screens.AdvancedSettingsScreen
import com.auralis.crisisconnect.screens.settings.ChildProfileScreen
import com.auralis.crisisconnect.screens.settings.ProfileScreen
import com.auralis.crisisconnect.screens.settings.SosEmergencyContactsScreen
import com.auralis.crisisconnect.screens.Tools.CrisisSentinelSwipeBackCoordinator
import com.auralis.crisisconnect.screens.Tools.MetalDetectorScreen
import com.auralis.crisisconnect.screens.Tools.SignalFinderScreen
import com.auralis.crisisconnect.screens.Tools.CompassScreen
import com.auralis.crisisconnect.screens.Tools.CrisisSentinelChatScreen
import com.auralis.crisisconnect.screens.Tools.CrisisSentinelHomeScreen
import com.auralis.crisisconnect.screens.Tools.CrisisSentinelScreen
import com.auralis.crisisconnect.screens.Tools.CrisisSentinelSettingsScreen
import com.auralis.crisisconnect.screens.Tools.OfflineMapScreen
import com.auralis.crisisconnect.screens.Tools.RecentDisastersScreen
import com.auralis.crisisconnect.screens.Tools.SensorToolScreen
import com.auralis.crisisconnect.screens.Tools.WhistleScreen
import com.auralis.crisisconnect.screens.Chat.ChatScreen
import com.auralis.crisisconnect.screens.Chat.ChatInfoScreen
import com.auralis.crisisconnect.screens.Chat.BleChatScreen
import com.auralis.crisisconnect.screens.Chat.GattMeshScreen
import com.auralis.crisisconnect.screens.SOSScreen
import com.auralis.crisisconnect.screens.SosCountdownScreen
import com.auralis.crisisconnect.ui.cert.CertificateProvisioningBanner

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.auralis.crisisconnect.messaging.call.InternetCallForegroundService
import com.auralis.crisisconnect.messaging.call.InternetCallManager
import com.auralis.crisisconnect.screens.Chat.InternetCallOverlay
import com.auralis.crisisconnect.screens.Chat.InternetCallOverlayHost
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.feature.RescueFeatureRedirectScreen
import com.auralis.crisisconnect.navigation.navigateBottomBar
import com.auralis.crisisconnect.ui.components.NavbarSettingsCache
import kotlinx.coroutines.flow.map
import com.auralis.crisisconnect.navigation.resolveConversationRoute
import com.auralis.crisisconnect.screens.Chat.CallOverlay
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme
import com.auralis.crisisconnect.screens.Chat.ChatSessionCodeScreen
import com.auralis.crisisconnect.screens.QR.ChatEncryptionSetupScreen
import com.auralis.crisisconnect.screens.QR.QrScannerScreen
import com.auralis.crisisconnect.screens.Chat.resolveChatDisplayName
import com.auralis.crisisconnect.data.database.DatabaseInitializer
import com.auralis.crisisconnect.security.EnterpriseSsoBridge
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.google.firebase.FirebaseApp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ONBOARDING_TERMS_ACCEPTED_KEY = booleanPreferencesKey("terms_v2_accepted")

class MainActivity : ComponentActivity() {
    private val navigationEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    private var rfcommServiceStarted = false
    private var isAppReady = false
    private var incomingCallWakeWindowApplied = false
    private val rescueFeatureManager by lazy { RescueFeatureManager(applicationContext) }
    private val rescueFeatureInstallListener = SplitInstallStateUpdatedListener { state ->
        if (!state.moduleNames().contains(RescueFeatureManager.MODULE_NAME)) {
            return@SplitInstallStateUpdatedListener
        }
        if (state.status() == SplitInstallSessionStatus.INSTALLED) {
            onRescueFeatureInstalled()
        }
    }

    private val criticalPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllPermissions(requiredPermissionsForServiceStart())) {
                startRfcommForegroundServiceIfNeeded()
            } else {
                Log.w(TAG, "Critical permissions denied; RFCOMM service was not started.")
            }
        }

    override fun attachBaseContext(newBase: Context) {
        val savedTheme = runCatching { getSavedThemeOptionSync(newBase) }
            .getOrDefault(ThemeOption.SYSTEM)
        val baseUiMode = newBase.resources.configuration.uiMode
        val systemInNight =
            (baseUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val desiredInNight = when (savedTheme) {
            ThemeOption.DARK -> true
            ThemeOption.LIGHT -> false
            ThemeOption.SYSTEM -> systemInNight
        }
        val overrideConfig = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (desiredInNight) Configuration.UI_MODE_NIGHT_YES
                else Configuration.UI_MODE_NIGHT_NO
        }
        super.attachBaseContext(newBase.createConfigurationContext(overrideConfig))
    }

    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val startupThemeOption = getSavedThemeOptionSync(this)
        val startupLanguageCode = getSavedLanguageSync(this)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isAppReady }
        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            splashViewProvider.view.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    splashViewProvider.remove()
                    // The splash screen window can reset the system bar appearance
                    // when it tears down; re-apply once it is gone so the icons match
                    // the active theme on cold start.
                    configureSystemBars(getSavedThemeOptionSync(this@MainActivity))
                }
                .start()
        }
        super.onCreate(savedInstanceState)
        configureSystemBars(startupThemeOption)
        setLocale(this, startupLanguageCode, shouldRecreate = false)
        applyOrientationPolicyForDeviceClass()
        applyIncomingCallWakeWindowPolicy(intent)
        answerInternetCallIfRequested(intent)
        handleSsoDeepLink(intent)
        rescueFeatureManager.registerListener(rescueFeatureInstallListener)
        FirebaseApp.initializeApp(this)
        lifecycleScope.launch {
            settingsDataStore.data.map { prefs ->
                prefs[booleanPreferencesKey("rescue_show_in_navbar")] ?: false
            }.collect { show ->
                NavbarSettingsCache.showRescueInNavbar = show
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val role = SecurityRepository(applicationContext)
                .getUsableStoredCertificateRole(allowExpired = true)
                ?.trim()
                ?.lowercase(Locale.US)
            NavbarSettingsCache.hasRescueAccess = role == "admin" || role == "fieldteam"
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                DatabaseInitializer().initializeDatabase(applicationContext)
            }.onFailure { throwable ->
                Log.w(TAG, "Database bootstrap failed", throwable)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // Make this device reachable over internet messaging (publish identity key + FCM
            // token). Signs in anonymously first when there is no account, so QR-added
            // contacts work online without an explicit login.
            runCatching {
                com.auralis.crisisconnect.messaging.MessagingBootstrap.ensureRegistered(applicationContext)
            }.onFailure { throwable ->
                Log.w(TAG, "Messaging bootstrap failed", throwable)
            }
        }
        // Nearby discovery no longer needs a kick here: RfcommForegroundService (started below)
        // hosts it and follows the opt-in preference on its own.
        val contentInitialized = runCatching {
            setUpComposeContent(
                onInitialUiReady = {
                    isAppReady = true
                }
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Primary compose bootstrap failed", throwable)
        }.isSuccess || runCatching {
            setFallbackComposeContent(
                onInitialUiReady = {
                    isAppReady = true
                }
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Fallback compose bootstrap failed", throwable)
        }.isSuccess

        if (!contentInitialized) {
            Log.e(TAG, "Compose content could not be initialized; releasing splash to avoid lock.")
            isAppReady = true
        }

        bootstrapCrisisLinkServiceIfEnabled()
        bootstrapGattMeshServiceIfEnabled()
        bootstrapAuthorityMeshServiceIfEnabled()
        bootstrapMeshServiceIfAlwaysOn()
        startRfcommForegroundServiceAfterOnboarding()

        lifecycleScope.launch {
            // Rethrow cancellation: swallowing it here made a dying activity fall back to "en"
            // and apply it, which is how installs used to get mislabeled as English.
            val languageCode = runCatching {
                withContext(Dispatchers.IO) {
                    getSavedLanguage(this@MainActivity).first()
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Log.w(TAG, "Unable to load saved language; falling back to default", throwable)
            }.getOrDefault(getSavedLanguageSync(this@MainActivity))

            val themeOption = runCatching {
                withContext(Dispatchers.IO) {
                    getSavedThemeOption(this@MainActivity).first()
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Log.w(TAG, "Unable to load saved theme; falling back to cached option", throwable)
            }.getOrDefault(getSavedThemeOptionSync(this@MainActivity))

            runCatching {
                setLocale(this@MainActivity, languageCode, shouldRecreate = false)
            }.onFailure { throwable ->
                Log.w(TAG, "Locale bootstrap failed", throwable)
            }

            runCatching {
                configureSystemBars(themeOption)
            }.onFailure { throwable ->
                Log.w(TAG, "System bar bootstrap failed", throwable)
            }
        }
    }

    private fun configureSystemBars(themeOption: ThemeOption) {
        val useDarkSystemBarIcons = when (themeOption) {
            ThemeOption.DARK -> false
            ThemeOption.LIGHT -> true
            ThemeOption.SYSTEM -> !isSystemNightMode()
        }
        enableEdgeToEdge(
            statusBarStyle = if (useDarkSystemBarIcons) {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            } else {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            },
            navigationBarStyle = if (useDarkSystemBarIcons) {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            } else {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            }
        )
    }

    private fun applyOrientationPolicyForDeviceClass() {
        val isLargeScreenDevice = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isLargeScreenDevice) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setFallbackComposeContent(onInitialUiReady: () -> Unit) {
        val fallbackThemeOption = getSavedThemeOptionSync(this)
        setContent {
            LaunchedEffect(Unit) {
                withFrameNanos { onInitialUiReady() }
            }
            DisasterCommunicationSystemTheme(
                darkTheme = fallbackThemeOption.resolveDarkTheme(isSystemNightMode()),
                dynamicColor = fallbackThemeOption == ThemeOption.SYSTEM
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                    color = MaterialTheme.colorScheme.background
                ) {}
            }
        }
    }

    private fun isSystemNightMode(): Boolean {
        return (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun ThemeOption.resolveDarkTheme(systemInDarkTheme: Boolean): Boolean {
        return when (this) {
            ThemeOption.DARK -> true
            ThemeOption.LIGHT -> false
            ThemeOption.SYSTEM -> systemInDarkTheme
        }
    }

    @OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
    private fun setUpComposeContent(onInitialUiReady: () -> Unit) {
        val initialThemeOption = getSavedThemeOptionSync(this)
        setContent {
            LaunchedEffect(Unit) {
                withFrameNanos { onInitialUiReady() }
            }
            val themeOption by getSavedThemeOption(this@MainActivity)
                .collectAsStateWithLifecycle(initialValue = initialThemeOption)
            LaunchedEffect(themeOption) {
                configureSystemBars(themeOption)
            }
            val darkTheme = themeOption.resolveDarkTheme(isSystemInDarkTheme())
            val dynamicColor = themeOption == ThemeOption.SYSTEM

            DisasterCommunicationSystemTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    DisposableEffect(navController) {
                        // Route patterns ("chat/{sessionCode}") carry no argument values, so the
                        // screen_view stream never leaks session codes or peer identifiers.
                        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                            destination.route?.let { Analytics.screenView(it) }
                        }
                        navController.addOnDestinationChangedListener(listener)
                        onDispose { navController.removeOnDestinationChangedListener(listener) }
                    }
                    var hasResolvedInitialDestination by remember { mutableStateOf(false) }

                    LaunchedEffect(currentBackStackEntry) {
                        if (currentBackStackEntry != null) {
                            hasResolvedInitialDestination = true
                        } else if (hasResolvedInitialDestination && !isFinishing) {
                            // Prevent leaving the activity on a blank surface when the nav stack
                            // gets emptied by rapid back presses.
                            finish()
                        }
                    }

                    BackHandler(enabled = hasResolvedInitialDestination) {
                        if (!navController.popBackStack() && !isFinishing) {
                            finish()
                        }
                    }

                    LaunchedEffect(navController) {
                        suspend fun navigateFromIntent(navIntent: Intent) {
                            if (!hasTrustedLaunchToken(navIntent)) {
                                if (hasPrivilegedLaunchExtras(navIntent)) {
                                    Log.w(TAG, "Ignoring untrusted navigation extras on exported MainActivity")
                                }
                                clearPrivilegedLaunchExtras(navIntent)
                                return
                            }
                            val route = navIntent.getStringExtra(EXTRA_NAVIGATE_TO_ROUTE)
                            if (!route.isNullOrBlank()) {
                                when {
                                    route == "main" ||
                                        route == "tools_main" ||
                                        route == "guide_main" -> {
                                        navController.navigateBottomBar(route)
                                    }

                                    // Authority-messages deep-link from a channel push notification.
                                    route == "authority_channels" ||
                                        route.startsWith("authority_channel/") -> {
                                        navController.navigate(route) { launchSingleTop = true }
                                    }

                                    // SOS quick-access widget tap → countdown screen (the countdown
                                    // itself is the accidental-tap guard, so we never skip it).
                                    route == "sos_countdown" -> {
                                        navController.navigate(route) { launchSingleTop = true }
                                    }

                                    // Disasters widget tap → Recent Disasters screen.
                                    route == "recent_disasters" -> {
                                        navController.navigate(route) { launchSingleTop = true }
                                    }

                                    // Live SOS notification tap → the running broadcast's status.
                                    route == "sos_status" -> {
                                        navController.navigate(route) { launchSingleTop = true }
                                    }

                                    else -> {
                                        Log.w(TAG, "Ignoring unsupported route extra: $route")
                                    }
                                }
                            }
                            val session = navIntent.getStringExtra(EXTRA_NAVIGATE_TO_SESSION)
                            if (!session.isNullOrBlank()) {
                                val destination = resolveConversationRoute(
                                    context = this@MainActivity.applicationContext,
                                    sessionCode = session
                                )
                                navController.navigate(destination) {
                                    launchSingleTop = true
                                }
                            }
                            clearPrivilegedLaunchExtras(navIntent)
                        }
                        intent?.let { navigateFromIntent(it) }
                        navigationEvents.collect { navigateFromIntent(it) }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        SharedTransitionLayout {
                            val sharedTransitionScope = this
                            NavHost(
                                navController = navController,
                                startDestination = "main",
                                enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None },
                                popEnterTransition = { EnterTransition.None },
                                popExitTransition = { ExitTransition.None }
                            ) {
                                composable(
                                    route = "main",
                                    exitTransition = {
                                        if (targetState.destination.route.isChatRoute()) {
                                            chatForwardExitTransition()
                                        } else {
                                            ExitTransition.None
                                        }
                                    },
                                    popEnterTransition = {
                                        if (initialState.destination.route.isChatRoute()) {
                                            chatPopEnterTransition()
                                        } else {
                                            EnterTransition.None
                                        }
                                    }
                                ) {
                                    MainScreen(
                                        navController = navController,
                                        sharedTransitionScope = if (ENABLE_CHAT_SHARED_ELEMENTS) {
                                            sharedTransitionScope
                                        } else {
                                            null
                                        },
                                        animatedVisibilityScope = if (ENABLE_CHAT_SHARED_ELEMENTS) {
                                            this
                                        } else {
                                            null
                                        },
                                        onOnboardingCompleted = ::ensureCriticalPermissionsAndStartService
                                    )
                                }
                                composable("sos_countdown") { SosCountdownScreen(navController) }
                                composable("sos_emergency_contacts") {
                                    SosEmergencyContactsScreen(navController)
                                }
                                composable("sos_status") { SOSScreen(navController) }
                                composable("new_chat") { NewChatScreen(navController) }
                                composable("add_from_contacts") { AddFromContactsScreen(navController) }
                                composable("authority_channels") { AuthorityChannelsScreen(navController) }
                                composable("authority_contact_picker") {
                                    AuthorityContactPickerScreen(navController)
                                }
                                composable(
                                    route = "authority_channel/{channelId}/{peerUid}?title={title}&agency={agency}&role={role}",
                                    arguments = listOf(
                                        navArgument("channelId") { type = NavType.StringType },
                                        navArgument("peerUid") { type = NavType.StringType },
                                        navArgument("title") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        },
                                        navArgument("agency") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        },
                                        navArgument("role") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        },
                                    ),
                                ) { backStackEntry ->
                                    AuthorityChannelThreadScreen(
                                        navController = navController,
                                        channelId = backStackEntry.arguments?.getString("channelId")
                                            ?.let(Uri::decode) ?: "",
                                        peerUid = backStackEntry.arguments?.getString("peerUid")
                                            ?.let(Uri::decode) ?: "",
                                        title = backStackEntry.arguments?.getString("title")
                                            ?.let(Uri::decode) ?: "",
                                        agency = backStackEntry.arguments?.getString("agency")
                                            ?.let(Uri::decode) ?: "",
                                        role = backStackEntry.arguments?.getString("role")
                                            ?.let(Uri::decode) ?: "",
                                    )
                                }
                                composable("qr_scan") { QrScannerScreen(navController) }
                                composable("settings") { SettingsScreen(navController) }
                                composable("advanced_settings") { AdvancedSettingsScreen(navController) }
                                composable("child_profile_settings") { ChildProfileScreen(navController) }
                                composable("profile") { ProfileScreen(navController) }
                                composable(
                                    route = "chat/{sessionCode}?displayName={displayName}",
                                    arguments = listOf(
                                        navArgument("displayName") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        }
                                    ),
                                    enterTransition = { chatEnterTransition() },
                                    exitTransition = { chatForwardExitTransition() },
                                    popEnterTransition = { chatPopEnterTransition() },
                                    popExitTransition = { chatPopExitTransition() }
                                ) { backStackEntry ->
                                    val sessionCode = backStackEntry.arguments
                                        ?.getString("sessionCode")
                                        ?.let(Uri::decode)
                                        ?: ""
                                    val preferredDisplayName = backStackEntry.arguments
                                        ?.getString("displayName")
                                        ?.let(Uri::decode)
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                    ChatScreen(
                                        navController = navController,
                                        sessionCode = sessionCode,
                                        preferredDisplayName = preferredDisplayName,
                                        sharedTransitionScope = if (ENABLE_CHAT_SHARED_ELEMENTS) {
                                            sharedTransitionScope
                                        } else {
                                            null
                                        },
                                        animatedVisibilityScope = if (ENABLE_CHAT_SHARED_ELEMENTS) {
                                            this
                                        } else {
                                            null
                                        }
                                    )
                                }
                                composable(
                                    route = "ble_chat/{sessionCode}?displayName={displayName}",
                                    arguments = listOf(
                                        navArgument("displayName") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        }
                                    ),
                                    enterTransition = { chatEnterTransition() },
                                    exitTransition = { chatForwardExitTransition() },
                                    popEnterTransition = { chatPopEnterTransition() },
                                    popExitTransition = { chatPopExitTransition() }
                                ) { backStackEntry ->
                                    val sessionCode = backStackEntry.arguments
                                        ?.getString("sessionCode")
                                        ?.let(Uri::decode)
                                        ?: ""
                                    val preferredDisplayName = backStackEntry.arguments
                                        ?.getString("displayName")
                                        ?.let(Uri::decode)
                                        ?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                    BleChatScreen(
                                        navController = navController,
                                        sessionCode = sessionCode,
                                        preferredDisplayName = preferredDisplayName,
                                        sharedTransitionScope = if (ENABLE_CHAT_SHARED_ELEMENTS) {
                                            sharedTransitionScope
                                        } else {
                                            null
                                        },
                                        animatedVisibilityScope = if (ENABLE_CHAT_SHARED_ELEMENTS) {
                                            this
                                        } else {
                                            null
                                        }
                                    )
                                }
                                composable(
                                    route = "gatt_mesh_chat",
                                    enterTransition = { chatEnterTransition() },
                                    exitTransition = { chatForwardExitTransition() },
                                    popEnterTransition = { chatPopEnterTransition() },
                                    popExitTransition = { chatPopExitTransition() }
                                ) {
                                    GattMeshScreen(navController = navController)
                                }
                                composable(
                                    route = "mesh_chat",
                                    enterTransition = { chatEnterTransition() },
                                    exitTransition = { chatForwardExitTransition() },
                                    popEnterTransition = { chatPopEnterTransition() },
                                    popExitTransition = { chatPopExitTransition() }
                                ) {
                                    RescueFeatureRedirectScreen(
                                        navController = navController,
                                        startDestination = RescueFeatureManager.START_DESTINATION_MESH_CHAT
                                    )
                                }
                                composable("chat/{sessionCode}/details") { backStackEntry ->
                                    val sessionCode = backStackEntry.arguments
                                        ?.getString("sessionCode")
                                        ?.let(Uri::decode)
                                        ?: ""
                                    ChatInfoScreen(navController, sessionCode)
                                }
                                composable("chat/{sessionCode}/secure_setup") { backStackEntry ->
                                    val sessionCode = backStackEntry.arguments
                                        ?.getString("sessionCode")
                                        ?.let(Uri::decode)
                                        ?: ""
                                    ChatEncryptionSetupScreen(navController, sessionCode)
                                }
                                composable("chat/{sessionCode}/handshake_code") { backStackEntry ->
                                    val sessionCode = backStackEntry.arguments
                                        ?.getString("sessionCode")
                                        ?.let(Uri::decode)
                                        ?: ""
                                    ChatSessionCodeScreen(navController, sessionCode)
                                }
                                composable("guide_main") {
                                    GuideMainScreen(navController = navController)
                                }
                                composable("tools_main") { ToolsMainScreen(navController) }
                                composable("crisis_sentinel") { CrisisSentinelScreen(navController) }
                                composable(
                                    route = "crisis_sentinel_home",
                                    exitTransition = {
                                        if (targetState.destination.route.isChatRoute()) {
                                            chatForwardExitTransition()
                                        } else {
                                            ExitTransition.None
                                        }
                                    },
                                    popEnterTransition = {
                                        if (initialState.destination.route.isChatRoute() &&
                                            !CrisisSentinelSwipeBackCoordinator.isArmed()
                                        ) {
                                            chatPopEnterTransition()
                                        } else {
                                            EnterTransition.None
                                        }
                                    }
                                ) { CrisisSentinelHomeScreen(navController) }
                                composable("crisis_sentinel_settings") { CrisisSentinelSettingsScreen(navController) }
                                 composable(
                                     route = "crisis_sentinel_chat/{conversationId}?initialPrompt={initialPrompt}",
                                     arguments = listOf(
                                         navArgument("conversationId") { type = NavType.StringType },
                                         navArgument("initialPrompt") { type = NavType.StringType; nullable = true; defaultValue = null }
                                     ),
                                     enterTransition = { chatEnterTransition() },
                                     exitTransition = { chatForwardExitTransition() },
                                     popEnterTransition = { chatPopEnterTransition() },
                                     popExitTransition = {
                                         if (CrisisSentinelSwipeBackCoordinator.isArmed()) {
                                             ExitTransition.None
                                         } else {
                                             chatPopExitTransition()
                                         }
                                     }
                                 ) { backStackEntry ->
                                     val conversationId = backStackEntry.arguments
                                         ?.getString("conversationId")
                                         ?.let(Uri::decode)
                                         ?: ""
                                     val initialPrompt = backStackEntry.arguments
                                         ?.getString("initialPrompt")
                                         ?.let(Uri::decode)
                                     CrisisSentinelChatScreen(
                                         navController = navController,
                                         conversationId = conversationId,
                                         initialPrompt = initialPrompt
                                     )
                                 }
                                composable("metal_detector") { MetalDetectorScreen(navController) }
                                composable("signal_finder") { SignalFinderScreen(navController) }
                                composable("whistle") { WhistleScreen(navController) }
                                 composable(
                                     route = "offline_map?lat={lat}&lng={lng}&label={label}&points={points}",
                                     arguments = listOf(
                                         navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                                         navArgument("lng") { type = NavType.StringType; nullable = true; defaultValue = null },
                                         navArgument("label") { type = NavType.StringType; nullable = true; defaultValue = null },
                                         navArgument("points") { type = NavType.StringType; nullable = true; defaultValue = null }
                                     )
                                 ) { backStackEntry ->
                                     val lat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull()
                                     val lng = backStackEntry.arguments?.getString("lng")?.toDoubleOrNull()
                                     val label = backStackEntry.arguments?.getString("label")
                                     val pointsJson = backStackEntry.arguments?.getString("points")
                                     OfflineMapScreen(
                                         navController,
                                         initialLat = lat,
                                         initialLng = lng,
                                         initialLabel = label,
                                         pointsJson = pointsJson
                                     )
                                 }
                                composable("compass") { CompassScreen(navController) }
                                composable("sensor_tool") { SensorToolScreen(navController) }
                                composable("recent_disasters") { RecentDisastersScreen(navController) }
                            }
                        }
                        GlobalIncomingCallOverlayHost(navController)
                        GlobalInternetCallOverlayHost()
                        com.auralis.crisisconnect.screens.Chat.SfuCallOverlayHost(this@MainActivity)
                        CertificateProvisioningBanner(
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                    }
                }
            }
        }
    }

    @Composable
    private fun GlobalIncomingCallOverlayHost(navController: NavHostController) {
        val context = LocalContext.current
        var service by remember { mutableStateOf<RfcommForegroundService?>(null) }
        var incomingCall by remember { mutableStateOf<CallUiState?>(null) }
        var dismissedCallId by rememberSaveable { mutableStateOf<String?>(null) }
        val backStackEntry by navController.currentBackStackEntryAsState()

        DisposableEffect(Unit) {
            val collectScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            var callsJob: Job? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val boundService = (binder as? RfcommForegroundService.LocalBinder)?.getService() ?: return
                    service = boundService
                    callsJob?.cancel()
                    callsJob = collectScope.launch {
                        boundService.calls.collectLatest { calls ->
                            incomingCall = selectGlobalIncomingCall(calls.values)
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    callsJob?.cancel()
                    callsJob = null
                    service = null
                    incomingCall = null
                }
            }
            val bound = runCatching {
                bindService(
                    Intent(this@MainActivity, RfcommForegroundService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)

            onDispose {
                callsJob?.cancel()
                collectScope.cancel()
                service = null
                incomingCall = null
                if (bound) {
                    runCatching { unbindService(connection) }
                }
            }
        }

        LaunchedEffect(incomingCall?.callId) {
            val currentCallId = incomingCall?.callId ?: return@LaunchedEffect
            if (dismissedCallId != currentCallId) {
                dismissedCallId = null
            }
        }

        val call = incomingCall
            ?.takeIf { it.callId != dismissedCallId }
            ?: return
        val currentSessionCode = backStackEntry?.arguments
            ?.getString("sessionCode")
            ?.let(Uri::decode)
        if (currentSessionCode == call.sessionCode && backStackEntry?.destination?.route.isChatRoute()) {
            return
        }

        val contactName by produceState<String?>(initialValue = null, key1 = call.sessionCode) {
            value = withContext(Dispatchers.IO) {
                getContact(applicationContext, call.sessionCode)?.name
            }
        }
        val avatarBitmap by produceState<Bitmap?>(initialValue = null, key1 = call.sessionCode) {
            value = withContext(Dispatchers.IO) {
                ContactAvatarStorage.loadContactAvatar(applicationContext, call.sessionCode)
            }
        }
        val displayName = remember(contactName, call.sessionCode) {
            resolveChatDisplayName(
                context = context,
                sessionCode = call.sessionCode,
                contactName = contactName,
                preferredDisplayName = null
            )
        }

        CallOverlay(
            modifier = Modifier.fillMaxSize(),
            call = call,
            contactName = displayName,
            avatarStableKey = call.sessionCode,
            avatarBitmap = avatarBitmap,
            onAccept = {
                service?.acceptIncomingCall(call.sessionCode, call.callId) { accepted ->
                    if (!accepted) {
                        return@acceptIncomingCall
                    }
                    lifecycleScope.launch {
                        val destination = resolveConversationRoute(
                            context = applicationContext,
                            sessionCode = call.sessionCode,
                            preferredDisplayName = displayName
                        )
                        navController.navigate(destination) {
                            launchSingleTop = true
                        }
                    }
                }
            },
            onReject = { service?.rejectIncomingCall(call.sessionCode, call.callId) },
            onHangup = { service?.endVoipCall(call.sessionCode, call.callId) },
            onToggleMute = { muted ->
                service?.setCallMicMuted(call.sessionCode, muted)
            },
            onSelectAudioRoute = { route ->
                service?.setCallAudioRoute(call.sessionCode, route)
            },
            onMinimize = { dismissedCallId = call.callId }
        )
    }

    /**
     * App-wide overlay for an internet (WebRTC) call, so an incoming call rings and can be answered
     * from ANY screen — not only inside that contact's chat. [InternetCallManager] is a process-wide
     * singleton whose state is a StateFlow, so unlike the Bluetooth host this needs no service binding.
     */
    @Composable
    private fun GlobalInternetCallOverlayHost() {
        // Shared with RescueActivity (see InternetCallOverlayHost) so authority (kurum) calls placed or
        // received in rescue mode render the identical full-screen call UI this activity already shows.
        InternetCallOverlayHost(this@MainActivity)
    }

    private fun String?.isChatRoute(): Boolean {
        val route = this?.substringBefore("?") ?: return false
        return route.startsWith("chat/") ||
            route.startsWith("ble_chat/") ||
            route.startsWith("crisis_sentinel_chat/") ||
            route == "mesh_chat" ||
            route == "gatt_mesh_chat"
    }

    private fun selectGlobalIncomingCall(calls: Collection<CallUiState>): CallUiState? {
        return calls
            .asSequence()
            .filter { call ->
                !call.isOutgoing && call.state == CallState.Ringing
            }
            .maxByOrNull { it.startedAt }
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.chatEnterTransition(): EnterTransition {
        return slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(durationMillis = 240)
        ) + fadeIn(animationSpec = tween(durationMillis = 180))
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.chatForwardExitTransition(): ExitTransition {
        return slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(durationMillis = 210)
        ) + fadeOut(animationSpec = tween(durationMillis = 140))
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.chatPopEnterTransition(): EnterTransition {
        return slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(durationMillis = 200)
        ) + fadeIn(animationSpec = tween(durationMillis = 150))
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.chatPopExitTransition(): ExitTransition {
        return slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(durationMillis = 180)
        ) + fadeOut(animationSpec = tween(durationMillis = 120))
    }

    private fun ensureCriticalPermissionsAndStartService() {
        val missingPermissions = missingPermissions(requiredPermissionsForRequest())
            .filterNot { it == Manifest.permission.RECORD_AUDIO }
        if (missingPermissions.isNotEmpty()) {
            criticalPermissionsLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        startRfcommForegroundServiceIfNeeded()
    }

    private fun startRfcommForegroundServiceAfterOnboarding() {
        lifecycleScope.launch {
            if (hasCompletedWelcomeOnboarding()) {
                ensureCriticalPermissionsAndStartService()
            }
        }
    }

    private suspend fun hasCompletedWelcomeOnboarding(): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val termsAccepted =
                    onboardingDataStore.data.first()[ONBOARDING_TERMS_ACCEPTED_KEY] ?: false
                val savedName = getSavedUserName(this@MainActivity).first().trim()
                termsAccepted && savedName.isNotBlank()
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to read onboarding state; deferring permission request", throwable)
            }.getOrDefault(false)
        }
    }

    private fun requiredPermissionsForRequest(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions.distinct()
    }

    private fun requiredPermissionsForServiceStart(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.distinct()
    }

    private fun missingPermissions(permissions: List<String>): List<String> {
        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAllPermissions(permissions: List<String>): Boolean {
        return missingPermissions(permissions).isEmpty()
    }

    private fun startRfcommForegroundServiceIfNeeded() {
        if (rfcommServiceStarted) {
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, com.auralis.crisisconnect.service.RfcommForegroundService::class.java)
        )
        rfcommServiceStarted = true
    }

    private fun bootstrapCrisisLinkServiceIfEnabled() {
        lifecycleScope.launch {
            val enabled = runCatching {
                val key = booleanPreferencesKey("rescue_crisis_link_enabled")
                settingsDataStore.data.first()[key] ?: false
            }.getOrDefault(false)
            val rescueFeatureManager = RescueFeatureManager(applicationContext)
            val canUseFieldFeatures = if (enabled) {
                hasStoredRescueFeatureAccess()
            } else {
                false
            }

            if (enabled && canUseFieldFeatures) {
                if (rescueFeatureManager.isInstalled()) {
                    rescueFeatureManager.startCrisisLinkService()
                } else {
                    rescueFeatureManager.prefetchIfNeeded()
                }
            } else {
                rescueFeatureManager.stopCrisisLinkService()
            }
        }
    }

    private fun bootstrapMeshServiceIfAlwaysOn() {
        lifecycleScope.launch {
            val alwaysOnKey = booleanPreferencesKey("rescue_mesh_always_on_enabled")
            val preferences = runCatching {
                settingsDataStore.data.first()
            }.getOrNull()
            val alwaysOn = preferences?.get(alwaysOnKey) ?: false
            val shouldAutoStart = alwaysOn
            val rescueFeatureManager = RescueFeatureManager(applicationContext)
            val canUseMesh = if (shouldAutoStart) {
                hasStoredRescueFeatureAccess()
            } else {
                false
            }

            if (shouldAutoStart && canUseMesh) {
                if (rescueFeatureManager.isInstalled()) {
                    rescueFeatureManager.setMeshEnabled(true)
                } else {
                    rescueFeatureManager.prefetchIfNeeded()
                }
            } else {
                rescueFeatureManager.stopMeshService()
                if (alwaysOn && !canUseMesh) {
                    runCatching {
                        settingsDataStore.edit { prefs ->
                            prefs[alwaysOnKey] = false
                        }
                    }
                }
            }
        }
    }

    private suspend fun hasStoredRescueFeatureAccess(): Boolean {
        return withContext(Dispatchers.IO) {
            SecurityRepository(applicationContext)
                .getUsableStoredCertificateRole(allowExpired = true)
                ?.trim()
                ?.lowercase(Locale.US) in RESCUE_FEATURE_ROLES
        }
    }

    private fun bootstrapGattMeshServiceIfEnabled() {
        lifecycleScope.launch {
            val enabled = runCatching {
                val publicMeshKey = booleanPreferencesKey("advanced_public_mesh_enabled")
                val prefs = settingsDataStore.data.first()
                prefs[publicMeshKey] ?: false
            }.getOrDefault(false)

            if (enabled) {
                GattMeshForegroundService.start(applicationContext)
            } else {
                GattMeshForegroundService.stop(applicationContext)
            }
        }
    }

    /**
     * Authority (yetkili) mesh defaults ON in the background so it connects without the user opening
     * the chat. Gated by a stored rescue/authority role cert, so civilian devices never start it (and
     * the authority runtime self-stops anyway if it can't derive a group key). Independent of the
     * public mesh and the Wi-Fi-Aware "always on" setting.
     */
    private fun bootstrapAuthorityMeshServiceIfEnabled() {
        lifecycleScope.launch {
            val enabled = runCatching {
                val authorityMeshKey = booleanPreferencesKey("advanced_authority_mesh_enabled")
                settingsDataStore.data.first()[authorityMeshKey] ?: true
            }.getOrDefault(true)
            if (!enabled) {
                return@launch
            }
            if (!hasStoredRescueFeatureAccess()) {
                return@launch
            }
            RescueFeatureManager(applicationContext).startAuthorityMeshService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingCallWakeWindowPolicy(intent)
        answerInternetCallIfRequested(intent)
        handleSsoDeepLink(intent)
        navigationEvents.tryEmit(intent)
    }

    /** Catches the `crisisconnect://sso/callback` deep link returned by the web enterprise-SSO flow. */
    private fun handleSsoDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "crisisconnect" || data.host != "sso") {
            return
        }
        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")
        when {
            !code.isNullOrBlank() -> EnterpriseSsoBridge.emit(EnterpriseSsoBridge.Result.Code(code))
            else -> EnterpriseSsoBridge.emit(EnterpriseSsoBridge.Result.Error(error))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply on warm start so the status/navigation bar icons stay in sync
        // with the active theme even if the system reset them while we were
        // backgrounded (e.g. another app changed the bar appearance).
        configureSystemBars(getSavedThemeOptionSync(this))
    }

    override fun onStop() {
        super.onStop()
        if (incomingCallWakeWindowApplied) {
            applyIncomingCallWakeWindowPolicy(shouldEnable = false)
        }
    }

    override fun onDestroy() {
        rescueFeatureManager.unregisterListener(rescueFeatureInstallListener)
        super.onDestroy()
    }

    private fun onRescueFeatureInstalled() {
        bootstrapCrisisLinkServiceIfEnabled()
        bootstrapMeshServiceIfAlwaysOn()
    }

    /**
     * Accept the ringing internet call when we were launched by the ring notification's answer action.
     *
     * The answer action opens US rather than the foreground service on purpose: the service answered
     * fine, but its follow-up attempt to start this activity was refused as a background activity
     * launch (BAL_BLOCK), so the call ran with audio and no screen. Launching from the notification is
     * the user's own tap, so it is always allowed — and answering here means the UI is already coming
     * up as the call goes active.
     *
     * Trusted-token gated like every other privileged extra: MainActivity is exported, so without the
     * check any app could answer the user's calls.
     */
    private fun answerInternetCallIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER_INTERNET_CALL, false) != true) return
        intent.removeExtra(EXTRA_ANSWER_INTERNET_CALL)
        if (!hasTrustedLaunchToken(intent)) {
            Log.w(TAG, "Ignoring untrusted answer request on exported MainActivity")
            return
        }
        InternetCallManager.accept()
    }

    private fun applyIncomingCallWakeWindowPolicy(intent: Intent?) {
        val shouldEnable = if (hasTrustedLaunchToken(intent)) {
            intent?.getBooleanExtra(EXTRA_WAKE_FOR_INCOMING_CALL, false) == true
        } else {
            if (intent?.getBooleanExtra(EXTRA_WAKE_FOR_INCOMING_CALL, false) == true) {
                Log.w(TAG, "Ignoring untrusted wake request on exported MainActivity")
            }
            false
        }
        if (shouldEnable) {
            intent?.removeExtra(EXTRA_WAKE_FOR_INCOMING_CALL)
        }
        applyIncomingCallWakeWindowPolicy(shouldEnable)
    }

    private fun hasPrivilegedLaunchExtras(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }
        return !intent.getStringExtra(EXTRA_NAVIGATE_TO_SESSION).isNullOrBlank() ||
            !intent.getStringExtra(EXTRA_NAVIGATE_TO_ROUTE).isNullOrBlank() ||
            !intent.getStringExtra(EXTRA_NAVIGATE_TO_CALL).isNullOrBlank() ||
            intent.getBooleanExtra(EXTRA_WAKE_FOR_INCOMING_CALL, false)
    }

    private fun clearPrivilegedLaunchExtras(intent: Intent?) {
        intent?.removeExtra(EXTRA_NAVIGATE_TO_SESSION)
        intent?.removeExtra(EXTRA_NAVIGATE_TO_ROUTE)
        intent?.removeExtra(EXTRA_NAVIGATE_TO_CALL)
        intent?.removeExtra(EXTRA_WAKE_FOR_INCOMING_CALL)
    }

    private fun hasTrustedLaunchToken(intent: Intent?): Boolean {
        val providedToken = intent?.getStringExtra(EXTRA_TRUSTED_LAUNCH_TOKEN)?.trim().orEmpty()
        return providedToken.isNotEmpty() &&
            providedToken == getOrCreateTrustedLaunchToken(applicationContext)
    }

    private fun applyIncomingCallWakeWindowPolicy(shouldEnable: Boolean) {
        if (incomingCallWakeWindowApplied == shouldEnable) {
            return
        }
        incomingCallWakeWindowApplied = shouldEnable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Keep this in sync with the incoming-call launch path.
            // The activity must explicitly opt into lockscreen visibility/screen-on behavior.
            setShowWhenLocked(shouldEnable)
            setTurnScreenOn(shouldEnable)
        } else {
            if (shouldEnable) {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val ENABLE_CHAT_SHARED_ELEMENTS = false
        private val RESCUE_FEATURE_ROLES = setOf("admin", "fieldteam")
        const val EXTRA_NAVIGATE_TO_SESSION = "extra_nav_session"
        const val EXTRA_NAVIGATE_TO_ROUTE = "extra_nav_route"
        const val EXTRA_NAVIGATE_TO_CALL = "extra_nav_call"
        const val EXTRA_WAKE_FOR_INCOMING_CALL = "extra_wake_for_incoming_call"
        /** Set by the internet ring notification's answer action; see [answerInternetCallIfRequested]. */
        const val EXTRA_ANSWER_INTERNET_CALL = "extra_answer_internet_call"
        private const val EXTRA_TRUSTED_LAUNCH_TOKEN = "extra_trusted_launch_token"
        private const val LAUNCH_TRUST_PREFS = "main_activity_launch_trust"
        private const val LAUNCH_TRUST_PREFS_ENCRYPTED = "main_activity_launch_trust_enc"
        private const val LAUNCH_TRUST_TOKEN_KEY = "launch_token"
        private const val LAUNCH_TRUST_TOKEN_BYTES = 32
        private val launchTokenRandom = SecureRandom()

        fun createTrustedLaunchIntent(
            context: Context,
            configure: Intent.() -> Unit = {}
        ): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(
                    EXTRA_TRUSTED_LAUNCH_TOKEN,
                    getOrCreateTrustedLaunchToken(context.applicationContext)
                )
                configure()
            }
        }

        private fun getOrCreateTrustedLaunchToken(context: Context): String {
            val appContext = context.applicationContext
            val prefs = openLaunchTokenPrefs(appContext)
            prefs.getString(LAUNCH_TRUST_TOKEN_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            val tokenBytes = ByteArray(LAUNCH_TRUST_TOKEN_BYTES)
            launchTokenRandom.nextBytes(tokenBytes)
            val token = Base64.encodeToString(tokenBytes, Base64.NO_WRAP or Base64.NO_PADDING)
            prefs.edit().putString(LAUNCH_TRUST_TOKEN_KEY, token).apply()
            return token
        }

        private fun openLaunchTokenPrefs(context: Context): SharedPreferences {
            return runCatching { openEncryptedLaunchTokenPrefs(context) }
                .onFailure { Log.w(TAG, "EncryptedSharedPreferences unavailable for launch token; using plain prefs", it) }
                .getOrElse {
                    context.getSharedPreferences(LAUNCH_TRUST_PREFS, Context.MODE_PRIVATE)
                }
        }

        private fun openEncryptedLaunchTokenPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                context,
                LAUNCH_TRUST_PREFS_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateLegacyLaunchTokenIfPresent(context, encrypted)
            return encrypted
        }

        private fun migrateLegacyLaunchTokenIfPresent(
            context: Context,
            encrypted: SharedPreferences,
        ) {
            val legacy = context.getSharedPreferences(LAUNCH_TRUST_PREFS, Context.MODE_PRIVATE)
            val legacyToken = legacy.getString(LAUNCH_TRUST_TOKEN_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (legacyToken != null && !encrypted.contains(LAUNCH_TRUST_TOKEN_KEY)) {
                encrypted.edit().putString(LAUNCH_TRUST_TOKEN_KEY, legacyToken).apply()
            }
            if (legacy.contains(LAUNCH_TRUST_TOKEN_KEY)) {
                legacy.edit().remove(LAUNCH_TRUST_TOKEN_KEY).apply()
            }
        }
    }
}
