#if DEBUG && targetEnvironment(simulator) && CC_WATCH_INDEPENDENT
import SwiftUI
struct IndependentWatchPhoneTestView: View {
    @State private var notice = "Telefon sohbet testi"
    var body: some View { Text(notice).task { await run() } }
    @MainActor private func run() async {
        let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("independent-phone-native.json")
        do {
            let env = ProcessInfo.processInfo.environment, phase = env["CC_PHONE_PHASE"] ?? "prepare"
            var report: [String: Any] = phase == "prepare" ? ["deviceID": UUID().uuidString.lowercased()] : try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
            let service = try IndependentWatchService(deviceID: report["deviceID"] as! String, endpoint: URL(string: "http://127.0.0.1:8291/gateway")!)
            if phase == "prepare" {
                guard let uid = env["CC_PHONE_OWNER"] else { throw IndependentWatchError.invalid }
                report["enrollment"] = try service.enrollment(ownerUid: uid)
                report["binding"] = try service.signalApproval(ownerUid: uid, deviceIndex: 2)
            } else if phase == "send" {
                guard try service.signal.outgoing().isEmpty else { throw IndependentWatchError.invalid }
                let status = try await service.refresh()
                guard let contacts = status["contacts"] as? [[String: Any]], contacts.count == 1,
                      let peer = contacts.first, let uid = peer["ownerUid"] as? String, let id = peer["deviceID"] as? String,
                      let bundle = peer["bundle"] as? [String: Any], let device = (bundle["deviceId"] as? NSNumber)?.uint32Value,
                      let identity = bundle["identityKey"] as? String else { throw IndependentWatchError.invalid }
                try service.signal.pinPeer(uid: uid, device: device, identity: identity)
                _ = try await service.sendMessage(recipientInstallation: id, uid: uid, device: device, bundle: bundle,
                    clear: JSONSerialization.data(withJSONObject: ["version": 1, "kind": "watchText", "text": "Gerçek Apple Watch cihazından mesaj"]))
                report["messageID"] = try service.signal.outgoing().keys.first
            } else {
                guard phase == "restore" else { throw IndependentWatchError.invalid }
                _ = try await service.refresh()
                let outgoing = try service.signal.outgoing()
                guard outgoing.count == 1, let id = report["messageID"] as? String, outgoing[id]?.state == "delivered", service.history.count == 1 else { throw IndependentWatchError.invalid }
                report["deliveredAfterWatchReboot"] = true
            }
            report["phase"] = phase; report["passed"] = true; report["realWatchRuntime"] = true
            try JSONSerialization.data(withJSONObject: report).write(to: file, options: .atomic)
            notice = "Test geçti"
        } catch {
            try? JSONSerialization.data(withJSONObject: ["passed": false, "error": String(describing: error)]).write(to: file, options: .atomic)
            notice = "Test başarısız"
        }
    }
}
#endif
