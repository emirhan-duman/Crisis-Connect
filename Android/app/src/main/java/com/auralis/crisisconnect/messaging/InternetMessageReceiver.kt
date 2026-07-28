package com.auralis.crisisconnect.messaging

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.data.deleteContact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContactByPeerUid
import com.auralis.crisisconnect.data.getContactByRemoteSessionCode
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.messaging.call.InternetCallManager
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.markLocalMessagesDeliveredWithRecipient
import com.auralis.crisisconnect.data.markLocalMessagesReadWithRecipient
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.updateSosAlertText
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.security.ChildProfileManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of handling an incoming internet message (for the caller to notify with). */
/**
 * [sessionCode] is the LOCAL thread key (a Bluetooth-added contact is keyed "ble:<device-id>", not
 * the internet conversation id) — it is what the notification tap must navigate to.
 */
data class ReceivedInternetMessage(
    val senderName: String?,
    val display: String,
    val sessionCode: String
)

private const val RECEIVE_TAG = "InternetReceive"

/**
 * Resolves the sender's authenticated identity, decrypts the message, persists it and acks so the
 * server drops its copy. Returns null (dropping without acking) whenever the sender/conversation
 * binding fails or decryption doesn't authenticate. Shared by the FCM push path
 * ({@link CrisisConnectMessagingService}) and the realtime Firestore listener below.
 *
 * Two layers of anti-impersonation:
 *  1. Conversation binding — refuse to file into a thread that belongs to a different peer, and only
 *     auto-create a thread when conversationId is the canonical pair id for (sender, me).
 *  2. Cryptographic sender auth — E2eEnvelope.open mixes ECDH(myIdentity, senderIdentity) into the
 *     key, so a message decrypts only if it was authored with the sender's private key.
 */
/** The two on-the-wire ciphertext shapes: v1 authenticated-ECIES, v2 forward-secret Signal. */
internal sealed interface IncomingCiphertext {
    data class V1(val sealed: SealedMessage) : IncomingCiphertext
    data class V2(val ctypeLabel: String, val ciphertextBase64: String) : IncomingCiphertext
}

/**
 * De-dupes the FCM-push and Firestore-listener copies of the same message BEFORE they reach the
 * ratchet. v1/v2 decryption is idempotent (the duplicate is caught later by the messageId row in
 * Room), but a v3 Signal message key is ONE-TIME: decrypting the same ciphertext twice throws
 * DuplicateMessageException. The two delivery paths land within a few hundred ms of each other, so
 * the loser must skip decryption entirely. Bounded, in-memory only — the durable dedup remains the
 * Signal session store (v3) and the messages table (v1/v2), which cover process restarts.
 */
internal object InternetMessageDedup {
    private const val MAX_TRACKED = 512
    private val processed = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > MAX_TRACKED
    }

    /** Atomically claim [messageId]; true = this caller owns it (proceed), false = a copy already has it. */
    @Synchronized
    fun claim(messageId: String): Boolean {
        if (messageId.isBlank()) return true
        if (processed.containsKey(messageId)) return false
        processed[messageId] = true
        return true
    }

    /** Release a claim so a genuine later re-delivery can retry (used only on a real, non-dup failure). */
    @Synchronized
    fun release(messageId: String) {
        processed.remove(messageId)
    }
}

