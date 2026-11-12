import Foundation
import CryptoKit

enum ContactIdentityBinding {
    static func sign(key: SymmetricKey, uid: String, sessionCode: String, publicKey: String) -> String {
        let payload = "cc-contact-identity-v1\n" + [uid, sessionCode, publicKey]
            .map { "\($0.utf8.count):\($0)" }.joined()
        return Data(HMAC<SHA256>.authenticationCode(for: Data(payload.utf8), using: key)).base64EncodedString()
    }

    static func verify(proof: String, key: SymmetricKey, uid: String, sessionCode: String, publicKey: String) -> Bool {
        guard let supplied = Data(base64Encoded: proof), supplied.count == 32 else { return false }
        let payload = "cc-contact-identity-v1\n" + [uid, sessionCode, publicKey]
            .map { "\($0.utf8.count):\($0)" }.joined()
        return HMAC<SHA256>.isValidAuthenticationCode(supplied, authenticating: Data(payload.utf8), using: key)
    }
}
