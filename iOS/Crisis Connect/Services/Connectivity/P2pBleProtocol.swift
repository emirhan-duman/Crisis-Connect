//
//  P2pBleProtocol.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation
import CoreBluetooth
import CryptoKit
import Security

enum P2pBleProtocol {
    static let protocolVersion = 1

    static let typeClientHello = "client_hello"
    static let typeServerHello = "server_hello"
    static let typeClientFinish = "client_finish"
    static let typeServerFinish = "server_finish"
    static let typeError = "error"
    static let typeChatEnvelope = "chat_envelope"

    static let chatKindText = "text"
    static let chatKindRead = "read"
    static let chatKindDelivered = "delivered"
    static let chatKindVoiceInit = "voice_init"
    static let chatKindVoiceChunk = "voice_chunk"
    static let chatKindVoiceDone = "voice_done"
    static let chatKindVoiceAbort = "voice_abort"
    static let chatKindImageInit = "image_init"
    static let chatKindImageChunk = "image_chunk"
    static let chatKindImageDone = "image_done"
    static let chatKindImageAbort = "image_abort"
    static let chatKindFileInit = "file_init"
    static let chatKindFileChunk = "file_chunk"
    static let chatKindFileDone = "file_done"
    static let chatKindFileAbort = "file_abort"
    static let chatKindDecryptFail = "decrypt_fail"
    static let chatKindCallOffer = "call_offer"
    static let chatKindCallRing = "call_ring"
    static let chatKindCallAccept = "call_accept"
    static let chatKindCallReject = "call_reject"
    static let chatKindCallBusy = "call_busy"
    static let chatKindCallEnd = "call_end"
    static let chatKindCallCfg = "call_cfg"
    static let chatKindCallCfgAck = "call_cfg_ack"

    static let voiceChunkSizeBytes = 1536
    static let voiceMaxTotalBytes = 524_288
    static let voiceMaxChunks = 512
    static let imageChunkSizeBytes = 2048
    static let imageMaxTotalBytes = 524_288
    static let imageMaxChunks = 512
    static let fileChunkSizeBytes = 2048
    static let fileMaxTotalBytes = 10 * 1_024 * 1_024
    static let fileMaxChunks = 8_192

    static let serviceUUID = CBUUID(string: "0000CD00-0000-1000-8000-00805F9B34FB")
    static let idCharacteristicUUID = CBUUID(string: "0000CD01-0000-1000-8000-00805F9B34FB")
    static let bootstrapCharacteristicUUID = CBUUID(string: "0000CD02-0000-1000-8000-00805F9B34FB")
    static let controlCharacteristicUUID = CBUUID(string: "0000CD03-0000-1000-8000-00805F9B34FB")
    static let messageInCharacteristicUUID = CBUUID(string: "0000CD04-0000-1000-8000-00805F9B34FB")
    static let messageOutCharacteristicUUID = CBUUID(string: "0000CD05-0000-1000-8000-00805F9B34FB")

    static func normalizeShareId(_ shareId: String) -> String {
        shareId.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    static func buildIdentityValue(shareId: String) -> Data {
        Data("share:\(normalizeShareId(shareId))".utf8)
    }

    static func buildDeviceIdentityValue(deviceId: String) -> Data {
        Data("device:\(deviceId.trimmingCharacters(in: .whitespacesAndNewlines))".utf8)
    }

    static func advertisedShareId(
        from advertisementData: [String: Any],
        peripheralName: String? = nil
    ) -> String? {
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey],
           let shareId = shareId(fromServiceData: serviceData) {
            return shareId
        }

        let candidates = [
            advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            peripheralName
        ]

        for candidate in candidates {
            let trimmed = candidate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !trimmed.isEmpty else { continue }
            guard looksLikeShareId(trimmed) else { continue }
            return trimmed
        }

        return nil
    }

