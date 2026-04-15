//
//  GattMeshProtocol.swift
//  Crisis Connect
//
//  Created by Codex on 29.03.2026.
//

import Foundation
import CoreBluetooth
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

enum GattMeshSendDisposition {
    case sent
    case queued
}

struct GattMeshSendResult {
    let messageId: String
    let disposition: GattMeshSendDisposition
}

enum GattMeshReceiptType: String, Codable {
    case delivered
    case read
}

enum GattMeshPacketType: String, Codable {
    case chat
    case receipt
    case authChallenge = "auth_challenge"
    case authProof = "auth_proof"
}

struct GattMeshPacket: Codable, Equatable {
    let id: String
    let senderLabel: String
    let timestampMillis: Int64
    let message: String
    let type: GattMeshPacketType
    let receiptType: GattMeshReceiptType?
    let receiptMessageIds: [String]
    let authNonce: String?
    let authProofJSON: String?
    let hop: Int
    let `protocol`: String
    let encrypted: Bool
    let keyId: String?
    let ivBase64: String?
    let cipherBase64: String?
    let originProofJSON: String?
    let originSignatureBase64: String?
    let isReadable: Bool
}

enum GattMeshProtocol {
    static let serviceUUID = CBUUID(string: "6F4B5D5E-2E0A-4F13-9B89-7D9F3F1D1001")
    static let messageInCharacteristicUUID = CBUUID(string: "6F4B5D5E-2E0A-4F13-9B89-7D9F3F1D1002")
    static let messageOutCharacteristicUUID = CBUUID(string: "6F4B5D5E-2E0A-4F13-9B89-7D9F3F1D1003")
    static let clientCharacteristicConfigurationUUID = CBUUID(string: CBUUIDClientCharacteristicConfigurationString)

    static let maxPacketBytes = 4_096
    static let maxForwardHops = 4
    static let maxChatMessageLength = 1_024
    static let maxMessageAgeMillis: Int64 = 24 * 60 * 60 * 1_000
    static let maxFutureClockSkewMillis: Int64 = 2 * 60 * 1_000
    static let maxOriginProofAgeMillis: Int64 = 10 * 60 * 1_000

    private static let protocolChatV1 = "dcs-gattmesh-v1"
    private static let protocolReceiptV2 = "dcs-gattmesh-v2"
    private static let protocolChatV3 = "dcs-gattmesh-v3"
    private static let protocolControlV4 = "dcs-gattmesh-v4"
    private static let encryptedPlaceholderMessage = "mesh-secure"
    private static let receiptPlaceholderMessage = "mesh-receipt"
    private static let authChallengePlaceholderMessage = "c"
    private static let authProofPlaceholderMessage = "p"
    private static let defaultKeyId = "public-v1"
    private static let messageIdPattern = "^[a-zA-Z0-9-]{8,128}$"
    private static let meshPayloadKeySeed = "dcs-gattmesh-public-payload-key-v1"
    private static let aesGcmNonceBytes = 12
    private static let aesGcmTagBytes = 16
    private static let maxReceiptIds = 40
    private static let maxAuthNonceLength = 128
    private static let maxAuthProofJSONLength = 1_800
    private static let maxOriginProofJSONLength = 2_200
    private static let maxOriginSignatureLength = 256

    private static let fieldId = "id"
    private static let fieldSender = "sender"
    private static let fieldTimestamp = "timestamp"
    private static let fieldMessage = "message"
    private static let fieldType = "type"
    private static let fieldReceiptType = "receiptType"
    private static let fieldReceiptIds = "receiptIds"
    private static let fieldAuthNonce = "authNonce"
    private static let fieldAuthProof = "authProof"
    private static let fieldOriginProof = "originProof"
    private static let fieldOriginSignature = "originSignature"
    private static let fieldHop = "hop"
    private static let fieldProtocol = "protocol"
    private static let fieldEncrypted = "encrypted"
    private static let fieldKeyId = "kid"
    private static let fieldIV = "iv"
    private static let fieldCipher = "cipher"

