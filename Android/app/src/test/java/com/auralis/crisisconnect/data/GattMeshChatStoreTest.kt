package com.auralis.crisisconnect.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GattMeshChatStoreTest {

    @Before
    fun setUp() {
        GattMeshChatStore.clear()
    }

    @After
    fun tearDown() {
        GattMeshChatStore.clear()
    }

    @Test
    fun delayedRemoteMessage_isSortedByOriginalSendTime() {
        GattMeshChatStore.appendRemoteMessage(
            id = "first",
            text = "first",
            senderLabel = "A",
            sourceAddress = null,
            timestampMillis = 1_000L,
            receivedTimestampMillis = 1_000L
        )
        GattMeshChatStore.appendRemoteMessage(
            id = "third",
            text = "third",
            senderLabel = "C",
            sourceAddress = null,
            timestampMillis = 5_000L,
            receivedTimestampMillis = 5_000L
        )

        GattMeshChatStore.appendRemoteMessage(
            id = "second",
            text = "second",
            senderLabel = "B",
            sourceAddress = null,
            timestampMillis = 2_000L,
            receivedTimestampMillis = 10_000L
        )

        val messages = GattMeshChatStore.currentMessages()

        assertEquals(listOf("first", "second", "third"), messages.map { it.id })
        assertEquals(2_000L, messages[1].timestampMillis)
        assertEquals(10_000L, messages[1].receivedTimestampMillis)
    }

    @Test
    fun onTimeRemoteMessage_doesNotExposeReceivedTimestamp() {
        GattMeshChatStore.appendRemoteMessage(
            id = "remote",
            text = "remote",
            senderLabel = "A",
            sourceAddress = null,
            timestampMillis = 7_000L,
            receivedTimestampMillis = 7_000L
        )

        val stored = GattMeshChatStore.currentMessages().single()

        assertEquals(7_000L, stored.timestampMillis)
        assertNull(stored.receivedTimestampMillis)
    }
}
