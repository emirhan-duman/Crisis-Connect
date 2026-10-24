package com.auralis.crisisconnect.security

data class AuthorityMeshKeyLease(val keyId: String, val keyBase64: String, val ownerUid: String,
    val notBeforeMs: Long, val expiresAtMs: Long) {
    fun isCurrent(now: Long, owner: String): Boolean {
        val epoch = now / PERIOD_MS
        return ownerUid == owner && keyId == "authority-v2-$epoch" &&
            notBeforeMs == epoch * PERIOD_MS && expiresAtMs > notBeforeMs &&
            expiresAtMs <= (epoch + 1) * PERIOD_MS && now >= notBeforeMs && now < expiresAtMs
    }
    companion object { const val PERIOD_MS = 72L * 60 * 60 * 1000 }
}
