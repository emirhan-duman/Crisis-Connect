//
//  BleChatEnvelope.swift
//  Crisis Connect
//
//  Created by Assistant on 07.03.2026.
//

import Foundation

enum BleChatEnvelope {
    private static let prefix = "CCMSG1"
    private static let typeChat = "CHAT"
    private static let typeDelivered = "DELIVERED"
    private static let typeRead = "READ"
    private static let typeDecryptFail = "DECRYPT_FAIL"
    private static let fieldSeparator = "|"
    private static let listSeparator = ","
    private static let maxAckIdsPayloadBytes = 180
    private static let posixLocale = Locale(identifier: "en_US_POSIX")

    enum AckType {
        case delivered
        case read
        case decryptFail
    }

    struct ChatPayload {
        let messageId: String
        let text: String
        let createdAtMillis: Int64
        let ttlMillis: Int64
        let attempt: Int
        let route: String
    }

    struct AckPayload {
        let type: AckType
        let messageIds: [String]
    }

    static func encodeChat(
        messageId: String,
        text: String,
        createdAtMillis: Int64,
        ttlMillis: Int64,
        attempt: Int,
        route: String
    ) -> String {
        let cleanId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeAttempt = max(1, attempt)
        let safeTtl = max(1, ttlMillis)
        let safeRoute = sanitizeRoute(route)
        let encodedText = base64URLEncode(Data(text.utf8))
        return [
            prefix,
            typeChat,
            cleanId,
            String(createdAtMillis),
            String(safeTtl),
            String(safeAttempt),
            safeRoute,
            encodedText
        ].joined(separator: fieldSeparator)
    }

    static func decodeChat(_ raw: String) -> ChatPayload? {
        let parts = raw.split(separator: Character(fieldSeparator), maxSplits: 7, omittingEmptySubsequences: false)
        guard parts.count >= 8 else { return nil }
        guard String(parts[0]) == prefix, String(parts[1]) == typeChat else { return nil }

        let messageId = String(parts[2]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !messageId.isEmpty else { return nil }
        guard let createdAtMillis = Int64(parts[3]),
              let ttlMillisRaw = Int64(parts[4]),
              let attemptRaw = Int(parts[5]) else {
            return nil
        }

        guard let textData = base64URLDecode(String(parts[7])),
              let text = String(data: textData, encoding: .utf8) else {
            return nil
        }

        return ChatPayload(
            messageId: messageId,
            text: text,
            createdAtMillis: createdAtMillis,
            ttlMillis: max(1, ttlMillisRaw),
            attempt: max(1, attemptRaw),
            route: sanitizeRoute(String(parts[6]))
        )
    }

    static func isExpired(_ payload: ChatPayload, nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        nowMillis > (payload.createdAtMillis + payload.ttlMillis)
    }

    static func encodeDeliveredAck(messageId: String?) -> Data {
        let normalized = normalizeMessageIds(messageId.map { [$0] } ?? []).first
        let payload = normalized.map { "\(typeDelivered)\(fieldSeparator)\($0)" } ?? typeDelivered
        return Data(payload.utf8)
    }

    static func encodeDecryptFailAck(messageId: String?) -> Data {
        let normalized = normalizeMessageIds(messageId.map { [$0] } ?? []).first
        let payload = normalized.map { "\(typeDecryptFail)\(fieldSeparator)\($0)" } ?? typeDecryptFail
        return Data(payload.utf8)
    }

    static func encodeReadAck(messageIds: [String]) -> Data {
        var accepted: [String] = []
        for messageId in normalizeMessageIds(messageIds) {
            let candidateIds = accepted + [messageId]
            let candidate = "\(typeRead)\(fieldSeparator)\(candidateIds.joined(separator: listSeparator))"
            let payloadSize = candidate.lengthOfBytes(using: .utf8)
            if payloadSize <= maxAckIdsPayloadBytes {
                accepted = candidateIds
            } else {
                break
            }
        }
        let payload = accepted.isEmpty
            ? typeRead
            : "\(typeRead)\(fieldSeparator)\(accepted.joined(separator: listSeparator))"
        return Data(payload.utf8)
    }

    static func decodeAck(_ data: Data) -> AckPayload? {
        guard !data.isEmpty,
              let text = String(data: data, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty else {
            return nil
        }

        if text.caseInsensitiveCompare(typeDelivered) == .orderedSame {
            return AckPayload(type: .delivered, messageIds: [])
        }
        if text.caseInsensitiveCompare(typeRead) == .orderedSame {
            return AckPayload(type: .read, messageIds: [])
        }
        if text.caseInsensitiveCompare(typeDecryptFail) == .orderedSame {
            return AckPayload(type: .decryptFail, messageIds: [])
        }

        let parts = text.split(separator: Character(fieldSeparator), maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let ids = normalizeMessageIds(String(parts[1]).split(separator: Character(listSeparator)).map(String.init))

        switch String(parts[0]).uppercased(with: posixLocale) {
        case typeDelivered:
            return AckPayload(type: .delivered, messageIds: ids)
        case typeRead:
            return AckPayload(type: .read, messageIds: ids)
        case typeDecryptFail:
            return AckPayload(type: .decryptFail, messageIds: ids)
        default:
            return nil
        }
    }

    private static func normalizeMessageIds(_ messageIds: [String]) -> [String] {
        var seen = Set<String>()
        var normalized: [String] = []
        for messageId in messageIds {
            let clean = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clean.isEmpty, !seen.contains(clean) else { continue }
            seen.insert(clean)
            normalized.append(clean)
            if normalized.count >= 64 {
                break
            }
        }
        return normalized
    }

    private static func sanitizeRoute(_ route: String) -> String {
        let normalized = route.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(with: posixLocale)
        guard !normalized.isEmpty else { return "unknown" }
        let sanitized = normalized.map { character -> Character in
            switch character {
            case "a"..."z", "0"..."9", "_", "-":
                return character
            default:
                return "_"
            }
        }
        let value = String(sanitized)
        return value.isEmpty ? "unknown" : value
    }

    private static func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func base64URLDecode(_ value: String) -> Data? {
        var normalized = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 {
            normalized = normalized.padding(toLength: normalized.count + (4 - remainder), withPad: "=", startingAt: 0)
        }
        return Data(base64Encoded: normalized, options: [.ignoreUnknownCharacters])
    }
}
