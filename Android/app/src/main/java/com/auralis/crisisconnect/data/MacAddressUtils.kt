package com.auralis.crisisconnect.data

import java.util.Locale

private val MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

fun normalizeMacAddress(address: String?): String {
    if (address.isNullOrBlank()) {
        return ""
    }

    val upper = address.trim().uppercase(Locale.US)

    val hexOnly = buildString {
        upper.forEach { ch ->
            if (ch.isDigit() || ch in 'A'..'F') {
                append(ch)
            }
        }
    }

    val normalized = when {
        hexOnly.length == 12 -> hexOnly.chunked(2).joinToString(":")
        upper.length == 17 && upper.count { it == ':' || it == '-' } == 5 ->
            upper.replace('-', ':')
        else -> upper
    }

    return if (MAC_REGEX.matches(normalized)) normalized else ""
}
