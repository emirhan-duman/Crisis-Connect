import XCTest
import CryptoKit
import FirebaseAuth
@testable import Crisis_Connect

final class EarlySecurityFindingsTests: XCTestCase {
    func testPrivateQrMetadataRejectsMissingKeyWrongDomainLegacyAndEveryTamperedByte() throws {
        let key = Data(0..<32)
        let plain = Data(#"{"serverDeviceId":"private-id","name":"Ayşe","sessionCode":"ABC123","avatarB64":"photo"}"#.utf8)
        for purpose in [P2pPrivacyWire.Purpose.bootstrap, .control] {
            let wire = try XCTUnwrap(P2pPrivacyWire.seal(plain, key: key, purpose: purpose))
            XCTAssertEqual(P2pPrivacyWire.open(wire, key: key, purpose: purpose), plain)
            XCTAssertFalse(String(decoding: wire, as: UTF8.self).contains("private-id"))
            XCTAssertNil(P2pPrivacyWire.open(wire, key: Data(repeating: 0, count: 32), purpose: purpose))
            XCTAssertNil(P2pPrivacyWire.open(plain, key: key, purpose: purpose))
            XCTAssertNil(P2pPrivacyWire.open(wire, key: key, purpose: purpose == .bootstrap ? .control : .bootstrap))
            for index in wire.indices {
                var changed = wire; changed[index] ^= 1
                XCTAssertNil(P2pPrivacyWire.open(changed, key: key, purpose: purpose))
            }
        }
    }

    func testPrivateControlSupportsDefaultAttMtuAndRejectsOversize() throws {
        let key = Data(0..<32), plain = Data(repeating: 7, count: 10_000)
        let sealed = try XCTUnwrap(P2pPrivacyWire.seal(plain, key: key, purpose: .control))
        let framed = try XCTUnwrap(P2pPrivacyWire.frameControl(sealed))
        let receiver = P2pPrivacyWire.ControlReceiver()
        let chunks = stride(from: 0, to: framed.count, by: 20).map { framed.subdata(in: $0..<min($0 + 20, framed.count)) }
        for chunk in chunks.dropLast() { XCTAssertNil(receiver.receive(chunk)) }
        XCTAssertEqual(receiver.receive(try XCTUnwrap(chunks.last)), sealed)
        XCTAssertNil(receiver.receive(Data([255, 255])))
        XCTAssertNil(receiver.receive(Data(repeating: 0, count: 60_003)))
        XCTAssertEqual(receiver.receive(framed), sealed)
    }

    func testPrivateRoutingAndBootstrapOpenAndroidFixture() throws {
        let key = Data(0..<32)
        let wire = try XCTUnwrap(Data(base64Encoded: "Q1BCMqWLQtiQ7bFX839C6gOZulm6roGYii7syugtnEpXdyZ8QXgl96qpb+TRSVSqF+ziRb9rdd7pUrZCsDMAV8ykvDEhV5Msxij3CJ6PkLDJ+ubGar99mIOLiiWNlZzJ6nfMxhFs+6BjuFGpChlLjJWyZPpNxLiRiqAIkVGAbJqfgHf044OGhQ=="))
        let plain = try XCTUnwrap(Data(base64Encoded: "eyJzZXJ2ZXJEZXZpY2VJZCI6InByaXZhdGUtZGV2aWNlIiwic2Vzc2lvbkNvZGUiOiJTRUNSRVQxMjMiLCJuYW1lIjoiQXnFn2UiLCJhdmF0YXJCNjQiOiJwcml2YXRlLXBob3RvIn0="))
        let alias = "p2p2.000102030405060708090a0b0c0d0e0f.fabb52222948873a1cbfaec8a17d08e6"
        XCTAssertEqual(P2pPrivacyWire.open(wire, key: key, purpose: .bootstrap), plain)
        XCTAssertTrue(P2pPrivacyWire.matches(alias, key: key, packet: wire))
        XCTAssertFalse(P2pPrivacyWire.matches(alias, key: Data(repeating: 0, count: 32), packet: wire))
        XCTAssertFalse(P2pPrivacyWire.matches(alias, key: key, packet: wire + Data([1])))
        XCTAssertFalse(P2pPrivacyWire.matches("device:permanent-id", key: key, packet: wire))
        XCTAssertNotEqual(P2pPrivacyWire.alias(key: key, packet: wire), P2pPrivacyWire.alias(key: key, packet: wire))
    }

    func testPrivateConnectionRequiresFreshOneTimeChallenge() {
        let challenge = P2pPrivacyWire.Challenge(now: 100)
        XCTAssertFalse(challenge.accept(UUID().uuidString, now: 101))
        XCTAssertTrue(challenge.accept(challenge.nonce, now: 101))
        XCTAssertFalse(challenge.accept(challenge.nonce, now: 102))
        let expired = P2pPrivacyWire.Challenge(now: 100)
        XCTAssertFalse(expired.accept(expired.nonce, now: 111))
        XCTAssertNotEqual(expired.nonce, challenge.nonce)
    }

    func testRadioAliasesRotateWithoutChangingDurableRescueIdentity() {
        let key = SymmetricKey(data: Data(0..<32))
        let first = RadioPrivacyIdentity.snapshot(key: key, epoch: 10).broadcastId
        XCTAssertNotEqual(first, RadioPrivacyIdentity.snapshot(key: key, epoch: 11).broadcastId)
        XCTAssertNotEqual(first, RadioPrivacyIdentity.snapshot(key: SymmetricKey(size: .bits256), epoch: 10).broadcastId)
        XCTAssertEqual(RadioPrivacyIdentity.validBroadcastId(first), first)
        XCTAssertNil(RadioPrivacyIdentity.validBroadcastId("cc-"))
    }

    func testMultipeerDiscoveryContainsNoPublicContactIdentityAndRotates() {
        let key = SymmetricKey(data: Data(0..<32))
        let info = ChatPeerCallSecurity.discoveryInfo(keys: [key])
        XCTAssertEqual(Set(info.keys), Set(["v", "r", "f"]))
        XCTAssertTrue(ChatPeerCallSecurity.matchesDiscovery(info, key: key))
        XCTAssertFalse(ChatPeerCallSecurity.matchesDiscovery(info, key: SymmetricKey(data: Data(repeating: 9, count: 32))))
        XCTAssertFalse(ChatPeerCallSecurity.matchesDiscovery(["sc": "SECRET123", "n": "Ayşe"], key: key))
        XCTAssertNotEqual(info, ChatPeerCallSecurity.discoveryInfo(keys: [key]))
    }

    @MainActor func testUpgradeRetiresCachedRouteBadgesButPreservesMessageAndNewSignatureBadges() {
        let message = GattMeshChatMessage(id: "message-123", text: "Emergency", senderLabel: "Officer", sourcePeerId: "route",
            originVerifiedRole: "fieldteam", originVerifiedAtMillis: 123, isLocal: false, timestampMillis: 100,
            status: .delivered, deliveredTo: [], readBy: [])
        let retired = GattMeshChatStore.retireLegacyOriginVerification([message], version: nil)[0]
        XCTAssertEqual(retired.text, message.text); XCTAssertEqual(retired.id, message.id)
        XCTAssertNil(retired.originVerifiedRole); XCTAssertNil(retired.originVerifiedAtMillis)
        XCTAssertEqual(GattMeshChatStore.retireLegacyOriginVerification([message], version: 2)[0], message)
    }

    func testNumberPairingHasLocalKeyScopedConversationInsteadOfClaimedAccountPair() {
        let code = NearbyPairingSupport.localSessionCode(contactKey: Data(0..<32))
        XCTAssertEqual(code, "nYw3NKWbEM2aRElRI")
        XCTAssertNotEqual(code, NearbyPairingSupport.localSessionCode(contactKey: Data(repeating: 0, count: 32)))
    }

    func testAuthorityKeyLeaseExpiresAt72HourEpochAndIsolatesAccounts() {
        let period = AuthorityMeshKeyLease.periodMillis
        let lease = AuthorityMeshKeyLease(keyId: "authority-v2-10", keyBase64: "", ownerUid: "owner",
            notBeforeMs: 10 * period, expiresAtMs: 11 * period)
        XCTAssertTrue(lease.isCurrent(now: 10 * period, owner: "owner"))
        XCTAssertTrue(lease.isCurrent(now: 11 * period - 1, owner: "owner"))
        XCTAssertFalse(lease.isCurrent(now: 11 * period, owner: "owner"))
        XCTAssertFalse(lease.isCurrent(now: 10 * period - 1, owner: "owner"))
        XCTAssertFalse(lease.isCurrent(now: 10 * period, owner: "different-account"))
        XCTAssertFalse(AuthorityMeshKeyLease(keyId: lease.keyId, keyBase64: "", ownerUid: "owner",
            notBeforeMs: 10 * period, expiresAtMs: 12 * period).isCurrent(now: 10 * period, owner: "owner"))
    }
    func testUpgradePreservesLegacyBytesButDisablesTheirUseAndKeepsFreshPairings() throws {
        let legacyKey = Data(0..<32).base64EncodedString()
        let fresh = ContactRecord(id: UUID(), name: "Contact", broadcastId: "LEGACY1", sessionCode: "LEGACY1",
            aesKeyBase64: legacyKey, preferredTransport: .legacyBroadcast, createdAt: Date(), lastUpdated: Date())
        let encoded = try JSONEncoder().encode(fresh)
        var oldObject = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        oldObject.removeValue(forKey: "offlineKeyVersion")
        let restored = try JSONDecoder().decode(ContactRecord.self, from: JSONSerialization.data(withJSONObject: oldObject))
        XCTAssertEqual(restored.id, fresh.id); XCTAssertEqual(restored.name, fresh.name)
        XCTAssertEqual(restored.offlineKeyVersion, 1); XCTAssertEqual(restored.aesKeyBase64, legacyKey)
        XCTAssertTrue(restored.offlineRepairRequired)
        let restarted = try JSONDecoder().decode(ContactRecord.self, from: JSONEncoder().encode(restored))
        XCTAssertEqual(restarted.aesKeyBase64, legacyKey)
        XCTAssertTrue(restarted.offlineRepairRequired)
        XCTAssertNil(ContactStore.shared.aesKey(for: restarted))
        XCTAssertNil(ContactStore.shared.aesKey(for: restored))
        let roundTrip = try JSONDecoder().decode(ContactRecord.self, from: encoded)
        XCTAssertEqual(roundTrip.aesKeyBase64, legacyKey)
        XCTAssertNotNil(ContactStore.shared.aesKey(for: roundTrip))
    }
    func testFreshPairingWinsOverRetainedLegacyKeyWhenDuplicateContactsMerge() {
        let oldKey = Data(repeating: 1, count: 32).base64EncodedString()
        let newKey = Data(repeating: 2, count: 32).base64EncodedString()
        let legacy = ContactRecord(id: UUID(), name: "Family", broadcastId: "old", sessionCode: "LEGACY1",
            aesKeyBase64: oldKey, offlineKeyVersion: 1, preferredTransport: .legacyBroadcast, createdAt: Date(), lastUpdated: Date())
        let fresh = ContactRecord(id: UUID(), name: "Family", broadcastId: "old", sessionCode: "LEGACY1",
            aesKeyBase64: newKey, preferredTransport: .legacyBroadcast, createdAt: Date(), lastUpdated: Date())
        let merged = ContactStore.shared.mergeDuplicateRecord(primary: legacy, secondary: fresh)
        XCTAssertEqual(merged.id, legacy.id)
        XCTAssertEqual(merged.aesKeyBase64, newKey)
        XCTAssertFalse(merged.offlineRepairRequired)
        XCTAssertNotNil(ContactStore.shared.aesKey(for: merged))
        let reverse = ContactStore.shared.mergeDuplicateRecord(primary: fresh, secondary: legacy)
        XCTAssertEqual(reverse.aesKeyBase64, newKey)
        XCTAssertFalse(reverse.offlineRepairRequired)
    }

    func testLegacyQrIsDecodedForOnlineContactButCannotAuthorizeOfflinePairing() throws {
        let raw = #"{"v":1,"code":"OLD123","key":"retained-key","platform":"android"}"#
        XCTAssertFalse(try XCTUnwrap(ContactQRPayload.decode(from: raw)).supportsSecureOfflinePairing)
        let fresh = ContactQRPayload(code: "NEW123", key: "fresh-key", platform: "ios")
        XCTAssertTrue(try XCTUnwrap(ContactQRPayload.decode(from: XCTUnwrap(fresh.encodedString()))).supportsSecureOfflinePairing)
        XCTAssertFalse(ContactQRPayload(v: 1, offlineProtocolVersion: 3, code: "NEW123", key: "key", platform: "ios").supportsSecureOfflinePairing)
    }

    @MainActor func testOnlineQrFallbackNeverActivatesKeyOrReplacesFreshPairing() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("contacts.enc")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = ContactStore(persistenceURL: url)
        await store.waitUntilLoaded()
        let oldKey = Data(repeating: 1, count: 32).base64EncodedString()
        let newKey = Data(repeating: 2, count: 32).base64EncodedString()
        func add(_ key: String, version: Int) -> ContactRecord {
            store.upsertBleContact(name: "Family", sessionCode: "PEER123", aesKeyBase64: key, offlineKeyVersion: version,
                remoteSessionCode: "REMOTE123", remotePlatform: .ios, bleShareId: nil, lastKnownBleAddress: nil, remoteDeviceId: nil)
        }
        let imported = add(oldKey, version: 1)
        XCTAssertTrue(imported.offlineRepairRequired); XCTAssertNil(store.aesKey(for: imported))
        let relabeled = add(oldKey, version: 2)
        XCTAssertTrue(relabeled.offlineRepairRequired); XCTAssertNil(store.aesKey(for: relabeled))
        let repaired = add(newKey, version: 2)
        XCTAssertEqual(repaired.id, imported.id); XCTAssertNotNil(store.aesKey(for: repaired))
        let delayed = add(oldKey, version: 1)
        XCTAssertEqual(delayed.aesKeyBase64, newKey); XCTAssertFalse(delayed.offlineRepairRequired)
        try await store.flushDurably()
    }

