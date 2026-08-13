package com.auralis.crisisconnect.messaging

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AuthorityMlsChatMessage(
    val id: String,
    val senderUid: String,
    val payload: AuthorityMlsMessagePayload,
    val pending: Boolean = false,
)

/** Same MLS application ciphertext carried over the nearby transport (never plaintext). */
data class AuthorityMlsOfflineEnvelope(
    val conversationId: String,
    val message: AuthorityMlsCiphertextMessage,
)

object AuthorityMlsOfflineEnvelopeCodec {
    const val PREFIX = "CC_AMLS2:"
    private const val MAX_ENCODED_BYTES = 256 * 1024
    private val CONVERSATION = Regex("^am2_[A-Za-z0-9_-]{43}$")
    private val MESSAGE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]{1,900000}$")

    fun encode(conversationId: String, message: AuthorityMlsCiphertextMessage): String {
        require(CONVERSATION.matches(conversationId) && MESSAGE_ID.matches(message.messageId) &&
            BASE64URL.matches(message.ciphertext) && validIdentityFields(message)) {
            "Authority MLS offline envelope is malformed."
        }
        val json = JSONObject()
            .put("v", 2)
            .put("c", conversationId)
            .put("m", message.messageId)
            .put("u", message.senderUid)
            .put("d", message.senderDeviceId)
            .put("k", message.senderCredential)
            .put("x", message.ciphertext)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_ENCODED_BYTES) { "Authority MLS offline envelope is too large." }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json)
    }

    fun decode(value: String): AuthorityMlsOfflineEnvelope? = runCatching {
        if (!value.startsWith(PREFIX) || value.length > PREFIX.length + MAX_ENCODED_BYTES * 2) return null
        val encoded = value.removePrefix(PREFIX)
        if (!BASE64URL.matches(encoded)) return null
        val bytes = Base64.getUrlDecoder().decode(encoded)
        if (bytes.isEmpty() || bytes.size > MAX_ENCODED_BYTES) return null
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        if (root.optInt("v") != 2) return null
        val conversationId = root.optString("c")
        val messageId = root.optString("m")
        val ciphertext = root.optString("x")
        val senderUid = root.optString("u")
        val senderDeviceId = root.optString("d")
        val senderCredential = root.optString("k")
        if (!CONVERSATION.matches(conversationId) || !MESSAGE_ID.matches(messageId) ||
            !BASE64URL.matches(ciphertext) || !validIdentityFields(
                AuthorityMlsCiphertextMessage(
                    messageId,
                    senderUid,
                    senderDeviceId,
                    senderCredential,
                    ciphertext,
                ),
            )) return null
        AuthorityMlsOfflineEnvelope(
            conversationId,
            AuthorityMlsCiphertextMessage(
                messageId,
                senderUid,
                senderDeviceId,
                senderCredential,
                ciphertext,
            ),
        )
    }.getOrNull()

    private fun validIdentityFields(message: AuthorityMlsCiphertextMessage): Boolean =
        message.senderUid.isNotBlank() && message.senderUid.length <= 256 &&
            message.senderDeviceId.isNotBlank() && message.senderDeviceId.length <= 128 &&
            message.senderCredential.isNotBlank() && message.senderCredential.length <= 512
}

