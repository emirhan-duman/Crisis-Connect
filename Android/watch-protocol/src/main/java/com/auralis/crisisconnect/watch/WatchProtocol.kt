package com.auralis.crisisconnect.watch

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class WatchAction { status, startSOS, stopSOS, quickReply, contactReply }
enum class QuickReply(val english: String, val turkish: String) {
    safe("I am safe", "İyiyim"), injured("I am injured", "Yaralıyım"), needHelp("I need help", "Yardıma ihtiyacım var")
}

data class WatchCommand(
    val id: String = UUID.randomUUID().toString(),
    val action: WatchAction,
    val issuedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = issuedAt + 30_000,
    val emergencyID: String = "",
    val peerID: String = "",
    val reply: QuickReply? = null,
    val version: Int = 1
) {
    fun valid(now: Long): Boolean = version == 1 && runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false) &&
        issuedAt > 0 && issuedAt <= now + 5_000 && expiresAt > now && expiresAt > issuedAt && expiresAt - issuedAt <= 30_000 &&
        (action != WatchAction.stopSOS || emergencyID.isNotBlank()) &&
        (action !in listOf(WatchAction.quickReply, WatchAction.contactReply) || (reply != null && peerID.isNotBlank() && peerID.length <= 128))
    fun encode(): ByteArray = JSONObject().put("version", version).put("id", id).put("action", action.name)
        .put("issuedAt", issuedAt).put("expiresAt", expiresAt).put("emergencyID", emergencyID)
        .put("peerID", peerID).put("reply", reply?.name ?: "").toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(bytes: ByteArray): WatchCommand? = runCatching {
            require(bytes.size <= 4096)
            val j = JSONObject(bytes.toString(Charsets.UTF_8))
            WatchCommand(j.getString("id"), WatchAction.valueOf(j.getString("action")), j.getLong("issuedAt"),
                j.getLong("expiresAt"), j.optString("emergencyID"), j.optString("peerID"),
                j.optString("reply").takeIf { it.isNotBlank() }?.let(QuickReply::valueOf), j.getInt("version"))
        }.getOrNull()
    }
}

/** Display data only. Never sync identity secrets or authentication tokens to the watch. */
data class WatchSavedContact(val id: String, val name: String, val lastMessage: String = "", val canSend: Boolean = false, val internetCapable: Boolean = false, val keyChanged: Boolean = false, val lastReplyResult: String = "") {
    fun json() = JSONObject().put("id", id).put("name", name.take(60)).put("lastMessage", lastMessage.take(100))
        .put("canSend", canSend).put("internetCapable", internetCapable).put("keyChanged", keyChanged).put("lastReplyResult", lastReplyResult)
    companion object {
        fun decode(j: JSONObject) = WatchSavedContact(j.getString("id").take(128), j.optString("name").take(60),
            j.optString("lastMessage").take(100), j.optBoolean("canSend"), j.optBoolean("internetCapable"), j.optBoolean("keyChanged"), j.optString("lastReplyResult"))
    }
}

data class PhoneWatchState(
    val sosActive: Boolean = false, val bleBroadcasting: Boolean = false,
    val emergencyID: String = "", val peerID: String = "", val peerName: String = "", val lastMessage: String = "",
    val updatedAt: Long = 0,
    val recoveryRequired: Boolean = false,
    val lastReplyResult: String = "",
    val contacts: List<WatchSavedContact> = emptyList(),
    val contactCount: Int = 0
) {
    fun json(): JSONObject {
        val rows = JSONArray()
        val json = JSONObject().put("version", 1).put("sosActive", sosActive).put("bleBroadcasting", bleBroadcasting)
            .put("emergencyID", emergencyID).put("peerID", peerID).put("peerName", peerName.take(80)).put("lastMessage", lastMessage.take(180)).put("updatedAt", updatedAt).put("recoveryRequired", recoveryRequired).put("lastReplyResult", lastReplyResult)
            .put("contacts", rows).put("contactCount", contactCount)
        for (contact in contacts.take(20)) {
            rows.put(contact.json())
            if (json.toString().toByteArray(Charsets.UTF_8).size > 14_000) { rows.remove(rows.length()-1); break }
        }
        return json
    }
    companion object {
        fun decode(j: JSONObject): PhoneWatchState {
            require(j.getInt("version") == 1)
            return PhoneWatchState(j.getBoolean("sosActive"), j.getBoolean("bleBroadcasting"), j.optString("emergencyID"),
                j.optString("peerID"), j.optString("peerName").take(80), j.optString("lastMessage").take(180), j.getLong("updatedAt"), j.optBoolean("recoveryRequired", false), j.optString("lastReplyResult"),
                j.optJSONArray("contacts")?.let { rows -> List(minOf(rows.length(),20)) { WatchSavedContact.decode(rows.getJSONObject(it)) } } ?: emptyList(), j.optInt("contactCount",0))
        }
    }
}
object WatchPaths {
    const val COMMAND = "/crisis-connect/v1/command"
    const val RECEIPT = "/crisis-connect/v1/receipt"
    const val STATE = "/crisis-connect/v1/state"
    const val PHONE_CAPABILITY = "crisis_connect_phone_v1"
}
