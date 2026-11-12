package com.auralis.crisisconnect.messaging.signal

import android.content.Context
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import java.security.MessageDigest
import java.util.Base64

/** Only authenticated P256 control messages may create this immutable Signal identity pin. */
class SignalPeerBinding(context: Context) {
    private val prefs = KeystoreBackedPreferences(context, "signal_peer_binding_v1", "signal_peer_binding_v1")
    private fun scope(owner: String, peer: String, p256: String): String =
        MessageDigest.getInstance("SHA-256").digest(listOf(owner, peer, p256)
            .joinToString("") { "${it.toByteArray().size}:$it" }.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun read(owner: String, peer: String, p256: String): String? = prefs.getString(scope(owner, peer, p256))
    fun changed(owner: String, peer: String, p256: String): Boolean = prefs.contains("changed_" + scope(owner, peer, p256))
    fun migrationPending(owner: String, peer: String, p256: String): Boolean = prefs.contains("legacy_" + scope(owner, peer, p256))
    fun markMigrationPending(owner: String, peer: String, p256: String): Boolean =
        prefs.putStringCommitted("legacy_" + scope(owner, peer, p256), "1")
    fun accept(owner: String, peer: String, p256: String, signal: String): Boolean = synchronized(LOCK) {
        require(owner.isNotBlank() && peer.isNotBlank() && p256.isNotBlank())
        val bytes = Base64.getDecoder().decode(signal)
        require(bytes.size == 33)
        val canonical = Base64.getEncoder().encodeToString(bytes)
        val id = scope(owner, peer, p256)
        val previous = prefs.getString(id)
        if (previous != null && previous != canonical) {
            prefs.putString("changed_$id", "1"); return@synchronized false
        }
        if (changed(owner, peer, p256)) return@synchronized false
        if (!prefs.putStringCommitted(id, canonical)) return@synchronized false
        prefs.remove("legacy_$id"); read(owner, peer, p256) == canonical
    }
    fun reset(owner: String, peer: String, p256: String) = synchronized(LOCK) {
        val id = scope(owner, peer, p256); prefs.remove(id, "changed_$id", "sent_$id", "legacy_$id")
    }
    private companion object { val LOCK = Any() }
    fun claimAnnouncement(owner: String, peer: String, p256: String, now: Long): Boolean = synchronized(LOCK) {
        val id = "sent_" + scope(owner, peer, p256)
        if (now - (prefs.getString(id)?.toLongOrNull() ?: 0L) < 60_000L) return@synchronized false
        prefs.putString(id, now.toString()); true
    }
}
