package com.auralis.crisisconnect.security

import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thin cryptographic helper for BLE secure transport. All primitives use standard JCA providers
 * so they can execute inside the Android sandbox without additional dependencies.
 */
object Crypto {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val ECDH_ALGORITHM = "ECDH"
    private const val ECDSA_ALGORITHM = "SHA256withECDSA"
    private const val EC_CURVE = "secp256r1"

    private val secureRandom = SecureRandom()

    fun randomBytes(length: Int): ByteArray {
        require(length > 0) { "Requested random byte length must be positive" }
        return ByteArray(length).also { secureRandom.nextBytes(it) }
    }

    @Throws(GeneralSecurityException::class)
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "HMAC key may not be empty" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(message)
    }

    @Throws(GeneralSecurityException::class)
    fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32-byte key" }
        require(nonce.size == 12) { "AES-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(plaintext)
    }

    @Throws(GeneralSecurityException::class)
    fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32-byte key" }
        require(nonce.size == 12) { "AES-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Generates a fresh EC key pair for ephemeral use (P-256).
     */
    @Throws(GeneralSecurityException::class)
    fun generateEphemeralEcKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Performs ECDH key agreement using the provided EC key material.
     */
    @Throws(GeneralSecurityException::class)
    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        require(privateKey.algorithm.equals("EC", ignoreCase = true)) {
            "ECDH requires an EC private key"
        }
        require(peerPublicKey.algorithm.equals("EC", ignoreCase = true)) {
            "ECDH requires an EC public key"
        }
        val agreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    /**
     * Simple HKDF-SHA256 implementation for deriving symmetric keys from the ECDH secret.
     */
    @Throws(GeneralSecurityException::class)
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray? = null,
        outputLength: Int = 32
    ): ByteArray {
        require(outputLength in 1..(255 * 32)) {
            "HKDF output length must be between 1 and ${255 * 32} bytes"
        }
        val effectiveSalt = salt ?: ByteArray(32)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val infoBytes = info ?: ByteArray(0)
        var blockIndex = 1
        var generatedBytes = 0
        var previous = ByteArray(0)
        val output = ByteArray(outputLength)

        while (generatedBytes < outputLength) {
            mac.reset()
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(blockIndex.toByte())
            previous = mac.doFinal()

            val bytesToCopy = minOf(previous.size, outputLength - generatedBytes)
            System.arraycopy(previous, 0, output, generatedBytes, bytesToCopy)
            generatedBytes += bytesToCopy
            blockIndex++
        }
        return output
    }

    /**
     * Derives an AES session key by running ECDH and HKDF-SHA256.
     */
    @Throws(GeneralSecurityException::class)
    fun deriveSessionKey(
        privateKey: PrivateKey,
        peerPublicKey: PublicKey,
        salt: ByteArray? = null,
        info: ByteArray? = null,
        outputLength: Int = 32
    ): ByteArray {
        val sharedSecret = deriveSharedSecret(privateKey, peerPublicKey)
        return hkdfSha256(sharedSecret, salt = salt, info = info, outputLength = outputLength)
    }

    /**
     * Signs [data] with ECDSA using SHA-256.
     */
    @Throws(GeneralSecurityException::class)
    fun signData(privateKey: PrivateKey, data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Cannot sign empty payloads" }
        val signature = Signature.getInstance(ECDSA_ALGORITHM)
        signature.initSign(privateKey, secureRandom)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Verifies an ECDSA signature using SHA-256.
     */
    @Throws(GeneralSecurityException::class)
    fun verifySignature(publicKey: PublicKey, data: ByteArray, signatureBytes: ByteArray): Boolean {
        require(data.isNotEmpty()) { "Cannot verify signature over empty payload" }
        require(signatureBytes.isNotEmpty()) { "Signature bytes are required" }
        val verifier = Signature.getInstance(ECDSA_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(signatureBytes)
    }

    /**
     * Validates that [certificateBytes] was produced by signing the device public key with
     * the master ECDSA private key.
     */
    @Throws(GeneralSecurityException::class)
    fun verifyCertificate(
        masterPublicKey: PublicKey,
        devicePublicKey: ByteArray,
        certificateBytes: ByteArray
    ): Boolean {
        require(devicePublicKey.isNotEmpty()) { "Device public key may not be empty" }
        return verifySignature(masterPublicKey, devicePublicKey, certificateBytes)
    }
}
