import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

enum ResilientApplicationStore {
    static let enabled = true // Server controls enrollment; deployed stores are verified before app release.

    static func profiles() -> Firestore {
        if enabled, let app = FirebaseApp.app() {
            return Firestore.firestore(app: app, database: MessagingDatabaseTarget.databaseID)
        }
        return Firestore.firestore()
    }
    static func isResilientConversation(_ id: String) -> Bool {
        guard enabled, let uid = Auth.auth().currentUser?.uid else { return false }
        return UserDefaults.standard.bool(forKey: "group-ha-conversation/\(uid)/\(id)")
    }

    static func prefersCompatibility(_ binding: AuthorityMlsBinding) -> Bool {
        guard let uid = Auth.auth().currentUser?.uid else { return false }
        var old = binding; old.resilient = false
        guard let id = try? AuthorityMlsIdentifiers.conversationId(old) else { return false }
        return UserDefaults.standard.bool(forKey: "group-ha-compatibility/\(uid)/\(id)")
    }
    static func rememberSessionChoice(_ binding: AuthorityMlsBinding, compatibility: Bool) {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        var old = binding; old.resilient = false
        guard let id = try? AuthorityMlsIdentifiers.conversationId(old) else { return }
        UserDefaults.standard.set(compatibility, forKey: "group-ha-compatibility/\(uid)/\(id)")
    }

    static func resolve(_ binding: AuthorityMlsBinding) async throws -> AuthorityMlsBinding {
        guard enabled, !binding.routeResolved else { return binding }
        guard let uid = Auth.auth().currentUser?.uid, binding.participants.contains(uid) else {
            throw InternetMessagingClientError.notSignedIn
        }
        let canonical = try AuthorityMlsIdentifiers.canonicalBinding(binding)
        let old = AuthorityMlsBinding(scopeType: canonical.scopeType, channelId: canonical.channelId,
            participants: canonical.participants)
        let oldId = try AuthorityMlsIdentifiers.conversationId(old)
        let cacheKey = "group-ha-route-v1/\(uid)/\(oldId)"
        if UserDefaults.standard.bool(forKey: cacheKey) {
            let result = AuthorityMlsBinding(scopeType: canonical.scopeType, channelId: canonical.channelId,
                participants: canonical.participants, resilient: true, routeResolved: true)
            UserDefaults.standard.set(true, forKey: "group-ha-conversation/\(uid)/\(try AuthorityMlsIdentifiers.conversationId(result))")
            return result
        }
        let deviceId = SecureLocalStore.shared.getOrCreateRescueDeviceId()
        let raw = try await RegionalMessagingCalls().call("openResilientGroup", [
            "binding": ["scopeType": canonical.scopeType.rawValue, "channelId": canonical.channelId,
                        "participants": canonical.participants], "deviceId": deviceId
        ], accountCurrent: { Auth.auth().currentUser?.uid == uid })
        guard let data = raw as? [String: Any], let resilient = data["resilient"] as? Bool else {
            throw InternetMessagingClientError.malformedResponse
        }
        let result = AuthorityMlsBinding(scopeType: canonical.scopeType, channelId: canonical.channelId,
            participants: canonical.participants, resilient: resilient, routeResolved: true)
        guard data["conversationId"] as? String == (try AuthorityMlsIdentifiers.conversationId(result)) else {
            throw InternetMessagingClientError.malformedResponse
        }
        if resilient {
            UserDefaults.standard.set(true, forKey: cacheKey)
            UserDefaults.standard.set(true, forKey: "group-ha-conversation/\(uid)/\(try AuthorityMlsIdentifiers.conversationId(result))")
        }
        return result
    }
}
