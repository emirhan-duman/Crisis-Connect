package com.auralis.crisisconnect.messaging

import android.util.Base64
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.network.PinnedOkHttpClient
import com.auralis.crisisconnect.security.Crypto
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A manager on the far side of a cross-panel channel (a candidate recipient). */
data class HierarchyPeer(
    val uid: String,
    val name: String,
    val role: String? = null,
    val photoUrl: String? = null,
    val agency: String? = null,
    /** E.164 phone (entitled managers only) — the SPAKE2 password for an offline number link later. */
    val phone: String? = null
)

/** A cross-panel channel: my panel ↔ one related panel (parent/child/sibling), with its roster. */
data class HierarchyChannel(
    val channelId: String,
    val peerPanelId: String,
    val peerPanelName: String,
    val group: String,
    val peers: List<HierarchyPeer>
)

/** The shared per-channel AES key, issued by /api/messaging/hierarchy after re-verifying entitlement. */
data class HierarchyChannelKey(
    val keyId: String,
    val key: ByteArray,
    val channelId: String
)

/** Last-message preview for a cross-panel conversation row (decrypted text + timestamp). */
data class HierarchyPreview(
    val text: String,
    val atMillis: Long,
    /** Sender of the last message — lets the home list tell an incoming message from your own. */
    val senderUid: String = "",
    /** "image" / "audio" / "file" when the last message is an attachment (blank text), else "". */
    val attachmentKind: String = "",
    /** The peer's own display name, stamped as `senderName` on their messages. Used to show a real
     *  name when the roster only gave us their login email (the backend's last-resort fallback). */
    val peerName: String = "",
)

/** A decrypted cross-panel message. */
data class HierarchyMessage(
    val id: String,
    val senderUid: String,
    val senderName: String,
    val recipientUid: String,
    val recipientName: String,
    val text: String,
    val atMillis: Long,
    val attachments: List<ChannelAttachment> = emptyList(),
    // Sender-chosen uuid carried on backfilled docs: a message first delivered over the offline
    // Bluetooth bridge and posted to the channel later keeps its original uuid here, so clients
    // that already rendered the Bluetooth copy can dedupe instead of showing it twice.
    val clientUuid: String = "",
)

/**
 * Resolves the display name actually shown for a cross-panel (kurum) peer — the iOS parity for
 * showing a real name instead of a raw login email. The backend's roster falls back to the peer's
 * email when they have no `username` in Firestore (see /api/messaging/hierarchy: `username ?? email
 * ?? uid`), so a real name beats an email; if the roster name IS an email, the name the peer's own
 * app stamped on their messages wins; else the email's local part ("demo@x.net" → "Demo").
 */
object AuthorityNameResolver {
    fun resolve(rosterName: String, messageSenderName: String? = null): String {
        val roster = rosterName.trim()
        if (roster.isNotEmpty() && !looksLikeEmail(roster)) return roster
        val sender = messageSenderName?.trim().orEmpty()
        if (sender.isNotEmpty() && !looksLikeEmail(sender)) return sender
        if (roster.isNotEmpty()) return prettifyEmail(roster)
        return roster
    }

    fun looksLikeEmail(value: String): Boolean {
        val v = value.trim()
        val at = v.indexOf('@')
        if (at <= 0) return false
        return v.substring(at + 1).contains('.') && !v.contains(' ')
    }

    /** "demo@crisisconnect.network" → "Demo": local part, separators to spaces, each word capitalized. */
    fun prettifyEmail(email: String): String {
        val local = email.substringBefore('@')
        val words = local
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
        return if (words.isEmpty()) local else words.joinToString(" ")
    }
}

/**
 * Android client for the dashboard's cross-panel (hierarchy) messaging, mirroring the web
 * `lib/messaging/hierarchy.ts`. Entitlement + the shared per-channel key are decided server-side by
 * the Next.js route `/api/messaging/hierarchy` (called over HTTPS with the Firebase ID token, same
 * host + auth pattern as [com.auralis.crisisconnect.sync.MobileSyncClient]); messages themselves are
 * AES-256-GCM under that key with AAD = channelId, stored as ciphertext under
 * `hierarchyChannels/{channelId}/secureMessages` — byte-compatible with the web secure-channel.
 */
