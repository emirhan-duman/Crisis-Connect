package com.auralis.crisisconnect.service.p2p.call

import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.service.p2p.P2pBleProtocol
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Wire codec for voice calls carried over the P2P GATT chat link (0xCD00).
 *
 * Two packet families share the link with regular chat traffic:
 *
 * 1. **Signaling** rides the existing encrypted chat envelope as new `call_*` chat kinds
 *    (see [P2pBleProtocol.CHAT_KIND_CALL_OFFER] etc.). Old app versions ignore unknown kinds
 *    silently on both platforms, so mixed-version peers are unaffected.
 *
 * 2. **Audio** uses a compact binary frame instead of the JSON envelope so that a bundle of
 *    Opus frames plus AES-GCM tag fits a single ATT write at the preferred MTU (247):
 *
 *    ```
 *    [0xCA][version=1][callTag 4B][seq u32 BE][AES-GCM(txKey, bundle, nonce, aad=header)]
 *    bundle = ([u16 BE len][opus frame]) * framesPerPacket
 *    nonce  = 4 zero bytes ‖ u64 BE seq          (never transmitted)
 *    aad    = the 10-byte header
 *    ```
 *
 *    Each direction encrypts with its own key derived from the contact AES key, so the
 *    seq-based nonce can never collide between the two talkers:
 *
 *    ```
 *    txKey(caller→callee) = HKDF-SHA256(contactKey, salt="dcs-p2pcall-v1", info="a2b|"+callId+"|"+saltHex)
 *    txKey(callee→caller) = HKDF-SHA256(contactKey, salt="dcs-p2pcall-v1", info="b2a|"+callId+"|"+saltHex)
 *    ```
 *
 *    The 16-byte `saltHex` for each direction is a fresh random value chosen by that
 *    direction's sender (caller's salt rides the offer, callee's rides the accept). This means
 *    an honest sender's own audio key is unique per call even if a malicious peer replays a
 *    `callId`, closing an AES-GCM nonce-reuse (two-time-pad) gap.
 *
 * The iOS implementation (`Services/Connectivity/Call/P2pCallProtocol.swift`) must stay
 * bit-compatible; both are pinned by the same golden fixtures in their unit tests.
 */
object P2pCallProtocol {

    const val FRAME_MAGIC: Byte = 0xCA.toByte()
    const val FRAME_VERSION: Byte = 0x01
    const val CALL_TAG_BYTES = 4
    const val FRAME_HEADER_BYTES = 1 + 1 + CALL_TAG_BYTES + 4
    const val GCM_TAG_BYTES = 16
    const val MAX_SEQ = 0xFFFF_FFFFL
    const val MAX_BUNDLE_FRAMES = 8
    const val MAX_BUNDLE_BYTES = 4_096
    const val SALT_BYTES = 16

    private const val HKDF_SALT = "dcs-p2pcall-v1"
    private const val HKDF_INFO_CALLER_TO_CALLEE = "a2b|"
    private const val HKDF_INFO_CALLEE_TO_CALLER = "b2a|"
    private const val GCM_NONCE_BYTES = 12
    private const val MAX_CALL_ID_LENGTH = 128

    val SIGNAL_KINDS: Set<String> = setOf(
        P2pBleProtocol.CHAT_KIND_CALL_OFFER,
        P2pBleProtocol.CHAT_KIND_CALL_RING,
        P2pBleProtocol.CHAT_KIND_CALL_ACCEPT,
        P2pBleProtocol.CHAT_KIND_CALL_REJECT,
        P2pBleProtocol.CHAT_KIND_CALL_BUSY,
        P2pBleProtocol.CHAT_KIND_CALL_END,
        P2pBleProtocol.CHAT_KIND_CALL_CFG,
        P2pBleProtocol.CHAT_KIND_CALL_CFG_ACK
    )

    data class CallSignal(
        val kind: String,
        val callId: String,
        val senderName: String? = null,
        val timestampMillis: Long? = null,
        val sampleRateHz: Int? = null,
        val frameMs: Int? = null,
        val framesPerPacket: Int? = null,
        val bitrateBps: Int? = null,
        val reason: String? = null,
        val ok: Boolean? = null,
        // Lowercase hex of this sender's fresh 16-byte audio-key salt (offer + accept only).
        val saltHex: String? = null
    )

    data class DecodedAudioFrame(
        val seq: Long,
        val bundle: ByteArray
    )

