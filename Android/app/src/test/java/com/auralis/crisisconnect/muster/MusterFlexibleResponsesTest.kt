package com.auralis.crisisconnect.muster

import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusterFlexibleResponsesTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : MusterPersistence {
        val values = mutableMapOf<String, String>(); var fail = false
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String): Boolean { if (fail) return false; values[key] = value; return true }
    }
    private val config = MusterResponseConfig("Where are you?", listOf(MusterOption("needs_access", "Exit is blocked", "help")), true, true)
    private fun voices(limit: Long = 4 * 1024 * 1024, total: Long = 32 * 1024 * 1024) = MusterEncryptedVoiceStore(temporary.root, { SecretKeySpec(ByteArray(32) { 5 }, "AES") }, limit, total)
    private fun voice() = MusterVoiceDraft(ByteArray(500) { (it % 251).toByte() }, 1234)

    @Test fun frozenChoicesCannotInventSafetyAndStandaloneMessagesStayNeutral() {
        val queue = MusterQueue(Memory(), voices())
        assertThrows(IllegalArgumentException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("safe", "", "needs_access"), 1) }
        assertThrows(IllegalArgumentException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("help", "hello"), 1) }
        assertThrows(IllegalArgumentException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", ""), 1) }
        val message = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "Need an interpreter"), 1)
        assertEquals("message", message.answer); assertEquals(2, message.request().getInt("schemaVersion")); assertFalse(message.request().has("optionId"))
        val help = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("help", "", "needs_access"), 2)
        assertEquals("needs_access", help.request().getString("optionId"))
    }
    @Test fun textAndVoicePermissionsAreEnforcedByTheQueueAndLegacyKeepsItsLimit() {
        val queue = MusterQueue(Memory(), voices())
        assertThrows(IllegalArgumentException::class.java) { queue.enqueue("alice", "factory", "session", "message", "hello", 1) }
        assertThrows(IllegalArgumentException::class.java) { queue.enqueue("alice", "factory", "session", "safe", "x".repeat(501), 1) }
        assertThrows(IllegalArgumentException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config.copy(allowText = false), MusterSubmission("message", "hello"), 1) }
        assertThrows(IllegalArgumentException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config.copy(allowVoice = false), MusterSubmission("message", "", voice = voice()), 1) }
        assertThrows(IllegalArgumentException::class.java) { MusterVoiceDraft(ByteArray(MUSTER_VOICE_MAX_BYTES + 1), 1000) }
        assertThrows(IllegalArgumentException::class.java) { MusterVoiceDraft(ByteArray(20), 30_001) }
    }
    @Test fun audioIsEncryptedAccountBoundAndNeverEmbeddedInThePreferenceJournal() {
        val storage = Memory(); val vault = voices(); val queue = MusterQueue(storage, vault); val draft = voice()
        val event = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = draft), 77, "stable-operation")
        val file = File(File(temporary.root, musterHash("alice".toByteArray())), event.voice!!.file)
        assertFalse(file.readBytes().contentEquals(draft.bytes))
        assertTrue(storage.values.values.single().length < 1200)
        assertFalse(JSONObject(JSONArray(storage.values.values.single()).getJSONObject(0).toString()).has("voice"))
        assertArrayEquals(draft.bytes, MusterQueue(storage, voices()).voiceBytes(event))
        assertThrows(Exception::class.java) { vault.read("bob", event.voice) }
        val copied = File(File(temporary.root, musterHash("bob".toByteArray())).apply { mkdirs() }, event.voice.file)
        file.copyTo(copied)
        assertThrows(Exception::class.java) { vault.read("bob", event.voice) }
        assertTrue(queue.entries("bob").isEmpty())
        val packet = file.readBytes(); packet[packet.lastIndex] = (packet.last().toInt() xor 1).toByte(); file.writeBytes(packet)
        assertThrows(Exception::class.java) { vault.read("alice", event.voice) }
    }
    @Test fun restartAndRetryKeepTheSameOperationTimestampHashAndPayload() {
        val storage = Memory(); val first = MusterQueue(storage, voices())
        val event = first.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "Stairwell", voice = voice()), 1234, "stable-operation")
        val restarted = MusterQueue(storage, voices()); val claimed = restarted.claim("alice", 2000)!!
        restarted.finish(claimed, "pending", 2001)
        val retry = restarted.claim("alice", 999999)!!
        assertEquals(event.operationId, retry.operationId); assertEquals(1234L, retry.answeredAt)
        assertEquals(event.voice!!.sha256, retry.voice!!.sha256)
        assertArrayEquals(first.voiceBytes(event), restarted.voiceBytes(retry))
        assertEquals(event.request("base64").toString(), retry.request("base64").toString())
        assertThrows(IllegalArgumentException::class.java) { restarted.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "Different", voice = voice()), 1234, "stable-operation") }
    }
    @Test fun noReceiptOrPermanentRejectionKeepsAudioAndStrictReceiptCleansItAfterDurableAck() = runTest {
        val storage = Memory(); val vault = voices(); val queue = MusterQueue(storage, vault)
        val event = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 1)
        drainMusterQueue(queue, "alice", { "alice" }, { 200 to JSONObject().put("ok", true) }, now = { 2 })
        assertEquals("pending", queue.entries("alice").single().state); assertNotNull(queue.voiceBytes(event))
        drainMusterQueue(queue, "alice", { "alice" }, { 403 to JSONObject() }, now = { 999999 })
        assertEquals("failed", queue.entries("alice").single().state); assertNotNull(queue.voiceBytes(event))
        // A write failure cannot erase the only durable copy of an unacknowledged voice note.
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { queue.finish(event, "sent", 999999, receiptEventId = musterServerEventId(event.uid, event.operationId)) }
        assertNotNull(queue.voiceBytes(event)); storage.fail = false
        queue.finish(event, "sent", 999999, receiptEventId = musterServerEventId(event.uid, event.operationId))
        assertEquals(musterServerEventId(event.uid, event.operationId), queue.entries("alice").single().receiptEventId)
        assertThrows(Exception::class.java) { queue.voiceBytes(event) }
    }
    @Test fun anotherEventReceiptForTheSameSessionCannotDeleteThePendingVoice() = runTest {
        val storage = Memory(); val queue = MusterQueue(storage, voices())
        val event = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 1)
        val foreign = JSONObject().put("ok", true).put("id", "session").put("eventId", musterServerEventId("alice", "another-operation"))
        assertFalse(drainMusterQueue(queue, "alice", { "alice" }, { 200 to foreign }, now = { 2 }))
        assertEquals("pending", queue.entries("alice").single().state); assertNotNull(queue.voiceBytes(event))
        assertThrows(IllegalArgumentException::class.java) { queue.finish(event, "sent", 3, receiptEventId = foreign.getString("eventId")) }
        assertEquals("pending", queue.entries("alice").single().state); assertNotNull(queue.voiceBytes(event))
        val accepted = JSONObject().put("ok", true).put("id", "session").put("eventId", musterServerEventId(event.uid, event.operationId)).put("applied", false)
        assertTrue(drainMusterQueue(queue, "alice", { "alice" }, { 200 to accepted }, now = { 999999 }))
        assertEquals("sent", queue.entries("alice").single().state); assertThrows(Exception::class.java) { queue.voiceBytes(event) }
    }
    @Test fun failedPersistenceAndDiskBudgetRefuseNewAudioWithoutEvictingPendingAnswers() {
        val storage = Memory(); val vault = voices(600); val queue = MusterQueue(storage, vault)
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 1) }
        assertEquals(1, temporary.root.walkTopDown().count { it.isFile })
        val orphan = temporary.root.walkTopDown().single { it.isFile }
        assertTrue(orphan.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)); storage.fail = false
        val event = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 1)
        assertThrows(IllegalStateException::class.java) { queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 2) }
        assertEquals(1, queue.entries("alice").size); assertNotNull(queue.voiceBytes(event))
        assertEquals("message", queue.enqueueFlexible("bob", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 3).answer)
    }
    @Test fun totalDeviceBudgetProtectsEveryAccountsPendingVoiceInsteadOfEvictingIt() {
        val queue = MusterQueue(Memory(), voices(limit = 10000, total = 1100))
        val alice = queue.enqueueFlexible("alice", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 1)
        val bob = queue.enqueueFlexible("bob", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 2)
        assertThrows(IllegalStateException::class.java) { queue.enqueueFlexible("charlie", "factory", "session", config, MusterSubmission("message", "", voice = voice()), 3) }
        assertTrue(queue.entries("charlie").isEmpty())
        assertEquals("pending", queue.entries("alice").single().state); assertArrayEquals(voice().bytes, queue.voiceBytes(alice))
        assertEquals("pending", queue.entries("bob").single().state); assertArrayEquals(voice().bytes, queue.voiceBytes(bob))
    }
    @Test fun unknownContractAndInvalidOptionDefinitionsCannotFallBackToLegacyAnswers() {
        val contract = JSONObject().put("version", 1).put("question", "Where?").put("allowText", true).put("allowVoice", true)
            .put("options", JSONArray().put(JSONObject().put("id", "custom").put("label", "Exit blocked").put("meaning", "help")))
        assertEquals("Exit blocked", MusterResponseConfig.from(contract).options.single().label)
        contract.put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { MusterResponseConfig.from(contract) }
        contract.put("version", 1).getJSONArray("options").getJSONObject(0).put("label", "")
        assertThrows(IllegalArgumentException::class.java) { MusterResponseConfig.from(contract) }
    }
}