    func testSignalMigrationDoesNotDowngradeWhenRatchetIsMissingOrIdentityChanges() {
        XCTAssertTrue(SignalMigrationPolicy.permitsApplication(isSignal: false, hasAuthenticatedBinding: false, identityChanged: false))
        XCTAssertFalse(SignalMigrationPolicy.permitsApplication(isSignal: false, hasAuthenticatedBinding: true, identityChanged: false))
        XCTAssertTrue(SignalMigrationPolicy.permitsApplication(isSignal: true, hasAuthenticatedBinding: true, identityChanged: false))
        XCTAssertFalse(SignalMigrationPolicy.permitsApplication(isSignal: true, hasAuthenticatedBinding: true, identityChanged: true))
        XCTAssertFalse(SignalMigrationPolicy.permitsBindingControl(requestReply: true, alreadyBound: false, cohortEnabled: false))
        XCTAssertFalse(SignalMigrationPolicy.permitsBindingControl(requestReply: false, alreadyBound: false, cohortEnabled: true))
        XCTAssertTrue(SignalMigrationPolicy.permitsBindingControl(requestReply: true, alreadyBound: false, cohortEnabled: true))
        XCTAssertTrue(SignalMigrationPolicy.permitsBindingControl(requestReply: false, alreadyBound: true, cohortEnabled: false))
    }

