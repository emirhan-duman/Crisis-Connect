package com.auralis.crisisconnect.messaging

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic conversation id shared by both peers of an internet chat. Derived from the two
 * Firebase uids in an order-independent way, so each side computes the same id without a round
 * trip. It becomes the local `sessionCode` of the contact AND the `conversationId` bound into the
 * E2E envelope (HKDF info + AEAD), so both peers derive the same key and group the same thread.
 */
object InternetConversation {
    fun pairId(uidA: String, uidB: String): String {
        val sorted = listOf(uidA.trim(), uidB.trim()).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("crisisconnect:conv:v1:${sorted[0]}:${sorted[1]}".toByteArray(StandardCharsets.UTF_8))
        return "i" + Base64.encodeToString(
            digest,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        ).take(24)
    }
}
