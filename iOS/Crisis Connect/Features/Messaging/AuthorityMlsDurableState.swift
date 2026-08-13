import Foundation

struct AuthorityMlsPendingApplication: Equatable, Sendable {
    let messageId: String
    let ciphertext: String
}

struct AuthorityMlsPendingReceivedApplication: Sendable {
    let messageId: String
    let senderCredential: String
    let plaintext: Data
}

/// Same-MLS application already opened from Bluetooth, awaiting its authoritative cloud copy.
struct AuthorityMlsOfflineReceipt: Equatable, Sendable {
    let messageId: String
    let senderCredential: String
    let ciphertextHash: String
}

struct AuthorityMlsDurableState: Sendable {
    let snapshot: Data
    let nextControlSequence: Int64
    let nextApplicationSequence: Int64
    let pendingControlEvents: [String]
    let pendingApplicationMessages: [AuthorityMlsPendingApplication]
    let pendingReceivedApplications: [AuthorityMlsPendingReceivedApplication]
    let offlineReceipts: [AuthorityMlsOfflineReceipt]
}

enum AuthorityMlsDurableStateError: Error {
    case malformed
    case tooLarge
}

/// Versioned envelope binding OpenMLS, relay cursor and both crash-safe outboxes.
enum AuthorityMlsDurableStateCodec {
    private static let magicPrefix: [UInt8] = [0x43, 0x43, 0x4d, 0x4c, 0x53, 0x32, 0x00]
    private static let v1: UInt8 = 1
    private static let v2: UInt8 = 2
    private static let v3: UInt8 = 3
    private static let v4: UInt8 = 4
    private static let v1HeaderBytes = 24
    private static let v2HeaderBytes = 28
    private static let v3HeaderBytes = 40
    private static let v4HeaderBytes = 44
    private static let maxMlsSnapshotBytes = 16 * 1024 * 1024
    private static let maxControlEventBytes = 256 * 1024
    private static let maxPendingControlEvents = 32
    private static let maxPendingControlBytes = 4 * 1024 * 1024
    private static let maxPendingApplicationMessages = 64
    private static let maxPendingApplicationBytes = 4 * 1024 * 1024
    private static let maxMessageIdBytes = 128
    private static let maxCiphertextBytes = 900_000
    private static let maxPendingReceivedApplications = 64
    private static let maxPendingReceivedBytes = 4 * 1024 * 1024
    private static let maxSenderCredentialBytes = 512
    private static let maxPlaintextBytes = 900_000
    private static let maxOfflineReceipts = 256
    private static let maxOfflineReceiptBytes = 256 * 1024
    private static let ciphertextHashBytes = 43
    private static let maxSafeSequence: Int64 = 9_007_199_254_740_991
    static let maxDurableStateBytes =
        v4HeaderBytes + maxMlsSnapshotBytes + maxPendingControlBytes +
            maxPendingApplicationBytes + maxPendingReceivedBytes + maxOfflineReceiptBytes

