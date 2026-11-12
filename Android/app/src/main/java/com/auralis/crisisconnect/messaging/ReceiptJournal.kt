package com.auralis.crisisconnect.messaging

import android.content.Context
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Account-isolated, synchronously committed encrypted pending receipts. No ciphertext/keys/locations. */
class ReceiptJournal(context: Context) {
    private val store = KeystoreBackedPreferences(context, "messaging_receipts", "messaging_receipts_key")
    private fun read(uid: String): List<ReceiptBatch> {
        val json = JSONArray(store.getStringStrict(uid, "[]"))
        return (0 until json.length()).map { i ->
            val item = json.getJSONObject(i); val ids = item.getJSONArray("ids")
            ReceiptBatch(item.getString("peer"), item.getInt("template"), (0 until ids.length()).map { ids.getString(it) })
        }
    }
    private fun write(uid: String, batches: List<ReceiptBatch>) {
        val json = JSONArray()
        batches.forEach { json.put(JSONObject().put("peer",it.peer).put("template",it.template).put("ids",JSONArray(it.ids))) }
        check(store.putStringCommitted(uid,json.toString())) { "Receipt journal commit unavailable" }
    }
    fun enqueue(uid: String, batch: ReceiptBatch) = synchronized(lock) { write(uid,ReceiptBatches.enqueue(read(uid),batch)) }
    fun next(uid: String): ReceiptBatch? = synchronized(lock) { ReceiptBatches.next(read(uid)) }
    fun confirm(uid: String, batch: ReceiptBatch) = synchronized(lock) { write(uid,ReceiptBatches.confirm(read(uid),batch)) }
    companion object { private val lock = Any() }
}
