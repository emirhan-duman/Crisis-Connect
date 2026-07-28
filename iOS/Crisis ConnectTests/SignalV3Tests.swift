//
//  SignalV3Tests.swift
//  Crisis ConnectTests
//
//  FS-6: the forward-secret (v3) layer must interoperate with Android bit-for-bit. Three claims:
//   1. The inner content codec (the plaintext the ratchet encrypts) matches Android's
//      MessageContentCodec byte layout exactly — golden vectors below were derived from the
//      Android implementation.
//   2. A full libsignal session round-trip (PQXDH establish → prekey message → ratchet message)
//      encrypts and decrypts through the same in-memory stores the app's file store mirrors.
//   3. The v3 safety number is symmetric.
//

import XCTest
import LibSignalClient
@testable import Crisis_Connect

final class SignalV3Tests: XCTestCase {

    // MARK: - 1. Content codec golden vectors (must match Android MessageContentCodec)

    func testTextCodecMatchesAndroidLayout() throws {
        // Android: [1][templateCode u8][utf8 text]
        let content = InternetMessageContent(templateCode: 204, text: "abc")
        let encoded = InternetE2eEnvelope.encodeContent(content)
        XCTAssertEqual([UInt8](encoded), [1, 204, 0x61, 0x62, 0x63])

        let decoded = try InternetE2eEnvelope.decodeContent(encoded)
        XCTAssertEqual(decoded.templateCode, 204)
        XCTAssertEqual(decoded.text, "abc")
    }

    func testAttachmentCodecRoundTrip() throws {
        let attachment = InternetMessageAttachment(
            kind: 2,
            mime: "image/webp",
            name: "photo.webp",
            transferId: "t-1",
            chunkIndex: 1,
            chunkCount: 3,
            totalSize: 36_000,
            durationMs: 0,
            bytes: Data([9, 8, 7, 6])
        )
        let encoded = InternetE2eEnvelope.encodeContent(
            InternetMessageContent(templateCode: 0, text: "", attachment: attachment)
        )
        // Android header: [2][kind][chunkIndex i32][chunkCount i32][totalSize i32][durationMs i32]
        XCTAssertEqual(encoded[0], 2)
        XCTAssertEqual(encoded[1], 2)
        XCTAssertEqual([UInt8](encoded[2..<6]), [0, 0, 0, 1])   // chunkIndex BE
        XCTAssertEqual([UInt8](encoded[6..<10]), [0, 0, 0, 3])  // chunkCount BE

        let decoded = try InternetE2eEnvelope.decodeContent(encoded)
        let out = try XCTUnwrap(decoded.attachment)
        XCTAssertEqual(out.mime, "image/webp")
        XCTAssertEqual(out.transferId, "t-1")
        XCTAssertEqual(out.chunkIndex, 1)
        XCTAssertEqual(out.chunkCount, 3)
        XCTAssertEqual(out.totalSize, 36_000)
        XCTAssertEqual(out.bytes, Data([9, 8, 7, 6]))
    }

    // MARK: - 2. Full v3 session round-trip (in-memory stores)

