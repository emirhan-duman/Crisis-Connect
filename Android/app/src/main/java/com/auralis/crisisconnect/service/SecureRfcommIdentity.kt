package com.auralis.crisisconnect.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One challenge per live socket; possession of the pair key authorizes first enrichment. */
internal class SecureRfcommIdentity {
    data class Identity(val uid: String, val publicKey: String)
    enum class Kind { CHALLENGE, PROOF }
    private val nonce = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private var consumed = false
    fun challenge(): String = envelope("ID_CHALLENGE|$nonce")

    @Synchronized
    fun open(key: String, frame: String, localIdentity: Identity): Identity? = runCatching {
        require(!consumed && frame.length <= 2048)
        val parts = decode(frame).split('|')
        require(parts.size == 6 && parts[0] == "ID_PROOF" && parts[1] == nonce)
        require(parts[2] != nonce && Base64.getDecoder().decode(parts[2]).size == 32)
        val identity = Identity(parts[3], parts[4])
        validate(identity)
        validate(localIdentity)
        // Also rejects an oracle on another local socket sharing this pair key.
        require(identity.uid != localIdentity.uid && identity.publicKey != localIdentity.publicKey)
        require(MessageDigest.isEqual(mac(key, parts.take(5).joinToString("|")), Base64.getDecoder().decode(parts[5])))
        consumed = true
        identity
    }.getOrNull()

    fun answer(key: String, challenge: String, uid: String, publicKey: String): String? = runCatching {
            require(challenge.length <= 128)
            val parts = decode(challenge).split('|')
            require(parts.size == 2 && parts[0] == "ID_CHALLENGE")
            require(parts[1] != nonce)
            require(Base64.getDecoder().decode(parts[1]).size == 32)
            validate(Identity(uid, publicKey))
            val body = "ID_PROOF|${parts[1]}|$nonce|$uid|$publicKey"
            envelope("$body|${Base64.getEncoder().encodeToString(mac(key, body))}")
        }.getOrNull()

    companion object {
        private const val PREFIX = "PEER_IDENTITY|V2:"
        fun isEnvelope(frame: String): Boolean = frame.startsWith(PREFIX)
        fun kind(frame: String): Kind? = runCatching {
            when (decode(frame).substringBefore('|')) {
                "ID_CHALLENGE" -> Kind.CHALLENGE
                "ID_PROOF" -> Kind.PROOF
                else -> null
            }
        }.getOrNull()
        // Legacy peers recognize PEER_IDENTITY but require three pipe-separated fields.
        // Two fields therefore neither enrich a pin nor appear as a legacy chat bubble.
        private fun envelope(body: String): String = PREFIX + Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8)) + "\n"
        private fun decode(frame: String): String {
            require(frame.length <= 2048 && isEnvelope(frame))
            return Base64.getDecoder().decode(frame.trimEnd('\r', '\n').removePrefix(PREFIX)).toString(Charsets.UTF_8)
        }

        private fun validate(identity: Identity) {
            require(identity.uid.length in 1..128 && identity.uid.none { it == '|' || it.isWhitespace() || it.isISOControl() })
            val der = Base64.getDecoder().decode(identity.publicKey)
            val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(der))
                as java.security.interfaces.ECPublicKey
            require(key.params.curve.field.fieldSize == 256)
        }

        private fun mac(key: String, body: String): ByteArray {
            val bytes = Base64.getDecoder().decode(key)
            require(bytes.size == 16 || bytes.size == 32)
            return Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(bytes, "HmacSHA256"))
                doFinal("cc-rfcomm-identity-v1|$body".toByteArray(Charsets.UTF_8))
            }
        }
    }
}
