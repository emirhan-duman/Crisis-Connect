package com.auralis.crisisconnect.data.database

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import com.auralis.crisisconnect.security.RoleCertificate
import java.security.SecureRandom
import java.util.Locale

object LocalKeyStorage {

    private const val TAG = "LocalKeyStorage"
    private const val PREF_NAME = "crisisconnect_secure_prefs_v2"
    private const val LEGACY_PREF_NAME = "crisisconnect_secure_prefs"
    private const val KEYSET_PREF_NAME = "__androidx_security_crypto_encrypted_prefs__"
    private const val MASTER_KEY_ALIAS = "cc_secure_prefs_master_v2"
    private const val MIGRATION_MARKER_KEY = "__migration_complete_v2"
    private const val AES_KEY = "local_aes_key"
    private const val SQLCIPHER_KEY = "local_sqlcipher_key"
    private const val UID_KEY = "local_uid"
    private const val COUNTRY_KEY = "local_country"
    private const val COUNTRY_SOURCE_KEY = "local_country_source"
    private const val VPN_KEY = "local_vpn"
    private const val ROLE_KEY = "local_role"
    private const val ROLE_UID_KEY = "local_role_uid"
    private const val ROLE_CACHE_SCHEMA_VERSION_KEY = "local_role_cache_schema_version"
    private const val ROLE_SAVED_AT_KEY = "local_role_saved_at"
    private const val RESCUE_DEVICE_ID_KEY = "local_rescue_device_id"
    private const val RESCUE_DEVICE_OWNER_UID_KEY = "local_rescue_device_owner_uid"
    private const val P2P_DEVICE_ID_KEY = "local_p2p_device_id"
    private const val P2P_SESSION_CODE_KEY = "local_p2p_session_code"
    private const val ROLE_CACHE_SCHEMA_VERSION = 2
    private const val ROLE_CACHE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val RESCUE_DEVICE_ID_HEX_LENGTH = 24
    private const val RESCUE_DEVICE_ID_BYTE_LENGTH = 12
    private const val P2P_DEVICE_ID_HEX_LENGTH = 24
    private const val P2P_DEVICE_ID_BYTE_LENGTH = 12
    private const val P2P_SESSION_CODE_LENGTH = 6
    private val secureRandom = SecureRandom()
    private val migratedStringKeys = listOf(
        AES_KEY,
        SQLCIPHER_KEY,
        UID_KEY,
        COUNTRY_KEY,
        COUNTRY_SOURCE_KEY,
        VPN_KEY,
        ROLE_KEY,
        ROLE_UID_KEY,
        RESCUE_DEVICE_ID_KEY,
        RESCUE_DEVICE_OWNER_UID_KEY,
        P2P_DEVICE_ID_KEY,
        P2P_SESSION_CODE_KEY
    )

    @Volatile
    private var migrationChecked = false

    private fun prefs(context: Context): KeystoreBackedPreferences {
        val securePrefs = KeystoreBackedPreferences(
            context = context.applicationContext,
            prefName = PREF_NAME,
            keyAlias = MASTER_KEY_ALIAS
        )
        ensureMigrated(context.applicationContext, securePrefs)
        return securePrefs
    }

    private fun ensureMigrated(
        context: Context,
        securePrefs: KeystoreBackedPreferences
    ) {
        if (migrationChecked) {
            return
        }
        synchronized(this) {
            if (migrationChecked) {
                return
            }
            if (securePrefs.getString(MIGRATION_MARKER_KEY, null) == "1") {
                migrationChecked = true
                return
            }
            migrateLegacyPrefs(context, securePrefs)
            securePrefs.putString(MIGRATION_MARKER_KEY, "1")
            migrationChecked = true
        }
    }

