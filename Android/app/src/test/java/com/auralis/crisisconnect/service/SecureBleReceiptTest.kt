package com.auralis.crisisconnect.service

import org.junit.Assert.*
import org.junit.Test

class SecureBleReceiptTest {
    @Test fun authenticatesPayloadAndRejectsPlaintextWrongKeyTamperingAndExpiredReceipts() {
        val key = ByteArray(32) { it.toByte() }
        for (plain in listOf("READ|message-123", "DELIVERED|message-123")) {
            val wire = SecureBleReceipt.seal(key, plain.toByteArray(), 1_000_000)!!
            assertArrayEquals(plain.toByteArray(), SecureBleReceipt.open(key, wire, 1_000_000))
            assertNull(SecureBleReceipt.open(ByteArray(32), wire, 1_000_000))
            assertNull(SecureBleReceipt.open(key, plain.toByteArray(), 1_000_000))
            assertNull(SecureBleReceipt.open(key, wire, 1_600_001))
            for (index in wire.indices) {
                val changed = wire.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
                assertNull(SecureBleReceipt.open(key, changed, 1_000_000))
            }
        }
    }
}
