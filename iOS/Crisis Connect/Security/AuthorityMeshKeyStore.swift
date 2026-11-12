import Foundation
import FirebaseAuth
import Security

enum AuthorityMeshKeyStore {
    private static let leaseKey = "authority_mesh_group_key_lease_v2"
    private static let leasesKey = "authority_mesh_group_key_leases_v2"
    private static let lock = NSLock()
    private static var cached: [AuthorityMeshKeyLease]?
    private static func read() throws -> [AuthorityMeshKeyLease] {
        if let cached { return cached }
        if let data = try KeychainStore.loadStrict(leasesKey) {
            let leases = try JSONDecoder().decode([AuthorityMeshKeyLease].self, from: data)
            guard leases.count <= 3 else { throw KeychainStore.AccessError.status(errSecDecode) }
            cached = leases
            return leases
        }
        let leases = try KeychainStore.loadStrict(leaseKey).map { [try JSONDecoder().decode(AuthorityMeshKeyLease.self, from: $0)] } ?? []
        cached = leases
        return leases
    }
    static func loadLease(keyId: String? = nil) -> AuthorityMeshKeyLease? {
        lock.lock(); defer { lock.unlock() }
        let owner = Auth.auth().currentUser?.uid ?? ""
        guard !owner.isEmpty else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return (try? read())?.first { lease in
            let usable = keyId.map { lease.keyId == $0 && lease.isReceivable(now: now, owner: owner) }
                ?? lease.isCurrent(now: now, owner: owner)
            return usable && Data(base64Encoded: lease.keyBase64)?.count == 32
        }
    }
    static func loadGroupKey(keyId: String? = nil) -> Data? { loadLease(keyId: keyId).flatMap { Data(base64Encoded: $0.keyBase64) } }
    static func receiveKeys() -> [Data] {
        lock.lock(); defer { lock.unlock() }
        let owner = Auth.auth().currentUser?.uid ?? ""
        guard !owner.isEmpty else { return [] }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return ((try? read()) ?? []).filter { $0.isReceivable(now: now, owner: owner) }.compactMap {
            guard let data = Data(base64Encoded: $0.keyBase64), data.count == 32 else { return nil }
            return data
        }
    }
    static func hasNextLease() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let owner = Auth.auth().currentUser?.uid ?? ""
        guard !owner.isEmpty else { return false }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return ((try? read()) ?? []).contains { $0.isCacheable(now: now, owner: owner) &&
            $0.notBeforeMs / AuthorityMeshKeyLease.periodMillis == now / AuthorityMeshKeyLease.periodMillis + 1 &&
            Data(base64Encoded: $0.keyBase64)?.count == 32 }
    }
    static func saveGroupKey(_ lease: AuthorityMeshKeyLease) throws { try saveGroupKeys([lease]) }
    static func saveGroupKeys(_ leases: [AuthorityMeshKeyLease]) throws {
        lock.lock(); defer { lock.unlock() }
        let owner = Auth.auth().currentUser?.uid ?? ""
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard !owner.isEmpty, !leases.isEmpty, leases.count <= 2,
              leases.contains(where: { $0.isCurrent(now: now, owner: owner) }),
              Set(leases.map(\.keyId)).count == leases.count,
              leases.allSatisfy({ $0.isCacheable(now: now, owner: owner) && Data(base64Encoded: $0.keyBase64)?.count == 32 })
        else { throw KeychainStore.AccessError.status(errSecParam) }
        let previous = ((try? read()) ?? []).filter { $0.isCacheable(now: now, owner: owner) && $0.notBeforeMs < now }
        var merged = Dictionary(previous.map { ($0.keyId, $0) }, uniquingKeysWith: { _, last in last })
        leases.forEach { merged[$0.keyId] = $0 }
        let retained = Array(merged.values.sorted { $0.notBeforeMs < $1.notBeforeMs }.suffix(3))
        try KeychainStore.saveStrict(JSONEncoder().encode(retained), key: leasesKey)
        cached = retained
    }
    static func hasGroupKey() -> Bool { loadGroupKey() != nil }
    static func clearGroupKey() {
        lock.lock(); defer { lock.unlock() }
        cached = nil
        KeychainStore.delete(leasesKey); KeychainStore.delete(leaseKey); KeychainStore.delete("authority_mesh_group_key_v1")
    }
}