    private static let roleProofPublicKeyField = "devicePublicKey"
    private static let roleProofCertificateField = "certificate"
    private static let roleProofTimestampField = "timestamp"
    private static let roleProofSignatureField = "signature"
    private static let roleProofNonceField = "sessionNonce"
    private static let roleProofAllowExpiredField = "allowExpiredCertificate"
    private static let compactRoleProofPublicKeyField = "pk"
    private static let compactRoleProofCertificateField = "c"
    private static let compactRoleProofTimestampField = "ts"
    private static let compactRoleProofSignatureField = "s"
    private static let compactRoleProofNonceField = "n"
    private static let compactRoleProofAllowExpiredField = "g"

    static func makeChatPacket(text: String, messageId: String? = nil, senderLabel: String? = nil) -> GattMeshPacket? {
        let resolvedText = sanitizeMessage(text)
        guard !resolvedText.isEmpty else { return nil }

        let resolvedMessageId = normalizeMessageId(messageId) ?? UUID().uuidString
        let resolvedSender = sanitizeSenderLabel(senderLabel ?? localSenderLabel())
        let timestampMillis = Int64(Date().timeIntervalSince1970 * 1_000)

        guard let encryptedPayload = encryptChatPayload(
            messageId: resolvedMessageId,
            senderLabel: resolvedSender,
            timestampMillis: timestampMillis,
            message: resolvedText
        ) else {
            return nil
        }

        return GattMeshPacket(
            id: resolvedMessageId,
            senderLabel: resolvedSender,
            timestampMillis: timestampMillis,
            message: resolvedText,
            type: .chat,
            receiptType: nil,
            receiptMessageIds: [],
            authNonce: nil,
            authProofJSON: nil,
            hop: 0,
            protocol: protocolChatV1,
            encrypted: true,
            keyId: encryptedPayload.keyId,
            ivBase64: encryptedPayload.ivBase64,
            cipherBase64: encryptedPayload.cipherBase64,
            originProofJSON: nil,
            originSignatureBase64: nil,
            isReadable: true
        )
    }

    static func withOriginAuthentication(
        packet: GattMeshPacket,
        compactProofJSON: String,
        originSignatureBase64: String
    ) -> GattMeshPacket? {
        guard packet.type == .chat else { return nil }
        let normalizedProof = nonEmpty(compactProofJSON)
        let normalizedSignature = nonEmpty(originSignatureBase64)
        guard let normalizedProof, let normalizedSignature else { return nil }
        guard normalizedProof.count <= maxOriginProofJSONLength, normalizedSignature.count <= maxOriginSignatureLength else {
            return nil
        }

        return GattMeshPacket(
            id: packet.id,
            senderLabel: packet.senderLabel,
            timestampMillis: packet.timestampMillis,
            message: packet.message,
            type: .chat,
            receiptType: nil,
            receiptMessageIds: [],
            authNonce: nil,
            authProofJSON: nil,
            hop: packet.hop,
            protocol: protocolControlV4,
            encrypted: packet.encrypted,
            keyId: packet.keyId,
            ivBase64: packet.ivBase64,
            cipherBase64: packet.cipherBase64,
            originProofJSON: normalizedProof,
            originSignatureBase64: normalizedSignature,
            isReadable: packet.isReadable
        )
    }

    static func makeReceiptPacket(
        type: GattMeshReceiptType,
        messageIds: [String],
        senderLabel: String? = nil
    ) -> GattMeshPacket? {
        let resolvedIds = normalizeMessageIds(messageIds)
        guard !resolvedIds.isEmpty else { return nil }

        return GattMeshPacket(
            id: UUID().uuidString,
            senderLabel: sanitizeSenderLabel(senderLabel ?? localSenderLabel()),
            timestampMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            message: receiptPlaceholderMessage,
            type: .receipt,
            receiptType: type,
            receiptMessageIds: resolvedIds,
            authNonce: nil,
            authProofJSON: nil,
            hop: 0,
            protocol: protocolReceiptV2,
            encrypted: false,
            keyId: nil,
            ivBase64: nil,
            cipherBase64: nil,
            originProofJSON: nil,
            originSignatureBase64: nil,
            isReadable: true
        )
    }

