//
//  AgencyMessageCrypto.swift
//  Crisis Connect
//
//  AES-256-GCM under the agency's shared key for the dashboard's agency-internal messaging,
//  byte-for-byte compatible with the web `lib/messaging/agency.ts` and Android `AgencyMessageCrypto`
//  (12-byte nonce, ciphertext = ct||tag, associated data = the agencySlug). Verified against a
//  web-produced golden vector.
//

import Foundation
import CryptoKit

struct AgencyKey {
    let keyId: String
    let key: SymmetricKey
    let agencySlug: String

    /// Builds an AgencyKey from the base64 key returned by issueAgencyMessagingKey.
    static func from(keyBase64: String, keyId: String, agencySlug: String) -> AgencyKey? {
        guard let raw = Data(base64Encoded: keyBase64) else { return nil }
        return AgencyKey(keyId: keyId, key: SymmetricKey(data: raw), agencySlug: agencySlug)
    }
}

enum AgencyMessageError: Error { case malformed }

enum AgencyMessageCrypto {
    private static let tagBytes = 16

    static func decrypt(agencyKey: AgencyKey, nonceBase64: String, ciphertextBase64: String) throws -> String {
        guard let nonce = Data(base64Encoded: nonceBase64),
              let ctTag = Data(base64Encoded: ciphertextBase64),
              ctTag.count > tagBytes else {
            throw AgencyMessageError.malformed
        }
        let box = try AES.GCM.SealedBox(
            nonce: try AES.GCM.Nonce(data: nonce),
            ciphertext: ctTag.prefix(ctTag.count - tagBytes),
            tag: ctTag.suffix(tagBytes)
        )
        let plaintext = try AES.GCM.open(box, using: agencyKey.key, authenticating: Data(agencyKey.agencySlug.utf8))
        return String(decoding: plaintext, as: UTF8.self)
    }

    /// Returns (nonceBase64, ciphertextBase64).
    static func encrypt(agencyKey: AgencyKey, text: String) throws -> (nonceBase64: String, ciphertextBase64: String) {
        var nonce = Data(count: 12)
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }
        let box = try AES.GCM.seal(
            Data(text.utf8),
            using: agencyKey.key,
            nonce: try AES.GCM.Nonce(data: nonce),
            authenticating: Data(agencyKey.agencySlug.utf8)
        )
        return (nonce.base64EncodedString(), (box.ciphertext + box.tag).base64EncodedString())
    }
}
