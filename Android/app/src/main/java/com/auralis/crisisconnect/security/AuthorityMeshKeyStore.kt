package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Only a current, account-bound 72-hour lease may supply authority traffic keys. */
object AuthorityMeshKeyStore {
    @Volatile private var cached: AuthorityMeshKeyLease? = null
    @Volatile private var cachedContext: WeakReference<Context>? = null
    @Synchronized fun loadGroupKey(context: Context): ByteArray? = runCatching {
        val owner = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val lease = cached.takeIf { cachedContext?.get() === context.applicationContext } ?: run {
            val encoded = prefs(context).getString(KEY_LEASE) ?: return null
            val json = JSONObject(encoded)
            AuthorityMeshKeyLease(json.getString("keyId"), json.getString("keyBase64"), json.getString("ownerUid"),
                json.getLong("notBeforeMs"), json.getLong("expiresAtMs")).also {
                cached = it; cachedContext = WeakReference(context.applicationContext)
            }
        }
        if (!lease.isCurrent(System.currentTimeMillis(), owner)) return null
        Base64.decode(lease.keyBase64, Base64.NO_WRAP).takeIf { it.size == 32 }
    }.getOrNull()

    @Synchronized fun saveGroupKey(context: Context, lease: AuthorityMeshKeyLease) {
        require(lease.isCurrent(System.currentTimeMillis(), FirebaseAuth.getInstance().currentUser?.uid.orEmpty()))
        require(Base64.decode(lease.keyBase64, Base64.NO_WRAP).size == 32)
        val encoded = JSONObject().put("keyId", lease.keyId).put("keyBase64", lease.keyBase64)
            .put("ownerUid", lease.ownerUid).put("notBeforeMs", lease.notBeforeMs).put("expiresAtMs", lease.expiresAtMs)
        prefs(context).putString(KEY_LEASE, encoded.toString())
        cached = lease; cachedContext = WeakReference(context.applicationContext)
    }
    @Synchronized fun clearGroupKey(context: Context) {
        cached = null; cachedContext = null
        prefs(context).remove(KEY_LEASE, "authority_mesh_group_key_v1")
    }
    private fun prefs(context: Context) = KeystoreBackedPreferences(context.applicationContext,
        "authority_mesh_keys", "cc_authority_mesh_key_store")
    private const val KEY_LEASE = "authority_mesh_group_key_lease_v2"
}
