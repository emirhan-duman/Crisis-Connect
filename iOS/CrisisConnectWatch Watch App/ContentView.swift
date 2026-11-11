import SwiftUI
import WatchKit

struct ContentView: View {
    @StateObject private var model = WatchSessionModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var countdown: Int?
    @State private var confirmStop = false
    @State private var showContacts = false
    @State private var showIndependent = false
    @State private var showConnectionHelp = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            if let seconds = countdown {
                VStack(spacing: 8) {
                    Text(model.label("Starting SOS", "SOS başlatılıyor")).font(.headline)
                    Text("\(seconds)")
                        .font(.system(size: 52, weight: .bold, design: .rounded)).foregroundStyle(.red)
                        .accessibilityIdentifier("countdown")
                    Button(model.label("Cancel", "İptal"), role: .cancel) { countdown = nil }
                        .accessibilityIdentifier("cancelCountdown")
                    Text(model.label("Cancel to prevent sending.", "Göndermemek için iptal et."))
                        .font(.caption2).foregroundStyle(.secondary)
                }.padding(.horizontal, 8)
            } else {
            ScrollView {
                VStack(spacing: 8) {
                    Label("Crisis Connect", systemImage: "shield.lefthalf.filled")
                        .font(.caption.bold()).foregroundStyle(.orange)
                    if model.simulationScenario != nil || model.nativeE2ETestEnabled {
                        Text(model.label("SIMULATOR TEST", "SİMÜLATÖR TESTİ"))
                            .font(.system(size: 10, weight: .bold)).foregroundStyle(.yellow)
                    }
                    Label(model.fresh ? model.label("Phone connected", "Telefon bağlı") : model.label("Status not current", "Durum güncel değil"),
                          systemImage: model.fresh ? "iphone.radiowaves.left.and.right" : "iphone.slash")
                        .font(.caption2).foregroundStyle(model.fresh ? .green : .yellow)
                    Text(model.state.updatedAt == 0 ? model.label("SOS status unknown", "SOS durumu bilinmiyor") : model.state.recoveryRequired == true ? model.label("SOS saved · paused", "SOS kayıtlı · duraklatıldı") : model.state.sosActive ? model.label("SOS active", "SOS aktif") : model.label("SOS inactive", "SOS kapalı"))
                        .font(.title3.bold()).foregroundStyle(model.state.sosActive ? .red : .primary)
                    if model.state.sosActive {
                        Text(model.state.bleBroadcasting ? model.label("Bluetooth broadcast active", "Bluetooth yayını aktif") : model.label("Bluetooth broadcast not confirmed", "Bluetooth yayını doğrulanmadı"))
                            .font(.caption2)
                    }
                        Button(role: model.state.sosActive ? nil : .destructive) {
                            if model.state.sosActive { confirmStop = true } else if model.state.recoveryRequired == true { model.send(.startSOS) } else { countdown = 5 }
                        } label: {
                            Label(model.state.recoveryRequired == true ? model.label("Resume on phone", "Telefonda devam et") : model.state.sosActive ? model.label("End SOS", "SOS’u bitir") : model.label("Start SOS", "SOS başlat"), systemImage: "sos.circle.fill")
                        }
                        .disabled(!model.fresh || model.busy)
                        .accessibilityIdentifier("sosAction")
                    if model.state.recoveryRequired == true {
                        Text(model.label("Confirm on the phone to resume. Broadcasting is paused.", "Devam etmek için telefonda onay ver. Yayın duraklatıldı.")).font(.caption2)
                        Button(model.label("End saved SOS", "Kayıtlı SOS’u bitir")) { confirmStop = true }.disabled(!model.fresh || model.busy)
                    }
                    if model.busy { ProgressView() }
                    if !model.notice.isEmpty { Text(model.notice).font(.caption2).accessibilityLabel(model.notice) }
                    if let name = model.state.peerName {
                        Text(name).font(.headline)
                        if let message = model.state.lastMessage { Text(message).font(.caption) }
                        ForEach(WatchQuickReply.allCases, id: \.rawValue) { reply in
                            Button(model.tr ? reply.turkish : reply.english) { model.send(.quickReply, reply: reply) }
                                .disabled(!model.fresh || model.busy || countdown != nil)
                                .accessibilityIdentifier("reply_\(reply.rawValue)")
                        }
                    } else {
                        if let message = model.state.lastMessage {
                            Text(model.label("Saved reply", "Kayıtlı cevap")).font(.caption2)
                            Text(message).font(.caption)
                            Text(model.label("Delivery unconfirmed; no automatic resend.", "Teslim doğrulanmadı; otomatik tekrar gönderilmez.")).font(.caption2)
                        }
                        Text(model.label("Quick replies become available when a rescue team connects.", "Kurtarma ekibi bağlandığında kısa cevaplar açılır.")).font(.caption2)
                    }
                    Button(model.label("Refresh status", "Durumu yenile")) { model.refresh() }.disabled(model.busy || countdown != nil)
                    #if CC_WATCH_INDEPENDENT
                    Button("Bağımsız mesajlar") { showIndependent = true }
                    #endif
                    Button(model.label("Saved contacts", "Kayıtlı kişiler")) { showContacts = true }
                        .accessibilityIdentifier("savedContacts")
                    Button(model.label("Connection options", "Bağlantı seçenekleri")) { showConnectionHelp = true }
                }.padding(.horizontal, 8).padding(.vertical, 4)
            }
            }
        }
        #if CC_WATCH_INDEPENDENT
        .sheet(isPresented: $showIndependent) { IndependentMessagesView() }
        #endif
        .sheet(isPresented: $showContacts) { WatchSavedContactsView(model: model) }
        .sheet(isPresented: $showConnectionHelp) {
            ScrollView { VStack(alignment: .leading, spacing: 12) {
                Text(model.label("Connection options", "Bağlantı seçenekleri")).font(.headline)
                Text(model.label("Crisis Connect currently sends through your phone. The watch cannot send independently over Wi-Fi or LTE.", "Crisis Connect şu an telefon üzerinden gönderir. Saatten bağımsız Wi-Fi/LTE gönderimi henüz yok."))
                Text(model.label("Saved contacts remain visible offline. Unconfirmed messages are never resent automatically.", "Kayıtlı kişiler çevrimdışıyken görülebilir. Doğrulanmayan mesajlar otomatik tekrar gönderilmez."))
                Text(model.label("Satellite features require a supported device and region. Use the watch's system SOS or Messages app where available; Crisis Connect does not control that satellite service.", "Uydu özellikleri desteklenen cihaz ve bölge gerektirir. Uygunsa saatin sistem SOS veya Mesajlar uygulamasını kullan; Crisis Connect bu uydu servisini yönetmez."))
            }.font(.caption).padding() }
        }
        .task {
            #if DEBUG && targetEnvironment(simulator)
            await WatchNativeE2ERunner.shared.run(model:model)
            #endif
        }
        .confirmationDialog(model.label("End the active SOS?", "Aktif SOS bitirilsin mi?"), isPresented: $confirmStop) {
            Button(model.label("End SOS", "SOS’u bitir"), role: .destructive) { model.send(.stopSOS) }
        }
        .task(id: countdown != nil) {
            guard countdown != nil else { return }
            for seconds in stride(from: 5, through: 1, by: -1) {
                guard !Task.isCancelled, scenePhase == .active else { countdown = nil; return }
                countdown = seconds
                WKInterfaceDevice.current().play(.click)
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
            guard !Task.isCancelled, countdown != nil, scenePhase == .active else { return }
            countdown = nil
            model.send(.startSOS)
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { countdown = nil; return }
            while !Task.isCancelled {
                model.refresh()
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
            }
        }
    }
}

