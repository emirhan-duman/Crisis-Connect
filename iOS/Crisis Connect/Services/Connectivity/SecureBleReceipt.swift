import Foundation
import CryptoKit

enum SecureBleReceipt {
    private static let magic = Data("CAR2".utf8)
    static func seal(key: SymmetricKey, payload: Data, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Data? {
        guard key.bitCount == 256, (1...400).contains(payload.count) else { return nil }
        var timestamp = now.bigEndian
        var body = withUnsafeBytes(of: &timestamp) { Data($0) }; body.append(payload)
        guard let encrypted = try? AES.GCM.seal(body, using: key, authenticating: magic).combined else { return nil }
        return magic + encrypted
    }
    static func open(key: SymmetricKey, wire: Data, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Data? {
        guard key.bitCount == 256, (41...440).contains(wire.count), wire.prefix(4) == magic,
              let box = try? AES.GCM.SealedBox(combined: wire.dropFirst(4)),
              let body = try? AES.GCM.open(box, using: key, authenticating: magic), body.count > 8 else { return nil }
        let raw = body.prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let timestamp = Int64(bitPattern: raw)
        guard timestamp >= now - 600_000, timestamp <= now + 120_000 else { return nil }
        return Data(body.dropFirst(8))
    }
}
