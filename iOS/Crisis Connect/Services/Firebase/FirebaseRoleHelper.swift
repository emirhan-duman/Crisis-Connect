//
//  FirebaseRoleHelper.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import Foundation
import FirebaseAuth

enum FirebaseRoleHelper {
    private static let rescueEndpoint = "https://verifyrescueaction-jxxgznalnq-uc.a.run.app"

    enum RescueRoleResult {
        case authorized(String)
        case unauthenticated
        case unauthorized
        case failure(Error)
    }

    enum RescuePermissionResult {
        case authorized
        case forbidden
        case unauthenticated
        case failure(Error)
    }

    static func fetchRescueRole(auth: Auth = Auth.auth()) async -> RescueRoleResult {
        guard let user = auth.currentUser, !user.isAnonymous else {
            return .unauthenticated
        }
        do {
            let token = try await tokenResult(for: user)
            let rawRole = token.claims["role"] as? String
            if let normalized = RescueRoleAccess.normalizedRole(rawRole) {
                return .authorized(normalized)
            }
            return .unauthorized
        } catch {
            return .failure(error)
        }
    }

    static func verifyRescuePermission(
        auth: Auth = Auth.auth(),
        endpoint: String? = nil
    ) async -> RescuePermissionResult {
        guard let user = auth.currentUser, !user.isAnonymous else {
            return .unauthenticated
        }
        do {
            let token = try await tokenResult(for: user)
            let idToken = token.token
            guard !idToken.isEmpty else {
                return .failure(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: -3,
                    userInfo: [NSLocalizedDescriptionKey: "Firebase returned empty token."]
                ))
            }

            let resolvedEndpoint = endpoint ?? rescueEndpoint
            guard let url = URL(string: resolvedEndpoint) else {
                return .failure(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: -4,
                    userInfo: [NSLocalizedDescriptionKey: "Invalid rescue endpoint URL."]
                ))
            }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.timeoutInterval = 10
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")

            let (_, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .failure(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: -5,
                    userInfo: [NSLocalizedDescriptionKey: "Invalid rescue response."]
                ))
            }

            switch http.statusCode {
            case 200:
                return .authorized
            case 403:
                return .forbidden
            case 401:
                return .unauthenticated
            default:
                return .failure(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: http.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "Unexpected response code \(http.statusCode)."]
                ))
            }
        } catch {
            return .failure(error)
        }
    }

    private static func tokenResult(for user: User) async throws -> AuthTokenResult {
        try await withCheckedThrowingContinuation { continuation in
            user.getIDTokenResult(forcingRefresh: true) { result, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let result = result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: NSError(
                        domain: "CrisisConnect.Firebase",
                        code: -6,
                        userInfo: [NSLocalizedDescriptionKey: "Token result is nil."]
                    ))
                }
            }
        }
    }
}
