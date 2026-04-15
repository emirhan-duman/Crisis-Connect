package com.auralis.crisisconnect.screens.Chat

import android.content.Context
import com.auralis.crisisconnect.R

private val MAC_ADDRESS_REGEX = Regex("(?i)^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

internal fun resolveChatDisplayName(
    context: Context,
    sessionCode: String,
    contactName: String?,
    preferredDisplayName: String?
): String {
    val sessionRaw = sessionCode.trim()
    val sessionWithoutPrefix = sessionRaw.substringAfter("ble:", sessionRaw).trim()

    val candidate = sequenceOf(contactName, preferredDisplayName)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull { value ->
            !value.equals(sessionRaw, ignoreCase = true) &&
                !value.equals(sessionWithoutPrefix, ignoreCase = true) &&
                !MAC_ADDRESS_REGEX.matches(value)
        }
    if (candidate != null) {
        return candidate
    }

    return when {
        sessionRaw.startsWith("ble:", ignoreCase = true) || MAC_ADDRESS_REGEX.matches(sessionWithoutPrefix) -> {
            context.getString(R.string.rescue_unknown_device)
        }

        sessionRaw.startsWith("mesh:", ignoreCase = true) ||
            sessionRaw.startsWith("gattmesh:", ignoreCase = true) -> {
            context.getString(R.string.mesh_chat_general_title)
        }

        else -> {
            sessionWithoutPrefix
                .takeIf { it.isNotBlank() }
                ?.take(24)
                ?: context.getString(R.string.chat_title)
        }
    }
}
