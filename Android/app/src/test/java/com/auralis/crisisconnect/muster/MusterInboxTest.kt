package com.auralis.crisisconnect.muster

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusterInboxTest {
    private fun row(kind: String = "live", closed: Boolean = false, answer: String? = null, help: Boolean = false) = JSONObject()
        .put("panelId", "factory").put("receivedAtMillis", 100L)
        .put("session", JSONObject().put("id", "session").put("kind", kind).put("title", "Check in")
            .put("status", if (closed) "closed" else "active").put("deadlineAtMillis", 200L))
        .put("response", JSONObject().put("answer", answer ?: JSONObject.NULL).put("helpOpen", help))
    @Test fun realUnansweredCheckRemainsVisibleAfterDeadlineButExpiredDrillDoesNotPrompt() {
        assertTrue(musterCards(listOf(row()), emptyList(), "Muster").single().needsResponse(300))
        assertFalse(musterCards(listOf(row("drill")), emptyList(), "Muster").single().needsResponse(300))
        assertFalse(musterCards(listOf(row(closed = true)), emptyList(), "Muster").single().needsResponse(150))
        assertTrue(musterCards(listOf(row("future-kind")), emptyList(), "Muster").isEmpty())
    }
    @Test fun queuedAnswerIsNeverShownAsReceivedEvenWithOlderServerResponse() {
        val event = MusterEvent("alice", "factory", "session", "event", "help", "Offline note", 150L)
        val card = musterCards(listOf(row(answer = "safe")), listOf(event), "Muster").single()
        assertEquals("pending", card.replyState); assertEquals("help", card.answer)
        assertEquals("Offline note", card.note); assertFalse(card.needsResponse(300))
        assertEquals(card, MusterInboxState("alice", listOf(card), listOf(event)).priority(300))
    }
    @Test fun serverReceiptStopsPromptButOpenHelpRequestStaysVisible() {
        val card = musterCards(listOf(row(answer = "safe", help = true)), emptyList(), "Muster").single()
        assertEquals("sent", card.replyState); assertFalse(card.needsResponse(150))
        assertEquals(card, MusterInboxState("alice", listOf(card)).priority(300))
        assertNull(MusterInboxState("bob").priority(300))
    }
    @Test fun cacheEvictionDoesNotHidePendingOrFailedAnswers() {
        val pending = MusterEvent("alice", "factory", "missing", "event", "safe", "", 100L)
        val card = musterCards(emptyList(), listOf(pending), "Muster").single()
        assertEquals("pending", card.replyState); assertEquals("missing", card.session)
        assertEquals(card, MusterInboxState("alice", listOf(card), listOf(pending)).priority(300))
        assertTrue(musterCards(emptyList(), listOf(pending.copy(state = "sent")), "Muster").isEmpty())
    }
    @Test fun aNewerConfirmedServerAnswerWinsOverAnOlderLocalReceipt() {
        val record = row(answer = "safe")
        record.getJSONObject("response").put("answeredAtMillis", 300L)
        val old = MusterEvent("alice", "factory", "session", "event", "help", "Old note", 150L, state = "sent")
        val card = musterCards(listOf(record), listOf(old), "Muster").single()
        assertEquals("safe", card.answer); assertEquals("sent", card.replyState); assertEquals("", card.note)
    }
    @Test fun neutralMessagesKeepCustomChoiceAndHelpAudioSeparateFromSafety() {
        val record = row(answer = "message", help = true)
        record.getJSONObject("session").put("startedAtMillis", 100L).put("responseConfig", JSONObject().put("version", 1).put("question", "Your location?")
            .put("options", org.json.JSONArray().put(JSONObject().put("id", "blocked").put("label", "Exit blocked").put("meaning", "help"))).put("allowText", true).put("allowVoice", true))
        record.getJSONObject("response").put("note", "Near the exit").put("helpVoice", JSONObject().put("eventId", "a".repeat(32)).put("mimeType", "audio/mp4").put("durationMillis", 1000).put("bytes", 20).put("sha256", "b".repeat(64)))
        val card = musterCards(listOf(record), emptyList(), "Check-in").single()
        assertEquals("message", card.answer); assertEquals("Near the exit", card.note)
        assertEquals("Exit blocked", card.responseConfig!!.options.single().label)
        assertEquals("a".repeat(32), card.helpVoice!!.eventId); assertNull(card.remoteVoice)
        assertTrue(card.helpOpen); assertTrue(card.configurationAvailable)
    }
    private fun remoteVoice(eventId: String) = JSONObject().put("eventId", eventId).put("mimeType", "audio/mp4")
        .put("durationMillis", 1000).put("bytes", 20).put("sha256", "b".repeat(64))
    private fun oldResponseWithChoiceAndVoice() = row(answer = "safe", help = true).also { record ->
        record.getJSONObject("response").put("answeredAtMillis", 100L).put("optionId", "old_safe").put("note", "Old note")
            .put("voice", remoteVoice("a".repeat(32))).put("helpVoice", remoteVoice("d".repeat(32)))
    }
    @Test fun newerVoiceOnlyReplyClearsOldChoiceAndUsesOnlyItsOwnPendingOrReceivedAudio() {
        val voice = MusterVoiceRef("c".repeat(64), 2000, 40, "e".repeat(64))
        for (state in listOf("pending", "sent")) {
            val event = MusterEvent("alice", "factory", "session", "new-voice", "message", "", 150L,
                state = state, schemaVersion = 2, optionId = null, voice = voice, receiptEventId = "f".repeat(32))
            val card = musterCards(listOf(oldResponseWithChoiceAndVoice()), listOf(event), "Muster").single()
            assertEquals(state, card.replyState); assertEquals("message", card.answer); assertNull(card.optionId); assertEquals("", card.note)
            if (state == "pending") { assertEquals(voice, card.localVoice); assertNull(card.remoteVoice) }
            else { assertNull(card.localVoice); assertEquals("f".repeat(32), card.remoteVoice!!.eventId) }
            assertTrue(card.helpOpen); assertEquals("d".repeat(32), card.helpVoice!!.eventId)
        }
    }
    @Test fun newerCustomTextReplyClearsOldAudioForBothPendingAndReceivedStates() {
        for (state in listOf("pending", "sent")) {
            val event = MusterEvent("alice", "factory", "session", "new-text", "message", "On my way", 150L,
                state = state, schemaVersion = 2, optionId = "en_route")
            val card = musterCards(listOf(oldResponseWithChoiceAndVoice()), listOf(event), "Muster").single()
            assertEquals(state, card.replyState); assertEquals("message", card.answer); assertEquals("en_route", card.optionId)
            assertEquals("On my way", card.note); assertNull(card.localVoice); assertNull(card.remoteVoice)
            assertTrue(card.helpOpen); assertEquals("d".repeat(32), card.helpVoice!!.eventId)
        }
    }
    @Test fun newerConfirmedRemoteReplyOverridesTheCompleteOlderLocalReplySnapshot() {
        val record = oldResponseWithChoiceAndVoice()
        record.getJSONObject("response").put("answeredAtMillis", 300L).put("optionId", "ready").put("note", "Latest server note")
            .put("voice", remoteVoice("9".repeat(32)))
        val old = MusterEvent("alice", "factory", "session", "older-local", "message", "Old local note", 150L,
            state = "sent", schemaVersion = 2, optionId = null,
            voice = MusterVoiceRef("c".repeat(64), 2000, 40, "e".repeat(64)), receiptEventId = "f".repeat(32))
        val card = musterCards(listOf(record), listOf(old), "Muster").single()
        assertEquals("sent", card.replyState); assertEquals("safe", card.answer); assertEquals("ready", card.optionId)
        assertEquals("Latest server note", card.note); assertNull(card.localVoice); assertEquals("9".repeat(32), card.remoteVoice!!.eventId)
        assertTrue(card.helpOpen); assertEquals("d".repeat(32), card.helpVoice!!.eventId)
    }
    @Test fun unknownConfigurationCannotFallBackToAnEditableLegacyForm() {
        val record = row(); record.getJSONObject("session").put("startedAtMillis", 100L).put("responseConfig", JSONObject().put("version", 99))
        val card = musterCards(listOf(record), emptyList(), "Check-in").single()
        assertFalse(card.configurationAvailable); assertNull(card.responseConfig)
    }

}
