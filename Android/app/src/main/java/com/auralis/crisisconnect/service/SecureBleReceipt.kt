package com.auralis.crisisconnect.service

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated receipts for the SOS GATT link, under its negotiated session key. */
object SecureBleReceipt {
    private val magic = "CAR2".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    fun seal(key: ByteArray, payload: ByteArray, now: Long = System.currentTimeMillis()): ByteArray? = runCatching {
        require(key.size == 32 && payload.size in 1..400)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(magic)
        }
        magic + nonce + cipher.doFinal(ByteBuffer.allocate(8).putLong(now).array() + payload)
    }.getOrNull()
    fun open(key: ByteArray, wire: ByteArray, now: Long = System.currentTimeMillis()): ByteArray? = runCatching {
        require(key.size == 32 && wire.size in 41..440 && wire.copyOfRange(0, 4).contentEquals(magic))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wire.copyOfRange(4, 16))); updateAAD(magic)
        }
        val body = ByteBuffer.wrap(cipher.doFinal(wire.copyOfRange(16, wire.size)))
        val timestamp = body.long
        require(timestamp >= now - 600_000L && timestamp <= now + 120_000L)
        ByteArray(body.remaining()).also { body.get(it) }
    }.getOrNull()
}