class HierarchyMessagingClient(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Lists the cross-panel channels this manager may use, each with the peer panel's roster. */
    suspend fun fetchChannels(): List<HierarchyChannel> = withContext(Dispatchers.IO) {
        val base = requireBaseUrl()
        val token = idToken()
        val url = base.newBuilder().addPathSegments(API_PATH).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val bodyText = execute(request) ?: return@withContext emptyList()
        val channels = JSONObject(bodyText).optJSONArray("channels") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until channels.length()) {
                val obj = channels.optJSONObject(i) ?: continue
                add(
                    HierarchyChannel(
                        channelId = obj.optString("channelId"),
                        peerPanelId = obj.optString("peerPanelId"),
                        peerPanelName = obj.optString("peerPanelName"),
                        group = obj.optString("group"),
                        peers = parsePeers(obj.optJSONArray("peers"))
                    )
                )
            }
        }.filter { it.channelId.isNotBlank() }
    }

    /** Fetches (create-on-first-use) the shared key for [channelId]. Throws if not entitled. */
    suspend fun fetchChannelKey(channelId: String): HierarchyChannelKey = withContext(Dispatchers.IO) {
        val base = requireBaseUrl()
        val token = idToken()
        val url = base.newBuilder().addPathSegments(API_PATH).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(JSONObject().put("channelId", channelId).toString().toRequestBody(jsonMediaType))
            .build()
        val bodyText = execute(request) ?: throw IOException("No hierarchy channel key returned.")
        val obj = JSONObject(bodyText)
        val keyBase64 = obj.optString("keyBase64").takeIf { it.isNotBlank() }
            ?: throw IOException("No hierarchy channel key returned.")
        HierarchyChannelKey(
            keyId = obj.optString("keyId"),
            key = Base64.decode(keyBase64, Base64.NO_WRAP),
            channelId = channelId
        )
    }

    /**
     * The newest message (decrypted) between the caller and [peerUid] in this channel, or null when
     * they've never messaged. Serves double duty: null means "not a real conversation" (keeps the
     * home list to messaged peers), and non-null carries the last-message preview + timestamp for the
     * home-list row. senderUid/recipientUid are plaintext, so we only fetch the key when a pair
     * message actually exists (the key was created when that message was sent — no side effect here).
     */
    suspend fun latestMessageWith(channelId: String, peerUid: String): HierarchyPreview? = withContext(Dispatchers.IO) {
        val me = auth.currentUser?.uid ?: return@withContext null
        val snap = channel(channelId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PREVIEW_SCAN_LIMIT)
            .get()
            .await()
        val doc = snap.documents.firstOrNull { d ->
            val s = d.getString("senderUid").orEmpty()
            val r = d.getString("recipientUid").orEmpty()
            (s == me && r == peerUid) || (s == peerUid && r == me)
        } ?: return@withContext null
        val atMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
        val nonce = doc.getString("nonce")
        val ciphertext = doc.getString("ciphertext")
        val senderUid = doc.getString("senderUid").orEmpty()
        // One key fetch covers both the text and the (metadata-only) attachment decode, so an
        // attachment-only last message can still surface a "Voice message"/"Photo"/"File" preview.
        val key = if (nonce != null || doc.getString("attMeta") != null) {
            runCatching { fetchChannelKey(channelId) }.getOrNull()
        } else {
            null
        }
        val text = if (nonce != null && ciphertext != null && key != null) {
            runCatching { decrypt(key, nonce, ciphertext) }.getOrElse { "" }
        } else {
            ""
        }
        val attachmentKind = if (text.isBlank() && key != null) {
            val attachments = runCatching {
                ChannelAttachments.decodeAttachments(
                    key = key.key,
                    aadString = key.channelId,
                    attMeta = doc.getString("attMeta"),
                    attMetaNonce = doc.getString("attMetaNonce"),
                )
            }.getOrDefault(emptyList())
            attachments.firstOrNull()?.let { att ->
                when {
                    att.isImage -> "image"
                    att.isAudio -> "audio"
                    else -> "file"
                }
            } ?: ""
        } else {
            ""
        }
        // The peer's own client stamps its real display name as `senderName` (plaintext) on every
        // message it sends; grab it so a row whose roster name is only a login email can still show
        // the real name (mirrors iOS HierarchyMessagingClient.latestMessageWith's peerName).
        val peerName = snap.documents
            .firstOrNull { it.getString("senderUid") == peerUid }
            ?.getString("senderName")
            ?.trim()
            .orEmpty()
        HierarchyPreview(
            text = text,
            atMillis = atMillis,
            senderUid = senderUid,
            attachmentKind = attachmentKind,
            peerName = peerName,
        )
    }

    private fun channel(channelId: String) =
        firestore.collection("hierarchyChannels").document(channelId).collection("secureMessages")

    private fun readReceipts(channelId: String) =
        firestore.collection("hierarchyChannels").document(channelId).collection("readReceipts")

    private fun typingCol(channelId: String) =
        firestore.collection("hierarchyChannels").document(channelId).collection("typing")

    // --- Read receipts (✓/✓✓) + typing, byte-compatible with the web (lib/messaging/receipts.ts,
    // typing.ts). Metadata only (uids + timestamps), so no encryption — a member only writes its own doc.

    /** Records that I've read [peerUid]'s messages up to [atMillis]. Doc id `me__peer`. Best-effort. */
    fun writeReadCursor(channelId: String, myUid: String, peerUid: String, atMillis: Long) {
        if (myUid.isBlank() || peerUid.isBlank() || atMillis <= 0L) return
        runCatching {
            readReceipts(channelId).document("${myUid}__${peerUid}").set(
                hashMapOf(
                    "viewerUid" to myUid,
                    "partnerUid" to peerUid,
                    "at" to atMillis,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
        }
    }

    /** Subscribes to how far [peerUid] has read MY messages (millis; 0 when none) — drives ✓✓. */
    fun listenReadCursor(
        channelId: String,
        myUid: String,
        peerUid: String,
        onAt: (Long) -> Unit,
    ): ListenerRegistration =
        readReceipts(channelId).document("${peerUid}__${myUid}").addSnapshotListener { snap, _ ->
            onAt(
                when (val at = snap?.get("at")) {
                    is Number -> at.toLong()
                    is com.google.firebase.Timestamp -> at.toDate().time
                    else -> 0L
                },
            )
        }

    /** Writes my typing state toward [peerUid]. Doc id = my uid. Best-effort. */
    fun setTyping(channelId: String, myUid: String, peerUid: String, typing: Boolean) {
        if (myUid.isBlank() || peerUid.isBlank()) return
        runCatching {
            typingCol(channelId).document(myUid).set(
                hashMapOf(
                    "uid" to myUid,
                    "to" to peerUid,
                    "typing" to typing,
                    "at" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
        }
    }

    /** Subscribes to whether [peerUid] is currently typing to me. */
    fun listenTyping(
        channelId: String,
        myUid: String,
        peerUid: String,
        onTyping: (Boolean) -> Unit,
    ): ListenerRegistration =
        typingCol(channelId).document(peerUid).addSnapshotListener { snap, _ ->
            onTyping(snap?.getBoolean("typing") == true && snap.getString("to").orEmpty() == myUid)
        }

    /** Realtime subscription: decrypts each stored message with the channel key (AAD = channelId). */
    fun listen(channelKey: HierarchyChannelKey, onMessages: (List<HierarchyMessage>) -> Unit): ListenerRegistration =
        channel(channelKey.channelId)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(MESSAGE_LIMIT)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val messages = snapshot.documents.mapNotNull { doc ->
                    val nonce = doc.getString("nonce")
                    val ciphertext = doc.getString("ciphertext")
                    val attachments = ChannelAttachments.decodeAttachments(
                        key = channelKey.key,
                        aadString = channelKey.channelId,
                        attMeta = doc.getString("attMeta"),
                        attMetaNonce = doc.getString("attMetaNonce"),
                    )
                    // Skip only truly empty docs (no text payload AND no attachments).
                    if ((nonce == null || ciphertext == null) && attachments.isEmpty()) return@mapNotNull null
                    val text = if (nonce != null && ciphertext != null) {
                        runCatching { decrypt(channelKey, nonce, ciphertext) }.getOrElse { "⚠️" }
                    } else {
                        ""
                    }
                    HierarchyMessage(
                        id = doc.id,
                        senderUid = doc.getString("senderUid").orEmpty(),
                        senderName = doc.getString("senderName").orEmpty(),
                        recipientUid = doc.getString("recipientUid").orEmpty(),
                        recipientName = doc.getString("recipientName").orEmpty(),
                        text = text,
                        atMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                        attachments = attachments,
                        clientUuid = doc.getString("clientUuid").orEmpty(),
                    )
                }
                onMessages(messages)
            }

    /** Encrypts and posts a message addressed to [recipientUid] within [channelKey]'s channel. */
    suspend fun send(
        channelKey: HierarchyChannelKey,
        senderName: String,
        recipientUid: String,
        recipientName: String,
        text: String,
        attachments: List<PendingChannelAttachment> = emptyList(),
        clientUuid: String? = null,
    ) = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in.")
        val nonce = Crypto.randomBytes(12)
        val ciphertext = Crypto.aesGcmEncrypt(
            key = channelKey.key,
            nonce = nonce,
            plaintext = text.toByteArray(StandardCharsets.UTF_8),
            associatedData = channelKey.channelId.toByteArray(StandardCharsets.UTF_8)
        )
        // Encrypt + upload any blobs first; their descriptors ride in the doc (attMeta/attMetaNonce).
        val attachmentFields = ChannelAttachments.buildAttachmentFields(
            key = channelKey.key,
            aadString = channelKey.channelId,
            pendings = attachments,
        )
        val doc = hashMapOf<String, Any>(
            "senderUid" to uid,
            "senderName" to senderName,
            "recipientUid" to recipientUid,
            "recipientName" to recipientName,
            "keyId" to channelKey.keyId,
            "nonce" to Base64.encodeToString(nonce, Base64.NO_WRAP),
            "ciphertext" to Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        attachmentFields?.let { doc.putAll(it) }
        clientUuid?.takeIf { it.isNotBlank() }?.let { doc["clientUuid"] = it }
        channel(channelKey.channelId).add(doc).await()
        Unit
    }

    private fun decrypt(channelKey: HierarchyChannelKey, nonceBase64: String, ciphertextBase64: String): String {
        val plaintext = Crypto.aesGcmDecrypt(
            key = channelKey.key,
            nonce = Base64.decode(nonceBase64, Base64.NO_WRAP),
            ciphertextAndTag = Base64.decode(ciphertextBase64, Base64.NO_WRAP),
            associatedData = channelKey.channelId.toByteArray(StandardCharsets.UTF_8)
        )
        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun parsePeers(array: JSONArray?): List<HierarchyPeer> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uid = obj.optString("uid").takeIf { it.isNotBlank() } ?: continue
                add(
                    HierarchyPeer(
                        uid = uid,
                        name = obj.optString("name").takeIf { it.isNotBlank() } ?: uid,
                        role = obj.optString("role").takeIf { it.isNotBlank() },
                        photoUrl = obj.optString("photoUrl").takeIf { it.isNotBlank() },
                        agency = obj.optString("agency").takeIf { it.isNotBlank() },
                        phone = obj.optString("phone").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private suspend fun idToken(): String {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in.")
        return user.getIdToken(false).await().token?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ID token missing.")
    }

    private fun requireBaseUrl(): HttpUrl {
        val base = BuildConfig.MOBILE_SYNC_BASE_URL.trim().trimEnd('/')
        val parsed = base.toHttpUrlOrNull()
        require(base.isNotBlank() && parsed != null && isAllowedBaseUrl(parsed)) {
            "MOBILE_SYNC_BASE_URL is not configured."
        }
        return parsed
    }

    private fun isAllowedBaseUrl(url: HttpUrl): Boolean {
        if (url.isHttps) return true
        if (!BuildConfig.DEBUG) return false
        return url.host == "10.0.2.2" || url.host == "127.0.0.1" ||
            url.host == "localhost" || url.host.endsWith(".local")
    }

    /** Executes [request], returning the response body text or throwing on a non-2xx / IO error. */
    private fun execute(request: Request): String? {
        PinnedOkHttpClient.newClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 401/403 = not a manager / not entitled: treat as "no channels" rather than a crash.
                if (response.code == 401 || response.code == 403) return null
                throw IOException("Hierarchy request failed with HTTP ${response.code}")
            }
            return response.body?.string()
        }
    }

    companion object {
        private const val API_PATH = "api/messaging/hierarchy"
        private const val MESSAGE_LIMIT = 200L
        // How many recent channel docs to scan when finding the latest message for a specific peer.
        private const val PREVIEW_SCAN_LIMIT = 40L
    }
}
