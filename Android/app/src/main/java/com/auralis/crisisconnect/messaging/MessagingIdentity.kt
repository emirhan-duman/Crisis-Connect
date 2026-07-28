package com.auralis.crisisconnect.messaging

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Encodes/decodes the P-256 keys used by the E2E internet-messaging layer.
 *
 * Public keys travel as X.509/SubjectPublicKeyInfo (SPKI) DER and private keys as PKCS#8 DER, both
 * base64. SPKI is the interoperable wire form so iOS (CryptoKit) and web (WebCrypto) can import the
 * exact same bytes.
 */
object MessagingKeyCodec {
    private const val EC = "EC"

    fun encodePublicKey(key: PublicKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodePublicKey(base64: String): PublicKey {
        val der = Base64.decode(base64, Base64.NO_WRAP)
        return KeyFactory.getInstance(EC).generatePublic(X509EncodedKeySpec(der))
    }

    fun encodePrivateKey(key: PrivateKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodePrivateKey(base64: String): PrivateKey {
        val der = Base64.decode(base64, Base64.NO_WRAP)
        return KeyFactory.getInstance(EC).generatePrivate(PKCS8EncodedKeySpec(der))
    }
}

/**
 * The device's long-term identity key pair for E2E internet messaging.
 *
 * On API 31+ the private key is generated inside the hardware-backed AndroidKeyStore with
 * `PURPOSE_AGREE_KEY`, so it is NON-EXPORTABLE — ECDH runs through the Keystore and the raw private
 * key never exists in app memory. Below API 31 (no AGREE_KEY support) it falls back to a software
 * key stored in a hardware-encrypted preference. Nothing is shipped yet, so no key migration is
 * needed. The public half is published to the backend key directory so contacts can encrypt to us.
 */
class MessagingIdentity(context: Context) {

    private val appContext = context.applicationContext
    private val store = KeystoreBackedPreferences(
        context = appContext,
        prefName = PREFS_NAME,
        keyAlias = SOFTWARE_STORE_KEY_ALIAS
    )

    fun keyPair(): KeyPair =
        if (hardwareBacked()) keystoreKeyPair() else softwareKeyPair()

    fun publicKeyBase64(): String = MessagingKeyCodec.encodePublicKey(keyPair().public)

    /**
     * ECDH between our long-term identity private key and a peer's public key. On API 31+ this runs
     * through the AndroidKeyStore provider so the (non-exportable) hardware key is used without ever
     * leaving the Keystore; below that it uses the software key.
     */
    fun deriveSharedSecret(peerPublicKey: PublicKey): ByteArray {
        return if (hardwareBacked()) {
            KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE).apply {
                init(keystoreKeyPair().private)
                doPhase(peerPublicKey, true)
            }.generateSecret()
        } else {
            Crypto.deriveSharedSecret(softwareKeyPair().private, peerPublicKey)
        }
    }

    private fun hardwareBacked(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @RequiresApi(Build.VERSION_CODES.S)
    @Synchronized
    private fun keystoreKeyPair(): KeyPair {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(HARDWARE_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
            return KeyPair(it.certificate.publicKey, it.privateKey)
        }
        val spec = KeyGenParameterSpec.Builder(HARDWARE_KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    @Synchronized
    private fun softwareKeyPair(): KeyPair {
        val storedPublic = store.getString(KEY_PUBLIC)
        val storedPrivate = store.getString(KEY_PRIVATE)
        if (!storedPublic.isNullOrBlank() && !storedPrivate.isNullOrBlank()) {
            runCatching {
                KeyPair(
                    MessagingKeyCodec.decodePublicKey(storedPublic),
                    MessagingKeyCodec.decodePrivateKey(storedPrivate)
                )
            }.getOrNull()?.let { return it }
        }
        val pair = Crypto.generateEphemeralEcKeyPair()
        store.putString(KEY_PUBLIC, MessagingKeyCodec.encodePublicKey(pair.public))
        store.putString(KEY_PRIVATE, MessagingKeyCodec.encodePrivateKey(pair.private))
        return pair
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HARDWARE_KEY_ALIAS = "cc_messaging_identity_hw_v1"
        private const val PREFS_NAME = "crisisconnect_messaging_identity"
        private const val SOFTWARE_STORE_KEY_ALIAS = "cc_messaging_identity_v1"
        private const val KEY_PUBLIC = "identity_public_spki"
        private const val KEY_PRIVATE = "identity_private_pkcs8"
    }
}
