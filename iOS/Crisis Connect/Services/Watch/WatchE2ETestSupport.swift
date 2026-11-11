import Foundation
import SwiftUI
import Combine
import WatchConnectivity
import UIKit

/// Never enabled on a physical device or in a distribution build.
nonisolated enum WatchE2ETestMode {
    // A watch status request can cold-launch the phone before simctl supplies environment
    // variables after a device reboot. Keep that launch isolated too; the host removes
    // this marker after stopping both test apps. Never available on physical devices.
    static var contactRebootEnabled: Bool {
        #if DEBUG && targetEnvironment(simulator)
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("watch-contact-reboot-test-mode")
        let env = ProcessInfo.processInfo.environment
        if env["CRISIS_WATCH_E2E"] == "1", env["CRISIS_WATCH_CONTACT_TEST"] == "1",
           env["CRISIS_WATCH_CONTACT_REBOOT"] == "1", !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? Data("isolated simulator contact reboot test".utf8).write(to: url, options: .atomic)
        }
        return FileManager.default.fileExists(atPath: url.path)
        #else
        return false
        #endif
    }
    static var chatLifecycleEnabled: Bool {
        #if DEBUG && targetEnvironment(simulator)
        let url=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("watch-chat-lifecycle-test-mode")
        let env=ProcessInfo.processInfo.environment
        if env["CRISIS_WATCH_E2E"] == "1" && env["CRISIS_WATCH_CHAT_LIFECYCLE"] == "1" && !FileManager.default.fileExists(atPath:url.path) {
            try? FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
            try? Data("isolated simulator lifecycle test".utf8).write(to:url,options:.atomic)
        }
        return FileManager.default.fileExists(atPath:url.path)
        #else
        return false
        #endif
    }
    static var chatEnabled: Bool { ProcessInfo.processInfo.environment["CRISIS_WATCH_CHAT_TEST"] == "1" || chatLifecycleEnabled }
    static var contactsEnabled: Bool { ProcessInfo.processInfo.environment["CRISIS_WATCH_CONTACT_TEST"] == "1" || contactRebootEnabled }
    static var enabled: Bool {
        #if DEBUG && targetEnvironment(simulator)
        contactRebootEnabled || chatLifecycleEnabled || ProcessInfo.processInfo.environment["CRISIS_WATCH_E2E"] == "1"
        #else
        false
        #endif
    }
}

