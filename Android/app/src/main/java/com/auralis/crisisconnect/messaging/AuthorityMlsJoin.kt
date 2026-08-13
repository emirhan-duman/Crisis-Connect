package com.auralis.crisisconnect.messaging

import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal enum class AuthorityMlsPreJoinControlDisposition {
    WELCOME,
    SKIP,
    INVALID,
}

internal data class AuthorityMlsControlOrdering(
    val type: String,
    val applicationSequenceBoundary: Long?,
)

/** True only for this device's authenticated KeyPackage envelope. */
internal fun isAuthorityMlsLocalKeyPackage(
    payload: String,
    senderCredential: String,
    senderUid: String,
): Boolean {
    val envelope = parseAuthorityMlsEnvelope(payload) ?: return false
    return envelope.optString("type") == "shareKeyPackage" &&
        envelope.optString("senderId") == senderCredential &&
        envelope.optString("senderUid") == senderUid
}

/**
 * A fresh leaf was not a member of epochs before its own Welcome. Those authenticated events only
 * advance its durable relay cursor; the one Welcome addressed to this exact credential is applied.
 */
internal fun classifyAuthorityMlsPreJoinControl(
    payload: String,
    eventSenderCredential: String,
    eventSenderUid: String,
    localCredential: String,
): AuthorityMlsPreJoinControlDisposition {
    val envelope = parseAuthorityMlsEnvelope(payload)
        ?: return AuthorityMlsPreJoinControlDisposition.INVALID
    if (envelope.optString("senderId") != eventSenderCredential ||
        envelope.optString("senderUid") != eventSenderUid) {
        return AuthorityMlsPreJoinControlDisposition.INVALID
    }
    return when (envelope.optString("type")) {
        "sendMlsWelcome" -> {
            val recipient = envelope.optString("recipientId")
            when {
                recipient.isBlank() -> AuthorityMlsPreJoinControlDisposition.INVALID
                recipient == localCredential -> AuthorityMlsPreJoinControlDisposition.WELCOME
                else -> AuthorityMlsPreJoinControlDisposition.SKIP
            }
        }
        "shareKeyPackage", "sendMlsMessage" -> AuthorityMlsPreJoinControlDisposition.SKIP
        else -> AuthorityMlsPreJoinControlDisposition.INVALID
    }
}

/**
 * Exactly one pre-existing verified device sponsors a new device join. The preparation service
 * wakes the other participant, so prefer that participant's established leaf and fall back to the
 * same account only when the peer has no roster leaf. Sorting mirrors the browser implementation.
 */
internal fun authorityMlsJoinSponsor(
    rosterCredentials: List<String>,
    ownerByCredential: Map<String, String>,
    joiningUid: String,
    joiningCredential: String,
): String? {
    if (joiningUid.isBlank() || joiningCredential.isBlank() || rosterCredentials.isEmpty() ||
        rosterCredentials.toSet().size != rosterCredentials.size ||
        rosterCredentials.any { it.isBlank() || it !in ownerByCredential }
    ) return null
    val established = rosterCredentials.filter { it != joiningCredential }
    return established
        .filter { ownerByCredential[it] != joiningUid }
        .minWithOrNull(::compareAuthorityMlsUtf8)
        ?: established
            .filter { ownerByCredential[it] == joiningUid }
            .minWithOrNull(::compareAuthorityMlsUtf8)
}

internal fun authorityMlsControlOrdering(payload: String): AuthorityMlsControlOrdering? {
    val envelope = parseAuthorityMlsEnvelope(payload) ?: return null
    val type = envelope.optString("type")
    if (type !in setOf("shareKeyPackage", "sendMlsWelcome", "sendMlsMessage")) return null
    if (type == "shareKeyPackage") {
        return if (!envelope.has("applicationSequenceBoundary")) {
            AuthorityMlsControlOrdering(type, null)
        } else {
            null
        }
    }
    val raw = envelope.opt("applicationSequenceBoundary") as? Number ?: return null
    val boundary = raw.toLong()
    if (raw.toDouble() != boundary.toDouble() || boundary !in 0 until 9_007_199_254_740_991L) return null
    return AuthorityMlsControlOrdering(type, boundary)
}

private fun parseAuthorityMlsEnvelope(payload: String): JSONObject? =
    runCatching { JSONObject(payload) }.getOrNull()

private fun compareAuthorityMlsUtf8(left: String, right: String): Int {
    val a = left.toByteArray(StandardCharsets.UTF_8)
    val b = right.toByteArray(StandardCharsets.UTF_8)
    for (index in 0 until minOf(a.size, b.size)) {
        val compared = (a[index].toInt() and 0xff).compareTo(b[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return a.size.compareTo(b.size)
}
