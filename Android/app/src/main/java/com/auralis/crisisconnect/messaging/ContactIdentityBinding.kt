package com.auralis.crisisconnect.messaging

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Binds a relay identity to the independently paired offline contact key. */
object ContactIdentityBinding {
    fun sign(keyBase64: String, uid: String, sessionCode: String, publicKey: String): String {
        val key = Base64.getDecoder().decode(keyBase64)
        require(key.size == 32)
        val payload = buildString {
            append("cc-contact-identity-v1\n")
            for (field in listOf(uid, sessionCode, publicKey)) {
                append(field.toByteArray(UTF_8).size); append(':'); append(field)
            }
        }.toByteArray(UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(payload))
    }

    fun verify(proof: String, keyBase64: String, uid: String, sessionCode: String, publicKey: String): Boolean =
        runCatching {
            val supplied = Base64.getDecoder().decode(proof)
            supplied.size == 32 && MessageDigest.isEqual(supplied,
                Base64.getDecoder().decode(sign(keyBase64, uid, sessionCode, publicKey)))
        }.getOrDefault(false)
}
