package com.auralis.crisisconnect.muster

import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusterDeliveryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun disk() = object : MusterPersistence {
        override fun read(key: String) = File(folder.root, key).takeIf { it.exists() }?.readText()
        override fun write(key: String, value: String): Boolean { File(folder.root, key).writeText(value); return true }
    }
    private fun receipt(event: MusterEvent) = 200 to JSONObject().put("ok", true).put("id", event.session).put("eventId", musterServerEventId(event.uid, event.operationId))

    @Test fun reconnectAfterRestartPreservesTheOriginalAnswerAndOperationId() = runBlocking {
        val queue = MusterQueue(disk())
        val original = queue.enqueue("alice", "factory", "session", "help", "Stair exit", 1000)
        assertFalse(drainMusterQueue(queue, "alice", { "alice" }, { throw IOException("offline") }, { 1001 }))
        val restarted = MusterQueue(disk())
        assertEquals("pending", restarted.entries("alice").single().state)
        assertTrue(drainMusterQueue(restarted, "alice", { "alice" }, { event ->
            assertEquals(original.request().toString(), event.request().toString())
            receipt(event)
        }, { 200000 }))
        assertEquals("sent", MusterQueue(disk()).entries("alice").single().state)
    }

    @Test fun disconnectAfterServerCommitRetriesTheSameEventWithoutCreatingAnotherAnswer() = runBlocking {
        val queue = MusterQueue(disk())
        queue.enqueue("alice", "factory", "session", "safe", "", 1000)
        val serverEvents = mutableSetOf<String>()
        assertFalse(drainMusterQueue(queue, "alice", { "alice" }, { event -> serverEvents.add(event.operationId); throw IOException("response lost") }, { 1001 }))
        assertTrue(drainMusterQueue(MusterQueue(disk()), "alice", { "alice" }, { event -> serverEvents.add(event.operationId); receipt(event) }, { 200000 }))
        assertEquals(1, serverEvents.size)
    }

    @Test fun missingReceiptStaysPendingAndMembershipRevocationRemainsVisibleAsFailed() = runBlocking {
        val queue = MusterQueue(disk()); queue.enqueue("alice", "factory", "session", "safe", "", 1000)
        assertFalse(drainMusterQueue(queue, "alice", { "alice" }, { 200 to JSONObject().put("ok", true) }, { 1001 }))
        assertEquals("pending", queue.entries("alice").single().state)
        assertTrue(drainMusterQueue(queue, "alice", { "alice" }, { 403 to null }, { 200000 }))
        assertEquals("failed", MusterQueue(disk()).entries("alice").single().state)
    }

    @Test fun anotherSignedInAccountCannotTransmitOrDiscardAnOfflineAnswer() = runBlocking {
        val queue = MusterQueue(disk()); queue.enqueue("alice", "factory", "session", "help", "", 1000)
        var requests = 0
        assertTrue(drainMusterQueue(queue, "alice", { "bob" }, { requests++; receipt(it) }, { 200000 }))
        assertEquals(0, requests)
        assertEquals("pending", MusterQueue(disk()).entries("alice").single().state)
    }
    @Test fun schedulerUsesTheDurableDueTimeAndDoesNotCompoundBackoff() {
        val queue = MusterQueue(disk()); queue.enqueue("alice", "factory", "session", "help", "", 1000)
        assertEquals(0L, nextMusterSendDelay(queue, "alice", 1001))
        val claimed = queue.claim("alice", 1001)!!
        queue.finish(claimed, "pending", 1002, "connection")
        assertEquals(60000L, nextMusterSendDelay(MusterQueue(disk()), "alice", 1002))
        assertEquals(0L, nextMusterSendDelay(queue, "alice", 200000))
        val retried = queue.claim("alice", 200000)!!; queue.finish(retried, "sent", 200001, receiptEventId = musterServerEventId(retried.uid, retried.operationId))
        assertNull(nextMusterSendDelay(queue, "alice", 200002))
    }

    @Test fun aNewVoiceReplyIsReadyWhileAnOlderTextReplyWaitsForRetry() = runBlocking {
        val voiceStore = MusterEncryptedVoiceStore(File(folder.root, "voice"), {
            SecretKeySpec(ByteArray(32) { 7 }, "AES")
        })
        val config = MusterResponseConfig("", emptyList(), allowText = true, allowVoice = true)
        val queue = MusterQueue(disk(), voiceStore)
        val originalText = queue.enqueueFlexible("alice", "factory", "session", config,
            MusterSubmission("message", "Offline text"), 1000, "text")
        assertFalse(drainMusterQueue(queue, "alice", { "alice" }, { throw IOException("offline") }, { 1001 }))

        val delayed = checkNotNull(nextMusterSendDelay(queue, "alice", 1001))
        assertEquals(60000L, delayed)
        val delayedWork = musterSendWorkName("alice", 1001 + delayed)
        val restarted = MusterQueue(disk(), voiceStore)
        val audio = MusterVoiceDraft(byteArrayOf(1, 2, 3, 4), 1000)
        val voice = restarted.enqueueFlexible("alice", "factory", "session", config,
            MusterSubmission("message", "", voice = audio), 2000, "voice")
        val ready = checkNotNull(nextMusterSendDelay(restarted, "alice", 2000))
        assertEquals(0L, ready)
        assertNotEquals(delayedWork, musterSendWorkName("alice", ready))

        val attempts = mutableListOf<String>()
        assertFalse(drainMusterQueue(restarted, "alice", { "alice" }, { event ->
            attempts += event.operationId
            assertEquals(voice.operationId, event.operationId)
            assertArrayEquals(audio.bytes, restarted.voiceBytes(event))
            receipt(event)
        }, { 2000 }))
        val remainingDelay = checkNotNull(nextMusterSendDelay(restarted, "alice", 2000))
        assertEquals(delayedWork, musterSendWorkName("alice", 2000 + remainingDelay))
        assertTrue(drainMusterQueue(restarted, "alice", { "alice" }, { event ->
            attempts += event.operationId
            assertEquals(originalText.request().toString(), event.request().toString())
            receipt(event)
        }, { 61001 }))
        assertEquals(listOf("voice", "text"), attempts)
        assertTrue(restarted.entries("alice").all { it.state == "sent" })
    }

}
