package com.auralis.crisisconnect.messaging.signal

import com.auralis.crisisconnect.data.signal.SignalIdentityEntity
import com.auralis.crisisconnect.data.signal.SignalKyberPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalSenderKeyEntity
import com.auralis.crisisconnect.data.signal.SignalSessionEntity
import com.auralis.crisisconnect.data.signal.SignalSignedPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalStoreDao
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * FS-2 store proof: an [AndroidSignalProtocolStore] wired to a fake in-memory DAO must behave like a
 * real Signal store — a full PQXDH handshake + ratchet completes when BOTH sides persist through it.
 * This exercises every store method the protocol touches (identity/session/prekey/signed/kyber).
 */
class AndroidSignalProtocolStoreTest {

    /** Minimal in-memory stand-in for the Room DAO (Robolectric-free, pure JVM). */
    private class FakeDao : SignalStoreDao {
        val identities = HashMap<String, SignalIdentityEntity>()
        val sessions = HashMap<String, SignalSessionEntity>()
        val preKeys = HashMap<Int, SignalPreKeyEntity>()
        val signed = HashMap<Int, SignalSignedPreKeyEntity>()
        val kyber = HashMap<Int, SignalKyberPreKeyEntity>()
        val senderKeys = HashMap<String, SignalSenderKeyEntity>()

        override fun putIdentity(entity: SignalIdentityEntity) { identities[entity.address] = entity }
        override fun getIdentity(address: String) = identities[address]
        override fun putSession(entity: SignalSessionEntity) { sessions[entity.address] = entity }
        override fun getSession(address: String) = sessions[address]
        override fun getSessionsForName(name: String) = sessions.values.filter { it.name == name }
        override fun containsSession(address: String) = sessions.containsKey(address)
        override fun deleteSession(address: String) { sessions.remove(address) }
        override fun deleteSessionsForName(name: String) { sessions.values.removeAll { it.name == name } }
        override fun putPreKey(entity: SignalPreKeyEntity) { preKeys[entity.keyId] = entity }
        override fun getPreKey(keyId: Int) = preKeys[keyId]
        override fun containsPreKey(keyId: Int) = preKeys.containsKey(keyId)
        override fun deletePreKey(keyId: Int) { preKeys.remove(keyId) }
        override fun putSignedPreKey(entity: SignalSignedPreKeyEntity) { signed[entity.keyId] = entity }
        override fun getSignedPreKey(keyId: Int) = signed[keyId]
        override fun getAllSignedPreKeys() = signed.values.toList()
        override fun containsSignedPreKey(keyId: Int) = signed.containsKey(keyId)
        override fun deleteSignedPreKey(keyId: Int) { signed.remove(keyId) }
        override fun putKyberPreKey(entity: SignalKyberPreKeyEntity) { kyber[entity.keyId] = entity }
        override fun getKyberPreKey(keyId: Int) = kyber[keyId]
        override fun getAllKyberPreKeys() = kyber.values.toList()
        override fun containsKyberPreKey(keyId: Int) = kyber.containsKey(keyId)
        override fun markKyberPreKeyUsed(keyId: Int) {
            kyber[keyId]?.let { kyber[keyId] = it.copy(used = true) }
        }
        override fun putSenderKey(entity: SignalSenderKeyEntity) {
            senderKeys["${entity.address}|${entity.distributionId}"] = entity
        }
        override fun getSenderKey(address: String, distributionId: String) =
            senderKeys["$address|$distributionId"]
        override fun clearPreKeys() { preKeys.clear() }
        override fun clearSignedPreKeys() { signed.clear() }
        override fun clearKyberPreKeys() { kyber.clear() }
        override fun clearSessions() { sessions.clear() }
        override fun clearIdentities() { identities.clear() }
    }

    /** Fixed keypair identity so the store can run without a Context. */
    private fun fixedIdentity(): SignalIdentityProvider {
        val kp = IdentityKeyPair.generate()
        val reg = KeyHelper.generateRegistrationId(false)
        return object : SignalIdentityProvider {
            override fun identityKeyPair() = kp
            override fun registrationId() = reg
        }
    }

