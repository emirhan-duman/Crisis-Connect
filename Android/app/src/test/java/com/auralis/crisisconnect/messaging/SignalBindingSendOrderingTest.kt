package com.auralis.crisisconnect.messaging

import android.app.Application
import android.content.Context
import com.auralis.crisisconnect.connectivity.DisasterTrafficController
import com.auralis.crisisconnect.connectivity.SatelliteNetworkStatus
import com.auralis.crisisconnect.connectivity.TrafficMeasurements
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.messaging.signal.SignalMessageTransport
import com.auralis.crisisconnect.messaging.signal.SignalSessionGate
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator

/** Exercise the real sender orchestration; mock only identity crypto, network and gate storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class SignalBindingSendOrderingTest {
    @After fun cleanup() = unmockkAll()

    @Test fun firstApplicationWaitsForDelayedBindingReplyInsteadOfBeingStrandedAsEcies() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val client = mockk<InternetMessagingClient>(relaxed = true)
        val gate = mockk<SignalSessionGate>(relaxed = true)
        mockkObject(SignalSessionGate.Companion, DisasterTrafficController, SatelliteNetworkStatus, TrafficMeasurements, E2eEnvelope)
        every { SignalSessionGate.create(any(), client) } returns gate
        every { client.currentUid() } returns "alice"
        coEvery { client.supportsSignalIdentityBinding("bob") } returns true
        every { SatelliteNetworkStatus.isSatellite(any()) } returns false
        every { SatelliteNetworkStatus.isConstrained(any()) } returns false
        every { DisasterTrafficController.saving } returns MutableStateFlow(false)
        coEvery { DisasterTrafficController.awaitSendSlot(any()) } just Runs
        every { DisasterTrafficController.record(any(), any(), any()) } just Runs
        every { TrafficMeasurements.request(any(), any()) } just Runs
        var bound = false
        var pending = false
        every { gate.hasIdentityBinding("bob") } answers { bound }
        every { gate.identityChangePending("bob") } returns false
        var claimed = false
        every { gate.claimBindingAnnouncement(any(), any()) } answers { if (claimed) false else { claimed = true; true } }
        every { gate.markPeerUpgradeRequired("bob") } answers { pending = true; true }
        every { gate.peerUpgradeRequired("bob") } answers { pending && !bound }
        every { gate.localIdentityBase64() } returns "signal-public-identity"
        coEvery { gate.ensureOutbound("bob", any()) } answers { bound }
        every { gate.encrypt("bob", any()) } returns SignalMessageTransport.Wire(3, "signal-ciphertext")
        every { gate.typeLabel(any()) } returns "prekey"
        val sealedTemplates = mutableListOf<Int>()
        every { E2eEnvelope.seal(any(), any(), any<(java.security.PublicKey) -> ByteArray>(), any(), any(), any()) } answers {
            sealedTemplates.add(firstArg<MessageContent>().templateCode)
            SealedMessage("ephemeral", "nonce", "ciphertext")
        }
        val uploaded = mutableListOf<RelayableEnvelope>()
        coEvery { client.writeMessageDoc(any()) } coAnswers {
            if (sealedTemplates.lastOrNull() == InternetChatTransport.SIGNAL_IDENTITY_BINDING_TEMPLATE) {
                assertTrue("Migration must be persisted before the control reaches the peer", pending)
            }
            uploaded.add(firstArg())
            Unit
        }
        val publicKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public
        val contact = Contact(name = "Bob", aesKey = "", sessionCode = "bob-thread", peerUid = "bob",
            peerPublicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded))
        val transport = InternetChatTransport(context, client)
        assertFalse(transport.sendText("message-1", "Emergency", 1000, contact))
        assertEquals(listOf(InternetChatTransport.SIGNAL_IDENTITY_BINDING_TEMPLATE), sealedTemplates)
        assertEquals(1, uploaded.size) // only the control; application stays in the caller's queue
        bound = true // deliver the authenticated reply, then retry the same application ID
        assertTrue(transport.sendText("message-1", "Emergency", 1000, contact))
        assertTrue(uploaded.last() is OutgoingSignalEnvelope)
        assertEquals("message-1", uploaded.last().messageId)
        assertEquals(listOf(InternetChatTransport.SIGNAL_IDENTITY_BINDING_TEMPLATE), sealedTemplates)
        // A peer outside the cohort still uses the shipped ECIES path without a control bubble.
        bound = false; pending = false
        coEvery { client.supportsSignalIdentityBinding("bob") } returns false
        assertTrue(transport.sendText("legacy-message", "Old peer", 1000, contact))
        assertTrue(uploaded.last() is OutgoingEnvelope)
        assertEquals(listOf(InternetChatTransport.SIGNAL_IDENTITY_BINDING_TEMPLATE, 0), sealedTemplates)
    }
}
