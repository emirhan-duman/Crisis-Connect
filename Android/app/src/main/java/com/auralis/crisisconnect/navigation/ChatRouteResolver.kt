package com.auralis.crisisconnect.navigation

import android.content.Context
import android.net.Uri
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val contact = withContext(Dispatchers.IO) {
        runCatching { getContact(context, sessionCode) }.getOrNull()
    }
    // A hidden authority-bridge contact has no citizen chat of its own — its conversation IS the
    // kurum channel thread. A Bluetooth message notification must land there, not on the citizen
    // ChatScreen of a contact the home list deliberately hides.
    if (contact?.isAuthorityBridge == true && contact.peerUid.isNotBlank()) {
        val conversation = withContext(Dispatchers.IO) {
            runCatching {
                AppDatabase.getInstance(context).authorityMessageDao()
                    .observeConversations().first()
                    .filter { it.peerUid == contact.peerUid }
                    .maxByOrNull { it.lastAtMillis }
            }.getOrNull()
        }
        if (conversation != null) {
            return "authority_channel/${Uri.encode(conversation.channelId)}/" +
                "${Uri.encode(conversation.peerUid)}" +
                "?title=${Uri.encode(conversation.peerName)}" +
                "&agency=${Uri.encode(conversation.peerPanelName)}" +
                "&role=${Uri.encode(conversation.peerRole)}"
        }
    }
    return buildConversationRoute(
        sessionCode = sessionCode,
        preferredDisplayName = preferredDisplayName,
        preferredTransport = contact?.preferredTransport
    )
}
