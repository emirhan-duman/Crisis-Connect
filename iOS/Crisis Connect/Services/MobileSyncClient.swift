//
//  MobileSyncClient.swift
//  Crisis Connect
//
//  Pushes the signed-in user's profile snapshot to the web dashboard's /api/mobile/sync endpoint
//  (iOS port of Android's sync/MobileSyncClient). Until this existed, every iOS user was simply
//  absent from the dashboard roster — the panel only ever heard about Android devices.
//
//  Best-effort BY DESIGN: sync failures are logged and swallowed, never surfaced. The profile
//  screen must keep working offline, and the dashboard catches up on the next successful push.
//

import FirebaseAuth
import Foundation

enum MobileSyncClient {

    /// Mirrors Android's envelope exactly: {clientEventId, source, deviceId, events:[{id, type:
    /// "profile", occurredAt, payload}]} with a Bearer ID token. The handler accepts any `source`
    /// string (verified against the web repo: `readString(body.source) ?? "mobile"`).
    static func syncProfile(
        user: User,
        username: String,
        email: String,
        phoneNumber: String,
        country: String,
        agency: String,
        role: String,
        verified: Bool
    ) async {
        guard let url = endpointURL() else { return }
        guard let token = try? await user.getIDToken(), !token.isEmpty else {
            NSLog("MobileSyncClient: skipping sync, no ID token")
            return
        }
        let now = Int(Date().timeIntervalSince1970 * 1000)
        let clientEventId = "ios-profile-\(user.uid)-\(now)"
        let payload: [String: Any] = [
            "uid": user.uid,
            "username": username,
            "displayName": username,
            "email": email.isEmpty ? (user.email ?? "") : email,
            "phoneNumber": phoneNumber.isEmpty ? (user.phoneNumber ?? "") : phoneNumber,
            "country": country,
            "agency": agency,
            "role": role,
            "verified": verified,
            "platform": "ios",
        ]
        let body: [String: Any] = [
            "clientEventId": clientEventId,
            "source": "ios",
            "deviceId": SecureLocalStore.shared.getOrCreateRescueDeviceId(),
            "events": [[
                "id": clientEventId,
                "type": "profile",
                "occurredAt": now,
                "payload": payload,
            ]],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = data
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                NSLog("MobileSyncClient: sync rejected status=%d", http.statusCode)
            }
        } catch {
            NSLog("MobileSyncClient: sync failed: %@", String(describing: error))
        }
    }

    /// The dashboard origin from Info.plist, https-only — a plaintext or malformed override must
    /// disable sync rather than send the profile somewhere insecure. Same default Android's
    /// enterprise-SSO base uses, so sync works out of the box.
    private static func endpointURL() -> URL? {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let base = raw.isEmpty ? "https://crisisconnect.network" : raw
        guard var components = URLComponents(string: base), components.scheme == "https" else {
            NSLog("MobileSyncClient: ignoring non-https base URL")
            return nil
        }
        components.path = components.path.hasSuffix("/")
            ? components.path + "api/mobile/sync"
            : components.path + "/api/mobile/sync"
        return components.url
    }
}