    static func isPotentialContactShareAdvertisement(
        expectedShareId: String,
        advertisementData: [String: Any],
        peripheralName: String? = nil
    ) -> Bool {
        let normalizedExpected = normalizeShareId(expectedShareId)
        guard !normalizedExpected.isEmpty else { return false }

        if let advertisedShareId = advertisedShareId(from: advertisementData) {
            return normalizeShareId(advertisedShareId) == normalizedExpected
        }
        if let peripheralNameShareId = advertisedShareId(from: [:], peripheralName: peripheralName),
           normalizeShareId(peripheralNameShareId) == normalizedExpected {
            return true
        }

        return advertisesService(serviceUUID, in: advertisementData)
    }

    static func advertisesService(_ serviceUUID: CBUUID, in advertisementData: [String: Any]) -> Bool {
        containsServiceUUID(serviceUUID, in: advertisementData[CBAdvertisementDataServiceUUIDsKey]) ||
            containsServiceUUID(serviceUUID, in: advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey]) ||
            containsServiceUUID(serviceUUID, in: advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey]) ||
            serviceDataContains(serviceUUID, in: advertisementData[CBAdvertisementDataServiceDataKey])
    }

    static func randomNonceBase64(length: Int = 16) -> String {
        guard let data = randomBytes(count: length) else { return "" }
        return data.base64EncodedString()
    }

    static func randomShareId(length: Int = 8) -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        guard let bytes = randomBytes(count: max(length, 1)) else {
            return "SHARE001"
        }
        var result = ""
        result.reserveCapacity(length)
        for byte in bytes.prefix(length) {
            result.append(alphabet[Int(byte) % alphabet.count])
        }
        return result
    }

    static func decodeBase64(_ value: String?) -> Data? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return Data(base64Encoded: trimmed, options: [.ignoreUnknownCharacters])
    }

    /// Proof payload for the scanner's internet identity carried in the client-hello. A SEPARATE
    /// HMAC from the main handshake proof so it's fully backward-compatible: peers that omit the
    /// identity keep pairing unchanged, and a peer that supplies it binds it to this session's
    /// nonces so it can't be replayed or tampered by a BLE MITM (who lacks the shared key).
    /// MUST stay byte-identical with Android's equivalent.
    static func buildClientIdentityProofPayload(
        shareId: String,
        serverNonce: String,
        clientNonce: String,
        peerUid: String,
        peerPublicKey: String
    ) -> Data {
        buildProofPayload([
            ("type", "client_identity"),
            ("shareId", shareId),
            ("serverNonce", serverNonce),
            ("clientNonce", clientNonce),
            ("clientPeerUid", peerUid),
            ("clientPeerPublicKey", peerPublicKey)
        ])
    }

    static func buildProofPayload(_ parts: [(String, String)]) -> Data {
        var lines = ["p2p-v\(protocolVersion)"]
        lines.reserveCapacity(parts.count + 1)
        for (key, value) in parts {
            let encoded = Data(value.utf8).base64EncodedString()
            lines.append("\(key)=\(encoded)")
        }
        return Data(lines.joined(separator: "\n").utf8)
    }

    static func hmacBase64(key: Data, payload: Data) -> String? {
        guard !key.isEmpty else { return nil }
        let symmetricKey = SymmetricKey(data: key)
        let digest = HMAC<SHA256>.authenticationCode(for: payload, using: symmetricKey)
        return Data(digest).base64EncodedString()
    }

    static func secureEqualsBase64(_ expected: String?, _ actual: String?) -> Bool {
        guard
            let expectedData = decodeBase64(expected),
            let actualData = decodeBase64(actual),
            expectedData.count == actualData.count
        else {
            return false
        }
        var accumulator: UInt8 = 0
        for index in expectedData.indices {
            accumulator |= expectedData[index] ^ actualData[index]
        }
        return accumulator == 0
    }

    private static func randomBytes(count: Int) -> Data? {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { return nil }
        return data
    }

    private static func shareId(fromServiceData serviceData: Any) -> String? {
        let payload: Data?

        if let typed = serviceData as? [CBUUID: Data] {
            payload = typed[serviceUUID]
        } else if let typed = serviceData as? [String: Data] {
            payload = typed[serviceUUID.uuidString]
        } else if let typed = serviceData as? [AnyHashable: Data] {
            payload = typed.first { key, _ in
                if let uuid = key as? CBUUID {
                    return uuid == serviceUUID
                }
                if let string = key as? String {
                    return string.caseInsensitiveCompare(serviceUUID.uuidString) == .orderedSame
                }
                return false
            }?.value
        } else {
            payload = nil
        }

        guard let payload else { return nil }
        return String(data: payload, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func containsServiceUUID(_ serviceUUID: CBUUID, in value: Any?) -> Bool {
        serviceUUIDs(from: value).contains { candidate in
            serviceUUIDMatches(candidate, serviceUUID)
        }
    }

    private static func serviceUUIDs(from value: Any?) -> [CBUUID] {
        if let uuid = value as? CBUUID {
            return [uuid]
        }
        if let uuids = value as? [CBUUID] {
            return uuids
        }
        if let values = value as? [Any] {
            return values.compactMap { item in
                if let uuid = item as? CBUUID {
                    return uuid
                }
                if let string = item as? String {
                    return CBUUID(string: string.trimmingCharacters(in: .whitespacesAndNewlines))
                }
                return nil
            }
        }
        return []
    }

    private static func serviceDataContains(_ serviceUUID: CBUUID, in serviceData: Any?) -> Bool {
        if let typed = serviceData as? [CBUUID: Data] {
            return typed.keys.contains { serviceUUIDMatches($0, serviceUUID) }
        }
        if let typed = serviceData as? [String: Data] {
            return typed.keys.contains { serviceUUIDStringMatches($0, serviceUUID) }
        }
        if let typed = serviceData as? [AnyHashable: Data] {
            return typed.keys.contains { key in
                if let uuid = key as? CBUUID {
                    return serviceUUIDMatches(uuid, serviceUUID)
                }
                if let string = key as? String {
                    return serviceUUIDStringMatches(string, serviceUUID)
                }
                return false
            }
        }
        return false
    }

    private static func serviceUUIDMatches(_ candidate: CBUUID, _ expected: CBUUID) -> Bool {
        candidate == expected ||
            candidate.uuidString.caseInsensitiveCompare(expected.uuidString) == .orderedSame
    }

    private static func serviceUUIDStringMatches(_ candidate: String, _ expected: CBUUID) -> Bool {
        let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        if trimmed.caseInsensitiveCompare(expected.uuidString) == .orderedSame {
            return true
        }
        return serviceUUIDMatches(CBUUID(string: trimmed), expected)
    }

    private static func looksLikeShareId(_ value: String) -> Bool {
        let normalized = normalizeShareId(value)
        guard (6...16).contains(normalized.count) else { return false }
        return normalized.unicodeScalars.allSatisfy(CharacterSet.alphanumerics.contains)
    }
}

enum BleImagePayload {
    private static let initPrefix = "CC_IMAGE_INIT|"
    private static let chunkPrefix = "CC_IMAGE_CHUNK|"
    private static let donePrefix = "CC_IMAGE_DONE|"
    private static let abortPrefix = "CC_IMAGE_ABORT|"
    private static let maxTotalChunks = 4_096
    private static let maxChunkBase64Length = 16_384
    private static let maxAbortReasonLength = 64
    private static let sha256Bytes = 32

    static let outgoingChunkSizeBytes = 10_240
    static let maxOutgoingTotalBytes = 512 * 1_024

    enum Packet {
        case initPacket(InitPacket)
        case chunk(ChunkPacket)
        case done(DonePacket)
        case abort(AbortPacket)
    }

    struct InitPacket {
        let transferId: String
        let messageId: String
        let mimeType: String
        let width: Int?
        let height: Int?
        let totalBytes: Int
        let totalChunks: Int
        let sha256: Data
    }

    struct ChunkPacket {
        let transferId: String
        let chunkIndex: Int
        let bytes: Data
    }

    struct DonePacket {
        let transferId: String
    }

    struct AbortPacket {
        let transferId: String
        let reason: String?
    }

    struct IncomingTransfer {
        let transferId: String
        let messageId: String
        let mimeType: String
        let width: Int?
        let height: Int?
        let totalBytes: Int
        let totalChunks: Int
        let sha256: Data
        let createdAt: Date = Date()
        var chunks: [Int: Data] = [:]

        mutating func addChunk(index: Int, bytes: Data) -> Bool {
            guard index >= 0, index < totalChunks else { return false }
            if chunks[index] == nil {
                chunks[index] = bytes
            }
            return true
        }

        var isComplete: Bool {
            chunks.count == totalChunks
        }

        func composedData() -> Data? {
            guard isComplete else { return nil }
            var combined = Data()
            combined.reserveCapacity(totalBytes)
            for index in 0..<totalChunks {
                guard let chunk = chunks[index] else { return nil }
                combined.append(chunk)
            }
            return combined.count == totalBytes ? combined : nil
        }
    }

    static func buildPackets(
        transferId: String,
        messageId: String,
        mimeType: String,
        width: Int?,
        height: Int?,
        bytes: Data
    ) -> [String] {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeMessageId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !safeTransferId.isEmpty,
            !safeMessageId.isEmpty,
            !bytes.isEmpty,
            bytes.count <= maxOutgoingTotalBytes
        else {
            return []
        }

        let safeMimeType = mimeType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "image/jpeg"
            : mimeType.trimmingCharacters(in: .whitespacesAndNewlines)
        let totalChunks = max(1, Int(ceil(Double(bytes.count) / Double(outgoingChunkSizeBytes))))
        guard totalChunks <= maxTotalChunks else { return [] }

        let digest = Data(SHA256.hash(data: bytes)).base64EncodedString()
        var packets: [String] = []
        packets.reserveCapacity(totalChunks + 1)
        packets.append(
            [
                initPrefix + safeTransferId,
                safeMessageId,
                safeMimeType,
                String(width ?? 0),
                String(height ?? 0),
                String(bytes.count),
                String(totalChunks),
                digest
            ].joined(separator: "|")
        )

        var offset = 0
        for index in 0..<totalChunks {
            let end = min(offset + outgoingChunkSizeBytes, bytes.count)
            let chunk = bytes.subdata(in: offset..<end).base64EncodedString()
            packets.append("\(chunkPrefix)\(safeTransferId)|\(index)|\(chunk)")
            offset = end
        }

        return packets
    }

    static func parsePacket(_ raw: String) -> Packet? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix(initPrefix) {
            return parseInit(trimmed)
        }
        if trimmed.hasPrefix(chunkPrefix) {
            return parseChunk(trimmed)
        }
        if trimmed.hasPrefix(donePrefix) {
            return parseDone(trimmed)
        }
        if trimmed.hasPrefix(abortPrefix) {
            return parseAbort(trimmed)
        }
        return nil
    }

    static func buildDonePacket(_ transferId: String) -> String {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeTransferId.isEmpty else { return "" }
        return donePrefix + safeTransferId
    }

    static func buildAbortPacket(_ transferId: String, reason: String?) -> String {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeTransferId.isEmpty else { return "" }
        let sanitizedReason = reason?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "|", with: "/")
            .prefix(maxAbortReasonLength) ?? ""
        if sanitizedReason.isEmpty {
            return abortPrefix + safeTransferId
        }
        return "\(abortPrefix)\(safeTransferId)|\(sanitizedReason)"
    }

    private static func parseInit(_ message: String) -> Packet? {
        let fields = message.dropFirst(initPrefix.count).split(separator: "|", omittingEmptySubsequences: false)
        guard fields.count == 8 else { return nil }
        let transferId = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty, !messageId.isEmpty else { return nil }
        let mimeType = String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "image/jpeg"
            : String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            let totalBytes = Int(fields[5]),
            let totalChunks = Int(fields[6]),
            (1...maxOutgoingTotalBytes).contains(totalBytes),
            (1...maxTotalChunks).contains(totalChunks),
            let sha256 = Data(base64Encoded: String(fields[7]), options: [.ignoreUnknownCharacters]),
            sha256.count == sha256Bytes
        else {
            return nil
        }
        let width = Int(fields[3]).flatMap { $0 > 0 ? $0 : nil }
        let height = Int(fields[4]).flatMap { $0 > 0 ? $0 : nil }
        return .initPacket(
            InitPacket(
                transferId: transferId,
                messageId: messageId,
                mimeType: mimeType,
                width: width,
                height: height,
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                sha256: sha256
            )
        )
    }

    private static func parseChunk(_ message: String) -> Packet? {
        let fields = message.dropFirst(chunkPrefix.count).split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false)
        guard fields.count == 3 else { return nil }
        let transferId = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !transferId.isEmpty,
            let chunkIndex = Int(fields[1])
        else {
            return nil
        }
        let payload = String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !payload.isEmpty, payload.count <= maxChunkBase64Length,
              let bytes = Data(base64Encoded: payload, options: [.ignoreUnknownCharacters]) else {
            return nil
        }
        return .chunk(
            ChunkPacket(
                transferId: transferId,
                chunkIndex: chunkIndex,
                bytes: bytes
            )
        )
    }

    private static func parseDone(_ message: String) -> Packet? {
        let transferId = message.dropFirst(donePrefix.count).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty else { return nil }
        return .done(DonePacket(transferId: transferId))
    }

    private static func parseAbort(_ message: String) -> Packet? {
        let fields = message.dropFirst(abortPrefix.count).split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
        guard let first = fields.first else { return nil }
        let transferId = String(first).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty else { return nil }
        let reason: String?
        if fields.count > 1 {
            let trimmedReason = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            reason = trimmedReason.isEmpty ? nil : trimmedReason
        } else {
            reason = nil
        }
        return .abort(AbortPacket(transferId: transferId, reason: reason))
    }
}