internal suspend fun receiveInternetMessage(
    context: Context,
    conversationId: String,
    senderUid: String,
    myUid: String,
    messageId: String,
    payload: IncomingCiphertext
): ReceivedInternetMessage? {
    val sender = senderUid.trim()
    if (sender.isEmpty()) {
        Log.w(RECEIVE_TAG, "Dropping internet message with no sender")
        return null
    }
    // Never ingest our own traffic. iOS has always had this check; Android did not, so a contact that
    // somehow carried OUR uid (a self-pairing, or the same account signed in on two devices) would
    // make us decrypt and display our own sends as if a peer had written them — and an SOS alert
    // addressed that way would land in a thread with ourselves instead of reaching anyone.
    if (sender.equals(myUid.trim(), ignoreCase = true)) {
        Log.w(RECEIVE_TAG, "Dropping internet message whose sender is this account")
        return null
    }

    // Find the local thread for this peer. First by the conversation id's session code; else by the
    // peer's uid — a Bluetooth-added contact is keyed by "ble:<other-device-id>" (different per
    // peer), so it can only be found by the internet identity, not the conversationId.
    var contact = getContact(context, conversationId)
        ?: getContactByPeerUid(context, sender)
    if (contact != null && contact.peerUid.isNotBlank() && contact.peerUid.trim() != sender) {
        Log.w(RECEIVE_TAG, "Dropping internet message: conversation does not belong to the sender")
        return null
    }

    // Resolve the peer's v2 identity key (needed for v1 decryption AND to stamp on a new contact).
    // For a brand-new peer, require the canonical pair-id conversation before a directory lookup.
    var peer: PeerIdentityKey? = null
    var senderPublicKeyB64: String = contact?.peerPublicKey?.takeIf { it.isNotBlank() }.orEmpty()
    if (senderPublicKeyB64.isEmpty()) {
        if (conversationId != InternetConversation.pairId(sender, myUid)) {
            Log.w(RECEIVE_TAG, "Dropping internet message: conversationId is not canonical for the sender")
            return null
        }
        peer = runCatching { InternetMessagingClient().lookupIdentityKey(uid = sender) }.getOrNull()
        senderPublicKeyB64 = peer?.publicKeyBase64?.takeIf { it.isNotBlank() }.orEmpty()
        // A v1 message can't be authenticated without the sender's identity key; a v2 (Signal)
        // message authenticates via the session, so an absent v2 key is not fatal there.
        if (senderPublicKeyB64.isEmpty() && payload is IncomingCiphertext.V1) {
            Log.w(RECEIVE_TAG, "Dropping internet message: no identity key for sender")
            return null
        }
    }

    val gate = com.auralis.crisisconnect.messaging.signal.SignalSessionGate.create(context)
    // Anti-downgrade pin: once a v3 session exists with this peer, a v1 message from them is a
    // stripped-v3 downgrade attempt — drop it fail-closed (no ack).
    if (payload is IncomingCiphertext.V1 && gate.hasV3Session(sender)) {
        Log.w(RECEIVE_TAG, "Dropping v1 message: downgrade from an established v3 session with $sender")
        return null
    }

    // Claim the id BEFORE touching the ratchet so the racing FCM/listener copy skips decryption
    // entirely (a v3 message key is one-time — a second decrypt of it throws DuplicateMessage).
    if (!InternetMessageDedup.claim(messageId)) {
        Log.d(RECEIVE_TAG, "Skipping duplicate delivery of message $messageId")
        return null
    }

    val content = try {
        when (payload) {
            is IncomingCiphertext.V1 -> {
                val identity = MessagingIdentity(context)
                E2eEnvelope.open(
                    sealed = payload.sealed,
                    deriveSharedSecret = { pub -> identity.deriveSharedSecret(pub) },
                    senderStaticPublicKey = MessagingKeyCodec.decodePublicKey(senderPublicKeyB64),
                    senderUid = sender,
                    recipientUid = myUid,
                    conversationId = conversationId
                )
            }
            is IncomingCiphertext.V2 -> {
                gate.decrypt(sender, payload.ctypeLabel, payload.ciphertextBase64)
                    .also { gate.clearNegativeProbe(sender) }
            }
        }
    } catch (dup: org.signal.libsignal.protocol.DuplicateMessageException) {
        // In-memory claims are lost on process restart, so a listener replay of an already-decrypted
        // v3 message can still reach the ratchet — libsignal authoritatively flags it as a duplicate.
        // Benign (it was already delivered), so also ack it: the original delivery's ack may have
        // been lost, and without one the server copy replays on every listener attach forever.
        Log.d(RECEIVE_TAG, "Ignoring already-decrypted v3 message $messageId")
        InternetMessagingClient().acknowledgeMessage(messageId)
        return null
    } catch (t: Throwable) {
        // A genuine authentication/decryption failure — surface it, and release the claim so a real
        // later re-delivery (e.g. after a transient store error) can be retried rather than swallowed.
        Log.w(RECEIVE_TAG, "Dropping internet message: failed to authenticate/decrypt", t)
        InternetMessageDedup.release(messageId)
        return null
    }

    // Content-free breadcrumb (Log.i survives release) so FS-5 can confirm which transport decrypted.
    Log.i(
        RECEIVE_TAG,
        "internet recv template=${content.templateCode} via " +
            if (payload is IncomingCiphertext.V2) "v3(${payload.ctypeLabel})" else "v2"
    )

    // Delivery/read receipts reference OUR local bubbles; apply them to the existing thread and
    // stop. Handled BEFORE the thread auto-creation below so a receipt never creates a contact.
    if (content.templateCode == InternetChatTransport.DELIVERY_RECEIPT_TEMPLATE ||
        content.templateCode == InternetChatTransport.READ_RECEIPT_TEMPLATE
    ) {
        InternetMessagingClient().acknowledgeMessage(messageId)
        if (contact != null) {
            applyInternetReceipt(context, contact, content)
        }
        return null
    }

    // Ephemeral "peer is typing" pulse → in-memory bus only; never stored, never notified. Also
    // before thread auto-creation: a pulse from a peer we don't hold must not create a ghost thread.
    if (content.templateCode == InternetChatTransport.TYPING_SIGNAL_TEMPLATE) {
        InternetMessagingClient().acknowledgeMessage(messageId)
        contact?.sessionCode?.trim()?.takeIf { it.isNotBlank() }?.let(TypingIndicatorBus::signalTyping)
        return null
    }

    // Reciprocal identity announce from a peer who just QR-paired with us: heal our BLE-only paired
    // contact into an internet-capable one by stamping the sender's uid + key onto it. The BLE
    // client-hello can drop the identity on a small Android↔iOS MTU, so this relay-borne announce is
    // the MTU-independent path that makes QR pairing bidirectional. Matched by the announced session
    // code (our stored remoteSessionCode for that peer). Never auto-creates a thread.
    if (content.templateCode == InternetChatTransport.IDENTITY_ANNOUNCE_TEMPLATE) {
        val announceJson = runCatching { org.json.JSONObject(content.text) }.getOrNull()
        val announcedCode = announceJson?.optString("rsc")?.trim().orEmpty()
        // Prefer the key carried IN the (authenticated) payload — always present, no directory
        // lookup needed (the lookup lags after the peer's fresh install; a v3 announce leaves
        // senderPublicKeyB64 blank otherwise).
        val announcedKey = announceJson?.optString("pk")?.trim().orEmpty()
        val effectiveKey = senderPublicKeyB64.ifBlank { announcedKey }
        val target = announcedCode.takeIf { it.isNotBlank() }?.let {
            getContactByRemoteSessionCode(context, it) ?: getContact(context, it)
        }?.takeIf { it.peerUid.isBlank() }
        var healed = false
        if (effectiveKey.isNotBlank() && target != null) {
            // Heal FIRST, ack LAST: the announce is fire-once and a v3 copy can't be re-decrypted on
            // replay, so it must land now. Absorb any internet-only duplicate a preceding plain
            // message auto-created for this uid, so the user is never left with two threads.
            runCatching {
                getContactByPeerUid(context, sender)?.takeIf { it.sessionCode != target.sessionCode }
                    ?.let { deleteContact(context, it.sessionCode) }
                saveContact(
                    context,
                    target.copy(peerUid = sender, peerPublicKey = effectiveKey),
                    analyticsSource = "identity_announce"
                )
                healed = true
                Log.i(RECEIVE_TAG, "Healed BLE contact ${target.sessionCode} with internet identity from announce")
            }.onFailure { Log.w(RECEIVE_TAG, "Identity announce heal failed", it) }
        }
        // Ack even on a no-match: replaying it would not match any better, and a v3 copy is
        // undecryptable on replay. A transient heal failure is the only case we'd want to retry, but
        // Room failures here are effectively permanent, so acking avoids an infinite relay replay.
        InternetMessagingClient().acknowledgeMessage(messageId)
        return null
    }

    val display = if (content.text.isNotBlank()) content.text else "[#${content.templateCode}]"

    // File into the EXISTING thread's session code so a Bluetooth chat keeps ONE thread; only create
    // a new internet thread (keyed by the canonical pair id) when we hold no contact yet.
    val threadSessionCode = contact?.sessionCode?.trim()?.takeIf { it.isNotBlank() } ?: conversationId
    if (contact == null) {
        val name = peer?.displayName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.internet_message_unknown_sender)
        saveContact(
            context,
            Contact(
                name = name,
                aesKey = "",
                sessionCode = threadSessionCode,
                peerUid = sender,
                peerPublicKey = senderPublicKeyB64,
                peerPhotoUrl = peer?.photoUrl.orEmpty(),
                peerIsChild = peer?.isChild ?: false
            ),
            analyticsSource = "internet_message",
            analyticsReceived = true
        )
        contact = getContact(context, threadSessionCode) ?: return null
    } else if (contact.peerUid.isBlank() || contact.peerPublicKey.isBlank()) {
        val stamped = contact.copy(peerUid = sender, peerPublicKey = senderPublicKeyB64)
        saveContact(context, stamped)
        contact = stamped
    }

    // A child device asks this user to become its parent → approval notification with
    // accept/decline actions; never filed as a chat message.
    if (content.templateCode == InternetChatTransport.PARENT_REQUEST_TEMPLATE) {
        InternetMessagingClient().acknowledgeMessage(messageId)
        runCatching { ParentRequestNotifier.notifyParentRequest(context, contact) }
            .onFailure { Log.w(RECEIVE_TAG, "Failed to surface parent request", it) }
        return null
    }

    // The asked contact answered our parent request: promote or drop the pending entry.
    // confirmParent/the pending check make a duplicate FCM+listener delivery a no-op.
    if (content.templateCode == InternetChatTransport.PARENT_RESPONSE_TEMPLATE) {
        InternetMessagingClient().acknowledgeMessage(messageId)
        val accepted = runCatching {
            org.json.JSONObject(content.text).optString("action")
        }.getOrDefault("") == "accept"
        val hadPending = if (accepted) {
            ChildProfileManager.confirmParent(context, contact.sessionCode)
        } else {
            val wasPending = contact.sessionCode in
                ChildProfileManager.pendingParentsFlow(context).value
            if (wasPending) ChildProfileManager.removePendingParent(context, contact.sessionCode)
            wasPending
        }
        if (hadPending) {
            runCatching { ParentRequestNotifier.notifyParentResponse(context, contact, accepted) }
                .onFailure { Log.w(RECEIVE_TAG, "Failed to surface parent response", it) }
        }
        return null
    }

    // WebRTC call signaling (SDP/ICE + ring/accept/reject/end) rides the relay tagged with a reserved
    // templateCode. Route it to the call manager and ack it; never file it as a chat message.
    if (content.templateCode == InternetChatTransport.CALL_SIGNAL_TEMPLATE) {
        InternetMessagingClient().acknowledgeMessage(messageId)
        runCatching {
            InternetCallManager.onSignal(contact, sender, org.json.JSONObject(content.text))
        }.onFailure { Log.w(RECEIVE_TAG, "Failed to route call signal", it) }
        return null
    }

    // Live-location update for an earlier SOS alert: rewrite the referenced bubble in place —
    // silent (no notification, no new message). If the original alert never arrived, file the
    // update AS the alert so the contact still ends up with the emergency + latest location.
    if (content.templateCode == InternetChatTransport.SOS_LOCATION_UPDATE_TEMPLATE) {
        InternetMessagingClient().acknowledgeMessage(messageId)
        runCatching {
            val payload = org.json.JSONObject(content.text)
            val ref = payload.optString("ref").trim()
            val newText = payload.optString("text").trim()
            if (ref.isNotEmpty() && newText.isNotEmpty()) {
                val updated = updateSosAlertText(context, uuid = ref, text = newText)
                if (!updated) {
                    saveRemoteMessage(
                        context = context,
                        sessionCode = threadSessionCode,
                        uuid = ref,
                        text = newText,
                        senderDisplayName = contact.name,
                        messageType = MessageType.SOS_ALERT
                    )
                }
            }
        }.onFailure { Log.w(RECEIVE_TAG, "Failed to apply SOS location update", it) }
        return null
    }

    // A real message landing means the peer is done typing — drop the indicator immediately.
    TypingIndicatorBus.clear(threadSessionCode)

    val attachment = content.attachment
    if (attachment != null) {
        // Ack THIS chunk so the relay drops its copy, then buffer it. We only persist a message and
        // notify once every chunk of the transfer has arrived and reassembled.
        InternetMessagingClient().acknowledgeMessage(messageId)
        val complete = AttachmentReassembler.offer(attachment) ?: return null
        val display = persistReceivedAttachment(context, threadSessionCode, attachment, complete)
            ?: return null
        // The sender's voice/image bubble uuid IS the transferId, so this flips it to "delivered".
        // A document's bubble is the paired CC_FILE text message, whose receipt goes out via the
        // text path below; the transferId receipt is then a harmless no-op on the sender.
        sendInternetDeliveryReceipt(context, contact, attachment.transferId)
        // Documents carry no display text (the CC_FILE message is the visible bubble) — don't notify.
        if (display.isEmpty()) return null
        return ReceivedInternetMessage(
            senderName = contact.name,
            display = display,
            sessionCode = threadSessionCode
        )
    }

    // SOS alerts persist like text but with their own message type, so the chat renders them as a
    // distinct emergency bubble instead of an ordinary text message.
    val messageType = if (content.templateCode == InternetChatTransport.SOS_ALERT_TEMPLATE) {
        MessageType.SOS_ALERT
    } else {
        MessageType.TEXT
    }
    val inserted = saveRemoteMessage(
        context = context,
        sessionCode = threadSessionCode,
        uuid = messageId,
        text = display,
        senderDisplayName = contact.name,
        messageType = messageType
    )
    InternetMessagingClient().acknowledgeMessage(messageId)
    // Tell the sender their bubble reached this device (idempotent on their side, so re-sending it
    // for a duplicate doc is harmless — and it covers a first receipt that got lost).
    sendInternetDeliveryReceipt(context, contact, messageId)
    if (!inserted) {
        // Duplicate: the FCM push and the realtime listener both delivered this doc — notify once.
        return null
    }
    return ReceivedInternetMessage(
        senderName = contact.name,
        display = display,
        sessionCode = threadSessionCode
    )
}

