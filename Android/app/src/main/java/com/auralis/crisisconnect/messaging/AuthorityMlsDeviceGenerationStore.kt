package com.auralis.crisisconnect.messaging

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * A stable per-conversation MLS leaf generation, independent from the device-wide attestation ID.
 * Rotating this value abandons a poisoned sender ratchet without changing rescue certificates,
 * calls, sync, or other conversations.
 */
object AuthorityMlsDeviceGenerationStore {
    private const val VERSION = 1
    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val random = SecureRandom()
    private val lock = Any()

    fun load(
        context: Context,
        accountUid: String,
        baseDeviceId: String,
        conversationId: String,
    ): String = synchronized(lock) {
        val storageContext = storageContext(accountUid, baseDeviceId, conversationId)
        val encoded = MlsStateVault.loadProtectedData(context.applicationContext, storageContext)
            ?: return@synchronized baseDeviceId
        decode(encoded, baseDeviceId).activeDeviceId
    }

    /** Compare-and-swap rotation makes concurrent recovery attempts converge on one fresh leaf. */
    fun rotate(
        context: Context,
        accountUid: String,
        baseDeviceId: String,
        conversationId: String,
        expectedDeviceId: String,
    ): String = synchronized(lock) {
        requireValidDeviceId(expectedDeviceId)
        val storageContext = storageContext(accountUid, baseDeviceId, conversationId)
        val stored = MlsStateVault.loadProtectedData(context.applicationContext, storageContext)
        val current = stored?.let { decode(it, baseDeviceId) } ?: Record(baseDeviceId, 0)
        if (current.activeDeviceId != expectedDeviceId) return@synchronized current.activeDeviceId
        persistRotation(
            context.applicationContext,
            storageContext,
            baseDeviceId,
            current.joinRecoveryPolicyVersion,
        )
    }

    /**
     * Rotates at most once for a specific join-recovery policy. This recovers a KeyPackage that was
     * immutably published but skipped by every online leaf under an older sponsor policy. The old
     * authenticated directory entry remains as audit history and its sender ratchet is never reused.
     */
    fun rotateForStalledJoin(
        context: Context,
        accountUid: String,
        baseDeviceId: String,
        conversationId: String,
        expectedDeviceId: String,
        recoveryPolicyVersion: Int,
    ): String = synchronized(lock) {
        require(recoveryPolicyVersion > 0)
        requireValidDeviceId(expectedDeviceId)
        val storageContext = storageContext(accountUid, baseDeviceId, conversationId)
        val stored = MlsStateVault.loadProtectedData(context.applicationContext, storageContext)
        val current = stored?.let { decode(it, baseDeviceId) } ?: Record(baseDeviceId, 0)
        if (current.activeDeviceId != expectedDeviceId ||
            current.joinRecoveryPolicyVersion >= recoveryPolicyVersion) {
            return@synchronized current.activeDeviceId
        }
        persistRotation(
            context.applicationContext,
            storageContext,
            baseDeviceId,
            recoveryPolicyVersion,
        )
    }

    private fun persistRotation(
        context: Context,
        storageContext: String,
        baseDeviceId: String,
        joinRecoveryPolicyVersion: Int,
    ): String {

        val suffix = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(16).also(random::nextBytes))
        val activeDeviceId = "${baseDeviceId}_r_$suffix".also(::requireValidDeviceId)
        val record = JSONObject()
            .put("version", VERSION)
            .put("baseDeviceId", baseDeviceId)
            .put("activeDeviceId", activeDeviceId)
            .put("joinRecoveryPolicyVersion", joinRecoveryPolicyVersion)
            .put("rotatedAt", System.currentTimeMillis())
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        MlsStateVault.saveProtectedData(context, storageContext, record)
        return activeDeviceId
    }

    private fun decode(encoded: ByteArray, baseDeviceId: String): Record {
        val root = JSONObject(String(encoded, StandardCharsets.UTF_8))
        val storedBase = root.getString("baseDeviceId")
        val active = root.getString("activeDeviceId")
        val joinRecoveryPolicyVersion = root.optInt("joinRecoveryPolicyVersion", 0)
        val rotatedAt = root.getLong("rotatedAt")
        require(
            root.getInt("version") == VERSION &&
                storedBase == baseDeviceId &&
                DEVICE_ID.matches(active) &&
                (active == baseDeviceId || active.startsWith("${baseDeviceId}_r_")) &&
                joinRecoveryPolicyVersion >= 0 &&
                rotatedAt > 0L
        ) { "Stored Authority MLS device generation is invalid." }
        return Record(active, joinRecoveryPolicyVersion)
    }

    private fun storageContext(accountUid: String, baseDeviceId: String, conversationId: String): String {
        val uid = accountUid.trim()
        val base = baseDeviceId.trim().also(::requireValidDeviceId)
        val conversation = conversationId.trim()
        require(uid.isNotEmpty() && conversation.isNotEmpty()) {
            "Authority MLS device-generation context is invalid."
        }
        return "authority-mls-device-generation:v1:$uid:$base:$conversation"
    }

    private fun requireValidDeviceId(value: String) {
        require(DEVICE_ID.matches(value)) { "Authority MLS device generation is invalid." }
    }

    private data class Record(
        val activeDeviceId: String,
        val joinRecoveryPolicyVersion: Int,
    )
}