#if DEBUG && targetEnvironment(simulator)
@MainActor
final class WatchE2ETestSupport: ObservableObject {
    static let shared = WatchE2ETestSupport()
    static let peerID = "DE000000-0000-4000-8000-000000000001"
    static let contactIDs = ["DC000000-0000-4000-8000-000000000001", "DC000000-0000-4000-8000-000000000002", "DC000000-0000-4000-8000-000000000003"]
    static let contactNames = ["Ayşe · test", "Mehmet · test", "Anahtarı değişen kişi"]
    static func contact(_ id: UUID) -> ContactRecord? {
        if WatchE2ETestMode.contactRebootEnabled { return rebootContactStore.contact(for: id) }
        return fixtureContact(id)
    }
    private static func fixtureContact(_ id: UUID) -> ContactRecord? {
        guard let index = contactIDs.firstIndex(of: id.uuidString) else { return nil }
        return ContactRecord(id: id, name: contactNames[index], broadcastId: "test", sessionCode: "test-\(index)", aesKeyBase64: "", preferredTransport: .bleGatt,
            peerUid: "isolated-\(index)", peerPublicKey: "isolated-key", peerKeyChanged: index == 2, createdAt: .now, lastUpdated: .now)
    }
    static let rebootContactStore: ContactStore = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("watch-reboot-contacts.json")
        // Seed once at the explicit start of the test, then use the production
        // ContactStore encrypted-file reader on every cold launch, without reseeding.
        if ProcessInfo.processInfo.environment["CRISIS_WATCH_CHAT_RESET"] == "1" {
            let records = contactIDs.compactMap { UUID(uuidString: $0).flatMap(fixtureContact) }
            do { try LocalEncryptedFileStore.write(JSONEncoder().encode(records), to: url) }
            catch { assertionFailure("Could not persist isolated reboot contacts: \(error)") }
        }
        return ContactStore(persistenceURL: url)
    }()
    static var contacts: [WatchSavedContact] {
        contactIDs.compactMap { id in
            guard let record = UUID(uuidString: id).flatMap(contact) else { return nil }
            let saved = SOSRecoveryStore.shared.snapshot.replies.last { $0.peer == id }
            return WatchSavedContact(id: id, name: record.name, lastMessage: saved?.text ?? "", canSend: WatchContactMessaging.eligible(record), internetCapable: record.supportsInternet, keyChanged: record.peerKeyChanged == true, lastReplyResult: saved?.result ?? "")
        }
    }
    func recordContactEffect(id: String, peer: String) { append(["id":id,"peer":peer],to:"watch-contact-effects.json") }
    @Published private(set) var replies: [String] = []
    @Published private(set) var events: [String] = []

    private var lifecycleSubscriptions=Set<AnyCancellable>()
    private init() {
        replies=SOSRecoveryStore.shared.snapshot.replies.filter {$0.result == "accepted"}.map(\.text)
        if WatchE2ETestMode.chatLifecycleEnabled {
            for name in [UIApplication.didBecomeActiveNotification,UIApplication.didEnterBackgroundNotification] {
                NotificationCenter.default.publisher(for:name).sink { [weak self] _ in Task { @MainActor in self?.recordLifecycle() } }.store(in:&lifecycleSubscriptions)
            }
            recordLifecycle()
        }
    }
    func recordLifecycle() {
        let row:[String:Any]=["applicationState":UIApplication.shared.applicationState.rawValue,"pid":ProcessInfo.processInfo.processIdentifier]
        if let data=try? JSONSerialization.data(withJSONObject:row) {try? data.write(to:documents.appendingPathComponent("watch-chat-lifecycle-state.json"),options:.atomic)}
    }
    private var documents:URL {FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0]}
    private func append(_ row:[String:Any],to name:String) {
        let url=documents.appendingPathComponent(name)
        let old=(try? Data(contentsOf:url)).flatMap {try? JSONSerialization.jsonObject(with:$0) as? [[String:Any]]} ?? []
        if let data=try? JSONSerialization.data(withJSONObject:old+[row],options:[.prettyPrinted,.sortedKeys]) {try? data.write(to:url,options:.atomic)}
    }
    func recordEffect(_ command:WatchCommand,result:String) {
        guard WatchE2ETestMode.chatLifecycleEnabled else {return}
        append(["id":command.id,"reply":command.reply?.rawValue ?? "","result":result,"applicationState":UIApplication.shared.applicationState.rawValue,"pid":ProcessInfo.processInfo.processIdentifier],to:"watch-chat-effects.json")
    }
    func receive(_ text: String) { replies = SOSRecoveryStore.shared.snapshot.replies.filter { $0.result == "accepted" }.map(\.text) }
    func record(_ command: WatchCommand, result: String, state: WatchPhoneState) {
        let event = "\(command.action.rawValue) \(command.id) → \(result), SOS=\(state.sosActive), BLE=\(state.bleBroadcasting)"
        events.append(event)
        if WatchE2ETestMode.chatLifecycleEnabled {
            append(["id":command.id,"action":command.action.rawValue,"result":result,"applicationState":UIApplication.shared.applicationState.rawValue,"pid":ProcessInfo.processInfo.processIdentifier],to:"watch-chat-commands.json")
            recordLifecycle()
        }
        let report: [String: Any] = ["transport": "WatchConnectivity", "externalSOSDelivery": false,
            "events": events, "replies": replies, "sosActive": state.sosActive,
            "emergencyID": state.emergencyID ?? "", "bleBroadcasting": state.bleBroadcasting]
        if let data = try? JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]) {
            let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("watch-e2e-phone.json")
            try? data.write(to: url, options: .atomic)
        }
    }
}

