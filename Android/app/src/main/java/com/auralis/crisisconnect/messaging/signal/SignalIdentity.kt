package com.auralis.crisisconnect.messaging.signal

import android.content.Context
import android.util.Base64
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Read access to the device's Signal-protocol identity, so the store can depend on this narrow
 * surface (and tests can supply a fixed keypair without a Context).
 */
interface SignalIdentityProvider {
    fun identityKeyPair(): IdentityKeyPair
    fun registrationId(): Int
}

/**
 * The device's long-term Signal-protocol (v3) identity: a Curve25519 [IdentityKeyPair] plus a
 * registration id. libsignal needs the raw private identity bytes in memory to run the ratchet, so —
 * unlike the v2 [com.auralis.crisisconnect.messaging.MessagingIdentity], which can keep its P-256
 * key non-exportable in the hardware Keystore — this key can't be hardware-bound. It is instead
 * persisted inside a [KeystoreBackedPreferences] blob (AES-GCM under a hardware Keystore key), so at
 * rest it is wrapped by a hardware key even though it must be unwrapped to use.
 *
 * This is the deliberate FS trade-off recorded in the plan: we accept losing hardware
 * non-exportability of the identity key in exchange for forward secrecy + post-compromise security.
 * The v2 P-256 identity is kept untouched for the v2 fallback and legacy safety numbers.
 */
class SignalIdentity(context: Context) : SignalIdentityProvider {

    private val store = KeystoreBackedPreferences(
        context = context.applicationContext,
        prefName = PREFS_NAME,
        keyAlias = STORE_KEY_ALIAS,
    )

    @Synchronized
    override fun identityKeyPair(): IdentityKeyPair {
        store.getString(KEY_IDENTITY)?.let { encoded ->
            runCatching {
                IdentityKeyPair(Base64.decode(encoded, Base64.NO_WRAP))
            }.getOrNull()?.let { return it }
        }
        return generateAndPersist().first
    }

    @Synchronized
    override fun registrationId(): Int {
        val existing = store.getInt(KEY_REGISTRATION_ID, -1)
        if (existing in 1..0x3fff) return existing
        return generateAndPersist().second
    }

    /** True once an identity has been generated (so bootstrap can decide whether to publish). */
    fun isInitialized(): Boolean =
        store.contains(KEY_IDENTITY) && store.getInt(KEY_REGISTRATION_ID, -1) in 1..0x3fff

    private fun generateAndPersist(): Pair<IdentityKeyPair, Int> {
        val existingKey = store.getString(KEY_IDENTITY)
        val existingReg = store.getInt(KEY_REGISTRATION_ID, -1)
        val keyPair = existingKey
            ?.let { runCatching { IdentityKeyPair(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }
            ?: IdentityKeyPair.generate().also {
                store.putString(KEY_IDENTITY, Base64.encodeToString(it.serialize(), Base64.NO_WRAP))
            }
        val registrationId = existingReg.takeIf { it in 1..0x3fff }
            ?: KeyHelper.generateRegistrationId(false).also { store.putInt(KEY_REGISTRATION_ID, it) }
        return keyPair to registrationId
    }

    private companion object {
        private const val PREFS_NAME = "crisisconnect_signal_identity"
        private const val STORE_KEY_ALIAS = "cc_signal_identity_v1"
        private const val KEY_IDENTITY = "signal_identity_keypair"
        private const val KEY_REGISTRATION_ID = "signal_registration_id"
    }
}
