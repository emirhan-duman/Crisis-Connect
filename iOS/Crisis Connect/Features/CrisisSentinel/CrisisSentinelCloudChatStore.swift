//
//  CrisisSentinelCloudChatStore.swift
//  Crisis Connect
//
//  S3 — reads/writes the SAME Firestore collections the web dashboard uses
//  (`agencyPanels/{panelKey}/chats` + `messages`) so online (cloud) chats appear identically on
//  web and mobile. iOS port of Android's `CrisisSentinelCloudChatStore`.
//
//  The panel segment MUST be byte-identical to the web dashboard's `activeAgencyKey` (page.tsx),
//  or chats won't line up — see `derivePanelKey` / `toAgencyKey` / `normalizeAgencyDocumentId`.
//

import Foundation
import FirebaseAuth
import FirebaseFirestore

struct CrisisSentinelCloudSummary {
    let docId: String
    let title: String
    let updatedAt: Date
}

struct CrisisSentinelTurnSeqs {
    let userSeq: Int64
    let assistantSeq: Int64
}

actor CrisisSentinelCloudChatStore {
    static let shared = CrisisSentinelCloudChatStore()

    /// modelName web shows for on-device replies written into cloud chats (data, not UI).
    static let edgeModelName = "Crisis Sentinel Edge"
    private static let writeAckTimeout: TimeInterval = 4
    private static let agencyNameFields = ["agencyName", "agency", "authority", "institution", "organization"]

    private let db = Firestore.firestore()
    private let defaults = UserDefaults.standard
    private var cachedPanelKey: (uid: String, key: String?)?
    private var lastSeq: Int64 = 0

    private init() {}

    private func currentUid() -> String? { Auth.auth().currentUser?.uid }

    // MARK: - Panel key (byte-parity with web page.tsx)

    func resolvePanelKey(forceRefresh: Bool = false) async -> String? {
        guard let uid = currentUid() else { return nil }
        if !forceRefresh {
            if let cached = cachedPanelKey, cached.uid == uid { return cached.key }
            if let stored = defaults.string(forKey: Self.panelKeyDefaultsKey(uid)), !stored.isEmpty {
                cachedPanelKey = (uid, stored)
                return stored
            }
        }
        let snapshot = try? await db.collection("users").document(uid).getDocument()
        guard let snapshot, snapshot.exists else {
            return cachedPanelKey.flatMap { $0.uid == uid ? $0.key : nil }
        }
        let key = Self.derivePanelKey(snapshot.data() ?? [:])
        cachedPanelKey = (uid, key)
        defaults.set(key ?? "", forKey: Self.panelKeyDefaultsKey(uid))
        return key
    }

    func invalidatePanelKey() {
        if let uid = cachedPanelKey?.uid {
            defaults.removeObject(forKey: Self.panelKeyDefaultsKey(uid))
        }
        cachedPanelKey = nil
    }

    private static func panelKeyDefaultsKey(_ uid: String) -> String { "crisisSentinel.panelKey.\(uid)" }

    /// Resolution order mirrors the web `activeAgencyKey`: defaultDashboardPanelId → defaultPanelId
    /// → agencySlug → agencyKey (each via normalizeAgencyDocumentId) → slugified agency name.
    static func derivePanelKey(_ data: [String: Any]) -> String? {
        for field in ["defaultDashboardPanelId", "defaultPanelId", "agencySlug", "agencyKey"] {
            if let normalized = normalizeAgencyDocumentId(data[field] as? String) {
                return normalized
            }
        }
        let agencyName = agencyNameFields
            .lazy
            .compactMap { (data[$0] as? String)?.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty }
        guard let agencyName else { return nil }
        return toAgencyKey(agencyName)
    }

    /// Web `normalizeAgencyDocumentId`: trim + "/"→"-" only — NO lowercasing/slugify.
    static func normalizeAgencyDocumentId(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespaces), !trimmed.isEmpty else { return nil }
        return trimmed.replacingOccurrences(of: "/", with: "-")
    }

    /// Web `toAgencyKey`: NFKD → strip combining marks U+0300..U+036F → lowercase → slug.
    static func toAgencyKey(_ name: String) -> String? {
        let decomposed = name.decomposedStringWithCompatibilityMapping
        let withoutMarks = decomposed.unicodeScalars.filter { !(0x0300...0x036F).contains($0.value) }
        let lowered = String(String.UnicodeScalarView(withoutMarks)).lowercased()
        var slug = ""
        var lastWasDash = false
        for char in lowered {
            if char.isASCII && (char.isLetter || char.isNumber) {
                slug.append(char)
                lastWasDash = false
            } else if !lastWasDash {
                slug.append("-")
                lastWasDash = true
            }
        }
        while slug.hasPrefix("-") { slug.removeFirst() }
        while slug.hasSuffix("-") { slug.removeLast() }
        // Android returns nil (disable sync) rather than web's "default-agency" guess.
        return slug.isEmpty ? nil : slug
    }

    // MARK: - Seq (microsecond-scaled epoch; user strictly before assistant)

    func reserveTurnSeqs() -> CrisisSentinelTurnSeqs {
        CrisisSentinelTurnSeqs(userSeq: nextSeq(), assistantSeq: nextSeq())
    }

    private func nextSeq() -> Int64 {
        let candidate = Int64(Date().timeIntervalSince1970 * 1000) * 1000
        let next = candidate > lastSeq ? candidate : lastSeq + 1
        lastSeq = next
        return next
    }

    // MARK: - Writes

    private func chatsCollection() async throws -> CollectionReference {
        guard let panelKey = await resolvePanelKey() else {
            throw CrisisSentinelOnlineError(kind: .notConfigured, message: "No panel key")
        }
        return db.collection("agencyPanels").document(panelKey).collection("chats")
    }

    /// Creates the chat doc and AWAITS it — the messages subcollection rules `get()` the parent's
    /// userId, so the parent must exist before any message write. Returns the doc id.
    func createChat(firstUserText: String) async throws -> String {
        guard let uid = currentUid() else {
            throw CrisisSentinelOnlineError(kind: .unauthorized, message: "Not signed in")
        }
        let chatRef = try await chatsCollection().document()
        let title = String(firstUserText.prefix(30)).trimmingCharacters(in: .whitespaces)
        try await chatRef.setData([
            "userId": uid,
            "title": title.isEmpty ? "Yeni Sohbet" : title,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
            "isTemporary": false,
        ])
        return chatRef.documentID
    }

    func renameChat(docId: String, title: String) async throws {
        let clean = String(title.trimmingCharacters(in: .whitespaces).prefix(60))
        guard !clean.isEmpty else { return }
        try await chatsCollection().document(docId).updateData([
            "title": clean, "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    func deleteChat(docId: String) async throws {
        let chatRef = try await chatsCollection().document(docId)
        while true {
            let page = try await chatRef.collection("messages").limit(to: 100).getDocuments()
            if page.isEmpty { break }
            let batch = db.batch()
            page.documents.forEach { batch.deleteDocument($0.reference) }
            try await batch.commit()
            if page.count < 100 { break }
        }
        try await chatRef.delete()
    }

    func appendUserMessage(docId: String, text: String, seq: Int64) async {
        guard let chatRef = try? await chatsCollection().document(docId) else { return }
        let batch = db.batch()
        batch.setData([
            "sender": "user",
            "text": text,
            "timestamp": FieldValue.serverTimestamp(),
            "seq": seq,
            "attachments": [Any](),
        ], forDocument: chatRef.collection("messages").document(UUID().uuidString))
        batch.updateData(["updatedAt": FieldValue.serverTimestamp()], forDocument: chatRef)
        await commitDeferred(batch)
    }

    /// Deletes specific message docs from a cloud chat — used when regenerating the last response so
    /// the superseded assistant turn(s) don't linger in Firestore and reappear on the web dashboard
    /// or another device. Mirrors Android's CrisisSentinelCloudChatStore.deleteMessages.
    func deleteMessages(docId: String, messageIds: [String]) async {
        let ids = messageIds.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard !ids.isEmpty, let chatRef = try? await chatsCollection().document(docId) else { return }
        let batch = db.batch()
        for id in ids {
            batch.deleteDocument(chatRef.collection("messages").document(id))
        }
        await commitDeferred(batch)
    }

    func appendBotMessage(
        docId: String,
        messageId: String,
        text: String,
        modelName: String?,
        cardJson: String?,
        mapPoints: [CrisisSentinelMapPoint],
        groundingMetadataJson: String?,
        seq: Int64
    ) async {
        guard let chatRef = try? await chatsCollection().document(docId) else { return }
        // Never write nulls — web docs have ABSENT fields (their stripUndefined), and web read
        // paths rely on that.
        var fields: [String: Any] = [
            "sender": "bot",
            "text": text,
            "timestamp": FieldValue.serverTimestamp(),
            "seq": seq,
            "isMarkdown": true,
        ]
        if let modelName { fields["modelName"] = modelName }
        if let cardJson, let card = Self.jsonObject(cardJson) { fields["card"] = card }
        if !mapPoints.isEmpty {
            fields["mapInfo"] = mapPoints.map { point -> [String: Any] in
                var m: [String: Any] = ["lat": point.lat, "lng": point.lng, "label": point.label]
                if let details = point.details { m["details"] = details }
                if let type = point.type { m["type"] = type }
                return m
            }
        }
        if let groundingMetadataJson, let grounding = Self.jsonObject(groundingMetadataJson) {
            fields["groundingMetadata"] = grounding
        }
        let batch = db.batch()
        batch.setData(fields, forDocument: chatRef.collection("messages").document(messageId))
        batch.updateData(["updatedAt": FieldValue.serverTimestamp()], forDocument: chatRef)
        await commitDeferred(batch)
    }

    /// Commits with a cap. Write tasks resolve only on SERVER ack, so an unbounded await hangs
    /// forever offline even though the write already landed in the local cache and syncs later.
    private func commitDeferred(_ batch: WriteBatch) async {
        let task = Task { try await batch.commit() }
        _ = try? await withTimeout(Self.writeAckTimeout) { try await task.value }
    }

    // MARK: - Listeners (AsyncStream, one snapshot listener each)

    /// The signed-in user's cloud chats. No orderBy in the query (web parity — avoids a composite
    /// index); sorted by the consumer. Yields nil for the "no panel / not signed in" case.
    func summariesStream() -> AsyncStream<[CrisisSentinelCloudSummary]> {
        AsyncStream { continuation in
            let task = Task {
                guard let uid = currentUid(), let panelKey = await resolvePanelKey() else {
                    continuation.yield([])
                    continuation.finish()
                    return
                }
                let registration = db.collection("agencyPanels").document(panelKey)
                    .collection("chats")
                    .whereField("userId", isEqualTo: uid)
                    .addSnapshotListener { snapshot, _ in
                        let summaries = (snapshot?.documents ?? []).map { doc -> CrisisSentinelCloudSummary in
                            CrisisSentinelCloudSummary(
                                docId: doc.documentID,
                                title: (doc.get("title") as? String)?.nilIfBlank ?? "Yeni Sohbet",
                                updatedAt: (doc.get("updatedAt") as? Timestamp)?.dateValue() ?? Date()
                            )
                        }.sorted { $0.updatedAt > $1.updatedAt }
                        continuation.yield(summaries)
                    }
                continuation.onTermination = { _ in registration.remove() }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Live messages of one cloud chat, ordered. Emits nil `chatExists=false` when the chat was
    /// deleted server-side.
    func messagesStream(docId: String) -> AsyncStream<(messages: [CrisisSentinelChatMessage], chatExists: Bool)> {
        AsyncStream { continuation in
            let task = Task {
                guard let panelKey = await resolvePanelKey() else {
                    continuation.yield(([], false))
                    continuation.finish()
                    return
                }
                let chatRef = db.collection("agencyPanels").document(panelKey)
                    .collection("chats").document(docId)
                var chatExists = true
                let chatReg = chatRef.addSnapshotListener { snapshot, _ in
                    if let snapshot, !snapshot.exists, !snapshot.metadata.isFromCache {
                        chatExists = false
                        continuation.yield(([], false))
                    }
                }
                let msgReg = chatRef.collection("messages")
                    .order(by: "timestamp")
                    .addSnapshotListener { snapshot, _ in
                        let mapped = (snapshot?.documents ?? [])
                            .compactMap { Self.messageFromDoc($0) }
                            .sorted { $0.orderKey < $1.orderKey }
                            .map(\.message)
                        continuation.yield((mapped, chatExists))
                    }
                continuation.onTermination = { _ in chatReg.remove(); msgReg.remove() }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Mapping

    private static func messageFromDoc(
        _ doc: QueryDocumentSnapshot
    ) -> (orderKey: Int64, message: CrisisSentinelChatMessage)? {
        guard let sender = doc.get("sender") as? String else { return nil }
        let isUser = sender == "user"
        let seq = (doc.get("seq") as? NSNumber)?.int64Value
        let tsMillis = (doc.get("timestamp") as? Timestamp).map { Int64($0.dateValue().timeIntervalSince1970 * 1000) }
            ?? seq.map { $0 / 1000 }
            ?? Int64(Date().timeIntervalSince1970 * 1000)
        let orderKey = seq ?? (tsMillis * 1000)
        let modelName = doc.get("modelName") as? String
        var cardJson: String?
        if let card = doc.get("card") as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: card) {
            cardJson = String(data: data, encoding: .utf8)
        }
        let mapPoints: [CrisisSentinelMapPoint] = (doc.get("mapInfo") as? [[String: Any]] ?? []).compactMap { point in
            guard let lat = (point["lat"] as? NSNumber)?.doubleValue,
                  let lng = (point["lng"] as? NSNumber)?.doubleValue else { return nil }
            return CrisisSentinelMapPoint(
                lat: lat, lng: lng,
                label: point["label"] as? String ?? "",
                details: point["details"] as? String,
                type: point["type"] as? String
            )
        }
        let source: CrisisSentinelResponseSource? = isUser
            ? nil
            : (modelName == edgeModelName ? .localModel : .onlineModel)
        let message = CrisisSentinelChatMessage(
            id: doc.documentID,
            role: isUser ? .user : .assistant,
            text: doc.get("text") as? String ?? "",
            timestamp: Date(timeIntervalSince1970: Double(tsMillis) / 1000),
            source: source,
            confidence: nil,
            modelName: modelName,
            cardJson: cardJson,
            mapPoints: mapPoints
        )
        return (orderKey, message)
    }

    private static func jsonObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Runs `operation` but gives up after `seconds` (returns nil), without cancelling the underlying
/// Firestore write — it stays queued and syncs when possible.
private func withTimeout<T>(_ seconds: TimeInterval, _ operation: @escaping () async throws -> T) async throws -> T? {
    try await withThrowingTaskGroup(of: T?.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return nil
        }
        let result = try await group.next() ?? nil
        group.cancelAll()
        return result
    }
}