struct WatchE2EPhoneView: View {
    @ObservedObject private var manager = SOSBroadcastManager.shared
    @ObservedObject private var test = WatchE2ETestSupport.shared
    @ObservedObject private var recovery = SOSRecoveryStore.shared
    var body: some View {
        if WatchE2ETestMode.contactsEnabled {
            WatchContactPreviewView()
        } else if WatchE2ETestMode.chatEnabled {
            WatchChatPreviewView()
        } else { NavigationStack {
            List {
                Section("KONTROLLÜ SİMÜLATÖR TESTİ") {
                    Text("Gerçek WatchConnectivity bağlantısı")
                    Text("Bulut, kişiler, konum ve BLE yayını kapalı.")
                    Text(manager.recoveryRequired ? "SOS kayıtlı · duraklatıldı" : manager.isEmergencyDeclared ? "SOS aktif" : "SOS kapalı")
                        .font(.title.bold()).foregroundStyle(manager.isEmergencyDeclared ? .red : .green)
                    Text(manager.emergencySessionID ?? "Aktif oturum yok").font(.caption)
                }
                if manager.recoveryRequired { SavedSOSRecoveryView() }
                Section("Telefonun aldığı kısa cevaplar · test alıcısı") {
                    ForEach(Array(test.replies.enumerated()), id: \.offset) { _, reply in Text(reply) }
                }
                Section("Telefona ulaşan komutlar") {
                    ForEach(Array(test.events.suffix(12).enumerated()), id: \.offset) { _, event in Text(event).font(.caption) }
                }
            }.navigationTitle("Saat bağlantı testi")
        } }
    }
}
struct WatchContactPreviewView: View {
    @ObservedObject private var store = SOSChatStore.shared
    var body: some View {
        GeometryReader { proxy in NavigationStack { ScrollView { VStack(spacing: 12) {
            Text("Kişi mesaj testi · Dış gönderimler kapalı").font(.caption)
            ForEach(WatchE2ETestSupport.contactIDs, id: \.self) { id in
                let peer = UUID(uuidString: id)!
                Text(WatchE2ETestSupport.contact(peer)?.name ?? "Kişi yükleniyor…").font(.headline)
                ForEach(store.messages(for: peer)) { message in
                    SOSChatBubble(message: message, layout: ChatLayoutMetrics(availableWidth: proxy.size.width), onImageTap: nil, onLocationTap: nil, onFileTap: nil)
                }
            }
        }.padding(16) }.navigationTitle("Kayıtlı kişiler")
        .onReceive(store.$messagesBySession) { sessions in
            let rows = WatchE2ETestSupport.contactIDs.flatMap { sessions[UUID(uuidString: $0)!, default: []] }
            if let data = try? JSONEncoder().encode(rows) { try? data.write(to: FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("watch-contact-projection.json"),options:.atomic) }
        }
        } }
    }
}
struct WatchChatPreviewView: View {
    @ObservedObject private var store = SOSChatStore.shared
    var body: some View {
        GeometryReader { proxy in
            NavigationStack {
                ScrollView {
                    VStack(spacing: 16) {
                        Text("KONTROLLÜ TEST · Dış gönderimler kapalı").font(.caption)
                        ForEach(store.messages(for: UUID(uuidString: WatchE2ETestSupport.peerID)!)) { message in
                            SOSChatBubble(message: message, layout: ChatLayoutMetrics(availableWidth: proxy.size.width), onImageTap: nil, onLocationTap: nil, onFileTap: nil)
                        }
                    }.padding(16)
                }.navigationTitle("Saat test sohbeti")
                .onReceive(Timer.publish(every:0.5,on:.main,in:.common).autoconnect()) { _ in
                    guard WatchE2ETestMode.chatLifecycleEnabled else {return}
                    let request=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("watch-chat-background-request")
                    if FileManager.default.fileExists(atPath:request.path) {
                        try? FileManager.default.removeItem(at:request)
                        UIApplication.shared.open(URL(string:UIApplication.openSettingsURLString)!)
                    }
                }
                .onReceive(store.$messagesBySession) { sessions in
                    let messages = sessions[UUID(uuidString: WatchE2ETestSupport.peerID)!, default: []]
                    if let data = try? JSONEncoder().encode(messages) {
                        try? data.write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("watch-chat-projection.json"), options: .atomic)
                    }
                }
            }
        }
    }
}
#endif