    static func makePeerVerificationChallenge(
        senderLabel: String? = nil,
        nonce: String? = nil
    ) -> GattMeshPacket? {
        let resolvedNonce = nonEmpty(nonce) ?? UUID().uuidString
        guard resolvedNonce.count <= maxAuthNonceLength else { return nil }

        return GattMeshPacket(
            id: UUID().uuidString,
            senderLabel: sanitizeSenderLabel(senderLabel ?? localSenderLabel()),
            timestampMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            message: authChallengePlaceholderMessage,
            type: .authChallenge,
            receiptType: nil,
            receiptMessageIds: [],
            authNonce: resolvedNonce,
            authProofJSON: nil,
            hop: 0,
            protocol: protocolControlV4,
            encrypted: false,
            keyId: nil,
            ivBase64: nil,
            cipherBase64: nil,
            originProofJSON: nil,
            originSignatureBase64: nil,
            isReadable: true
        )
    }

    static func makePeerVerificationProof(
        nonce: String,
        compactProofJSON: String,
        senderLabel: String? = nil
    ) -> GattMeshPacket? {
        let resolvedNonce = nonEmpty(nonce)
        let resolvedProof = nonEmpty(compactProofJSON)
        guard let resolvedNonce, let resolvedProof else { return nil }
        guard resolvedNonce.count <= maxAuthNonceLength, resolvedProof.count <= maxAuthProofJSONLength else {
            return nil
        }

        return GattMeshPacket(
            id: UUID().uuidString,
            senderLabel: sanitizeSenderLabel(senderLabel ?? localSenderLabel()),
            timestampMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            message: authProofPlaceholderMessage,
            type: .authProof,
            receiptType: nil,
            receiptMessageIds: [],
            authNonce: resolvedNonce,
            authProofJSON: resolvedProof,
            hop: 0,
            protocol: protocolControlV4,
            encrypted: false,
            keyId: nil,
            ivBase64: nil,
            cipherBase64: nil,
            originProofJSON: nil,
            originSignatureBase64: nil,
            isReadable: true
        )
    }

