package com.auralis.crisisconnect.feature.rescue

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.auralis.crisisconnect.screens.Chat.MeshChatScreen
import com.auralis.crisisconnect.screens.RescueScreen
import com.auralis.crisisconnect.screens.RescueSettingsScreen
import com.auralis.crisisconnect.setLocale
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme
import com.google.android.play.core.splitcompat.SplitCompat

class RescueActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        SplitCompat.installActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupLanguageCode = getSavedLanguageSync(this)
        installSplashScreen()
        setLocale(this, startupLanguageCode, shouldRecreate = false)
        configureEdgeToEdge(getSavedThemeOptionSync(this))
        super.onCreate(savedInstanceState)
        val startDestination = intent
            ?.getStringExtra(RescueFeatureManager.EXTRA_START_DESTINATION)
            ?.takeIf { it in SUPPORTED_START_DESTINATIONS }
            ?: ROUTE_RESCUE_HOME

        setContent {
            val themeOption by getSavedThemeOption(this@RescueActivity)
                .collectAsState(initial = getSavedThemeOptionSync(this@RescueActivity))
            configureEdgeToEdge(themeOption)

            DisasterCommunicationSystemTheme(
                darkTheme = themeOption.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = themeOption == ThemeOption.SYSTEM
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = startDestination) {
                        composable(ROUTE_RESCUE_HOME) {
                            RescueScreen(
                                navController = navController,
                                onBackPressed = ::finish,
                                onBottomBarRouteSelected = ::openMainRoute,
                                onConversationSelected = ::openConversation
                            )
                        }
                        composable(ROUTE_RESCUE_SETTINGS) {
                            RescueSettingsScreen(
                                navController = navController,
                                onBottomBarRouteSelected = ::openMainRoute
                            )
                        }
                        composable(ROUTE_RESCUE_MESH_CHAT) {
                            MeshChatScreen(navController = navController)
                        }
                    }
                }
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
        val SUPPORTED_START_DESTINATIONS = setOf(
            ROUTE_RESCUE_HOME,
            ROUTE_RESCUE_SETTINGS,
            ROUTE_RESCUE_MESH_CHAT
        )
    }
}
