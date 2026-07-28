package com.auralis.crisisconnect.ai

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Helpers for the "cloud:{firestoreDocId}" conversation-id scheme. */
object CrisisSentinelConversationIds {
    const val CLOUD_PREFIX = "cloud:"
    fun isCloud(id: String): Boolean = id.startsWith(CLOUD_PREFIX)
    fun cloudDocId(id: String): String = id.removePrefix(CLOUD_PREFIX)
    fun fromCloudDocId(docId: String): String = CLOUD_PREFIX + docId
}

data class CrisisSentinelTurnSeqs(val userSeq: Long, val assistantSeq: Long)

data class CrisisSentinelCloudChatList(
    val summaries: List<CrisisSentinelConversationSummary> = emptyList(),
    val permissionDenied: Boolean = false
)

data class CrisisSentinelCloudMessages(
    val messages: List<CrisisSentinelChatMessage> = emptyList(),
    /** False once the chat doc is confirmed gone (e.g. deleted from the web dashboard). */
    val chatExists: Boolean = true
)

/**
 * Firestore-backed conversation store shared with the web dashboard's Crisis Sentinel chat.
 * Reads/writes the exact collections and field shapes the web client uses
 * (`agencyPanels/{panelKey}/chats/{chatId}` + `messages` subcollection) so online chats appear
 * on both platforms.
 *
 * App-singleton on purpose: several CrisisSentinelViewModel instances coexist (one per
 * NavBackStackEntry), and all Firestore listening must funnel through the shared
 * [chatSummaries]/[messages] flows so each query has at most one active snapshot listener.
 */
class CrisisSentinelCloudChatStore private constructor(context: Context) {

    private val firestore = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSeq = AtomicLong(0)

    @Volatile
    private var cachedPanelKey: Pair<String, String?>? = null // uid → derived key (null = none)

