import Foundation

struct AuthorityMlsMessagePayload: Sendable {
    let recipientUid: String
    let recipientName: String
    let senderName: String
    let text: String
    let sentAtMillis: Int64
    let attachments: [ChannelAttachment]
    let replyToId: String?
}

enum AuthorityMlsMessagePayloadError: Error {
    case malformed
    case tooLarge
}

/// Strict cross-platform AuthorityChat application envelope carried only inside MLS plaintext.
enum AuthorityMlsMessagePayloadCodec {
    private static let maxPayloadBytes = 900_000
    private static let maxTextBytes = 64 * 1024
    private static let maxAttachments = 8
    private static let idPattern = "^[A-Za-z0-9_-]{1,128}$"
    private static let pathPattern = "^authorityMessageAttachments/am2_[A-Za-z0-9_-]{43}/[^/]{1,256}/[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"

    private struct WirePayload: Codable {
        let version: Int
        let kind: String
        let recipientUid: String
        let recipientName: String
        let senderName: String
        let text: String
        let sentAtMillis: Int64
        let attachments: [WireAttachment]
        let replyToId: String?
    }

    private struct WireAttachment: Codable {
        let path: String
        let nonce: String
        let key: String?
        let name: String
        let mime: String
        let size: Int
        let width: Int?
        let height: Int?
        let duration: Int?
    }

    static func encode(_ payload: AuthorityMlsMessagePayload) throws -> Data {
        try validate(payload)
        let encoded = try JSONEncoder().encode(WirePayload(
            version: 2,
            kind: "message",
            recipientUid: payload.recipientUid,
            recipientName: payload.recipientName,
            senderName: payload.senderName,
            text: payload.text,
            sentAtMillis: payload.sentAtMillis,
            attachments: payload.attachments.map {
                WireAttachment(
                    path: $0.path,
                    nonce: $0.nonce,
                    key: $0.keyBase64,
                    name: $0.name,
                    mime: $0.mime,
                    size: $0.size,
                    width: $0.width,
                    height: $0.height,
                    duration: $0.durationSec
                )
            },
            replyToId: payload.replyToId
        ))
        guard !encoded.isEmpty, encoded.count <= maxPayloadBytes else { throw AuthorityMlsMessagePayloadError.tooLarge }
        return encoded
    }

    static func decode(_ encoded: Data) throws -> AuthorityMlsMessagePayload {
        guard !encoded.isEmpty, encoded.count <= maxPayloadBytes else { throw AuthorityMlsMessagePayloadError.tooLarge }
        let raw = try JSONDecoder().decode(WirePayload.self, from: encoded)
        guard raw.version == 2, raw.kind == "message" else { throw AuthorityMlsMessagePayloadError.malformed }
        let payload = AuthorityMlsMessagePayload(
            recipientUid: raw.recipientUid,
            recipientName: raw.recipientName,
            senderName: raw.senderName,
            text: raw.text,
            sentAtMillis: raw.sentAtMillis,
            attachments: raw.attachments.map {
                ChannelAttachment(
                    path: $0.path,
                    nonce: $0.nonce,
                    keyBase64: $0.key,
                    name: $0.name,
                    mime: $0.mime,
                    size: $0.size,
                    width: $0.width,
                    height: $0.height,
                    durationSec: $0.duration
                )
            },
            replyToId: raw.replyToId
        )
        try validate(payload)
        return payload
    }

    private static func validate(_ payload: AuthorityMlsMessagePayload) throws {
        try validateString(payload.recipientUid, maxBytes: 256, allowEmpty: false)
        try validateString(payload.recipientName, maxBytes: 256, allowEmpty: true)
        try validateString(payload.senderName, maxBytes: 256, allowEmpty: true)
        try validateString(payload.text, maxBytes: maxTextBytes, allowEmpty: true)
        guard payload.sentAtMillis > 0,
              payload.attachments.count <= maxAttachments,
              !payload.text.isEmpty || !payload.attachments.isEmpty,
              payload.replyToId == nil || payload.replyToId!.range(of: idPattern, options: .regularExpression) != nil else {
            throw AuthorityMlsMessagePayloadError.malformed
        }
        try payload.attachments.forEach(validateAttachment)
    }

    private static func validateAttachment(_ attachment: ChannelAttachment) throws {
        guard attachment.path.range(of: pathPattern, options: .regularExpression) != nil,
              let nonce = canonicalBase64(attachment.nonce), nonce.count == 12,
              let keyValue = attachment.keyBase64,
              let key = canonicalBase64(keyValue), key.count == 32,
              !attachment.name.isEmpty, attachment.name.utf8.count <= 255,
              !attachment.mime.isEmpty, attachment.mime.utf8.count <= 255,
              (0...ChannelAttachments.maxAttachmentBytes).contains(attachment.size) else {
            throw AuthorityMlsMessagePayloadError.malformed
        }
    }

    private static func validateString(_ value: String, maxBytes: Int, allowEmpty: Bool) throws {
        guard (allowEmpty || !value.isEmpty), value.utf8.count <= maxBytes,
              value.unicodeScalars.allSatisfy({ scalar in
                  let code = scalar.value
                  return !(code <= 8 || code == 11 || code == 12 || (14...31).contains(code) || code == 127)
              }) else { throw AuthorityMlsMessagePayloadError.malformed }
    }

    private static func canonicalBase64(_ value: String) -> Data? {
        guard let decoded = Data(base64Encoded: value), decoded.base64EncodedString() == value else { return nil }
        return decoded
    }
}
