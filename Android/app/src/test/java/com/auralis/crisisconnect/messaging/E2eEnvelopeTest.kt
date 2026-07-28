package com.auralis.crisisconnect.messaging

import com.auralis.crisisconnect.security.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class E2eEnvelopeTest {

    @Test
    fun seal_thenOpen_roundTripsText() {
        val sender = Crypto.generateEphemeralEcKeyPair()
        val recipient = Crypto.generateEphemeralEcKeyPair()
        val content = MessageContent(templateCode = 0, text = "Enkaz altındayım, 2 kişi")

        val sealed = E2eEnvelope.seal(
            content = content,
            recipientPublicKey = recipient.public,
            senderPrivateKey = sender.private,
            senderUid = "sender-1",
            recipientUid = "recipient-1",
            conversationId = "conv-1"
        )
        val opened = E2eEnvelope.open(
            sealed = sealed,
            recipientPrivateKey = recipient.private,
            senderStaticPublicKey = sender.public,
            senderUid = "sender-1",
            recipientUid = "recipient-1",
            conversationId = "conv-1"
        )

        assertEquals(content.text, opened.text)
        assertEquals(content.templateCode, opened.templateCode)
    }

    @Test
    fun templateCode_roundTripsWithoutText() {
        val sender = Crypto.generateEphemeralEcKeyPair()
        val recipient = Crypto.generateEphemeralEcKeyPair()
        val sealed = E2eEnvelope.seal(
            content = MessageContent(templateCode = 7, text = ""),
            recipientPublicKey = recipient.public,
            senderPrivateKey = sender.private,
            senderUid = "s",
            recipientUid = "r",
            conversationId = "c"
        )
        val opened = E2eEnvelope.open(sealed, recipient.private, sender.public, "s", "r", "c")

        assertEquals(7, opened.templateCode)
        assertEquals("", opened.text)
    }

    @Test
    fun open_withMismatchedConversation_fails() {
        // conversationId feeds both the HKDF info and the GCM associated data, so a message
        // sealed for one conversation cannot be opened under another.
        val sender = Crypto.generateEphemeralEcKeyPair()
        val recipient = Crypto.generateEphemeralEcKeyPair()
        val sealed = E2eEnvelope.seal(
            content = MessageContent(templateCode = 0, text = "hello"),
            recipientPublicKey = recipient.public,
            senderPrivateKey = sender.private,
            senderUid = "s",
            recipientUid = "r",
            conversationId = "conv-1"
        )
        assertThrows(Exception::class.java) {
            E2eEnvelope.open(sealed, recipient.private, sender.public, "s", "r", "conv-2")
        }
    }

    @Test
    fun open_withWrongSenderKey_fails() {
        // Static-static ECDH mixes the sender's identity key into the message key, so opening the
        // message under a DIFFERENT sender key (e.g. a forger/server that knows only public keys)
        // derives a different key and fails to authenticate — this is the sender-authentication win.
        val sender = Crypto.generateEphemeralEcKeyPair()
        val attacker = Crypto.generateEphemeralEcKeyPair()
        val recipient = Crypto.generateEphemeralEcKeyPair()
        val sealed = E2eEnvelope.seal(
            content = MessageContent(templateCode = 0, text = "authentic"),
            recipientPublicKey = recipient.public,
            senderPrivateKey = sender.private,
            senderUid = "sender-1",
            recipientUid = "recipient-1",
            conversationId = "conv-1"
        )
        assertThrows(Exception::class.java) {
            E2eEnvelope.open(
                sealed = sealed,
                recipientPrivateKey = recipient.private,
                senderStaticPublicKey = attacker.public,
                senderUid = "sender-1",
                recipientUid = "recipient-1",
                conversationId = "conv-1"
            )
        }
    }
}
