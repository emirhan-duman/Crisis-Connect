//
//  RemoteSosSignalsService.swift
//  Crisis Connect
//
//  Live internet-side SOS signals for the rescuer's own agency panel
//  (`agencyPanels/{slug}/signals` — the same collection the web dashboard maps and Android's
//  RemoteSosSignals listens to). Read-only: signal writes stay with the victim uplink and
//  CrisisLink sync.
//

import Combine
import FirebaseAuth
import FirebaseFirestore
import Foundation

struct RemoteSosSignal: Identifiable, Equatable {
    let id: String
    let victimName: String?
    let victimBatteryPercent: Int?
    let latitude: Double?
    let longitude: Double?
    let source: String
    let lastSeenMillis: Int64
    let reporterCount: Int

    var hasLocation: Bool { latitude != nil && longitude != nil }
}

@MainActor
final class RemoteSosSignalsService: ObservableObject {

    static let shared = RemoteSosSignalsService()

    @Published private(set) var signals: [RemoteSosSignal] = []
    @Published private(set) var isLoading = true

    private var listeners: [ListenerRegistration] = []
    private var signalsByPanel: [String: [RemoteSosSignal]] = [:]
    private var resolveTask: Task<Void, Never>?

    private static let panelCollection = "agencyPanels"
    private static let signalsCollection = "signals"
    private static let queryLimit = 50
    private static let freshnessWindowMillis: Int64 = 24 * 60 * 60 * 1000

    func start() {
        guard listeners.isEmpty, resolveTask == nil else { return }
        resolveTask = Task { [weak self] in
            guard let self else { return }
            // Own panel first, then ancestors: victim self-reports land on the NATIONAL panel
            // (e.g. `afad`) while a sub-agency rescuer's slug is the child (`afad-istanbul`) —
            // rules already grant child→ancestor reads, mirroring the web dashboard.
            let panelIds = await Self.resolvePanelIds()
            self.resolveTask = nil
            guard !panelIds.isEmpty else {
                self.isLoading = false
                self.signals = []
                return
            }
            panelIds.forEach { self.attachListener(slug: $0) }
        }
    }

    func stop() {
        resolveTask?.cancel()
        resolveTask = nil
        listeners.forEach { $0.remove() }
        listeners = []
        signalsByPanel = [:]
    }

    private func publishMerged() {
        var newestBySignalId: [String: RemoteSosSignal] = [:]
        for signal in signalsByPanel.values.joined() {
            let key = signal.id.lowercased()
            if let existing = newestBySignalId[key], existing.lastSeenMillis >= signal.lastSeenMillis {
                continue
            }
            newestBySignalId[key] = signal
        }
        signals = newestBySignalId.values.sorted { $0.lastSeenMillis > $1.lastSeenMillis }
    }

    private func attachListener(slug: String) {
        let registration = Firestore.firestore()
            .collection(Self.panelCollection)
            .document(slug)
            .collection(Self.signalsCollection)
            .order(by: "lastSeenAt", descending: true)
            .limit(to: Self.queryLimit)
            .addSnapshotListener { [weak self] snapshot, error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.isLoading = false
                    guard error == nil, let snapshot else {
                        self.signalsByPanel[slug] = []
                        self.publishMerged()
                        return
                    }
                    let now = Int64(Date().timeIntervalSince1970 * 1000)
                    self.signalsByPanel[slug] = snapshot.documents.compactMap { doc -> RemoteSosSignal? in
                        let data = doc.data()
                        let lastSeenMillis = (data["lastSeenMillis"] as? NSNumber)?.int64Value
                            ?? (data["lastSeenAt"] as? Timestamp).map {
                                Int64($0.dateValue().timeIntervalSince1970 * 1000)
                            }
                        guard let lastSeenMillis,
                              now - lastSeenMillis <= Self.freshnessWindowMillis else {
                            return nil
                        }
                        let status = ((data["status"] as? String) ?? "active").lowercased()
                        guard status == "active" else { return nil }
                        let gps = (data["gps"] as? [String: Any])
                            ?? (data["victimGps"] as? [String: Any])
                            ?? (data["estimatedVictimGps"] as? [String: Any])
                        return RemoteSosSignal(
                            id: (data["signalId"] as? String) ?? doc.documentID,
                            victimName: (data["victimName"] as? String)?
                                .trimmingCharacters(in: .whitespacesAndNewlines)
                                .nilIfEmpty,
                            victimBatteryPercent: {
                                guard let value = (data["victimBatteryPercent"] as? NSNumber)?.intValue,
                                      (0...100).contains(value) else { return nil }
                                return value
                            }(),
                            latitude: (gps?["lat"] as? NSNumber)?.doubleValue,
                            longitude: (gps?["lon"] as? NSNumber)?.doubleValue,
                            source: (data["source"] as? String) ?? "unknown",
                            lastSeenMillis: lastSeenMillis,
                            reporterCount: (data["reporterIds"] as? [Any])?.count ?? 0
                        )
                    }
                    self.publishMerged()
                }
            }
        listeners.append(registration)
    }

    /// Own panel first, then its ancestors (parent/national), distinct, order-preserving.
    private static func resolvePanelIds() async -> [String] {
        guard let slug = await resolveAgencySlug(), !slug.isEmpty else { return [] }
        var panelIds: [String] = [slug]
        if let panelDoc = try? await Firestore.firestore()
            .collection(panelCollection)
            .document(slug)
            .getDocumentAsync() {
            let ancestors = (panelDoc.get("ancestorPanelIds") as? [Any])?
                .compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty } ?? []
            for ancestor in ancestors where !panelIds.contains(ancestor) {
                panelIds.append(ancestor)
            }
            if let parent = (panelDoc.get("parentPanelId") as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !parent.isEmpty, !panelIds.contains(parent) {
                panelIds.append(parent)
            }
        }
        return panelIds
    }

    /// The rescuer's panel id, mirroring CrisisLinkDashboardSync/Android's resolution chain:
    /// users/{uid} slug-ish fields first, else a slugified agency-ish field.
    private static func resolveAgencySlug() async -> String? {
        let uid = Auth.auth().currentUser?.uid ?? SecureLocalStore.shared.loadUid()
        guard let uid, !uid.isEmpty else { return nil }
        guard let snapshot = try? await Firestore.firestore()
            .collection("users")
            .document(uid)
            .getDocumentAsync(),
            snapshot.exists else {
            return nil
        }
        let slugFields = ["agencySlug", "panelSlug", "panelId", "agencyPanelId", "agencyKey", "panelKey"]
        for field in slugFields {
            if let value = (snapshot.get(field) as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !value.isEmpty {
                return value
            }
        }
        let agencyFields = ["agency", "agencyName", "authority", "institution", "organization"]
        for field in agencyFields {
            if let value = (snapshot.get(field) as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !value.isEmpty {
                let slug = AgencyRouter.slugify(value)
                return slug.isEmpty ? nil : slug
            }
        }
        return nil
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
