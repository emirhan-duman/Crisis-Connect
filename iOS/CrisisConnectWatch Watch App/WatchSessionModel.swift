import Combine
import Foundation
import WatchConnectivity
import WatchKit

@MainActor
final class WatchSessionModel: NSObject, ObservableObject, WCSessionDelegate {
    @Published var state = WatchPhoneState()
    @Published var reachable = false
    @Published var busy = false
    @Published var notice = ""
    #if DEBUG && targetEnvironment(simulator)
    private(set) var e2eCommands:[WatchCommand]=[]
    private(set) var e2eActivationError = ""
    private(set) var e2eTransportError = ""
    var e2eConnectivity: [String: Any] {
        ["activationState": WCSession.default.activationState.rawValue,
         "sessionReachable": WCSession.default.isReachable,
         "companionInstalled": WCSession.default.isCompanionAppInstalled,
         "activationError": e2eActivationError, "transportError": e2eTransportError, "fresh": fresh,
         "busy": busy, "notice": notice, "commands": e2eCommands.count]
    }
    #endif
    private var connectionFailed = false
    private var waitingForPhoneApproval = false
    private var pendingID: String?
    private var receivedAt: Date?
    private var newestStateTime: Double = 0

    // Explicit opt-in, compiled out of physical-device and Release builds.
    var simulationScenario: String? {
        #if DEBUG && targetEnvironment(simulator)
        let value = ProcessInfo.processInfo.environment["CRISIS_WATCH_SCENARIO"]
        return ["idle", "active", "disconnected", "unconfirmed"].contains(value ?? "") ? value : nil
        #else
        return nil
        #endif
    }

    var nativeE2ETestEnabled: Bool {
        #if DEBUG && targetEnvironment(simulator)
        ProcessInfo.processInfo.environment["CRISIS_WATCH_E2E"] == "1"
        #else
        false
        #endif
    }

    var fresh: Bool { reachable && receivedAt.map { Date().timeIntervalSince($0) < 30 } == true }
    var tr: Bool { Locale.current.language.languageCode?.identifier == "tr" }
    func label(_ english: String, _ turkish: String) -> String { tr ? turkish : english }

