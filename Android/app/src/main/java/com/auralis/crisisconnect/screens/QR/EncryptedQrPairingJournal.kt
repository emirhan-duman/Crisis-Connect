package com.auralis.crisisconnect.screens.QR

import android.content.Context
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import org.json.JSONObject

/** QR secrets are encrypted by the device Keystore. Backup/transfer excludes this preference. */
internal class EncryptedQrPairingJournal(context: Context) : QrPairingJournal {
    private val store = QrRecoveryPreferences.get(context)
    override fun read(): QrPairingCheckpoint? {
        val text = store.getStringStrict("pending_v3", "")
        if (text.isEmpty()) return null
        val json = JSONObject(text)
        return QrPairingCheckpoint(json.getString("raw"), json.optString("name").takeIf(String::isNotEmpty),
            json.getString("owner"), json.getLong("createdAt"), json.optInt("recoveries"),
            json.optString("savedSession").takeIf(String::isNotEmpty))
    }
    override fun write(checkpoint: QrPairingCheckpoint) {
        val json = JSONObject().put("raw", checkpoint.raw).put("name", checkpoint.name ?: "")
            .put("owner", checkpoint.owner).put("createdAt", checkpoint.createdAt)
            .put("recoveries", checkpoint.recoveries).put("savedSession", checkpoint.savedSession ?: "")
        check(store.putStringCommitted("pending_v3", json.toString())) { "Pairing checkpoint unavailable" }
    }
    override fun clear() {
        check(store.putStringCommitted("pending_v3", null)) { "Pairing checkpoint unavailable" }
    }
}

/** Sharing and scanning use one key resolver, including their first concurrent write. */
internal object QrRecoveryPreferences {
    @Volatile private var instance: KeystoreBackedPreferences? = null
    fun get(context: Context): KeystoreBackedPreferences = instance ?: synchronized(this) {
        instance ?: KeystoreBackedPreferences(context.applicationContext, "qr_pairing_recovery", "qr_pairing_recovery_key")
            .also { instance = it }
    }
}
