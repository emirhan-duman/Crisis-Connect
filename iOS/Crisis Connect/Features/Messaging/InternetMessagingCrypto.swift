//
//  InternetMessagingCrypto.swift
//  Crisis Connect
//
//  E2E crypto for low-bandwidth internet messaging. This is the iOS port of the Android
//  `E2eEnvelope` / `MessagingKeyCodec` (Faz 4) and MUST stay byte-for-byte compatible with it
//  and with the web (WebCrypto) client, otherwise cross-platform decryption breaks silently.
//
//  Construction (authenticated sealed-sender, mirrors Android commit a7be594):
//    key = HKDF-SHA256(
//            ikm  = ECDH(ephemeral, recipientIdentity) || ECDH(senderIdentity, recipientIdentity),
//            salt = 32 zero bytes,
//            info = "crisisconnect:e2e:v2:" + conversationId,
//            len  = 32)
//    ciphertext = AES-256-GCM(key, nonce=12B, plaintext, aad="senderUid|recipientUid|conversationId")
//  The static-static term (ECDH(senderIdentity, recipientIdentity)) authenticates the sender: a
//  party holding only public keys cannot forge a message another device will accept.
//
//  Wire encoding (all base64, standard alphabet, padded, single line = Android Base64.NO_WRAP):
//    - ephemeralPublicKey: SPKI/X.509 DER of the ephemeral P-256 public key
//    - nonce:              the 12-byte GCM nonce
//    - ciphertext:         AES-GCM ciphertext followed by the 16-byte tag (ct || tag)
//    - inner plaintext:    [version=1][templateCode & 0xFF][utf8 text...]
//

import Foundation
import CryptoKit

/// Decrypted content. `templateCode == 0` means free text in `text`; a non-zero code refers to a
/// predefined crisis phrase rendered from a shared dictionary (the low-bandwidth win).
struct InternetMessageContent: Equatable {
    let templateCode: Int
    let text: String
    /// When non-nil, this message carries a binary attachment chunk (voice clip / image / file)
    /// instead of (or alongside) text. Large media is split across several sealed messages sharing
    /// a `transferId`; the receiver reassembles by transfer id.
    var attachment: InternetMessageAttachment? = nil
}

/// One chunk of a binary attachment carried inside the E2E envelope, byte-compatible with
/// Android's `MessageAttachment` (v2 payload layout).
struct InternetMessageAttachment: Equatable {
    let kind: Int // InternetE2eEnvelope.attachmentKindAudio / Image / File
    let mime: String
    let name: String
    let transferId: String
    let chunkIndex: Int
    let chunkCount: Int
    let totalSize: Int
    let durationMs: Int // audio duration; 0 when not applicable
    let bytes: Data
}

/// The server-visible, end-to-end-encrypted envelope. The server routes these bytes but never
/// holds a key to decrypt them. Mirrors the backend `RelayEnvelope` ciphertext fields.
struct SealedInternetMessage {
    let ephemeralPublicKey: String // base64 SPKI (DER)
    let nonce: String              // base64, 12-byte AES-GCM nonce
    let ciphertext: String         // base64 (AES-256-GCM ciphertext || 16-byte tag)
    var alg: String = InternetE2eEnvelope.alg
}

enum InternetMessagingCryptoError: Error {
    case invalidBase64
    case payloadTooShort
    case unsupportedPayloadVersion
}

/// SPKI (X.509 SubjectPublicKeyInfo) base64 codec for P-256 key-agreement public keys. Matches
/// Android `MessagingKeyCodec` (which uses `PublicKey.encoded`, i.e. SPKI DER) so the same base64
/// string round-trips across platforms.
enum MessagingKeyCodec {
    static func encodePublicKey(_ key: P256.KeyAgreement.PublicKey) -> String {
        key.derRepresentation.base64EncodedString()
    }

    static func decodePublicKey(_ base64: String) throws -> P256.KeyAgreement.PublicKey {
        guard let der = Data(base64Encoded: base64) else {
            throw InternetMessagingCryptoError.invalidBase64
        }
        return try P256.KeyAgreement.PublicKey(derRepresentation: der)
    }
}

enum InternetE2eEnvelope {
    static let alg = "ECDH-P256(es+ss)+HKDF-SHA256+AES-256-GCM"
    private static let infoPrefix = "crisisconnect:e2e:v2:"
    private static let payloadVersion: UInt8 = 1
    private static let payloadVersionAttachment: UInt8 = 2
    private static let gcmTagLength = 16
    private static let nonceLength = 12

    static let attachmentKindAudio = 1
    static let attachmentKindImage = 2
    static let attachmentKindFile = 3

    // MARK: - Seal

