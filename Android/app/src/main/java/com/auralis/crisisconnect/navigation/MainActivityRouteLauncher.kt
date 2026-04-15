package com.auralis.crisisconnect.navigation

import android.content.Context
import android.content.Intent
import com.auralis.crisisconnect.MainActivity

object MainActivityRouteLauncher {

    fun launch(context: Context, route: String) {
        if (!isSupportedRoute(route)) {
            return
        }
        val intent = MainActivity.createTrustedLaunchIntent(context) {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    fun isSupportedRoute(route: String): Boolean = route in SUPPORTED_ROUTES

    private val SUPPORTED_ROUTES = setOf(
        "main",
        "tools_main",
        "guide_main"
    )
}
