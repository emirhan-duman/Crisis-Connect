package com.auralis.crisisconnect.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContactByPeerUid
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.service.NotificationAvatarFormatter
import com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling
import com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives E2E internet messages over the OS push channel (FCM). This is the low-bandwidth
 * downlink: no app-owned socket, the OS already holds one push connection for the whole phone.
 *
 * Incoming data pushes carry the already-encrypted envelope; we decrypt with our identity
 * private key, persist for known conversations and post a local notification (the server has
 * no plaintext to put in a notification, so the client builds it after decrypting).
 */
class CrisisConnectMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hierarchyClient by lazy { HierarchyMessagingClient() }
    private val agencyClient by lazy { AgencyMessagingClient() }

    override fun onNewToken(token: String) {
        // Cache locally so we can (re)register after the user signs in, then push it now.
        applicationContext
            .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
        scope.launch {
            runCatching {
                if (FirebaseAuth.getInstance().currentUser != null) {
                    InternetMessagingClient().registerPushToken(token)
                }
            }.onFailure { Log.w(TAG, "Failed to register push token", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "resource_alert_wake") {
            handleResourceAlertWake(data)
            return
        }
        if (data["type"] == "authority_mls_prepare_v2") {
            handleAuthorityMlsPrepareWake(data)
            return
        }
        if (data["type"] == "authority_mls_v2") {
            handleAuthorityMlsWake(data)
            return
        }
        if (data["type"] == "authority_call_v2") {
            handleAuthorityCallWake(data)
            return
        }
        if (data["type"] == "channel_chat") {
            // Legacy shared-key channel pushes are retired. Processing them could surface old
            // plaintext previews on the lock screen and preserve a downgrade signal after MLS v2.
            Log.w(TAG, "Dropped retired channel_chat push; AuthorityChat accepts MLS v2 wakes only")
            return
        }
        if (data["type"] != "chat") return

        val conversationId = data["conversationId"].orEmpty()
        val senderUid = data["senderUid"].orEmpty()
        val messageId = data["messageId"].orEmpty()
        if (conversationId.isBlank() || messageId.isBlank()) return

        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // `fetch == "1"`: the ciphertext exceeded the FCM budget — which is EVERY attachment chunk
        // and any oversized text. Returning here (as this used to) silently dropped those for a dead
        // process: no notification, and the listener replay on next open doesn't notify either. Pull
        // the document ourselves, bounded so the push handler's execution budget survives; the
        // dedup inside receiveInternetMessage keeps the later listener replay from double-filing.
        var fetchedDoc: com.google.firebase.firestore.DocumentSnapshot? = null
        val ciphertext = data["ciphertext"].let { inline ->
            if (!inline.isNullOrBlank()) return@let inline
            if (data["fetch"] != "1") return
            fetchedDoc = runCatching {
                Tasks.await(
                    FirebaseFirestore.getInstance().collection("messages")
                        .whereEqualTo("messageId", messageId)
                        .whereEqualTo("recipientUid", myUid)
                        .limit(1)
                        .get(),
                    5, TimeUnit.SECONDS
                ).documents.firstOrNull()
            }.getOrNull() ?: return
            fetchedDoc?.getString("ciphertext").orEmpty().ifBlank { return }
        }

        fun field(key: String): String? = fetchedDoc?.getString(key) ?: data[key]
        // `v` selects the crypto layer: 2 = forward-secret Signal (ctype), else the v1 ECIES envelope.
        val isV2 = if (fetchedDoc != null) fetchedDoc?.getLong("v") == 2L else data["v"] == "2"
        val payload: IncomingCiphertext = if (isV2) {
            IncomingCiphertext.V2(
                ctypeLabel = field("ctype").orEmpty(),
                ciphertextBase64 = ciphertext
            )
        } else {
            IncomingCiphertext.V1(
                SealedMessage(
                    ephemeralPublicKey = field("ephemeralPubKey").orEmpty(),
                    nonce = field("nonce").orEmpty(),
                    ciphertext = ciphertext,
                    alg = field("alg").orEmpty().ifBlank { E2eEnvelope.ALG }
                )
            )
        }

        val received = runCatching {
            runBlocking {
                receiveInternetMessage(applicationContext, conversationId, senderUid, myUid, messageId, payload)
            }
        }.getOrElse {
            Log.w(TAG, "Failed to handle incoming internet message", it)
            return
        } ?: return

        // The Bluetooth posters already skip the visible chat; this path fired heads-up + sound
        // into the user's face whenever the FCM copy won the dedup race with the chat open.
        if (ActiveChatTracker.isSessionActive(received.sessionCode)) return
        postNotification(
            conversationId,
            received.senderName,
            received.display,
            // Without this the citizen notification had NO content intent at all — the authority
            // path passed a route, the citizen path forgot, and if (route != null) quietly skipped
            // setContentIntent. Tapping the notification then did literally nothing: not even the
            // app opened. The session extra rides the trusted launch intent like every other
            // navigation, and MainActivity resolves it to the right chat screen.
            sessionCode = received.sessionCode,
            senderUid = senderUid
        )
    }

    private fun handleResourceAlertWake(data: Map<String, String>) {
        val acknowledged = runCatching {
            runBlocking {
                withTimeoutOrNull(10_000L) {
                    ResourceAlertWakeClient.acknowledge(applicationContext, data)
                } == true
            }
        }.getOrDefault(false)
        if (!acknowledged) {
            // The nonce is deliberately absent from logs; the next provider attempt remains the
            // recovery path when the OS wake budget, auth refresh, or network is unavailable.
            ResourceAlertWakeAckWorker.enqueue(applicationContext)
        }
    }

    /**
     * High-priority, content-free wake for an AuthorityChat SFU offer. Push fields are treated only
     * as an index: the immutable Firestore signal is fetched and fully revalidated before ringing.
     */
    private fun handleAuthorityCallWake(data: Map<String, String>) {
        val scopeType = when (data["scopeType"]) {
            AuthorityMlsScopeType.AGENCY.wireName -> AuthorityMlsScopeType.AGENCY
            AuthorityMlsScopeType.HIERARCHY.wireName -> AuthorityMlsScopeType.HIERARCHY
            else -> return
        }
        val channelId = data["channelId"].orEmpty()
        val signalId = data["signalId"].orEmpty()
        val pushCallId = data["callId"].orEmpty()
        val pushSenderUid = data["senderUid"].orEmpty()
        if (channelId.isBlank() || channelId.length > 256 || !SAFE_MLS_ID.matches(signalId) ||
            !CALL_UUID.matches(pushCallId) || pushSenderUid.isBlank() || pushSenderUid.length > 128
        ) return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val kind = if (scopeType == AuthorityMlsScopeType.AGENCY) {
            AuthorityCallSignaling.ChannelKind.AGENCY
        } else {
            AuthorityCallSignaling.ChannelKind.HIERARCHY
        }
        val collection = if (kind == AuthorityCallSignaling.ChannelKind.AGENCY) "agencyPanels" else "hierarchyChannels"
        val firestore = FirebaseFirestore.getInstance()
        val signal = runCatching {
            Tasks.await(
                firestore.document("$collection/$channelId/callSignals/$signalId").get(),
                5, TimeUnit.SECONDS,
            )
        }.getOrNull() ?: return
        if (!signal.exists()) return
        val createdAt = signal.getTimestamp("createdAt") ?: return
        val expireAt = signal.getTimestamp("expireAt") ?: return
        val ageMs = System.currentTimeMillis() - createdAt.toDate().time
        val retentionMs = expireAt.toDate().time - createdAt.toDate().time
        val expectedKeys = setOf(
            "signalVersion", "scopeType", "channelId", "from", "to", "callId", "type",
            "createdAt", "expireAt", "roomId", "video", "sfuVersion",
        )
        val fromUid = signal.getString("from").orEmpty()
        val callId = signal.getString("callId").orEmpty()
        val roomId = signal.getString("roomId").orEmpty()
        if (signal.data?.keys != expectedKeys || signal.getLong("signalVersion") != 2L ||
            signal.getString("scopeType") != scopeType.wireName || signal.getString("channelId") != channelId ||
            signal.getString("type") != "offer" || signal.getLong("sfuVersion") != 2L ||
            signal.getBoolean("video") == null || signal.getString("to") != myUid ||
            fromUid != pushSenderUid || fromUid == myUid || callId != pushCallId ||
            !CALL_UUID.matches(callId) || !CALL_UUID.matches(roomId) || ageMs !in -5_000L..45_000L ||
            retentionMs !in 1L..(25L * 60 * 60 * 1000) || expireAt.toDate().time <= System.currentTimeMillis()
        ) return

        val verified = runBlocking {
            withTimeoutOrNull(15_000L) {
                AuthorityMlsCallGate.isVerified(
                    context = applicationContext,
                    selfUid = myUid,
                    peerUid = fromUid,
                    scopeType = scopeType,
                    channelId = channelId,
                )
            }
        } == true
        val signaling = AuthorityCallSignaling(channelId, myUid, kind)
        if (!verified) {
            runBlocking {
                signaling.sendSfuSignal(
                    fromUid,
                    org.json.JSONObject().put("type", "reject").put("callId", callId),
                )
            }
            return
        }

        val peerDoc = runCatching {
            Tasks.await(firestore.document("users/$fromUid").get(), 3, TimeUnit.SECONDS)
        }.getOrNull()
        val peerName = sequenceOf("displayName", "name", "fullName")
            .mapNotNull { peerDoc?.getString(it)?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: getString(R.string.internet_call_unknown_peer)
        val parsed = org.json.JSONObject()
            .put("type", "offer")
            .put("callId", callId)
            .put("roomId", roomId)
            .put("video", signal.getBoolean("video") == true)
            .put("sfuVersion", 2)
        SfuAuthorityCallManager.init(applicationContext)
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                SfuAuthorityCallManager.onSfuSignal(
                    channelId = channelId,
                    kind = kind,
                    myUid = myUid,
                    fromUid = fromUid,
                    signal = parsed,
                    peerName = peerName,
                )
            }
        }
    }

    /**
     * Content-free MLS bootstrap wake. Every routing field is loaded from and checked against the
     * immutable parent document; the push's conversation id alone is never trusted as authority.
     */
    private fun handleAuthorityMlsPrepareWake(data: Map<String, String>) {
        val conversationId = data["conversationId"].orEmpty()
        if (!AUTHORITY_MLS_CONVERSATION.matches(conversationId)) return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val parent = runCatching {
            Tasks.await(
                FirebaseFirestore.getInstance().document("authorityMlsV2/$conversationId").get(),
                5,
                TimeUnit.SECONDS,
            )
        }.getOrNull() ?: return
        if (parent.getLong("version") != 2L) return
        val participants = (parent.get("participants") as? List<*>)?.mapNotNull { it as? String } ?: return
        if (participants.size != 2 || myUid !in participants || participants.distinct().size != 2) return
        val peerUid = participants.singleOrNull { it != myUid } ?: return
        val scopeType = when (parent.getString("scopeType")) {
            AuthorityMlsScopeType.AGENCY.wireName -> AuthorityMlsScopeType.AGENCY
            AuthorityMlsScopeType.HIERARCHY.wireName -> AuthorityMlsScopeType.HIERARCHY
            else -> return
        }
        val channelId = parent.getString("channelId")?.takeIf { it.isNotBlank() } ?: return
        val canonical = runCatching {
            AuthorityMlsIdentifiers.canonicalBinding(
                AuthorityMlsBinding(scopeType, channelId, participants),
            )
        }.getOrNull() ?: return
        if (canonical.participants != participants ||
            AuthorityMlsIdentifiers.conversationId(canonical) != conversationId
        ) return

        runBlocking {
            withTimeoutOrNull(25_000L) {
                AuthorityMlsPrewarmer.prewarm(
                    applicationContext,
                    myUid,
                    listOf(AuthorityMlsPrewarmTarget(peerUid, scopeType, channelId)),
                )
            }
        }
    }

    /**
     * Content-free Authority MLS wake-up. Resolve only the immutable v2 binding and ciphertext
     * metadata, then show a generic notification. Plaintext stays in the verified chat session;
     * creating a second background MLS session here would race its durable ratchet state.
     */
    private fun handleAuthorityMlsWake(data: Map<String, String>) {
        val conversationId = data["conversationId"].orEmpty()
        val messageId = data["messageId"].orEmpty()
        if (!AUTHORITY_MLS_CONVERSATION.matches(conversationId) || !SAFE_MLS_ID.matches(messageId)) return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val firestore = FirebaseFirestore.getInstance()
        val parent = runCatching {
            Tasks.await(firestore.document("authorityMlsV2/$conversationId").get(), 5, TimeUnit.SECONDS)
        }.getOrNull() ?: return
        if (parent.getLong("version") != 2L) return
        val participants = (parent.get("participants") as? List<*>)?.mapNotNull { it as? String } ?: return
        if (participants.size != 2 || myUid !in participants) return
        val peerUid = participants.singleOrNull { it != myUid } ?: return
        val scopeType = when (parent.getString("scopeType")) {
            AuthorityMlsScopeType.AGENCY.wireName -> AuthorityMlsScopeType.AGENCY
            AuthorityMlsScopeType.HIERARCHY.wireName -> AuthorityMlsScopeType.HIERARCHY
            else -> return
        }
        val channelId = parent.getString("channelId")?.takeIf { it.isNotBlank() } ?: return
        val canonical = runCatching {
            AuthorityMlsIdentifiers.canonicalBinding(
                AuthorityMlsBinding(scopeType, channelId, participants),
            )
        }.getOrNull() ?: return
        if (canonical.participants != participants ||
            AuthorityMlsIdentifiers.conversationId(canonical) != conversationId
        ) return

        val ciphertext = runCatching {
            Tasks.await(
                firestore.document("authorityMlsV2/$conversationId/messages/$messageId").get(),
                5,
                TimeUnit.SECONDS,
            )
        }.getOrNull() ?: return
        val senderDeviceId = ciphertext.getString("senderDeviceId") ?: return
        val senderCredential = ciphertext.getString("senderCredential") ?: return
        val parsedCredential = AuthorityMlsCredential.decode(senderCredential) ?: return
        if (ciphertext.getLong("contentVersion") != 2L ||
            ciphertext.getString("messageId") != messageId ||
            ciphertext.getString("senderUid") != peerUid ||
            !SAFE_MLS_ID.matches(senderDeviceId) ||
            parsedCredential.accountUid != peerUid ||
            parsedCredential.deviceId != senderDeviceId ||
            ciphertext.getLong("sequence")?.let { it >= 0 && it < 9_007_199_254_740_991L } != true ||
            ciphertext.getString("ciphertext")?.let {
                it.isNotEmpty() && it.length <= 900_000 && MLS_BASE64URL.matches(it)
            } != true ||
            ciphertext.getTimestamp("createdAt") == null
        ) return

        val route = if (scopeType == AuthorityMlsScopeType.HIERARCHY) {
            "authority_channel/${Uri.encode(channelId)}/${Uri.encode(peerUid)}"
        } else {
            "authority_channels"
        }
        postNotification(
            conversationId = conversationId,
            title = getString(R.string.authority_channels_title),
            body = getString(R.string.authority_message_notification_body),
            route = route,
            senderUid = peerUid,
            authority = true,
        )
    }

    /**
     * Authority CHANNEL message push (agency/hierarchy). The content is E2E, so the push carries only
     * routing (sender + channel) — no plaintext. Instead of a generic "encrypted message" card, we
     * fetch the latest message for this channel and DECRYPT it on-device (same shared channel key the
     * thread screen uses) so the notification shows the real text, or a "🎤 Voice message"/"📷 Photo"/
     * "📎 File" preview for an attachment. Falls back to the generic body if decryption isn't possible
     * (offline / key fetch fails). Skips our own messages (belt-and-braces over the server's exclusion).
     */
    private fun handleChannelMessage(data: Map<String, String>) {
        val senderUid = data["senderUid"].orEmpty()
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        if (senderUid.isBlank() || senderUid == myUid) return
        val senderName = data["senderName"].orEmpty()
            .ifBlank { getString(R.string.internet_message_notification_title) }
        val channelKind = data["channelKind"].orEmpty()
        val channelId = data["channelId"].orEmpty().ifBlank { "channel" }
        // For a hierarchy (1:1-within-channel) message, deep-link straight into that peer's thread; the
        // peer is the sender. Agency (broadcast) pushes fall back to the channel list.
        val route = if (channelKind == "hierarchy" && channelId != "channel") {
            "authority_channel/${Uri.encode(channelId)}/${Uri.encode(senderUid)}?title=${Uri.encode(senderName)}"
        } else {
            "authority_channels"
        }
        Log.i(TAG, "channel_chat push: kind=$channelKind channelId=$channelId route=$route")

        // Decrypt the latest message for a real preview (bounded so a slow network can't hang the push
        // handler; the generic body is the fallback). Hierarchy (1:1) and agency (broadcast) messages
        // live in different collections with different keys — route by kind.
        val body = runCatching {
            runBlocking {
                withTimeoutOrNull(6_000L) {
                    if (channelKind == "agency") {
                        agencyClient.latestMessage(channelId)?.text
                            ?.takeIf { it.isNotBlank() }
                            ?.let { HierarchyPreview(text = it, atMillis = 0L) }
                    } else {
                        hierarchyClient.latestMessageWith(channelId, senderUid)
                    }
                }
            }
        }.getOrNull().let { preview ->
            when {
                preview == null -> getString(R.string.authority_message_notification_body)
                preview.attachmentKind == "image" -> getString(R.string.notification_preview_photo)
                preview.attachmentKind == "audio" -> getString(R.string.notification_preview_voice)
                preview.attachmentKind == "file" -> getString(R.string.notification_preview_file)
                preview.text.isNotBlank() ->
                    channelControlPayloadLabel(preview.text) ?: preview.text
                else -> getString(R.string.authority_message_notification_body)
            }
        }

        postNotification(
            conversationId = channelId,
            title = senderName,
            body = body,
            route = route,
            senderUid = senderUid,
            authority = true,
        )
    }

    /**
     * Human label for a channel CONTROL payload, so the notification body never shows raw wire
     * data: a call summary (U+0001"call"U+0001+JSON) becomes "Missed/Video/Voice call" and a
     * shared location (CC_LOC…) "Shared location". Null for ordinary text.
     */
    private fun channelControlPayloadLabel(raw: String): String? = when {
        raw.startsWith('') -> runCatching {
            val json = org.json.JSONObject(raw.substringAfter('').substringAfter(''))
            val missed = json.optString("status") != "ended"
            val video = json.optString("kind") == "video"
            getString(
                when {
                    missed -> R.string.authority_channel_call_missed
                    video -> R.string.authority_channel_call_video
                    else -> R.string.authority_channel_call_audio
                }
            )
        }.getOrDefault(getString(R.string.authority_channel_call_audio))
        isChatLocationPayload(raw) ->
            getString(R.string.chat_location_preview_label)
        else -> null
    }

    private fun isChatLocationPayload(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.contains("CC_LOC:", ignoreCase = true)) return true
        return Regex("""https?://maps\.google\.com/\?q=([-0-9.]+),([-0-9.]+)""").containsMatchIn(trimmed)
    }

    /**
     * Chat-style notification (MessagingStyle) with the sender rendered as a proper conversation
     * [Person] — name + circular avatar — instead of the old flat title/body card. The avatar comes
     * from the sender's profile photo (`users/{uid}.photoURL`, fetched best-effort with a short
     * timeout and cached per uid) and falls back to the same deterministic colored-initial avatar
     * contacts get. Authority (kurum) messages get their own channel so the user can tune them
     * separately from citizen chats.
     */
    @SuppressLint("MissingPermission")
    private fun postNotification(
        conversationId: String,
        title: String?,
        body: String,
        route: String? = null,
        sessionCode: String? = null,
        senderUid: String? = null,
        authority: Boolean = false,
    ) = postInternetMessageNotification(
        this, conversationId, title, body, route, sessionCode, senderUid, authority
    )

    companion object {
        private val AUTHORITY_MLS_CONVERSATION = Regex("^am2_[A-Za-z0-9_-]{43}$")
        private val SAFE_MLS_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val MLS_BASE64URL = Regex("^[A-Za-z0-9_-]+$")
        private val CALL_UUID = Regex(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"
        )
        private const val TAG = "CCMessagingService"

        private const val TOKEN_PREFS = "crisisconnect_fcm_token"
        private const val KEY_TOKEN = "fcm_token"
    }
}


