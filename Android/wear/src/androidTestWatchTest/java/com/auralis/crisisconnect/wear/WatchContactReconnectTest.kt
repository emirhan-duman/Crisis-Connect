package com.auralis.crisisconnect.wear

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.watch.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host restarts the phone process before this test; all recipients use the isolated relay. */
class WatchContactReconnectTest {
    @Test fun reconnectKeepsOutcomesAndRejectsDuplicateDelivery() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val transport = DevLinkTransport(args.getString("dev_link_token")!!)
        val initial = transport.exchange(WatchCommand(action = WatchAction.status))
        assertFalse(initial.state.sosActive)
        assertEquals("accepted", initial.state.contacts.first { it.id == "watch-contact-a" }.lastReplyResult)
        assertEquals("noPeer", initial.state.contacts.first { it.id == "watch-contact-b" }.lastReplyResult)
        for ((key, peer, reply, expected) in listOf(
            listOf("accepted_id", "watch-contact-a", "safe", "accepted"),
            listOf("failed_id", "watch-contact-b", "needHelp", "noPeer")
        )) {
            val receipt = transport.exchange(WatchCommand(id = args.getString(key)!!,
                action = WatchAction.contactReply, peerID = peer, reply = QuickReply.valueOf(reply)))
            assertEquals(expected, receipt.result)
            assertFalse(receipt.state.sosActive)
        }
        repeat(4) {
            kotlinx.coroutines.delay(5000)
            assertFalse(transport.exchange(WatchCommand(action = WatchAction.status)).state.sosActive)
        }
    }
}
