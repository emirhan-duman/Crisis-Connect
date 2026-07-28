package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import java.security.KeyPair
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val Context.securityDataStore by preferencesDataStore(name = "security_prefs")

/**
 * Manages the device-bound rescue certificate.
 *
 * The signing identity is a hardware-backed EC P-256 key produced via
 * [AttestedKeyStore]. Each provisioning flow generates a fresh key whose
 * attestation chain is verified by the backend; the resulting certificate is
 * cached in encrypted DataStore for up to its expiry.
 */
class SecurityRepository(private val context: Context) {
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)

    /**
     * Ensures this device holds the shared authority-mesh group key, fetching it from the backend
     * on first use, and returns whether the key is present afterwards. The backend only returns the
     * key to verified admin/fieldteam callers, so this should be gated behind the authority role;
     * on civilian devices it simply fails and the authority mesh stays off.
     */
    suspend fun ensureAuthorityMeshGroupKey(): Boolean = withContext(Dispatchers.IO) {
        if (AuthorityMeshKeyStore.loadGroupKey(context) != null) {
            return@withContext true
        }
        runCatching {
            val result = functions.getHttpsCallable("issueAuthorityMeshKey")
                .call(emptyMap<String, Any>())
                .awaitResult()
            val data = result.data as? Map<*, *>
            val keyBase64 = (data?.get("keyBase64") as? String)?.trim()?.replace("\n", "")
            val bytes = keyBase64?.let { Base64.decode(it, Base64.NO_WRAP or Base64.NO_PADDING) }
            if (bytes != null && bytes.size == 32) {
                AuthorityMeshKeyStore.saveGroupKey(context, bytes)
                true
            } else {
                false
            }
        }.getOrElse { throwable ->
            Log.w("SecurityRepository", "Unable to fetch authority mesh group key", throwable)
            false
        }
    }

    /**
     * Returns the most recently provisioned attested key pair, or null if no
     * certificate has been issued yet.
     */
    suspend fun loadAttestedKey(): KeyPair? = withContext(Dispatchers.IO) {
        AttestedKeyStore.loadExistingAttestedKey()
    }

    /**
     * Backwards-compatible alias for [loadAttestedKey] that throws when the
     * caller cannot reasonably continue without a key.
     */
    suspend fun getOrCreateDeviceIdentity(): KeyPair = withContext(Dispatchers.IO) {
        AttestedKeyStore.loadExistingAttestedKey()
            ?: throw MissingRoleCertificateException(
                "Device-bound signing key is not present. Issue a certificate first."
            )
    }

    /**
     * Loads a cached certificate if present, still bound to the current
     * device-bound key, and within its validity window.
     *
     * Pass [allowExpired]=true ONLY for display / UI gating purposes; this
     * bypasses the expiry check so the user can see an expired cert and act
     * on it. For signing operations, always use the default ([allowExpired]=false).
     */
    suspend fun getStoredCertificate(allowExpired: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) return@withContext null
        val keyPair = AttestedKeyStore.loadExistingAttestedKey() ?: return@withContext null
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            skipExpiryCheck = allowExpired
        )
    }

    /**
     * Ensures a certificate exists and is bound to a fresh attested key. When
     * no usable certificate is cached, runs the full provisioning flow
     * (challenge -> attested key -> Play Integrity -> backend issue).
     */
    suspend fun getOrFetchCertificate(): Pair<KeyPair, ByteArray> = withContext(Dispatchers.IO) {
        val currentUid = FirebaseAuth.getInstance().currentUser
            ?.takeUnless { it.isAnonymous }
            ?.uid
            ?.trim()
            .orEmpty()
        require(currentUid.isNotEmpty()) {
            "Authenticated user is required to fetch rescue certificate"
        }

        val existingKey = AttestedKeyStore.loadExistingAttestedKey()
        if (existingKey != null) {
            val publicKeyBase64 = Base64.encodeToString(existingKey.public.encoded, Base64.NO_WRAP)
            val cached = loadUsableStoredCertificate(
                currentUid = currentUid,
                publicKeyBase64 = publicKeyBase64
            )
            if (cached != null) {
                Log.i(TAG, "Using cached device-bound rescue certificate")
                return@withContext existingKey to cached
            }
        }

        val deviceId = LocalKeyStorage.getOrCreateRescueDeviceId(context.applicationContext)
        val flow = CertificateProvisioningFlow(context.applicationContext, functions)
        val result = try {
            flow.provisionCertificate(deviceId)
        } catch (throwable: Throwable) {
            // Fall back to a still-usable cached cert if provisioning failed
            // (e.g. transient network loss in a disaster scenario).
            // We use a BOUNDED offline grace so that rescue operations can
            // continue for up to 72 hours without backend connectivity, but
            // an indefinitely-old certificate is rejected.
            existingKey?.let { keyPair ->
                val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
                loadUsableStoredCertificate(
                    currentUid = currentUid,
                    publicKeyBase64 = publicKeyBase64,
                    offlineGraceMillis = OFFLINE_FALLBACK_GRACE_MILLIS
                )?.let { cached ->
                    Log.w(TAG, "Using cached certificate because provisioning failed", throwable)
                    return@withContext keyPair to cached
                }
            }
            throw throwable
        }

        require(result.certificate.isOwnedBy(currentUid)) {
            "Provisioned certificate owner does not match authenticated user."
        }

        val certificateBytes = result.certificate.toStorageBytes()
        persistCertificate(
            publicKeyBase64 = result.keyMaterial.publicKeyBase64,
            certificateBytes = certificateBytes,
            ownerUid = result.certificate.ownerUid
        )
        // Keep release log minimal: avoid leaking deviceId / expiry timestamps.
        Log.i(TAG, "Provisioned device-bound certificate (role=${result.certificate.role})")
        Log.d(
            TAG,
            "Cert detail deviceId=${result.certificate.deviceId} " +
                "expiresAtMs=${result.certificate.expiresAtMillis}"
        )
        result.keyMaterial.keyPair to certificateBytes
    }

    /**
     * Forces a fresh provision from the backend and PERSISTS it locally, returning the new
     * certificate. Use this for the manual "get certificate" / "renew" button: unlike
     * [getOrFetchCertificate] it never short-circuits to a cached cert, and unlike calling
     * [CertificateProvisioningFlow] directly it actually stores the result — otherwise the profile
     * card cannot see the new certificate until the app is restarted.
     */
    suspend fun provisionAndStoreCertificate(deviceId: String): RoleCertificate =
        withContext(Dispatchers.IO) {
            val currentUid = resolveCurrentUid()
            require(currentUid.isNotEmpty()) {
                "Authenticated user is required to provision a rescue certificate"
            }
            val flow = CertificateProvisioningFlow(context.applicationContext, functions)
            val result = flow.provisionCertificate(deviceId)
            require(result.certificate.isOwnedBy(currentUid)) {
                "Provisioned certificate owner does not match authenticated user."
            }
            val certificateBytes = result.certificate.toStorageBytes()
            persistCertificate(
                publicKeyBase64 = result.keyMaterial.publicKeyBase64,
                certificateBytes = certificateBytes,
                ownerUid = result.certificate.ownerUid
            )
            Log.i(TAG, "Provisioned + stored device-bound certificate (role=${result.certificate.role})")
            result.certificate
        }

    /**
     * Returns true if a cert exists for the current user/device. Pass
     * [allowExpired]=true for UI gating (e.g. "should we show the rescue
     * button at all"); pass false to require a strictly-valid cert.
     */
    suspend fun hasUsableStoredCertificate(allowExpired: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) return@withContext false
        val keyPair = AttestedKeyStore.loadExistingAttestedKey() ?: return@withContext false
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            skipExpiryCheck = allowExpired
        ) != null
    }

    /**
     * Returns the role string from the cached cert. Pass [allowExpired]=true
     * for UI gating (lets us still show the user their last-known role even
     * after expiry); strict signing paths must pass false.
     */
    suspend fun getUsableStoredCertificateRole(allowExpired: Boolean = false): String? = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) return@withContext null
        val keyPair = AttestedKeyStore.loadExistingAttestedKey() ?: return@withContext null
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val certificateBytes = loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            skipExpiryCheck = allowExpired
        ) ?: return@withContext null
        RoleCertificate.fromStorageBytes(certificateBytes)?.role
            ?.takeIf { it in RoleCertificate.ALLOWED_ROLES }
    }

    /**
     * Verified agency (e.g. AFAD/FEMA) bound into this device's own stored certificate.
     * Mirrors what peers see for us, so the connected-users sheet can label our own row
     * with the same institution. Empty for legacy v2 certificates (no agency).
     */
    suspend fun getUsableStoredCertificateAgency(allowExpired: Boolean = true): String? = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) return@withContext null
        val keyPair = AttestedKeyStore.loadExistingAttestedKey() ?: return@withContext null
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val certificateBytes = loadUsableStoredCertificate(
            currentUid = currentUid,
            publicKeyBase64 = publicKeyBase64,
            skipExpiryCheck = allowExpired
        ) ?: return@withContext null
        RoleCertificate.fromStorageBytes(certificateBytes)?.agency
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Best-effort warm-up used after successful rescue verification while online.
     * Also runs a server-side revocation check on the existing cert.
     */
    suspend fun warmUpCertificate(): Boolean = try {
        revalidateAgainstServer()
        getOrFetchCertificate()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Log.w(TAG, "Rescue certificate warm-up failed", throwable)
        false
    }

    /**
     * Runs [warmUpCertificate] on an app-lifetime scope so it survives UI
     * lifecycles. Provisioning takes several seconds (Play Integrity + key
     * attestation + backend call); a composition-scoped caller would cancel
     * it as soon as the screen is left. Concurrent requests are de-duplicated
     * because [getOrFetchCertificate] has no internal locking.
     */
    fun warmUpCertificateInBackground() {
        val appContext = context.applicationContext
        synchronized(WARM_UP_LOCK) {
            if (backgroundWarmUpJob?.isActive == true) return
            backgroundWarmUpJob = warmUpScope.launch {
                SecurityRepository(appContext).warmUpCertificate()
            }
        }
    }

    /**
     * Calls the backend to confirm the cached certificate is still active.
     * If the server reports `revoked` or `missing`, wipes local state so the
     * next call to [getOrFetchCertificate] re-provisions from scratch.
     */
    suspend fun revalidateAgainstServer(): String? = withContext(Dispatchers.IO) {
        val currentUid = resolveCurrentUid()
        if (currentUid.isEmpty()) return@withContext null
        val keyPair = AttestedKeyStore.loadExistingAttestedKey() ?: return@withContext null
        val callable = functions.getHttpsCallable("validateCertificate")
        val result = try {
            callable.call(emptyMap<String, String>()).awaitResult()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Certificate revalidation call failed", throwable)
            return@withContext null
        }
        val data = result.data as? Map<*, *> ?: return@withContext null
        val status = (data["status"] as? String)?.trim()?.lowercase() ?: return@withContext null
        when (status) {
            "revoked", "missing" -> {
                val reason = (data["revokedReason"] as? String) ?: status
                Log.w(TAG, "Server reported certificate status=$status reason=$reason; wiping")
                wipeCertificate("server-status=$status reason=$reason")
            }
            "active" -> Unit
            else -> Log.i(TAG, "Server reported certificate status=$status")
        }
        // Surface a hint about the most recent stored cert publicKey for logs.
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP).take(16)
        status
    }

    suspend fun clearStoredCertificate() {
        context.securityDataStore.edit { prefs ->
            prefs.remove(CERTIFICATE_KEY)
            prefs.remove(PUBLIC_KEY_KEY)
            prefs.remove(CERTIFICATE_OWNER_UID_KEY)
        }
    }

    /**
     * Wipes both the cached certificate and the underlying attested key. The
     * next call to [getOrFetchCertificate] will start a fresh provisioning
     * flow with a brand new key.
     */
    suspend fun wipeCertificate(reason: String) = withContext(Dispatchers.IO) {
        Log.w(TAG, "Wiping device-bound certificate: $reason")
        AttestedKeyStore.deleteExistingKey()
        clearStoredCertificate()
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

    /**
     * Internal cache loader. Two modes for handling expiry:
     *  - [skipExpiryCheck]=true: bypass the expiry check entirely. Only used
     *    for display / UI gating so the user can see and act on an expired
     *    cert. Never use this on a signing path.
     *  - [offlineGraceMillis]>0: accept a cert that expired within this
     *    window. Used for the provisioning fallback so rescue ops keep
     *    working during transient backend outages.
     * If both are unset, the cert must be strictly within its validity
     * window (plus the 60s clock skew handled by [RoleCertificate.isUsableAt]).
     */
    private suspend fun loadUsableStoredCertificate(
        currentUid: String,
        publicKeyBase64: String,
        offlineGraceMillis: Long = 0L,
        skipExpiryCheck: Boolean = false,
    ): ByteArray? {
        if (currentUid.isBlank()) return null
        val stored = loadStoredCertificate() ?: return null
        if (stored.publicKeyBase64 != publicKeyBase64) return null
        val certificateBytes = decodeCertificate(stored.certificateBase64) ?: return null
        val roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes) ?: return null
        if (!stored.ownerUid.isNullOrBlank()) {
            if (stored.ownerUid != currentUid) return null
        } else if (!roleCertificate.isOwnedBy(currentUid)) {
            return null
        }
        val storedDeviceId = LocalKeyStorage.getOrCreateRescueDeviceId(context.applicationContext)
        if (storedDeviceId.isNotBlank() && !roleCertificate.isBoundTo(storedDeviceId)) {
            Log.w(TAG, "Stored certificate is not bound to current deviceId; ignoring")
            return null
        }
        if (skipExpiryCheck) {
            // Display path: still validate shape so we don't surface a corrupt
            // record, but skip the time-based check entirely.
            if (!roleCertificate.hasValidShape()) return null
        } else {
            if (!roleCertificate.isUsableAt(
                    nowMillis = timeProvider(),
                    allowOfflineGrace = offlineGraceMillis > 0L,
                    offlineGraceMillis = offlineGraceMillis,
                )
            ) return null
        }
        if (!RoleCertificateSignatureVerifier.verify(roleCertificate, publicKeyBase64)) return null
        return certificateBytes
    }

    private fun timeProvider(): Long = System.currentTimeMillis()

    private fun resolveCurrentUid(): String {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser?.isAnonymous == true) return ""
        val authUid = currentUser?.uid?.trim().orEmpty()
        if (authUid.isNotEmpty()) return authUid
        return LocalKeyStorage.getSavedUid(context)?.trim().orEmpty()
    }

    private fun decodeCertificate(certificateBase64: String): ByteArray? = runCatching {
        val sanitized = certificateBase64.trim().replace("\n", "")
        Base64.decode(sanitized, Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "SecurityRepository"
        private const val FUNCTIONS_REGION = "us-central1"

        /**
         * Maximum age past expiry that the provisioning fallback will accept
         * a cached certificate for signing. 72 hours balances disaster-scenario
         * offline tolerance against accepting an indefinitely-stale cert.
         */
        val OFFLINE_FALLBACK_GRACE_MILLIS: Long = TimeUnit.HOURS.toMillis(72)

        private val CERTIFICATE_KEY = stringPreferencesKey("device_certificate")
        private val PUBLIC_KEY_KEY = stringPreferencesKey("device_public_key")
        private val CERTIFICATE_OWNER_UID_KEY = stringPreferencesKey("device_certificate_owner_uid")

        private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val WARM_UP_LOCK = Any()
        private var backgroundWarmUpJob: Job? = null
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
