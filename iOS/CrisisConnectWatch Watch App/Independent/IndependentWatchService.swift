#if CC_WATCH_INDEPENDENT || os(iOS)
import Foundation
import CryptoKit

@MainActor final class IndependentWatchService {
    let deviceID: String
    let signal: IndependentSignalVault
    private let endpoint: URL
    private let identity: IndependentWatchIdentity
    private let journal: IndependentWatchJournal
    private var inFlight = false
    private let session: URLSession

    init(deviceID: String, endpoint: URL) throws {
        var allowed = endpoint.scheme == "https" && endpoint.host == "europe-west1-crisis-connect-1.cloudfunctions.net" && endpoint.path == "/independentWatchGateway"
        #if DEBUG && targetEnvironment(simulator)
        allowed = allowed || (endpoint.scheme == "http" && endpoint.host == "127.0.0.1")
        #endif
        guard allowed, endpoint.user == nil, endpoint.password == nil, endpoint.fragment == nil, endpoint.query == nil else { throw IndependentWatchError.invalid }
        self.deviceID = deviceID; self.endpoint = endpoint
        signal = try IndependentSignalVault(namespace: deviceID)
        identity = try IndependentWatchIdentity(deviceID: deviceID)
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        journal = try IndependentWatchJournal(storage: IndependentWatchJournalFile(url: directory.appendingPathComponent("independent-\(deviceID).enc"), service: "crisis.watch.journal.v1.\(deviceID)"))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8; configuration.timeoutIntervalForResource = 12
        configuration.urlCache = nil
        session = URLSession(configuration: configuration, delegate: IndependentNoRedirect(), delegateQueue: nil)
    }
    func enrollment(ownerUid: String) throws -> [String: String] { try identity.enrollment(ownerUid: ownerUid) }
    func signalApproval(ownerUid: String, deviceIndex: UInt32) throws -> [String: Any] {
        let proof: [String: Any] = ["version": 1, "kind": "signal-binding", "ownerUid": ownerUid, "deviceID": deviceID,
            "expiresAt": Int64(Date().timeIntervalSince1970 * 1000) + 300_000, "bundle": try signal.publicBundle(device: deviceIndex)]
        let encoded = try JSONSerialization.data(withJSONObject: proof, options: [.sortedKeys]).base64EncodedString()
        return ["ownerUid": ownerUid, "payload": encoded, "signature": try identity.sign(encoded)]
    }
    var history: [IndependentWatchOperation] { journal.entries }
    func startSOS(location: [String: Any], sessionID: String = UUID().uuidString.lowercased()) async throws -> [String: Any] {
        var body = location; body["sessionID"] = sessionID
        return try await dispatch(action: "startSOS", subject: sessionID, body: body)
    }
    func stopSOS(sessionID: String) async throws -> [String: Any] {
        try await dispatch(action: "stopSOS", subject: sessionID, body: ["sessionID": sessionID])
    }
    func refresh(inboxAfter: String? = nil) async throws -> [String: Any] {
        guard !inFlight else { throw IndependentWatchError.uncertain }
        inFlight = true; defer { inFlight = false }
        try journal.reload()
        let ids = journal.entries.map(\.id)
        let offsets = Array(stride(from: 0, to: max(1, ids.count), by: 32))
        var response: [String: Any] = [:], allReceipts: [[String: Any]] = []
        for offset in offsets {
            let chunk = Array(ids.dropFirst(offset).prefix(32))
            var body: [String: Any] = ["commandIDs": chunk]
            if let inboxAfter { body["inboxAfter"] = inboxAfter }
            let encoded = try payload(action: "status", id: UUID().uuidString.lowercased(), body: body)
            response = try await exchange(encoded)
            guard response["deviceID"] as? String == deviceID, let receipts = response["receipts"] as? [[String: Any]] else { throw IndependentWatchError.invalid }
            for receipt in receipts {
                guard let id = receipt["id"] as? String, let operation = journal.entries.first(where: { $0.id == id }) else { continue }
                guard receipt["digest"] as? String == Self.digest(operation.payload), let outcome = receipt["outcome"] as? String else { throw IndependentWatchError.invalid }
                try journal.reconcile(id: id, state: outcome)
                if operation.action == "shortMessage", ["accepted", "delivered", "read"].contains(outcome) { try signal.reconcile(id: id, state: outcome) }
            }
            allReceipts.append(contentsOf: receipts)
        }
        response["receipts"] = allReceipts
        return response
    }
    func sendMessage(recipientInstallation: String, uid: String, device: UInt32, bundle: [String: Any]?, clear: Data) async throws -> [String: Any] {
        guard !inFlight else { throw IndependentWatchError.uncertain }
        inFlight = true; defer { inFlight = false }
        let op = try journal.reservePrepared(action: "shortMessage", subjectID: uid) { id in
            let wire = try signal.prepare(id: id, uid: uid, device: device, bundle: bundle, clear: clear)
            return try payload(action: "shortMessage", id: id, body: ["messageID": id, "recipientInstallation": recipientInstallation, "recipientUid": uid, "recipientDevice": device, "ctype": wire.ctype, "ciphertext": wire.ciphertext])
        }
        let result = try await exchange(op.payload)
        guard result["id"] as? String == op.id, result["digest"] as? String == Self.digest(op.payload), let outcome = result["outcome"] as? String else { throw IndependentWatchError.invalid }
        try journal.reconcile(id: op.id, state: outcome); try signal.reconcile(id: op.id, state: outcome)
        return result
    }
    func receiveMessage(_ message: [String: Any], persist: (Data) async throws -> Void = { _ in }) async throws -> Data {
        guard !inFlight, let id = message["id"] as? String, let uid = message["senderUid"] as? String,
              let device = (message["senderDevice"] as? NSNumber)?.uint32Value, let ctype = message["ctype"] as? String,
              let ciphertext = message["ciphertext"] as? String else { throw IndependentWatchError.invalid }
        inFlight = true; defer { inFlight = false }
        let clear = try signal.receive(id: id, uid: uid, device: device, ctype: ctype, ciphertext: ciphertext)
        try await persist(clear) // Commit the ordinary chat before acknowledging delivery.
        // A lost delivery receipt cannot hide plaintext already committed to history.
        _ = try? await exchange(payload(action: "ackMessage", id: UUID().uuidString.lowercased(), body: ["messageID": id]))
        return clear
    }
    private func dispatch(action: String, subject: String, body: [String: Any]) async throws -> [String: Any] {
        guard !inFlight else { throw IndependentWatchError.uncertain }
        inFlight = true; defer { inFlight = false }
        let op = try journal.reservePrepared(action: action, subjectID: subject) { id in try payload(action: action, id: id, body: body) }
        let result = try await exchange(op.payload)
        guard result["id"] as? String == op.id, result["digest"] as? String == Self.digest(op.payload), let outcome = result["outcome"] as? String else { throw IndependentWatchError.invalid }
        try journal.reconcile(id: op.id, state: outcome)
        return result
    }
    private func payload(action: String, id: String, body: [String: Any]) throws -> String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return try JSONSerialization.data(withJSONObject: ["version": 1, "deviceID": deviceID, "id": id, "issuedAt": now, "expiresAt": now + 30_000, "action": action, "body": body], options: [.sortedKeys]).base64EncodedString()
    }
    private func exchange(_ payload: String) async throws -> [String: Any] {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["payload": payload, "signature": identity.sign(payload)])
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65_536,
            let wrapper = try JSONSerialization.jsonObject(with: data) as? [String: Any], let result = wrapper["result"] as? [String: Any] else { throw IndependentWatchError.uncertain }
        return result
    }
    static func digest(_ payload: String) -> String { SHA256.hash(data: Data(payload.utf8)).map { String(format: "%02x", $0) }.joined() }
}

private final class IndependentNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

#endif
