package com.auralis.crisisconnect.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Generates hardware-backed signing keys whose attestation certificate chain can
 * be presented to the backend for verification. Backed by the AndroidKeyStore
 * provider, the key material never leaves the device's TEE or StrongBox.
 */
object AttestedKeyStore {

    private const val TAG = "AttestedKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val ATTESTED_SIGNING_KEY_ALIAS = "dcs_attested_signing_key"

    data class AttestedKeyMaterial(
        val keyPair: KeyPair,
        val publicKeyBase64: String,
        val certificateChainPem: List<String>,
        val securityHint: SecurityHint,
    )

    enum class SecurityHint {
        STRONG_BOX_BACKED,
        TEE_BACKED,
        UNKNOWN,
    }

    /**
     * Generates a fresh attested EC P-256 signing key. The supplied
     * `serverChallenge` is embedded inside the attestation extension and binds
     * the resulting chain to a specific server-issued nonce.
     */
    fun generateAttestedSigningKey(
        context: Context,
        serverChallenge: ByteArray,
        alias: String = ATTESTED_SIGNING_KEY_ALIAS,
        preferStrongBox: Boolean = true,
        requireUnlockedDevice: Boolean = true,
    ): AttestedKeyMaterial {
        require(serverChallenge.isNotEmpty()) { "serverChallenge must not be empty" }
        deleteExistingKey(alias)

        val strongBoxAvailable = preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.applicationContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE
            )

        var securityHint = SecurityHint.UNKNOWN
        val keyPair = try {
            securityHint = if (strongBoxAvailable) SecurityHint.STRONG_BOX_BACKED else SecurityHint.TEE_BACKED
            generateKeyPair(
                alias = alias,
                serverChallenge = serverChallenge,
                strongBoxBacked = strongBoxAvailable,
                requireUnlockedDevice = requireUnlockedDevice,
            )
        } catch (error: Exception) {
            if (!strongBoxAvailable) throw error
            Log.w(TAG, "StrongBox unavailable for $alias; falling back to TEE", error)
            securityHint = SecurityHint.TEE_BACKED
            generateKeyPair(
                alias = alias,
                serverChallenge = serverChallenge,
                strongBoxBacked = false,
                requireUnlockedDevice = requireUnlockedDevice,
            )
        }

        val chain = readCertificateChain(alias)
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        return AttestedKeyMaterial(
            keyPair = keyPair,
            publicKeyBase64 = publicKeyBase64,
            certificateChainPem = chain,
            securityHint = securityHint,
        )
    }

    fun loadExistingAttestedKey(alias: String = ATTESTED_SIGNING_KEY_ALIAS): KeyPair? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            entry?.let { KeyPair(it.certificate.publicKey, it.privateKey) }
        }.getOrNull()
    }

    fun signWithAttestedKey(
        payload: ByteArray,
        alias: String = ATTESTED_SIGNING_KEY_ALIAS,
    ): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Attested signing key '$alias' not found in keystore.")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(payload)
        return signature.sign()
    }

    fun deleteExistingKey(alias: String = ATTESTED_SIGNING_KEY_ALIAS) {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    private fun generateKeyPair(
        alias: String,
        serverChallenge: ByteArray,
        strongBoxBacked: Boolean,
        requireUnlockedDevice: Boolean,
    ): KeyPair {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(serverChallenge)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBoxBacked) {
            builder.setIsStrongBoxBacked(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && requireUnlockedDevice) {
            // setUnlockedDeviceRequired is OS-enforced (not TEE-enforced); still
            // a useful defense-in-depth check that prevents signing while the
            // device is locked.
            builder.setUnlockedDeviceRequired(true)
        }
        generator.initialize(builder.build())
        return generator.generateKeyPair()
    }

    private fun readCertificateChain(alias: String): List<String> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val chain: Array<Certificate>? = keyStore.getCertificateChain(alias)
        if (chain.isNullOrEmpty()) {
            throw IllegalStateException("Attestation chain for alias '$alias' is missing.")
        }
        return chain.map { cert ->
            val encoded = (cert as? X509Certificate)?.encoded
                ?: throw IllegalStateException("Attestation chain contains a non-X509 certificate.")
            buildPem(encoded)
        }
    }

    private fun buildPem(derEncoded: ByteArray): String {
        val base64 = Base64.encodeToString(derEncoded, Base64.NO_WRAP)
        val builder = StringBuilder()
        builder.append("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < base64.length) {
            val end = (i + 64).coerceAtMost(base64.length)
            builder.append(base64, i, end).append('\n')
            i = end
        }
        builder.append("-----END CERTIFICATE-----\n")
        return builder.toString()
    }
}
