#if CC_WATCH_INDEPENDENT && os(watchOS)
import SwiftUI
struct IndependentMessagesView: View {
    @State private var contacts: [[String: Any]] = []
    @State private var notice = "Önce telefondan saati ve kişiyi onayla."
    @State private var busy = false
    var body: some View {
        ScrollView {
            VStack {
                Text("Bağımsız mesajlar").font(.headline)
                Text(notice).font(.caption)
                Button("Durumu yenile") { run {
                    let service = try IndependentWatchSetup.service(), response = try await service.refresh()
                    contacts = response["contacts"] as? [[String: Any]] ?? []
                    for peer in contacts {
                        guard let uid = peer["ownerUid"] as? String, let bundle = peer["bundle"] as? [String: Any],
                              let index = (bundle["deviceId"] as? NSNumber)?.uint32Value, let identity = bundle["identityKey"] as? String else { throw IndependentWatchError.invalid }
                        try service.signal.pinPeer(uid: uid, device: index, identity: identity)
                    }
                    notice = contacts.isEmpty ? "Karşılıklı onaylanmış kişi yok." : "Kişiler ve teslim durumları güncel."
                } }
                ForEach(contacts.indices, id: \.self) { index in
                    Text(contacts[index]["name"] as? String ?? "Kayıtlı kişi")
                    ForEach(["İyiyim", "Yardıma ihtiyacım var", "Konumumdayım"], id: \.self) { text in
                        Button(text) { run {
                            let peer = contacts[index]
                            guard let uid = peer["ownerUid"] as? String, let id = peer["deviceID"] as? String,
                                  let bundle = peer["bundle"] as? [String: Any], let device = (bundle["deviceId"] as? NSNumber)?.uint32Value else { throw IndependentWatchError.invalid }
                            let clear = try JSONSerialization.data(withJSONObject: ["version": 1, "kind": "watchText", "text": text])
                            _ = try await IndependentWatchSetup.service().sendMessage(recipientInstallation: id, uid: uid, device: device, bundle: bundle, clear: clear)
                            notice = "Gönderim kabul edildi; teslim durumu için yenile."
                        } }
                    }
                }
            }
        }.disabled(busy)
    }
    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        busy = true
        Task { @MainActor in
            defer { busy = false }
            do { try await work() }
            catch { notice = "İşlem doğrulanamadı. Durumu yenile; otomatik tekrar gönderilmez." }
        }
    }
}
#endif