    private fun currentUid(): String? =
        FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }?.uid

    // ------------------------------------------------------------------ panel key

    /**
     * The chat collection's panel segment — must be byte-identical to the web dashboard's
     * `activeAgencyKey` or chats won't line up. Derivation mirrors dashboard/page.tsx:
     * defaultDashboardPanelId → defaultPanelId → agencySlug → agencyKey (all via
     * [normalizeAgencyDocumentId]: trim + "/"→"-" ONLY) → slugified agency name. Null disables
     * sync (never fall back to a guessed panel).
     */
    suspend fun resolvePanelKey(forceRefresh: Boolean = false): String? {
        val uid = currentUid() ?: return null
        if (!forceRefresh) {
            cachedPanelKey?.let { (cachedUid, key) -> if (cachedUid == uid) return key }
            prefs.getString(panelKeyPrefsKey(uid), null)?.takeIf { it.isNotEmpty() }?.let { key ->
                cachedPanelKey = uid to key
                return key
            }
        }
        val snapshot = runCatching {
            firestore.collection("users").document(uid).get().await()
        }.onFailure { Log.w(TAG, "Could not load users/$uid for panel key", it) }.getOrNull()
            ?: return cachedPanelKey?.takeIf { it.first == uid }?.second
        val key = derivePanelKey(snapshot)
        cachedPanelKey = uid to key
        prefs.edit().putString(panelKeyPrefsKey(uid), key.orEmpty()).apply()
        return key
    }

    fun invalidatePanelKey() {
        val uid = cachedPanelKey?.first
        cachedPanelKey = null
        uid?.let { prefs.edit().remove(panelKeyPrefsKey(it)).apply() }
    }

    private fun panelKeyPrefsKey(uid: String) = "panel_key:$uid"

    private fun derivePanelKey(snapshot: DocumentSnapshot): String? {
        normalizeAgencyDocumentId(snapshot.getString("defaultDashboardPanelId"))?.let { return it }
        normalizeAgencyDocumentId(snapshot.getString("defaultPanelId"))?.let { return it }
        normalizeAgencyDocumentId(snapshot.getString("agencySlug"))?.let { return it }
        normalizeAgencyDocumentId(snapshot.getString("agencyKey"))?.let { return it }
        val agencyName = AGENCY_NAME_FIELDS.firstNotNullOfOrNull { field ->
            snapshot.getString(field)?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        return toAgencyKey(agencyName)
    }

    // ------------------------------------------------------------------ seq

    /**
     * Reserves the user+assistant sequence numbers for a turn up front (web parity:
     * microsecond-scaled epoch, user strictly before assistant even when the reply lands
     * seconds later).
     */
    fun reserveTurnSeqs(): CrisisSentinelTurnSeqs {
        return CrisisSentinelTurnSeqs(userSeq = nextSeq(), assistantSeq = nextSeq())
    }

    private fun nextSeq(): Long {
        while (true) {
            val candidate = System.currentTimeMillis() * 1000
            val previous = lastSeq.get()
            val next = if (candidate > previous) candidate else previous + 1
            if (lastSeq.compareAndSet(previous, next)) return next
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Awaits a Firestore write with a cap. Write tasks resolve only on SERVER ack, so an
     * unbounded await would hang forever offline even though the write already landed in the
     * local cache and will sync later (this is what makes Edge replies inside synced chats work
     * in airplane mode). Fast failures (permission-denied while online) still propagate; late
     * failures of a queued write only get logged.
     */
    private suspend fun com.google.android.gms.tasks.Task<*>.awaitAckOrQueue() {
        addOnFailureListener { error ->
            Log.w(TAG, "Deferred cloud chat write failed", error)
        }
        withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { await() }
    }

    private suspend fun chatsCollection(): CollectionReference {
        val panelKey = resolvePanelKey() ?: throw FirebaseFirestoreException(
            "No panel key",
            FirebaseFirestoreException.Code.FAILED_PRECONDITION
        )
        return firestore.collection("agencyPanels").document(panelKey).collection("chats")
    }

    /**
     * Creates the chat doc and AWAITS it — the messages subcollection rules `get()` the parent
     * chat's `userId`, so the parent must exist before any message write. Returns the doc id.
     */
    suspend fun createChat(firstUserText: String): String {
        val uid = currentUid() ?: throw FirebaseFirestoreException(
            "Not signed in",
            FirebaseFirestoreException.Code.UNAUTHENTICATED
        )
        val chatRef = chatsCollection().document()
        // Web parity: first 30 chars of the first message, trimmed; literal fallback (data,
        // not UI — rendered on web too, so not localized).
        val title = firstUserText.take(30).trim().ifBlank { "Yeni Sohbet" }
        chatRef.set(
            mapOf(
                "userId" to uid,
                "title" to title,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "isTemporary" to false
            )
        ).awaitAckOrQueue()
        return chatRef.id
    }

    suspend fun renameChat(chatDocId: String, title: String) {
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return
        chatsCollection().document(chatDocId)
            .update(mapOf("title" to clean, "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }

    /** Deletes all message docs in pages (Firestore has no recursive delete), then the chat. */
    suspend fun deleteChat(chatDocId: String) {
        val chatRef = chatsCollection().document(chatDocId)
        while (true) {
            val page = chatRef.collection("messages").limit(100).get().await()
            if (page.isEmpty) break
            val batch = firestore.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            if (page.size() < 100) break
        }
        chatRef.delete().await()
    }

    suspend fun deleteMessages(chatDocId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val chatRef = chatsCollection().document(chatDocId)
        val batch = firestore.batch()
        messageIds.forEach { batch.delete(chatRef.collection("messages").document(it)) }
        batch.commit().awaitAckOrQueue()
    }

    suspend fun appendUserMessage(chatDocId: String, text: String, seq: Long) {
        val chatRef = chatsCollection().document(chatDocId)
        val batch = firestore.batch()
        batch.set(
            chatRef.collection("messages").document(UUID.randomUUID().toString()),
            mapOf(
                "sender" to "user",
                "text" to text,
                "timestamp" to FieldValue.serverTimestamp(),
                "seq" to seq,
                // Web parity: always present (some web render paths iterate it unguarded).
                "attachments" to emptyList<Any>()
            )
        )
        batch.update(chatRef, mapOf("updatedAt" to FieldValue.serverTimestamp()))
        batch.commit().awaitAckOrQueue()
    }

    suspend fun appendBotMessage(
        chatDocId: String,
        message: CrisisSentinelChatMessage,
        seq: Long,
        groundingMetadataJson: String? = null
    ) {
        val chatRef = chatsCollection().document(chatDocId)
        // Build the field map imperatively and never put nulls — web docs have ABSENT fields,
        // not null fields (their stripUndefined), and web read paths rely on that.
        val fields = mutableMapOf<String, Any>(
            "sender" to "bot",
            "text" to message.text,
            "timestamp" to FieldValue.serverTimestamp(),
            "seq" to seq,
            "isMarkdown" to true
        )
        message.modelName?.let { fields["modelName"] = it }
        message.cardJson?.let { cardJson ->
            runCatching { jsonObjectToMap(JSONObject(cardJson)) }
                .getOrNull()
                ?.let { fields["card"] = it }
        }
        if (message.mapPoints.isNotEmpty()) {
            fields["mapInfo"] = message.mapPoints.map { point ->
                buildMap<String, Any> {
                    put("lat", point.lat)
                    put("lng", point.lng)
                    put("label", point.label)
                    point.details?.let { put("details", it) }
                    point.type?.let { put("type", it) }
                }
            }
        }
        groundingMetadataJson?.let { json ->
            runCatching { jsonObjectToMap(JSONObject(json)) }
                .getOrNull()
                ?.let { fields["groundingMetadata"] = it }
        }
        val batch = firestore.batch()
        batch.set(chatRef.collection("messages").document(message.id), fields)
        batch.update(chatRef, mapOf("updatedAt" to FieldValue.serverTimestamp()))
        batch.commit().awaitAckOrQueue()
    }

    // ------------------------------------------------------------------ listeners

    /**
     * The signed-in user's cloud chats, shared across all collectors with a single Firestore
     * listener. No orderBy in the query (web parity — avoids a composite index); sorted here.
     */
    val chatSummaries: SharedFlow<CrisisSentinelCloudChatList> = callbackFlow {
        val uid = currentUid()
        val panelKey = resolvePanelKey()
        if (uid == null || panelKey == null) {
            trySend(CrisisSentinelCloudChatList())
            awaitClose { }
            return@callbackFlow
        }
        val registration = firestore.collection("agencyPanels").document(panelKey)
            .collection("chats")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Cloud chat list listener failed", error)
                    trySend(
                        CrisisSentinelCloudChatList(
                            permissionDenied =
                                error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        )
                    )
                    return@addSnapshotListener
                }
                val summaries = snapshot?.documents.orEmpty()
                    .map(::summaryFromChatDoc)
                    .sortedByDescending { it.updatedAtMillis }
                trySend(CrisisSentinelCloudChatList(summaries = summaries))
            }
        awaitClose { registration.remove() }
    }.shareIn(storeScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val messageFlows = object : LinkedHashMap<String, Flow<CrisisSentinelCloudMessages>>(
        4, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Flow<CrisisSentinelCloudMessages>>
        ): Boolean = size > 4
    }

    /** Live messages of one cloud chat (+ a chat-doc watch so web-side deletion is detected). */
    fun messages(chatDocId: String): Flow<CrisisSentinelCloudMessages> = synchronized(messageFlows) {
        messageFlows.getOrPut(chatDocId) { createMessagesFlow(chatDocId) }
    }

    private fun createMessagesFlow(chatDocId: String): Flow<CrisisSentinelCloudMessages> =
        callbackFlow {
            val panelKey = resolvePanelKey()
            if (panelKey == null) {
                trySend(CrisisSentinelCloudMessages(chatExists = false))
                awaitClose { }
                return@callbackFlow
            }
            val chatRef = firestore.collection("agencyPanels").document(panelKey)
                .collection("chats").document(chatDocId)
            var latestMessages: List<CrisisSentinelChatMessage> = emptyList()
            var chatExists = true
            val chatRegistration = chatRef.addSnapshotListener { snapshot, _ ->
                // Only trust server-confirmed absence (cache misses report !exists too).
                if (snapshot != null && !snapshot.exists() && !snapshot.metadata.isFromCache) {
                    chatExists = false
                    trySend(CrisisSentinelCloudMessages(latestMessages, chatExists = false))
                }
            }
            val messagesRegistration = chatRef.collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Cloud messages listener failed", error)
                        return@addSnapshotListener
                    }
                    latestMessages = snapshot?.documents.orEmpty()
                        .mapNotNull(::messageFromDoc)
                        // seq is authoritative: serverTimestamp is null in cache until commit.
                        .sortedBy { it.first }
                        .map { it.second }
                    trySend(CrisisSentinelCloudMessages(latestMessages, chatExists))
                }
            awaitClose {
                chatRegistration.remove()
                messagesRegistration.remove()
            }
        }.shareIn(storeScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // ------------------------------------------------------------------ mapping

    private fun summaryFromChatDoc(doc: DocumentSnapshot): CrisisSentinelConversationSummary {
        return CrisisSentinelConversationSummary(
            id = CrisisSentinelConversationIds.fromCloudDocId(doc.id),
            title = doc.getString("title").orEmpty().ifBlank { "Yeni Sohbet" },
            mode = CrisisSentinelUserMode.FieldTeam,
            // Pending serverTimestamp → "now": the write just happened on this device.
            updatedAtMillis = doc.getTimestamp("updatedAt")?.toDate()?.time
                ?: System.currentTimeMillis(),
            lastMessagePreview = null,
            isCloud = true
        )
    }

    /** Returns orderKey→message; null for malformed docs. */
    private fun messageFromDoc(doc: DocumentSnapshot): Pair<Long, CrisisSentinelChatMessage>? {
        val sender = doc.getString("sender") ?: return null
        val isUser = sender == "user"
        val seq = doc.getLong("seq")
        val timestampMillis = doc.getTimestamp("timestamp")?.toDate()?.time
            ?: seq?.let { it / 1000 }
            ?: System.currentTimeMillis()
        val orderKey = seq ?: (timestampMillis * 1000)
        val modelName = doc.getString("modelName")
        val cardJson = (doc.get("card") as? Map<*, *>)?.let { mapToJsonObject(it).toString() }
        val mapPoints = (doc.get("mapInfo") as? List<*>).orEmpty().mapNotNull { entry ->
            val point = entry as? Map<*, *> ?: return@mapNotNull null
            val lat = (point["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lng = (point["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
            CrisisSentinelMapPoint(
                lat = lat,
                lng = lng,
                label = point["label"] as? String ?: "",
                details = point["details"] as? String,
                type = point["type"] as? String
            )
        }
        return orderKey to CrisisSentinelChatMessage(
            id = doc.id,
            role = if (isUser) CrisisSentinelChatRole.User else CrisisSentinelChatRole.Assistant,
            text = doc.getString("text").orEmpty(),
            timestampMillis = timestampMillis,
            source = when {
                isUser -> null
                modelName == EDGE_MODEL_NAME -> CrisisSentinelResponseSource.LocalModel
                else -> CrisisSentinelResponseSource.OnlineModel
            },
            modelName = modelName,
            cardJson = cardJson,
            mapPoints = mapPoints
        )
    }

    companion object {
        private const val TAG = "CrisisSentinelCloud"
        private const val PREFS_NAME = "crisis_sentinel_cloud"
        private const val WRITE_ACK_TIMEOUT_MS = 4_000L

        /** modelName web shows for on-device replies written into cloud chats (data, not UI). */
        const val EDGE_MODEL_NAME = "Crisis Sentinel Edge"

        private val AGENCY_NAME_FIELDS =
            listOf("agencyName", "agency", "authority", "institution", "organization")

        @Volatile
        private var instance: CrisisSentinelCloudChatStore? = null

        fun getInstance(context: Context): CrisisSentinelCloudChatStore =
            instance ?: synchronized(this) {
                instance ?: CrisisSentinelCloudChatStore(context.applicationContext)
                    .also { instance = it }
            }

        /** Web `normalizeAgencyDocumentId`: trim + "/"→"-" only — NO lowercasing/slugify. */
        internal fun normalizeAgencyDocumentId(raw: String?): String? =
            raw?.trim()?.replace("/", "-")?.takeIf { it.isNotEmpty() }

        /** Web `toAgencyKey`: NFKD → strip combining marks U+0300..U+036F → lowercase → slug. */
        internal fun toAgencyKey(name: String): String? =
            Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replace(Regex("[\\u0300-\\u036F]"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .takeIf { it.isNotEmpty() }

        internal fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            json.keys().forEach { key ->
                convertJsonValue(json.get(key))?.let { result[key] = it }
            }
            return result
        }

        private fun jsonArrayToList(array: JSONArray): List<Any> = buildList {
            for (index in 0 until array.length()) {
                convertJsonValue(array.get(index))?.let(::add)
            }
        }

        private fun convertJsonValue(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            else -> value
        }

        internal fun mapToJsonObject(map: Map<*, *>): JSONObject {
            val json = JSONObject()
            map.forEach { (key, value) ->
                if (key is String) json.put(key, convertMapValue(value))
            }
            return json
        }

        private fun convertMapValue(value: Any?): Any? = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> mapToJsonObject(value)
            is List<*> -> JSONArray().also { array ->
                value.forEach { array.put(convertMapValue(it)) }
            }
            else -> value
        }
    }
}
