package com.auralis.crisisconnect.security

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONObject

/**
 * Signed role certificate issued by backend and cached on the device.
 *
 * Version 2 binds the certificate to the issuing device by including
 * `deviceId` in both the signing payload and the storage envelope.
 */
data class RoleCertificate(
    val ownerUid: String,
    val role: String,
    val deviceId: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val signatureBase64: String,
    val version: Int = CERTIFICATE_VERSION,
) {

    fun signatureBytes(): ByteArray = decodeBase64(signatureBase64, "signature")

    fun toStorageBytes(): ByteArray = JSONObject().apply {
        put(JSON_KEY_VERSION, version)
        put(JSON_KEY_OWNER_UID, ownerUid)
        put(JSON_KEY_ROLE, role)
        put(JSON_KEY_DEVICE_ID, deviceId)
        put(JSON_KEY_ISSUED_AT, issuedAtMillis)
        put(JSON_KEY_EXPIRES_AT, expiresAtMillis)
        put(JSON_KEY_SIGNATURE, signatureBase64)
    }.toString().toByteArray(StandardCharsets.UTF_8)

    fun signingPayload(publicKeyBase64: String): ByteArray = signingPayload(
        publicKeyBase64 = publicKeyBase64,
        ownerUid = ownerUid,
        role = role,
        deviceId = deviceId,
        issuedAtMillis = issuedAtMillis,
        expiresAtMillis = expiresAtMillis
    )

    fun isOwnedBy(userUid: String): Boolean {
        val normalizedUserUid = userUid.trim()
        return normalizedUserUid.isNotEmpty() && ownerUid == normalizedUserUid
    }

    fun isBoundTo(deviceIdToCheck: String): Boolean {
        val normalized = deviceIdToCheck.trim()
        return normalized.isNotEmpty() && deviceId == normalized
    }

    fun isValidAt(nowMillis: Long, maxClockSkewMillis: Long = DEFAULT_MAX_CLOCK_SKEW_MILLIS): Boolean {
        if (!hasValidShape()) {
            return false
        }
        val earliestValidMillis = issuedAtMillis - maxClockSkewMillis
        val latestValidMillis = expiresAtMillis + maxClockSkewMillis
        return nowMillis in earliestValidMillis..latestValidMillis
    }

    fun isUsableAt(
        nowMillis: Long,
        allowOfflineGrace: Boolean = false,
        maxClockSkewMillis: Long = DEFAULT_MAX_CLOCK_SKEW_MILLIS,
        offlineGraceMillis: Long = DEFAULT_OFFLINE_GRACE_MILLIS
    ): Boolean {
        if (!hasValidShape()) {
            return false
        }
        val earliestValidMillis = issuedAtMillis - maxClockSkewMillis
        if (nowMillis < earliestValidMillis) {
            return false
        }

        val latestValidMillis = expiresAtMillis + maxClockSkewMillis
        if (nowMillis <= latestValidMillis) {
            return true
        }
        if (!allowOfflineGrace) {
            return false
        }

        val latestUsableMillis = latestValidMillis + offlineGraceMillis.coerceAtLeast(0L)
        return nowMillis <= latestUsableMillis
    }

    fun hasValidShape(): Boolean {
        if (version != CERTIFICATE_VERSION) {
            return false
        }
        if (ownerUid.isBlank()) {
            return false
        }
        if (role !in ALLOWED_ROLES) {
            return false
        }
        if (deviceId.isBlank() || !DEVICE_ID_REGEX.matches(deviceId)) {
            return false
        }
        if (issuedAtMillis <= 0L || expiresAtMillis <= issuedAtMillis) {
            return false
        }
        return runCatching { signatureBytes().isNotEmpty() }.getOrDefault(false)
    }

    companion object {
        const val CERTIFICATE_VERSION = 2
        const val DEFAULT_MAX_CLOCK_SKEW_MILLIS = 60_000L
        const val DEFAULT_OFFLINE_GRACE_MILLIS = 0L
        val ALLOWED_ROLES: Set<String> = setOf("admin", "fieldteam")

        private val DEVICE_ID_REGEX = Regex("^cc-[0-9a-f]{24}$")
        private const val JSON_KEY_VERSION = "v"
        private const val JSON_KEY_OWNER_UID = "uid"
        private const val JSON_KEY_ROLE = "role"
        private const val JSON_KEY_DEVICE_ID = "did"
        private const val JSON_KEY_ISSUED_AT = "iat"
        private const val JSON_KEY_EXPIRES_AT = "exp"
        private const val JSON_KEY_SIGNATURE = "sig"

        fun fromStorageBytes(bytes: ByteArray): RoleCertificate? {
            if (bytes.isEmpty()) {
                return null
            }
            return runCatching {
                val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
                val certificate = RoleCertificate(
                    ownerUid = json.getString(JSON_KEY_OWNER_UID).trim(),
                    role = normalizeRoleOrNull(json.getString(JSON_KEY_ROLE))
                        ?: throw IllegalArgumentException("Stored certificate role is invalid"),
                    deviceId = json.getString(JSON_KEY_DEVICE_ID).trim(),
                    issuedAtMillis = json.getLong(JSON_KEY_ISSUED_AT),
                    expiresAtMillis = json.getLong(JSON_KEY_EXPIRES_AT),
                    signatureBase64 = json.getString(JSON_KEY_SIGNATURE).trim(),
                    version = json.optInt(JSON_KEY_VERSION, CERTIFICATE_VERSION)
                )
                require(certificate.hasValidShape()) { "Stored certificate content is invalid" }
                certificate
            }.getOrNull()
        }

        fun fromCallableResponse(data: Map<*, *>): RoleCertificate {
            val version = parseLongValue(data["certificateVersion"] ?: data["version"])
                ?.toInt()
                ?: CERTIFICATE_VERSION
            require(version == CERTIFICATE_VERSION) {
                "Unsupported certificate version $version"
            }

            val ownerUid = (data["ownerUid"] as? String)?.trim()
                ?: throw IllegalStateException("Certificate owner UID is missing")
            val role = normalizeRoleOrNull(data["role"] as? String)
                ?: throw IllegalStateException("Certificate role is missing")
            val deviceId = (data["deviceId"] as? String)?.trim()
                ?: throw IllegalStateException("Certificate deviceId is missing")
            require(DEVICE_ID_REGEX.matches(deviceId)) {
                "Certificate deviceId does not match expected format"
            }
            val issuedAtMillis = parseLongValue(data["issuedAtMs"] ?: data["issuedAt"] ?: data["iat"])
                ?: throw IllegalStateException("Certificate issuedAt timestamp is missing")
            val expiresAtMillis = parseLongValue(data["expiresAtMs"] ?: data["expiresAt"] ?: data["exp"])
                ?: throw IllegalStateException("Certificate expiry timestamp is missing")
            val signatureBase64 = (data["certificate"] ?: data["signature"]) as? String
                ?: throw IllegalStateException("Certificate signature is missing")

            val certificate = RoleCertificate(
                ownerUid = ownerUid,
                role = role,
                deviceId = deviceId,
                issuedAtMillis = issuedAtMillis,
                expiresAtMillis = expiresAtMillis,
                signatureBase64 = signatureBase64.trim(),
                version = version,
            )
            require(certificate.hasValidShape()) {
                "Backend returned an invalid role certificate"
            }
            return certificate
        }

        fun signingPayload(
            publicKeyBase64: String,
            ownerUid: String,
            role: String,
            deviceId: String,
            issuedAtMillis: Long,
            expiresAtMillis: Long,
        ): ByteArray {
            val normalizedPublicKey = publicKeyBase64.trim()
            val normalizedOwnerUid = ownerUid.trim()
            val normalizedRole = normalizeRoleOrNull(role)
                ?: throw IllegalArgumentException("Unsupported role '${role.trim()}'")
            val normalizedDeviceId = deviceId.trim()
            require(normalizedPublicKey.isNotEmpty()) { "publicKey is required" }
            require(normalizedOwnerUid.isNotEmpty()) { "ownerUid is required" }
            require(DEVICE_ID_REGEX.matches(normalizedDeviceId)) {
                "deviceId is required and must match 'cc-' + 24 hex characters"
            }
            require(issuedAtMillis > 0L) { "issuedAtMillis must be positive" }
            require(expiresAtMillis > issuedAtMillis) { "expiresAtMillis must be after issuedAtMillis" }

            val canonical = StringBuilder(
                normalizedPublicKey.length + normalizedOwnerUid.length + normalizedDeviceId.length + 64
            )
                .append(normalizedPublicKey)
                .append('|')
                .append(normalizedOwnerUid)
                .append('|')
                .append(normalizedRole)
                .append('|')
                .append(normalizedDeviceId)
                .append('|')
                .append(issuedAtMillis)
                .append('|')
                .append(expiresAtMillis)
                .toString()
            return canonical.toByteArray(StandardCharsets.UTF_8)
        }

        private fun normalizeRoleOrNull(rawRole: String?): String? {
            val normalized = rawRole?.trim()?.lowercase(Locale.US).orEmpty()
            if (normalized.isBlank()) {
                return null
            }
            return when (normalized) {
                "admin" -> "admin"
                "fieldteam", "field_team", "field-team", "ft" -> "fieldteam"
                else -> null
            }
        }

        private fun parseLongValue(raw: Any?): Long? = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }

        private fun decodeBase64(value: String, fieldName: String): ByteArray {
            require(value.isNotBlank()) { "$fieldName is required" }
            return try {
                Base64.decode(value.trim().replace("\n", ""), Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("$fieldName is not valid Base64", error)
            }
        }
    }
}
