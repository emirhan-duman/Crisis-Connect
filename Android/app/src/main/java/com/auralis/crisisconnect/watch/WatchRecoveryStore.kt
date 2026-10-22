package com.auralis.crisisconnect.watch

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local history only: loading this file must never start a service or send a message. */
class WatchRecoveryStore(private val file: AtomicFile) {
    data class Reply(val id: String, val peer: String, val text: String, val result: String, val createdAt: Long = 0, val kind: String = "")
    data class Snapshot(val startedAt: Long? = null, val replies: List<Reply> = emptyList(), val unavailable: Boolean = false)
    private val current = MutableStateFlow(runCatching { read() }.getOrElse { Snapshot(unavailable = true) })
    val state = current.asStateFlow()

    private fun read(): Snapshot {
        // Missing is normal; corrupt/unreadable storage is an error, never permission to start SOS.
        val text = try { file.openRead().bufferedReader().use { it.readText() } }
            catch (missing: java.io.FileNotFoundException) {
                if (file.baseFile.exists()) throw missing
                return Snapshot()
            }
        val j = JSONObject(text)
        require(j.getInt("version") == 1)
        val replies = j.getJSONArray("replies")
        return Snapshot(j.getLong("startedAt").takeIf { it > 0 }, List(replies.length()) { index ->
            val r = replies.getJSONObject(index)
            Reply(r.getString("id"), r.getString("peer"), r.getString("text"), r.getString("result"), r.optLong("createdAt", 0), r.optString("kind", ""))
        })
    }

    @Synchronized fun begin(now: Long): Long {
        check(!current.value.unavailable) { "SOS history unavailable" }
        check(current.value.startedAt == null) { "Existing SOS requires explicit resume" }
        require(now > 0)
        write(current.value.copy(startedAt = now))
        return now
    }
    @Synchronized fun resume(expectedId: String): Long {
        check(!current.value.unavailable) { "SOS history unavailable" }
        val saved = checkNotNull(current.value.startedAt)
        check(saved.toString() == expectedId) { "SOS session changed" }
        return saved
    }
    @Synchronized fun removeRepliesForPeer(peer: String) { write(current.value.copy(replies = current.value.replies.filterNot { it.peer == peer })) }
    @Synchronized fun finish() { write(current.value.copy(startedAt = null)) }
    @Synchronized fun reserveReply(id: String, peer: String, text: String, kind: String = ""): Reply {
        current.value.replies.firstOrNull { it.id == id }?.let { return it }
        val reply = Reply(id, peer, text, "uncertain", System.currentTimeMillis(), kind)
        write(current.value.copy(replies = (current.value.replies + reply).takeLast(500)))
        return reply
    }
    @Synchronized fun completeReply(id: String, result: String) {
        write(current.value.copy(replies = current.value.replies.map { if (it.id == id) it.copy(result = result) else it }))
    }
    private fun write(next: Snapshot) {
        check(!current.value.unavailable) { "SOS history unavailable" }
        val json = JSONObject().put("version", 1).put("startedAt", next.startedAt ?: 0)
            .put("replies", JSONArray(next.replies.map {
                JSONObject().put("id", it.id).put("peer", it.peer).put("text", it.text).put("result", it.result).put("createdAt", it.createdAt).put("kind", it.kind)
            }))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
        current.value = next
    }

    companion object {
        @Volatile private var instance: WatchRecoveryStore? = null
        @Synchronized fun get(context: Context): WatchRecoveryStore = instance ?: WatchRecoveryStore(
            AtomicFile(File(context.applicationContext.noBackupFilesDir, "watch-recovery-v1.json"))
        ).also { instance = it }
    }
}
