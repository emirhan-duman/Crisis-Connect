import XCTest
import FirebaseCore
import FirebaseAuth
import FirebaseFunctions
@testable import Crisis_Connect

@MainActor final class IndependentPhoneAccountTests: XCTestCase {
    private func trace(_ stage: String) {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("independent-account-stage.txt")
        try? stage.write(to: url, atomically: true, encoding: .utf8)
    }
    private func account(_ label: String, email existing: String? = nil) async throws -> (Auth, Functions, String) {
        let options = FirebaseOptions(googleAppID: "1:123456789:ios:abcdef123456", gcmSenderID: "123456789")
        options.apiKey = "AIzaSyEmulatorOnlyNotARealFirebaseApiKey00"; options.projectID = "demo-crisis-independent-watch"
        let name = "watch-\(label)-\(UUID().uuidString)"
        trace("configure-" + label)
        FirebaseApp.configure(name: name, options: options)
        trace("auth-instance-" + label)
        let app = try XCTUnwrap(FirebaseApp.app(name: name))
        let auth = Auth.auth(app: app)
        auth.useEmulator(withHost: "127.0.0.1", port: 9097)
        let functions = Functions.functions(app: app, region: "europe-west1")
        functions.useEmulator(withHost: "127.0.0.1", port: 8291)
        let email = existing ?? "\(label)-\(UUID().uuidString)@example.test"
        trace("signup-" + label)
        if existing == nil { _ = try await auth.createUser(withEmail: email, password: "controlled-test-123!") }
        else { _ = try await auth.signIn(withEmail: email, password: "controlled-test-123!") }
        trace("account-ready-" + label)
        return (auth, functions, email)
    }
    private func invoke(_ functions: Functions, _ name: String, _ data: [String: Any]) async throws -> [String: Any] {
        trace("call-" + name)
        let callable = functions.httpsCallable(name); callable.timeoutInterval = 10
        let result = try await callable.call(data)
        return try XCTUnwrap(result.data as? [String: Any])
    }
    private var reportURL: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("independent-phone-account.json") }
    func testAuthenticatedAccountConsentAndNormalPhoneDelivery() async throws {
        await ContactStore.shared.waitUntilLoaded(); await SOSChatStore.shared.waitUntilLoaded()
        trace("before-account")
        let (auth, functions, email) = try await account("phone")
        let owner = try XCTUnwrap(auth.currentUser?.uid)
        let endpoint = URL(string: "http://127.0.0.1:8291/gateway")!
        let receiver = try IndependentPhoneInbox(ownerUid: owner, endpoint: endpoint, call: { name, data in try await self.invoke(functions, name, data) }, accountAvailable: { auth.currentUser?.uid == owner })
        try await receiver.enable()
        let (senderAuth, senderFunctions, _) = try await account("watch"), uid = try XCTUnwrap(senderAuth.currentUser?.uid)
        let sender = try IndependentWatchService(deviceID: UUID().uuidString.lowercased(), endpoint: endpoint)
        var enrollment: [String: Any] = try sender.enrollment(ownerUid: uid); enrollment["confirmed"] = true
        let enrolled = try await invoke(senderFunctions, "enrollIndependentWatch", enrollment), index = try XCTUnwrap((enrolled["signalDeviceId"] as? NSNumber)?.uint32Value)
        var proof = try sender.signalApproval(ownerUid: uid, deviceIndex: index); proof["confirmed"] = true
        _ = try await invoke(senderFunctions, "configureIndependentWatchSignal", proof)
        let base = ContactStore.shared.upsertContact(name: "Hesap onaylı test saati", broadcastId: "account-watch-\(uid)", aesKeyBase64: "")
        let contact = try XCTUnwrap(ContactStore.shared.applyInternetIdentity(conversationSessionCode: base.sessionCode, peerUid: uid, peerPublicKey: "controlled-saved-contact", displayName: base.name))
        try await ContactStore.shared.flushDurably()
        let devices = try await receiver.devices(contact: contact), descriptor = try XCTUnwrap(devices.first { $0["deviceID"] as? String == sender.deviceID })
        try await receiver.approve(contact: contact, descriptor: descriptor)
        let bundle = try receiver.service.signal.publicBundle(device: 2)
        _ = try await invoke(senderFunctions, "approveIndependentWatchContact", ["confirmed": true, "deviceID": sender.deviceID, "peerInstallation": receiver.deviceID, "peerUid": owner, "identityKey": bundle["identityKey"]!])
        try sender.signal.pinPeer(uid: owner, device: 2, identity: bundle["identityKey"] as! String)
        _ = try? await sender.sendMessage(recipientInstallation: receiver.deviceID, uid: owner, device: 2, bundle: bundle, clear: IndependentPhoneInbox.textContent("Gerçek hesap akışından saat mesajı"))
        _ = try await sender.refresh(); try await receiver.refresh(); _ = try await sender.refresh(); try await receiver.refresh()
        let outgoing = try sender.signal.outgoing(), id = try XCTUnwrap(outgoing.keys.first)
        XCTAssertEqual(outgoing.count, 1); XCTAssertEqual(outgoing[id]?.state, "delivered")
        let messages = SOSChatStore.shared.messages(for: contact.id).filter { $0.transportMessageId == id }
        XCTAssertEqual(messages.count, 1); XCTAssertEqual(messages.first?.text, "Gerçek hesap akışından saat mesajı")
        let report: [String: Any] = ["passed": true, "ownerUid": owner, "email": email, "messageID": id, "contactID": contact.id.uuidString,
            "accountProvider": "Firebase Auth SDK / isolated Auth emulator", "realWatchRuntime": false]
        try JSONSerialization.data(withJSONObject: report).write(to: reportURL, options: .atomic)
    }
    func testAuthenticatedRestoreAfterAppRestart() async throws {
        var report = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: reportURL)) as? [String: Any])
        let (auth, functions, _) = try await account("phone", email: report["email"] as? String), owner = try XCTUnwrap(auth.currentUser?.uid)
        XCTAssertEqual(owner, report["ownerUid"] as? String)
        await ContactStore.shared.waitUntilLoaded(); await SOSChatStore.shared.waitUntilLoaded()
        let id = report["messageID"] as! String, contact = UUID(uuidString: report["contactID"] as! String)!
        let before = try XCTUnwrap(SOSChatStore.shared.messages(for: contact).first { $0.transportMessageId == id })
        let receiver = try IndependentPhoneInbox(ownerUid: owner, endpoint: URL(string: "http://127.0.0.1:8291/gateway")!, call: { name, data in try await self.invoke(functions, name, data) }, accountAvailable: { auth.currentUser?.uid == owner })
        try await receiver.restoreChats(); try await receiver.refresh(); try await receiver.restoreChats()
        let rows = SOSChatStore.shared.messages(for: contact).filter { $0.transportMessageId == id }
        XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows.first?.id, before.id); XCTAssertEqual(rows.first?.timestamp, before.timestamp)
        report["restoredAfterProcessRestart"] = true
        try JSONSerialization.data(withJSONObject: report).write(to: reportURL, options: .atomic)
    }
    func testNativeWatchToNormalPhoneChat() async throws {
        var report = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: reportURL)) as? [String: Any])
        let phase = report["nativePhase"] as? String ?? "approve"
        let (auth, functions, _) = try await account("phone", email: report["email"] as? String), owner = try XCTUnwrap(auth.currentUser?.uid)
        await ContactStore.shared.waitUntilLoaded(); await SOSChatStore.shared.waitUntilLoaded()
        let receiver = try IndependentPhoneInbox(ownerUid: owner, endpoint: URL(string: "http://127.0.0.1:8291/gateway")!, call: { name, data in try await self.invoke(functions, name, data) }, accountAvailable: { auth.currentUser?.uid == owner })
        if phase == "approve" {
            let uid = report["nativePeer"] as! String
            let base = ContactStore.shared.upsertContact(name: "Gerçek saat test kişisi", broadcastId: "native-watch-\(uid)", aesKeyBase64: "")
            let contact = try XCTUnwrap(ContactStore.shared.applyInternetIdentity(conversationSessionCode: base.sessionCode, peerUid: uid, peerPublicKey: "controlled-saved-contact", displayName: base.name))
            try await ContactStore.shared.flushDurably()
            let devices = try await receiver.devices(contact: contact)
            try await receiver.approve(contact: contact, descriptor: XCTUnwrap(devices.first { $0["deviceID"] as? String == report["nativeDevice"] as? String }))
            report["nativeContactID"] = contact.id.uuidString
        } else {
            let id = report["nativeMessageID"] as! String, contact = UUID(uuidString: report["nativeContactID"] as! String)!
            let before = SOSChatStore.shared.messages(for: contact).first { $0.transportMessageId == id }
            if phase == "restore" { XCTAssertNotNil(before) }
            try await receiver.refresh(); try await receiver.restoreChats(); try await receiver.refresh()
            let rows = SOSChatStore.shared.messages(for: contact).filter { $0.transportMessageId == id }
            XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows.first?.text, "Gerçek Apple Watch cihazından mesaj")
            if let before { XCTAssertEqual(rows.first?.id, before.id); XCTAssertEqual(rows.first?.timestamp, before.timestamp) }
            report["realWatchRuntime"] = true
        }
        report["nativeStagePassed"] = phase
        try JSONSerialization.data(withJSONObject: report).write(to: reportURL, options: .atomic)
    }

}
