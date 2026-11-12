package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject

/** Account-bound current/next leases. A future key may be stored but never used to send early. */
object AuthorityMeshKeyStore {
    private var cached: List<AuthorityMeshKeyLease>? = null
    private var cachedContext: java.lang.ref.WeakReference<Context>? = null
    private fun owner() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    private fun decode(json: JSONObject) = AuthorityMeshKeyLease(json.getString("keyId"),
        json.getString("keyBase64"), json.getString("ownerUid"), json.getLong("notBeforeMs"),
        json.getLong("expiresAtMs"), json.optLong("receiveUntilMs", json.getLong("expiresAtMs")))
    private fun read(context: Context): List<AuthorityMeshKeyLease> {
        if (cachedContext?.get() === context.applicationContext) cached?.let { return it }
        val storage = prefs(context)
        val bundle = storage.getString(KEY_LEASES)
        if (bundle != null) {
            val array = JSONArray(bundle)
            require(array.length() <= 3)
            return (0 until array.length()).map { decode(array.getJSONObject(it)) }.also {
                cached = it; cachedContext = java.lang.ref.WeakReference(context.applicationContext)
            }
        }
        return storage.getString(KEY_LEASE)?.let { listOf(decode(JSONObject(it))) }.orEmpty().also {
            cached = it; cachedContext = java.lang.ref.WeakReference(context.applicationContext)
        }
    }
    @Synchronized fun loadGroupKey(context: Context, keyId: String? = null): ByteArray? = runCatching {
        val owner = owner(); if (owner.isBlank()) return null
        val now = System.currentTimeMillis()
        val lease = read(context).firstOrNull {
            if (keyId == null) it.isCurrent(now, owner) else it.keyId == keyId && it.isReceivable(now, owner)
        } ?: return null
        Base64.decode(lease.keyBase64, Base64.NO_WRAP).takeIf { it.size == 32 }
    }.getOrNull()
    @Synchronized fun receiveKeys(context: Context): List<ByteArray> = runCatching {
        val owner = owner(); if (owner.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        read(context).filter { it.isReceivable(now, owner) }.mapNotNull {
            Base64.decode(it.keyBase64, Base64.NO_WRAP).takeIf { bytes -> bytes.size == 32 }
        }
    }.getOrDefault(emptyList())
    @Synchronized fun hasNextLease(context: Context): Boolean = runCatching {
        val owner = owner(); if (owner.isBlank()) return false
        val now = System.currentTimeMillis()
        read(context).any { it.isCacheable(now, owner) && it.notBeforeMs / AuthorityMeshKeyLease.PERIOD_MS ==
            now / AuthorityMeshKeyLease.PERIOD_MS + 1 && Base64.decode(it.keyBase64, Base64.NO_WRAP).size == 32 }
    }.getOrDefault(false)
    @Synchronized fun saveGroupKey(context: Context, lease: AuthorityMeshKeyLease) = saveGroupKeys(context, listOf(lease))
    @Synchronized fun saveGroupKeys(context: Context, leases: List<AuthorityMeshKeyLease>) {
        val owner = owner(); val now = System.currentTimeMillis()
        require(owner.isNotBlank() && leases.isNotEmpty() && leases.size <= 2)
        require(leases.any { it.isCurrent(now, owner) })
        require(leases.all { it.isCacheable(now, owner) && Base64.decode(it.keyBase64, Base64.NO_WRAP).size == 32 })
        require(leases.map { it.keyId }.distinct().size == leases.size)
        val retained = runCatching { read(context) }.getOrDefault(emptyList())
            .filter { it.isCacheable(now, owner) && it.notBeforeMs < now }
        val merged = (retained + leases).associateBy { it.keyId }.values.sortedBy { it.notBeforeMs }.takeLast(3)
        val encoded = JSONArray()
        merged.forEach { lease -> encoded.put(JSONObject().put("keyId", lease.keyId).put("keyBase64", lease.keyBase64)
            .put("ownerUid", lease.ownerUid).put("notBeforeMs", lease.notBeforeMs)
            .put("expiresAtMs", lease.expiresAtMs).put("receiveUntilMs", lease.receiveUntilMs)) }
        prefs(context).putString(KEY_LEASES, encoded.toString())
        cached = merged; cachedContext = java.lang.ref.WeakReference(context.applicationContext)
    }
    @Synchronized fun clearGroupKey(context: Context) {
        cached = null; cachedContext = null
        prefs(context).remove(KEY_LEASES, KEY_LEASE, "authority_mesh_group_key_v1")
    }
    private fun prefs(context: Context) = KeystoreBackedPreferences(context.applicationContext,
        "authority_mesh_keys", "cc_authority_mesh_key_store")
    private const val KEY_LEASE = "authority_mesh_group_key_lease_v2"
    private const val KEY_LEASES = "authority_mesh_group_key_leases_v2"
}
