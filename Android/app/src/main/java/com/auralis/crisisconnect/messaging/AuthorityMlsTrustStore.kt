package com.auralis.crisisconnect.messaging

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

enum class AuthorityMlsTrustVerdict { FIRST, MATCH, CHANGED, MISSING }

data class AuthorityMlsTrustAssessment(
    val uid: String,
    val verdict: AuthorityMlsTrustVerdict,
    val approved: Boolean,
    val fingerprint: String,
    val safetyNumber: String,
    val deviceCommitments: List<String>,
)

/**
 * Keeps an authenticated local approval pin for each account's complete MLS device set. Firestore
 * proves only which account session wrote a directory row; first and changed sets therefore remain
 * fail-closed until the user verifies and approves the exact safety number.
 */
class AuthorityMlsTrustStore(context: Context) {
    private val appContext = context.applicationContext

    fun assess(
        conversationId: String,
        uid: String,
        devices: List<AuthorityMlsDirectoryRecord>,
    ): AuthorityMlsTrustAssessment {
        require(devices.all { it.uid == uid }) { "Authority MLS directory was grouped incorrectly." }
        val commitments = devices.map(AuthorityMlsTrust::deviceCommitment).distinct().sorted()
        if (devices.isEmpty() || commitments.size != devices.size) {
            return AuthorityMlsTrustAssessment(uid, AuthorityMlsTrustVerdict.MISSING, false, "", "", commitments)
        }
        val fingerprint = AuthorityMlsTrust.deviceSetFingerprint(commitments)
        val safetyNumber = AuthorityMlsTrust.safetyNumber(commitments)
        val key = recordKey(conversationId, uid)
        val existing = loadPin(key)
        if (existing == null) {
            save(key, PinRecord(fingerprint, commitments, false, System.currentTimeMillis()))
            return AuthorityMlsTrustAssessment(
                uid,
                AuthorityMlsTrustVerdict.FIRST,
                false,
                fingerprint,
                safetyNumber,
                commitments,
            )
        }
        val match = existing.fingerprint == fingerprint && existing.deviceCommitments == commitments
        if (!match) {
            save(key, PinRecord(fingerprint, commitments, false, System.currentTimeMillis()))
        }
        return AuthorityMlsTrustAssessment(
            uid,
            if (match) AuthorityMlsTrustVerdict.MATCH else AuthorityMlsTrustVerdict.CHANGED,
            AuthorityMlsTrust.approvalCarriesForward(existing.approved, match),
            fingerprint,
            safetyNumber,
            commitments,
        )
    }

    /** Calls fail closed unless the exact current device set was approved previously. */
    fun verifyExisting(
        conversationId: String,
        uid: String,
        devices: List<AuthorityMlsDirectoryRecord>,
    ): AuthorityMlsTrustAssessment = assess(conversationId, uid, devices)

    /** Approves only the exact canonical set currently shown to the user. */
    fun approve(
        conversationId: String,
        uid: String,
        expectedFingerprint: String,
        deviceCommitments: List<String>,
    ) {
        require(deviceCommitments.isNotEmpty() && deviceCommitments == deviceCommitments.distinct().sorted()) {
            "Authority MLS approval device set is malformed."
        }
        val fingerprint = AuthorityMlsTrust.deviceSetFingerprint(deviceCommitments)
        require(fingerprint == expectedFingerprint) { "Authority MLS approval fingerprint changed." }
        val key = recordKey(conversationId, uid)
        val existing = loadPin(key) ?: throw SecurityException("Authority MLS approval pin is missing.")
        if (existing.fingerprint != fingerprint || existing.deviceCommitments != deviceCommitments) {
            throw SecurityException("Authority MLS device set changed before approval.")
        }
        save(key, existing.copy(approved = true, verifiedAt = System.currentTimeMillis()))
    }

    private fun save(key: String, record: PinRecord) {
        synchronized(storageLock) {
            val root = loadRoot()
            root.put(key, encodeRecord(record))
            MlsStateVault.saveProtectedData(
                appContext,
                STORAGE_CONTEXT,
                root.toString().toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    private fun loadPin(key: String): PinRecord? = synchronized(storageLock) {
        val root = loadRoot()
        if (!root.has(key)) null else decodeRecord(root.getString(key))
    }

    private fun loadRoot(): JSONObject {
        val encoded = MlsStateVault.loadProtectedData(appContext, STORAGE_CONTEXT) ?: return JSONObject()
        return JSONObject(String(encoded, StandardCharsets.UTF_8))
    }

    private fun recordKey(conversationId: String, uid: String): String {
        val input = "cc-authority-mls-pin:v1:$conversationId\u0000$uid".toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(input))
    }

    private data class PinRecord(
        val fingerprint: String,
        val deviceCommitments: List<String>,
        val approved: Boolean,
        val verifiedAt: Long,
    )

    private fun encodeRecord(record: PinRecord): String = JSONObject()
        .put("fingerprint", record.fingerprint)
        .put("deviceCommitments", JSONArray(record.deviceCommitments))
        .put("approved", record.approved)
        .put("verifiedAt", record.verifiedAt)
        .toString()

    private fun decodeRecord(encoded: String): PinRecord {
        val root = JSONObject(encoded)
        val fingerprint = root.getString("fingerprint")
        val array = root.getJSONArray("deviceCommitments")
        val commitments = List(array.length()) { index -> array.getString(index) }
        val verifiedAt = root.getLong("verifiedAt")
        require(
            fingerprint.isNotBlank() &&
                commitments.isNotEmpty() &&
                commitments == commitments.distinct().sorted() &&
                fingerprint == AuthorityMlsTrust.deviceSetFingerprint(commitments) &&
                verifiedAt > 0L
        ) { "Authority MLS device pin is malformed." }
        return PinRecord(fingerprint, commitments, root.getBoolean("approved"), verifiedAt)
    }

    companion object {
        // v2 invalidates pins created by the former automatic-approval implementation.
        private const val STORAGE_CONTEXT = "authority-mls-device-pins:v2"
        private val storageLock = Any()
    }
}

object AuthorityMlsTrust {
    /** Only an already-approved, byte-for-byte matching pin may pass without a new user decision. */
    fun approvalCarriesForward(existingApproved: Boolean, exactDeviceSetMatch: Boolean): Boolean =
        existingApproved && exactDeviceSetMatch

    fun deviceCommitment(record: AuthorityMlsDirectoryRecord): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateLengthPrefixed(digest, record.credential.toByteArray(StandardCharsets.UTF_8))
        updateLengthPrefixed(digest, record.signingPublicKey)
        return base64url(digest.digest())
    }

    fun deviceSetFingerprint(commitments: List<String>): String {
        require(commitments.isNotEmpty() && commitments == commitments.distinct().sorted())
        val digest = MessageDigest.getInstance("SHA-256")
        updateLengthPrefixed(digest, "cc-authority-mls-device-set:v1".toByteArray(StandardCharsets.UTF_8))
        commitments.forEach { updateLengthPrefixed(digest, it.toByteArray(StandardCharsets.UTF_8)) }
        return base64url(digest.digest())
    }

    fun safetyNumber(commitments: List<String>): String {
        val fingerprint = deviceSetFingerprint(commitments)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("cc-authority-mls-safety:v1:$fingerprint".toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).map { (it.toInt() and 0xff).toString().padStart(3, '0') }
            .chunked(2).joinToString("  ") { it.joinToString(" ") }
    }

    private fun updateLengthPrefixed(digest: MessageDigest, bytes: ByteArray) {
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun base64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