/** Fail-closed 1:1 AuthorityChat adapter over the persistent MLS session. */
class AuthorityMlsChatChannel private constructor(
    private val appContext: Context,
    private val selfUid: String,
    private val peerUid: String,
    private val session: AuthorityMlsConversationSession,
    private val transport: AuthorityMlsTransport = AuthorityMlsTransport(),
) {
    private var active = false
    private var preparationWakeRequestedAt = 0L
    private val flushMutex = Mutex()

    val conversationId: String get() = session.conversationId

    suspend fun refreshPreparation(): AuthorityMlsPreparation = session.refreshPreparation()

    suspend fun approveDeviceSet(uid: String, expectedFingerprint: String): AuthorityMlsPreparation =
        session.approveDeviceSet(uid, expectedFingerprint)

    suspend fun isReadyToSend(): Boolean = session.isReadyToSend()

    /** Best-effort wake, throttled locally just like the browser implementation. */
    suspend fun requestPeerPreparation() {
        val now = System.currentTimeMillis()
        if (now - preparationWakeRequestedAt < 30_000L) return
        preparationWakeRequestedAt = now
        try {
            InternetMessagingClient().requestAuthorityMlsPreparation(conversationId)
        } catch (error: Throwable) {
            // Keep the local cooldown even when the server rejects the request. A persistent auth
            // or rollout mismatch must not turn the 400 ms convergence loop into callable spam.
            throw error
        }
    }

    suspend fun activate(
        onMessage: suspend (AuthorityMlsChatMessage) -> Unit,
        onSecurityError: (Throwable) -> Unit = {},
    ) {
        if (active) return
        pendingAttachmentDescriptors().values.flatten().distinctBy { it.path }.let {
            ChannelAttachments.ensureAuthorityMlsAttachmentsUploaded(appContext, it)
        }
        session.activate(
            onApplication = { application -> onMessage(decode(application)) },
            onSecurityError = onSecurityError,
        )
        active = true
    }

    /** Accepts a message into the Keystore-sealed local outbox without waiting for an online peer. */
    suspend fun stage(payload: AuthorityMlsMessagePayload): AuthorityMlsChatMessage {
        require(payload.recipientUid == peerUid) { "Authority MLS recipient changed." }
        val plaintext = AuthorityMlsMessagePayloadCodec.encode(payload)
        val messageId = randomMessageId()
        AuthorityMlsPendingApplicationStore.stage(
            appContext,
            selfUid,
            conversationId,
            AuthorityMlsStagedApplication(messageId, session.localCredential, plaintext),
        )
        return AuthorityMlsChatMessage(messageId, selfUid, payload, pending = true)
    }

    /** Flushes staged plaintext only after this leaf and both accounts are in the verified MLS group. */
    suspend fun flushPending(): List<AuthorityMlsChatMessage> = flushMutex.withLock {
        if (!active || !session.isReadyToSend()) return@withLock emptyList()
        val delivered = ArrayList<AuthorityMlsChatMessage>()
        for (pending in AuthorityMlsPendingApplicationStore.load(appContext, selfUid, conversationId)) {
            val sender = AuthorityMlsCredential.decode(pending.senderCredential)
                ?: throw SecurityException("Authority MLS protected outbox sender is malformed.")
            require(sender.accountUid == selfUid) { "Authority MLS protected outbox sender changed." }
            val payload = AuthorityMlsMessagePayloadCodec.decode(pending.plaintext)
            require(payload.recipientUid == peerUid) { "Authority MLS protected outbox recipient changed." }
            ChannelAttachments.ensureAuthorityMlsAttachmentsUploaded(appContext, payload.attachments)
            if (!transport.isCiphertextPublished(conversationId, pending.messageId, selfUid)) {
                session.sendApplication(pending.plaintext, pending.messageId)
            }
            AuthorityMlsPendingApplicationStore.remove(
                appContext,
                selfUid,
                conversationId,
                pending.messageId,
            )
            delivered += AuthorityMlsChatMessage(pending.messageId, selfUid, payload, pending = false)
        }
        delivered
    }

    suspend fun send(payload: AuthorityMlsMessagePayload): AuthorityMlsChatMessage {
        val staged = stage(payload)
        val delivered = flushPending().any { it.id == staged.id }
        return staged.copy(pending = !delivered)
    }

    /** Builds the nearby packet from the same durable sender generation later published online. */
    suspend fun offlineEnvelope(messageId: String): String {
        val pending = AuthorityMlsPendingApplicationStore.load(appContext, selfUid, conversationId)
            .firstOrNull { it.messageId == messageId }
            ?: throw SecurityException("Authority MLS protected outbox entry is unavailable.")
        val sender = AuthorityMlsCredential.decode(pending.senderCredential)
            ?: throw SecurityException("Authority MLS protected outbox sender is malformed.")
        require(sender.accountUid == selfUid) { "Authority MLS protected outbox sender changed." }
        val ciphertext = session.queueApplicationForOfflineRelay(pending.plaintext, pending.messageId)
        return AuthorityMlsOfflineEnvelopeCodec.encode(conversationId, ciphertext)
    }

    suspend fun pendingOfflineEnvelopes(): List<Pair<String, String>> =
        AuthorityMlsPendingApplicationStore.load(appContext, selfUid, conversationId).map { pending ->
            pending.messageId to offlineEnvelope(pending.messageId)
        }

    suspend fun pendingAttachmentDescriptors(): Map<String, List<ChannelAttachment>> =
        AuthorityMlsPendingApplicationStore.load(appContext, selfUid, conversationId)
            .associate { pending ->
                pending.messageId to AuthorityMlsMessagePayloadCodec.decode(pending.plaintext).attachments
            }

    /** Opens and delivers a nearby packet through the same MLS state used by the cloud listener. */
    suspend fun acceptOfflineEnvelope(encoded: String) {
        val envelope = AuthorityMlsOfflineEnvelopeCodec.decode(encoded)
            ?: throw SecurityException("Authority MLS offline envelope is malformed.")
        require(envelope.conversationId == conversationId && envelope.message.senderUid == peerUid) {
            "Authority MLS offline envelope is bound to another conversation."
        }
        session.handleOfflineApplicationMessage(envelope.message, peerUid)
    }

    suspend fun close() {
        active = false
        session.close()
    }

    private fun decode(application: AuthorityMlsPendingReceivedApplication): AuthorityMlsChatMessage {
        val sender = AuthorityMlsCredential.decode(application.senderCredential)?.accountUid
            ?: throw SecurityException("Authority MLS sender credential is malformed.")
        require(sender == peerUid) { "Authority MLS sender is outside the bound conversation." }
        val payload = AuthorityMlsMessagePayloadCodec.decode(application.plaintext)
        require(payload.recipientUid == selfUid) { "Authority MLS recipient is outside the bound conversation." }
        return AuthorityMlsChatMessage(application.messageId, sender, payload)
    }

    companion object {
        suspend fun prepare(
            context: Context,
            selfUid: String,
            peerUid: String,
            scopeType: AuthorityMlsScopeType,
            channelId: String,
            deviceLabel: String,
            foreground: Boolean = true,
        ): AuthorityMlsChatChannel {
            val binding = AuthorityMlsBinding(scopeType, channelId, listOf(selfUid, peerUid))
            if (foreground) {
                // A visible thread must preempt its background prewarmer. Both deliberately use the
                // same exclusive ratchet lease, so letting a stalled background fetch keep ownership
                // would make every foreground action appear permanently disabled.
                AuthorityMlsPrewarmer.yieldToForeground(selfUid, binding)
            }
            val session = AuthorityMlsConversationSession.prepare(
                context = context,
                accountUid = selfUid,
                binding = binding,
                deviceLabel = deviceLabel,
            )
            return AuthorityMlsChatChannel(context.applicationContext, selfUid, peerUid, session)
        }

        private fun randomMessageId(): String {
            val bytes = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
            return "m_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
