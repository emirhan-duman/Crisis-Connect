package com.auralis.crisisconnect.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.navigation.navigateBottomBar

private enum class AppTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    Messages(route = "main", labelRes = R.string.Messages, icon = Icons.AutoMirrored.Filled.Message),
    Tools(route = "tools_main", labelRes = R.string.Tools, icon = Icons.Filled.Build),
    Guide(route = "guide_main", labelRes = R.string.Guide, icon = Icons.AutoMirrored.Filled.MenuBook)
}

private val MESSAGE_ROUTES = setOf(
    "main",
    "sos_status",
    "new_chat"
)

private val TOOLS_ROUTES = setOf(
    "tools_main",
    "metal_detector",
    "signal_finder",
    "whistle",
    "offline_map",
    "compass",
    "sensor_tool"
)

@Composable
fun AppBottomBar(
    navController: NavController,
    modifier: Modifier = Modifier,
    messageBadgeCount: Int = 0,
    onRouteSelected: ((String) -> Unit)? = null
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedTab = resolveSelectedTab(backStackEntry?.destination?.route)

    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            AppBottomBarItem(
                tab = AppTab.Messages,
                selected = selectedTab == AppTab.Messages,
                onClick = {
                    onRouteSelected?.invoke(AppTab.Messages.route)
                        ?: navController.navigateBottomBar(AppTab.Messages.route)
                },
                colors = itemColors,
                badgeCount = messageBadgeCount
            )
            AppBottomBarItem(
                tab = AppTab.Tools,
                selected = selectedTab == AppTab.Tools,
                onClick = {
                    onRouteSelected?.invoke(AppTab.Tools.route)
                        ?: navController.navigateBottomBar(AppTab.Tools.route)
                },
                colors = itemColors
            )
            AppBottomBarItem(
                tab = AppTab.Guide,
                selected = selectedTab == AppTab.Guide,
                onClick = {
                    onRouteSelected?.invoke(AppTab.Guide.route)
                        ?: navController.navigateBottomBar(AppTab.Guide.route)
                },
                colors = itemColors
            )
        }
    }
}

@Composable
private fun RowScope.AppBottomBarItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    colors: androidx.compose.material3.NavigationBarItemColors,
    badgeCount: Int = 0
) {
    val label = stringResource(tab.labelRes)

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        alwaysShowLabel = true,
        colors = colors,
        icon = {
            if (tab == AppTab.Messages && badgeCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text(text = formatBadgeCount(badgeCount))
                        }
                    }
                ) {
                    AnimatedTabIcon(
                        icon = tab.icon,
                        selected = selected,
                        contentDescription = label
                    )
                }
            } else {
                AnimatedTabIcon(
                    icon = tab.icon,
                    selected = selected,
                    contentDescription = label
                )
            }
        },
        label = {
            Text(text = label, maxLines = 1)
        }
    )
}

@Composable
private fun AnimatedTabIcon(
    icon: ImageVector,
    selected: Boolean,
    contentDescription: String
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = tween(durationMillis = 170),
        label = "bottom_bar_icon_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.82f,
        animationSpec = tween(durationMillis = 150),
        label = "bottom_bar_icon_alpha"
    )

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier
            .scale(scale)
            .alpha(alpha)
    )
}

private fun resolveSelectedTab(route: String?): AppTab? {
    if (route == null) return null
    val normalizedRoute = route.substringBefore("?")

    return when {
        normalizedRoute == AppTab.Guide.route -> AppTab.Guide
        normalizedRoute in TOOLS_ROUTES -> AppTab.Tools
        normalizedRoute in MESSAGE_ROUTES ||
            normalizedRoute.startsWith("chat/") ||
            normalizedRoute.startsWith("ble_chat/") ||
            normalizedRoute == "mesh_chat" ||
            normalizedRoute == "gatt_mesh_chat" -> {
            AppTab.Messages
        }

        else -> null
    }
}

private fun formatBadgeCount(count: Int): String {
    return if (count > 99) "99+" else count.toString()
}