enum BleVoicePayload {
    private static let initPrefix = "CC_VOICE_INIT|"
    private static let chunkPrefix = "CC_VOICE_CHUNK|"
    private static let donePrefix = "CC_VOICE_DONE|"
    private static let abortPrefix = "CC_VOICE_ABORT|"
    private static let maxTotalChunks = 4_096
    private static let maxChunkBase64Length = 16_384
    private static let maxAbortReasonLength = 64
    private static let sha256Bytes = 32

    static let outgoingChunkSizeBytes = 8_192
    static let maxOutgoingTotalBytes = 2 * 1_024 * 1_024

    enum Packet {
        case initPacket(InitPacket)
        case chunk(ChunkPacket)
        case done(DonePacket)
        case abort(AbortPacket)
    }

    struct InitPacket {
        let transferId: String
        let messageId: String
        let mimeType: String
        let durationMillis: Int
        let totalBytes: Int
        let totalChunks: Int
        let sha256: Data
    }

    struct ChunkPacket {
        let transferId: String
        let chunkIndex: Int
        let bytes: Data
    }

    struct DonePacket {
        let transferId: String
    }

    struct AbortPacket {
        let transferId: String
        let reason: String?
    }

    struct IncomingTransfer {
        let transferId: String
        let messageId: String
        let mimeType: String
        let durationMillis: Int
        let totalBytes: Int
        let totalChunks: Int
        let sha256: Data
        let createdAt: Date = Date()
        var chunks: [Int: Data] = [:]

