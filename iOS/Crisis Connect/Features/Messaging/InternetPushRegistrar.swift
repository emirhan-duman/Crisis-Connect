//
//  InternetPushRegistrar.swift
//  Crisis Connect
//
//  APNs/FCM registration for the internet messaging downlink — the iOS counterpart of
//  Android's FCM token handling (CrisisConnectMessagingService.onNewToken + MessagingBootstrap's
//  token registration). The OS push channel is the low-bandwidth downlink: the backend fans each
//  E2E-encrypted envelope out to every registered token (messagingTokens/{uid}) as a silent,
//  data-only push; the client decrypts and posts the local notification itself, because the
//  server never holds plaintext to show.
//

import Foundation
import FirebaseAuth
import FirebaseMessaging
import UIKit

final class InternetPushRegistrar: NSObject, MessagingDelegate, @unchecked Sendable {
    static let shared = InternetPushRegistrar()

    private let client = InternetMessagingClient()

    private override init() {
        super.init()
    }

    /// Hooks FCM token delivery and asks the OS for an APNs token. Idempotent, safe on every
    /// launch. Never prompts: silent (content-available) pushes need no notification permission —
    /// visible message notifications reuse the authorization onboarding already requests.
    @MainActor
    func activate() {
        guard !PlatformRuntime.isRunningTests else { return }
        Messaging.messaging().delegate = self
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// Publishes the current FCM token under the signed-in uid so the message fan-out can reach
    /// this device. No-op without an authenticated user; best-effort otherwise (a failure here is
    /// retried on the next launch and on every FCM token refresh).
    func registerTokenWithBackend() async {
        guard Auth.auth().currentUser != nil else { return }
        do {
            let token = try await Messaging.messaging().token()
            try await client.registerPushToken(token)
        } catch {
            NSLog("InternetPushRegistrar: failed to register push token: %@", String(describing: error))
        }
    }

    // FCM rotates tokens over the app's lifetime; re-register on every refresh so the backend
    // registry never goes stale (mirrors Android's onNewToken).
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard fcmToken != nil else { return }
        Task { await self.registerTokenWithBackend() }
    }
}
