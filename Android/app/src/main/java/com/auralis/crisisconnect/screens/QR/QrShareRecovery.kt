package com.auralis.crisisconnect.screens.QR

import android.content.Context
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject

/** Only a brief share intent survives. Handshake nonces/proofs are freshly negotiated. */
internal object QrShareRecovery {
    private val lock = Any()
    private var checked = false
    private fun store(context: Context) = QrRecoveryPreferences.get(context)
    private fun owner(context: Context) = LocalKeyStorage.getOrCreateP2pSessionCode(context) + ":" +
        (FirebaseAuth.getInstance().currentUser?.uid ?: "")

    fun save(context: Context, shareId: String, code: String, name: String?, key: String, deadline: Long = 0L) = synchronized(lock) {
        checked = true
        val json = JSONObject().put("shareId", shareId).put("code", code).put("name", name ?: "")
            .put("key", key).put("owner", owner(context))
            .put("expiresAt", if (deadline > 0L) deadline else System.currentTimeMillis() + 300_000L)
        check(store(context).putStringCommitted("hosting", json.toString())) { "Share checkpoint unavailable" }
    }
    fun stop(context: Context, graceMillis: Long = 0L) = synchronized(lock) {
        checked = true
        val store = store(context)
        val raw = store.getStringStrict("hosting", "")
        if (raw.isNotEmpty() && graceMillis > 0L) {
            val json = JSONObject(raw)
            json.put("expiresAt", minOf(json.getLong("expiresAt"), System.currentTimeMillis() + graceMillis))
            check(store.putStringCommitted("hosting", json.toString()))
        } else { check(store.putStringCommitted("hosting", null)) }
    }
    fun restore(context: Context) = synchronized(lock) {
        if (checked) return@synchronized
        val store = store(context)
        val raw = store.getStringStrict("hosting", "")
        if (raw.isEmpty()) { checked = true; return@synchronized }
        val json = JSONObject(raw)
        val now = System.currentTimeMillis()
        val deadline = json.getLong("expiresAt")
        if (json.getString("owner") != owner(context) || deadline <= now || deadline - now > 300_000L) {
            check(store.putStringCommitted("hosting", null)); checked = true; return@synchronized
        }
        P2pGattServerService.startPublishing(context, json.getString("shareId"), json.getString("code"),
            json.optString("name").takeIf(String::isNotEmpty), json.getString("key"),
            recoveryDeadline = minOf(deadline, now + 30_000L))
        checked = true
    }
}
