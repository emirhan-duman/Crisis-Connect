package com.auralis.crisisconnect.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.auralis.crisisconnect.MainActivity

/**
 * Quick Settings tile: one tap from the shade opens the in-app SOS countdown.
 * Like the home-screen widget, it never fires the broadcast directly — the
 * 5-second countdown remains the guard against accidental taps. On a locked
 * device the tile asks for unlock first, so a pocketed phone can't declare.
 */
class SosQuickSettingsTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchSosCountdown() }
        } else {
            launchSosCountdown()
        }
    }

    private fun launchSosCountdown() {
        val intent = MainActivity.createTrustedLaunchIntent(this) {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_ROUTE, "sos_countdown")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
