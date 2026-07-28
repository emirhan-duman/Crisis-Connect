package com.auralis.crisisconnect.messaging

import android.util.Base64
import com.auralis.crisisconnect.security.Crypto
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey

/**
 * The decrypted content of a message. [templateCode] 0 means "free text in [text]"; a
 * non-zero code refers to a predefined crisis phrase (e.g. "I'm safe", "Need water") that
 * the receiver renders from a shared dictionary — this is the bandwidth win, since a whole
 * sentence collapses to a single byte. The template code lives INSIDE the ciphertext, so the
 * server never learns which phrase was sent.
 */
data class MessageContent(
    val templateCode: Int,
    val text: String,
    /**
     * When non-null, this message carries a binary attachment chunk (voice clip / image / file)
     * instead of (or alongside) text. Large media is split across several sealed messages sharing a
     * [MessageAttachment.transferId]; the receiver reassembles by transfer id.
     */
    val attachment: MessageAttachment? = null
)

/**
 * One chunk of a binary attachment carried inside the E2E envelope. [bytes] is THIS chunk;
 * [chunkIndex] / [chunkCount] locate it within the whole transfer and [totalSize] is the full
 * (reassembled) byte count. The header is a deterministic binary layout so iOS/web reproduce it
 * exactly.
 */
data class MessageAttachment(
    val kind: Int, // E2eEnvelope.ATTACHMENT_KIND_AUDIO / IMAGE / FILE
    val mime: String,
    val name: String,
    val transferId: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val totalSize: Int,
    val durationMs: Int, // audio duration; 0 when not applicable
    val bytes: ByteArray
)

/**
 * The server-visible, end-to-end-encrypted envelope. The server routes these bytes but can
 * never decrypt them (it holds no private key). Mirrors the backend `RelayEnvelope` fields.
 */
data class SealedMessage(
    val ephemeralPublicKey: String, // base64 SPKI of the sender's per-message ephemeral key
    val nonce: String, // base64, 12-byte AES-GCM nonce
    val ciphertext: String, // base64 AES-256-GCM output (ciphertext || tag)
    val alg: String = E2eEnvelope.ALG
)

/**
 * Authenticated sealed-sender E2E. The message key is derived from TWO ECDH agreements combined
 * through HKDF:
 *   - ephemeral-static  = ECDH(ephemeral, recipientIdentity)  → confidentiality + per-message
 *     forward secrecy on the sender side (a fresh ephemeral key per message).
 *   - static-static     = ECDH(senderIdentity, recipientIdentity) → SENDER AUTHENTICATION: only
 *     the holder of the sender's long-term private key can produce a key the recipient accepts
 *     under that sender's identity, so a malicious server (which knows only public keys) can no
 *     longer forge a message as someone else. This is what makes the safety number meaningful.
 *
 * All primitives reuse the app's existing [Crypto] (P-256 ECDH → HKDF-SHA256 → AES-256-GCM), which
 * iOS (CryptoKit) and web (WebCrypto) must reproduce exactly: ikm = es || ss, in that order.
 *
 * The sender/recipient/conversation identifiers are also bound as AES-GCM associated data, so a
 * ciphertext can't be replayed into a different conversation.
 *
 * NOTE: this is a lightweight interim construction (1-RTT-free authenticated ECIES). It gives
 * sender authentication and sender-side forward secrecy but NOT full ratcheting forward secrecy /
 * post-compromise security — that is the separate libsignal (X3DH + Double Ratchet) milestone.
 */
object E2eEnvelope {
    const val ALG = "ECDH-P256(es+ss)+HKDF-SHA256+AES-256-GCM"
    private const val INFO_PREFIX = "crisisconnect:e2e:v2:"

    const val ATTACHMENT_KIND_AUDIO = 1
    const val ATTACHMENT_KIND_IMAGE = 2
    const val ATTACHMENT_KIND_FILE = 3

