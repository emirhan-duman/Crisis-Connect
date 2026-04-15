package com.auralis.crisisconnect.core.io

private val SAFE_TRANSFER_ID_REGEX =
    Regex("^(?=.*[A-Za-z0-9])[A-Za-z0-9_-]{16,96}$")

fun normalizeSafeTransferId(rawId: String?): String? {
    val normalized = rawId?.trim().orEmpty()
    return normalized.takeIf { SAFE_TRANSFER_ID_REGEX.matches(it) }
}

fun requireSafeTransferId(rawId: String): String {
    return normalizeSafeTransferId(rawId)
        ?: throw IllegalArgumentException("Unsafe transfer id")
}
