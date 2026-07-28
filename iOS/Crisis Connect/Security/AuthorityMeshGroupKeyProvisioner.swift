//
//  AuthorityMeshGroupKeyProvisioner.swift
//  Crisis Connect
//
//  iOS counterpart of Android `SecurityRepository.ensureAuthorityMeshGroupKey()`.
//

import Foundation
import FirebaseFunctions

/// Fetches the shared authority-mesh group key from the backend on first use and caches it in
/// ``AuthorityMeshKeyStore``.
///
/// The backend (`issueAuthorityMeshKey`) only returns the key to verified admin/fieldteam callers,
/// so callers should gate this behind the authority role (`RescueRoleAccess.isAuthorized`). On
/// civilian devices the call fails the role check and the authority mesh simply stays off.
enum AuthorityMeshGroupKeyProvisioner {

    private static let groupKeyBytes = 32

    /// Ensures the device holds the group key, returning whether it is present afterwards.
    @discardableResult
    static func ensureGroupKey() async -> Bool {
        if AuthorityMeshKeyStore.hasGroupKey() {
            return true
        }
        do {
            let result = try await Functions.functions(region: "us-central1")
                .httpsCallable("issueAuthorityMeshKey")
                .call([:])
            guard
                let dict = result.data as? [String: Any],
                let keyBase64 = (dict["keyBase64"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                let bytes = Data(base64Encoded: keyBase64, options: [.ignoreUnknownCharacters]),
                bytes.count == groupKeyBytes
            else {
                return false
            }
            AuthorityMeshKeyStore.saveGroupKey(bytes)
            return true
        } catch {
            return false
        }
    }
}
