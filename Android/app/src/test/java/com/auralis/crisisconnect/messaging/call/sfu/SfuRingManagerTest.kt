package com.auralis.crisisconnect.messaging.call.sfu

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SfuRingManagerTest {

    @Test
    fun `offer without explicit secure v2 is rejected before state mutation`() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val states = mutableListOf<SfuCallState>()
        val rooms = mutableListOf<Triple<String, Boolean, SfuProtocolVersion>>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val ring = SfuRingManager(
            scope = scope,
            sender = SfuSignalSender { uid, signal -> sent += uid to signal },
            onState = states::add,
            onRoom = { roomId, isCaller, version -> rooms += Triple(roomId, isCaller, version) },
        )

        ring.handleSignal(
            "peer",
            JSONObject()
                .put("type", "offer")
                .put("callId", "00000000-0000-4000-8000-000000000001")
                .put("roomId", "00000000-0000-4000-8000-000000000002"),
        )

        assertEquals(SfuCallState.IDLE, ring.state)
        assertEquals(SfuProtocolVersion.SECURE, ring.protocolVersion)
        assertTrue(states.isEmpty())
        assertTrue(rooms.isEmpty())
        assertEquals("peer", sent.single().first)
        assertEquals("reject", sent.single().second.getString("type"))
        ring.dispose()
    }

    @Test
    fun `explicit secure v2 offer remains a legitimate incoming call`() {
        val states = mutableListOf<SfuCallState>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val ring = SfuRingManager(
            scope = scope,
            sender = SfuSignalSender { _, _ -> },
            onState = states::add,
            onRoom = { _, _, _ -> },
        )

        ring.handleSignal(
            "peer",
            JSONObject()
                .put("type", "offer")
                .put("callId", "00000000-0000-4000-8000-000000000001")
                .put("roomId", "00000000-0000-4000-8000-000000000002")
                .put("sfuVersion", 2),
        )

        assertEquals(SfuCallState.INCOMING, ring.state)
        assertEquals(SfuProtocolVersion.SECURE, ring.protocolVersion)
        assertEquals(listOf(SfuCallState.INCOMING), states)
        ring.dispose()
    }

    @Test
    fun `new caller terminates when callee answers without secure v2`() {
        val rooms = mutableListOf<SfuProtocolVersion>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val ring = SfuRingManager(
            scope = scope,
            sender = SfuSignalSender { _, _ -> },
            onState = {},
            onRoom = { _, _, version -> rooms += version },
        )
        ring.startCall("peer", "Peer")
        val callId = ring.callId

        ring.handleSignal("peer", JSONObject().put("type", "answer").put("callId", callId))

        assertEquals(SfuCallState.ENDED, ring.state)
        assertEquals(SfuProtocolVersion.SECURE, ring.protocolVersion)
        assertTrue(rooms.isEmpty())
        ring.dispose()
    }

    @Test
    fun `control signal from an account other than the bound peer is ignored`() {
        val rooms = mutableListOf<SfuProtocolVersion>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val ring = SfuRingManager(
            scope = scope,
            sender = SfuSignalSender { _, _ -> },
            onState = {},
            onRoom = { _, _, version -> rooms += version },
        )
        ring.startCall("peer", "Peer")

        ring.handleSignal(
            "third-party",
            JSONObject().put("type", "answer").put("callId", ring.callId).put("sfuVersion", 2),
        )

        assertEquals(SfuCallState.OUTGOING, ring.state)
        assertTrue(rooms.isEmpty())
        ring.dispose()
    }
}
