package com.auralis.crisisconnect.data

import java.util.UUID

private const val MESSAGE_UUID_TIMESTAMP_DELIMITER = "~"
private const val MIN_REASONABLE_MESSAGE_TIMESTAMP_MILLIS = 946_684_800_000L // 2000-01-01 UTC
private const val MESSAGE_TIMESTAMP_FUTURE_SKEW_TOLERANCE_MS = 5 * 60_000L

fun buildStoreForwardMessageUuid(
    baseUuid: String = UUID.randomUUID().toString(),
    createdAtMillis: Long = System.currentTimeMillis()
): String {
    val normalizedBaseUuid = baseUuid.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    val normalizedCreatedAt = createdAtMillis
        .takeIf { it >= MIN_REASONABLE_MESSAGE_TIMESTAMP_MILLIS }
        ?: System.currentTimeMillis()
    return "$normalizedBaseUuid$MESSAGE_UUID_TIMESTAMP_DELIMITER$normalizedCreatedAt"
}

fun extractStoreForwardCreatedAtMillis(messageUuid: String): Long? {
    if (messageUuid.isBlank()) {
        return null
    }
    val delimiterIndex = messageUuid.lastIndexOf(MESSAGE_UUID_TIMESTAMP_DELIMITER)
    if (delimiterIndex <= 0 || delimiterIndex == messageUuid.lastIndex) {
        return null
    }
    val suffix = messageUuid.substring(delimiterIndex + 1)
    if (suffix.any { !it.isDigit() }) {
        return null
    }
    val parsed = suffix.toLongOrNull() ?: return null
    val upperBound = System.currentTimeMillis() + MESSAGE_TIMESTAMP_FUTURE_SKEW_TOLERANCE_MS
    return parsed.takeIf { it in MIN_REASONABLE_MESSAGE_TIMESTAMP_MILLIS..upperBound }
}
