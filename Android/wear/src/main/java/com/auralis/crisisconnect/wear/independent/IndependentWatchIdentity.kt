package com.auralis.crisisconnect.wear.independent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject

/** Device-only request identity, separate from phone credentials and Signal identity. */
class IndependentWatchIdentity(private val deviceID: String) {
    private val alias = "crisis.watch.request.v1.$deviceID"
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init { require(java.util.UUID.fromString(deviceID).toString() == deviceID) }
    @Synchronized fun publicKey(): String {
        val keyStore = store
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return b64(store.getCertificate(alias).publicKey.encoded)
    }
    fun sign(encodedPayload: String): String {
        // Do not silently generate a replacement for an enrolled identity that was lost.
        val key = store.getKey(alias, null) as? java.security.PrivateKey ?: error("Watch identity unavailable")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update((DOMAIN + encodedPayload).toByteArray(Charsets.UTF_8))
        return b64(signer.sign())
    }
    fun enrollment(ownerUid: String): JSONObject {
        require(ownerUid.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val publicKey = publicKey()
        val raw = "{\"version\":1,\"ownerUid\":\"$ownerUid\",\"deviceID\":\"$deviceID\",\"publicKey\":\"$publicKey\",\"scopes\":[\"shortMessage\",\"sos\"]}"
        return JSONObject().put("deviceID", deviceID).put("publicKey", publicKey).put("signature", sign(b64(raw.toByteArray())))
    }
    companion object {
        const val DOMAIN = "crisis-connect/watch-request/v1\n"
        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
