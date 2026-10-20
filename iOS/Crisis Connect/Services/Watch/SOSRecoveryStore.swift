import Foundation
import Combine

/// Reading recovery history is passive: it never starts a broadcaster or retransmits a reply.
final class SOSRecoveryStore: ObservableObject {
    static let shared = SOSRecoveryStore()
    struct Session: Codable, Equatable { let id: String; let startedAt: Date }
    struct Reply: Codable, Equatable { let id: String; let peer: String; let text: String; var result: String; var createdAt: Date? = nil; var kind: String? = nil }
    struct Snapshot: Codable { var session: Session?; var replies: [Reply] = [] }
    @Published private(set) var snapshot = Snapshot()
    @Published private(set) var unavailable = false
    private let url: URL
    init(url: URL? = nil) {
        self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("sos-recovery-v1.json")
        if FileManager.default.fileExists(atPath: self.url.path) {
            do { snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: self.url)) }
            catch { unavailable = true }
        }
    }
    func begin(resuming id: String? = nil, now: Date = .now) throws -> Session {
        guard !unavailable else { throw Failure.unavailable }
        if let id {
            guard let saved = snapshot.session, saved.id == id else { throw Failure.changed }
            return saved
        }
        guard snapshot.session == nil else { throw Failure.confirmationRequired }
        let session = Session(id: UUID().uuidString, startedAt: now)
        var next = snapshot; next.session = session; try save(next)
        return session
    }
    func finish() throws { var next = snapshot; next.session = nil; try save(next) }
    func reserve(id: String, peer: String, text: String, kind: String? = nil) throws {
        guard !snapshot.replies.contains(where: { $0.id == id }) else { return }
        var next = snapshot; next.replies.append(Reply(id: id, peer: peer, text: text, result: "uncertain", createdAt: .now, kind: kind)); try save(next)
    }
    func complete(id: String, result: String) throws {
        var next = snapshot
        if let index = next.replies.firstIndex(where: { $0.id == id }) { next.replies[index].result = result }
        try save(next)
    }
    func clearReplies() throws { var next = snapshot; next.replies = []; try save(next) }
    private func save(_ next: Snapshot) throws {
        guard !unavailable else { throw Failure.unavailable }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(next).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var excluded = url; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? excluded.setResourceValues(values)
        snapshot = next
    }
    enum Failure: Error { case unavailable, changed, confirmationRequired }
}

import SwiftUI
struct SavedSOSRecoveryView: View {
    var onClose: () -> Void = {}
    @ObservedObject private var store = SOSRecoveryStore.shared
    @ObservedObject private var manager = SOSBroadcastManager.shared
    private var tr: Bool { Locale.current.language.languageCode?.identifier == "tr" }
    private func label(_ en: String, _ trText: String) -> String { tr ? trText : en }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(label("Saved SOS", "Kayıtlı SOS")).font(.largeTitle.bold()).accessibilityIdentifier("savedSOSTitle")
                Text(label("Your SOS and replies were saved. Broadcasting is paused. Confirm to continue the same session.", "SOS ve cevapların korundu. Yayın duraklatıldı. Aynı oturuma devam etmek için onay ver."))
                ForEach(store.snapshot.replies.filter { $0.kind != "contact" }.suffix(3), id: \.id) { reply in
                    Text(reply.text).font(.headline)
                    Text(reply.result == "accepted" ? label("Handed to the connection; delivery unconfirmed.", "Bağlantıya verildi; teslim doğrulanmadı.") : label("Unconfirmed; will not be resent automatically.", "Doğrulanmadı; otomatik tekrar gönderilmeyecek.")).font(.caption)
                }
                if store.unavailable { Text(label("Saved history unavailable. No SOS restarted.", "Kayıt okunamadı. SOS yeniden başlatılmadı.")) }
                if let error = manager.recoveryError { Text(error).foregroundStyle(.red) }
                Button(label("Continue saved SOS", "Kayıtlı SOS’a devam et")) {
                    guard let id = store.snapshot.session?.id else { return }
                    manager.start(declaringEmergency: true, resumingID: id)
                }.buttonStyle(.borderedProminent).accessibilityIdentifier("resumeSavedSOS").disabled(store.unavailable)
                Button(label("End saved SOS", "Kayıtlı SOS’u bitir"), role: .destructive) { manager.stop(); if !manager.recoveryRequired { onClose() } }
                    .accessibilityIdentifier("endSavedSOS").disabled(store.unavailable)
            }.padding(24)
        }.background(Color(.systemBackground))
    }
}