        mutating func addChunk(index: Int, bytes: Data) -> Bool {
            guard index >= 0, index < totalChunks else { return false }
            if chunks[index] == nil {
                chunks[index] = bytes
            }
            return true
        }

        var isComplete: Bool {
            chunks.count == totalChunks
        }

        func composedData() -> Data? {
            guard isComplete else { return nil }
            var combined = Data()
            combined.reserveCapacity(totalBytes)
            for index in 0..<totalChunks {
                guard let chunk = chunks[index] else { return nil }
                combined.append(chunk)
            }
            return combined.count == totalBytes ? combined : nil
        }
    }

    static func buildPackets(
        transferId: String,
        messageId: String,
        mimeType: String,
        durationMillis: Int,
        bytes: Data
    ) -> [String] {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeMessageId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !safeTransferId.isEmpty,
            !safeMessageId.isEmpty,
            !bytes.isEmpty,
            bytes.count <= maxOutgoingTotalBytes
        else {
            return []
        }

        let safeMimeType = mimeType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "audio/mp4"
            : mimeType.trimmingCharacters(in: .whitespacesAndNewlines)
        let totalChunks = max(1, Int(ceil(Double(bytes.count) / Double(outgoingChunkSizeBytes))))
        guard totalChunks <= maxTotalChunks else { return [] }

        let digest = Data(SHA256.hash(data: bytes)).base64EncodedString()
        var packets: [String] = []
        packets.reserveCapacity(totalChunks + 2)
        packets.append(
            [
                initPrefix + safeTransferId,
                safeMessageId,
                safeMimeType,
                String(max(0, durationMillis)),
                String(bytes.count),
                String(totalChunks),
                digest
            ].joined(separator: "|")
        )

        var offset = 0
        for index in 0..<totalChunks {
            let end = min(offset + outgoingChunkSizeBytes, bytes.count)
            let chunk = bytes.subdata(in: offset..<end).base64EncodedString()
            packets.append("\(chunkPrefix)\(safeTransferId)|\(index)|\(chunk)")
            offset = end
        }
        packets.append(buildDonePacket(safeTransferId))
        return packets
    }

