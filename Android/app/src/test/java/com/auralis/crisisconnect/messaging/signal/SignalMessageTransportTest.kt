package com.auralis.crisisconnect.messaging.signal

import android.util.Base64
import com.auralis.crisisconnect.data.signal.SignalIdentityEntity
import com.auralis.crisisconnect.data.signal.SignalKyberPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalSenderKeyEntity
import com.auralis.crisisconnect.data.signal.SignalSessionEntity
import com.auralis.crisisconnect.data.signal.SignalSignedPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalStoreDao
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import com.auralis.crisisconnect.messaging.MessageAttachment
import com.auralis.crisisconnect.messaging.MessageContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * FS-3 core: [SignalMessageTransport] must round-trip a [MessageContent] — text AND attachment —
 * end to end through two independent stores, so the whole template/attachment layer rides the
 * forward-secret v3 transport unchanged. Robolectric so android.util.Base64 is real.
 */
@RunWith(RobolectricTestRunner::class)
class SignalMessageTransportTest {

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
        override fun markKyberPreKeyUsed(keyId: Int) { kyber[keyId]?.let { kyber[keyId] = it.copy(used = true) } }
        override fun putSenderKey(entity: SignalSenderKeyEntity) { senderKeys["${entity.address}|${entity.distributionId}"] = entity }
        override fun getSenderKey(address: String, distributionId: String) = senderKeys["$address|$distributionId"]
        override fun clearPreKeys() { preKeys.clear() }
        override fun clearSignedPreKeys() { signed.clear() }
        override fun clearKyberPreKeys() { kyber.clear() }
        override fun clearSessions() { sessions.clear() }
        override fun clearIdentities() { identities.clear() }
    }

    private fun store(): AndroidSignalProtocolStore {
        val kp = IdentityKeyPair.generate()
        val reg = KeyHelper.generateRegistrationId(false)
        return AndroidSignalProtocolStore(FakeDao(), object : SignalIdentityProvider {
            override fun identityKeyPair() = kp
            override fun registrationId() = reg
        })
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Generate + store Bob's prekeys, return the wire bundle Alice would fetch from the backend. */
    private fun publishBundle(bob: AndroidSignalProtocolStore): InternetMessagingClient.SignalBundle {
        val id = bob.identityKeyPair
        val preKeyPair = ECKeyPair.generate()
        val signedPair = ECKeyPair.generate()
        val signedSig = id.privateKey.calculateSignature(signedPair.publicKey.serialize())
        val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = id.privateKey.calculateSignature(kyberPair.publicKey.serialize())

        bob.storePreKey(555, PreKeyRecord(555, preKeyPair))
        bob.storeSignedPreKey(9, SignedPreKeyRecord(9, System.currentTimeMillis(), signedPair, signedSig))
        bob.storeKyberPreKey(3, KyberPreKeyRecord(3, System.currentTimeMillis(), kyberPair, kyberSig))

        return InternetMessagingClient.SignalBundle(
            registrationId = bob.localRegistrationId,
            deviceId = 1,
            identityKeyBase64 = b64(id.publicKey.serialize()),
            signedPreKeyId = 9,
            signedPreKeyBase64 = b64(signedPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = b64(signedSig),
            preKeyId = 555,
            preKeyBase64 = b64(preKeyPair.publicKey.serialize()),
            kyberPreKeyId = 3,
            kyberPreKeyBase64 = b64(kyberPair.publicKey.serialize()),
            kyberPreKeySignatureBase64 = b64(kyberSig),
        )
    }

    @Test
    fun roundTripsTextThroughRatchet() {
        val aliceStore = store()
        val bobStore = store()
        val alice = SignalMessageTransport(aliceStore)
        val bob = SignalMessageTransport(bobStore)
        val bundle = publishBundle(bobStore)

        assertFalse(alice.hasSession("bob"))
        alice.establishOutboundSession("bob", bundle)
        assertTrue(alice.hasSession("bob"))

        // First message is a prekey message (carries the handshake).
        val first = alice.encrypt("bob", MessageContent(templateCode = 0, text = "Enkaz altındayım"))
        assertTrue(first.isPreKey)
        assertEquals("Enkaz altındayım", bob.decrypt("alice", alice.typeLabel(first.type), first.ciphertextBase64).text)

        // Bob replies → plain signal message; ratchet continues both ways.
        val reply = bob.encrypt("alice", MessageContent(templateCode = 0, text = "Ekip yolda"))
        assertFalse(reply.isPreKey)
        assertEquals("Ekip yolda", alice.decrypt("bob", bob.typeLabel(reply.type), reply.ciphertextBase64).text)

        // A non-zero template code (e.g. SOS alert 202) survives intact.
        val sos = alice.encrypt("bob", MessageContent(templateCode = 202, text = "SOS"))
        val gotSos = bob.decrypt("alice", alice.typeLabel(sos.type), sos.ciphertextBase64)
        assertEquals(202, gotSos.templateCode)
        assertEquals("SOS", gotSos.text)
    }

    @Test
    fun roundTripsAttachmentChunk() {
        val aliceStore = store()
        val bobStore = store()
        val alice = SignalMessageTransport(aliceStore)
        val bob = SignalMessageTransport(bobStore)
        alice.establishOutboundSession("bob", publishBundle(bobStore))

        val payload = ByteArray(3000) { (it % 251).toByte() }
        val content = MessageContent(
            templateCode = 0,
            text = "",
            attachment = MessageAttachment(
                kind = 2, mime = "image/jpeg", name = "enkaz.jpg", transferId = "tx-1",
                chunkIndex = 1, chunkCount = 4, totalSize = 12000, durationMs = 0, bytes = payload,
            ),
        )
        val wire = alice.encrypt("bob", content)
        val got = bob.decrypt("alice", alice.typeLabel(wire.type), wire.ciphertextBase64)

        val att = got.attachment!!
        assertEquals("image/jpeg", att.mime)
        assertEquals("enkaz.jpg", att.name)
        assertEquals("tx-1", att.transferId)
        assertEquals(1, att.chunkIndex)
        assertEquals(4, att.chunkCount)
        assertEquals(12000, att.totalSize)
        assertArrayEquals(payload, att.bytes)
    }
}
