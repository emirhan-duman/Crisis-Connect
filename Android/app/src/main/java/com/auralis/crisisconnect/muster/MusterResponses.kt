package com.auralis.crisisconnect.muster

import org.json.JSONObject
import java.security.MessageDigest

internal const val MUSTER_VOICE_MAX_BYTES = 128 * 1024
internal const val MUSTER_VOICE_MAX_MILLIS = 30_000L
internal fun musterHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
/** Match JS JSON.stringify([uid, operationId]) exactly; Android JSON may escape solidus. */
internal fun musterServerEventId(uid: String, operationId: String): String {
    fun quote(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    Character.isHighSurrogate(character) && index < value.length && Character.isLowSurrogate(value[index]) -> { append(character); append(value[index++]) }
                    character.code < 32 || Character.isSurrogate(character) -> { append("\\u"); append(character.code.toString(16).padStart(4, '0')) }
                    else -> append(character)
                }
            }
        }
        append('"')
    }
    return musterHash(("[" + quote(uid) + "," + quote(operationId) + "]").toByteArray(Charsets.UTF_8)).take(32)
}
internal val musterMeanings = setOf("safe", "help", "at_muster", "message")

internal data class MusterOption(val id: String, val label: String, val meaning: String)
internal data class MusterResponseConfig(val question: String, val options: List<MusterOption>, val allowText: Boolean, val allowVoice: Boolean) {
    companion object {
        /** An invalid new contract must never silently fall back to the three legacy safety buttons. */
        fun from(o: JSONObject): MusterResponseConfig {
            require(o.getInt("version") == 1)
            val array = o.getJSONArray("options")
            require(array.length() in 0..8)
            val options = (0 until array.length()).map { index -> array.getJSONObject(index).let {
                MusterOption(it.getString("id"), it.getString("label"), it.getString("meaning"))
            } }
            require(options.all { Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$").matches(it.id) && it.label.length <= 100 && it.meaning in musterMeanings })
            require(options.map { it.id }.distinct().size == options.size)
            require(options.all { it.label.isNotBlank() || it.id == it.meaning && it.meaning in setOf("safe", "help", "at_muster") })
            val question = o.getString("question"); require(question.length <= 500)
            val text = o.getBoolean("allowText"); val voice = o.getBoolean("allowVoice")
            require(options.isNotEmpty() || text || voice)
            return MusterResponseConfig(question, options, text, voice)
        }
    }
}

internal data class MusterVoiceDraft(val bytes: ByteArray, val durationMillis: Long) {
    init { require(bytes.size in 1..MUSTER_VOICE_MAX_BYTES && durationMillis in 1..MUSTER_VOICE_MAX_MILLIS) }
    val sha256 get() = musterHash(bytes)
}
internal data class MusterVoiceRef(val file: String, val durationMillis: Long, val bytes: Int, val sha256: String) {
    fun json() = JSONObject().put("file", file).put("durationMillis", durationMillis).put("bytes", bytes).put("sha256", sha256)
    companion object { fun from(o: JSONObject) = MusterVoiceRef(o.getString("file"), o.getLong("durationMillis"), o.getInt("bytes"), o.getString("sha256")).also {
        require(Regex("^[a-f0-9]{64}$").matches(it.file) && Regex("^[a-f0-9]{64}$").matches(it.sha256))
        require(it.bytes in 1..MUSTER_VOICE_MAX_BYTES && it.durationMillis in 1..MUSTER_VOICE_MAX_MILLIS)
    } }
}
internal data class MusterRemoteVoice(val eventId: String, val durationMillis: Long, val bytes: Int, val sha256: String) {
    companion object { fun from(o: JSONObject): MusterRemoteVoice {
        require(o.getString("mimeType") == "audio/mp4")
        return MusterRemoteVoice(o.getString("eventId"), o.getLong("durationMillis"), o.getInt("bytes"), o.getString("sha256")).also {
            require(Regex("^[a-f0-9]{32}$").matches(it.eventId) && Regex("^[a-f0-9]{64}$").matches(it.sha256))
            require(it.bytes in 1..MUSTER_VOICE_MAX_BYTES && it.durationMillis in 1..MUSTER_VOICE_MAX_MILLIS)
        }
    } }
}
internal data class MusterSubmission(val answer: String, val note: String, val optionId: String? = null, val voice: MusterVoiceDraft? = null)
internal interface MusterVoiceStorage {
    fun put(uid: String, operationId: String, draft: MusterVoiceDraft): MusterVoiceRef
    fun read(uid: String, ref: MusterVoiceRef): ByteArray
    fun delete(uid: String, ref: MusterVoiceRef)
    fun reconcile(uid: String, referencedFiles: Set<String>) {}
}