    static func parsePacket(_ raw: String) -> Packet? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix(initPrefix) {
            return parseInit(trimmed)
        }
        if trimmed.hasPrefix(chunkPrefix) {
            return parseChunk(trimmed)
        }
        if trimmed.hasPrefix(donePrefix) {
            return parseDone(trimmed)
        }
        if trimmed.hasPrefix(abortPrefix) {
            return parseAbort(trimmed)
        }
        return nil
    }

    static func buildDonePacket(_ transferId: String) -> String {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeTransferId.isEmpty else { return "" }
        return donePrefix + safeTransferId
    }

    static func buildAbortPacket(_ transferId: String, reason: String?) -> String {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeTransferId.isEmpty else { return "" }
        let sanitizedReason = reason?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "|", with: "/")
            .prefix(maxAbortReasonLength) ?? ""
        if sanitizedReason.isEmpty {
            return abortPrefix + safeTransferId
        }
        return "\(abortPrefix)\(safeTransferId)|\(sanitizedReason)"
    }

    private static func parseInit(_ message: String) -> Packet? {
        let fields = message.dropFirst(initPrefix.count).split(separator: "|", omittingEmptySubsequences: false)
        guard fields.count == 7 else { return nil }
        let transferId = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty, !messageId.isEmpty else { return nil }
        let mimeType = String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "audio/mp4"
            : String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            let durationMillis = Int(fields[3]),
            let totalBytes = Int(fields[4]),
            let totalChunks = Int(fields[5]),
            (1...maxOutgoingTotalBytes).contains(totalBytes),
            (1...maxTotalChunks).contains(totalChunks),
            let sha256 = Data(base64Encoded: String(fields[6]), options: [.ignoreUnknownCharacters]),
            sha256.count == sha256Bytes
        else {
            return nil
        }
        return .initPacket(
            InitPacket(
                transferId: transferId,
                messageId: messageId,
                mimeType: mimeType,
                durationMillis: max(0, durationMillis),
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                sha256: sha256
            )
        )
    }

    private static func parseChunk(_ message: String) -> Packet? {
        let fields = message.dropFirst(chunkPrefix.count).split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false)
        guard fields.count == 3 else { return nil }
        let transferId = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty, let chunkIndex = Int(fields[1]) else { return nil }
        let payload = String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !payload.isEmpty, payload.count <= maxChunkBase64Length,
              let bytes = Data(base64Encoded: payload, options: [.ignoreUnknownCharacters]) else {
            return nil
        }
        return .chunk(ChunkPacket(transferId: transferId, chunkIndex: chunkIndex, bytes: bytes))
    }

    private static func parseDone(_ message: String) -> Packet? {
        let transferId = message.dropFirst(donePrefix.count).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty else { return nil }
        return .done(DonePacket(transferId: transferId))
    }

    private static func parseAbort(_ message: String) -> Packet? {
        let fields = message.dropFirst(abortPrefix.count).split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
        guard let first = fields.first else { return nil }
        let transferId = String(first).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty else { return nil }
        let reason: String?
        if fields.count > 1 {
            let trimmedReason = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            reason = trimmedReason.isEmpty ? nil : trimmedReason
        } else {
            reason = nil
        }
        return .abort(AbortPacket(transferId: transferId, reason: reason))
    }
}

