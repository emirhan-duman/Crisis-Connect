package com.auralis.crisisconnect.messaging

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Canonical, cross-platform account/device binding carried inside every MLS BasicCredential. */
object AuthorityMlsCredential {
    private const val PREFIX = "cc-mls:v1:"

    data class Parsed(val accountUid: String, val deviceId: String)

    fun encode(accountUid: String, deviceId: String): String {
        val uid = accountUid.trim()
        val device = deviceId.trim()
        require(uid.isNotEmpty() && uid.toByteArray().size <= 192) { "MLS account UID is invalid." }
        require(device.isNotEmpty() && device.toByteArray().size <= 192) { "MLS device ID is invalid." }
        val encoded = PREFIX + b64(uid) + ":" + b64(device)
        require(encoded.toByteArray().size <= 512) { "MLS credential is too long." }
        return encoded
    }

    fun decode(value: String): Parsed? {
        if (!value.startsWith(PREFIX) || value.length > 512) return null
        val parts = value.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return runCatching {
            val uid = String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
            val device = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            if (encode(uid, device) != value) null else Parsed(uid, device)
        }.getOrNull()
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
