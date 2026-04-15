package com.auralis.crisisconnect.navigation

import android.content.Context
import android.net.Uri
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun buildConversationRoute(
    sessionCode: String,
    preferredDisplayName: String? = null,
    preferredTransport: String? = null
): String {
    val normalizedSessionCode = sessionCode.trim()
    val encodedSessionCode = Uri.encode(normalizedSessionCode)
    val encodedDisplayName = preferredDisplayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(Uri::encode)

    val baseRoute = when {
        normalizedSessionCode.startsWith("gattmesh:", ignoreCase = true) -> "gatt_mesh_chat"
        normalizedSessionCode.startsWith("mesh:", ignoreCase = true) -> "mesh_chat"
        normalizedSessionCode.startsWith("ble:", ignoreCase = true) &&
            normalizePreferredTransport(preferredTransport) != PREFERRED_TRANSPORT_BLE_GATT -> {
            "ble_chat/$encodedSessionCode"
        }

        else -> "chat/$encodedSessionCode"
    }

    if (encodedDisplayName == null || !baseRoute.contains('/')) {
        return baseRoute
    }
    return "$baseRoute?displayName=$encodedDisplayName"
}

internal suspend fun resolveConversationRoute(
    context: Context,
    sessionCode: String,
    preferredDisplayName: String? = null
): String {
    val preferredTransport = if (sessionCode.startsWith("ble:", ignoreCase = true)) {
        withContext(Dispatchers.IO) {
            getContact(context, sessionCode)?.preferredTransport
        }
    } else {
        null
    }
    return buildConversationRoute(
        sessionCode = sessionCode,
        preferredDisplayName = preferredDisplayName,
        preferredTransport = preferredTransport
    )
}