    static func encode(_ state: AuthorityMlsDurableState) throws -> Data {
        guard !state.snapshot.isEmpty,
              state.snapshot.count <= maxMlsSnapshotBytes,
              (0...maxSafeSequence).contains(state.nextControlSequence),
              (0...maxSafeSequence).contains(state.nextApplicationSequence),
              state.pendingControlEvents.count <= maxPendingControlEvents,
              state.pendingApplicationMessages.count <= maxPendingApplicationMessages else {
            throw AuthorityMlsDurableStateError.malformed
        }
        var controlBytes = 0
        let events = try state.pendingControlEvents.map { event -> Data in
            let encoded = Data(event.utf8)
            guard !encoded.isEmpty, encoded.count <= maxControlEventBytes else {
                throw AuthorityMlsDurableStateError.tooLarge
            }
            controlBytes += 4 + encoded.count
            guard controlBytes <= maxPendingControlBytes else { throw AuthorityMlsDurableStateError.tooLarge }
            return encoded
        }
        var applicationBytes = 0
        let messages = try state.pendingApplicationMessages.map { message -> (Data, Data) in
            let messageId = Data(message.messageId.utf8)
            let ciphertext = Data(message.ciphertext.utf8)
            guard !messageId.isEmpty,
                  messageId.count <= maxMessageIdBytes,
                  isBase64url(message.messageId),
                  !ciphertext.isEmpty,
                  ciphertext.count <= maxCiphertextBytes,
                  isBase64url(message.ciphertext) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            applicationBytes += 8 + messageId.count + ciphertext.count
            guard applicationBytes <= maxPendingApplicationBytes else {
                throw AuthorityMlsDurableStateError.tooLarge
            }
            return (messageId, ciphertext)
        }
        guard state.pendingReceivedApplications.count <= maxPendingReceivedApplications else {
            throw AuthorityMlsDurableStateError.tooLarge
        }
        var receivedBytes = 0
        let received = try state.pendingReceivedApplications.map { message -> (Data, Data, Data) in
            let messageId = Data(message.messageId.utf8)
            let sender = Data(message.senderCredential.utf8)
            guard !messageId.isEmpty, messageId.count <= maxMessageIdBytes, isBase64url(message.messageId),
                  !sender.isEmpty, sender.count <= maxSenderCredentialBytes,
                  !message.plaintext.isEmpty, message.plaintext.count <= maxPlaintextBytes else {
                throw AuthorityMlsDurableStateError.malformed
            }
            receivedBytes += 12 + messageId.count + sender.count + message.plaintext.count
            guard receivedBytes <= maxPendingReceivedBytes else { throw AuthorityMlsDurableStateError.tooLarge }
            return (messageId, sender, message.plaintext)
        }
        guard state.offlineReceipts.count <= maxOfflineReceipts else {
            throw AuthorityMlsDurableStateError.tooLarge
        }
        var receiptBytes = 0
        let receipts = try state.offlineReceipts.map { receipt -> (Data, Data, Data) in
            let messageId = Data(receipt.messageId.utf8)
            let sender = Data(receipt.senderCredential.utf8)
            let hash = Data(receipt.ciphertextHash.utf8)
            guard !messageId.isEmpty, messageId.count <= maxMessageIdBytes,
                  isBase64url(receipt.messageId), !sender.isEmpty,
                  sender.count <= maxSenderCredentialBytes, hash.count == ciphertextHashBytes,
                  isBase64url(receipt.ciphertextHash) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            receiptBytes += 12 + messageId.count + sender.count + hash.count
            guard receiptBytes <= maxOfflineReceiptBytes else {
                throw AuthorityMlsDurableStateError.tooLarge
            }
            return (messageId, sender, hash)
        }
        let total = v4HeaderBytes + state.snapshot.count + controlBytes + applicationBytes +
            receivedBytes + receiptBytes
        guard total <= maxDurableStateBytes else { throw AuthorityMlsDurableStateError.tooLarge }
        var output = Data(capacity: total)
        output.append(contentsOf: magicPrefix)
        output.append(v4)
        appendUInt64(UInt64(state.nextControlSequence), to: &output)
        appendUInt64(UInt64(state.nextApplicationSequence), to: &output)
        appendUInt32(UInt32(state.snapshot.count), to: &output)
        appendUInt32(UInt32(events.count), to: &output)
        appendUInt32(UInt32(messages.count), to: &output)
        appendUInt32(UInt32(received.count), to: &output)
        appendUInt32(UInt32(receipts.count), to: &output)
        output.append(state.snapshot)
        for event in events {
            appendUInt32(UInt32(event.count), to: &output)
            output.append(event)
        }
        for (messageId, ciphertext) in messages {
            appendUInt32(UInt32(messageId.count), to: &output)
            appendUInt32(UInt32(ciphertext.count), to: &output)
            output.append(messageId)
            output.append(ciphertext)
        }
        for (messageId, sender, plaintext) in received {
            appendUInt32(UInt32(messageId.count), to: &output)
            appendUInt32(UInt32(sender.count), to: &output)
            appendUInt32(UInt32(plaintext.count), to: &output)
            output.append(messageId)
            output.append(sender)
            output.append(plaintext)
        }
        for (messageId, sender, hash) in receipts {
            appendUInt32(UInt32(messageId.count), to: &output)
            appendUInt32(UInt32(sender.count), to: &output)
            appendUInt32(UInt32(hash.count), to: &output)
            output.append(messageId)
            output.append(sender)
            output.append(hash)
        }
        return output
    }

