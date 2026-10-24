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
    private val context: Context,
    private val transport: SignalMessageTransport,
    private val client: InternetMessagingClient,
) {
    private val bindings = SignalPeerBinding(context)
    private fun scope(peerUid: String): Triple<String, String, String>? {
        val owner = client.currentUid()?.takeIf { it.isNotBlank() } ?: return null
        val contact = com.auralis.crisisconnect.data.getContactByPeerUid(context, peerUid) ?: return null
        val key = contact.peerPublicKey.takeIf { it.isNotBlank() } ?: return null
        return Triple(owner, peerUid, key)
    }
    private fun expectedIdentity(peerUid: String): String? {
        val (owner, peer, key) = scope(peerUid) ?: return null
        check(!bindings.changed(owner, peer, key)) { "Signal identity changed; remove and re-pair this contact" }
        return bindings.read(owner, peer, key)
    }
    fun identityChangePending(peerUid: String): Boolean = scope(peerUid)?.let { (owner, peer, key) ->
        bindings.changed(owner, peer, key)
    } == true
    fun hasIdentityBinding(peerUid: String): Boolean = scope(peerUid)?.let { (owner, peer, key) -> bindings.read(owner, peer, key) != null } == true
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasV3Session(peerUid: String): Boolean =
        peerUid.isNotBlank() && transport.hasSession(peerUid) &&
            expectedIdentity(peerUid)?.let { expected ->
                transport.peerIdentityKey(peerUid)?.serialize()?.let {
                    android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) == expected
                }
            } == true

    fun peerUpgradeRequired(peerUid: String): Boolean {
        if (hasIdentityBinding(peerUid)) return false
        val scope = scope(peerUid) ?: return false
        return bindings.migrationPending(scope.first, scope.second, scope.third) ||
            AppDatabase.getInstance(context).signalStoreDao().getSessionsForName(peerUid).isNotEmpty()
    }
    fun markPeerUpgradeRequired(peerUid: String): Boolean {
        val scope = scope(peerUid) ?: return false
        if (!bindings.markMigrationPending(scope.first, scope.second, scope.third)) return false
        com.auralis.crisisconnect.data.getContactByPeerUid(context, peerUid)?.let {
            com.auralis.crisisconnect.data.saveContact(context, it)
        }
        return true
    }

    fun localIdentityBase64(): String = android.util.Base64.encodeToString(transport.localIdentityKey().serialize(), android.util.Base64.NO_WRAP)

    fun acceptBinding(peerUid: String, p256: String, signal: String): Boolean = synchronized(CRYPTO_LOCK) {
        val (owner, peer, pinned) = scope(peerUid) ?: return@synchronized false
        if (pinned != p256) return@synchronized false
        val previous = bindings.read(owner, peer, pinned)
        // Validate serialized identity before persisting any pin.
        org.signal.libsignal.protocol.IdentityKey(android.util.Base64.decode(signal, android.util.Base64.NO_WRAP))
        if (!bindings.accept(owner, peer, pinned, signal)) {
            com.auralis.crisisconnect.data.getContactByPeerUid(context, peer)?.let {
                com.auralis.crisisconnect.data.saveContact(context, it.copy(peerKeyChanged = true))
            }
            return@synchronized false
        }
        if (previous == null) clearProtocolPeer(peer)
        true
    }

    private fun clearProtocolPeer(peer: String) {
        val dao = AppDatabase.getInstance(context).signalStoreDao()
        dao.deleteSessionsForName(peer)
        dao.deleteIdentity(org.signal.libsignal.protocol.SignalProtocolAddress(peer, 1).toString())
    }

    fun forgetPeer(peer: String, p256: String) = synchronized(CRYPTO_LOCK) {
        client.currentUid()?.let { bindings.reset(it, peer, p256) }
        clearProtocolPeer(peer)
    }

    fun claimBindingAnnouncement(peer: String, p256: String): Boolean =
        client.currentUid()?.let { bindings.claimAnnouncement(it, peer, p256, System.currentTimeMillis()) } == true

    /**
     * Ensure we can send v3 to [peerUid]. [forceEstablish] true (durable messages) will fetch a
     * bundle and establish a session if none exists; false (ephemeral pulses like typing) only uses
     * an already-established session and never drives establishment.
     */
    suspend fun ensureOutbound(peerUid: String, forceEstablish: Boolean): Boolean {
        if (peerUid.isBlank()) return false
        val expected = expectedIdentity(peerUid) ?: return false
        if (hasV3Session(peerUid)) return true
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
        check(bundle.identityKeyBase64 == expected) { "Prekey bundle does not match the authenticated Signal identity" }
        return runCatching {
            synchronized(CRYPTO_LOCK) {
                // Re-check under the lock: a concurrent sender (e.g. a call's ICE-signal burst) may
                // have established while we fetched the bundle. Re-processing the bundle would RESET
                // the ratchet and reuse message counters, so only the first establisher wins.
                if (!hasV3Session(peerUid)) {
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
        synchronized(CRYPTO_LOCK) { check(hasV3Session(peerUid)); transport.encrypt(peerUid, content) }

    fun typeLabel(type: Int): String = transport.typeLabel(type)

    fun decrypt(peerUid: String, ctypeLabel: String, ciphertextBase64: String): MessageContent =
        synchronized(CRYPTO_LOCK) {
            val expected = expectedIdentity(peerUid) ?: run {
                markPeerUpgradeRequired(peerUid)
                error("Peer update is required to authenticate this Signal identity")
            }
            val identity = if (ctypeLabel == "prekey") {
                org.signal.libsignal.protocol.message.PreKeySignalMessage(android.util.Base64.decode(ciphertextBase64, android.util.Base64.NO_WRAP)).identityKey
            } else transport.peerIdentityKey(peerUid)
            check(identity?.serialize()?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } == expected) {
                "Signal identity mismatch; remove and re-pair this contact"
            }
            transport.decrypt(peerUid, ctypeLabel, ciphertextBase64)
        }

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
