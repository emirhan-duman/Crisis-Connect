//
//  AuthorityMeshGroupKeyProvisioner.swift
//  Crisis Connect
//
//  iOS counterpart of Android `SecurityRepository.ensureAuthorityMeshGroupKey()`.
//

import Foundation
import FirebaseFunctions
import FirebaseAuth

/// Fetches the shared authority-mesh group key from the backend on first use and caches it in
/// ``AuthorityMeshKeyStore``.
///
/// The backend (`issueAuthorityMeshKeyV2`) only returns the key to verified admin/fieldteam callers,
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
            guard let owner = Auth.auth().currentUser?.uid, !owner.isEmpty else { return false }
            let proof = try await RoleProofCreator(allowExpiredCertificate: false)
                .createPayload(sessionNonce: "cc-authority-mesh-key-v2")
            let request = try JSONSerialization.jsonObject(with: proof.jsonData())
            let result = try await Functions.functions(region: "us-central1")
                .httpsCallable("issueAuthorityMeshKeyV2")
                .call(request)
            guard
                let dict = result.data as? [String: Any],
                let keyBase64 = (dict["keyBase64"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                let bytes = Data(base64Encoded: keyBase64, options: [.ignoreUnknownCharacters]),
                bytes.count == groupKeyBytes,
                let keyId = dict["keyId"] as? String,
                let notBefore = dict["notBeforeMs"] as? NSNumber,
                let expires = dict["expiresAtMs"] as? NSNumber
            else {
                return false
            }
            try AuthorityMeshKeyStore.saveGroupKey(AuthorityMeshKeyLease(keyId: keyId, keyBase64: keyBase64,
                ownerUid: owner, notBeforeMs: notBefore.int64Value, expiresAtMs: expires.int64Value))
            return true
        } catch {
            return false
        }
    }
}
