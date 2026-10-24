package com.auralis.crisisconnect.service

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureRfcommReceipt {
    enum class Kind(val code: Int) { DELIVERED(1), READ(2) }
    data class Receipt(val kind: Kind, val messageId: String)
    private val aad = "cc-rfcomm-receipt-v1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    private val idPattern = Regex("[A-Za-z0-9:_-]{1,128}")
    fun seal(keyBase64: String, kind: Kind, messageId: String, now: Long = System.currentTimeMillis()): String? = runCatching {
        require(messageId.matches(idPattern))
        val key = Base64.getDecoder().decode(keyBase64); require(key.size == 32)
        val id = messageId.toByteArray(Charsets.UTF_8)
        val body = ByteBuffer.allocate(11 + id.size).put(kind.code.toByte()).putLong(now).putShort(id.size.toShort()).put(id).array()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad)
        }
        "SEC_RECEIPT|" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(body)) + "\n"
    }.getOrNull()

    fun open(keyBase64: String, payload: String, now: Long = System.currentTimeMillis()): Receipt? = runCatching {
        require(payload.length <= 512 && payload.startsWith("SEC_RECEIPT|"))
        val key = Base64.getDecoder().decode(keyBase64); require(key.size == 32)
        val bytes = Base64.getDecoder().decode(payload.trim().substringAfter('|')); require(bytes.size >= 39)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(aad)
        }
        val body = ByteBuffer.wrap(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        val kindCode = body.get().toInt() and 255
        val kind = Kind.entries.firstOrNull { it.code == kindCode } ?: return null
        val timestamp = body.long; require(timestamp >= now - 86_400_000L && timestamp <= now + 120_000L)
        val length = body.short.toInt() and 65535; require(length == body.remaining() && length in 1..128)
        val id = ByteArray(length).also { body.get(it) }.toString(Charsets.UTF_8); require(id.matches(idPattern))
        Receipt(kind, id)
    }.getOrNull()
}
