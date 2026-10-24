import Foundation
import CryptoKit

/// Public radio IDs are short-lived and unrelated to the durable rescue/certificate identity.
enum RadioPrivacyIdentity {
    static let period: TimeInterval = 15 * 60
    private static let secret = SymmetricKey(size: .bits256)
    struct Snapshot {
        let broadcastId: String
    }
    static var current: Snapshot {
        snapshot(key: secret, epoch: UInt64(ProcessInfo.processInfo.systemUptime / period))
    }
    static func snapshot(key: SymmetricKey, epoch: UInt64) -> Snapshot {
        var value = epoch.bigEndian
        let input = Data("cc-radio-alias-v2".utf8) + withUnsafeBytes(of: &value) { Data($0) }
        let bytes = HMAC<SHA256>.authenticationCode(for: input, using: key).prefix(12)
        return Snapshot(broadcastId: "CC-" + bytes.map { String(format: "%02X", $0) }.joined())
    }
    static func validBroadcastId(_ raw: String) -> String? {
        let value = raw.uppercased()
        guard value.count == 27, value.hasPrefix("CC-"), value.dropFirst(3).allSatisfy({ "0123456789ABCDEF".contains($0) }) else { return nil }
        return value
    }
}
