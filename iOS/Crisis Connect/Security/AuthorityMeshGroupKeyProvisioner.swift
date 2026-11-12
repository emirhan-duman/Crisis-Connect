//
//  AuthorityMeshGroupKeyProvisioner.swift
//  Crisis Connect
//
//  iOS counterpart of Android `SecurityRepository.ensureAuthorityMeshGroupKey()`.
//

import Foundation
import FirebaseFunctions
import FirebaseAuth

enum AuthorityMeshKeyAccess: Equatable {
    case available, signInRequired, certificateRequired, notAuthorized
    case notEnabled, serviceUnavailable, networkError, failed

    var errorMessageKey: String? {
        switch self {
        case .available: return nil
        case .signInRequired: return "RESCUE_MESH_KEY_SIGN_IN_REQUIRED"
        case .certificateRequired: return "RESCUE_MESH_KEY_CERTIFICATE_REQUIRED"
        case .notAuthorized: return "RESCUE_MESH_KEY_UNAUTHORIZED"
        case .notEnabled: return "RESCUE_MESH_KEY_NOT_ENABLED"
        case .serviceUnavailable: return "RESCUE_MESH_KEY_SERVICE_UNAVAILABLE"
        case .networkError: return "RESCUE_MESH_KEY_NETWORK_ERROR"
        case .failed: return "RESCUE_MESH_KEY_FETCH_FAILED"
        }
    }

    static func failure(_ error: Error) -> Self {
        if error is MissingRoleCertificateError { return .certificateRequired }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain { return .networkError }
        guard ns.domain == FunctionsErrorDomain,
              let code = FunctionsErrorCode(rawValue: ns.code) else { return .failed }
        switch code {
        case .notFound, .unimplemented: return .serviceUnavailable
        case .permissionDenied: return .notAuthorized
        case .unauthenticated: return .signInRequired
        case .failedPrecondition: return .notEnabled
        case .unavailable, .deadlineExceeded: return .networkError
        default: return .failed
        }
    }
}

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
        await requestGroupKey() == .available
    }

    static func requestGroupKey() async -> AuthorityMeshKeyAccess {
        if AuthorityMeshKeyStore.hasGroupKey() && AuthorityMeshKeyStore.hasNextLease() {
            return .available
        }
        do {
            guard let owner = Auth.auth().currentUser?.uid, !owner.isEmpty else { return .signInRequired }
            let proof = try await RoleProofCreator(allowExpiredCertificate: false)
                .createPayload(sessionNonce: "cc-authority-mesh-key-v2")
            let request = try JSONSerialization.jsonObject(with: proof.jsonData())
            let result = try await Functions.functions(region: "us-central1")
                .httpsCallable("issueAuthorityMeshKeyV2")
                .call(request)
            guard let dict = result.data as? [String: Any] else { throw KeychainStore.AccessError.status(-50) }
            func lease(_ fields: [String: Any]) throws -> AuthorityMeshKeyLease {
                guard let key = fields["keyBase64"] as? String, Data(base64Encoded: key)?.count == groupKeyBytes,
                      let id = fields["keyId"] as? String,
                      let start = fields["notBeforeMs"] as? NSNumber,
                      let end = fields["expiresAtMs"] as? NSNumber else { throw KeychainStore.AccessError.status(-50) }
                return AuthorityMeshKeyLease(keyId: id, keyBase64: key, ownerUid: owner,
                    notBeforeMs: start.int64Value, expiresAtMs: end.int64Value,
                    receiveUntilMs: (fields["receiveUntilMs"] as? NSNumber)?.int64Value)
            }
            var leases = [try lease(dict)]
            if let next = dict["nextLease"] as? [String: Any] { leases.append(try lease(next)) }
            try AuthorityMeshKeyStore.saveGroupKeys(leases)
            return .available
        } catch {
            return AuthorityMeshKeyStore.hasGroupKey() ? .available : .failure(error)
        }
    }
}