private const val NOTIF_TAG = "CCMessagingNotif"
private const val CHANNEL_ID = "internet_messages"
private const val AUTHORITY_CHANNEL_ID = "authority_messages"
private const val MAX_HISTORY = 6

// Per-process profile-photo cache so back-to-back pushes from the same sender don't refetch.
private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

// Recent messages per conversation, so MessagingStyle can render a running thread instead of a
// single card. Bounded to MAX_HISTORY; cleared when the chat is opened (see
// clearInternetMessageNotification — auto-cancel alone never cleared it, so the next push
// re-posted up to MAX_HISTORY already-read messages inside a newly alerting card).
private val conversationHistory =
    java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<CCNotifHistoryEntry>>()

private data class CCNotifHistoryEntry(val text: String, val time: Long, val person: Person)

/**
 * Posts (or updates) the chat-style notification for an internet message. File-level, not a Service
 * member, because BOTH delivery paths must reach it: the FCM push handler AND the Firestore
 * listener — whichever wins InternetMessageDedup owns the message, and when the listener won, the
 * message used to be filed silently with no notification at all.
 */
@SuppressLint("MissingPermission")
internal fun postInternetMessageNotification(
    context: Context,
    conversationId: String,
    title: String?,
    body: String,
    route: String? = null,
    sessionCode: String? = null,
    senderUid: String? = null,
    authority: Boolean = false,
) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = if (authority) AUTHORITY_CHANNEL_ID else CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    context.getString(
                        if (authority) R.string.authority_message_channel_name
                        else R.string.internet_message_channel_name
                    ),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    if (authority) description = context.getString(R.string.authority_message_channel_desc)
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                }
            )
        }
        if (!hasPostNotificationsPermission(context)) {
            Log.w(NOTIF_TAG, "Skipping internet message notification: POST_NOTIFICATIONS not granted")
            return
        }
        val senderName = title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.internet_message_notification_title)
        val avatar = resolveSenderAvatar(context, senderUid, senderName)
        val sender = Person.Builder()
            .setName(senderName)
            .setKey(senderUid ?: conversationId)
            .setIcon(avatar?.let(IconCompat::createWithBitmap))
            .build()
        val me = Person.Builder().setName(context.getString(R.string.notification_me_label)).build()
        val now = System.currentTimeMillis()
        // Stack this message onto the conversation's recent history so the notification reads like a
        // real thread (WhatsApp-style) instead of a single card that replaces the previous one.
        // Key the card by the LOCAL thread when known, not the wire conversation id: for ble:-keyed
        // contacts these differ, so one person got two independent cards and chat-open cleanup could
        // never find the internet one. History must share the key or a merged card shows partials.
        val notifKey = sessionCode ?: conversationId
        val history = conversationHistory.getOrPut(notifKey) { ArrayDeque() }
        synchronized(history) {
            history.addLast(CCNotifHistoryEntry(body, now, sender))
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        val style = NotificationCompat.MessagingStyle(me)
        synchronized(history) { history.forEach { style.addMessage(it.text, it.time, it.person) } }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setWhen(now)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(true)
            .setOnlyAlertOnce(false)
            .setColor(ContextCompat.getColor(context, R.color.teal_700))
            .apply { if (avatar != null) setLargeIcon(avatar) }
        if (route != null || sessionCode != null) {
            // Trusted in-app intent (carries the launch token MainActivity requires to honor the
            // route/session — a bare intent's navigation extras are dropped by design).
            val intent = MainActivity.createTrustedLaunchIntent(context) {
                if (route != null) putExtra(MainActivity.EXTRA_NAVIGATE_TO_ROUTE, route)
                if (sessionCode != null) putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, sessionCode)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(
                context,
                notifKey.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(pending)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(notifKey.hashCode(), builder.build())
        }.onFailure { Log.w(NOTIF_TAG, "Notification not shown (permission?)", it) }
    }


/** Clears the thread's card + stacked history when its chat is opened. */
internal fun clearInternetMessageNotification(context: Context, sessionCode: String) {
    conversationHistory.remove(sessionCode)
    runCatching { NotificationManagerCompat.from(context).cancel(sessionCode.hashCode()) }
}

private fun resolveSenderAvatar(context: Context, senderUid: String?, senderName: String): Bitmap? {
    val photo = senderUid?.let { uid ->
        avatarCache[uid] ?: runCatching {
            val doc = Tasks.await(
                FirebaseFirestore.getInstance().collection("users").document(uid).get(),
                2, TimeUnit.SECONDS
            )
            val url = doc.getString("photoURL")?.trim()?.takeIf { it.startsWith("https://") }
                ?: return@runCatching null
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 2000
                readTimeout = 2500
                inputStream.use(BitmapFactory::decodeStream)
            }
        }.getOrNull()?.also { avatarCache[uid] = it }
    }
    return runCatching {
        NotificationAvatarFormatter.resolveRemoteAvatarBitmap(
            context, seed = senderUid ?: senderName, name = senderName, photo = photo
        )
    }.getOrNull()
}

private fun hasPostNotificationsPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