enum BleFilePayload {
    private static let initPrefix = "CC_FILE_TRANSFER_INIT|"
    private static let chunkPrefix = "CC_FILE_TRANSFER_CHUNK|"
    private static let donePrefix = "CC_FILE_TRANSFER_DONE|"
    private static let abortPrefix = "CC_FILE_TRANSFER_ABORT|"
    private static let maxTotalChunks = 4_096
    private static let maxChunkBase64Length = 16_384
    private static let maxAbortReasonLength = 64
    private static let sha256Bytes = 32

    static let outgoingChunkSizeBytes = 8_192
    static let maxOutgoingTotalBytes = 10 * 1_024 * 1_024

    enum Packet {
        case initPacket(InitPacket)
        case chunk(ChunkPacket)
        case done(DonePacket)
        case abort(AbortPacket)
    }

    struct InitPacket {
        let transferId: String
        let messageId: String
        let displayName: String
        let mimeType: String?
        let originalSizeBytes: Int
        let totalBytes: Int
        let totalChunks: Int
        let sha256: Data
    }

    struct ChunkPacket {
        let transferId: String
        let chunkIndex: Int
        let bytes: Data
    }

    struct DonePacket {
        let transferId: String
    }

    struct AbortPacket {
        let transferId: String
        let reason: String?
    }