/**
 * Applies an incoming delivery/read receipt to our local outgoing bubbles. The referenced ids are
 * wire message ids; when the original send fell back to the relayMessage callable, its stricter id
 * charset replaced the store-and-forward '~' with ':' — try that variant too so the receipt lands.
 */
private suspend fun applyInternetReceipt(
    context: Context,
    contact: Contact,
    content: MessageContent
) {
    val uuids = runCatching {
        val array = org.json.JSONObject(content.text).optJSONArray("ids") ?: return@runCatching emptySet()
        buildSet {
            for (i in 0 until array.length()) {
                val id = array.optString(i).trim()
                if (id.isEmpty()) continue
                add(id)
                if (id.contains(':')) add(id.replace(':', '~'))
            }
        }
    }.getOrElse {
        Log.w(RECEIVE_TAG, "Dropping malformed receipt payload", it)
        return
    }
    if (uuids.isEmpty()) return
    if (content.templateCode == InternetChatTransport.READ_RECEIPT_TEMPLATE) {
        markLocalMessagesReadWithRecipient(
            context = context,
            sessionCode = contact.sessionCode,
            messageUuids = uuids,
            recipientLabel = contact.name
        )
    } else {
        markLocalMessagesDeliveredWithRecipient(
            context = context,
            sessionCode = contact.sessionCode,
            messageUuids = uuids,
            recipientLabel = contact.name
        )
    }
}

