import Foundation
import CryptoKit
import Security

@main struct SecurityRegressionChecks {
    static func main() throws {
        try leaseBoundaries()
        pairingAdmission()
        try identityStorage()
        try pairingAndMalformedPoints()
        try pttOverlap()
        try expiredRoleProof()
        protocolMigration()
        print("PASS: 7 security regression groups (leases, pairing admission, identity storage, SPAKE, PTT, certificate expiry, migration)")
    }
    static func check(_ value: @autoclosure () -> Bool, _ message: String) {
        precondition(value(), message)
    }
    static func mustThrow(_ body: () throws -> Void) {
        do { try body(); preconditionFailure("Expected rejection") } catch { }
    }
    static func leaseBoundaries() throws {
        let p = AuthorityMeshKeyLease.periodMillis, boundary = 11 * p
        let current = AuthorityMeshKeyLease(keyId: "authority-v2-10", keyBase64: "key", ownerUid: "owner",
            notBeforeMs: 10 * p, expiresAtMs: boundary, receiveUntilMs: boundary + 60_000)
        let next = AuthorityMeshKeyLease(keyId: "authority-v2-11", keyBase64: "next", ownerUid: "owner",
            notBeforeMs: boundary, expiresAtMs: 12 * p)
        let restored = try JSONDecoder().decode([AuthorityMeshKeyLease].self,
            from: JSONEncoder().encode([current, next]))
        check(restored[0].isCurrent(now: boundary - 1, owner: "owner"), "Current lease before boundary")
        check(!restored[1].isCurrent(now: boundary - 1, owner: "owner"), "Never send with future key")
        check(restored[1].isCacheable(now: 10 * p + 1, owner: "owner"), "Prefetch next lease")
        check(restored[1].isCurrent(now: boundary, owner: "owner"), "Activate cached next key offline after restart")
        check(!restored[0].isCurrent(now: boundary, owner: "owner"), "Never send with previous key")
        check(restored[0].isReceivable(now: boundary + 59_999, owner: "owner"), "Bounded receive overlap")
        check(!restored[0].isReceivable(now: boundary + 60_000, owner: "owner"), "End overlap exactly")
        check(!next.isReceivable(now: boundary - 60_001, owner: "owner"), "Do not accept arbitrarily early keys")
        check(!next.isCurrent(now: boundary, owner: "other"), "Account isolation")
        check(!next.isCacheable(now: 9 * p, owner: "owner"), "Never cache an unbounded future epoch")
        let invalid = AuthorityMeshKeyLease(keyId: "authority-v1", keyBase64: "", ownerUid: "owner",
            notBeforeMs: Int64.max, expiresAtMs: Int64.max)
        check(!invalid.isCurrent(now: boundary, owner: "owner"), "Overflow-safe malformed lease")
        let legacy = Data("{\"keyId\":\"authority-v2-10\",\"keyBase64\":\"old\",\"ownerUid\":\"owner\",\"notBeforeMs\":\(10*p),\"expiresAtMs\":\(boundary)}".utf8)
        let old = try JSONDecoder().decode(AuthorityMeshKeyLease.self, from: legacy)
        check(old.receiveUntilMs == boundary, "Old persisted lease must not gain grace")
    }
    static func pairingAdmission() {
        var admission = NearbyPairingAdmission()
        let peer = UUID()
        for _ in 0..<3 { check(admission.admit(peer, now: 100), "Admit ordinary attempt") }
        check(!admission.admit(peer, now: 101), "Limit guesses per peer")
        for _ in 0..<9 { check(admission.admit(UUID(), now: 101), "Admit bounded rotating peers") }
        check(!admission.admit(UUID(), now: 102), "Global bound survives address rotation")
        check(admission.admit(peer, now: 162), "Legitimate retry after window")
    }
    static func identityStorage() throws {
        for status in [errSecInteractionNotAllowed, errSecAuthFailed, errSecNotAvailable] {
            var additions = 0
            let identity = MessagingIdentity(read: { throw KeychainStore.AccessError.status(status) }, add: { _ in additions += 1 })
            mustThrow { _ = try identity.publicKeyBase64() }
            check(additions == 0, "Storage access errors must never replace identity")
        }
        var additions = 0
        let corrupt = MessagingIdentity(read: { Data([0xff, 0]) }, add: { _ in additions += 1 })
        mustThrow { _ = try corrupt.publicKeyBase64() }
        check(additions == 0, "Corrupt stored key must not be overwritten")
        let privateKey = P256.KeyAgreement.PrivateKey()
        let stored = Data([2]) + privateKey.rawRepresentation
        let restored = MessagingIdentity(read: { stored }, add: { _ in additions += 1 })
        let publicKey = try restored.publicKeyBase64()
        check(publicKey == MessagingKeyCodec.encodePublicKey(privateKey.publicKey), "Preserve existing identity")
        check(additions == 0, "A valid identity needs no writes")
        var created: Data?
        let fresh = MessagingIdentity(read: { nil }, add: { created = $0 })
        let first = try fresh.publicKeyBase64()
        check(created != nil, "First use creates identity after confirmed absence")
        let second = try fresh.publicKeyBase64()
        check(first == second, "Cached identity remains stable")
        let deniedWrite = MessagingIdentity(read: { nil }, add: { _ in throw KeychainStore.AccessError.status(errSecDuplicateItem) })
        mustThrow { _ = try deniedWrite.publicKeyBase64() }
    }
    static func pairingAndMalformedPoints() throws {
        let w = NearbySpakePairing.deriveW("+15555550100")
        for invalid in [Data(), Data([0]), Data(repeating: 0xff, count: 65), Data([4]) + Data(repeating: 0, count: 64)] {
            mustThrow { _ = try Spake2P256.keyA(w: w, x: BigUInt(small: 2), pB: invalid) }
            mustThrow { _ = try Spake2P256.keyB(w: w, y: BigUInt(small: 3), pA: invalid) }
        }
        let a = NearbyIdentity(uid: "alice", publicKeyBase64: "alice-key", displayName: "Alice")
        let b = NearbyIdentity(uid: "bob", publicKeyBase64: "bob-key", displayName: "Bob")
        let initiator = NearbySpakePairing.Initiator(w: w, me: a)
        let responder = NearbySpakePairing.Responder(w: w, me: b)
        let msg2 = try responder.onMessage1(initiator.message1())
        let msg3 = try initiator.onMessage2(msg2)
        let receivedA = try responder.onMessage3(msg3)
        check(receivedA == a, "Legitimate initiator identity")
        let msg4 = try responder.responseMessage(status: NearbySpakePairing.statusOk)
        let receivedB = try initiator.onMessage4(msg4)
        check(receivedB == b, "Legitimate responder identity")
        let keyA = try initiator.contactKey(), keyB = try responder.contactKey()
        check(keyA == keyB && keyA.count == 32, "Both devices agree on pairing key")
    }
    static func pttOverlap() throws {
        let now: Int64 = 1_700_000_000_000, old = Data(repeating: 1, count: 32), new = Data(repeating: 2, count: 32)
        func proof(_ nonce: String) -> Data { Data(nonce.utf8) }
        func verify(_ data: Data, _ nonce: String) -> SecureMeshPtt.VerifiedRole? {
            data == proof(nonce) ? .init(agency: "TEST", expiresAt: now + 60_000) : nil
        }
        let sender = SecureMeshPtt(groupKey: { old }, createProof: proof, verifyProof: verify, clock: { now })
        var overlap = true
        let receiver = SecureMeshPtt(groupKey: { new }, receiveKeys: { overlap ? [new, old] : [new] },
            createProof: proof, verifyProof: verify, clock: { now })
        let control = sender.encode(kind: 1, label: "Alice", body: Data([7]))!
        check(receiver.decode(control)?.body == Data([7]), "Receive overlap decrypts other epoch")
        check(receiver.decode(control) == nil, "Overlap must preserve replay protection")
        overlap = false
        check(receiver.decode(sender.encode(kind: 1, label: "Alice", body: Data([8]))!) == nil, "Retired key cannot decrypt")
    }
    static func expiredRoleProof() throws {
        let now: Int64 = 1_700_000_000_000
        let cert = RoleCertificate(ownerUID: "officer", role: "fieldteam", deviceId: "cc-000000000000000000000001",
            issuedAtMillis: now - 100_000, expiresAtMillis: now - 1, signatureBase64: Data([1]).base64EncodedString(),
            agency: "TEST", version: 3)
        let proof = RoleProofPayload(devicePublicKey: "unused", certificate: try cert.toStorageData().base64EncodedString(),
            timestamp: now, signature: "unused", sessionNonce: "nonce", allowExpiredCertificate: true)
        do {
            _ = try RoleProofVerifier(timeProvider: { now }).verifyProofPayload(proof: proof, expectedSessionNonce: "nonce")
            preconditionFailure("Expired certificate accepted")
        } catch RoleProofVerificationError.expiredCertificate { }
    }
    static func protocolMigration() {
        check(SignalMigrationPolicy.permitsApplication(isSignal: false, hasAuthenticatedBinding: false, identityChanged: false), "Legacy pinned P256 still works")
        check(!SignalMigrationPolicy.permitsApplication(isSignal: false, hasAuthenticatedBinding: true, identityChanged: false), "Authenticated binding prevents downgrade")
        check(!SignalMigrationPolicy.permitsApplication(isSignal: true, hasAuthenticatedBinding: true, identityChanged: true), "Changed identity must be rejected")
    }
}
