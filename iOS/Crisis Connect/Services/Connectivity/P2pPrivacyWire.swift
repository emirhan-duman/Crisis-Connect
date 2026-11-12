import Foundation
import CryptoKit

/// QR secrets protect bootstrap/control metadata; paired packet routing carries no device ID.
enum P2pPrivacyWire {
    static let privateIdentity = Data("private:v2".utf8)
    static let maxControlBytes = 60_000
    enum Purpose: String { case bootstrap = "CPB2", control = "CPC2" }

    static func seal(_ plaintext: Data, key: Data, purpose: Purpose) -> Data? {
        guard key.count == 32, !plaintext.isEmpty, plaintext.count <= maxControlBytes - 32 else { return nil }
        let header = Data(purpose.rawValue.utf8)
        guard let sealed = try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key), authenticating: header),
              let combined = sealed.combined else { return nil }
        return header + combined
    }

    static func open(_ wire: Data, key: Data, purpose: Purpose) -> Data? {
        let header = Data(purpose.rawValue.utf8)
        guard key.count == 32, (33...maxControlBytes).contains(wire.count), wire.prefix(4) == header,
              let box = try? AES.GCM.SealedBox(combined: wire.dropFirst(4)) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: header)
    }

    static func alias(key: Data, packet: Data, salt: Data? = nil) -> String {
        let random = salt ?? withUnsafeBytes(of: UUID().uuid) { Data($0) }
        precondition(random.count == 16)
        let tag = aliasTag(key: key, packet: packet, salt: random)
        return "p2p2." + hex(random) + "." + hex(tag)
    }

    static func matches(_ alias: String, key: Data, packet: Data) -> Bool {
        guard key.count == 32 else { return false }
        let parts = alias.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "p2p2", let salt = unhex(parts[1]), let tag = unhex(parts[2]) else { return false }
        let expected = aliasTag(key: key, packet: packet, salt: salt)
        return zip(tag, expected).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    static func alias(key: SymmetricKey, packet: Data) -> String {
        alias(key: key.withUnsafeBytes { Data($0) }, packet: packet)
    }
    static func matches(_ alias: String, key: SymmetricKey, packet: Data) -> Bool {
        matches(alias, key: key.withUnsafeBytes { Data($0) }, packet: packet)
    }

    private static func aliasTag(key: Data, packet: Data, salt: Data) -> Data {
        let input = Data("cc-p2p-route-v2".utf8) + salt + Data(SHA256.hash(data: packet))
        return Data(HMAC<SHA256>.authenticationCode(for: input, using: SymmetricKey(data: key)).prefix(16))
    }
    private static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    private static func unhex(_ string: Substring) -> Data? {
        guard string.count == 32, string.allSatisfy({ "0123456789abcdef".contains($0) }) else { return nil }
        let bytes = Array(string.utf8)
        return Data(stride(from: 0, to: 32, by: 2).compactMap { UInt8(String(bytes: bytes[$0..<$0+2], encoding: .utf8)!, radix: 16) })
    }

    final class Challenge {
        let nonce = UUID().uuidString
        private let issuedAt: TimeInterval
        private var consumed = false
        init(now: TimeInterval = ProcessInfo.processInfo.systemUptime) { issuedAt = now }
        func accept(_ response: String?, now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Bool {
            guard !consumed, now >= issuedAt, now - issuedAt <= 10, response == nonce else { return false }
            consumed = true
            return true
        }
    }

    /// Control writes may exceed one ATT write. Bound length and lifetime before decryption.
    final class ControlReceiver {
        private var data = Data()
        private var started = ProcessInfo.processInfo.systemUptime
        var isExpired: Bool { ProcessInfo.processInfo.systemUptime - started > 10 }
        func receive(_ chunk: Data) -> Data? {
            let now = ProcessInfo.processInfo.systemUptime
            if now - started > 10 { data.removeAll() }
            if data.isEmpty { started = now }
            guard !chunk.isEmpty, data.count + chunk.count <= maxControlBytes + 2 else { data.removeAll(); return nil }
            data.append(chunk)
            guard data.count >= 2 else { return nil }
            let length = Int(data[0]) << 8 | Int(data[1])
            guard (33...maxControlBytes).contains(length), data.count <= length + 2 else { data.removeAll(); return nil }
            guard data.count == length + 2 else { return nil }
            let result = Data(data.dropFirst(2)); data.removeAll(); return result
        }
    }
    static func frameControl(_ wire: Data) -> Data? {
        guard (33...maxControlBytes).contains(wire.count) else { return nil }
        return Data([UInt8(wire.count >> 8), UInt8(wire.count & 255)]) + wire
    }
}