    func testSignalSessionRoundTrip() throws {
        let alice = InMemorySignalProtocolStore(identity: IdentityKeyPair.generate(), registrationId: 11)
        let bob = InMemorySignalProtocolStore(identity: IdentityKeyPair.generate(), registrationId: 22)
        let bobAddress = try ProtocolAddress(name: "bob-uid", deviceId: 1)
        let aliceAddress = try ProtocolAddress(name: "alice-uid", deviceId: 1)
        let context = NullContext()

        // Bob publishes a PQXDH bundle; Alice processes it (what fetchSignalPreKeyBundle drives).
        let bobPreKey = PrivateKey.generate()
        let bobSignedPreKey = PrivateKey.generate()
        let bobKyber = KEMKeyPair.generate()
        let bobIdentity = try bob.identityKeyPair(context: context)
        let signedSig = bobIdentity.privateKey.generateSignature(message: bobSignedPreKey.publicKey.serialize())
        let kyberSig = bobIdentity.privateKey.generateSignature(message: bobKyber.publicKey.serialize())
        try bob.storePreKey(PreKeyRecord(id: 1, privateKey: bobPreKey), id: 1, context: context)
        try bob.storeSignedPreKey(
            SignedPreKeyRecord(id: 2, timestamp: 0, privateKey: bobSignedPreKey, signature: signedSig),
            id: 2, context: context
        )
        try bob.storeKyberPreKey(
            KyberPreKeyRecord(id: 3, timestamp: 0, keyPair: bobKyber, signature: kyberSig),
            id: 3, context: context
        )
        let bundle = try PreKeyBundle(
            registrationId: 22, deviceId: 1,
            prekeyId: 1, prekey: bobPreKey.publicKey,
            signedPrekeyId: 2, signedPrekey: bobSignedPreKey.publicKey, signedPrekeySignature: signedSig,
            identity: bobIdentity.identityKey,
            kyberPrekeyId: 3, kyberPrekey: bobKyber.publicKey, kyberPrekeySignature: kyberSig
        )
        try processPreKeyBundle(bundle, for: bobAddress, sessionStore: alice, identityStore: alice, context: context)

        // Alice → Bob: the first message is a PreKeySignalMessage carrying the codec bytes.
        let content = InternetMessageContent(templateCode: 0, text: "merhaba v3")
        let sealed = try signalEncrypt(
            message: InternetE2eEnvelope.encodeContent(content),
            for: bobAddress, sessionStore: alice, identityStore: alice, context: context
        )
        XCTAssertEqual(sealed.messageType, .preKey)

        let opened = try signalDecryptPreKey(
            message: PreKeySignalMessage(bytes: sealed.serialize()),
            from: aliceAddress,
            sessionStore: bob, identityStore: bob,
            preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob,
            context: context
        )
        XCTAssertEqual(try InternetE2eEnvelope.decodeContent(opened).text, "merhaba v3")

        // Bob → Alice: the reply rides the established ratchet (plain SignalMessage).
        let reply = try signalEncrypt(
            message: InternetE2eEnvelope.encodeContent(InternetMessageContent(templateCode: 0, text: "aldım")),
            for: aliceAddress, sessionStore: bob, identityStore: bob, context: context
        )
        XCTAssertEqual(reply.messageType, .whisper)
        let openedReply = try signalDecrypt(
            message: SignalMessage(bytes: reply.serialize()),
            from: bobAddress, sessionStore: alice, identityStore: alice, context: context
        )
        XCTAssertEqual(try InternetE2eEnvelope.decodeContent(openedReply).text, "aldım")

        // A replay of the first ciphertext must be flagged as the benign duplicate (the receive
        // path acks + drops on this exact error).
        XCTAssertThrowsError(
            try signalDecryptPreKey(
                message: PreKeySignalMessage(bytes: sealed.serialize()),
                from: aliceAddress,
                sessionStore: bob, identityStore: bob,
                preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob,
                context: context
            )
        ) { error in
            guard case SignalError.duplicatedMessage = error else {
                return XCTFail("expected duplicatedMessage, got \(error)")
            }
        }
    }

    // MARK: - 3. Safety number symmetry

    func testSafetyNumberIsSymmetric() throws {
        let alice = IdentityKeyPair.generate().publicKey
        let bob = IdentityKeyPair.generate().publicKey
        let generator = NumericFingerprintGenerator(iterations: 5200)
        let fromAlice = try generator.create(
            version: 0,
            localIdentifier: Data("alice-uid".utf8), localKey: alice,
            remoteIdentifier: Data("bob-uid".utf8), remoteKey: bob
        )
        let fromBob = try generator.create(
            version: 0,
            localIdentifier: Data("bob-uid".utf8), localKey: bob,
            remoteIdentifier: Data("alice-uid".utf8), remoteKey: alice
        )
        XCTAssertEqual(fromAlice.displayable.formatted, fromBob.displayable.formatted)
        XCTAssertEqual(fromAlice.displayable.formatted.count, 60)
    }

    // MARK: - Dedup

    func testDedupClaimAndRelease() {
        let id = "msg-\(UUID().uuidString)"
        XCTAssertTrue(InternetMessageDedup.shared.claim(id))
        XCTAssertFalse(InternetMessageDedup.shared.claim(id))
        InternetMessageDedup.shared.release(id)
        XCTAssertTrue(InternetMessageDedup.shared.claim(id))
        // A blank id must never collapse unrelated messages into one another.
        XCTAssertTrue(InternetMessageDedup.shared.claim(""))
        XCTAssertTrue(InternetMessageDedup.shared.claim(""))
    }
}