    static func decode(_ encoded: Data) throws -> AuthorityMlsDurableState {
        guard encoded.count >= v1HeaderBytes, encoded.count <= maxDurableStateBytes else {
            throw AuthorityMlsDurableStateError.malformed
        }
        let bytes = [UInt8](encoded)
        guard Array(bytes.prefix(magicPrefix.count)) == magicPrefix,
              bytes.count > magicPrefix.count,
              bytes[magicPrefix.count] == v1 || bytes[magicPrefix.count] == v2 ||
                bytes[magicPrefix.count] == v3 || bytes[magicPrefix.count] == v4 else {
            throw AuthorityMlsDurableStateError.malformed
        }
        let version = bytes[magicPrefix.count]
        let headerBytes = version == v1 ? v1HeaderBytes
            : version == v2 ? v2HeaderBytes : version == v3 ? v3HeaderBytes : v4HeaderBytes
        guard bytes.count >= headerBytes else { throw AuthorityMlsDurableStateError.malformed }
        var offset = magicPrefix.count + 1
        let rawSequence = try readUInt64(bytes, offset: &offset)
        let rawApplicationSequence = version == v3 || version == v4
            ? try readUInt64(bytes, offset: &offset) : 0
        guard rawSequence <= UInt64(maxSafeSequence) else { throw AuthorityMlsDurableStateError.malformed }
        guard rawApplicationSequence <= UInt64(maxSafeSequence) else { throw AuthorityMlsDurableStateError.malformed }
        let snapshotLength = Int(try readUInt32(bytes, offset: &offset))
        let eventCount = Int(try readUInt32(bytes, offset: &offset))
        let applicationCount = version == v1 ? 0 : Int(try readUInt32(bytes, offset: &offset))
        let receivedCount = version == v3 || version == v4
            ? Int(try readUInt32(bytes, offset: &offset)) : 0
        let receiptCount = version == v4 ? Int(try readUInt32(bytes, offset: &offset)) : 0
        guard snapshotLength > 0,
              snapshotLength <= maxMlsSnapshotBytes,
              eventCount <= maxPendingControlEvents,
              applicationCount <= maxPendingApplicationMessages,
              receivedCount <= maxPendingReceivedApplications,
              receiptCount <= maxOfflineReceipts,
              snapshotLength <= bytes.count - offset else {
            throw AuthorityMlsDurableStateError.malformed
        }
        let snapshot = Data(bytes[offset..<(offset + snapshotLength)])
        offset += snapshotLength
        var controlBytes = 0
        var events: [String] = []
        events.reserveCapacity(eventCount)
        for _ in 0..<eventCount {
            let length = Int(try readUInt32(bytes, offset: &offset))
            controlBytes += 4 + length
            guard length > 0,
                  length <= maxControlEventBytes,
                  controlBytes <= maxPendingControlBytes,
                  length <= bytes.count - offset,
                  let event = String(bytes: bytes[offset..<(offset + length)], encoding: .utf8) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            events.append(event)
            offset += length
        }
        var applicationBytes = 0
        var messages: [AuthorityMlsPendingApplication] = []
        messages.reserveCapacity(applicationCount)
        for _ in 0..<applicationCount {
            let messageIdLength = Int(try readUInt32(bytes, offset: &offset))
            let ciphertextLength = Int(try readUInt32(bytes, offset: &offset))
            applicationBytes += 8 + messageIdLength + ciphertextLength
            guard messageIdLength > 0,
                  messageIdLength <= maxMessageIdBytes,
                  ciphertextLength > 0,
                  ciphertextLength <= maxCiphertextBytes,
                  applicationBytes <= maxPendingApplicationBytes,
                  messageIdLength + ciphertextLength <= bytes.count - offset,
                  let messageId = String(bytes: bytes[offset..<(offset + messageIdLength)], encoding: .utf8) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            offset += messageIdLength
            guard let ciphertext = String(bytes: bytes[offset..<(offset + ciphertextLength)], encoding: .utf8),
                  isBase64url(messageId), isBase64url(ciphertext) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            offset += ciphertextLength
            messages.append(AuthorityMlsPendingApplication(messageId: messageId, ciphertext: ciphertext))
        }
        var receivedBytes = 0
        var received: [AuthorityMlsPendingReceivedApplication] = []
        received.reserveCapacity(receivedCount)
        for _ in 0..<receivedCount {
            let messageIdLength = Int(try readUInt32(bytes, offset: &offset))
            let senderLength = Int(try readUInt32(bytes, offset: &offset))
            let plaintextLength = Int(try readUInt32(bytes, offset: &offset))
            receivedBytes += 12 + messageIdLength + senderLength + plaintextLength
            guard messageIdLength > 0, messageIdLength <= maxMessageIdBytes,
                  senderLength > 0, senderLength <= maxSenderCredentialBytes,
                  plaintextLength > 0, plaintextLength <= maxPlaintextBytes,
                  receivedBytes <= maxPendingReceivedBytes,
                  messageIdLength + senderLength + plaintextLength <= bytes.count - offset,
                  let messageId = String(bytes: bytes[offset..<(offset + messageIdLength)], encoding: .utf8) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            offset += messageIdLength
            guard let sender = String(bytes: bytes[offset..<(offset + senderLength)], encoding: .utf8),
                  isBase64url(messageId) else { throw AuthorityMlsDurableStateError.malformed }
            offset += senderLength
            let plaintext = Data(bytes[offset..<(offset + plaintextLength)])
            offset += plaintextLength
            received.append(AuthorityMlsPendingReceivedApplication(
                messageId: messageId,
                senderCredential: sender,
                plaintext: plaintext
            ))
        }
        var receiptBytes = 0
        var receipts: [AuthorityMlsOfflineReceipt] = []
        receipts.reserveCapacity(receiptCount)
        for _ in 0..<receiptCount {
            let messageIdLength = Int(try readUInt32(bytes, offset: &offset))
            let senderLength = Int(try readUInt32(bytes, offset: &offset))
            let hashLength = Int(try readUInt32(bytes, offset: &offset))
            receiptBytes += 12 + messageIdLength + senderLength + hashLength
            guard messageIdLength > 0, messageIdLength <= maxMessageIdBytes,
                  senderLength > 0, senderLength <= maxSenderCredentialBytes,
                  hashLength == ciphertextHashBytes, receiptBytes <= maxOfflineReceiptBytes,
                  messageIdLength + senderLength + hashLength <= bytes.count - offset,
                  let messageId = String(bytes: bytes[offset..<(offset + messageIdLength)], encoding: .utf8)
            else { throw AuthorityMlsDurableStateError.malformed }
            offset += messageIdLength
            guard let sender = String(bytes: bytes[offset..<(offset + senderLength)], encoding: .utf8)
            else { throw AuthorityMlsDurableStateError.malformed }
            offset += senderLength
            guard let hash = String(bytes: bytes[offset..<(offset + hashLength)], encoding: .utf8),
                  isBase64url(messageId), isBase64url(hash) else {
                throw AuthorityMlsDurableStateError.malformed
            }
            offset += hashLength
            receipts.append(AuthorityMlsOfflineReceipt(
                messageId: messageId, senderCredential: sender, ciphertextHash: hash
            ))
        }
        guard offset == bytes.count else { throw AuthorityMlsDurableStateError.malformed }
        return AuthorityMlsDurableState(
            snapshot: snapshot,
            nextControlSequence: Int64(rawSequence),
            nextApplicationSequence: Int64(rawApplicationSequence),
            pendingControlEvents: events,
            pendingApplicationMessages: messages,
            pendingReceivedApplications: received,
            offlineReceipts: receipts
        )
    }