    struct IncomingTransfer {
        let transferId: String
        let messageId: String
        let displayName: String
        let mimeType: String?
        let originalSizeBytes: Int
        let totalBytes: Int
        let totalChunks: Int
        let sha256: Data
        let createdAt: Date = Date()
        var chunks: [Int: Data] = [:]

        mutating func addChunk(index: Int, bytes: Data) -> Bool {
            guard index >= 0, index < totalChunks else { return false }
            if chunks[index] == nil {
                chunks[index] = bytes
            }
            return true
        }

        var isComplete: Bool {
            chunks.count == totalChunks
        }

        func composedData() -> Data? {
            guard isComplete else { return nil }
            var combined = Data()
            combined.reserveCapacity(totalBytes)
            for index in 0..<totalChunks {
                guard let chunk = chunks[index] else { return nil }
                combined.append(chunk)
            }
            return combined.count == totalBytes ? combined : nil
        }
    }

    static func buildPackets(
        transferId: String,
        messageId: String,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Int,
        bytes: Data
    ) -> [String] {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeMessageId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeDisplayName = displayName
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "|", with: "_")
        guard
            !safeTransferId.isEmpty,
            !safeMessageId.isEmpty,
            !safeDisplayName.isEmpty,
            !bytes.isEmpty,
            bytes.count <= maxOutgoingTotalBytes
        else {
            return []
        }

        let trimmedMimeType = mimeType?.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeMimeType = (trimmedMimeType?.isEmpty == false) ? trimmedMimeType : nil
        let totalChunks = max(1, Int(ceil(Double(bytes.count) / Double(outgoingChunkSizeBytes))))
        guard totalChunks <= maxTotalChunks else { return [] }

        let digest = Data(SHA256.hash(data: bytes)).base64EncodedString()
        var packets: [String] = []
        packets.reserveCapacity(totalChunks + 2)
        let encodedDisplayName = Data(safeDisplayName.utf8).base64EncodedString()
        let encodedMimeType = safeMimeType.flatMap { Data($0.utf8).base64EncodedString() } ?? "-"
        let initFields = [
            initPrefix + safeTransferId,
            safeMessageId,
            encodedDisplayName,
            encodedMimeType,
            String(max(1, originalSizeBytes)),
            String(bytes.count),
            String(totalChunks),
            digest
        ]
        packets.append(initFields.joined(separator: "|"))