    private fun migrateLegacyPrefs(
        context: Context,
        securePrefs: KeystoreBackedPreferences
    ) {
        val legacyPrefs = openLegacyEncryptedPrefs(context) ?: return
        runCatching {
            migratedStringKeys.forEach { key ->
                legacyPrefs.getString(key, null)?.let { value ->
                    securePrefs.putString(key, value)
                }
            }
            if (legacyPrefs.contains(ROLE_CACHE_SCHEMA_VERSION_KEY)) {
                securePrefs.putInt(
                    ROLE_CACHE_SCHEMA_VERSION_KEY,
                    legacyPrefs.getInt(ROLE_CACHE_SCHEMA_VERSION_KEY, 0)
                )
            }
            if (legacyPrefs.contains(ROLE_SAVED_AT_KEY)) {
                securePrefs.putLong(
                    ROLE_SAVED_AT_KEY,
                    legacyPrefs.getLong(ROLE_SAVED_AT_KEY, 0L)
                )
            }
            context.deleteSharedPreferences(LEGACY_PREF_NAME)
            context.deleteSharedPreferences(KEYSET_PREF_NAME)
            Log.i(TAG, "Migrated legacy encrypted preferences to keystore-backed storage")
        }.onFailure { throwable ->
            Log.w(TAG, "Legacy encrypted preferences migration failed", throwable)
        }
    }

