package com.auralis.crisisconnect

import com.auralis.crisisconnect.screens.MainScreen
import com.auralis.crisisconnect.screens.NewChatScreen
import com.auralis.crisisconnect.screens.Guide.GuideMainScreen
import com.auralis.crisisconnect.screens.ToolsMainScreen
import com.auralis.crisisconnect.screens.SettingsScreen
import com.auralis.crisisconnect.screens.AdvancedSettingsScreen
import com.auralis.crisisconnect.screens.settings.ProfileScreen
import com.auralis.crisisconnect.screens.Tools.MetalDetectorScreen
import com.auralis.crisisconnect.screens.Tools.SignalFinderScreen
import com.auralis.crisisconnect.screens.Tools.CompassScreen
import com.auralis.crisisconnect.screens.Tools.OfflineMapScreen
import com.auralis.crisisconnect.screens.Tools.SensorToolScreen
import com.auralis.crisisconnect.screens.Tools.WhistleScreen
import com.auralis.crisisconnect.screens.Chat.ChatScreen
import com.auralis.crisisconnect.screens.Chat.ChatInfoScreen
import com.auralis.crisisconnect.screens.Chat.BleChatScreen
import com.auralis.crisisconnect.screens.Chat.GattMeshScreen
import com.auralis.crisisconnect.screens.SOSScreen

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
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
import com.auralis.crisisconnect.navigation.resolveConversationRoute
import com.auralis.crisisconnect.screens.Chat.CallOverlay
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme
import com.auralis.crisisconnect.screens.Chat.ChatSessionCodeScreen
import com.auralis.crisisconnect.screens.QR.ChatEncryptionSetupScreen
import com.auralis.crisisconnect.screens.QR.QrScannerScreen
import com.auralis.crisisconnect.screens.Chat.resolveChatDisplayName
import com.auralis.crisisconnect.data.database.DatabaseInitializer
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.google.firebase.FirebaseApp
import java.security.SecureRandom
import java.util.Locale
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
                .withEndAction { splashViewProvider.remove() }
                .start()
        }
        configureSystemBars(startupThemeOption)
        super.onCreate(savedInstanceState)
        setLocale(this, startupLanguageCode, shouldRecreate = false)
        applyOrientationPolicyForDeviceClass()
        applyIncomingCallWakeWindowPolicy(intent)
        rescueFeatureManager.registerListener(rescueFeatureInstallListener)
        FirebaseApp.initializeApp(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                DatabaseInitializer().initializeDatabase(applicationContext)
            }.onFailure { throwable ->
                Log.w(TAG, "Database bootstrap failed", throwable)
            }
        }
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
        bootstrapMeshServiceIfAlwaysOn()
        ensureCriticalPermissionsAndStartService()

        lifecycleScope.launch {
            val languageCode = runCatching {
                withContext(Dispatchers.IO) {
                    getSavedLanguage(this@MainActivity).first()
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load saved language; falling back to default", throwable)
            }.getOrDefault("en")

            val themeOption = runCatching {
                withContext(Dispatchers.IO) {
                    getSavedThemeOption(this@MainActivity).first()
                }
            }.onFailure { throwable ->
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
                .collectAsState(initial = initialThemeOption)
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
                    val navController = rememberNavController()
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
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
                                when (route) {
                                    "main",
                                    "tools_main",
                                    "guide_main" -> {
                                        navController.navigateBottomBar(route)
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
                                        }
                                    )
                                }
                                composable("sos_status") { SOSScreen(navController) }
                                composable("new_chat") { NewChatScreen(navController) }
                                composable("qr_scan") { QrScannerScreen(navController) }
                                composable("settings") { SettingsScreen(navController) }
                                composable("advanced_settings") { AdvancedSettingsScreen(navController) }
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
                                composable("metal_detector") { MetalDetectorScreen(navController) }
                                composable("signal_finder") { SignalFinderScreen(navController) }
                                composable("whistle") { WhistleScreen(navController) }
                                composable("offline_map") { OfflineMapScreen(navController) }
                                composable("compass") { CompassScreen(navController) }
                                composable("sensor_tool") { SensorToolScreen(navController) }
                            }
                        }
                        GlobalIncomingCallOverlayHost(navController)
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

    private fun String?.isChatRoute(): Boolean {
        val route = this?.substringBefore("?") ?: return false
        return route.startsWith("chat/") ||
            route.startsWith("ble_chat/") ||
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            setIntent(it)
            applyIncomingCallWakeWindowPolicy(it)
            navigationEvents.tryEmit(it)
        }
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
        private const val EXTRA_TRUSTED_LAUNCH_TOKEN = "extra_trusted_launch_token"
        private const val LAUNCH_TRUST_PREFS = "main_activity_launch_trust"
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
            val prefs = context.applicationContext.getSharedPreferences(
                LAUNCH_TRUST_PREFS,
                Context.MODE_PRIVATE
            )
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
    }
}
