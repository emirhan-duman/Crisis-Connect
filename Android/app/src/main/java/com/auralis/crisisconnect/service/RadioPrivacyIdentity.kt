package com.auralis.crisisconnect.service

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Volatile radio aliases. Neither installation ID nor hardware identifiers enter advertisements. */
object RadioPrivacyIdentity {
    const val PERIOD_NANOS = 15L * 60 * 1_000_000_000
    private val processSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    data class Snapshot(val epoch: Long, val serviceData: ByteArray, val seed: Int) {
        val broadcastId get() = "CC-" + serviceData.joinToString("") { "%02X".format(it) }
        fun rank(legacy: Boolean): Int {
            val mixed = seed xor (seed ushr 16) xor 0x6F4B5D5E
            return if (legacy) mixed or Int.MIN_VALUE else mixed and Int.MAX_VALUE
        }
    }
    val current: Snapshot get() = derive(processSecret, System.nanoTime() / PERIOD_NANOS)
    internal fun derive(secret: ByteArray, epoch: Long): Snapshot {
        val bytes = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal("cc-radio-alias-v2".toByteArray() + ByteBuffer.allocate(8).putLong(epoch).array())
        }
        return Snapshot(epoch, bytes.copyOf(12), ByteBuffer.wrap(bytes, 12, 4).int)
    }
}