    /**
     * Seals [content] to [recipientPublicKey], authenticating it as [senderUid]. [deriveSenderStaticSecret]
     * computes ECDH(senderIdentityPrivate, recipientPublicKey); passing a lambda lets the sender's
     * identity key live in the hardware Keystore (see [MessagingIdentity.deriveSharedSecret]) without
     * ever exposing the raw private key.
     */
    fun seal(
        content: MessageContent,
        recipientPublicKey: PublicKey,
        deriveSenderStaticSecret: (recipientPublicKey: PublicKey) -> ByteArray,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ): SealedMessage {
        val ephemeral = Crypto.generateEphemeralEcKeyPair()
        val es = Crypto.deriveSharedSecret(ephemeral.private, recipientPublicKey)
        val ss = deriveSenderStaticSecret(recipientPublicKey)
        val key = Crypto.hkdfSha256(
            ikm = es + ss,
            salt = null,
            info = info(conversationId),
            outputLength = 32
        )
        val nonce = Crypto.randomBytes(12)
        val ciphertext = Crypto.aesGcmEncrypt(
            key = key,
            nonce = nonce,
            plaintext = MessageContentCodec.encode(content),
            associatedData = aad(senderUid, recipientUid, conversationId)
        )
        return SealedMessage(
            ephemeralPublicKey = Base64.encodeToString(ephemeral.public.encoded, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    /** Convenience overload for a software sender private key (unit tests / non-hardware keys). */
    fun seal(
        content: MessageContent,
        recipientPublicKey: PublicKey,
        senderPrivateKey: PrivateKey,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ): SealedMessage = seal(
        content = content,
        recipientPublicKey = recipientPublicKey,
        deriveSenderStaticSecret = { recipientPub ->
            Crypto.deriveSharedSecret(senderPrivateKey, recipientPub)
        },
        senderUid = senderUid,
        recipientUid = recipientUid,
        conversationId = conversationId
    )

    /**
     * Opens a sealed message. [deriveSharedSecret] computes ECDH(recipientIdentityPrivate, pub) for a
     * given public key (kept in the hardware Keystore, see [MessagingIdentity.deriveSharedSecret]); it
     * is invoked twice — once with the ephemeral key (es) and once with [senderStaticPublicKey] (ss).
     * The message decrypts only if it was authored with the private key matching [senderStaticPublicKey],
     * so the caller MUST pass the sender's PINNED/verified identity key (safety-number / TOFU protected).
     */
    fun open(
        sealed: SealedMessage,
        deriveSharedSecret: (peerPublicKey: PublicKey) -> ByteArray,
        senderStaticPublicKey: PublicKey,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ): MessageContent {
        val ephemeralPublicKey = MessagingKeyCodec.decodePublicKey(sealed.ephemeralPublicKey)
        val es = deriveSharedSecret(ephemeralPublicKey)
        val ss = deriveSharedSecret(senderStaticPublicKey)
        val key = Crypto.hkdfSha256(
            ikm = es + ss,
            salt = null,
            info = info(conversationId),
            outputLength = 32
        )
        val plaintext = Crypto.aesGcmDecrypt(
            key = key,
            nonce = Base64.decode(sealed.nonce, Base64.NO_WRAP),
            ciphertextAndTag = Base64.decode(sealed.ciphertext, Base64.NO_WRAP),
            associatedData = aad(senderUid, recipientUid, conversationId)
        )
        return MessageContentCodec.decode(plaintext)
    }

    /** Convenience overload for a software recipient private key (unit tests / non-hardware keys). */
    fun open(
        sealed: SealedMessage,
        recipientPrivateKey: PrivateKey,
        senderStaticPublicKey: PublicKey,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ): MessageContent = open(
        sealed = sealed,
        deriveSharedSecret = { peerPublicKey ->
            Crypto.deriveSharedSecret(recipientPrivateKey, peerPublicKey)
        },
        senderStaticPublicKey = senderStaticPublicKey,
        senderUid = senderUid,
        recipientUid = recipientUid,
        conversationId = conversationId
    )

    private fun info(conversationId: String): ByteArray =
        (INFO_PREFIX + conversationId).toByteArray(StandardCharsets.UTF_8)

    private fun aad(senderUid: String, recipientUid: String, conversationId: String): ByteArray =
        "$senderUid|$recipientUid|$conversationId".toByteArray(StandardCharsets.UTF_8)
}
