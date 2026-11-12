package com.auralis.crisisconnect.watch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.screens.settings.WatchSettingsScreen
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme

/**
 * Activity entry point for managing independent watch messaging, approvals, and sync.
 * Renders the modern [WatchSettingsScreen] Jetpack Compose UI.
 */
class IndependentMessagingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DisasterCommunicationSystemTheme(darkTheme = isSystemInDarkTheme()) {
                WatchSettingsScreen(
                    onNavigateBack = { finish() },
                    onNavigateToQrScan = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            putExtra("route", "qr_scan")
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}
