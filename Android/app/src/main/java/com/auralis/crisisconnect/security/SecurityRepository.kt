package com.auralis.crisisconnect.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.UnrecoverableEntryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val Context.securityDataStore by preferencesDataStore(name = "security_prefs")

/**
 * Manages device identity keys stored in Android Keystore and handles retrieval of the signed
 * role certificate issued by Firebase Cloud Functions.
 */
class SecurityRepository(private val context: Context) {
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)

    /**
     * Loads an existing device identity key pair from Android Keystore or generates a new one.
     */
    suspend fun getOrCreateDeviceIdentity(): KeyPair = withContext(Dispatchers.IO) {
        loadExistingKeyPair() ?: generateKeyPair()
    }

    /**
     * Loads a cached certificate if present and still bound to the active device public key.
     */
    suspend fun getStoredCertificate(allowExpired: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) {
            return@withContext null
        }
        val keyPair = getOrCreateDeviceIdentity()
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            allowExpired = allowExpired
        )
    }

    /**
     * Ensures a certificate exists for the current device key. If the keystore entry was rotated
     * or no certificate is cached, it requests a new one from the Cloud Function and persists it.
     */
    suspend fun getOrFetchCertificate(
        functions: FirebaseFunctions = this@SecurityRepository.functions
    ): Pair<KeyPair, ByteArray> = withContext(Dispatchers.IO) {
        val keyPair = getOrCreateDeviceIdentity()
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val currentUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { it.isAnonymous }
            ?.uid
            ?.trim()
            .orEmpty()
        require(currentUid.isNotEmpty()) { "Authenticated user is required to fetch rescue certificate" }

        val cachedCertificate = loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64
        )
        if (cachedCertificate != null) {
            return@withContext keyPair to cachedCertificate
        }

        val certificate = try {
            requestCertificate(
                functions = functions,
                publicKeyBase64 = publicKeyBase64,
                currentUid = currentUid
            )
        } catch (throwable: Throwable) {
            loadUsableStoredCertificate(
                currentUid = currentUid,
                publicKeyBase64 = publicKeyBase64
            )?.let { cached ->
                Log.w(
                    TAG,
                    "Using cached rescue certificate because refresh failed",
                    throwable
                )
                return@withContext keyPair to cached
            }
            throw throwable
        }
        val certificateBytes = certificate.toStorageBytes()
        persistCertificate(publicKeyBase64, certificateBytes, certificate.ownerUid)
        keyPair to certificateBytes
    }

    /**
     * Returns true when a locally cached rescue certificate can be used for the current keystore
     * identity and (when available) the currently signed-in Firebase user.
     */
    suspend fun hasUsableStoredCertificate(allowExpired: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) {
            return@withContext false
        }
        val keyPair = getOrCreateDeviceIdentity()
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            allowExpired = allowExpired
        ) != null
    }

    suspend fun getUsableStoredCertificateRole(allowExpired: Boolean = false): String? = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) {
            return@withContext null
        }
        val keyPair = getOrCreateDeviceIdentity()
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val certificateBytes = loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            allowExpired = allowExpired
        ) ?: return@withContext null
        RoleCertificate.fromStorageBytes(certificateBytes)?.role
            ?.takeIf { it in RoleCertificate.ALLOWED_ROLES }
    }

    /**
     * Best-effort warm-up used after successful rescue verification while online.
     */
    suspend fun warmUpCertificate(): Boolean = runCatching {
        getOrFetchCertificate()
        true
    }.getOrDefault(false)

    suspend fun clearStoredCertificate() {
        context.securityDataStore.edit { prefs ->
            prefs.remove(CERTIFICATE_KEY)
            prefs.remove(PUBLIC_KEY_KEY)
            prefs.remove(CERTIFICATE_OWNER_UID_KEY)
        }
    }

    private suspend fun requestCertificate(
        functions: FirebaseFunctions,
        publicKeyBase64: String,
        currentUid: String
    ): RoleCertificate {
        val callable = functions.getHttpsCallable("issueRoleCertificate")
        val result = callable.call(mapOf("publicKey" to publicKeyBase64)).awaitResult()
        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected certificate response type")

        val certificate = RoleCertificate.fromCallableResponse(data)
        require(certificate.isOwnedBy(currentUid)) {
            "Certificate owner does not match authenticated user"
        }
        require(certificate.isValidAt(timeProvider())) {
            "Certificate is outside its validity window"
        }
        require(RoleCertificateSignatureVerifier.verify(certificate, publicKeyBase64)) {
            "Certificate signature verification failed"
        }
        // Ensure signing payload can be reconstructed with local public key encoding.
        certificate.signingPayload(publicKeyBase64)
        return certificate
    }

    private suspend fun persistCertificate(
        publicKeyBase64: String,
        certificateBytes: ByteArray,
        ownerUid: String
    ) {
        val certificateBase64 = Base64.encodeToString(certificateBytes, Base64.NO_WRAP)
        context.securityDataStore.edit { prefs ->
            prefs[CERTIFICATE_KEY] = certificateBase64
            prefs[PUBLIC_KEY_KEY] = publicKeyBase64
            if (ownerUid.isNotBlank()) {
                prefs[CERTIFICATE_OWNER_UID_KEY] = ownerUid
            } else {
                prefs.remove(CERTIFICATE_OWNER_UID_KEY)
            }
        }
    }

    private suspend fun loadStoredCertificate(): StoredCertificate? {
        val prefs = context.securityDataStore.data.first()
        val certificate = prefs[CERTIFICATE_KEY]
        val publicKey = prefs[PUBLIC_KEY_KEY]
        val ownerUid = prefs[CERTIFICATE_OWNER_UID_KEY]
        return if (certificate != null && publicKey != null) {
            StoredCertificate(publicKey, certificate, ownerUid)
        } else {
            null
        }
    }

    private suspend fun loadUsableStoredCertificate(
        currentUid: String,
        publicKeyBase64: String,
        allowExpired: Boolean = false
    ): ByteArray? {
        if (currentUid.isBlank()) {
            return null
        }
        val stored = loadStoredCertificate() ?: return null
        if (stored.publicKeyBase64 != publicKeyBase64) {
            return null
        }
        val certificateBytes = decodeCertificate(stored.certificateBase64) ?: return null
        val roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes) ?: return null
        if (!stored.ownerUid.isNullOrBlank()) {
            if (stored.ownerUid != currentUid) {
                return null
            }
        } else if (!roleCertificate.isOwnedBy(currentUid)) {
            return null
        }
        if (!roleCertificate.isUsableAt(
                nowMillis = timeProvider(),
                allowOfflineGrace = allowExpired
            )
        ) {
            return null
        }
        if (!RoleCertificateSignatureVerifier.verify(roleCertificate, publicKeyBase64)) {
            return null
        }
        return certificateBytes
    }

    private fun timeProvider(): Long = System.currentTimeMillis()

    private fun resolveCurrentUid(): String {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser?.isAnonymous == true) {
            return ""
        }
        val authUid = currentUser?.uid?.trim().orEmpty()
        if (authUid.isNotEmpty()) {
            return authUid
        }
        return LocalKeyStorage.getSavedUid(context)?.trim().orEmpty()
    }

    private fun decodeCertificate(certificateBase64: String): ByteArray? = runCatching {
        val sanitized = certificateBase64.trim().replace("\n", "")
        Base64.decode(sanitized, Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun loadExistingKeyPair(): KeyPair? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(DEVICE_IDENTITY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            entry?.let { KeyPair(it.certificate.publicKey, it.privateKey) }
        } catch (unrecoverable: UnrecoverableEntryException) {
            Log.w(TAG, "Device identity key was unrecoverable; regenerating", unrecoverable)
            null
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to load device identity from keystore", throwable)
            null
        }
    }

    private fun generateKeyPair(): KeyPair {
        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                DEVICE_IDENTITY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            return generator.generateKeyPair()
        } catch (throwable: GeneralSecurityException) {
            Log.e(TAG, "Failed to generate EC key pair in keystore", throwable)
            throw throwable
        }
    }

    companion object {
        private const val TAG = "SecurityRepository"
        private const val DEVICE_IDENTITY_ALIAS = "dcs_device_identity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val FUNCTIONS_REGION = "us-central1" // change to your deployed region if needed
        private val CERTIFICATE_KEY = stringPreferencesKey("device_certificate")
        private val PUBLIC_KEY_KEY = stringPreferencesKey("device_public_key")
        private val CERTIFICATE_OWNER_UID_KEY = stringPreferencesKey("device_certificate_owner_uid")
    }
}

private data class StoredCertificate(
    val publicKeyBase64: String,
    val certificateBase64: String,
    val ownerUid: String?
)

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val result = task.result
            if (result != null) {
                continuation.resume(result)
            } else {
                continuation.resumeWithException(
                    IllegalStateException("Firebase callable returned a null result")
                )
            }
        } else {
            val error = task.exception ?: IllegalStateException("Firebase callable failed")
            continuation.resumeWithException(error)
        }
    }
}