    /// Seals `content` to `recipientPublicKey`, authenticating it as `senderUid`.
    /// `deriveSenderStaticSecret` computes ECDH(senderIdentityPrivate, recipientPublicKey); passing a
    /// closure lets the sender identity key live in the Secure Enclave without exposing raw key bytes.
    static func seal(
        content: InternetMessageContent,
        recipientPublicKey: P256.KeyAgreement.PublicKey,
        deriveSenderStaticSecret: (P256.KeyAgreement.PublicKey) throws -> Data,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ) throws -> SealedInternetMessage {
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let es = rawSecret(try ephemeral.sharedSecretFromKeyAgreement(with: recipientPublicKey))
        let ss = try deriveSenderStaticSecret(recipientPublicKey)
        let key = deriveKey(ikm: es + ss, conversationId: conversationId)

        let nonceData = randomBytes(nonceLength)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let box = try AES.GCM.seal(
            encodeContent(content),
            using: key,
            nonce: nonce,
            authenticating: aad(senderUid, recipientUid, conversationId)
        )
        let ciphertextAndTag = box.ciphertext + box.tag

        return SealedInternetMessage(
            ephemeralPublicKey: ephemeral.publicKey.derRepresentation.base64EncodedString(),
            nonce: nonceData.base64EncodedString(),
            ciphertext: ciphertextAndTag.base64EncodedString()
        )
    }

    /// Convenience overload for a software sender private key (tests / non-enclave keys).
    static func seal(
        content: InternetMessageContent,
        recipientPublicKey: P256.KeyAgreement.PublicKey,
        senderPrivateKey: P256.KeyAgreement.PrivateKey,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ) throws -> SealedInternetMessage {
        try seal(
            content: content,
            recipientPublicKey: recipientPublicKey,
            deriveSenderStaticSecret: { recipientPub in
                rawSecret(try senderPrivateKey.sharedSecretFromKeyAgreement(with: recipientPub))
            },
            senderUid: senderUid,
            recipientUid: recipientUid,
            conversationId: conversationId
        )
    }

    // MARK: - Open

    /// Opens a sealed message. `deriveSharedSecret` computes ECDH(recipientIdentityPrivate, pub) for a
    /// given public key (kept in the Secure Enclave); it is invoked twice — once with the ephemeral
    /// key (es) and once with `senderStaticPublicKey` (ss). The message decrypts only if it was
    /// authored with the private key matching `senderStaticPublicKey`, so callers MUST pass the
    /// sender's pinned/verified identity key (safety-number / TOFU protected).
    static func open(
        sealed: SealedInternetMessage,
        deriveSharedSecret: (P256.KeyAgreement.PublicKey) throws -> Data,
        senderStaticPublicKey: P256.KeyAgreement.PublicKey,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ) throws -> InternetMessageContent {
        let ephemeralPublicKey = try MessagingKeyCodec.decodePublicKey(sealed.ephemeralPublicKey)
        let es = try deriveSharedSecret(ephemeralPublicKey)
        let ss = try deriveSharedSecret(senderStaticPublicKey)
        let key = deriveKey(ikm: es + ss, conversationId: conversationId)

        guard let nonceData = Data(base64Encoded: sealed.nonce),
              let ciphertextAndTag = Data(base64Encoded: sealed.ciphertext),
              ciphertextAndTag.count > gcmTagLength else {
            throw InternetMessagingCryptoError.invalidBase64
        }
        let ciphertext = ciphertextAndTag.prefix(ciphertextAndTag.count - gcmTagLength)
        let tag = ciphertextAndTag.suffix(gcmTagLength)
        let box = try AES.GCM.SealedBox(
            nonce: try AES.GCM.Nonce(data: nonceData),
            ciphertext: ciphertext,
            tag: tag
        )
        let plaintext = try AES.GCM.open(
            box,
            using: key,
            authenticating: aad(senderUid, recipientUid, conversationId)
        )
        return try decodeContent(plaintext)
    }

    /// Convenience overload for a software recipient private key (tests / non-enclave keys).
    static func open(
        sealed: SealedInternetMessage,
        recipientPrivateKey: P256.KeyAgreement.PrivateKey,
        senderStaticPublicKey: P256.KeyAgreement.PublicKey,
        senderUid: String,
        recipientUid: String,
        conversationId: String
    ) throws -> InternetMessageContent {
        try open(
            sealed: sealed,
            deriveSharedSecret: { pub in
                rawSecret(try recipientPrivateKey.sharedSecretFromKeyAgreement(with: pub))
            },
            senderStaticPublicKey: senderStaticPublicKey,
            senderUid: senderUid,
            recipientUid: recipientUid,
            conversationId: conversationId
        )
    }

    // MARK: - Primitives (kept identical to Android Crypto.kt)