/** Best-effort "delivered to this device" receipt back to the sender. Never blocks message filing. */
private suspend fun sendInternetDeliveryReceipt(
    context: Context,
    contact: Contact,
    messageId: String
) {
    runCatching {
        InternetChatTransport(context).sendReceipt(
            contact = contact,
            templateCode = InternetChatTransport.DELIVERY_RECEIPT_TEMPLATE,
            messageIds = listOf(messageId)
        )
    }.onFailure { Log.w(RECEIVE_TAG, "Failed to send delivery receipt", it) }
}

/**
 * Buffers attachment chunks (keyed by transfer id) until every chunk of a transfer has arrived, then
 * returns the reassembled bytes. In-memory only — fine for the common case where both peers are
 * online and chunks land within seconds; a mid-transfer restart simply drops the partial transfer
 * (the sender's relay copies were already ack'd, so nothing is resent — acceptable for v1). Bounded
 * so a hostile sender can't exhaust memory.
 */
private object AttachmentReassembler {
    private const val MAX_CHUNKS = 4000
    private const val MAX_TOTAL_SIZE = 25 * 1024 * 1024 // 25 MB

    private class Pending(val chunkCount: Int, val totalSize: Int) {
        val chunks = HashMap<Int, ByteArray>()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    // The FCM push and the realtime listener can BOTH deliver the same chunk docs. Without this,
    // a duplicate of the final chunk arriving after completion would seed a fresh Pending that
    // never completes (leak) — or, if every chunk repeats, complete the whole transfer twice.
    private val completedTransferIds = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 64
    }

