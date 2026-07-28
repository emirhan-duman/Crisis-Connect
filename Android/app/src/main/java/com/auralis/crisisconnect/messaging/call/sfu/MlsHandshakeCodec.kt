package com.auralis.crisisconnect.messaging.call.sfu

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire codec for MLS handshake messages relayed over Firestore (`sfuRooms/{id}/mlsMessages`), **byte-exact
 * with the web** (`e2ee-mls.ts` replacer/reviver + the `MessageFromWorker` union from Cloudflare Orange).
 * The SFU + Firestore never see keys/plaintext — these are opaque, already-serialized MLS blobs; this
 * class only frames them so they survive a JSON round-trip and both platforms parse each other's.
 *
 * Byte fields are encoded like Orange: a `Uint8Array` becomes `{ FLAG_TYPED_ARRAY: true, data: [ints] }`
 * and an `ArrayBuffer` becomes `{ FLAG_ARRAY_BUFFER: true, data: [ints] }`. The message types mirror
 * `MessageFromWorker`: shareKeyPackage / sendMlsMessage / sendMlsWelcome (newSafetyNumber is local-only,
 * never broadcast). This is the one Faz C piece with NO native dependency — the native worker (Faz C
 * Rust→JNI) produces/consumes the raw bytes; this frames them for the wire.
 */
object MlsHandshakeCodec {
    private const val FLAG_TYPED_ARRAY = "FLAG_TYPED_ARRAY"
    private const val FLAG_ARRAY_BUFFER = "FLAG_ARRAY_BUFFER"

    /** One MLS message the worker wants broadcast to the room. */
    sealed interface Outgoing {
        data class ShareKeyPackage(val keyPkg: ByteArray) : Outgoing
        data class SendMlsMessage(val msg: ByteArray, val senderId: String) : Outgoing
        data class SendMlsWelcome(val senderId: String, val welcome: ByteArray, val rtree: ByteArray) : Outgoing
    }

    /** A peer's MLS message to feed back into our worker (mirror of [Outgoing]). */
    sealed interface Incoming {
        data class ShareKeyPackage(val keyPkg: ByteArray) : Incoming
        data class SendMlsMessage(val msg: ByteArray, val senderId: String) : Incoming
        data class SendMlsWelcome(val senderId: String, val welcome: ByteArray, val rtree: ByteArray) : Incoming
    }

    /** Serialize an outgoing MLS message to the exact JSON string the web expects on the wire. */
    fun encode(message: Outgoing): String = when (message) {
        is Outgoing.ShareKeyPackage -> JSONObject()
            .put("type", "shareKeyPackage")
            .put("keyPkg", typedArray(message.keyPkg))
        is Outgoing.SendMlsMessage -> JSONObject()
            .put("type", "sendMlsMessage")
            .put("msg", typedArray(message.msg))
            .put("senderId", message.senderId)
        is Outgoing.SendMlsWelcome -> JSONObject()
            .put("type", "sendMlsWelcome")
            .put("senderId", message.senderId)
            .put("welcome", typedArray(message.welcome))
            .put("rtree", typedArray(message.rtree))
    }.toString()

    /** Parse a peer's (web or Android) MLS message. Returns null for unknown/local-only types. */
    fun decode(json: String): Incoming? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return when (obj.optString("type")) {
            "shareKeyPackage" -> bytes(obj, "keyPkg")?.let { Incoming.ShareKeyPackage(it) }
            "sendMlsMessage" -> bytes(obj, "msg")?.let { Incoming.SendMlsMessage(it, obj.optString("senderId")) }
            "sendMlsWelcome" -> {
                val welcome = bytes(obj, "welcome") ?: return null
                val rtree = bytes(obj, "rtree") ?: return null
                Incoming.SendMlsWelcome(obj.optString("senderId"), welcome, rtree)
            }
            else -> null // newSafetyNumber / workerReady are never broadcast
        }
    }

    // ---- Orange typed-array framing ----

    private fun typedArray(bytes: ByteArray): JSONObject {
        val data = JSONArray()
        for (b in bytes) data.put(b.toInt() and 0xFF)
        return JSONObject().put(FLAG_TYPED_ARRAY, true).put("data", data)
    }

    /** Reads a Uint8Array/ArrayBuffer-flagged field back into raw bytes. */
    private fun bytes(parent: JSONObject, key: String): ByteArray? {
        val field = parent.optJSONObject(key) ?: return null
        if (!field.optBoolean(FLAG_TYPED_ARRAY) && !field.optBoolean(FLAG_ARRAY_BUFFER)) return null
        val data = field.optJSONArray("data") ?: return null
        return ByteArray(data.length()) { i -> (data.optInt(i) and 0xFF).toByte() }
    }
}