    private static func deriveKey(ikm: Data, conversationId: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: Data(repeating: 0, count: 32), // Android uses a 32-byte zero salt when salt == null
            info: Data((infoPrefix + conversationId).utf8),
            outputByteCount: 32
        )
    }

    private static func aad(_ senderUid: String, _ recipientUid: String, _ conversationId: String) -> Data {
        Data("\(senderUid)|\(recipientUid)|\(conversationId)".utf8)
    }

    // Internal (not private) so the interop tests can assert the exact v2 byte layout.
    static func encodeContent(_ content: InternetMessageContent) -> Data {
        guard let att = content.attachment else {
            var out = Data()
            out.append(payloadVersion)
            out.append(UInt8(content.templateCode & 0xFF))
            out.append(Data(content.text.utf8))
            return out
        }
        // v2 attachment layout (big-endian, identical to Android's ByteBuffer encoding):
        // [2][kind][chunkIndex i32][chunkCount i32][totalSize i32][durationMs i32]
        // [mimeLen u16][mime][nameLen u16][name][tidLen u16][transferId][chunk bytes]
        var out = Data()
        out.append(payloadVersionAttachment)
        out.append(UInt8(att.kind & 0xFF))
        out.appendBigEndianInt32(att.chunkIndex)
        out.appendBigEndianInt32(att.chunkCount)
        out.appendBigEndianInt32(att.totalSize)
        out.appendBigEndianInt32(att.durationMs)
        out.appendLengthPrefixedUtf8(att.mime)
        out.appendLengthPrefixedUtf8(att.name)
        out.appendLengthPrefixedUtf8(att.transferId)
        out.append(att.bytes)
        return out
    }

    static func decodeContent(_ bytes: Data) throws -> InternetMessageContent {
        guard bytes.count >= 2 else { throw InternetMessagingCryptoError.payloadTooShort }
        // Data slices keep their parent indices; re-base to read positionally.
        let flat = Data(bytes)
        switch flat[0] {
        case payloadVersion:
            let templateCode = Int(flat[1])
            let text = String(decoding: flat.suffix(from: 2), as: UTF8.self)
            return InternetMessageContent(templateCode: templateCode, text: text)
        case payloadVersionAttachment:
            var reader = BigEndianReader(flat, offset: 1)
            let kind = Int(try reader.readByte())
            let chunkIndex = try reader.readInt32()
            let chunkCount = try reader.readInt32()
            let totalSize = try reader.readInt32()
            let durationMs = try reader.readInt32()
            let mime = try reader.readLengthPrefixedUtf8()
            let name = try reader.readLengthPrefixedUtf8()
            let transferId = try reader.readLengthPrefixedUtf8()
            let chunk = reader.remaining()
            return InternetMessageContent(
                templateCode: 0,
                text: "",
                attachment: InternetMessageAttachment(
                    kind: kind,
                    mime: mime,
                    name: name,
                    transferId: transferId,
                    chunkIndex: chunkIndex,
                    chunkCount: chunkCount,
                    totalSize: totalSize,
                    durationMs: durationMs,
                    bytes: chunk
                )
            )
        default:
            throw InternetMessagingCryptoError.unsupportedPayloadVersion
        }
    }

    private static func rawSecret(_ secret: SharedSecret) -> Data {
        secret.withUnsafeBytes { Data($0) }
    }

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = Data(count: count)
        _ = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return bytes
    }
}

// MARK: - v2 payload binary helpers (big-endian, matching Android's ByteBuffer defaults)

private extension Data {
    mutating func appendBigEndianInt32(_ value: Int) {
        var be = Int32(clamping: value).bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }

    mutating func appendLengthPrefixedUtf8(_ string: String) {
        let bytes = Data(string.utf8)
        var be = UInt16(clamping: bytes.count).bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
        append(bytes.prefix(Int(UInt16.max)))
    }
}

/// Positional big-endian reader with bounds checking; any overrun throws `payloadTooShort`.
private struct BigEndianReader {
    private let data: Data
    private var offset: Int

    init(_ data: Data, offset: Int) {
        self.data = data
        self.offset = offset
    }

    mutating func readByte() throws -> UInt8 {
        guard offset + 1 <= data.count else { throw InternetMessagingCryptoError.payloadTooShort }
        defer { offset += 1 }
        return data[offset]
    }

    mutating func readInt32() throws -> Int {
        guard offset + 4 <= data.count else { throw InternetMessagingCryptoError.payloadTooShort }
        let value = data[offset..<(offset + 4)].reduce(0) { ($0 << 8) | Int($1) }
        offset += 4
        return Int(Int32(truncatingIfNeeded: value))
    }

    mutating func readUInt16() throws -> Int {
        guard offset + 2 <= data.count else { throw InternetMessagingCryptoError.payloadTooShort }
        let value = data[offset..<(offset + 2)].reduce(0) { ($0 << 8) | Int($1) }
        offset += 2
        return value
    }

    mutating func readLengthPrefixedUtf8() throws -> String {
        let length = try readUInt16()
        guard offset + length <= data.count else { throw InternetMessagingCryptoError.payloadTooShort }
        defer { offset += length }
        return String(decoding: data[offset..<(offset + length)], as: UTF8.self)
    }

    func remaining() -> Data {
        guard offset < data.count else { return Data() }
        return Data(data[offset...])
    }
}
