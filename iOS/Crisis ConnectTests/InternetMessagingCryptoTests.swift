//
//  InternetMessagingCryptoTests.swift
//  Crisis ConnectTests
//
//  Golden vector shared with the Android test suite (`E2eInteropVectorTest.kt`, produced by the real
//  `E2eEnvelope`). If this fails, the internet-messaging wire crypto diverged from Android and BOTH
//  platforms must be reconciled before shipping — a mismatch means messages silently fail to decrypt
//  across platforms.
//

import XCTest
import CryptoKit
@testable import Crisis_Connect

final class InternetMessagingCryptoTests: XCTestCase {

    // Emitted by Android E2eInteropVectorTest (authenticated es‖ss construction, alg v2).
    private let recipientPrivatePkcs8B64 = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBjuPt62tYc7xWmLAhDgnPs6O0SnqOZ+mPaa5EFScRZsA=="
    private let senderPublicSpkiB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKYdrHZPmt5qCyW/ntbQNLEsAZJ/KsuLhJrn8DU40Si76TFI6UOeZf4PjK7dJRzR9nBCOyeBaL56FLIEf9kHouw=="
    private let ephemeralPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgRDY5XSyZklZGEMFvN5z1ML9LBHGlxkqkAatnsg86owtXBfVSslka/w8GdC7mqL03o68wQ8zxR1pJx4xhmvURQ=="
    private let nonce = "nMv3MCXRPlfZRq+U"
    private let ciphertext = "Sg8SkM4/0gztFsflaf4xd+/GnGhwoM0xxi4eS1e3QxY8YFNVckCyXy640ABHJXYqhFFMaQ=="
    private let senderUid = "uidSENDER"
    private let recipientUid = "uidRECIP"
    private let conversationId = "conv-interop-1"
    private let expectedText = "Enkaz altındayım, 2 kişi mahsur"
    private let expectedTemplateCode = 0

    private func loadRecipientKey() throws -> P256.KeyAgreement.PrivateKey {
        let der = try XCTUnwrap(Data(base64Encoded: recipientPrivatePkcs8B64))
        return try P256.KeyAgreement.PrivateKey(derRepresentation: der)
    }

    private var androidSealed: SealedInternetMessage {
        SealedInternetMessage(ephemeralPublicKey: ephemeralPublicKey, nonce: nonce, ciphertext: ciphertext)
    }

    func testDecryptsAndroidGoldenVector() throws {
        let recipient = try loadRecipientKey()
        let sender = try MessagingKeyCodec.decodePublicKey(senderPublicSpkiB64)

        let opened = try InternetE2eEnvelope.open(
            sealed: androidSealed,
            recipientPrivateKey: recipient,
            senderStaticPublicKey: sender,
            senderUid: senderUid,
            recipientUid: recipientUid,
            conversationId: conversationId
        )

        XCTAssertEqual(opened.text, expectedText)
        XCTAssertEqual(opened.templateCode, expectedTemplateCode)
    }

    func testWrongSenderKeyIsRejected() throws {
        // Static-static ECDH binds the sender's identity key into the message key, so opening under a
        // different sender key (a forger/server that knows only public keys) must fail to authenticate.
        let recipient = try loadRecipientKey()
        let attacker = P256.KeyAgreement.PrivateKey().publicKey

        XCTAssertThrowsError(
            try InternetE2eEnvelope.open(
                sealed: androidSealed,
                recipientPrivateKey: recipient,
                senderStaticPublicKey: attacker,
                senderUid: senderUid,
                recipientUid: recipientUid,
                conversationId: conversationId
            )
        )
    }

    func testWrongConversationIsRejected() throws {
        let recipient = try loadRecipientKey()
        let sender = try MessagingKeyCodec.decodePublicKey(senderPublicSpkiB64)

        XCTAssertThrowsError(
            try InternetE2eEnvelope.open(
                sealed: androidSealed,
                recipientPrivateKey: recipient,
                senderStaticPublicKey: sender,
                senderUid: senderUid,
                recipientUid: recipientUid,
                conversationId: "conv-DIFFERENT"
            )
        )
    }