    static func encodePacket(_ packet: GattMeshPacket) -> Data? {
        let object = packetJSONObject(packet)
        return try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    static func transportPacket(for packet: GattMeshPacket) -> Data? {
        guard let payload = encodePacket(packet), payload.count <= maxPacketBytes else {
            return nil
        }
        return BleAesGcm.wrapTransportPacket(payload)
    }

    static func unwrapTransportPacket(_ packet: Data) -> Data? {
        return try? BleAesGcm.unwrapTransportPacket(packet, maxPacketSize: maxPacketBytes)
    }

    static func decodePacket(from payload: Data) -> GattMeshPacket? {
        guard let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
            return nil
        }

        guard
            let id = normalizeMessageId(object[fieldId] as? String),
            let senderRaw = object[fieldSender] as? String,
            let timestamp = object[fieldTimestamp] as? NSNumber,
            let protocolValue = nonEmpty(object[fieldProtocol] as? String),
            let typeValue = resolvePacketType(rawType: object[fieldType] as? String, protocolValue: protocolValue)
        else {
            return nil
        }

        let senderLabel = sanitizeSenderLabel(senderRaw)
        let hop = (object[fieldHop] as? NSNumber)?.intValue ?? 0
        guard !senderLabel.isEmpty, hop >= 0, hop <= maxForwardHops, timestamp.int64Value > 0 else {
            return nil
        }

        let originProofJSON = nonEmpty(trimmedStringValue(object[fieldOriginProof]))
        let originSignatureBase64 = nonEmpty(trimmedStringValue(object[fieldOriginSignature]))
        if let originProofJSON, originProofJSON.count > maxOriginProofJSONLength {
            return nil
        }
        if let originSignatureBase64, originSignatureBase64.count > maxOriginSignatureLength {
            return nil
        }

        switch typeValue {
        case .chat:
            let encrypted = (object[fieldEncrypted] as? Bool) == true || protocolValue == protocolChatV3
            if encrypted {
                let keyId = nonEmpty(trimmedStringValue(object[fieldKeyId])) ?? defaultKeyId
                guard
                    let ivBase64 = nonEmpty(trimmedStringValue(object[fieldIV])),
                    let cipherBase64 = nonEmpty(trimmedStringValue(object[fieldCipher]))
                else {
                    return nil
                }

                let decryptedMessage = decryptChatPayload(
                    messageId: id,
                    senderLabel: senderLabel,
                    timestampMillis: timestamp.int64Value,
                    keyId: keyId,
                    ivBase64: ivBase64,
                    cipherBase64: cipherBase64
                )

                return GattMeshPacket(
                    id: id,
                    senderLabel: senderLabel,
                    timestampMillis: timestamp.int64Value,
                    message: decryptedMessage ?? encryptedPlaceholderMessage,
                    type: .chat,
                    receiptType: nil,
                    receiptMessageIds: [],
                    authNonce: nil,
                    authProofJSON: nil,
                    hop: hop,
                    protocol: protocolValue,
                    encrypted: true,
                    keyId: keyId,
                    ivBase64: ivBase64,
                    cipherBase64: cipherBase64,
                    originProofJSON: originProofJSON,
                    originSignatureBase64: originSignatureBase64,
                    isReadable: decryptedMessage != nil
                )
            }

            guard
                protocolValue == protocolChatV1 || protocolValue == protocolChatV3 || protocolValue == protocolControlV4,
                let messageRaw = object[fieldMessage] as? String
            else {
                return nil
            }

            let message = sanitizeMessage(messageRaw)
            guard !message.isEmpty else { return nil }
            return GattMeshPacket(
                id: id,
                senderLabel: senderLabel,
                timestampMillis: timestamp.int64Value,
                message: message,
                type: .chat,
                receiptType: nil,
                receiptMessageIds: [],
                authNonce: nil,
                authProofJSON: nil,
                hop: hop,
                protocol: protocolValue,
                encrypted: false,
                keyId: nil,
                ivBase64: nil,
                cipherBase64: nil,
                originProofJSON: originProofJSON,
                originSignatureBase64: originSignatureBase64,
                isReadable: true
            )

        case .receipt:
            guard
                protocolValue == protocolReceiptV2,
                let receiptTypeRaw = object[fieldReceiptType] as? String,
                let receiptType = GattMeshReceiptType(rawValue: receiptTypeRaw.lowercased())
            else {
                return nil
            }

            let receiptIds = normalizeMessageIds((object[fieldReceiptIds] as? [String]) ?? [])
            guard !receiptIds.isEmpty else { return nil }

            return GattMeshPacket(
                id: id,
                senderLabel: senderLabel,
                timestampMillis: timestamp.int64Value,
                message: receiptPlaceholderMessage,
                type: .receipt,
                receiptType: receiptType,
                receiptMessageIds: receiptIds,
                authNonce: nil,
                authProofJSON: nil,
                hop: hop,
                protocol: protocolValue,
                encrypted: false,
                keyId: nil,
                ivBase64: nil,
                cipherBase64: nil,
                originProofJSON: nil,
                originSignatureBase64: nil,
                isReadable: true
            )

        case .authChallenge:
            guard
                protocolValue == protocolControlV4,
                let authNonce = nonEmpty(trimmedStringValue(object[fieldAuthNonce])),
                authNonce.count <= maxAuthNonceLength
            else {
                return nil
            }

            return GattMeshPacket(
                id: id,
                senderLabel: senderLabel,
                timestampMillis: timestamp.int64Value,
                message: authChallengePlaceholderMessage,
                type: .authChallenge,
                receiptType: nil,
                receiptMessageIds: [],
                authNonce: authNonce,
                authProofJSON: nil,
                hop: hop,
                protocol: protocolValue,
                encrypted: false,
                keyId: nil,
                ivBase64: nil,
                cipherBase64: nil,
                originProofJSON: nil,
                originSignatureBase64: nil,
                isReadable: true
            )

        case .authProof:
            guard
                protocolValue == protocolControlV4,
                let authNonce = nonEmpty(trimmedStringValue(object[fieldAuthNonce])),
                let authProofJSON = nonEmpty(trimmedStringValue(object[fieldAuthProof])),
                authNonce.count <= maxAuthNonceLength,
                authProofJSON.count <= maxAuthProofJSONLength
            else {
                return nil
            }

            return GattMeshPacket(
                id: id,
                senderLabel: senderLabel,
                timestampMillis: timestamp.int64Value,
                message: authProofPlaceholderMessage,
                type: .authProof,
                receiptType: nil,
                receiptMessageIds: [],
                authNonce: authNonce,
                authProofJSON: authProofJSON,
                hop: hop,
                protocol: protocolValue,
                encrypted: false,
                keyId: nil,
                ivBase64: nil,
                cipherBase64: nil,
                originProofJSON: nil,
                originSignatureBase64: nil,
                isReadable: true
            )
        }
    }

