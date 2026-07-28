//
//  AuthorityMeshKeyStore.swift
//  Crisis Connect
//
//  iOS counterpart of the Android `AuthorityMeshKeyStore`.
//

import Foundation

/// Secure storage for the shared "authority mesh" group key.
///
/// The key is provisioned by the backend (`issueAuthorityMeshKey`) to verified admin/fieldteam
/// devices and lets only authorities decrypt/produce authority-mesh traffic. It is stored in the
/// Keychain via ``KeychainStore``. When no key is present the authority mesh must stay off — a
/// civilian device never holds it.
enum AuthorityMeshKeyStore {

    private static let groupKeyKey = "authority_mesh_group_key_v1"
    private static let groupKeyBytes = 32

    /// Returns the 32-byte AES-256 group key, or nil when the device is not provisioned.
    static func loadGroupKey() -> Data? {
        guard let data = KeychainStore.load(groupKeyKey), data.count == groupKeyBytes else {
            return nil
        }
        return data
    }

    static func saveGroupKey(_ keyBytes: Data) {
        guard keyBytes.count == groupKeyBytes else { return }
        KeychainStore.save(keyBytes, key: groupKeyKey)
    }

    static func hasGroupKey() -> Bool { loadGroupKey() != nil }

    static func clearGroupKey() {
        KeychainStore.delete(groupKeyKey)
    }
}
