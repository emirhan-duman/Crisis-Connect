package com.auralis.crisisconnect.muster

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun acceptsMusterReceipt(status: Int, body: JSONObject?, event: MusterEvent): Boolean = status in 200..299 && body?.opt("ok") == true &&
    body.opt("id") == event.session && body.opt("eventId") == musterServerEventId(event.uid, event.operationId)

internal interface MusterPersistence { fun read(key: String): String?; fun write(key: String, value: String): Boolean }
internal data class MusterEvent(val uid: String, val panel: String, val session: String, val operationId: String, val answer: String,
    val note: String, val answeredAt: Long, val state: String = "pending", val attempts: Int = 0, val nextAt: Long = 0, val error: String = "",
    val schemaVersion: Int = 1, val optionId: String? = null, val voice: MusterVoiceRef? = null, val receiptEventId: String? = null) {
    fun json() = JSONObject().put("uid", uid).put("panelId", panel).put("sessionId", session).put("operationId", operationId)
        .put("answer", answer).put("note", note).put("answeredAtMillis", answeredAt).put("state", state).put("attempts", attempts).put("nextAt", nextAt).put("error", error)
        .put("schemaVersion", schemaVersion).apply { optionId?.let { put("optionId", it) }; voice?.let { put("voiceRef", it.json()) }; receiptEventId?.let { put("receiptEventId", it) } }
    fun request(voiceData: String? = null) = JSONObject().put("schemaVersion", schemaVersion).put("panelId", panel).put("sessionId", session).put("operationId", operationId)
        .put("answer", answer).put("note", note).put("answeredAtMillis", answeredAt).apply {
            optionId?.let { put("optionId", it) }
            voice?.let { ref -> require(!voiceData.isNullOrBlank()); put("voice", JSONObject().put("mimeType", "audio/mp4").put("durationMillis", ref.durationMillis).put("data", voiceData)) }
        }
    companion object { fun from(o: JSONObject) = MusterEvent(o.getString("uid"), o.getString("panelId"), o.getString("sessionId"), o.getString("operationId"),
        o.getString("answer"), o.optString("note"), o.getLong("answeredAtMillis"), o.getString("state"), o.optInt("attempts"), o.optLong("nextAt"), o.optString("error"),
        o.optInt("schemaVersion", 1), o.optString("optionId").takeUnless { it.isBlank() || it == "null" }, o.optJSONObject("voiceRef")?.let(MusterVoiceRef::from), o.optString("receiptEventId").takeUnless { it.isBlank() || it == "null" }) }
}

/** One durable, encrypted namespace per account. A switch never deletes another user's answers. */
internal class MusterQueue(private val storage: MusterPersistence, private val voices: MusterVoiceStorage? = null) {
    private fun key(uid: String) = "answers-" + musterHash(uid.toByteArray())
    @Synchronized fun entries(uid: String): List<MusterEvent> {
        val raw = storage.read(key(uid)) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { MusterEvent.from(array.getJSONObject(it)) }.also { rows ->
            require(rows.all { row -> row.uid == uid })
            rows.filter { it.state == "sent" && it.receiptEventId == musterServerEventId(it.uid, it.operationId) }.forEach { row ->
                row.voice?.let { ref -> runCatching { voices?.delete(uid, ref) } }
            }
        }
    }
    private fun save(uid: String, rows: List<MusterEvent>) { check(storage.write(key(uid), JSONArray().apply { rows.forEach { put(it.json()) } }.toString())) { "muster-persistence-failed" } }
    @Synchronized fun enqueue(uid: String, panel: String, session: String, answer: String, note: String, now: Long, operationId: String = UUID.randomUUID().toString()): MusterEvent {
        require(answer in listOf("safe", "help", "at_muster") && note.length <= 500)
        return append(uid, panel, session, MusterSubmission(answer, note), null, now, operationId)
    }
    @Synchronized fun enqueueFlexible(uid: String, panel: String, session: String, config: MusterResponseConfig, submission: MusterSubmission,
        now: Long, operationId: String = UUID.randomUUID().toString()): MusterEvent {
        require(submission.note.length <= 2000 && (config.allowText || submission.note.isEmpty()))
        require(config.allowVoice || submission.voice == null)
        if (submission.optionId != null) require(config.options.any { it.id == submission.optionId && it.meaning == submission.answer })
        else require(submission.answer == "message" && (submission.note.isNotBlank() || submission.voice != null))
        return append(uid, panel, session, submission, config, now, operationId)
    }
    private fun append(uid: String, panel: String, session: String, submission: MusterSubmission, config: MusterResponseConfig?, now: Long, operationId: String): MusterEvent {
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(panel) && Regex("^[A-Za-z0-9_-]{1,128}$").matches(session))
        require(operationId.length in 1..128)
        val rows = entries(uid)
        rows.find { it.operationId == operationId }?.let {
            require(it.panel == panel && it.session == session && it.answer == submission.answer && it.note == submission.note && it.optionId == submission.optionId &&
                it.voice?.sha256 == submission.voice?.sha256 && it.voice?.durationMillis == submission.voice?.durationMillis)
            return it
        }
        val retained = rows.filter { it.state != "sent" || now - it.answeredAt < 7 * 86400000L }
        check(retained.size < 256) { "muster-queue-full" }
        voices?.reconcile(uid, retained.mapNotNull { it.voice?.file }.toSet())
        // The encrypted audio must be fully durable before its tiny metadata enters the journal.
        val voice = submission.voice?.let { checkNotNull(voices) { "muster-voice-storage-unavailable" }.put(uid, operationId, it) }
        val event = MusterEvent(uid, panel, session, operationId, submission.answer, submission.note, now, schemaVersion = if (config == null) 1 else 2, optionId = submission.optionId, voice = voice)
        // A failed preference commit can still have changed its in-memory map. Keep the audio
        // until a parsed future journal proves it orphaned; never strand an uncertain commit.
        save(uid, retained + event)
        return event
    }
    @Synchronized fun claim(uid: String, now: Long): MusterEvent? {
        val rows = entries(uid); val row = rows.firstOrNull { it.state == "pending" && it.nextAt <= now } ?: return null
        val claimed = row.copy(attempts = row.attempts + 1, nextAt = now + 60000)
        save(uid, rows.map { if (it.operationId == row.operationId) claimed else it }); return claimed
    }
    @Synchronized fun finish(event: MusterEvent, state: String, now: Long, error: String = "", receiptEventId: String? = null) {
        if (state == "sent" && event.voice != null) require((receiptEventId ?: event.receiptEventId)?.matches(Regex("^[a-f0-9]{32}$")) == true)
        if (state == "sent") require(receiptEventId == musterServerEventId(event.uid, event.operationId)) { "muster-receipt-mismatch" }
        val next = event.copy(state = state, nextAt = now + minOf(900000L, 30000L * (1L shl minOf(event.attempts, 5))), error = error, receiptEventId = receiptEventId ?: event.receiptEventId)
        save(event.uid, entries(event.uid).map { if (it.operationId == event.operationId) next else it })
        // Called as sent only after strict server receipt; failed/pending audio remains recoverable.
        if (state == "sent") event.voice?.let { runCatching { voices?.delete(event.uid, it) } }
    }
    fun voiceBytes(event: MusterEvent): ByteArray? = event.voice?.let { checkNotNull(voices).read(event.uid, it) }
}
