package com.auralis.crisisconnect.watch
import org.junit.Assert.*
import org.junit.Test

class WatchCommandTest {
    @Test fun expiredAndFutureCommandsAreRejected() {
        val command = WatchCommand(action = WatchAction.startSOS, issuedAt = 1_000_000)
        assertTrue(command.valid(1_001_000))
        assertFalse(command.valid(1_030_000))
        assertFalse(command.valid(994_000))
    }
    @Test fun stopRequiresEmergencyAndReplyRequiresRecipient() {
        assertFalse(WatchCommand(action = WatchAction.stopSOS).valid(System.currentTimeMillis()))
        assertFalse(WatchCommand(action = WatchAction.quickReply, reply = QuickReply.safe).valid(System.currentTimeMillis()))
        assertTrue(WatchCommand(action = WatchAction.quickReply, peerID = "rescue-session", reply = QuickReply.safe).valid(System.currentTimeMillis()))
    }
    @Test fun malformedAndUnsupportedCommandsAreRejected() {
        assertFalse(WatchCommand(id = "invalid", action = WatchAction.startSOS).valid(System.currentTimeMillis()))
        assertFalse(WatchCommand(action = WatchAction.startSOS, version = 2).valid(System.currentTimeMillis()))
        val c = WatchCommand(action = WatchAction.startSOS)
        assertFalse(c.copy(expiresAt = c.issuedAt + 31_000).valid(System.currentTimeMillis()))
    }
    @Test fun contactReplyRequiresRecipientAndTemplate() {
        assertFalse(WatchCommand(action = WatchAction.contactReply, reply = QuickReply.safe).valid(System.currentTimeMillis()))
        assertFalse(WatchCommand(action = WatchAction.contactReply, peerID = "saved-person").valid(System.currentTimeMillis()))
        assertTrue(WatchCommand(action = WatchAction.contactReply, peerID = "saved-person", reply = QuickReply.safe).valid(System.currentTimeMillis()))
    }
}
