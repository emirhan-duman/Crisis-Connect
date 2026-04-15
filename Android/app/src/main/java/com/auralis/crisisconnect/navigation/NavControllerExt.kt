package com.auralis.crisisconnect.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Bottom bar navigation optimized for instant tab switching:
 * - avoids duplicate destinations
 * - prefers popping to an existing tab entry
 * - preserves and restores tab state
 */
fun NavController.navigateBottomBar(route: String) {
    if (currentDestination?.route == route) {
        return
    }

    if (popBackStack(route, inclusive = false)) {
        return
    }

    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
