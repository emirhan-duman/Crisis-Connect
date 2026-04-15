package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Device-side helper that builds the signed role proof packet using the keystore-backed device
 * identity and the HQ-issued certificate.
 */
class RoleProofCreator(
    private val context: Context,
    private val securityRepository: SecurityRepository = SecurityRepository(context),
    private val allowExpiredCertificate: Boolean = false,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val tag: String = "RoleProofCreator"
) {

    suspend fun createProof(sessionNonce: String? = null): RoleProofPayload = withContext(Dispatchers.IO) {
        val now = timeProvider()
        val deviceIdentity = securityRepository.getOrCreateDeviceIdentity()
        val certificateBytes = securityRepository.getStoredCertificate(
            allowExpired = allowExpiredCertificate
        )
            ?: throw MissingRoleCertificateException(
                "Rescue certificate is not cached on this device. Verify once while online."
            )
        val roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes)
            ?: throw MissingRoleCertificateException(
                "Cached rescue certificate is invalid. Verify once while online."
            )
        if (!roleCertificate.isUsableAt(nowMillis = now, allowOfflineGrace = allowExpiredCertificate)) {
            throw MissingRoleCertificateException(
                "Cached rescue certificate expired. Verify once while online."
            )
        }
        val publicKeyBase64 = Base64.encodeToString(deviceIdentity.public.encoded, Base64.NO_WRAP)
        val certificateBase64 = Base64.encodeToString(certificateBytes, Base64.NO_WRAP)
        val timestamp = now
        val normalizedSessionNonce = sessionNonce?.trim()?.takeIf { it.isNotEmpty() }
        val usingOfflineGrace = !roleCertificate.isValidAt(now)

        val signaturePayload = RoleProofPayload.signaturePayload(
            publicKey = publicKeyBase64,
            certificate = certificateBase64,
            timestamp = timestamp,
            sessionNonce = normalizedSessionNonce,
            allowExpiredCertificate = usingOfflineGrace
        )
        val signatureBytes = Crypto.signData(deviceIdentity.private, signaturePayload)
        val signatureBase64 = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)

        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, "Generated signed role proof at $timestamp")
        }
        RoleProofPayload(
            devicePublicKey = publicKeyBase64,
            certificate = certificateBase64,
            timestamp = timestamp,
            signature = signatureBase64,
            sessionNonce = normalizedSessionNonce,
            allowExpiredCertificate = usingOfflineGrace
        )
    }

    suspend fun createEncryptedPacket(
        keyBytes: ByteArray,
        sessionNonce: String? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val proof = createProof(sessionNonce = sessionNonce)
        val json = proof.toJson()
        val plaintext = json.toString().toByteArray(StandardCharsets.UTF_8)
        val encrypted = AesCipherHelper.encrypt(keyBytes, plaintext)
        AesCipherHelper.wrapForTransport(encrypted)
    }
}

class MissingRoleCertificateException(message: String) : IllegalStateException(message)

/**
 * Represents the signed role proof document prior to encryption.
 */
data class RoleProofPayload(
    val devicePublicKey: String,
    val certificate: String,
    val timestamp: Long,
    val signature: String,
    val sessionNonce: String? = null,
    val allowExpiredCertificate: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("devicePublicKey", devicePublicKey)
        put("certificate", certificate)
        put("timestamp", timestamp)
        put("signature", signature)
        sessionNonce?.let { put("sessionNonce", it) }
        put("allowExpiredCertificate", allowExpiredCertificate)
    }

    fun signaturePayloadBytes(): ByteArray = signaturePayload(
        publicKey = devicePublicKey,
        certificate = certificate,
        timestamp = timestamp,
        sessionNonce = sessionNonce,
        allowExpiredCertificate = allowExpiredCertificate
    )

    companion object {
        fun fromJson(raw: String): RoleProofPayload {
            val json = JSONObject(raw)
            return RoleProofPayload(
                devicePublicKey = json.getString("devicePublicKey"),
                certificate = json.getString("certificate"),
                timestamp = json.getLong("timestamp"),
                signature = json.getString("signature"),
                sessionNonce = json.optString("sessionNonce").trim().ifBlank { null },
                allowExpiredCertificate = json.optBoolean("allowExpiredCertificate")
            )
        }

        fun signaturePayload(
            publicKey: String,
            certificate: String,
            timestamp: Long,
            sessionNonce: String? = null,
            allowExpiredCertificate: Boolean = false
        ): ByteArray {
            val canonical = StringBuilder(publicKey.length + certificate.length + 32)
                .append(publicKey)
                .append("|")
                .append(certificate)
                .append("|")
                .append(timestamp)

            sessionNonce?.trim()?.takeIf { it.isNotEmpty() }?.let { nonce ->
                canonical.append("|").append(nonce)
            }

            if (allowExpiredCertificate) {
                canonical.append("|").append(allowExpiredCertificate)
            }

            return canonical.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }
}
