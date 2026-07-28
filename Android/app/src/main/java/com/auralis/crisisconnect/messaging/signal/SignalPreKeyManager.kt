package com.auralis.crisisconnect.messaging.signal

import android.content.Context
import android.util.Base64
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Generates, locally persists, and publishes the device's Signal-protocol prekeys (FS-2). Every key
 * it hands to the backend is first stored in the [AndroidSignalProtocolStore] so that when a peer
 * consumes it and opens a session against us, we can decrypt (the backend copy is public-only).
 *
 * Ownership of ids: monotonically increasing counters kept in a Keystore-encrypted pref so ids never
 * repeat across top-ups. The signed prekey and last-resort Kyber prekey are rotated in full each
 * time we publish meta (≈ on every bootstrap / rotation); one-time pools are topped up when the
 * server reports them low.
 */
class SignalPreKeyManager(
    context: Context,
    private val store: AndroidSignalProtocolStore,
    private val identity: SignalIdentity,
    private val client: InternetMessagingClient = InternetMessagingClient(),
) {
    private val appContext = context.applicationContext
    private val counters = KeystoreBackedPreferences(appContext, PREFS_NAME, STORE_KEY_ALIAS)

    /**
     * Ensure the device has published Signal prekeys and keeps its one-time pools stocked. Called
     * from bootstrap. Republishes meta (identity/signed/last-resort Kyber) every time — cheap and
     * doubles as signed-prekey rotation — then tops up the one-time pools if the server says they're
     * below [LOW_WATERMARK].
     */
    suspend fun ensurePublished() {
        val identityKeyPair = identity.identityKeyPair()

        // Rotate the signed + last-resort Kyber prekeys and store them locally.
        val signedPreKey = generateSignedPreKey()
        store.storeSignedPreKey(signedPreKey.id, signedPreKey)
        val lastResortKyber = generateKyberPreKey()
        store.storeKyberPreKey(lastResortKyber.id, lastResortKyber)

        // How many one-time keys to publish now: fill to TARGET_POOL if the server is low (or empty).
        val inventory = runCatching { client.checkSignalPreKeys() }.getOrNull()
        val needEc = topUpCount(inventory?.ecCount, inventory?.published)
        val needKyber = topUpCount(inventory?.kyberCount, inventory?.published)

        val oneTimeEc = List(needEc) { generateOneTimePreKey() }
        oneTimeEc.forEach { store.storePreKey(it.id, it) }
        val oneTimeKyber = List(needKyber) { generateKyberPreKey() }
        oneTimeKyber.forEach { store.storeKyberPreKey(it.id, it) }

        client.publishSignalPreKeys(
            registrationId = identity.registrationId(),
            identityKeyBase64 = b64(identityKeyPair.publicKey.serialize()),
            signedPreKey = signedPreKey.toWire(),
            lastResortKyberPreKey = lastResortKyber.toWire(),
            preKeys = oneTimeEc.map { it.toWire() },
            kyberPreKeys = oneTimeKyber.map { it.toWire() },
        )
    }

    private fun topUpCount(serverCount: Int?, published: Boolean?): Int {
        // Server never published (published==false / null) → fill from scratch. Otherwise top up only
        // when below the low watermark, back to the target.
        val current = if (published == true) (serverCount ?: 0) else 0
        return if (current >= LOW_WATERMARK) 0 else (TARGET_POOL - current).coerceAtLeast(0)
    }

    private fun generateSignedPreKey(): SignedPreKeyRecord {
        val keyPair = ECKeyPair.generate()
        val signature = identity.identityKeyPair().privateKey.calculateSignature(keyPair.publicKey.serialize())
        return SignedPreKeyRecord(nextId(KEY_SIGNED_ID), System.currentTimeMillis(), keyPair, signature)
    }

    private fun generateKyberPreKey(): KyberPreKeyRecord {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.identityKeyPair().privateKey.calculateSignature(keyPair.publicKey.serialize())
        return KyberPreKeyRecord(nextId(KEY_KYBER_ID), System.currentTimeMillis(), keyPair, signature)
    }

    private fun generateOneTimePreKey(): PreKeyRecord =
        PreKeyRecord(nextId(KEY_PREKEY_ID), ECKeyPair.generate())

    /** Monotonic id in [1, 0xFFFFFF]; wraps to 1 to stay inside libsignal's Medium range. */
    @Synchronized
    private fun nextId(counterKey: String): Int {
        val next = counters.getInt(counterKey, 0) + 1
        val wrapped = if (next in 1..MAX_KEY_ID) next else 1
        counters.putInt(counterKey, wrapped)
        return wrapped
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun PreKeyRecord.toWire(): InternetMessagingClient.PreKeyUpload =
        InternetMessagingClient.PreKeyUpload(id, b64(keyPair.publicKey.serialize()), null)

    private fun SignedPreKeyRecord.toWire(): InternetMessagingClient.PreKeyUpload =
        InternetMessagingClient.PreKeyUpload(id, b64(keyPair.publicKey.serialize()), b64(signature))

    private fun KyberPreKeyRecord.toWire(): InternetMessagingClient.PreKeyUpload =
        InternetMessagingClient.PreKeyUpload(id, b64(keyPair.publicKey.serialize()), b64(signature))

    companion object {
        private const val PREFS_NAME = "crisisconnect_signal_prekey_counters"
        private const val STORE_KEY_ALIAS = "cc_signal_prekey_counters_v1"
        private const val KEY_PREKEY_ID = "next_prekey_id"
        private const val KEY_SIGNED_ID = "next_signed_prekey_id"
        private const val KEY_KYBER_ID = "next_kyber_prekey_id"
        private const val MAX_KEY_ID = 0xFFFFFF
        private const val TARGET_POOL = 100
        private const val LOW_WATERMARK = 20

        fun create(context: Context): SignalPreKeyManager {
            val identity = SignalIdentity(context)
            val store = AndroidSignalProtocolStore(
                dao = AppDatabase.getInstance(context).signalStoreDao(),
                identity = identity,
            )
            return SignalPreKeyManager(context, store, identity)
        }
    }
}
