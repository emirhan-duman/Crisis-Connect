import XCTest
import Foundation
import UIKit
import CryptoKit
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions
import FirebaseAppCheck
@testable import Crisis_Connect

private actor ApplicationHaInbox {
    private var value: Data?
    func put(_ data: Data) { value = data }
    func get() -> Data? { value }
}

final class ApplicationHighAvailabilityLiveSdkTests: XCTestCase {
    func testProfilesAndMlsAcrossIosAndAndroid() async throws {
        func fixture(_ body: [String: Any]? = nil) async throws -> [String: Any] {
            var request = URLRequest(url: URL(string: "http://127.0.0.1:31234/ios")!)
            request.timeoutInterval = 10
            if let body { request.httpMethod = "POST"; request.httpBody = try JSONSerialization.data(withJSONObject: body) }
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        }
        func until(_ condition: () async throws -> Bool) async throws {
            let deadline = Date().addingTimeInterval(480)
            while !(try await condition()) {
                guard Date() < deadline else { throw URLError(.timedOut) }
                try await Task.sleep(nanoseconds: 500_000_000)
            }
        }
        let config: [String: Any]
        do { config = try await fixture() } catch { throw XCTSkip("Dedicated application HA fixture required") }
        let run = try XCTUnwrap(config["runId"] as? String)
        // AppDelegate deliberately skips production startup under XCTest.
        RustMlsWorkerBackend.activate()
        let mediaRoute = RegionalRoutePolicy(initialRegion: { RegionalRoutePolicy.us })
        let previous = ProcessInfo.processInfo.environment["FIRAAppCheckDebugToken"]
        setenv("FIRAAppCheckDebugToken", try XCTUnwrap(config["debugToken"] as? String), 1)
        AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
        defer {
            if let previous { setenv("FIRAAppCheckDebugToken", previous, 1) } else { unsetenv("FIRAAppCheckDebugToken") }
            AppCheck.setAppCheckProviderFactory(CrisisConnectAppCheckProviderFactory())
        }
        FirebaseApp.configure(name: "application-ha-\(run)", options: try XCTUnwrap(FirebaseApp.app()?.options))
        let app = try XCTUnwrap(FirebaseApp.app(name: "application-ha-\(run)"))
        let auth = Auth.auth(app: app)
        let store = Firestore.firestore(app: app, database: MessagingDatabaseTarget.databaseID)
        let legacy = Firestore.firestore(app: app)
        var session: AuthorityMlsConversationSession?
        func call(_ op: String, _ payload: [String: Any]) async throws -> [String: Any] {
            let router = RegionalMessagingCalls(policy: RegionalRoutePolicy(initialRegion: { RegionalRoutePolicy.us }), highAvailability: true) { region, operation, data in
                let name = region == RegionalRoutePolicy.europe ? "messagingGatewayHaEurope" : "messagingGatewayHaUS"
                let function = Functions.functions(app: app, region: region).httpsCallable(name)
                function.timeoutInterval = 45
                return try await function.call(["operation": operation, "payload": data]).data
            }
            let result = try await router.call(op, payload)
            return try XCTUnwrap(result as? [String: Any])
        }
        do {
            _ = try await XCTUnwrap(AppCheck.appCheck(app: app)).token(forcingRefresh: true)
            let uid = try await auth.signInAnonymously().user.uid
            let device = SecureLocalStore.shared.getOrCreateRescueDeviceId()
            _ = try await fixture(["uid": uid, "deviceId": device])
            try await until { try await fixture()["ready"] as? Bool == true }
            let peerState = try await fixture()
            let peer = try XCTUnwrap((peerState["peer"] as? [String: Any])?["uid"] as? String)
            let panel = try XCTUnwrap(config["panelId"] as? String)
            let raw: [String: Any] = ["scopeType": "agency", "channelId": panel, "participants": [uid, peer]]
            let first = try await call("resolveResilientGroup", ["binding": raw, "deviceId": device])
            XCTAssertEqual(first["resilient"] as? Bool, false)
            _ = try await fixture(["legacyChecked": true])
            try await until { try await fixture()["legacyReleased"] as? Bool == true }
            let binding = AuthorityMlsBinding(scopeType: .agency, channelId: panel, participants: [uid, peer], resilient: true)
            let id = try AuthorityMlsIdentifiers.conversationId(binding)
            func legacyRound(_ phase: String) async throws {
                let compatibility = AuthorityMlsBinding(scopeType: .agency, channelId: panel, participants: [uid, peer], routeResolved: true)
                XCTAssertNotEqual(try AuthorityMlsIdentifiers.conversationId(compatibility), id)
                let old = try await AuthorityMlsConversationSession.prepare(accountUid: uid, binding: compatibility, deviceLabel: "iOS compatibility SDK", transport: AuthorityMlsTransport(db: legacy))
                session = old
                _ = try await fixture(["\(phase)Prepared": true, "legacyGroupId": try AuthorityMlsIdentifiers.conversationId(compatibility)])
                try await until { (try await fixture()["peer"] as? [String: Any])?["\(phase)Prepared"] as? Bool == true }
                _ = try await fixture(["legacyStage": phase + ":directory"])
                for trust in try await old.refreshPreparation().trust where !trust.approved {
                    _ = try await old.approveDeviceSet(uid: trust.uid, expectedFingerprint: trust.fingerprint)
                }
                let inbox = ApplicationHaInbox(), expected = Data("Android \(phase) \(run)".utf8)
                _ = try await fixture(["legacyStage": phase + ":activate"])
                try await old.activate(onApplication: { if $0.plaintext == expected { await inbox.put($0.plaintext) } })
                _ = try await fixture(["legacyStage": phase + ":ready"])
                try await until { try await old.isReadyToSend() }
                _ = try await fixture(["legacyStage": phase + ":send"])
                _ = try await old.sendApplication(Data("iOS \(phase) \(run)".utf8))
                try await until { await inbox.get() == expected }
                _ = try await fixture(["\(phase)Received": true])
                try await until { (try await fixture()["peer"] as? [String: Any])?["\(phase)Received"] as? Bool == true }
                try await old.close(); session = nil
            }
            try await legacyRound("legacyBefore")
            try await store.document("users/\(uid)").updateData(["username": "HA iOS"])
            try await until { try await legacy.document("users/\(uid)").getDocument(source: .server).get("username") as? String == "HA iOS" }
            try await legacy.document("users/\(uid)").updateData(["country": "TR"])
            try await until { try await store.document("users/\(uid)").getDocument(source: .server).get("country") as? String == "TR" }
            let image = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { $0.cgContext.fill(CGRect(x: 0, y: 0, width: 1, height: 1)) }
            let avatar = try XCTUnwrap(image.jpegData(compressionQuality: 0.8))
            let avatarPath = "users/\(uid)/avatars/\(UUID().uuidString).jpg"
            let uploaded = try await ResilientMediaClient.request(avatarPath, method: "PUT", body: avatar, app: app, route: mediaRoute)
            let uploadedURL = try XCTUnwrap((try JSONSerialization.jsonObject(with: uploaded) as? [String: Any])?["downloadUrl"] as? String)
            try await store.document("users/\(uid)").updateData(["photoURL": uploadedURL])
            try await until { try await legacy.document("users/\(uid)").getDocument(source: .server).get("photoURL") as? String == uploadedURL }
            let roster = try await call("listAuthorityRoster", ["agencySlug": panel])
            XCTAssertTrue((roster["members"] as? [[String: Any]])?.contains { $0["uid"] as? String == peer } == true)
            try await legacy.disableNetwork()
            try await until { try await fixture()["bootstrapOutageArmed"] as? Bool == true }
            do {
                _ = try await call("resolveResilientGroup", ["binding": raw, "deviceId": device])
                XCTFail("The first legacy directory access should fail during the server-side outage fixture")
            } catch {
                XCTAssertEqual((error as NSError).domain, FunctionsErrorDomain)
                XCTAssertEqual((error as NSError).code, FunctionsErrorCode.unavailable.rawValue)
            }
            _ = try await fixture(["legacyOutageRejected": true])
            try await until { (try await fixture()["peer"] as? [String: Any])?["legacyOutageRejected"] as? Bool == true }
            let route = try await call("openResilientGroup", ["binding": raw, "deviceId": device])
            XCTAssertEqual(route["conversationId"] as? String, id)
            XCTAssertEqual(route["compatibility"] as? String, "separate-session")
            XCTAssertEqual(route["legacyDelivery"] as? Bool, false)
            XCTAssertTrue(try AuthorityMlsIdentifiers.legacyConversationIds(binding).isEmpty)
            let transport = AuthorityMlsTransport(db: store)
            let active = try await AuthorityMlsConversationSession.prepare(accountUid: uid, binding: binding, deviceLabel: "iOS HA SDK", transport: transport)
            session = active
            _ = try await fixture(["prepared": true, "groupId": id, "avatarPath": avatarPath])
            try await until { try await fixture()["allPrepared"] as? Bool == true }
            for trust in try await active.refreshPreparation().trust where !trust.approved {
                _ = try await active.approveDeviceSet(uid: trust.uid, expectedFingerprint: trust.fingerprint)
            }
            let inbox = ApplicationHaInbox()
            try await active.activate(onApplication: { await inbox.put($0.plaintext) })
            try await until { try await active.isReadyToSend() }
            let path = "authorityMessageAttachments/\(id)/\(uid)/\(UUID().uuidString)"
            let key = SymmetricKey(size: .bits256), nonce = AES.GCM.Nonce()
            let plain = Data("iOS attachment \(run)".utf8)
            let box = try AES.GCM.seal(plain, using: key, nonce: nonce, authenticating: Data(path.utf8))
            let cipher = box.ciphertext + box.tag
            _ = try await ResilientMediaClient.request(path, method: "PUT", body: cipher, app: app, route: mediaRoute)
            _ = try await ResilientMediaClient.request(path, method: "PUT", body: cipher, app: app, route: mediaRoute)
            do {
                _ = try await ResilientMediaClient.request(path, method: "PUT", body: Data("conflicting bytes".utf8), app: app, route: mediaRoute)
                XCTFail("An immutable object was overwritten")
            } catch { XCTAssertEqual((error as NSError).code, 409) }
            do {
                _ = try await ResilientMediaClient.request("authorityMessageAttachments/\(id)/\(peer)/\(UUID().uuidString)", method: "PUT", body: cipher, app: app, route: mediaRoute)
                XCTFail("A peer's upload namespace was writable")
            } catch { XCTAssertEqual((error as NSError).code, 403) }
            let attachment = ChannelAttachment(path: path, nonce: Data(nonce).base64EncodedString(), keyBase64: key.withUnsafeBytes { Data($0).base64EncodedString() }, name: "canary.txt", mime: "text/plain", size: plain.count, width: nil, height: nil, durationSec: nil)
            let payload = AuthorityMlsMessagePayload(recipientUid: peer, recipientName: "Android", senderName: "iOS", text: "iOS HA \(run)", sentAtMillis: Int64(Date().timeIntervalSince1970 * 1000), attachments: [attachment], replyToId: nil)
            _ = try await active.sendApplication(AuthorityMlsMessagePayloadCodec.encode(payload))
            try await until { await inbox.get() != nil }
            let inbound = await inbox.get()
            let received = try AuthorityMlsMessagePayloadCodec.decode(XCTUnwrap(inbound))
            XCTAssertEqual(received.recipientUid, uid); XCTAssertEqual(received.text, "Android HA \(run)")
            let file = try XCTUnwrap(received.attachments.first)
            let bytes = try await ResilientMediaClient.request(file.path, method: "GET", app: app, route: mediaRoute)
            let fileKey = SymmetricKey(data: try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(file.keyBase64))))
            let fileNonce = try AES.GCM.Nonce(data: XCTUnwrap(Data(base64Encoded: file.nonce)))
            let sealed = try AES.GCM.SealedBox(nonce: fileNonce, ciphertext: bytes.dropLast(16), tag: bytes.suffix(16))
            XCTAssertEqual(String(data: try AES.GCM.open(sealed, using: fileKey, authenticating: Data(file.path.utf8)), encoding: .utf8), "Android attachment \(run)")
            _ = try await fixture(["received": true, "mediaPath": path])
            try await until { try await fixture()["allReceived"] as? Bool == true }
            try await active.close(); session = nil
            try await legacy.enableNetwork()
            try await legacyRound("legacyAfter")
            _ = try await Functions.functions(app: app, region: "us-central1").httpsCallable("deleteAccountAndData").call()
            _ = try await fixture(["status": "passed", "cleanupComplete": true])
        } catch {
            _ = try? await fixture(["status": "failed", "errorType": String(describing: type(of: error))])
            try? await session?.close(); try? await auth.currentUser?.delete()
            throw error
        }
        try? await store.terminate(); try? await legacy.terminate()
        await withCheckedContinuation { c in app.delete { _ in c.resume() } }
    }
}