    @Synchronized
    fun offer(att: MessageAttachment): ByteArray? {
        if (att.chunkCount !in 1..MAX_CHUNKS || att.totalSize !in 1..MAX_TOTAL_SIZE) return null
        if (completedTransferIds.containsKey(att.transferId)) return null
        val p = pending.getOrPut(att.transferId) { Pending(att.chunkCount, att.totalSize) }
        if (att.chunkIndex in 0 until p.chunkCount) {
            p.chunks[att.chunkIndex] = att.bytes
        }
        if (p.chunks.size < p.chunkCount) return null
        val out = ByteArray(p.totalSize)
        var offset = 0
        for (i in 0 until p.chunkCount) {
            val c = p.chunks[i] ?: return null
            if (offset + c.size > out.size) { pending.remove(att.transferId); return null }
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        pending.remove(att.transferId)
        completedTransferIds[att.transferId] = true
        return out
    }
}

/** Writes a reassembled attachment to storage and saves it as a chat message. Returns the notification
 *  text ("" for kinds whose visible bubble arrives separately, like documents), or null on failure /
 *  a kind that isn't persisted yet. */
private suspend fun persistReceivedAttachment(
    context: Context,
    threadSessionCode: String,
    att: MessageAttachment,
    data: ByteArray
): String? = when (att.kind) {
    E2eEnvelope.ATTACHMENT_KIND_AUDIO -> runCatching {
        val fileName = voiceMessageFileName(att.transferId, att.mime)
        withContext(Dispatchers.IO) { voiceMessageFile(context, fileName).writeBytes(data) }
        saveRemoteAudioMessage(
            context = context,
            sessionCode = threadSessionCode,
            uuid = att.transferId,
            fileName = fileName,
            audioDurationMillis = att.durationMs.toLong().takeIf { it > 0 }
        )
        context.getString(R.string.internet_message_voice_received)
    }.onFailure { Log.w(RECEIVE_TAG, "Failed to persist received voice message", it) }.getOrNull()

    E2eEnvelope.ATTACHMENT_KIND_IMAGE -> runCatching {
        val fileName = ImageFileUtils.fileNameFor(att.transferId, att.mime)
        withContext(Dispatchers.IO) { imageMessageFile(context, fileName).writeBytes(data) }
        // Derive dimensions from the bytes (the sender doesn't ship them separately); no eager
        // thumbnail — the chat UI renders a preview from the full image, matching the Bluetooth path.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        saveRemoteImageMessage(
            context = context,
            sessionCode = threadSessionCode,
            uuid = att.transferId,
            fileName = fileName,
            thumbnailName = null,
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
            mimeType = att.mime
        )
        context.getString(R.string.internet_message_image_received)
    }.onFailure { Log.w(RECEIVE_TAG, "Failed to persist received image", it) }.getOrNull()

