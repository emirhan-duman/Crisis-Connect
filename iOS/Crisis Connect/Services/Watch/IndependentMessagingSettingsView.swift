import SwiftUI
import FirebaseAuth
import CryptoKit

struct IndependentMessagingSettingsView: View {
    @State private var inbox: IndependentPhoneInbox?
    @State private var notice = "Saatten gelen şifreli mesajlar normal sohbetlerinde görünür. Gönderen cihazı ve kişiyi burada onayla."
    @State private var busy = false
    @State private var selected: ContactRecord?
    @State private var devices: [[String: Any]] = []
    @State private var approval: [String: Any]?
    @State private var showApproval = false
    @ObservedObject private var contacts = ContactStore.shared
    var body: some View {
        List {
            Text(notice).font(.callout)
            if let inbox {
                Button("Bu telefonda mesaj almayı aç") { perform { try await inbox.enable(); notice = "Telefon alıcısı hazır. Kişinin cihazını seçip onayla." } }
                Button("Bağlı saatimi bu hesapla yetkilendir") { perform { try await inbox.pairWatch(); notice = "Saat yetkilendirildi. Kişi cihazlarını onaylayabilirsin." } }
                Button("Mesajları kontrol et") { perform { try await inbox.refresh(); notice = "Kayıtlı mesajlar ve teslim durumları güncellendi." } }
                Button("Bu telefonun yetkisini kaldır", role: .destructive) { perform { try await inbox.disable(); notice = "Alıcı yetkisi kaldırıldı; sohbet geçmişi korundu." } }
                Section("Kayıtlı kişiler") {
                    ForEach(contacts.contacts.filter(WatchContactMessaging.eligible)) { contact in
                        Button(contact.name) { perform {
                            guard inbox.isEnabled else { notice = "Önce bu telefonda mesaj almayı aç."; return }
                            selected = contact; devices = try await inbox.devices(contact: contact)
                            if devices.isEmpty { notice = "Bu kişi henüz bağımsız mesajlaşmayı açmamış." }
                        } }
                    }
                }
                if let selected {
                    Section(selected.name) {
                        ForEach(devices.indices, id: \.self) { index in
                            Button("\(devices[index]["kind"] as? String == "phoneInbox" ? "Telefon" : "Saat") · \((devices[index]["deviceID"] as? String ?? "").prefix(8))") {
                                approval = devices[index]; showApproval = true
                            }
                        }
                    }
                }
            }
        }
        .disabled(busy)
        .navigationTitle("Bağımsız saat mesajları")
        .task {
            guard let user = Auth.auth().currentUser, !user.isAnonymous else { notice = "Devam etmek için hesabına giriş yap."; return }
            do { inbox = try IndependentPhoneInbox(ownerUid: user.uid) } catch { notice = error.localizedDescription }
        }
        .alert("Cihazı onayla", isPresented: $showApproval) {
            Button("İptal", role: .cancel) {}
            Button("Yetkiyi kaldır", role: .destructive) { if let inbox, let approval { perform { try await inbox.revokeContact(approval); notice = "Cihazın kişi onayı kaldırıldı." } } }
            Button("Onayla") { if let inbox, let selected, let approval { perform { try await inbox.approve(contact: selected, descriptor: approval); notice = "Cihaz onaylandı." } } }
        } message: { Text("Bu cihazdan mesaj almaya izin verilsin mi? Karşı tarafta da onay gereklidir.\n\nCihaz doğrulama kodu:\n\(fingerprint)") }
    }
    private var fingerprint: String {
        guard let bundle = approval?["bundle"] as? [String: Any], let key = bundle["identityKey"] as? String,
              let bytes = Data(base64Encoded: key) else { return "" }
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
    private func perform(_ work: @escaping @MainActor () async throws -> Void) {
        busy = true
        Task { @MainActor in defer { busy = false }; do { try await work() } catch { notice = error.localizedDescription } }
    }
}
