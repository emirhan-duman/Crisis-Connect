package com.auralis.crisisconnect.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    fun launchConversation(context: Context, sessionCode: String) {
        val normalizedSessionCode = sessionCode.trim()
        if (normalizedSessionCode.isEmpty()) {
            return
        }
        val intent = MainActivity.createTrustedLaunchIntent(context) {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, normalizedSessionCode)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    fun launchAuthorityConversation(
        context: Context,
        channelId: String,
        peerUid: String,
        title: String,
        agency: String,
        role: String,
    ) {
        val normalizedChannelId = channelId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedPeerUid = peerUid.trim().takeIf { it.isNotEmpty() } ?: return
        val route = "authority_channel/${Uri.encode(normalizedChannelId)}/${Uri.encode(normalizedPeerUid)}" +
            "?title=${Uri.encode(title.trim())}" +
            "&agency=${Uri.encode(agency.trim())}" +
            "&role=${Uri.encode(role.trim())}" +
            "&scope=agency"
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
        "guide_main",
        "sos_countdown",
        "recent_disasters",
        "sos_status"
    )
}
