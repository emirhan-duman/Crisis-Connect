package com.auralis.crisisconnect.messaging.signal

import android.util.Base64
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import com.auralis.crisisconnect.messaging.MessageContent
import com.auralis.crisisconnect.messaging.MessageContentCodec
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignalProtocolStore

/**
 * The v3 crypto layer: turns a [MessageContent] into a Signal-protocol ciphertext and back, using a
 * per-peer Double Ratchet session over the [AndroidSignalProtocolStore]. It is the drop-in analogue
 * of [com.auralis.crisisconnect.messaging.E2eEnvelope] for the forward-secret transport — the INNER
 * plaintext is the same [MessageContentCodec] bytes, so every template/attachment works unchanged;
 * only the encryption changes.
 *
 * A peer is addressed as `SignalProtocolAddress(peerUid, 1)` (single logical device per account).
 * The first outbound message on a fresh session is a `PreKeySignalMessage` (carries the X3DH/PQXDH
 * handshake); once the peer has replied, subsequent messages are plain `SignalMessage`s. Decrypting
 * a PreKeySignalMessage auto-establishes the inbound session by consuming our own stored one-time
 * prekeys. All libsignal calls are blocking → never call on the main thread.
 */
class SignalMessageTransport(private val store: SignalProtocolStore) {

    /** Wire form of a Signal ciphertext: the CiphertextMessage type + its serialized bytes (base64). */
    data class Wire(val type: Int, val ciphertextBase64: String) {
        val isPreKey: Boolean get() = type == CiphertextMessage.PREKEY_TYPE

        companion object {
            const val TYPE_PREKEY = "prekey"
            const val TYPE_SIGNAL = "signal"
        }
    }

    fun typeLabel(type: Int): String =
        if (type == CiphertextMessage.PREKEY_TYPE) Wire.TYPE_PREKEY else Wire.TYPE_SIGNAL

    fun typeFromLabel(label: String): Int =
        if (label == Wire.TYPE_PREKEY) CiphertextMessage.PREKEY_TYPE else CiphertextMessage.WHISPER_TYPE

    /** Whether we already hold a session for [peerUid] (so a bundle fetch can be skipped). */
    fun hasSession(peerUid: String): Boolean = store.containsSession(address(peerUid))

    /**
     * Establish an OUTBOUND session with [peerUid] from a fetched PQXDH bundle (X3DH + Kyber). Safe
     * to call when a session already exists — libsignal replaces it with the new bundle's keys.
     */
    fun establishOutboundSession(peerUid: String, bundle: InternetMessagingClient.SignalBundle) {
        val preKeyPublic: ECPublicKey? =
            bundle.preKeyBase64?.let { ECPublicKey(decode(it)) }
        val preKeyId = bundle.preKeyId ?: -1
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            preKeyId,
            preKeyPublic,
            bundle.signedPreKeyId,
            ECPublicKey(decode(bundle.signedPreKeyBase64)),
            decode(bundle.signedPreKeySignatureBase64),
            IdentityKey(decode(bundle.identityKeyBase64)),
            bundle.kyberPreKeyId,
            KEMPublicKey(decode(bundle.kyberPreKeyBase64)),
            decode(bundle.kyberPreKeySignatureBase64),
        )
        SessionBuilder(store, address(peerUid)).process(preKeyBundle)
    }

    /** Encrypt [content] for [peerUid]. Requires an established session (out or in). */
    fun encrypt(peerUid: String, content: MessageContent): Wire {
        val cipher = SessionCipher(store, address(peerUid))
        val message = cipher.encrypt(MessageContentCodec.encode(content))
        return Wire(message.type, Base64.encodeToString(message.serialize(), Base64.NO_WRAP))
    }

    /**
     * Decrypt an inbound Signal message. A `prekey`-typed wire auto-establishes the inbound session
     * (consuming our stored one-time prekeys); a `signal`-typed wire advances an existing ratchet.
     * Throws (fail-closed) on a forged/duplicate/undecryptable message — the caller drops it.
     */
    fun decrypt(peerUid: String, typeLabel: String, ciphertextBase64: String): MessageContent {
        val cipher = SessionCipher(store, address(peerUid))
        val bytes = decode(ciphertextBase64)
        val plaintext = if (typeLabel == Wire.TYPE_PREKEY) {
            cipher.decrypt(PreKeySignalMessage(bytes))
        } else {
            cipher.decrypt(SignalMessage(bytes))
        }
        return MessageContentCodec.decode(plaintext)
    }

    /** Our own long-term Signal identity public key (for the safety-number fingerprint). */
    fun localIdentityKey(): org.signal.libsignal.protocol.IdentityKey = store.identityKeyPair.publicKey

    /** The peer's pinned Signal identity key, present once a v3 session has been established. */
    fun peerIdentityKey(peerUid: String): org.signal.libsignal.protocol.IdentityKey? =
        store.getIdentity(address(peerUid))

    private fun address(peerUid: String) = SignalProtocolAddress(peerUid, DEVICE_ID)

    private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)

    companion object {
        /** One logical device per Crisis Connect account (no multi-device today). */
        const val DEVICE_ID = 1
    }
}