    @Suppress("DEPRECATION")
    private fun openLegacyEncryptedPrefs(context: Context): SharedPreferences? {
        return runCatching {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                LEGACY_PREF_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure { throwable ->
            Log.d(TAG, "No readable legacy encrypted prefs found for migration", throwable)
        }.getOrNull()
    }

    fun getOrCreateAesKey(context: Context): String {
        val securePrefs = prefs(context)
        val existing = securePrefs.getString(AES_KEY, null)?.takeIf { encoded ->
            val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            decoded?.size == 32
        }
        return if (existing != null) {
            existing
        } else {
            val newKey = KeyGeneratorUtil.generateAesKeyBase64()
            securePrefs.putString(AES_KEY, newKey)
            newKey
        }
    }

    fun getOrCreateSqlCipherKey(context: Context): String {
        val securePrefs = prefs(context)
        canonicalizeAesKey(securePrefs.getString(SQLCIPHER_KEY, null))?.let { existing ->
            if (securePrefs.getString(SQLCIPHER_KEY, null) != existing) {
                securePrefs.putString(SQLCIPHER_KEY, existing)
            }
            return existing
        }

        val legacyAesKey = canonicalizeAesKey(securePrefs.getString(AES_KEY, null))
        val sqlCipherKey = legacyAesKey ?: KeyGeneratorUtil.generateAesKeyBase64()
        securePrefs.putString(SQLCIPHER_KEY, sqlCipherKey)
        return sqlCipherKey
    }

    fun saveAesKey(context: Context, base64Key: String) {
        val sanitized = base64Key.trim()
        if (sanitized.isEmpty()) {
            return
        }

        val decoded = runCatching {
            Base64.decode(sanitized, Base64.DEFAULT)
        }.getOrNull()

        if (decoded == null || (decoded.size != 16 && decoded.size != 32)) {
            Log.w(TAG, "Ignoring invalid AES key from remote state")
            return
        }

        val canonical = Base64.encodeToString(decoded, Base64.NO_WRAP)
        prefs(context).putString(AES_KEY, canonical)
    }

    private fun canonicalizeAesKey(value: String?): String? {
        val sanitized = value?.trim().orEmpty()
        if (sanitized.isEmpty()) {
            return null
        }
        val decoded = runCatching {
            Base64.decode(sanitized, Base64.DEFAULT)
        }.getOrNull() ?: return null

        if (decoded.size != 16 && decoded.size != 32) {
            return null
        }
        return Base64.encodeToString(decoded, Base64.NO_WRAP)
    }

    fun saveUid(context: Context, uid: String) {
        val securePrefs = prefs(context)
        val previousUid = securePrefs.getString(UID_KEY, null)
        securePrefs.putString(UID_KEY, uid)
        if (!previousUid.isNullOrBlank() && previousUid != uid) {
            securePrefs.remove(
                ROLE_KEY,
                ROLE_UID_KEY,
                ROLE_CACHE_SCHEMA_VERSION_KEY,
                ROLE_SAVED_AT_KEY,
                RESCUE_DEVICE_ID_KEY,
                RESCUE_DEVICE_OWNER_UID_KEY
            )
        }
    }

    fun getSavedUid(context: Context): String? {
        return prefs(context).getString(UID_KEY, null)
    }

    fun clearUid(context: Context) {
        prefs(context).remove(
            UID_KEY,
            ROLE_KEY,
            ROLE_UID_KEY,
            ROLE_CACHE_SCHEMA_VERSION_KEY,
            ROLE_SAVED_AT_KEY
        )
    }

    fun saveCountry(context: Context, country: String) {
        val securePrefs = prefs(context)
        securePrefs.putString(COUNTRY_KEY, country)
        securePrefs.remove(COUNTRY_SOURCE_KEY, VPN_KEY)
    }

    fun getSavedCountry(context: Context): Triple<String?, String?, Boolean> {
        val securePrefs = prefs(context)
        val country = securePrefs.getString(COUNTRY_KEY, null)
        val source = "locale"
        val vpn = false
        return Triple(country, source, vpn)
    }

    fun saveRole(context: Context, role: String) {
        val normalizedRole = role.trim().lowercase(Locale.US)
        if (normalizedRole.isEmpty()) {
            return
        }
        val securePrefs = prefs(context)
        if (normalizedRole !in RoleCertificate.ALLOWED_ROLES) {
            Log.w(TAG, "Ignoring unsupported role '$normalizedRole'")
            return
        }
        val uid = securePrefs.getString(UID_KEY, null)
        if (uid.isNullOrBlank()) {
            Log.w(TAG, "Ignoring role cache because no UID is available")
            return
        }
        securePrefs.putString(ROLE_KEY, normalizedRole)
        securePrefs.putString(ROLE_UID_KEY, uid)
        securePrefs.putInt(ROLE_CACHE_SCHEMA_VERSION_KEY, ROLE_CACHE_SCHEMA_VERSION)
        securePrefs.putLong(ROLE_SAVED_AT_KEY, System.currentTimeMillis())
    }

    fun getSavedRole(context: Context): String? {
        val securePrefs = prefs(context)
        val schemaVersion = securePrefs.getInt(ROLE_CACHE_SCHEMA_VERSION_KEY, 0)
        if (schemaVersion != ROLE_CACHE_SCHEMA_VERSION) {
            clearRole(context)
            return null
        }
        val role = securePrefs.getString(ROLE_KEY, null)?.trim()?.lowercase(Locale.US) ?: return null
        val savedAt = securePrefs.getLong(ROLE_SAVED_AT_KEY, 0L)
        if (savedAt == 0L || System.currentTimeMillis() - savedAt > ROLE_CACHE_MAX_AGE_MILLIS) {
            clearRole(context)
            return null
        }
        if (role !in RoleCertificate.ALLOWED_ROLES) {
            clearRole(context)
            return null
        }
        val currentUid = securePrefs.getString(UID_KEY, null)
        val roleUid = securePrefs.getString(ROLE_UID_KEY, null)
        if (currentUid.isNullOrBlank() || roleUid.isNullOrBlank() || currentUid != roleUid) {
            clearRole(context)
            return null
        }
        return role
    }

    fun clearRole(context: Context) {
        prefs(context).remove(
            ROLE_KEY,
            ROLE_UID_KEY,
            ROLE_CACHE_SCHEMA_VERSION_KEY,
            ROLE_SAVED_AT_KEY
        )
    }

    /**
     * Returns a stable, offline-generated rescue device ID in `cc-<24 hex>` format.
     */
    fun getOrCreateRescueDeviceId(context: Context): String {
        val securePrefs = prefs(context)
        normalizeRescueDeviceId(securePrefs.getString(RESCUE_DEVICE_ID_KEY, null))?.let { existing ->
            return existing
        }
        val generated = createRescueDeviceId()
        securePrefs.putString(RESCUE_DEVICE_ID_KEY, generated)
        return generated
    }

    fun rotateRescueDeviceId(context: Context, ownerUid: String? = null): String {
        val generated = createRescueDeviceId()
        val securePrefs = prefs(context)
        securePrefs.putString(RESCUE_DEVICE_ID_KEY, generated)
        val normalizedOwnerUid = ownerUid?.trim().orEmpty()
        if (normalizedOwnerUid.isNotBlank()) {
            securePrefs.putString(RESCUE_DEVICE_OWNER_UID_KEY, normalizedOwnerUid)
        } else {
            securePrefs.remove(RESCUE_DEVICE_OWNER_UID_KEY)
        }
        return generated
    }

    fun getRescueDeviceOwnerUid(context: Context): String? {
        return prefs(context).getString(RESCUE_DEVICE_OWNER_UID_KEY, null)?.trim()?.takeIf {
            it.isNotEmpty()
        }
    }

    fun markRescueDeviceOwner(context: Context, ownerUid: String) {
        val normalizedOwnerUid = ownerUid.trim()
        if (normalizedOwnerUid.isBlank()) {
            return
        }
        prefs(context).putString(RESCUE_DEVICE_OWNER_UID_KEY, normalizedOwnerUid)
    }

    /**
     * Converts a `cc-<24 hex>` rescue device ID into its 12-byte binary payload.
     */
    fun decodeRescueDeviceIdToBytes(deviceId: String): ByteArray? {
        val normalized = normalizeRescueDeviceId(deviceId) ?: return null
        val hex = normalized.removePrefix("cc-")
        return hexToBytes(hex)
    }

    fun getRescueDeviceIdBytes(context: Context): ByteArray {
        val id = getOrCreateRescueDeviceId(context)
        return decodeRescueDeviceIdToBytes(id) ?: ByteArray(RESCUE_DEVICE_ID_BYTE_LENGTH).also {
            secureRandom.nextBytes(it)
        }
    }

    fun getOrCreateP2pDeviceId(context: Context): String {
        val securePrefs = prefs(context)
        normalizeP2pDeviceId(securePrefs.getString(P2P_DEVICE_ID_KEY, null))?.let { existing ->
            return existing
        }
        val generated = createP2pDeviceId()
        securePrefs.putString(P2P_DEVICE_ID_KEY, generated)
        return generated
    }

    fun getOrCreateP2pSessionCode(context: Context): String {
        val securePrefs = prefs(context)
        normalizeP2pSessionCode(securePrefs.getString(P2P_SESSION_CODE_KEY, null))?.let { existing ->
            return existing
        }
        val generated = createP2pSessionCode()
        securePrefs.putString(P2P_SESSION_CODE_KEY, generated)
        return generated
    }

    private fun createRescueDeviceId(): String {
        val raw = ByteArray(RESCUE_DEVICE_ID_BYTE_LENGTH)
        secureRandom.nextBytes(raw)
        return "cc-${bytesToHex(raw)}"
    }

    private fun createP2pDeviceId(): String {
        val raw = ByteArray(P2P_DEVICE_ID_BYTE_LENGTH)
        secureRandom.nextBytes(raw)
        return "p2p-${bytesToHex(raw)}"
    }

    private fun createP2pSessionCode(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(P2P_SESSION_CODE_LENGTH) {
            repeat(P2P_SESSION_CODE_LENGTH) {
                append(alphabet[secureRandom.nextInt(alphabet.length)])
            }
        }
    }

    private fun normalizeRescueDeviceId(value: String?): String? {
        val trimmed = value?.trim()?.lowercase(Locale.US).orEmpty()
        if (!trimmed.startsWith("cc-")) return null
        val hex = trimmed.removePrefix("cc-")
        if (hex.length != RESCUE_DEVICE_ID_HEX_LENGTH) return null
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return "cc-$hex"
    }

    private fun normalizeP2pDeviceId(value: String?): String? {
        val trimmed = value?.trim()?.lowercase(Locale.US).orEmpty()
        if (!trimmed.startsWith("p2p-")) return null
        val hex = trimmed.removePrefix("p2p-")
        if (hex.length != P2P_DEVICE_ID_HEX_LENGTH) return null
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return "p2p-$hex"
    }

    private fun normalizeP2pSessionCode(value: String?): String? {
        val trimmed = value?.trim()?.uppercase(Locale.US).orEmpty()
        if (trimmed.length != P2P_SESSION_CODE_LENGTH) return null
        if (trimmed.any { it !in 'A'..'Z' && it !in '0'..'9' }) return null
        return trimmed
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef".toCharArray()
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            hexChars[index++] = digits[value ushr 4]
            hexChars[index++] = digits[value and 0x0F]
        }
        return String(hexChars)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val output = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hex[i].digitToIntOrNull(16) ?: return null
            val lo = hex[i + 1].digitToIntOrNull(16) ?: return null
            output[i / 2] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return output
    }
}
