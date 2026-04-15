package com.auralis.crisisconnect.service

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.Locale

object BleChatEnvelope {
    private const val PREFIX = "CCMSG1"
    private const val TYPE_CHAT = "CHAT"
    private const val TYPE_DELIVERED = "DELIVERED"
    private const val TYPE_READ = "READ"
    private const val TYPE_DECRYPT_FAIL = "DECRYPT_FAIL"
    private const val FIELD_SEPARATOR = "|"
    private const val LIST_SEPARATOR = ","
    private const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE
    private const val MAX_ACK_IDS_PAYLOAD_BYTES = 180

    enum class AckType {
        DELIVERED,
        READ,
        DECRYPT_FAIL
    }

    data class ChatPayload(
        val messageId: String,
        val text: String,
        val createdAtMillis: Long,
        val ttlMillis: Long,
        val attempt: Int,
        val route: String
    )

    data class AckPayload(
        val type: AckType,
        val messageIds: List<String>
    )

    fun encodeChat(
        messageId: String,
        text: String,
        createdAtMillis: Long,
        ttlMillis: Long,
        attempt: Int,
        route: String
    ): String {
        val cleanId = messageId.trim()
        val safeAttempt = attempt.coerceAtLeast(1)
        val safeTtl = ttlMillis.coerceAtLeast(1L)
        val safeRoute = sanitizeRoute(route)
        val encodedText = Base64.encodeToString(
            text.toByteArray(StandardCharsets.UTF_8),
            BASE64_FLAGS
        )
        return listOf(
            PREFIX,
            TYPE_CHAT,
            cleanId,
            createdAtMillis.toString(),
            safeTtl.toString(),
            safeAttempt.toString(),
            safeRoute,
            encodedText
        ).joinToString(FIELD_SEPARATOR)
    }

    fun decodeChat(raw: String): ChatPayload? {
        val parts = raw.split(FIELD_SEPARATOR, limit = 8)
        if (parts.size < 8) {
            return null
        }
        if (parts[0] != PREFIX || parts[1] != TYPE_CHAT) {
            return null
        }
        val messageId = parts[2].trim().takeIf { it.isNotEmpty() } ?: return null
        val createdAt = parts[3].toLongOrNull() ?: return null
        val ttl = parts[4].toLongOrNull()?.coerceAtLeast(1L) ?: return null
        val attempt = parts[5].toIntOrNull()?.coerceAtLeast(1) ?: return null
        val route = sanitizeRoute(parts[6])
        val decodedBytes = runCatching {
            Base64.decode(parts[7], BASE64_FLAGS)
        }.getOrNull() ?: return null
        val text = runCatching {
            decodedBytes.toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return ChatPayload(
            messageId = messageId,
            text = text,
            createdAtMillis = createdAt,
            ttlMillis = ttl,
            attempt = attempt,
            route = route
        )
    }

    fun isExpired(payload: ChatPayload, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiry = payload.createdAtMillis + payload.ttlMillis
        return nowMillis > expiry
    }

    fun encodeDeliveredAck(messageId: String?): ByteArray {
        val normalized = normalizeMessageIds(listOfNotNull(messageId)).firstOrNull()
        val payload = if (normalized == null) {
            TYPE_DELIVERED
        } else {
            "$TYPE_DELIVERED$FIELD_SEPARATOR$normalized"
        }
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    fun encodeDecryptFailAck(messageId: String?): ByteArray {
        val normalized = normalizeMessageIds(listOfNotNull(messageId)).firstOrNull()
        val payload = if (normalized == null) {
            TYPE_DECRYPT_FAIL
        } else {
            "$TYPE_DECRYPT_FAIL$FIELD_SEPARATOR$normalized"
        }
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    fun encodeReadAck(messageIds: Collection<String>): ByteArray {
        val ids = normalizeMessageIds(messageIds).fold(mutableListOf<String>()) { acc, id ->
            val candidate = if (acc.isEmpty()) {
                id
            } else {
                "${acc.joinToString(LIST_SEPARATOR)}$LIST_SEPARATOR$id"
            }
            if (candidate.toByteArray(StandardCharsets.UTF_8).size <= MAX_ACK_IDS_PAYLOAD_BYTES) {
                acc += id
            }
            acc
        }
        val payload = if (ids.isEmpty()) {
            TYPE_READ
        } else {
            "$TYPE_READ$FIELD_SEPARATOR${ids.joinToString(LIST_SEPARATOR)}"
        }
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeAck(bytes: ByteArray): AckPayload? {
        if (bytes.isEmpty()) {
            return null
        }
        val text = bytes.toString(StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) {
            return null
        }
        if (text.equals(TYPE_DELIVERED, ignoreCase = true)) {
            return AckPayload(AckType.DELIVERED, emptyList())
        }
        if (text.equals(TYPE_READ, ignoreCase = true)) {
            return AckPayload(AckType.READ, emptyList())
        }
        if (text.equals(TYPE_DECRYPT_FAIL, ignoreCase = true)) {
            return AckPayload(AckType.DECRYPT_FAIL, emptyList())
        }
        val parts = text.split(FIELD_SEPARATOR, limit = 2)
        if (parts.size != 2) {
            return null
        }
        val ids = normalizeMessageIds(parts[1].split(LIST_SEPARATOR))
        return when (parts[0].uppercase(Locale.US)) {
            TYPE_DELIVERED -> AckPayload(AckType.DELIVERED, ids)
            TYPE_READ -> AckPayload(AckType.READ, ids)
            TYPE_DECRYPT_FAIL -> AckPayload(AckType.DECRYPT_FAIL, ids)
            else -> null
        }
    }

    private fun normalizeMessageIds(messageIds: Collection<String>): List<String> {
        return messageIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(64)
            .toList()
    }

    private fun sanitizeRoute(route: String): String {
        val normalized = route.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) {
            return "unknown"
        }
        return buildString(normalized.length) {
            normalized.forEach { ch ->
                val allowed = ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'
                append(if (allowed) ch else '_')
            }
        }.ifEmpty { "unknown" }
    }
}