    func testRoundTrip() throws {
        let recipient = P256.KeyAgreement.PrivateKey()
        let sender = P256.KeyAgreement.PrivateKey()
        let content = InternetMessageContent(templateCode: 0, text: "Su ve battaniye lazım")

        let sealed = try InternetE2eEnvelope.seal(
            content: content,
            recipientPublicKey: recipient.publicKey,
            senderPrivateKey: sender,
            senderUid: "s", recipientUid: "r", conversationId: "c"
        )
        let opened = try InternetE2eEnvelope.open(
            sealed: sealed,
            recipientPrivateKey: recipient,
            senderStaticPublicKey: sender.publicKey,
            senderUid: "s", recipientUid: "r", conversationId: "c"
        )

        XCTAssertEqual(opened, content)
    }

    // MARK: - v2 attachment payload (must stay byte-identical to Android's ByteBuffer layout)

    private var sampleAttachment: InternetMessageAttachment {
        InternetMessageAttachment(
            kind: InternetE2eEnvelope.attachmentKindAudio,
            mime: "audio/mp4",
            name: "voice_abc.m4a",
            transferId: "tid-1234",
            chunkIndex: 1,
            chunkCount: 3,
            totalSize: 30_000,
            durationMs: 4_200,
            bytes: Data([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x7F])
        )
    }

    func testAttachmentRoundTrip() throws {
        let recipient = P256.KeyAgreement.PrivateKey()
        let sender = P256.KeyAgreement.PrivateKey()
        let content = InternetMessageContent(templateCode: 0, text: "", attachment: sampleAttachment)

        let sealed = try InternetE2eEnvelope.seal(
            content: content,
            recipientPublicKey: recipient.publicKey,
            senderPrivateKey: sender,
            senderUid: "s", recipientUid: "r", conversationId: "c"
        )
        let opened = try InternetE2eEnvelope.open(
            sealed: sealed,
            recipientPrivateKey: recipient,
            senderStaticPublicKey: sender.publicKey,
            senderUid: "s", recipientUid: "r", conversationId: "c"
        )

        XCTAssertEqual(opened, content)
    }

    /// Replicates Android `E2eEnvelope.encodeContent`'s ByteBuffer writes (big-endian) by hand and
    /// asserts our encoder emits the identical bytes — the actual cross-platform contract.
    func testAttachmentEncodingMatchesAndroidByteLayout() throws {
        let att = sampleAttachment

        var expected = Data()
        expected.append(2) // PAYLOAD_VERSION_ATTACHMENT
        expected.append(UInt8(att.kind))
        for value in [att.chunkIndex, att.chunkCount, att.totalSize, att.durationMs] {
            var be = Int32(value).bigEndian
            withUnsafeBytes(of: &be) { expected.append(contentsOf: $0) }
        }
        for string in [att.mime, att.name, att.transferId] {
            var lengthBE = UInt16(string.utf8.count).bigEndian
            withUnsafeBytes(of: &lengthBE) { expected.append(contentsOf: $0) }
            expected.append(Data(string.utf8))
        }
        expected.append(att.bytes)

        let encoded = InternetE2eEnvelope.encodeContent(
            InternetMessageContent(templateCode: 0, text: "", attachment: att)
        )
        XCTAssertEqual(encoded, expected)

        let decoded = try InternetE2eEnvelope.decodeContent(expected)
        XCTAssertEqual(decoded.attachment, att)
    }

    func testTruncatedAttachmentPayloadIsRejected() throws {
        let encoded = InternetE2eEnvelope.encodeContent(
            InternetMessageContent(templateCode: 0, text: "", attachment: sampleAttachment)
        )
        // Cut inside the length-prefixed transferId — decode must throw, not crash or misparse.
        let truncated = encoded.prefix(24)
        XCTAssertThrowsError(try InternetE2eEnvelope.decodeContent(Data(truncated)))
    }
}
