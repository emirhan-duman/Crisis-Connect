package com.auralis.crisisconnect.messaging.signal

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import com.auralis.crisisconnect.messaging.MessageContent

/**
 * Decides — and drives — whether a message to/from a peer uses the forward-secret v3 (Signal) path
 * or the legacy v2 envelope, and owns the anti-downgrade pin.
 *
 * Send: [ensureOutbound] returns true once a v3 session is ready (existing, or freshly established
 * from a fetched PQXDH bundle). If the peer has never published Signal prekeys it returns false and
 * remembers that (a short negative-probe throttle) so we don't burn a bundle fetch — and the daily
 * fetch cap — on every message to a v2-only peer.
 *
 * Downgrade pin: [hasV3Session] is the pin signal. Once a v3 session exists with a peer, every
 * message to them MUST be v3 (callers gate on [ensureOutbound]), and on RECEIVE a v2 message from a
 * peer we hold a v3 session with is refused (a stripped-v3 downgrade attempt) — see the receive path.
 *
 * All calls hit libsignal's blocking store → never on the main thread.
 */
class SignalSessionGate private constructor(
    context: Context,
    private val transport: SignalMessageTransport,
    private val client: InternetMessagingClient,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasV3Session(peerUid: String): Boolean =
        peerUid.isNotBlank() && transport.hasSession(peerUid)

    /**
     * Ensure we can send v3 to [peerUid]. [forceEstablish] true (durable messages) will fetch a
     * bundle and establish a session if none exists; false (ephemeral pulses like typing) only uses
     * an already-established session and never drives establishment.
     */
    suspend fun ensureOutbound(peerUid: String, forceEstablish: Boolean): Boolean {
        if (peerUid.isBlank()) return false
        if (transport.hasSession(peerUid)) return true
        if (!forceEstablish) return false
        if (isNegativeProbeFresh(peerUid)) return false

        val bundle = runCatching { client.fetchSignalPreKeyBundle(peerUid) }.getOrElse {
            Log.w(TAG, "prekey bundle fetch failed for $peerUid", it)
            return false
        }
        if (bundle == null) {
            // Peer has no Signal prekeys published → v2-only for now; throttle re-probing.
            markNegativeProbe(peerUid)
            return false
        }
        return runCatching {
            synchronized(CRYPTO_LOCK) {
                // Re-check under the lock: a concurrent sender (e.g. a call's ICE-signal burst) may
                // have established while we fetched the bundle. Re-processing the bundle would RESET
                // the ratchet and reuse message counters, so only the first establisher wins.
                if (!transport.hasSession(peerUid)) {
                    transport.establishOutboundSession(peerUid, bundle)
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "failed to establish v3 session with $peerUid", it)
            false
        }
    }

    fun encrypt(peerUid: String, content: MessageContent): SignalMessageTransport.Wire =
        synchronized(CRYPTO_LOCK) { transport.encrypt(peerUid, content) }

    fun typeLabel(type: Int): String = transport.typeLabel(type)

    fun decrypt(peerUid: String, ctypeLabel: String, ciphertextBase64: String): MessageContent =
        synchronized(CRYPTO_LOCK) { transport.decrypt(peerUid, ctypeLabel, ciphertextBase64) }

    /**
     * The forward-secret (v3) safety number for [peerUid], or null if we hold no v3 identity for
     * them yet (no session established → fall back to the v2 P-256 safety number). Blocking (DB +
     * a 5200-iteration fingerprint) → call off the main thread.
     */
    fun safetyNumber(localUid: String, peerUid: String): String? {
        if (localUid.isBlank() || peerUid.isBlank()) return null
        val remote = transport.peerIdentityKey(peerUid) ?: return null
        return runCatching {
            SignalSafetyNumber.compute(localUid, transport.localIdentityKey(), peerUid, remote)
        }.getOrNull()
    }

    private fun isNegativeProbeFresh(peerUid: String): Boolean {
        val at = prefs.getLong(peerUid, 0L)
        return at > 0L && System.currentTimeMillis() - at < NEGATIVE_PROBE_TTL_MS
    }

    private fun markNegativeProbe(peerUid: String) {
        prefs.edit().putLong(peerUid, System.currentTimeMillis()).apply()
    }

    /** A peer just proved v3 support (we received/established a session) → clear any negative mark. */
    fun clearNegativeProbe(peerUid: String) {
        if (prefs.contains(peerUid)) prefs.edit().remove(peerUid).apply()
    }

    companion object {
        private const val TAG = "SignalSessionGate"
        private const val PREFS_NAME = "crisisconnect_signal_probe"

        // Ratchet state is persisted in a shared Room DB, and every gate instance points at the same
        // rows — so a racing FCM push + Firestore listener (or two messages from one peer at once)
        // would read-modify-write the same session concurrently and corrupt the ratchet. Serialize
        // all ratchet-mutating ops process-wide. Internet messaging is low-throughput and each op is
        // sub-millisecond, so a single global monitor is ample. Callers are always off the main thread.
        private val CRYPTO_LOCK = Any()
        private const val NEGATIVE_PROBE_TTL_MS = 6L * 60L * 60L * 1000L // re-probe a v2-only peer every 6h

        fun create(context: Context, client: InternetMessagingClient = InternetMessagingClient()): SignalSessionGate {
            val store = AndroidSignalProtocolStore(
                dao = AppDatabase.getInstance(context).signalStoreDao(),
                identity = SignalIdentity(context),
            )
            return SignalSessionGate(context, SignalMessageTransport(store), client)
        }
    }
}
