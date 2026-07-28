package com.auralis.crisisconnect.service

import android.util.Base64
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.service.p2p.call.P2pCallProtocol
import org.json.JSONObject

/**
 * Wire codec for voice calls over the rescue link (SOS service 0xCC00).
 *
 * The rescue link carries calls on a dedicated characteristic pair so that older builds — whose
 * chat parsers fall back to rendering unknown payloads as plain text — never see call traffic:
 *
 *  - 0xCC40 CALL_IO_IN  (rescuer → victim, WRITE / WRITE_NO_RESPONSE)
 *  - 0xCC41 CALL_IO_OUT (victim → rescuer, NOTIFY)
 *
 * Two single-ATT-write packet families share the pair, distinguished by the first byte:
 *
 *  - Audio: standard [P2pCallProtocol] frames (magic 0xCA) — same codec, keys derived from the
 *    rescue session key instead of a contact AES key.
 *  - Signaling: `[0xCB][version=1][AES-GCM(sessionKey, call-signal JSON)]` where the cipher is
 *    the session-key AES-GCM already used by rescue chat ([AesCipherHelper]), so both platforms'
 *    existing primitives interop.
 *
 * The ephemeral ECDH session key both ends already hold doubles as the call key: the
 * [P2pCallProtocol] HKDF directional derivation (fresh per-direction salts in offer/accept)
 * keeps the seq-based nonces collision-free exactly as it does for contact calls.
 */
object RescueCallLinkCodec {

    const val CHAR_CALL_IO_IN_NUMBER = 0xCC40
    const val CHAR_CALL_IO_OUT_NUMBER = 0xCC41

    const val SIGNAL_MAGIC: Byte = 0xCB.toByte()
    const val SIGNAL_VERSION: Byte = 0x01
    const val LINK_NAME_VICTIM = "rescue-victim"
    const val LINK_NAME_RESCUER = "rescue-client"

    fun isSignalPacket(packet: ByteArray): Boolean {
        return packet.size > 2 && packet[0] == SIGNAL_MAGIC && packet[1] == SIGNAL_VERSION
    }

    fun encodeSignalPacket(sessionKey: ByteArray, payload: JSONObject): ByteArray? {
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        val encrypted = runCatching { AesCipherHelper.encrypt(sessionKey, plain) }.getOrNull()
            ?: return null
        return byteArrayOf(SIGNAL_MAGIC, SIGNAL_VERSION) + encrypted
    }

    fun decodeSignalPacket(sessionKey: ByteArray, packet: ByteArray): JSONObject? {
        if (!isSignalPacket(packet)) return null
        val cipher = packet.copyOfRange(2, packet.size)
        val plain = runCatching { AesCipherHelper.decrypt(sessionKey, cipher) }.getOrNull()
            ?: return null
        return runCatching { JSONObject(plain.toString(Charsets.UTF_8)) }.getOrNull()
    }

    /**
     * A stand-in [Contact] for the call controller: rescue sessions have no paired AES key, so
     * the ECDH session key rides in the aesKey slot. The controller only reads
     * sessionCode / name / aesKey off it.
     */
    fun syntheticContact(sessionCode: String, displayName: String?, sessionKey: ByteArray): Contact {
        return Contact(
            name = displayName?.takeIf { it.isNotBlank() } ?: sessionCode,
            aesKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP),
            sessionCode = sessionCode
        )
    }
}