    private static func isBase64url(_ value: String) -> Bool {
        !value.isEmpty && value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil
    }

    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        data.append(UInt8((value >> 24) & 0xff))
        data.append(UInt8((value >> 16) & 0xff))
        data.append(UInt8((value >> 8) & 0xff))
        data.append(UInt8(value & 0xff))
    }

    private static func appendUInt64(_ value: UInt64, to data: inout Data) {
        for shift in stride(from: 56, through: 0, by: -8) {
            data.append(UInt8((value >> UInt64(shift)) & 0xff))
        }
    }

    private static func readUInt32(_ bytes: [UInt8], offset: inout Int) throws -> UInt32 {
        guard offset + 4 <= bytes.count else { throw AuthorityMlsDurableStateError.malformed }
        let value = (UInt32(bytes[offset]) << 24) |
            (UInt32(bytes[offset + 1]) << 16) |
            (UInt32(bytes[offset + 2]) << 8) |
            UInt32(bytes[offset + 3])
        offset += 4
        return value
    }

    private static func readUInt64(_ bytes: [UInt8], offset: inout Int) throws -> UInt64 {
        guard offset + 8 <= bytes.count else { throw AuthorityMlsDurableStateError.malformed }
        var value: UInt64 = 0
        for index in offset..<(offset + 8) { value = (value << 8) | UInt64(bytes[index]) }
        offset += 8
        return value
    }
}