    override init() {
        super.init()
        if !nativeE2ETestEnabled, simulationScenario == nil,
           let data = UserDefaults.standard.data(forKey: "watch.displayState.v1"),
           let cached = try? JSONDecoder().decode(WatchPhoneState.self, from: data) {
            state = cached // Display only; receivedAt/reachable stay unset until a live response.
        }
        #if DEBUG && targetEnvironment(simulator)
        if let scenario = simulationScenario {
            if scenario != "disconnected" {
                state.sosActive = scenario == "active"
                state.bleBroadcasting = state.sosActive
                state.emergencyID = state.sosActive ? UUID().uuidString : nil
                state.peerID = UUID().uuidString
                state.peerName = label("Test rescue team", "Test kurtarma ekibi")
                state.lastMessage = label("How are you?", "Durumunuz nasıl?")
                state.updatedAt = Date().timeIntervalSince1970
                accept(state)
            }
            return
        }
        #endif
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func refresh() { if !busy { send(.status) } }
    func send(_ action: WatchAction, reply: WatchQuickReply? = nil, contactID: String? = nil) {
        #if DEBUG && targetEnvironment(simulator)
        if simulationScenario != nil {
            sendSimulated(action, reply: reply)
            return
        }
        #endif
        if nativeE2ETestEnabled && action != .status {
            guard state.peerID == "DE000000-0000-4000-8000-000000000001",
                  state.peerName == "Test alıcısı · dış iletim yok", !state.bleBroadcasting else {
                notice = label("Isolated phone test mode required.", "Telefonda kontrollü test modu gerekli.")
                return
            }
        }
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else {
            reachable = false
            connectionFailed = true
            notice = label("Phone unavailable. Open Crisis Connect on your iPhone.", "Telefon erişilemiyor. iPhone’da Crisis Connect’i aç.")
            return
        }
        guard !busy, action == .status || fresh else { return }
        if action == .contactReply {
            guard let contactID, state.contacts?.contains(where: { $0.id == contactID && $0.canSend }) == true else {
                notice = label("Contact unavailable. Refresh your phone connection.", "Kişiye erişilemiyor. Telefon bağlantısını yenile.")
                return
            }
        }
        let command = WatchCommand(action: action, emergencyID: state.emergencyID, peerID: action == .contactReply ? contactID : state.peerID, reply: reply)
        guard let data = try? JSONEncoder().encode(command) else { return }
        #if DEBUG && targetEnvironment(simulator)
        if nativeE2ETestEnabled {e2eCommands.append(command)}
        #endif
        busy = true
        pendingID = command.id
        if action != .status { notice = label("Waiting for phone…", "Telefon yanıtı bekleniyor…") }
        session.sendMessage(["watchCommand": data], replyHandler: { response in
            let receiptData = response["watchReceipt"] as? Data
            Task { @MainActor in
                guard self.pendingID == command.id else { return }
                guard let receiptData, let receipt = try? JSONDecoder().decode(WatchReceipt.self, from: receiptData), receipt.id == command.id else {
                    self.fail(command.id); return
                }
                self.busy = false; self.pendingID = nil
                self.accept(receipt.state)
                if action != .status {
                    self.waitingForPhoneApproval = receipt.result == "phoneConfirmation"
                    self.notice = self.resultText(receipt.result, action: action)
                    WKInterfaceDevice.current().play(receipt.result == "accepted" ? .success : .failure)
                }
            }
        }, errorHandler: { error in Task { @MainActor in
            #if DEBUG && targetEnvironment(simulator)
            self.e2eTransportError = String(describing: error)
            #endif
            self.fail(command.id)
        } })
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(10))
            if self.pendingID == command.id { self.fail(command.id) }
        }
    }

    private func resultText(_ result: String, action: WatchAction) -> String {
        switch result {
        case "accepted":
            switch action {
            case .startSOS: return label("Phone confirmed SOS activation.", "Telefon SOS’un başladığını doğruladı.")
            case .stopSOS: return label("Phone confirmed SOS ended.", "Telefon SOS’un bittiğini doğruladı.")
            case .quickReply:
                if nativeE2ETestEnabled { return label("Reply reached the phone test receiver.", "Cevap telefondaki test alıcısına ulaştı.") }
                return label("Reply handed to the rescue connection.", "Cevap kurtarma bağlantısına iletime verildi.")
            case .contactReply: return label("Message handed to the phone connection. Delivery unconfirmed.", "Mesaj telefon bağlantısına verildi. Teslim henüz doğrulanmadı.")
            case .status: return ""
            }
        case "phoneConfirmation": return label("Confirm the saved SOS on your iPhone. Broadcasting has not resumed.", "Kayıtlı SOS’u iPhone’da onayla. Yayın henüz devam etmedi.")
        case "noPeer": return action == .contactReply ? label("Contact or phone internet unavailable. No automatic resend.", "Kişi veya telefonun interneti erişilemiyor. Otomatik tekrar yok.") : label("No active rescue connection.", "Aktif kurtarma bağlantısı yok.")
        case "stateChanged": return label("SOS changed. Refresh and try again.", "SOS durumu değişti. Yenile ve tekrar dene.")
        case "bluetoothUnavailable": return label("Enable Bluetooth on your phone.", "Telefonda Bluetooth’u aç.")
        case "openPhone": return label("Open the phone app and allow Bluetooth.", "Telefon uygulamasını aç ve Bluetooth iznini ver.")
        default: return label("Action not confirmed. Refresh status before retrying.", "İşlem doğrulanamadı. Tekrar denemeden durumu yenile.")
        }
    }
    private func fail(_ id: String) {
        guard pendingID == id else { return }
        pendingID = nil; busy = false; receivedAt = nil; reachable = false
        connectionFailed = true
        notice = label("No confirmation. Refresh to check the phone.", "Doğrulama alınamadı. Telefonun durumunu yenile.")
    }
    private func accept(_ next: WatchPhoneState) {
        let now = Date().timeIntervalSince1970
        guard next.version == 1, next.updatedAt.isFinite, next.updatedAt > 0,
              next.updatedAt <= now + 5, next.updatedAt >= newestStateTime else { return }
        newestStateTime = next.updatedAt; state = next
        if !nativeE2ETestEnabled, simulationScenario == nil, let data = try? JSONEncoder().encode(next) {
            UserDefaults.standard.set(data, forKey: "watch.displayState.v1")
        }
        // Cached applicationContext must not make old state actionable.
        receivedAt = Date(timeIntervalSince1970: min(next.updatedAt, Date().timeIntervalSince1970))
        reachable = simulationScenario.map { $0 != "disconnected" } ?? WCSession.default.isReachable
        if fresh && connectionFailed {
            connectionFailed = false
            notice = label("Phone reconnected. Status updated.", "Telefon yeniden bağlandı. Durum güncellendi.")
        }
        if fresh && waitingForPhoneApproval && next.sosActive {
            waitingForPhoneApproval = false
            notice = label("Phone confirmed SOS activation.", "Telefon SOS’un başladığını doğruladı.")
        }
    }

    #if DEBUG && targetEnvironment(simulator)
    /// Exercises the real UI/countdown with local receipts; never activates WatchConnectivity.
    private func sendSimulated(_ action: WatchAction, reply: WatchQuickReply?) {
        guard simulationScenario != "disconnected" else {
            reachable = false
            connectionFailed = true
            notice = label("Phone unavailable. Open Crisis Connect on your iPhone.", "Telefon erişilemiyor. iPhone’da Crisis Connect’i aç.")
            return
        }
        guard !busy, action == .status || fresh else { return }
        let command = WatchCommand(action: action, emergencyID: state.emergencyID, peerID: state.peerID, reply: reply)
        busy = true
        pendingID = command.id
        if action != .status { notice = label("Waiting for phone…", "Telefon yanıtı bekleniyor…") }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(500))
            guard pendingID == command.id else { return }
            if simulationScenario == "unconfirmed", action != .status {
                fail(command.id)
                return
            }
            var next = state
            switch action {
            case .status: break
            case .startSOS:
                next.sosActive = true
                next.bleBroadcasting = true
                next.emergencyID = UUID().uuidString
            case .stopSOS:
                next.sosActive = false
                next.bleBroadcasting = false
                next.emergencyID = nil
            case .quickReply, .contactReply:
                next.lastMessage = label("Test reply: ", "Test cevabı: ") + ((tr ? reply?.turkish : reply?.english) ?? "")
            }
            next.updatedAt = Date().timeIntervalSince1970
            busy = false
            pendingID = nil
            accept(next)
            if action != .status {
                notice = resultText("accepted", action: action)
                WKInterfaceDevice.current().play(.success)
            }
        }
    }
    #endif
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        Task { @MainActor in
            #if DEBUG && targetEnvironment(simulator)
            self.e2eActivationError = error?.localizedDescription ?? ""
            #endif
            self.reachable = session.isReachable; self.refresh()
        }
    }
    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.reachable = session.isReachable; if self.reachable { self.refresh() } }
    }
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        #if CC_WATCH_INDEPENDENT
        guard let request = message["independentSetup"] as? [String: Any] else { replyHandler([:]); return }
        Task { @MainActor in
            do { replyHandler(["result": try IndependentWatchSetup.request(request)]) }
            catch { replyHandler(["error": "setup-unavailable"]) }
        }
        #else
        replyHandler(["error": "independent-not-supported"])
        #endif
    }
    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        guard let data = applicationContext["watchState"] as? Data,
              let next = try? JSONDecoder().decode(WatchPhoneState.self, from: data) else { return }
        Task { @MainActor in self.accept(next) }
    }
}
