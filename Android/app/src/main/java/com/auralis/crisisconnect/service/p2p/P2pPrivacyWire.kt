package com.auralis.crisisconnect.service.p2p

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Metadata is available only with the QR secret; packet routes contain no permanent device ID. */
object P2pPrivacyWire {
    val privateIdentity get() = "private:v2".toByteArray(Charsets.UTF_8)
    const val MAX_CONTROL_BYTES = 60_000
    enum class Purpose(val header: String) { BOOTSTRAP("CPB2"), CONTROL("CPC2") }
    fun seal(plaintext: ByteArray, key: ByteArray, purpose: Purpose): ByteArray? = runCatching {
        require(key.size == 32 && plaintext.size in 1..MAX_CONTROL_BYTES - 32)
        val header = purpose.header.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        header + nonce + cipher.doFinal(plaintext)
    }.getOrNull()
    fun open(wire: ByteArray, key: ByteArray, purpose: Purpose): ByteArray? = runCatching {
        val header = purpose.header.toByteArray(Charsets.UTF_8)
        require(key.size == 32 && wire.size in 33..MAX_CONTROL_BYTES && wire.copyOfRange(0, 4).contentEquals(header))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wire.copyOfRange(4, 16)))
        cipher.updateAAD(header)
        cipher.doFinal(wire.copyOfRange(16, wire.size))
    }.getOrNull()
    fun alias(key: ByteArray, packet: ByteArray, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String {
        require(key.size == 32 && salt.size == 16)
        return "p2p2.${hex(salt)}.${hex(aliasTag(key, packet, salt))}"
    }
    fun matches(alias: String, key: ByteArray, packet: ByteArray): Boolean = runCatching {
        require(key.size == 32)
        val parts = alias.split('.')
        require(parts.size == 3 && parts[0] == "p2p2")
        MessageDigest.isEqual(unhex(parts[2]), aliasTag(key, packet, unhex(parts[1])))
    }.getOrDefault(false)
    private fun aliasTag(key: ByteArray, packet: ByteArray, salt: ByteArray): ByteArray {
        val input = "cc-p2p-route-v2".toByteArray(Charsets.UTF_8) + salt + MessageDigest.getInstance("SHA-256").digest(packet)
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(input).copyOf(16) }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(value: String): ByteArray {
        require(value.length == 32 && value.all { it in "0123456789abcdef" })
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    class Challenge(private val issuedAt: Long = System.nanoTime()) {
        val nonce = java.util.UUID.randomUUID().toString()
        private var consumed = false
        @Synchronized fun accept(response: String?, now: Long = System.nanoTime()): Boolean {
            if (consumed || now < issuedAt || now - issuedAt > 10_000_000_000L || response != nonce) return false
            consumed = true
            return true
        }
    }
    class ControlReceiver {
        private val data = ByteArrayOutputStream()
        private var started = System.nanoTime()
        val isExpired: Boolean get() = System.nanoTime() - started > 10_000_000_000L
        @Synchronized fun receive(chunk: ByteArray): ByteArray? {
            val now = System.nanoTime()
            if (now - started > 10_000_000_000L) data.reset()
            if (data.size() == 0) started = now
            if (chunk.isEmpty() || data.size() + chunk.size > MAX_CONTROL_BYTES + 2) { data.reset(); return null }
            data.write(chunk)
            val bytes = data.toByteArray()
            if (bytes.size < 2) return null
            val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            if (length !in 33..MAX_CONTROL_BYTES || bytes.size > length + 2) { data.reset(); return null }
            if (bytes.size != length + 2) return null
            data.reset(); return bytes.copyOfRange(2, bytes.size)
        }
    }
    fun frameControl(wire: ByteArray): ByteArray? {
        if (wire.size !in 33..MAX_CONTROL_BYTES) return null
        return byteArrayOf((wire.size shr 8).toByte(), wire.size.toByte()) + wire
    }
}