struct WatchSavedContactsView: View {
    @ObservedObject var model: WatchSessionModel
    @State private var selectedID: String?
    @State private var pendingReply: WatchQuickReply?
    @State private var confirmSend = false
    private var contacts: [WatchSavedContact] { model.state.contacts ?? [] }
    private var selected: WatchSavedContact? { contacts.first { $0.id == selectedID } }

    var body: some View {
        ScrollView { VStack(spacing: 10) {
            if let contact = selected {
                Button(model.label("Contacts", "Kişiler")) { selectedID = nil }
                Text(contact.name).font(.headline)
                if !contact.lastMessage.isEmpty {
                    Text(model.label("Last message", "Son mesaj")).font(.caption2)
                    Text(contact.lastMessage).font(.caption)
                }
                if !contact.lastReplyResult.isEmpty {
                    Text(contact.lastReplyResult == "read" ? model.label("Read", "Okundu") : contact.lastReplyResult == "delivered" ? model.label("Delivered", "Teslim edildi") : contact.lastReplyResult == "accepted" ? model.label("Handed to connection · delivery unconfirmed", "Bağlantıya verildi · teslim doğrulanmadı") : model.label("Last send unconfirmed · no automatic resend", "Son gönderim doğrulanmadı · otomatik tekrar yok")).font(.caption2)
                }
                ForEach(WatchQuickReply.allCases, id: \.rawValue) { reply in
                    Button(model.tr ? reply.turkish : reply.english) { pendingReply = reply; confirmSend = true }
                        .disabled(!model.fresh || model.busy || !contact.canSend)
                        .accessibilityIdentifier("contactReply_\(reply.rawValue)")
                }
                if contact.keyChanged { Text(model.label("Verify this contact's changed key on the phone.", "Kişinin değişen anahtarını telefonda doğrula.")).font(.caption2) }
                else if !contact.internetCapable { Text(model.label("This contact has no internet messaging identity.", "Bu kişinin internet mesajlaşma bağlantısı tanımlı değil.")).font(.caption2) }
                else if !contact.canSend { Text(model.label("Phone internet connection required.", "Telefonun internet bağlantısı gerekli.")).font(.caption2) }
                if !model.notice.isEmpty { Text(model.notice).font(.caption2) }
            } else {
                Text(model.label("Saved contacts", "Kayıtlı kişiler")).font(.headline)
                ForEach(contacts) { contact in
                    Button(contact.name) { selectedID = contact.id }
                }
                if contacts.isEmpty { Text(model.label("Add contacts in Crisis Connect on your phone, then refresh.", "Telefondaki Crisis Connect’e kişi ekle, ardından yenile.")).font(.caption) }
                if (model.state.contactCount ?? 0) > contacts.count { Text(model.label("Showing \(contacts.count) contacts. Use the phone for the full list.", "\(contacts.count) kişi gösteriliyor. Tam liste için telefonu kullan.")).font(.caption2) }
            }
            Text(model.fresh ? model.label("Messages use the phone connection.", "Mesajlar telefon bağlantısıyla gönderilir.") : model.label("Phone unavailable · saved list only. Sending is disabled.", "Telefon erişilemiyor · kayıtlı liste. Gönderim kapalı.")).font(.caption2).foregroundStyle(.secondary)
            Button(model.label("Refresh", "Yenile")) { model.refresh() }.disabled(model.busy)
        }.padding(.horizontal, 8) }
        .confirmationDialog((selected?.name ?? "") + ": " + model.label("Send this message?", "Bu mesaj gönderilsin mi?"), isPresented: $confirmSend) {
            if let reply = pendingReply, let contact = selected {
                Button(model.tr ? reply.turkish : reply.english) { model.send(.contactReply, reply: reply, contactID: contact.id) }
            }
        }
    }
}