    func testBleReceiptsRequireSessionKeyAndRejectReplayAfterExpiryAndTampering() throws {
        let key = SymmetricKey(data: Data(0..<32))
        for text in ["READ|message-123", "DELIVERED|message-123"] {
            let plain = Data(text.utf8)
            let wire = try XCTUnwrap(SecureBleReceipt.seal(key: key, payload: plain, now: 1_000_000))
            XCTAssertEqual(SecureBleReceipt.open(key: key, wire: wire, now: 1_000_000), plain)
            XCTAssertNil(SecureBleReceipt.open(key: SymmetricKey(size: .bits256), wire: wire, now: 1_000_000))
            XCTAssertNil(SecureBleReceipt.open(key: key, wire: plain, now: 1_000_000))
            XCTAssertNil(SecureBleReceipt.open(key: key, wire: wire, now: 1_600_001))
            for index in wire.indices {
                var changed = wire; changed[index] ^= 1
                XCTAssertNil(SecureBleReceipt.open(key: key, wire: changed, now: 1_000_000))
            }
        }
    }
    private let pttNow: Int64 = 1_700_000_000_000
    private func pttProof(_ nonce: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data(nonce.utf8), using: SymmetricKey(data: Data("test-role-authority".utf8))))
    }
    private func pttCodec(key: Data = Data(0..<32), clock: (() -> Int64)? = nil) -> SecureMeshPtt {
        SecureMeshPtt(groupKey: { key }, createProof: { self.pttProof($0) }, verifyProof: { supplied, nonce in
            guard supplied == self.pttProof(nonce) else { return nil }
            return SecureMeshPtt.VerifiedRole(agency: "AFAD", expiresAt: self.pttNow + 60_000)
        }, clock: clock ?? { self.pttNow })
    }

    func testSecurePttRejectsTamperingReplayAndUnauthenticatedAudioAndFitsATT() throws {
        let alice = pttCodec(), bob = pttCodec()
        let control = try XCTUnwrap(alice.encode(kind: 1, label: "Alice", body: Data(repeating: 0, count: 8)))
        let body = Data([0, 0, 0, 7, 0, 4, 1, 2, 3, 4])
        let audio = try XCTUnwrap(alice.encode(kind: 5, label: "Alice", body: body))
        XCTAssertEqual(bob.decode(control)?.agency, "AFAD")
        XCTAssertEqual(bob.decode(audio)?.body, body)
        XCTAssertNil(bob.decode(audio)); XCTAssertNil(alice.decode(control))
        XCTAssertNil(pttCodec().decode(audio))
        XCTAssertNil(pttCodec(key: Data(repeating: 0, count: 32)).decode(control))
        XCTAssertNil(pttCodec(clock: { self.pttNow + 11_000 }).decode(control))
        XCTAssertLessThanOrEqual(audio.count + 3, 244)
        for index in audio.indices where index != 4 {
            let receiver = pttCodec(); XCTAssertNotNil(receiver.decode(control))
            var tampered = audio; tampered[index] ^= 1
            XCTAssertNil(receiver.decode(tampered), "tampered byte \(index)")
        }
        let relay = pttCodec(); XCTAssertNotNil(relay.decode(control))
        XCTAssertEqual(relay.decode(try XCTUnwrap(SecureMeshPtt.relayed(audio, hop: 2)))?.hop, 2)
        XCTAssertNil(SecureMeshPtt.relayed(audio, hop: 5))
    }
    func testSecurePttOpensAndroidGeneratedFixture() throws {
        let receiver = pttCodec()
        let control = try XCTUnwrap(Data(base64Encoded: "Q1BUMgABKS0vMcZojCGQWCoflupvDwAAAYvP5WgAAAAAAQVBbGljZQB9AFswWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASKxdbpVPAPD21NGKY7weXwRtbVicay3hINyaZNAG4f54ArTbDJmZqQVmbr9wX9quWBUxeTR2344X3g1zz5SbboMzV7IrnY6pjO77XNSx2N6odYX0GhV52z4kwtQDfe5ue0t9yTJpMn/l6bzH/IbdfGBNRbPT+ljS2cSiuYWpNAp34gErswRgIhANohqzukEEkptddN63TM9SF0cl4FcfgXr5lwpc85qnXWAiEAtSgqfRM4zYUX3YqkUXgEYlUK5HSLOJFlQWitJys2C2hI"))
        let audio = try XCTUnwrap(Data(base64Encoded: "Q1BUMgAFKS0vMcZojCGQWCoflupvDwAAAYvP5WgAAAAAAgVBbGljZQAAkIMS6KN/GNGywV9f9C3Sz12PQDGBDsMZWx45/bNdWHx+gjDWO04wRQIhAIE2aUzokgLmJ0e8XgvrEzxYAuNypfJXvmbnW78f0p8yAiBovdxDq7VXgIC6QdPymCs63cmNx0j2n+7xmpzKstYWQUc="))
        XCTAssertEqual(receiver.decode(control)?.agency, "AFAD")
        XCTAssertEqual(receiver.decode(audio)?.body, Data([0, 0, 0, 7, 0, 4, 1, 2, 3, 4]))
    }

    func testContactIdentityBindingMatchesAndroidAndRejectsSubstitution() {
        let key = SymmetricKey(data: Data(0..<32))
        let proof = ContactIdentityBinding.sign(key: key, uid: "uid-ä", sessionCode: "ABC123", publicKey: "public-key")
        XCTAssertEqual(proof, "FbhJmHAiLEAITG2CBkZTI3SW8B+fyk7diKAaJipi9pw=")
        XCTAssertTrue(ContactIdentityBinding.verify(proof: proof, key: key, uid: "uid-ä", sessionCode: "ABC123", publicKey: "public-key"))
        for fields in [["attacker", "ABC123", "public-key"], ["uid-ä", "OTHER1", "public-key"], ["uid-ä", "ABC123", "attacker-key"]] {
            XCTAssertFalse(ContactIdentityBinding.verify(proof: proof, key: key, uid: fields[0], sessionCode: fields[1], publicKey: fields[2]))
        }
    }

    func testSignalBindingRequiresExplicitResetAndIsScopedToAccountAndP256Identity() {
        let owner = "security-test-\(UUID().uuidString)"
        let first = Data(repeating: 5, count: 33).base64EncodedString()
        let next = Data(repeating: 7, count: 33).base64EncodedString()
        defer { SignalPeerBinding.reset(owner: owner, peer: "peer", p256: "p256") }
        XCTAssertTrue(SignalPeerBinding.markMigrationPending(owner: owner, peer: "peer", p256: "p256"))
        XCTAssertTrue(SignalPeerBinding.migrationPending(owner: owner, peer: "peer", p256: "p256"))
        XCTAssertTrue(SignalPeerBinding.accept(owner: owner, peer: "peer", p256: "p256", signal: first))
        XCTAssertFalse(SignalPeerBinding.migrationPending(owner: owner, peer: "peer", p256: "p256"))
        XCTAssertNil(SignalPeerBinding.read(owner: "another-account", peer: "peer", p256: "p256"))
        XCTAssertNil(SignalPeerBinding.read(owner: owner, peer: "another-peer", p256: "p256"))
        XCTAssertNil(SignalPeerBinding.read(owner: owner, peer: "peer", p256: "another-p256"))
        XCTAssertFalse(SignalPeerBinding.accept(owner: owner, peer: "peer", p256: "p256", signal: next))
        XCTAssertTrue(SignalPeerBinding.changed(owner: owner, peer: "peer", p256: "p256"))
        XCTAssertFalse(SignalPeerBinding.accept(owner: owner, peer: "peer", p256: "p256", signal: first))
        XCTAssertEqual(SignalPeerBinding.read(owner: owner, peer: "peer", p256: "p256"), first)
        SignalPeerBinding.reset(owner: owner, peer: "peer", p256: "p256")
        XCTAssertTrue(SignalPeerBinding.accept(owner: owner, peer: "peer", p256: "p256", signal: next))
    }

    func testAuthorityMeshRejectsPlaintextAndWrongGroupKeyButPublicMeshStillWorks() throws {
        let packet = try XCTUnwrap(GattMeshProtocol.makeChatPacket(text: "legitimate public message", messageId: "SECURITY-1234"))
        let encoded = try XCTUnwrap(GattMeshProtocol.encodePacket(packet))
        XCTAssertEqual(GattMeshProtocol.decodePacket(from: encoded)?.message, packet.message)
        XCTAssertNil(GattMeshProtocol.decodePacket(from: encoded, profile: .authority))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        object["encrypted"] = false
        object["protocol"] = "dcs-gattmesh-v1"
        object["message"] = "forged evacuation order"
        object.removeValue(forKey: "iv"); object.removeValue(forKey: "cipher")
        let plaintext = try JSONSerialization.data(withJSONObject: object)
        XCTAssertNotNil(GattMeshProtocol.decodePacket(from: plaintext))
        XCTAssertNil(GattMeshProtocol.decodePacket(from: plaintext, profile: .authority))
    }

    func testAuthorityMeshStillAcceptsItsAuthenticatedGroupMessages() throws {
        let previous = AuthorityMeshKeyStore.loadLease()
        defer { if let previous { try? AuthorityMeshKeyStore.saveGroupKey(previous) } else { AuthorityMeshKeyStore.clearGroupKey() } }
        let now = Int64(Date().timeIntervalSince1970 * 1000), period = AuthorityMeshKeyLease.periodMillis
        let epoch = now / period
        try AuthorityMeshKeyStore.saveGroupKey(AuthorityMeshKeyLease(keyId: "authority-v2-\(epoch)",
            keyBase64: Data(repeating: 0x5a, count: 32).base64EncodedString(), ownerUid: Auth.auth().currentUser?.uid ?? "",
            notBeforeMs: epoch * period, expiresAtMs: (epoch + 1) * period))
        let packet = try XCTUnwrap(GattMeshProtocol.makeChatPacket(text: "verified group payload", profile: .authority))
        let encoded = try XCTUnwrap(GattMeshProtocol.encodePacket(packet))
        XCTAssertEqual(GattMeshProtocol.decodePacket(from: encoded, profile: .authority)?.message, packet.message)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        object["sender"] = "forged sender"
        XCTAssertNil(GattMeshProtocol.decodePacket(from: try JSONSerialization.data(withJSONObject: object), profile: .authority))
    }

    func testCallInvitationRequiresContactKeyAndBindsEveryField() {
        let key = SymmetricKey(size: .bits256)
        let fields = ["3", "call", "ALICE1", "BOB123", "Alice", "audio", "1234567", "nonce"]
        let proof = ChatPeerCallSecurity.proof(key: key, fields: fields)
        XCTAssertTrue(ChatPeerCallSecurity.verify(proof: proof, key: key, fields: fields))
        XCTAssertFalse(ChatPeerCallSecurity.verify(proof: proof, key: SymmetricKey(size: .bits256), fields: fields))
        for i in fields.indices {
            var changed = fields; changed[i] += "x"
            XCTAssertFalse(ChatPeerCallSecurity.verify(proof: proof, key: key, fields: changed))
        }
    }

    func testCallMediaKeysIsolateDirectionAndCallAndRejectAlteredHeader() throws {
        let contact = SymmetricKey(size: .bits256)
        let key = ChatPeerCallSecurity.mediaKey(contactKey: contact, callId: "call-1", nonce: "fresh", direction: "a2b")
        let header = Data([1, 0, 0, 0, 7]), audio = Data([9, 8, 7])
        let box = try AES.GCM.seal(audio, using: key, authenticating: header)
        XCTAssertEqual(try AES.GCM.open(box, using: key, authenticating: header), audio)
        XCTAssertThrowsError(try AES.GCM.open(box, using: key, authenticating: Data([1, 0, 0, 0, 8])))
        for (call, nonce, direction) in [("call-2", "fresh", "a2b"), ("call-1", "other", "a2b"), ("call-1", "fresh", "b2a")] {
            XCTAssertThrowsError(try AES.GCM.open(box, using: ChatPeerCallSecurity.mediaKey(contactKey: contact,
                callId: call, nonce: nonce, direction: direction), authenticating: header))
        }
    }
}
