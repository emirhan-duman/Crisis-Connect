package com.auralis.crisisconnect.messaging

import android.content.Context
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/** One durable group epoch per binding. Network failures never select another ratchet. */
object ResilientApplicationStore {
    const val ENABLED = true // Server controls enrollment; verified before app release.
    fun profiles(): FirebaseFirestore = if (ENABLED)
        FirebaseFirestore.getInstance(MessagingDatabaseTarget.DATABASE_ID) else FirebaseFirestore.getInstance()
    fun isResilientConversation(context: Context, id: String): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        return ENABLED && context.applicationContext.getSharedPreferences("group-ha-routes-v1", Context.MODE_PRIVATE)
            .getBoolean("conversation/$uid/$id", false)
    }

    fun prefersCompatibility(context: Context, binding: AuthorityMlsBinding): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val old = AuthorityMlsIdentifiers.conversationId(binding.copy(resilient = false))
        return context.applicationContext.getSharedPreferences("group-ha-routes-v1", Context.MODE_PRIVATE)
            .getBoolean("compatibility/$uid/$old", false)
    }
    fun rememberSessionChoice(context: Context, binding: AuthorityMlsBinding, compatibility: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val old = AuthorityMlsIdentifiers.conversationId(binding.copy(resilient = false))
        context.applicationContext.getSharedPreferences("group-ha-routes-v1", Context.MODE_PRIVATE).edit()
            .putBoolean("compatibility/$uid/$old", compatibility).apply()
    }

    suspend fun resolve(context: Context, binding: AuthorityMlsBinding): AuthorityMlsBinding {
        if (!ENABLED || binding.routeResolved) return binding
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("Not signed in")
        val canonical = AuthorityMlsIdentifiers.canonicalBinding(binding)
        require(uid in canonical.participants)
        val legacy = canonical.copy(resilient = false)
        val oldId = AuthorityMlsIdentifiers.conversationId(legacy)
        val preferences = context.applicationContext.getSharedPreferences("group-ha-routes-v1", Context.MODE_PRIVATE)
        val cacheKey = "$uid/$oldId"
        if (preferences.getBoolean(cacheKey, false)) {
            val result = canonical.copy(resilient = true, routeResolved = true)
            preferences.edit().putBoolean("conversation/$uid/${AuthorityMlsIdentifiers.conversationId(result)}", true).apply()
            return result
        }
        val baseDevice = LocalKeyStorage.getOrCreateRescueDeviceId(context.applicationContext)
        val device = AuthorityMlsDeviceGenerationStore.load(context.applicationContext, uid, baseDevice, oldId)
        val result = RegionalMessagingCalls().call("openResilientGroup", mapOf(
            "binding" to mapOf("scopeType" to canonical.scopeType.wireName, "channelId" to canonical.channelId,
                "participants" to canonical.participants), "deviceId" to device,
        ), { FirebaseAuth.getInstance().currentUser?.uid == uid }) as? Map<*, *> ?: error("Malformed group route")
        val resilient = result["resilient"] as? Boolean ?: error("Missing group route")
        val resolved = canonical.copy(resilient = resilient, routeResolved = true)
        check(result["conversationId"] == AuthorityMlsIdentifiers.conversationId(resolved)) { "Group route identity mismatch" }
        if (resilient) preferences.edit().putBoolean(cacheKey, true)
            .putBoolean("conversation/$uid/${AuthorityMlsIdentifiers.conversationId(resolved)}", true).apply()
        return resolved
    }
}