    static func localSenderLabel() -> String {
        let preferredName = ProfileMetadataStore.preferredDisplayName()
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { value in
                value.isEmpty ? nil : value
            }
#if canImport(UIKit)
        let deviceName = nonEmpty(UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines))
#else
        let deviceName: String? = nil
#endif

        return sanitizeSenderLabel(preferredName ?? deviceName ?? "Mesh Node")
    }

    static func normalizeMessageId(_ rawValue: String?) -> String? {
        guard let trimmed = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed.range(of: messageIdPattern, options: .regularExpression) != nil else {
            return nil
        }
        return trimmed
    }

    static func sanitizeSenderLabel(_ rawValue: String) -> String {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let collapsed = trimmed.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        let limited = String(collapsed.prefix(48))
        return limited.isEmpty ? "Mesh Node" : limited
    }

    static func sanitizeMessage(_ rawValue: String) -> String {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return String(trimmed.prefix(maxChatMessageLength))
    }

    static func normalizeMessageIds(_ messageIds: [String]) -> [String] {
        var seen = Set<String>()
        var resolved: [String] = []
        resolved.reserveCapacity(min(messageIds.count, maxReceiptIds))

        for messageId in messageIds {
            guard let normalized = normalizeMessageId(messageId), !seen.contains(normalized) else {
                continue
            }
            seen.insert(normalized)
            resolved.append(normalized)
            if resolved.count >= maxReceiptIds {
                break
            }
        }

        return resolved
    }

    static func isTimestampValid(
        _ timestampMillis: Int64,
        nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) -> Bool {
        guard timestampMillis > 0 else { return false }
        guard timestampMillis <= nowMillis + maxFutureClockSkewMillis else { return false }
        guard timestampMillis >= nowMillis - maxMessageAgeMillis else { return false }
        return true
    }

    static func encodeCompactRoleProofPayload(
        proof: RoleProofPayload,
        includeSessionNonce: Bool
    ) -> String? {
        var payload: [String: Any] = [
            compactRoleProofPublicKeyField: proof.devicePublicKey,
            compactRoleProofCertificateField: proof.certificate,
            compactRoleProofTimestampField: proof.timestamp,
            compactRoleProofSignatureField: proof.signature
        ]
        if includeSessionNonce,
           let nonce = nonEmpty(proof.sessionNonce) {
            payload[compactRoleProofNonceField] = nonce
        }
        if proof.allowExpiredCertificate {
            payload[compactRoleProofAllowExpiredField] = true
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    static func decodeCompactRoleProofPayload(
        raw: String,
        fallbackSessionNonce: String? = nil
    ) -> RoleProofPayload? {
        guard let data = nonEmpty(raw)?.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        let publicKey = nonEmpty(trimmedStringValue(object[compactRoleProofPublicKeyField]))
            ?? nonEmpty(trimmedStringValue(object[roleProofPublicKeyField]))
        let certificate = nonEmpty(trimmedStringValue(object[compactRoleProofCertificateField]))
            ?? nonEmpty(trimmedStringValue(object[roleProofCertificateField]))
        let signature = nonEmpty(trimmedStringValue(object[compactRoleProofSignatureField]))
            ?? nonEmpty(trimmedStringValue(object[roleProofSignatureField]))
        let nonce = nonEmpty(trimmedStringValue(object[compactRoleProofNonceField]))
            ?? nonEmpty(trimmedStringValue(object[roleProofNonceField]))
            ?? nonEmpty(fallbackSessionNonce)

        let timestamp: Int64
        switch object[compactRoleProofTimestampField] ?? object[roleProofTimestampField] {
        case let number as NSNumber:
            timestamp = number.int64Value
        case let string as String:
            guard let parsed = Int64(string.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
            timestamp = parsed
        default:
            return nil
        }

        let allowExpiredCertificate: Bool
        if let boolValue = object[compactRoleProofAllowExpiredField] as? Bool {
            allowExpiredCertificate = boolValue
        } else {
            allowExpiredCertificate = (object[roleProofAllowExpiredField] as? Bool) == true
        }

        guard let publicKey, let certificate, let signature, timestamp > 0 else {
            return nil
        }

        return RoleProofPayload(
            devicePublicKey: publicKey,
            certificate: certificate,
            timestamp: timestamp,
            signature: signature,
            sessionNonce: nonce,
            allowExpiredCertificate: allowExpiredCertificate
        )
    }

    static func resolveVerifiedRole(_ proof: RoleProofPayload) -> String? {
        guard let certificateBytes = Data(base64Encoded: proof.certificate, options: [.ignoreUnknownCharacters]),
              let certificate = RoleCertificate.fromStorageBytes(certificateBytes) else {
            return nil
        }
        return certificate.role
    }

    static func verifyOriginSignature(packet: GattMeshPacket, proof: RoleProofPayload) -> Bool {
        guard packet.type == .chat else { return false }
        guard let signatureBase64 = nonEmpty(packet.originSignatureBase64),
              let signatureData = Data(base64Encoded: signatureBase64, options: [.ignoreUnknownCharacters]),
              let keyData = Data(base64Encoded: proof.devicePublicKey, options: [.ignoreUnknownCharacters]),
              let x963 = try? BleKeyDecoder.x963PublicKey(from: keyData),
              let publicKey = try? P256.Signing.PublicKey(x963Representation: x963),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureData) else {
            return false
        }
        return publicKey.isValidSignature(signature, for: buildMessageOriginSignaturePayload(packet: packet))
    }

    static func buildMessageOriginSignaturePayload(packet: GattMeshPacket) -> Data {
        var canonical = ""
        canonical.reserveCapacity(512)
        canonical.append("gattmesh-origin-chat")
        canonical.append("|")
        canonical.append(packet.id)
        canonical.append("|")
        canonical.append(String(packet.timestampMillis))
        canonical.append("|")
        canonical.append(packet.senderLabel)
        canonical.append("|")
        canonical.append(packet.protocol)
        canonical.append("|")
        canonical.append(packet.type.rawValue)
        canonical.append("|")
        canonical.append(packet.encrypted ? "1" : "0")
        canonical.append("|")
        canonical.append(packet.keyId ?? "")
        canonical.append("|")
        if packet.encrypted {
            canonical.append(packet.ivBase64 ?? "")
            canonical.append("|")
            canonical.append(packet.cipherBase64 ?? "")
        } else {
            canonical.append(packet.message)
        }
        return Data(canonical.utf8)
    }

    private static func resolvePacketType(rawType: String?, protocolValue: String) -> GattMeshPacketType? {
        let normalizedType = rawType?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? GattMeshPacketType.chat.rawValue

        switch normalizedType {
        case GattMeshPacketType.receipt.rawValue where protocolValue == protocolReceiptV2:
            return .receipt
        case GattMeshPacketType.authChallenge.rawValue where protocolValue == protocolControlV4:
            return .authChallenge
        case GattMeshPacketType.authProof.rawValue where protocolValue == protocolControlV4:
            return .authProof
        case GattMeshPacketType.chat.rawValue, "":
            return (protocolValue == protocolChatV1 || protocolValue == protocolChatV3 || protocolValue == protocolControlV4) ? .chat : nil
        default:
            return (protocolValue == protocolChatV1 || protocolValue == protocolChatV3 || protocolValue == protocolControlV4) ? .chat : nil
        }
    }

    private static func packetJSONObject(_ packet: GattMeshPacket) -> [String: Any] {
        var object: [String: Any] = [
            fieldId: packet.id,
            fieldSender: packet.senderLabel,
            fieldTimestamp: packet.timestampMillis,
            fieldType: packet.type.rawValue,
            fieldHop: packet.hop,
            fieldProtocol: packet.protocol
        ]

        switch packet.type {
        case .chat:
            if packet.encrypted,
               let ivBase64 = packet.ivBase64,
               let cipherBase64 = packet.cipherBase64 {
                object[fieldEncrypted] = true
                object[fieldKeyId] = packet.keyId ?? defaultKeyId
                object[fieldIV] = ivBase64
                object[fieldCipher] = cipherBase64
                object[fieldMessage] = encryptedPlaceholderMessage
            } else {
                object[fieldMessage] = packet.message
            }
            if let originProofJSON = packet.originProofJSON {
                object[fieldOriginProof] = originProofJSON
            }
            if let originSignatureBase64 = packet.originSignatureBase64 {
                object[fieldOriginSignature] = originSignatureBase64
            }

        case .receipt:
            object[fieldMessage] = packet.message
            object[fieldReceiptType] = packet.receiptType?.rawValue
            object[fieldReceiptIds] = packet.receiptMessageIds

        case .authChallenge:
            object[fieldMessage] = packet.message
            object[fieldAuthNonce] = packet.authNonce

        case .authProof:
            object[fieldMessage] = packet.message
            object[fieldAuthNonce] = packet.authNonce
            object[fieldAuthProof] = packet.authProofJSON
        }

        return object
    }

    private static func encryptChatPayload(
        messageId: String,
        senderLabel: String,
        timestampMillis: Int64,
        message: String
    ) -> (keyId: String, ivBase64: String, cipherBase64: String)? {
        let aad = buildChatAAD(
            messageId: messageId,
            senderLabel: senderLabel,
            timestampMillis: timestampMillis,
            keyId: defaultKeyId
        )

        let nonce = AES.GCM.Nonce()
        guard let sealedBox = try? AES.GCM.seal(
            Data(message.utf8),
            using: payloadKey(),
            nonce: nonce,
            authenticating: aad
        ) else {
            return nil
        }

        let nonceData = sealedBox.nonce.withUnsafeBytes { Data($0) }
        let cipherData = sealedBox.ciphertext + sealedBox.tag

        return (
            keyId: defaultKeyId,
            ivBase64: nonceData.base64EncodedString(),
            cipherBase64: cipherData.base64EncodedString()
        )
    }

    private static func decryptChatPayload(
        messageId: String,
        senderLabel: String,
        timestampMillis: Int64,
        keyId: String,
        ivBase64: String,
        cipherBase64: String
    ) -> String? {
        guard keyId == defaultKeyId else { return nil }
        guard
            let nonceData = Data(base64Encoded: ivBase64, options: [.ignoreUnknownCharacters]),
            nonceData.count == aesGcmNonceBytes,
            let cipherData = Data(base64Encoded: cipherBase64, options: [.ignoreUnknownCharacters]),
            cipherData.count > aesGcmTagBytes
        else {
            return nil
        }

        let aad = buildChatAAD(
            messageId: messageId,
            senderLabel: senderLabel,
            timestampMillis: timestampMillis,
            keyId: keyId
        )

        guard let nonce = try? AES.GCM.Nonce(data: nonceData) else { return nil }
        let ciphertext = cipherData.prefix(cipherData.count - aesGcmTagBytes)
        let tag = cipherData.suffix(aesGcmTagBytes)
        guard let sealedBox = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag) else {
            return nil
        }
        guard let plaintext = try? AES.GCM.open(sealedBox, using: payloadKey(), authenticating: aad) else {
            return nil
        }

        guard let text = String(data: plaintext, encoding: .utf8) else { return nil }
        let sanitized = sanitizeMessage(text)
        return sanitized.isEmpty ? nil : sanitized
    }

    private static func buildChatAAD(
        messageId: String,
        senderLabel: String,
        timestampMillis: Int64,
        keyId: String
    ) -> Data {
        let payload = "id=\(messageId)|sender=\(senderLabel)|ts=\(timestampMillis)|kid=\(keyId)"
        return Data(payload.utf8)
    }

    private static func payloadKey() -> SymmetricKey {
        let bundleIdentifier = nonEmpty(Bundle.main.bundleIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines))
            ?? "com.auralis.crisisconnect"
        let material = Data("\(meshPayloadKeySeed)|\(bundleIdentifier)".utf8)
        let digest = SHA256.hash(data: material)
        return SymmetricKey(data: Data(digest))
    }

    private static func trimmedStringValue(_ value: Any?) -> String? {
        (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }
}
