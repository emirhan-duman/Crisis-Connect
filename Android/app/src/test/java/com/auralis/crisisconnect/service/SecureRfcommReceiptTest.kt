package com.auralis.crisisconnect.service

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecureRfcommReceiptTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    @Test fun acceptsOnlyAuthenticatedFreshReceiptsAndBindsKindAndId() {
        for (kind in SecureRfcommReceipt.Kind.entries) {
            val packet = SecureRfcommReceipt.seal(key, kind, "message-123", 1_000_000L)!!
            assertEquals(SecureRfcommReceipt.Receipt(kind, "message-123"), SecureRfcommReceipt.open(key, packet, 1_000_000L))
            assertNull(SecureRfcommReceipt.open(Base64.getEncoder().encodeToString(ByteArray(32)), packet, 1_000_000L))
            assertNull(SecureRfcommReceipt.open(key, packet, 1_000_000L + 86_400_001L))
            val raw = Base64.getDecoder().decode(packet.trim().substringAfter('|'))
            for (index in raw.indices) {
                val changed = raw.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
                assertNull(SecureRfcommReceipt.open(key, "SEC_RECEIPT|" + Base64.getEncoder().encodeToString(changed), 1_000_000L))
            }
        }
        assertNull(SecureRfcommReceipt.open(key, "ACK|message-123", 1_000_000L))
        assertNull(SecureRfcommReceipt.open(key, "DELIVERED|message-123", 1_000_000L))
    }
}
