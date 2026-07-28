//
//  VoipPushRegistrar.swift
//  Crisis Connect
//
//  PushKit (VoIP) registration — the ONLY iOS mechanism that wakes a force-quit app so CallKit can
//  ring an incoming internet (WebRTC) call. FCM background pushes can't do this: they never launch a
//  terminated app for a call, and iOS 13+ hard-requires that every VoIP push report an incoming call
//  to CallKit synchronously inside the push handler (or the OS kills the app and stops delivering
//  VoIP pushes to it).
//
//  Flow: register the PushKit token under platform "ios-voip" (its own registry entry, distinct from
//  the FCM alert token). The backend's onMessageCreated fires a VoIP push (via direct APNs) only for
//  a call OFFER (the `callRing` envelope marker). On receipt we immediately report a placeholder
//  incoming call; the real offer rides the Firestore listener moments later and fills in the caller.
//
//  Requires (project owner, one-time): the app's App ID has Push Notifications enabled, the `voip`
//  UIBackgroundMode (already in Info.plist), and the backend APNs Auth Key secrets (see apnsVoip.ts).
//

import Foundation
import PushKit
import FirebaseAuth

final class VoipPushRegistrar: NSObject, PKPushRegistryDelegate, @unchecked Sendable {
    static let shared = VoipPushRegistrar()

    private let registry = PKPushRegistry(queue: .main)
    private let client = InternetMessagingClient()
    private var lastRegisteredToken: String?

    private override init() {
        super.init()
    }

    /// Arms PushKit. Idempotent; safe on every launch. Does not prompt (VoIP pushes need no
    /// user-facing authorization — the ring is CallKit, gated by the call notification permission
    /// onboarding already requests).
    func activate() {
        guard !PlatformRuntime.isRunningTests else { return }
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        // If a token already exists (fast relaunch), re-publish it under the current uid.
        if let data = registry.pushToken(for: .voIP) {
            Task { await self.registerToken(data) }
        }
    }

    /// Re-publishes the current VoIP token — call after sign-in so a token minted before auth
    /// lands under the right uid.
    func registerCurrentTokenWithBackend() {
        guard let data = registry.pushToken(for: .voIP) else { return }
        Task { await self.registerToken(data) }
    }

    private func registerToken(_ tokenData: Data) async {
        guard Auth.auth().currentUser != nil else { return }
        let token = tokenData.map { String(format: "%02x", $0) }.joined()
        guard token != lastRegisteredToken else { return }
        do {
            try await client.registerPushToken(token, platform: "ios-voip")
            lastRegisteredToken = token
        } catch {
            NSLog("VoipPushRegistrar: failed to register VoIP token: %@", String(describing: error))
        }
    }

    // MARK: - PKPushRegistryDelegate

    func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        guard type == .voIP else { return }
        Task { await self.registerToken(pushCredentials.token) }
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP, let token = lastRegisteredToken else { return }
        lastRegisteredToken = nil
        Task { try? await client.unregisterPushToken(token) }
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else { completion(); return }
        let dict = payload.dictionaryPayload
        let senderUid = (dict["senderUid"] as? String) ?? ""
        let callerName = (dict["callerName"] as? String) ?? ""
        let hasVideo = (dict["hasVideo"] as? String) == "1"

        // MUST report to CallKit before `completion` runs — do it on main, synchronously kicking
        // the report; InternetCallManager owns the CXProvider. The real offer arrives via the
        // Firestore listener that the app's bootstrap (running now, because this push launched us)
        // has already started.
        DispatchQueue.main.async {
            InternetCallManager.shared.reportVoipWake(
                senderUid: senderUid,
                callerName: callerName,
                hasVideo: hasVideo,
                completion: completion
            )
        }
    }
}