    E2eEnvelope.ATTACHMENT_KIND_FILE -> runCatching {
        // Store the document like the Bluetooth receiver does: an app-local copy keyed by the
        // transfer id. The bubble itself comes from the paired CC_FILE metadata TEXT message (same
        // uuid) which the sender ships through the normal text path; tap-to-open resolves this copy.
        withContext(Dispatchers.IO) {
            persistSharedDocumentLocalCopy(
                context = context,
                uuid = att.transferId,
                displayName = att.name.ifBlank { att.transferId },
                bytes = data
            )
        }
        // No display text: the CC_FILE message carries the visible bubble + notification.
        ""
    }.onFailure { Log.w(RECEIVE_TAG, "Failed to persist received document", it) }.getOrNull()

    else -> {
        Log.i(RECEIVE_TAG, "Attachment kind=${att.kind} not persisted; dropping")
        null
    }
}

/**
 * Realtime downlink for internet messages while the app is running: a Firestore listener on
 * `messages` where recipientUid == me. This complements the FCM push path (which handles background
 * / closed-app delivery) — the listener guarantees delivery whenever both peers are online, without
 * depending on FCM reaching every device. Same E2E decrypt + anti-impersonation as the push path.
 */
object InternetMessageReceiver {
    private const val TAG = "InternetMsgListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registration: ListenerRegistration? = null
    private var listeningUid: String? = null

