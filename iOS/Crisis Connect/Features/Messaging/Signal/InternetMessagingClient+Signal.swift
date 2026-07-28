//
//  InternetMessagingClient+Signal.swift
//  Crisis Connect
//
//  FS-6: the Signal-prekey callables (publish / inventory / bundle fetch) — the same backend API
//  Android speaks, so an iOS device is a first-class v3 peer.
//

import Foundation
import FirebaseFunctions

/// One fetched PQXDH prekey bundle, exactly as `fetchSignalPreKeyBundle` returns it.
struct SignalPreKeyBundleWire {
    let registrationId: Int
    let deviceId: Int
    let identityKeyBase64: String
    let signedPreKeyId: Int
    let signedPreKeyBase64: String
    let signedPreKeySignatureBase64: String
    let preKeyId: Int?
    let preKeyBase64: String?
    let kyberPreKeyId: Int
    let kyberPreKeyBase64: String
    let kyberPreKeySignatureBase64: String
}

struct SignalPreKeyInventory {
    let published: Bool
    let ecCount: Int
    let kyberCount: Int
}

extension InternetMessagingClient {
    func publishSignalPreKeys(
        registrationId: Int,
        identityKeyBase64: String,
        signedPreKey: PreKeyUpload,
        lastResortKyberPreKey: PreKeyUpload,
        preKeys: [PreKeyUpload],
        kyberPreKeys: [PreKeyUpload]
    ) async throws {
        let payload: [String: Any] = [
            "registrationId": registrationId,
            "identityKey": identityKeyBase64,
            "signedPreKey": signedPreKey.toMap(),
            "lastResortKyberPreKey": lastResortKyberPreKey.toMap(),
            "preKeys": preKeys.map { $0.toMap() },
            "kyberPreKeys": kyberPreKeys.map { $0.toMap() },
        ]
        _ = try await Functions.functions(region: Self.region)
            .httpsCallable("publishSignalPreKeys").call(payload)
    }

    func checkSignalPreKeys() async throws -> SignalPreKeyInventory {
        let result = try await Functions.functions(region: Self.region)
            .httpsCallable("checkSignalPreKeys").call([:])
        let data = result.data as? [String: Any] ?? [:]
        return SignalPreKeyInventory(
            published: data["published"] as? Bool ?? false,
            ecCount: (data["ecCount"] as? NSNumber)?.intValue ?? 0,
            kyberCount: (data["kyberCount"] as? NSNumber)?.intValue ?? 0
        )
    }

    /// Returns nil when the target never published Signal prekeys (caller falls back to v2).
    func fetchSignalPreKeyBundle(targetUid: String) async throws -> SignalPreKeyBundleWire? {
        let result: HTTPSCallableResult
        do {
            result = try await Functions.functions(region: Self.region)
                .httpsCallable("fetchSignalPreKeyBundle").call(["targetUid": targetUid])
        } catch {
            let nsError = error as NSError
            if nsError.domain == FunctionsErrorDomain,
               nsError.code == FunctionsErrorCode.notFound.rawValue {
                return nil
            }
            throw error
        }
        guard let data = result.data as? [String: Any],
              let registrationId = (data["registrationId"] as? NSNumber)?.intValue,
              let identityKey = data["identityKey"] as? String,
              let signed = data["signedPreKey"] as? [String: Any],
              let signedId = (signed["keyId"] as? NSNumber)?.intValue,
              let signedKey = signed["publicKey"] as? String,
              let signedSig = signed["signature"] as? String,
              let kyber = data["kyberPreKey"] as? [String: Any],
              let kyberId = (kyber["keyId"] as? NSNumber)?.intValue,
              let kyberKey = kyber["publicKey"] as? String,
              let kyberSig = kyber["signature"] as? String else {
            throw InternetMessagingClientError.malformedResponse
        }
        let preKey = data["preKey"] as? [String: Any]
        return SignalPreKeyBundleWire(
            registrationId: registrationId,
            deviceId: (data["deviceId"] as? NSNumber)?.intValue ?? 1,
            identityKeyBase64: identityKey,
            signedPreKeyId: signedId,
            signedPreKeyBase64: signedKey,
            signedPreKeySignatureBase64: signedSig,
            preKeyId: (preKey?["keyId"] as? NSNumber)?.intValue,
            preKeyBase64: preKey?["publicKey"] as? String,
            kyberPreKeyId: kyberId,
            kyberPreKeyBase64: kyberKey,
            kyberPreKeySignatureBase64: kyberSig
        )
    }
}