    fun isCallSignalKind(kind: String?): Boolean = kind != null && kind in SIGNAL_KINDS

    /** Builds the inner chat-envelope JSON payload for a call signal. */
    fun encodeSignal(signal: CallSignal): JSONObject {
        require(isCallSignalKind(signal.kind)) { "Unknown call signal kind=${signal.kind}" }
        require(signal.callId.isNotBlank() && signal.callId.length <= MAX_CALL_ID_LENGTH) {
            "Invalid callId"
        }
        return JSONObject().apply {
            put("kind", signal.kind)
            put("callId", signal.callId)
            signal.senderName?.takeIf { it.isNotBlank() }?.let { put("senderName", it) }
            signal.timestampMillis?.let { put("ts", it) }
            signal.sampleRateHz?.let { put("sampleRate", it) }
            signal.frameMs?.let { put("frameMs", it) }
            signal.framesPerPacket?.let { put("framesPerPacket", it) }
            signal.bitrateBps?.let { put("bitrate", it) }
            signal.reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
            signal.ok?.let { put("ok", it) }
            signal.saltHex?.takeIf { it.isNotBlank() }?.let { put("salt", it) }
        }
    }

    /** Fresh random per-direction audio-key salt as lowercase hex. */
    fun randomSaltHex(): String {
        return Crypto.randomBytes(SALT_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** Parses a call signal from a decrypted inner chat payload; null if not a valid signal. */
    fun parseSignal(payload: JSONObject): CallSignal? {
        val kind = payload.optString("kind").takeIf { isCallSignalKind(it) } ?: return null
        val callId = payload.optString("callId").trim()
        if (callId.isEmpty() || callId.length > MAX_CALL_ID_LENGTH) {
            return null
        }
        return CallSignal(
            kind = kind,
            callId = callId,
            senderName = payload.optString("senderName").takeIf { it.isNotBlank() },
            timestampMillis = if (payload.has("ts")) payload.optLong("ts") else null,
            sampleRateHz = if (payload.has("sampleRate")) payload.optInt("sampleRate") else null,
            frameMs = if (payload.has("frameMs")) payload.optInt("frameMs") else null,
            framesPerPacket = if (payload.has("framesPerPacket")) {
                payload.optInt("framesPerPacket")
            } else {
                null
            },
            bitrateBps = if (payload.has("bitrate")) payload.optInt("bitrate") else null,
            reason = payload.optString("reason").takeIf { it.isNotBlank() },
            ok = if (payload.has("ok")) payload.optBoolean("ok") else null,
            saltHex = payload.optString("salt").takeIf { it.isNotBlank() }
        )
    }

    /** First four bytes of SHA-256 over the callId; public routing tag inside audio frames. */
    fun deriveCallTag(callId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(callId.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, CALL_TAG_BYTES)
    }

    /**
     * HKDF-SHA256 (RFC 5869, single expand block) over the per-contact AES key.
     * The caller of the call encrypts with the `a2b` key, the callee with `b2a`.
     *
     * [directionSaltHex] is the fresh random salt chosen by that direction's sender; folding it
     * into the info string makes the derived key unique per call-instance even if a peer reuses
     * a [callId], preventing AES-GCM nonce reuse.
     */
    fun deriveDirectionalKey(
        contactKeyBytes: ByteArray,
        callId: String,
        callerToCallee: Boolean,
        directionSaltHex: String
    ): ByteArray {
        require(contactKeyBytes.size == 16 || contactKeyBytes.size == 32) {
            "Contact AES key must be 16 or 32 bytes, was ${contactKeyBytes.size}"
        }
        require(directionSaltHex.isNotBlank()) { "Directional salt is required" }
        val prefix = if (callerToCallee) HKDF_INFO_CALLER_TO_CALLEE else HKDF_INFO_CALLEE_TO_CALLER
        val prk = Crypto.hmacSha256(HKDF_SALT.toByteArray(Charsets.UTF_8), contactKeyBytes)
        val info = (prefix + callId + "|" + directionSaltHex).toByteArray(Charsets.UTF_8)
        return Crypto.hmacSha256(prk, info + byteArrayOf(0x01))
    }

    /**
     * Distinguishes binary call-audio frames from JSON chat envelopes after transport unwrap.
     * JSON always starts with '{' (0x7B), so matching on the magic byte alone is unambiguous
     * and keeps future frame versions out of the chat parser as well.
     */
    fun isCallAudioFrame(payload: ByteArray): Boolean {
        return payload.isNotEmpty() && payload[0] == FRAME_MAGIC
    }

    /** Concatenates Opus frames as ([u16 BE length][frame])*. */
    fun packFrameBundle(frames: List<ByteArray>): ByteArray {
        require(frames.isNotEmpty() && frames.size <= MAX_BUNDLE_FRAMES) {
            "Bundle must contain 1..$MAX_BUNDLE_FRAMES frames"
        }
        val totalBytes = frames.sumOf { 2 + it.size }
        require(totalBytes <= MAX_BUNDLE_BYTES) { "Bundle too large: $totalBytes bytes" }
        val buffer = ByteBuffer.allocate(totalBytes)
        frames.forEach { frame ->
            require(frame.isNotEmpty() && frame.size <= 0xFFFF) { "Invalid frame size ${frame.size}" }
            buffer.putShort(frame.size.toShort())
            buffer.put(frame)
        }
        return buffer.array()
    }

    /** Reverses [packFrameBundle]; null on any structural violation. */
    fun unpackFrameBundle(bundle: ByteArray): List<ByteArray>? {
        if (bundle.isEmpty() || bundle.size > MAX_BUNDLE_BYTES) {
            return null
        }
        val frames = ArrayList<ByteArray>(2)
        var offset = 0
        while (offset < bundle.size) {
            if (offset + 2 > bundle.size || frames.size >= MAX_BUNDLE_FRAMES) {
                return null
            }
            val length = ((bundle[offset].toInt() and 0xFF) shl 8) or
                (bundle[offset + 1].toInt() and 0xFF)
            offset += 2
            if (length == 0 || offset + length > bundle.size) {
                return null
            }
            frames.add(bundle.copyOfRange(offset, offset + length))
            offset += length
        }
        return frames.takeIf { it.isNotEmpty() }
    }

    fun encodeAudioFrame(
        txKeyBytes: ByteArray,
        callTag: ByteArray,
        seq: Long,
        bundle: ByteArray
    ): ByteArray {
        require(callTag.size == CALL_TAG_BYTES) { "callTag must be $CALL_TAG_BYTES bytes" }
        require(seq in 0..MAX_SEQ) { "seq out of u32 range: $seq" }
        require(bundle.isNotEmpty() && bundle.size <= MAX_BUNDLE_BYTES) { "Invalid bundle size" }
        val header = ByteBuffer.allocate(FRAME_HEADER_BYTES)
            .put(FRAME_MAGIC)
            .put(FRAME_VERSION)
            .put(callTag)
            .putInt(seq.toInt())
            .array()
        val cipherText = gcm(Cipher.ENCRYPT_MODE, txKeyBytes, nonceForSeq(seq), header, bundle)
        return header + cipherText
    }

    /**
     * Authenticated decode of a binary audio frame. Returns null on any mismatch — wrong call,
     * wrong direction key, truncation, or tampering — so callers can drop packets silently.
     */
    fun decodeAudioFrame(
        rxKeyBytes: ByteArray,
        expectedCallTag: ByteArray,
        packet: ByteArray
    ): DecodedAudioFrame? {
        if (expectedCallTag.size != CALL_TAG_BYTES) {
            return null
        }
        if (packet.size < FRAME_HEADER_BYTES + GCM_TAG_BYTES + 1) {
            return null
        }
        if (packet[0] != FRAME_MAGIC || packet[1] != FRAME_VERSION) {
            return null
        }
        for (index in 0 until CALL_TAG_BYTES) {
            if (packet[2 + index] != expectedCallTag[index]) {
                return null
            }
        }
        val seq = ByteBuffer.wrap(packet, 2 + CALL_TAG_BYTES, 4).int.toLong() and MAX_SEQ
        val header = packet.copyOfRange(0, FRAME_HEADER_BYTES)
        val cipherText = packet.copyOfRange(FRAME_HEADER_BYTES, packet.size)
        val bundle = runCatching {
            gcm(Cipher.DECRYPT_MODE, rxKeyBytes, nonceForSeq(seq), header, cipherText)
        }.getOrNull() ?: return null
        return DecodedAudioFrame(seq = seq, bundle = bundle)
    }

    private fun nonceForSeq(seq: Long): ByteArray {
        return ByteBuffer.allocate(GCM_NONCE_BYTES)
            .putInt(0)
            .putLong(seq)
            .array()
    }

    private fun gcm(
        mode: Int,
        keyBytes: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }
}
