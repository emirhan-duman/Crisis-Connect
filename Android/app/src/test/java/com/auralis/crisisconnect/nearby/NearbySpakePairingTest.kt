package com.auralis.crisisconnect.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Exercises the transport-agnostic SPAKE2 pairing session end-to-end (no GATT): matching numbers
 * complete and exchange identities; a mismatched number fails at key confirmation (the harvesting
 * defense — a wrong guess reveals nothing and yields no pairing).
 */
class NearbySpakePairingTest {

    private val alice = NearbySpakePairing.NearbyIdentity(
        uid = "uidAlice", publicKeyBase64 = "ALICEPUBKEYb64", displayName = "Alice"
    )
    private val bob = NearbySpakePairing.NearbyIdentity(
        uid = "uidBob", publicKeyBase64 = "BOBPUBKEYb64", displayName = "Bob"
    )

    @Test
    fun matchingNumber_completesAndExchangesIdentities() {
        // Bob (initiator) is looking for Alice; both resolve the same number → same w.
        val number = "+905551112233"
        val w = NearbySpakePairing.deriveW(number)

        val initiator = NearbySpakePairing.Initiator(w, bob)   // searcher
        val responder = NearbySpakePairing.Responder(w, alice) // discoverable device (Alice)

        val msg1 = initiator.message1()
        val msg2 = responder.onMessage1(msg1)
        val msg3 = initiator.onMessage2(msg2)
        val peerSeenByResponder = responder.onMessage3(msg3)
        val msg4 = responder.responseMessage(NearbySpakePairing.STATUS_OK)
        val peerSeenByInitiator = initiator.onMessage4(msg4)

        // Initiator (Bob) learns Alice's identity; responder (Alice) learns Bob's.
        assertEquals(alice, peerSeenByInitiator)
        assertEquals(bob, peerSeenByResponder)
    }

    @Test
    fun mismatchedNumber_failsAtConfirmation() {
        val initiator = NearbySpakePairing.Initiator(
            NearbySpakePairing.deriveW("+905551112233"), bob
        )
        val responder = NearbySpakePairing.Responder(
            NearbySpakePairing.deriveW("+905559998877"), alice // different number
        )

        val msg2 = responder.onMessage1(initiator.message1())
        // The initiator cannot verify the responder's confirmation → aborts, learns nothing.
        assertThrows(Exception::class.java) { initiator.onMessage2(msg2) }
    }

    @Test
    fun pendingResponse_carriesNoIdentity() {
        val w = NearbySpakePairing.deriveW("+905551112233")
        val initiator = NearbySpakePairing.Initiator(w, bob)
        val responder = NearbySpakePairing.Responder(w, alice)

        val msg2 = responder.onMessage1(initiator.message1())
        val msg3 = initiator.onMessage2(msg2)
        responder.onMessage3(msg3)
        val pending = responder.responseMessage(NearbySpakePairing.STATUS_PENDING)

        // While consent is pending the initiator must not receive an identity.
        assertThrows(Exception::class.java) { initiator.onMessage4(pending) }
    }
}
