import Foundation

struct AuthorityMeshKeyLease: Codable {
    static let periodMillis: Int64 = 72 * 60 * 60 * 1000
    let keyId: String
    let keyBase64: String
    let ownerUid: String
    let notBeforeMs: Int64
    let expiresAtMs: Int64

    func isCurrent(now: Int64, owner: String) -> Bool {
        let epoch = now / Self.periodMillis
        return ownerUid == owner && keyId == "authority-v2-\(epoch)" &&
            notBeforeMs == epoch * Self.periodMillis && expiresAtMs > notBeforeMs &&
            expiresAtMs <= (epoch + 1) * Self.periodMillis && now >= notBeforeMs && now < expiresAtMs
    }
}