    private fun buildBundle(bobStore: SignalProtocolStore): PreKeyBundle {
        val identity = bobStore.identityKeyPair
        val preKeyPair = ECKeyPair.generate()
        val signedPair = ECKeyPair.generate()
        val signedSig = identity.privateKey.calculateSignature(signedPair.publicKey.serialize())
        val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identity.privateKey.calculateSignature(kyberPair.publicKey.serialize())

        bobStore.storePreKey(111, PreKeyRecord(111, preKeyPair))
        bobStore.storeSignedPreKey(22, SignedPreKeyRecord(22, System.currentTimeMillis(), signedPair, signedSig))
        bobStore.storeKyberPreKey(7, KyberPreKeyRecord(7, System.currentTimeMillis(), kyberPair, kyberSig))

        return PreKeyBundle(
            bobStore.localRegistrationId, 1, 111, preKeyPair.publicKey,
            22, signedPair.publicKey, signedSig,
            identity.publicKey, 7, kyberPair.publicKey, kyberSig,
        )
    }

    @Test
    fun daoBackedStore_completesPqxdhHandshakeAndRatchet() {
        val alice = AndroidSignalProtocolStore(FakeDao(), fixedIdentity())
        val bob = AndroidSignalProtocolStore(FakeDao(), fixedIdentity())
        val bobAddr = SignalProtocolAddress("bob-uid", 1)
        val aliceAddr = SignalProtocolAddress("alice-uid", 1)

        SessionBuilder(alice, bobAddr).process(buildBundle(bob))

        val aliceCipher = SessionCipher(alice, bobAddr)
        val bobCipher = SessionCipher(bob, aliceAddr)

        val first = aliceCipher.encrypt("mahsur".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        assertEquals("mahsur", String(bobCipher.decrypt(PreKeySignalMessage(first.serialize()))))

        val reply = bobCipher.encrypt("yolda".toByteArray())
        assertEquals("yolda", String(aliceCipher.decrypt(SignalMessage(reply.serialize()))))

        // Ratchet a few turns through persisted session state.
        repeat(5) {
            val a = aliceCipher.encrypt("a$it".toByteArray())
            assertEquals("a$it", String(bobCipher.decrypt(SignalMessage(a.serialize()))))
        }

        // The session persisted into the DAO — a fresh cipher over the same store keeps decrypting.
        val bobCipher2 = SessionCipher(bob, aliceAddr)
        val more = aliceCipher.encrypt("devam".toByteArray())
        assertEquals("devam", String(bobCipher2.decrypt(SignalMessage(more.serialize()))))
    }

    @Test
    fun tofuIdentity_trustsFirstAndAcceptsChangedKeyForRepin() {
        // The changed-key path logs via android.util.Log, which is unavailable on the JVM.
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        try {
            val store = AndroidSignalProtocolStore(FakeDao(), fixedIdentity())
            val addr = SignalProtocolAddress("peer-uid", 1)
            val first = IdentityKeyPair.generate().publicKey
            val second = IdentityKeyPair.generate().publicKey

            // Unknown peer is trusted (TOFU), and recorded.
            assertTrue(store.isTrustedIdentity(addr, first, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
            store.saveIdentity(addr, first)
            assertTrue(store.isTrustedIdentity(addr, first, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
            assertArrayEquals(first.serialize(), store.getIdentity(addr)!!.serialize())

            // A changed key (peer reinstalled/reset) is accepted non-blockingly — Signal's default
            // policy. Rejecting here would deadlock the re-handshake forever; libsignal follows up
            // with saveIdentity, which re-pins the new key.
            assertTrue(store.isTrustedIdentity(addr, second, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
            store.saveIdentity(addr, second)
            assertArrayEquals(second.serialize(), store.getIdentity(addr)!!.serialize())
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
        }
    }

    @Test
    fun sessionAndKeyLifecycle_roundTrips() {
        val store = AndroidSignalProtocolStore(FakeDao(), fixedIdentity())
        val addr = SignalProtocolAddress("x-uid", 1)
        assertNull(store.loadSession(addr))
        assertFalse(store.containsSession(addr))

        val kp = ECKeyPair.generate()
        store.storePreKey(5, PreKeyRecord(5, kp))
        assertTrue(store.containsPreKey(5))
        assertArrayEquals(kp.publicKey.serialize(), store.loadPreKey(5).keyPair.publicKey.serialize())
        store.removePreKey(5)
        assertFalse(store.containsPreKey(5))
    }
}