    @Synchronized
    fun start(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (listeningUid == uid && registration != null) return
        stop()
        listeningUid = uid
        val appContext = context.applicationContext
        registration = FirebaseFirestore.getInstance()
            .collection("messages")
            .whereEqualTo("recipientUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val d = change.document
                    val conversationId = d.getString("conversationId").orEmpty()
                    val senderUid = d.getString("senderUid").orEmpty()
                    val messageId = d.getString("messageId") ?: d.id
                    val ciphertext = d.getString("ciphertext").orEmpty()
                    if (conversationId.isBlank() || senderUid.isBlank() || ciphertext.isBlank()) continue
                    val payload: IncomingCiphertext = if (d.getLong("v") == 2L) {
                        IncomingCiphertext.V2(
                            ctypeLabel = d.getString("ctype").orEmpty(),
                            ciphertextBase64 = ciphertext
                        )
                    } else {
                        IncomingCiphertext.V1(
                            SealedMessage(
                                ephemeralPublicKey = d.getString("ephemeralPubKey").orEmpty(),
                                nonce = d.getString("nonce").orEmpty(),
                                ciphertext = ciphertext,
                                alg = d.getString("alg").orEmpty().ifBlank { E2eEnvelope.ALG }
                            )
                        )
                    }
                    scope.launch {
                        runCatching {
                            receiveInternetMessage(appContext, conversationId, senderUid, uid, messageId, payload)
                        }.onSuccess { received ->
                            // Whoever wins InternetMessageDedup owns the message — and when this
                            // listener won, the message was filed SILENTLY: the returned value
                            // (which exists precisely so the caller can notify) was discarded, so
                            // whether a notification appeared was literally a race-condition coin
                            // flip against the FCM path. Null = duplicate (FCM owns it) or rejected.
                            if (received != null &&
                                !ActiveChatTracker.isSessionActive(received.sessionCode)
                            ) {
                                postInternetMessageNotification(
                                    appContext,
                                    conversationId,
                                    received.senderName,
                                    received.display,
                                    sessionCode = received.sessionCode,
                                    senderUid = senderUid
                                )
                            }
                        }.onFailure { Log.w(TAG, "Failed to handle listener message", it) }
                    }
                }
            }
    }

    @Synchronized
    fun stop() {
        registration?.remove()
        registration = null
        listeningUid = null
    }
}
