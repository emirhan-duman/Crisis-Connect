package com.auralis.crisisconnect.security

/** Prefetch/storage and receive overlap are deliberately separate from sender activation. */
data class AuthorityMeshKeyLease(val keyId: String, val keyBase64: String, val ownerUid: String,
    val notBeforeMs: Long, val expiresAtMs: Long, val receiveUntilMs: Long = expiresAtMs) {
    private fun valid(owner: String): Boolean {
        if (ownerUid != owner || notBeforeMs < 0 || notBeforeMs % PERIOD_MS != 0L ||
            notBeforeMs > Long.MAX_VALUE - PERIOD_MS - OVERLAP_MS) return false
        return keyId == "authority-v2-${notBeforeMs / PERIOD_MS}" && expiresAtMs > notBeforeMs &&
            expiresAtMs <= notBeforeMs + PERIOD_MS && receiveUntilMs >= expiresAtMs &&
            receiveUntilMs <= expiresAtMs + OVERLAP_MS
    }
    fun isCurrent(now: Long, owner: String): Boolean = valid(owner) &&
        now >= notBeforeMs && now < expiresAtMs
    fun isReceivable(now: Long, owner: String): Boolean = valid(owner) &&
        now >= notBeforeMs - OVERLAP_MS && now < receiveUntilMs
    fun isCacheable(now: Long, owner: String): Boolean = valid(owner) && now >= 0 &&
        notBeforeMs / PERIOD_MS in (now / PERIOD_MS - 1)..(now / PERIOD_MS + 1) && now < receiveUntilMs
    companion object {
        const val PERIOD_MS = 72L * 60 * 60 * 1000
        const val OVERLAP_MS = 60_000L
    }
}
