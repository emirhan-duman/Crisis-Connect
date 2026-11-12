#if DEBUG && targetEnvironment(simulator) && CC_WATCH_INDEPENDENT
import SwiftUI

/// Simulator-only runner. Uses the real signing, encrypted journal and direct network client.
struct IndependentWatchSOSTestView: View {
    @State private var label = "Bağımsız SOS testi…"
    var body: some View { Text(label).padding().task { await run() } }
    @MainActor private func run() async {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let defaults = UserDefaults.standard
        do {
            let endpoint = URL(string: "http://127.0.0.1:8291/gateway")!
            let phase = ProcessInfo.processInfo.environment["CC_INDEPENDENT_WATCH_PHASE"] ?? "initial"
            let isResume = phase != "initial"
            let deviceID: String
            let sessionID: String
            if isResume {
                guard let id = defaults.string(forKey: "independent.fixture.device"), let incident = defaults.string(forKey: "independent.fixture.session") else { throw IndependentWatchError.invalid }
                deviceID = id; sessionID = incident
            } else {
                deviceID = UUID().uuidString.lowercased(); sessionID = UUID().uuidString.lowercased()
                defaults.set(deviceID, forKey: "independent.fixture.device"); defaults.set(sessionID, forKey: "independent.fixture.session")
            }
            let service = try IndependentWatchService(deviceID: deviceID, endpoint: endpoint)
            if !isResume {
                var enrollment = try service.enrollment(ownerUid: "watchTestApple"); enrollment["ownerUid"] = "watchTestApple"
                var request = URLRequest(url: URL(string: "http://127.0.0.1:8291/enroll")!)
                request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONSerialization.data(withJSONObject: enrollment)
                let (_, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw IndependentWatchError.invalid }
                var publish = URLRequest(url: URL(string: "http://127.0.0.1:8291/publish")!)
                publish.httpMethod = "POST"; publish.setValue("application/json", forHTTPHeaderField: "Content-Type")
                publish.httpBody = try JSONSerialization.data(withJSONObject: service.signalApproval(ownerUid: "watchTestApple", deviceIndex: 2))
                let (_, published) = try await URLSession.shared.data(for: publish)
                guard (published as? HTTPURLResponse)?.statusCode == 200 else { throw IndependentWatchError.invalid }
                let location: [String: Any] = ["lat": 41.01, "lon": 28.97, "accuracyMeters": 15, "capturedAtMillis": Int64(Date().timeIntervalSince1970 * 1000), "countryIso": "TR", "fixtureDropResponse": true]
                do { _ = try await service.startSOS(location: location, sessionID: sessionID) } catch { /* Deliberately lost receipt, reconcile without replay. */ }
                guard service.history.count == 1 else { throw IndependentWatchError.invalid }
                let active = try await service.refresh()
                guard let state = active["sos"] as? [String: Any], state["status"] as? String == "active", state["sessionID"] as? String == sessionID, service.history.first?.state == "accepted" else { throw IndependentWatchError.invalid }
                _ = try await service.stopSOS(sessionID: sessionID)
            }
            let (peerData, peerResponse) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8291/peer/watchTestApple")!)
            guard (peerResponse as? HTTPURLResponse)?.statusCode == 200,
                  let peer = try JSONSerialization.jsonObject(with: peerData) as? [String: Any], let bundle = peer["bundle"] as? [String: Any],
                  let uid = peer["ownerUid"] as? String, let installation = peer["deviceID"] as? String, let key = bundle["identityKey"] as? String else { throw IndependentWatchError.invalid }
            // Only this simulator fixture trusts keys from the owned loopback fixture.
            try service.signal.pinPeer(uid: uid, device: 2, identity: key)
            if !isResume {
                do { _ = try await service.sendMessage(recipientInstallation: installation, uid: uid, device: 2, bundle: bundle, clear: Data("Apple Watch kontrollü mesaj".utf8)) } catch { /* lost response */ }
                _ = try await service.refresh()
            } else if phase == "resume" {
                let status = try await service.refresh()
                guard let inbox = status["inbox"] as? [[String: Any]], inbox.count == 1 else { throw IndependentWatchError.invalid }
                let clear = try await service.receiveMessage(inbox[0])
                guard String(data: clear, encoding: .utf8) == "Wear OS kontrollü cevap" else { throw IndependentWatchError.invalid }
                let duplicate = try await service.receiveMessage(inbox[0])
                guard duplicate == clear else { throw IndependentWatchError.invalid }
            }
            let reopened = try IndependentWatchService(deviceID: deviceID, endpoint: endpoint)
            let final = try await reopened.refresh()
            guard let state = final["sos"] as? [String: Any], state["status"] as? String == "resolved", reopened.history.count == 3, reopened.history.allSatisfy({ ["accepted", "delivered"].contains($0.state) }) else { throw IndependentWatchError.invalid }
            let outgoing = try reopened.signal.outgoing()
            guard outgoing.count == 1, !isResume || outgoing.values.first?.state == "delivered" else { throw IndependentWatchError.invalid }
            let received = try reopened.signal.incoming()
            if isResume { guard received.count == 1, received.values.first?.clear == Data("Wear OS kontrollü cevap".utf8) else { throw IndependentWatchError.invalid } }
            let report: [String: Any] = ["passed": true, "deviceID": deviceID, "sessionID": sessionID, "status": state, "commandIDs": reopened.history.map(\.id), "phase": phase, "states": reopened.history.map(\.state), "outboxCount": outgoing.count, "inboxCount": received.count]
            try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: documents.appendingPathComponent("independent-sos-result.json"), options: .atomic)
            label = "SOS testi geçti"
        } catch {
            let report: [String: Any] = ["passed": false, "error": String(describing: error)]
            if let data = try? JSONSerialization.data(withJSONObject: report) { try? data.write(to: documents.appendingPathComponent("independent-sos-result.json"), options: .atomic) }
            label = "Test tamamlanamadı"
        }
    }
}
#endif
