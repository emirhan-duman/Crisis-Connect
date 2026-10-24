import Foundation
import FirebaseAuth
import Security

enum AuthorityMeshKeyStore {
    private static let leaseKey = "authority_mesh_group_key_lease_v2"
    private static let lock = NSLock()
    private static var cached: AuthorityMeshKeyLease?
    static func loadLease() -> AuthorityMeshKeyLease? {
        lock.lock(); defer { lock.unlock() }
        let owner = Auth.auth().currentUser?.uid ?? ""
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if cached == nil, let data = try? KeychainStore.loadStrict(leaseKey) {
            cached = try? JSONDecoder().decode(AuthorityMeshKeyLease.self, from: data)
        }
        guard let lease = cached, lease.isCurrent(now: now, owner: owner),
              Data(base64Encoded: lease.keyBase64)?.count == 32 else { return nil }
        return lease
    }
    static func loadGroupKey() -> Data? { loadLease().flatMap { Data(base64Encoded: $0.keyBase64) } }
    static func saveGroupKey(_ lease: AuthorityMeshKeyLease) throws {
        lock.lock(); defer { lock.unlock() }
        guard lease.isCurrent(now: Int64(Date().timeIntervalSince1970 * 1000), owner: Auth.auth().currentUser?.uid ?? ""),
              Data(base64Encoded: lease.keyBase64)?.count == 32 else { throw KeychainStore.AccessError.status(errSecParam) }
        try KeychainStore.saveStrict(JSONEncoder().encode(lease), key: leaseKey)
        cached = lease
    }
    static func hasGroupKey() -> Bool { loadGroupKey() != nil }
    static func clearGroupKey() {
        lock.lock(); defer { lock.unlock() }
        cached = nil
        KeychainStore.delete(leaseKey); KeychainStore.delete("authority_mesh_group_key_v1")
    }
}
