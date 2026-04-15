//
//  FirebaseAsync.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import Foundation
import FirebaseFunctions
import FirebaseFirestore

extension HTTPSCallable {
    func callAsync(_ data: Any? = nil) async throws -> HTTPSCallableResult {
        try await withCheckedThrowingContinuation { continuation in
            self.call(data) { result, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }
                if let result = result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: NSError(
                        domain: "CrisisConnect.Firebase",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Firebase callable returned nil."]
                    ))
                }
            }
        }
    }
}

extension DocumentReference {
    func getDocumentAsync() async throws -> DocumentSnapshot {
        try await withCheckedThrowingContinuation { continuation in
            getDocument { snapshot, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }
                if let snapshot = snapshot {
                    continuation.resume(returning: snapshot)
                } else {
                    continuation.resume(throwing: NSError(
                        domain: "CrisisConnect.Firebase",
                        code: -2,
                        userInfo: [NSLocalizedDescriptionKey: "Document snapshot is nil."]
                    ))
                }
            }
        }
    }

    func setDataAsync(_ data: [String: Any], merge: Bool = false) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            setData(data, merge: merge) { error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    func updateDataAsync(_ fields: [AnyHashable: Any]) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            updateData(fields) { error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}

extension WriteBatch {
    func commitAsync() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            commit { error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}
