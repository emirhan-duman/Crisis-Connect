#if CC_WATCH_INDEPENDENT && os(watchOS)
import Foundation

@MainActor enum IndependentWatchSetup {
    private static func file() throws -> IndependentWatchJournalFile {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return IndependentWatchJournalFile(url: directory.appendingPathComponent("independent-setup.enc"), service: "crisis.watch.setup")
    }
    private static func load() throws -> [String: String] {
        try file().readData().map { try JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
    }
    private static func client(_ id: String) throws -> IndependentWatchService {
        try IndependentWatchService(deviceID: id, endpoint: URL(string: "https://europe-west1-crisis-connect-1.cloudfunctions.net/independentWatchGateway")!)
    }
    static func service() throws -> IndependentWatchService {
        let state = try load()
        guard state["enabled"] == "true", let id = state["deviceID"] else { throw IndependentWatchError.invalid }
        return try client(id)
    }
    static func request(_ input: [String: Any]) throws -> [String: Any] {
        guard let owner = input["ownerUid"] as? String, !owner.isEmpty, owner.count <= 128 else { throw IndependentWatchError.invalid }
        var state = try load()
        guard state["ownerUid"] == nil || state["ownerUid"] == owner else { throw IndependentWatchError.invalid }
        let id = state["deviceID"] ?? UUID().uuidString.lowercased(), service = try client(id)
        switch input["stage"] as? String {
        case "enroll":
            state["ownerUid"] = owner; state["deviceID"] = id
            try file().writeData(JSONEncoder().encode(state))
            return try service.enrollment(ownerUid: owner)
        case "bind":
            guard state["deviceID"] == input["deviceID"] as? String, let index = (input["deviceIndex"] as? NSNumber)?.uint32Value else { throw IndependentWatchError.invalid }
            return try service.signalApproval(ownerUid: owner, deviceIndex: index)
        case "complete":
            guard state["deviceID"] == input["deviceID"] as? String else { throw IndependentWatchError.invalid }
            state["enabled"] = "true"; try file().writeData(JSONEncoder().encode(state)); return ["ready": true]
        case "history":
            guard state["enabled"] == "true" else { throw IndependentWatchError.invalid }
            let offset = input["offset"] as? Int ?? 0
            guard (0...512).contains(offset) else { throw IndependentWatchError.invalid }
            let messages = try service.signal.outgoing().values.sorted { $0.id < $1.id }.dropFirst(offset).prefix(16).map { message -> [String: Any] in
                ["id": message.id, "recipientUid": message.uid, "clear": message.clear.base64EncodedString(), "state": message.state]
            }
            return ["ownerUid": owner, "deviceID": id, "messages": messages]
        default: throw IndependentWatchError.invalid
        }
    }
}
#endif
