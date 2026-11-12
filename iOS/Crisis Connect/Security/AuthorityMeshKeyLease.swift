import Foundation

struct AuthorityMeshKeyLease: Codable {
    static let periodMillis: Int64 = 72 * 60 * 60 * 1000
    static let overlapMillis: Int64 = 60_000
    let keyId: String
    let keyBase64: String
    let ownerUid: String
    let notBeforeMs: Int64
    let expiresAtMs: Int64
    let receiveUntilMs: Int64

    init(keyId: String, keyBase64: String, ownerUid: String, notBeforeMs: Int64,
         expiresAtMs: Int64, receiveUntilMs: Int64? = nil) {
        self.keyId = keyId; self.keyBase64 = keyBase64; self.ownerUid = ownerUid
        self.notBeforeMs = notBeforeMs; self.expiresAtMs = expiresAtMs
        self.receiveUntilMs = receiveUntilMs ?? expiresAtMs
    }
    private enum CodingKeys: String, CodingKey { case keyId, keyBase64, ownerUid, notBeforeMs, expiresAtMs, receiveUntilMs }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(keyId: try c.decode(String.self, forKey: .keyId),
            keyBase64: try c.decode(String.self, forKey: .keyBase64),
            ownerUid: try c.decode(String.self, forKey: .ownerUid),
            notBeforeMs: try c.decode(Int64.self, forKey: .notBeforeMs),
            expiresAtMs: try c.decode(Int64.self, forKey: .expiresAtMs),
            receiveUntilMs: try c.decodeIfPresent(Int64.self, forKey: .receiveUntilMs))
    }
    private func valid(owner: String) -> Bool {
        guard ownerUid == owner, notBeforeMs >= 0, notBeforeMs % Self.periodMillis == 0,
              notBeforeMs <= Int64.max - Self.periodMillis - Self.overlapMillis else { return false }
        return keyId == "authority-v2-\(notBeforeMs / Self.periodMillis)" && expiresAtMs > notBeforeMs &&
            expiresAtMs <= notBeforeMs + Self.periodMillis && receiveUntilMs >= expiresAtMs &&
            receiveUntilMs <= expiresAtMs + Self.overlapMillis
    }
    func isCurrent(now: Int64, owner: String) -> Bool {
        valid(owner: owner) && now >= notBeforeMs && now < expiresAtMs
    }
    func isReceivable(now: Int64, owner: String) -> Bool {
        valid(owner: owner) && now >= notBeforeMs - Self.overlapMillis && now < receiveUntilMs
    }
    func isCacheable(now: Int64, owner: String) -> Bool {
        valid(owner: owner) && now >= 0 &&
            (now / Self.periodMillis - 1 ... now / Self.periodMillis + 1).contains(notBeforeMs / Self.periodMillis) &&
            now < receiveUntilMs
    }
}
