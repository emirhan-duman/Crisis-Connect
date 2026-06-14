package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64

/**
 * Stores the authority-only mesh group key in app-private encrypted preferences.
 *
 * The key is intentionally not auto-generated here. Authority mesh should stay disabled until a
 * backend/provisioning flow writes a valid 256-bit group key for an authorized device.
 */
object AuthorityMeshKeyStore {
    fun loadGroupKey(context: Context): ByteArray? {
        val encoded = prefs(context).getString(KEY_GROUP_KEY, null)?.trim() ?: return null
        val decoded = runCatching {
            Base64.decode(encoded.replace("\n", ""), Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        return decoded.takeIf { it.size == GROUP_KEY_BYTES }
    }

    fun saveGroupKey(context: Context, keyBytes: ByteArray) {
        require(keyBytes.size == GROUP_KEY_BYTES) { "Authority mesh group key must be 32 bytes." }
        prefs(context).putString(KEY_GROUP_KEY, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
    }

    fun clearGroupKey(context: Context) {
        prefs(context).remove(KEY_GROUP_KEY)
    }

    private fun prefs(context: Context): KeystoreBackedPreferences {
        return KeystoreBackedPreferences(
            context = context.applicationContext,
            prefName = PREF_NAME,
            keyAlias = KEY_ALIAS
        )
    }

    private const val PREF_NAME = "authority_mesh_keys"
    private const val KEY_ALIAS = "cc_authority_mesh_key_store"
    private const val KEY_GROUP_KEY = "authority_mesh_group_key_v1"
    private const val GROUP_KEY_BYTES = 32
}
