package com.auralis.crisisconnect.messaging.signal

import android.util.Log
import com.auralis.crisisconnect.data.signal.SignalIdentityEntity
import com.auralis.crisisconnect.data.signal.SignalKyberPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalSenderKeyEntity
import com.auralis.crisisconnect.data.signal.SignalSessionEntity
import com.auralis.crisisconnect.data.signal.SignalSignedPreKeyEntity
import com.auralis.crisisconnect.data.signal.SignalStoreDao
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * A [SignalProtocolStore] backed by the SQLCipher-encrypted app database (see
 * [com.auralis.crisisconnect.data.signal.SignalStoreDao]). All record bytes are libsignal
 * `serialize()` output; identity/registration come from [SignalIdentity].
 *
 * libsignal calls these methods synchronously from whatever thread drives encrypt/decrypt (the FCM
 * receive path or the send path), so the DAO calls are synchronous too — never call this on the main
 * thread. First use must be authenticated by SignalSessionGate against the pinned P256
 * identity. Changed identities require an explicit trusted re-pairing.
 */
class AndroidSignalProtocolStore(
    private val dao: SignalStoreDao,
    private val identity: SignalIdentityProvider,
) : SignalProtocolStore {

    // ---- IdentityKeyStore ----

    override fun getIdentityKeyPair(): IdentityKeyPair = identity.identityKeyPair()

    override fun getLocalRegistrationId(): Int = identity.registrationId()

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val existing = dao.getIdentity(address.toString())?.let { IdentityKey(it.identityKey) }
        check(existing == null || existing == identityKey) { "Signal identity changed; explicit re-pairing required" }
        dao.putIdentity(SignalIdentityEntity(address.toString(), identityKey.serialize()))
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val trusted = dao.getIdentity(address.toString())?.let { IdentityKey(it.identityKey) }
        if (trusted != null && trusted != identityKey) {
            // Keep the authenticated pin until a trusted re-pairing clears it.
            return false
        }
        return true
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        dao.getIdentity(address.toString())?.let { IdentityKey(it.identityKey) }

    // ---- SessionStore ----

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        dao.getSession(address.toString())?.let { SessionRecord(it.record) }

    override fun loadExistingSessions(
        addresses: List<SignalProtocolAddress>,
    ): List<SessionRecord> = addresses.map { address ->
        val entity = dao.getSession(address.toString())
            ?: throw NoSessionException("No session for $address")
        SessionRecord(entity.record)
    }

    override fun getSubDeviceSessions(name: String): List<Int> =
        dao.getSessionsForName(name).map { it.deviceId }.filter { it != 1 }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        dao.putSession(
            SignalSessionEntity(
                address = address.toString(),
                name = address.name,
                deviceId = address.deviceId,
                record = record.serialize(),
            )
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        dao.containsSession(address.toString())

    override fun deleteSession(address: SignalProtocolAddress) =
        dao.deleteSession(address.toString())

    override fun deleteAllSessions(name: String) = dao.deleteSessionsForName(name)

    // ---- PreKeyStore ----

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = dao.getPreKey(preKeyId) ?: throw InvalidKeyIdException("No prekey $preKeyId")
        return PreKeyRecord(entity.record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        dao.putPreKey(SignalPreKeyEntity(preKeyId, record.serialize()))

    override fun containsPreKey(preKeyId: Int): Boolean = dao.containsPreKey(preKeyId)

    override fun removePreKey(preKeyId: Int) = dao.deletePreKey(preKeyId)

    // ---- SignedPreKeyStore ----

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val entity = dao.getSignedPreKey(signedPreKeyId)
            ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")
        return SignedPreKeyRecord(entity.record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        dao.getAllSignedPreKeys().map { SignedPreKeyRecord(it.record) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        dao.putSignedPreKey(SignalSignedPreKeyEntity(signedPreKeyId, record.serialize()))

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        dao.containsSignedPreKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) = dao.deleteSignedPreKey(signedPreKeyId)

    // ---- KyberPreKeyStore ----

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val entity = dao.getKyberPreKey(kyberPreKeyId)
            ?: throw InvalidKeyIdException("No kyber prekey $kyberPreKeyId")
        return KyberPreKeyRecord(entity.record)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        dao.getAllKyberPreKeys().map { KyberPreKeyRecord(it.record) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        dao.putKyberPreKey(SignalKyberPreKeyEntity(kyberPreKeyId, record.serialize(), used = false))

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        dao.containsKyberPreKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        // We keep consumed one-time Kyber prekeys (marked used) rather than deleting, matching the
        // reference InMemory store; last-resort keys are simply never marked and stay reusable.
        dao.markKyberPreKeyUsed(kyberPreKeyId)
    }

    // ---- SenderKeyStore (group messaging; required by the interface, unused today) ----

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        dao.putSenderKey(
            SignalSenderKeyEntity(sender.toString(), distributionId.toString(), record.serialize())
        )
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? =
        dao.getSenderKey(sender.toString(), distributionId.toString())
            ?.let { SenderKeyRecord(it.record) }

    private companion object {
        private const val TAG = "SignalProtocolStore"
    }
}
