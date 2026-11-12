import Foundation
import CryptoKit

enum ChatPeerCallSecurity {
    static func proof(key: SymmetricKey, fields: [String]) -> String {
        let data = Data(("cc-multipeer-invite-v3\n" + fields.map { "\($0.utf8.count):\($0)" }.joined()).utf8)
        return Data(HMAC<SHA256>.authenticationCode(for: data, using: key)).base64EncodedString()
    }

    static func verify(proof: String, key: SymmetricKey, fields: [String]) -> Bool {
        guard let mac = Data(base64Encoded: proof), mac.count == 32 else { return false }
        let data = Data(("cc-multipeer-invite-v3\n" + fields.map { "\($0.utf8.count):\($0)" }.joined()).utf8)
        return HMAC<SHA256>.isValidAuthenticationCode(mac, authenticating: data, using: key)
    }

    static func mediaKey(contactKey: SymmetricKey, callId: String, nonce: String, direction: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: contactKey, salt: Data(nonce.utf8),
            info: Data("cc-multipeer-media-v3|\(direction)|\(callId)".utf8), outputByteCount: 32)
    }
    /// Fixed-size keyed discovery filter: no names, contact codes, or permanent public keys.
    static func discoveryInfo(keys: [SymmetricKey], nonce: Data = withUnsafeBytes(of: UUID().uuid) { Data($0) }) -> [String: String] {
        var filter = [UInt8](repeating: 0, count: 128)
        for key in keys {
            for bit in discoveryBits(key: key, nonce: nonce) { filter[bit / 8] |= 1 << (bit % 8) }
        }
        return ["v": "4", "r": nonce.base64EncodedString(), "f": Data(filter).base64EncodedString()]
    }

    static func matchesDiscovery(_ info: [String: String], key: SymmetricKey) -> Bool {
        guard info["v"] == "4", let rawNonce = info["r"], let nonce = Data(base64Encoded: rawNonce), nonce.count == 16,
              let rawFilter = info["f"], let filter = Data(base64Encoded: rawFilter), filter.count == 128 else { return false }
        return discoveryBits(key: key, nonce: nonce).allSatisfy { filter[$0 / 8] & (1 << ($0 % 8)) != 0 }
    }

    private static func discoveryBits(key: SymmetricKey, nonce: Data) -> [Int] {
        let tag = Array(HMAC<SHA256>.authenticationCode(for: Data("cc-call-discovery-v4".utf8) + nonce, using: key))
        return stride(from: 0, to: 6, by: 2).map { (Int(tag[$0]) << 8 | Int(tag[$0 + 1])) % 1024 }
    }

}
