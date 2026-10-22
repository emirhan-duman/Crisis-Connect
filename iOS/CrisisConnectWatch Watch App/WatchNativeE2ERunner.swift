#if DEBUG && targetEnvironment(simulator)
import Foundation
import WatchConnectivity

/// Opt-in transport test. Refuses commands unless the phone identifies the isolated fixture.
@MainActor
final class WatchNativeE2ERunner {
    static let shared = WatchNativeE2ERunner()
    private var started = false
    private var rows: [[String: Any]] = []
    private var awaiting: UUID?
    private let fixturePeer = "DE000000-0000-4000-8000-000000000001"
    enum Failure: Error { case timeout, malformed, check(String) }

    func run(model:WatchSessionModel? = nil) async {
        guard ProcessInfo.processInfo.environment["CRISIS_WATCH_E2E"] == "1",
              ProcessInfo.processInfo.environment["CRISIS_WATCH_E2E_AUTORUN"] == "1", !started else { return }
        started = true
        if ProcessInfo.processInfo.environment["CRISIS_WATCH_CONTACT_TEST"] == "1", let model { await runContacts(model); return }
        if ProcessInfo.processInfo.environment["CRISIS_WATCH_CHAT_LIFECYCLE"] == "1", let model {await runChatLifecycle(model);return}
        if ProcessInfo.processInfo.environment["CRISIS_WATCH_CHAT_E2E"] == "1" { await runChat(); return }
        if ProcessInfo.processInfo.environment["CRISIS_RECOVERY_E2E"] == "1" { await runRecovery(); return }
        do {
            for _ in 0..<60 {
                if WCSession.default.activationState == .activated && WCSession.default.isReachable { break }
                try await Task.sleep(for: .seconds(1))
            }
            let initial = try await exchange(WatchCommand(action: .status))
            try check(initial.result == "accepted" && initial.state.peerID == fixturePeer &&
                      initial.state.peerName == "Test alıcısı · dış iletim yok" && !initial.state.bleBroadcasting,
                      "isolated phone fixture connected")
            // Never run against a live or unrecognised phone session.
            try check(!initial.state.sosActive, "initial SOS inactive")
            let start = WatchCommand(action: .startSOS)
            let active = try await exchange(start)
            try check(active.result == "accepted" && active.state.sosActive && active.state.emergencyID != nil, "watch starts phone SOS")
            let repeated = try await exchange(start)
            try check(repeated.state.emergencyID == active.state.emergencyID, "duplicate start preserves SOS session")
            for reply in WatchQuickReply.allCases {
                let command = WatchCommand(action: .quickReply, peerID: fixturePeer, reply: reply)
                let received = try await exchange(command)
                try check(received.result == "accepted" && received.state.lastMessage == reply.turkish, "phone receives \(reply.rawValue)")
                if reply == .safe {
                    let duplicate = try await exchange(command)
                    try check(duplicate.result == "accepted", "duplicate reply acknowledged; phone log must contain one copy")
                }
            }
            let wrongPeer = try await exchange(WatchCommand(action: .quickReply, peerID: UUID().uuidString, reply: .safe))
            try check(wrongPeer.result == "noPeer", "wrong reply recipient rejected")
            let expired = try await exchange(WatchCommand(action: .stopSOS, emergencyID: active.state.emergencyID, now: Date().addingTimeInterval(-60)))
            try check(expired.result == "invalid" && expired.state.sosActive, "expired stop rejected")
            let wrongStop = try await exchange(WatchCommand(action: .stopSOS, emergencyID: UUID().uuidString))
            try check(wrongStop.result == "stateChanged" && wrongStop.state.sosActive, "wrong SOS session stop rejected")
            let stop = WatchCommand(action: .stopSOS, emergencyID: active.state.emergencyID)
            let ended = try await exchange(stop)
            try check(ended.result == "accepted" && !ended.state.sosActive && ended.state.emergencyID == nil, "watch ends phone SOS")
            let duplicateStop = try await exchange(stop)
            try check(duplicateStop.result == "accepted" && !duplicateStop.state.sosActive, "duplicate stop stays inactive")
            save("passed")
        } catch { save("failed: \(error)") }
    }
    private func runContacts(_ model: WatchSessionModel) async {
        save("running")
        do {
            try await ready(model)
            try check(model.state.peerID == fixturePeer && model.state.peerName == "Test alıcısı · dış iletim yok" && !model.state.sosActive && !model.state.bleBroadcasting, "isolated contact fixture; SOS off")
            try check(model.state.contacts?.count == 3, "saved contact roster received")
            let firstID = "DC000000-0000-4000-8000-000000000001"
            model.send(.contactReply, reply: .safe, contactID: firstID); try await finishSend(model)
            let sent = model.e2eCommands.last { $0.action == .contactReply }!
            try check(sent.peerID == firstID && model.state.contacts?.first?.lastReplyResult == "accepted", "model sends selected contact")
            var duplicate = sent
            // Reconstruct with the original UUID and fresh expiry if needed.
            var raw = try JSONSerialization.jsonObject(with: JSONEncoder().encode(duplicate)) as! [String:Any]
            raw["issuedAt"] = Date().timeIntervalSince1970; raw["expiresAt"] = Date().timeIntervalSince1970 + 29
            duplicate = try JSONDecoder().decode(WatchCommand.self, from: JSONSerialization.data(withJSONObject: raw))
            let repeated = try await exchange(duplicate)
            try check(repeated.result == "accepted", "explicit duplicate keeps original outcome")
            try await ready(model)
            model.send(.contactReply, reply: .needHelp, contactID: "DC000000-0000-4000-8000-000000000002"); try await finishSend(model)
            try check(model.state.contacts?[1].lastReplyResult == "noPeer", "failed contact handoff remains failed")
            let changed = try await exchange(WatchCommand(action: .contactReply, peerID: "DC000000-0000-4000-8000-000000000003", reply: .safe))
            try check(changed.result == "noPeer", "changed contact key blocks send")
            let missing = try await exchange(WatchCommand(action: .contactReply, peerID: UUID().uuidString, reply: .safe))
            try check(missing.result == "noPeer", "removed contact blocks send")
            let before = model.e2eCommands.filter { $0.action == .contactReply }.count
            try await Task.sleep(for: .seconds(17))
            try check(model.e2eCommands.filter { $0.action == .contactReply }.count == before && !model.state.sosActive, "status polling never resends contact replies or starts SOS")
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let contactsBefore = try encoder.encode(model.state.contacts)
            try contactsBefore.write(to: documents.appendingPathComponent("contacts-before-restart.json"), options: .atomic)
            try chatPhase("contacts_reopen_ready")
            let reboot = ProcessInfo.processInfo.environment["CRISIS_WATCH_CONTACT_REBOOT"] == "1"
            try await chatHost("contacts_reopened", timeout: reboot ? 600 : 120)
            try await ready(model)
            let contactsAfter = try encoder.encode(model.state.contacts)
            try contactsAfter.write(to: documents.appendingPathComponent("contacts-after-restart.json"), options: .atomic)
            try check(contactsBefore == contactsAfter, "reconnected watch preserves full contact roster and message previews")
            try check(model.state.contacts?.first?.lastReplyResult == "accepted" && model.state.contacts?[1].lastReplyResult == "noPeer", "reconnected watch retains accepted and failed outcomes")
            for original in model.e2eCommands.filter({ $0.action == .contactReply }) {
                var fresh = try JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as! [String: Any]
                fresh["issuedAt"] = Date().timeIntervalSince1970
                fresh["expiresAt"] = Date().timeIntervalSince1970 + 29
                let command = try JSONDecoder().decode(WatchCommand.self, from: JSONSerialization.data(withJSONObject: fresh))
                let receipt = try await exchange(command)
                try check(receipt.result == (original.reply == .safe ? "accepted" : "noPeer"), "duplicate after phone restart retains original outcome")
            }
            try await Task.sleep(for: .seconds(17))
            try check(model.e2eCommands.filter { $0.action == .contactReply }.count == before && !model.state.sosActive, "phone restart never resends replies or starts SOS")
            save("passed")
        } catch { save("failed: \(error)") }
    }
    private func chatPhase(_ name:String) throws {try Data(name.utf8).write(to:documents.appendingPathComponent("chat-lifecycle-phase.txt"),options:.atomic)}
    private func chatHost(_ name:String, timeout: Int = 120) async throws {
        let url=documents.appendingPathComponent("chat-lifecycle-"+name)
        for _ in 0..<(timeout * 5) {
            if FileManager.default.fileExists(atPath:url.path) {return}
            try await Task.sleep(for:.milliseconds(200))
        }
        throw Failure.timeout
    }
    private func ready(_ model:WatchSessionModel) async throws {
        model.refresh()
        // Cold simulator boot can delay daemon activation under host memory
        // pressure. This only extends the opt-in test's status/readiness wait.
        let attempts = ProcessInfo.processInfo.environment["CRISIS_WATCH_CONTACT_REBOOT"] == "1" ? 720 : 240
        for attempt in 0..<attempts {
            if attempt % 20 == 0 { saveConnectivity(model) }
            if !model.busy && model.fresh {return}
            if !model.busy {model.refresh()}
            try await Task.sleep(for:.milliseconds(250))
        }
        saveConnectivity(model)
        throw Failure.timeout
    }
    private func saveConnectivity(_ model: WatchSessionModel) {
        var snapshot = model.e2eConnectivity
        snapshot["timestamp"] = Date().timeIntervalSince1970
        if let data = try? JSONSerialization.data(withJSONObject: snapshot, options: [.prettyPrinted, .sortedKeys]) {
            try? data.write(to: documents.appendingPathComponent("watch-connectivity-diagnostics.json"), options: .atomic)
        }
    }
    private func finishSend(_ model:WatchSessionModel) async throws {
        for _ in 0..<160 {
            if !model.busy {return}
            try await Task.sleep(for:.milliseconds(100))
        }
        throw Failure.timeout
    }
    private func runChatLifecycle(_ model:WatchSessionModel) async {
        func replies()->[WatchCommand] {model.e2eCommands.filter {$0.action == .quickReply}}
        do {
            try await ready(model)
            try check(model.state.peerID == fixturePeer && !model.state.sosActive && !model.state.bleBroadcasting,"isolated lifecycle receiver, no SOS")
            model.send(.quickReply,reply:.safe);try await finishSend(model)
            try check(replies().count == 1 && model.notice.contains("ulaştı"),"foreground reply accepted")
            try chatPhase("background_ready");try await chatHost("background")
            try await ready(model);model.send(.quickReply,reply:.injured);try await finishSend(model)
            try check(replies().count == 2 && !model.notice.contains("ulaştı"),"background reply uncertain")
            try chatPhase("drop_ready");try await chatHost("drop")
            try await ready(model);model.send(.quickReply,reply:.needHelp);try await finishSend(model)
            try check(replies().count == 3 && !model.notice.contains("ulaştı"),"lost valid receipt does not claim success")
            try await ready(model)
            try chatPhase("kill_ready");try await chatHost("dead")
            model.send(.quickReply,reply:.safe);try await finishSend(model)
            let closedAttempts=replies().count
            try check(closedAttempts == 3 || closedAttempts == 4,"closed app attempt sent once or blocked before enqueue")
            rows.append(["closedAppNotice":model.notice,"modelReplyAttempts":closedAttempts])
            try chatPhase("closed_verified");try await chatHost("offline")
            for _ in 0..<80 {
                if !WCSession.default.isReachable {break}
                try await Task.sleep(for:.milliseconds(250))
            }
            model.send(.quickReply,reply:.safe);try await finishSend(model)
            try check(!model.fresh && !model.busy,"offline phone is not actionable")
            let beforeReconnect=replies().count
            rows.append(["offlineNotice":model.notice,"modelReplyAttempts":beforeReconnect])
            try chatPhase("offline_verified");try await chatHost("reopened")
            try await ready(model)
            try await Task.sleep(for:.seconds(17))
            try check(replies().count == beforeReconnect,"reconnect and automatic status poll never replay replies")
            let original=replies()[2]
            var json=try JSONSerialization.jsonObject(with:JSONEncoder().encode(original)) as! [String:Any]
            json["issuedAt"]=Date().timeIntervalSince1970;json["expiresAt"]=Date().timeIntervalSince1970+30
            let duplicate=try JSONDecoder().decode(WatchCommand.self,from:JSONSerialization.data(withJSONObject:json))
            let receipt=try await exchange(duplicate)
            try check(receipt.result == "noPeer","explicit duplicate keeps original failed result")
            try chatPhase("verify_rows");try await chatHost("verified")
            try chatPhase("cold_ready");try await chatHost("cold_reopened")
            try await ready(model)
            try check(replies().count == beforeReconnect && !model.state.sosActive,"second cold open does not replay replies or start SOS")
            let attempts=try JSONSerialization.jsonObject(with:JSONEncoder().encode(replies()))
            rows.append(["modelReplyAttempts":attempts,"automaticReplyRetries":0])
            save("passed");try chatPhase("finished")
        } catch {save("failed: \(error)");try? chatPhase("failed")}
    }
    private func runChat() async {
        do {
            let initial = try await recoveryStatus()
            try check(initial.state.peerID == fixturePeer && !initial.state.bleBroadcasting && !initial.state.sosActive, "isolated chat receiver, no SOS")
            for reply in WatchQuickReply.allCases {
                let command = WatchCommand(action: .quickReply, peerID: fixturePeer, reply: reply)
                let receipt = try await exchange(command)
                let expected = reply == .safe ? "accepted" : reply == .injured ? "uncertain" : "noPeer"
                try check(receipt.result == expected && !receipt.state.sosActive, "watch chat result " + expected)
                if reply == .safe {
                    let duplicate = try await exchange(command)
                    try check(duplicate.result == "accepted", "duplicate watch reply acknowledged once")
                }
            }
            save("passed")
        } catch { save("failed: \(error)") }
    }
    private var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    private func phase(_ name: String) throws { try Data(name.utf8).write(to: documents.appendingPathComponent("recovery-phase.txt"), options: .atomic) }
    private func waitHost(_ name: String) async throws {
        let url = documents.appendingPathComponent("recovery-" + name)
        for _ in 0..<120 {
            if FileManager.default.fileExists(atPath: url.path) { return }
            try await Task.sleep(for: .seconds(1))
        }
        throw Failure.timeout
    }
    private func recoveryStatus() async throws -> WatchReceipt {
        for _ in 0..<45 {
            if WCSession.default.activationState == .activated && WCSession.default.isReachable {
                do { return try await exchange(WatchCommand(action: .status)) } catch { }
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw Failure.timeout
    }
    private func runRecovery() async {
        do {
            let saved = try await recoveryStatus()
            try check(saved.state.peerID == fixturePeer && !saved.state.bleBroadcasting, "isolated recovery phone")
            try check(saved.state.recoveryRequired == true && !saved.state.sosActive, "saved SOS is paused")
            guard let id = saved.state.emergencyID else { throw Failure.malformed }
            try phase("kill"); try await waitHost("reopened")
            let reopened = try await recoveryStatus()
            try check(reopened.state.recoveryRequired == true && reopened.state.emergencyID == id && !reopened.state.sosActive, "cold phone relaunch preserves paused identity")
            let request = try await exchange(WatchCommand(action: .startSOS, emergencyID: id))
            try check(request.result == "phoneConfirmation" && !request.state.sosActive, "watch resume requires phone confirmation")
            try phase("approve")
            var active = request
            for _ in 0..<100 {
                active = try await recoveryStatus()
                if active.state.sosActive { break }
                try await Task.sleep(for: .seconds(1))
            }
            try check(active.state.sosActive && active.state.emergencyID == id && active.state.recoveryRequired != true, "phone UI resumes same SOS session")
            let stop = WatchCommand(action: .stopSOS, emergencyID: id)
            let ended = try await exchange(stop)
            try check(ended.result == "accepted" && !ended.state.sosActive && ended.state.recoveryRequired != true, "watch ends phone saved SOS")
            let duplicate = try await exchange(stop)
            try check(duplicate.result == "accepted" && !duplicate.state.sosActive, "duplicate stop stays ended")
            try phase("ended"); try await waitHost("off-reopened")
            let off = try await recoveryStatus()
            try check(!off.state.sosActive && off.state.recoveryRequired != true && off.state.emergencyID == nil, "second cold relaunch stays off")
            try check(off.state.lastMessage == "İyiyim", "saved reply preserved after final relaunch")
            save("passed"); try phase("finished")
        } catch { save("failed: \(error)"); try? phase("failed") }
    }
    private func check(_ condition: Bool, _ label: String) throws {
        rows.append(["check": label, "passed": condition])
        save("running")
        if !condition { throw Failure.check(label) }
    }
    private func exchange(_ command: WatchCommand) async throws -> WatchReceipt {
        let payload = try JSONEncoder().encode(command)
        let key = UUID()
        awaiting = key
        return try await withCheckedThrowingContinuation { continuation in
            WCSession.default.sendMessage(["watchCommand": payload], replyHandler: { response in
                let data = response["watchReceipt"] as? Data
                Task { @MainActor in
                    guard self.awaiting == key else { return }
                    self.awaiting = nil
                    guard let data, let receipt = try? JSONDecoder().decode(WatchReceipt.self, from: data), receipt.id == command.id else {
                        continuation.resume(throwing: Failure.malformed); return
                    }
                    self.rows.append(["command": command.action.rawValue, "id": command.id, "result": receipt.result,
                        "sosActive": receipt.state.sosActive, "emergencyID": receipt.state.emergencyID ?? "",
                        "bleBroadcasting": receipt.state.bleBroadcasting, "lastMessage": receipt.state.lastMessage ?? ""])
                    self.save("running")
                    continuation.resume(returning: receipt)
                }
            }, errorHandler: { error in
                Task { @MainActor in
                    guard self.awaiting == key else { return }
                    self.awaiting = nil
                    continuation.resume(throwing: error)
                }
            })
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(12))
                guard self.awaiting == key else { return }
                self.awaiting = nil
                continuation.resume(throwing: Failure.timeout)
            }
        }
    }
    private func save(_ result: String) {
        let object: [String: Any] = ["transport": "WatchConnectivity.sendMessage", "result": result, "rows": rows]
        if let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]) {
            let path = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("watch-e2e-results.json")
            try? data.write(to: path, options: .atomic)
        }
    }
}
#endif
