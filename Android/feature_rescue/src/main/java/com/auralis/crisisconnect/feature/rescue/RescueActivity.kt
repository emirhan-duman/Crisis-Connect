package com.auralis.crisisconnect.feature.rescue

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.ThemeOption
import com.auralis.crisisconnect.getSavedLanguageSync
import com.auralis.crisisconnect.getSavedThemeOption
import com.auralis.crisisconnect.getSavedThemeOptionSync
import com.auralis.crisisconnect.navigation.MainActivityRouteLauncher
import com.auralis.crisisconnect.messaging.AuthorityRosterMember
import com.auralis.crisisconnect.screens.Chat.InternetCallOverlayHost
import com.auralis.crisisconnect.screens.Chat.MeshChatScreen
import com.auralis.crisisconnect.screens.authority.AuthorityMessagingScreen
import com.auralis.crisisconnect.screens.authority.AuthorityRosterScreen
import com.auralis.crisisconnect.screens.authority.HierarchyMessagingScreen
import com.auralis.crisisconnect.screens.RemoteSignalsScreen
import com.auralis.crisisconnect.screens.RescueScreen
import com.auralis.crisisconnect.screens.RescueSettingsScreen
import com.auralis.crisisconnect.setLocale
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme
import com.google.android.play.core.splitcompat.SplitCompat
import androidx.lifecycle.lifecycleScope
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.ui.components.NavbarSettingsCache
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.auralis.crisisconnect.security.SecurityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.util.Locale
import com.auralis.crisisconnect.R

class RescueActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        SplitCompat.installActivity(this)
    }

    /**
     * Same policy as MainActivity: phones stay portrait, large screens (tablets) rotate freely.
     * Without this the rescue screens were the only part of the app that rotated on phones.
     */
    private fun applyOrientationPolicyForDeviceClass() {
        val isLargeScreenDevice = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isLargeScreenDevice) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupLanguageCode = getSavedLanguageSync(this)
        installSplashScreen()
        setLocale(this, startupLanguageCode, shouldRecreate = false)
        configureEdgeToEdge(getSavedThemeOptionSync(this))
        super.onCreate(savedInstanceState)
        applyOrientationPolicyForDeviceClass()
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
        val startDestination = intent
            ?.getStringExtra(RescueFeatureManager.EXTRA_START_DESTINATION)
            ?.takeIf { it in SUPPORTED_START_DESTINATIONS }
            ?: ROUTE_RESCUE_HOME

        setContent {
            var isAuthorized by remember { mutableStateOf<Boolean?>(null) }

            LaunchedEffect(Unit) {
                val hasAccess = withContext(Dispatchers.IO) {
                    SecurityRepository(applicationContext)
                        .getUsableStoredCertificateRole(allowExpired = true)
                        ?.trim()
                        ?.lowercase(Locale.US) in setOf("admin", "fieldteam")
                }
                isAuthorized = hasAccess
                if (!hasAccess) {
                    Toast.makeText(
                        this@RescueActivity,
                        getString(R.string.rescue_forbidden_toast),
                        Toast.LENGTH_LONG
                    ).show()
                    openMainRoute("main")
                }
            }

            if (isAuthorized == true) {
                val themeOption by getSavedThemeOption(this@RescueActivity)
                    .collectAsStateWithLifecycle(initialValue = getSavedThemeOptionSync(this@RescueActivity))
                configureEdgeToEdge(themeOption)

                DisasterCommunicationSystemTheme(
                    darkTheme = themeOption.resolveDarkTheme(isSystemInDarkTheme()),
                    dynamicColor = themeOption == ThemeOption.SYSTEM
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = startDestination) {
                            composable(ROUTE_RESCUE_HOME) {
                                RescueScreen(
                                    navController = navController,
                                    onBackPressed = ::finish,
                                    onBottomBarRouteSelected = { route ->
                                        if (route == ROUTE_RESCUE_HOME) {
                                            navController.navigate(ROUTE_RESCUE_HOME) {
                                                popUpTo(ROUTE_RESCUE_HOME) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            openMainRoute(route)
                                        }
                                    },
                                    onConversationSelected = ::openConversation
                                )
                            }
                            composable(ROUTE_RESCUE_SETTINGS) {
                                RescueSettingsScreen(
                                    navController = navController,
                                    onBottomBarRouteSelected = { route ->
                                        if (route == ROUTE_RESCUE_HOME) {
                                            navController.navigate(ROUTE_RESCUE_HOME) {
                                                popUpTo(ROUTE_RESCUE_HOME) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            openMainRoute(route)
                                        }
                                    }
                                )
                            }
                            composable(ROUTE_RESCUE_MESH_CHAT) {
                                MeshChatScreen(
                                    navController = navController,
                                    // When launched directly into the chat (it is the NavHost root, e.g.
                                    // from the main-home "Yetkili Sohbet" card) there is nothing to pop,
                                    // so finish the activity instead of getting stuck on the screen.
                                    onBack = { if (!navController.navigateUp()) finish() }
                                )
                            }
                            composable(ROUTE_AUTHORITY_MESSAGING) {
                                // Online authority (kurum) messaging — the same per-agency channel the
                                // web dashboard uses. Reachable directly as a start destination, so an
                                // empty back stack finishes the activity like the mesh chat above.
                                AuthorityMessagingScreen(
                                    onBack = { if (!navController.navigateUp()) finish() }
                                )
                            }
                            composable(ROUTE_HIERARCHY_MESSAGING) {
                                // Cross-panel (hierarchy) authority messaging — parent/child/sibling
                                // panels, via the web /api/messaging/hierarchy channels.
                                HierarchyMessagingScreen(
                                    onBack = { if (!navController.navigateUp()) finish() }
                                )
                            }
                            composable(ROUTE_AUTHORITY_ROSTER) {
                                // Same-agency authority directory — add a fellow authority as a 1:1
                                // contact (stores their number) then open the normal chat, which works
                                // online and falls back to an offline number-keyed Bluetooth link.
                                AuthorityRosterScreen(
                                    onBack = { if (!navController.navigateUp()) finish() },
                                    onOpenConversation = ::openAuthorityConversation
                                )
                            }
                            composable(ROUTE_REMOTE_SIGNALS) {
                                // The agency panel's live SOS feed (internet self-reports + other
                                // teams' sightings), opened from the overview card's remote pill.
                                RemoteSignalsScreen(
                                    onBack = { if (!navController.navigateUp()) finish() }
                                )
                            }
                        }

                            // App-wide internet call screen — the same overlay MainActivity hosts — so
                            // authority (kurum) calls placed or received in rescue mode show and can be
                            // answered here with the identical full-screen call UI.
                            InternetCallOverlayHost(this@RescueActivity)
                            com.auralis.crisisconnect.screens.Chat.SfuCallOverlayHost(this@RescueActivity)
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {}
            }
        }
    }

    private fun openMainRoute(route: String) {
        MainActivityRouteLauncher.launch(this, route)
        finish()
    }

    private fun openConversation(sessionCode: String) {
        MainActivityRouteLauncher.launchConversation(this, sessionCode)
        finish()
    }

    private fun openAuthorityConversation(member: AuthorityRosterMember) {
        MainActivityRouteLauncher.launchAuthorityConversation(
            context = this,
            channelId = member.agencySlug,
            peerUid = member.uid,
            title = member.name,
            agency = member.agencySlug,
            role = member.role,
        )
        finish()
    }

    private fun configureEdgeToEdge(themeOption: ThemeOption) {
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            themeOption.resolveLightSystemBarIcons(isSystemNightMode())
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            themeOption.resolveLightSystemBarIcons(isSystemNightMode())
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

    private fun ThemeOption.resolveLightSystemBarIcons(systemInDarkTheme: Boolean): Boolean {
        return when (this) {
            ThemeOption.DARK -> false
            ThemeOption.LIGHT -> true
            ThemeOption.SYSTEM -> !systemInDarkTheme
        }
    }

    private companion object {
        const val ROUTE_RESCUE_HOME = "rescue_home"
        const val ROUTE_RESCUE_SETTINGS = "rescue_settings"
        const val ROUTE_RESCUE_MESH_CHAT = "mesh_chat"
        const val ROUTE_AUTHORITY_MESSAGING = "authority_messaging"
        const val ROUTE_HIERARCHY_MESSAGING = "hierarchy_messaging"
        const val ROUTE_AUTHORITY_ROSTER = "authority_roster"
        const val ROUTE_REMOTE_SIGNALS = "remote_signals"
        val SUPPORTED_START_DESTINATIONS = setOf(
            ROUTE_RESCUE_HOME,
            ROUTE_RESCUE_SETTINGS,
            ROUTE_RESCUE_MESH_CHAT,
            ROUTE_AUTHORITY_MESSAGING,
            ROUTE_HIERARCHY_MESSAGING,
            ROUTE_AUTHORITY_ROSTER
        )
    }
}
