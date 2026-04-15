package com.auralis.crisisconnect.util

import java.util.Locale
import java.util.UUID
import kotlin.random.Random

object UUIDGenerator {

    private const val BASE_PREFIX = 0xCC00 // Crisis Connect'e özel prefix (üst 8-bit)

    private fun buildUuidFromAssignedNumber(assignedNumber: Int): UUID {
        require(assignedNumber in 0x0000..0xFFFF) {
            "Assigned number must fit in 16 bits."
        }

        val uuidString = String.format(
            Locale.US,
            "0000%04X-0000-1000-8000-00805F9B34FB",
            assignedNumber
        )
        return UUID.fromString(uuidString)
    }

    /**
     * Crisis Connect'e ait, benzersiz bir UUID üretir.
     * Format: 0000CCxx-0000-1000-8000-00805F9B34FB
     */
    fun generateRandomServiceUUID(): UUID {
        val randomSuffix = Random.nextInt(0x10, 0x100) // 0xCC10-0xCCFF servisler için ayrıldı
        val assignedNumber = BASE_PREFIX or randomSuffix
        return buildUuidFromAssignedNumber(assignedNumber)
    }

    /**
     * Belirli bir AES key'e göre sabit UUID üretmek için (opsiyonel).
     * Prefix sabit tutulurken hash'in alt 8-bit'i kullanılır.
     */
    fun generateDeterministicUUID(aesKey: String): UUID {
        val hash = aesKey.hashCode() and 0xFF
        val assignedNumber = BASE_PREFIX or hash
        return buildUuidFromAssignedNumber(assignedNumber)
    }

    fun fromAssignedNumber(assignedNumber: Int): UUID = buildUuidFromAssignedNumber(assignedNumber)
}