        var offset = 0
        for index in 0..<totalChunks {
            let end = min(offset + outgoingChunkSizeBytes, bytes.count)
            let chunk = bytes.subdata(in: offset..<end).base64EncodedString()
            packets.append("\(chunkPrefix)\(safeTransferId)|\(index)|\(chunk)")
            offset = end
        }
        packets.append(buildDonePacket(safeTransferId))
        return packets
    }

    static func parsePacket(_ raw: String) -> Packet? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix(initPrefix) {
            return parseInit(trimmed)
        }
        if trimmed.hasPrefix(chunkPrefix) {
            return parseChunk(trimmed)
        }
        if trimmed.hasPrefix(donePrefix) {
            return parseDone(trimmed)
        }
        if trimmed.hasPrefix(abortPrefix) {
            return parseAbort(trimmed)
        }
        return nil
    }

    static func buildDonePacket(_ transferId: String) -> String {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeTransferId.isEmpty else { return "" }
        return donePrefix + safeTransferId
    }

    static func buildAbortPacket(_ transferId: String, reason: String?) -> String {
        let safeTransferId = transferId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeTransferId.isEmpty else { return "" }
        let sanitizedReason = reason?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "|", with: "/")
            .prefix(maxAbortReasonLength) ?? ""
        if sanitizedReason.isEmpty {
            return abortPrefix + safeTransferId
        }
        return "\(abortPrefix)\(safeTransferId)|\(sanitizedReason)"
    }

    private static func parseInit(_ message: String) -> Packet? {
        let fields = message.dropFirst(initPrefix.count).split(separator: "|", omittingEmptySubsequences: false)
        guard fields.count == 8 else { return nil }
        let transferId = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty, !messageId.isEmpty else { return nil }
        guard
            let displayNameData = Data(base64Encoded: String(fields[2]), options: [.ignoreUnknownCharacters]),
            let decodedDisplayName = String(data: displayNameData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
        else {
            return nil
        }
        let displayName = decodedDisplayName.isEmpty ? nil : decodedDisplayName
        guard let displayName else { return nil }
        let mimeType: String?
        let rawMimeType = String(fields[3]).trimmingCharacters(in: .whitespacesAndNewlines)
        if rawMimeType == "-" {
            mimeType = nil
        } else if
            let mimeData = Data(base64Encoded: rawMimeType, options: [.ignoreUnknownCharacters]),
            let decodedMimeType = String(data: mimeData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
            !decodedMimeType.isEmpty {
            mimeType = decodedMimeType
        } else {
            mimeType = nil
        }
        guard
            let originalSizeBytes = Int(fields[4]),
            let totalBytes = Int(fields[5]),
            let totalChunks = Int(fields[6]),
            originalSizeBytes > 0,
            (1...maxOutgoingTotalBytes).contains(totalBytes),
            (1...maxTotalChunks).contains(totalChunks),
            let sha256 = Data(base64Encoded: String(fields[7]), options: [.ignoreUnknownCharacters]),
            sha256.count == sha256Bytes
        else {
            return nil
        }
        return .initPacket(
            InitPacket(
                transferId: transferId,
                messageId: messageId,
                displayName: displayName,
                mimeType: mimeType,
                originalSizeBytes: originalSizeBytes,
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                sha256: sha256
            )
        )
    }

    private static func parseChunk(_ message: String) -> Packet? {
        let fields = message.dropFirst(chunkPrefix.count).split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false)
        guard fields.count == 3 else { return nil }
        let transferId = String(fields[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty, let chunkIndex = Int(fields[1]) else { return nil }
        let payload = String(fields[2]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !payload.isEmpty, payload.count <= maxChunkBase64Length,
              let bytes = Data(base64Encoded: payload, options: [.ignoreUnknownCharacters]) else {
            return nil
        }
        return .chunk(ChunkPacket(transferId: transferId, chunkIndex: chunkIndex, bytes: bytes))
    }

    private static func parseDone(_ message: String) -> Packet? {
        let transferId = message.dropFirst(donePrefix.count).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty else { return nil }
        return .done(DonePacket(transferId: transferId))
    }

    private static func parseAbort(_ message: String) -> Packet? {
        let fields = message.dropFirst(abortPrefix.count).split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
        guard let first = fields.first else { return nil }
        let transferId = String(first).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !transferId.isEmpty else { return nil }
        let reason: String?
        if fields.count > 1 {
            let trimmedReason = String(fields[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            reason = trimmedReason.isEmpty ? nil : trimmedReason
        } else {
            reason = nil
        }
        return .abort(AbortPacket(transferId: transferId, reason: reason))
    }
}
