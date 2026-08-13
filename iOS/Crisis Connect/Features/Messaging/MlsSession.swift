//
//  MlsSession.swift
//  Crisis Connect
//
//  Drives the MLS group for ONE SFU call — the iOS port of Android's MlsWorker/MlsSession. The
//  room's first member (creator, claimed race-free on the room doc) creates the group; everyone
//  else shares a KeyPackage, gets a Welcome, and joins. Handshake bytes ride Firestore via
//  MlsHandshakeCodec, interoperating with the web dashboard and Android.
//
//  The native core is abstracted behind MlsWorkerBackend: the same OpenMLS 0.8.1 Rust crate the
//  web (wasm) and Android (JNI) use, built for iOS as an xcframework — see
//  docs/SFU_GROUP_CALLS.md. Until that lands, `backend == nil` keeps the session inert (no
//  crash, no E2EE), exactly like Android's `MlsWorker.available == false` path.
//

import FirebaseFirestore
import Foundation

/// FFI surface of the shared Rust MLS core (mirrors rust-mls-android's android_ffi.rs). Every
/// handshake op returns a JSON string: { broadcast: [wireMsg…], safetyNumber?: [int…] }.
protocol MlsWorkerBackend: Sendable {
    func newState(uid: String) throws -> String
    func newStateAndCreateGroup(uid: String) throws -> String
    func addUser(keyPkg: Data) throws -> String
    func removeUser(uid: String) throws -> String
    func joinGroup(welcome: Data, rtree: Data) throws -> String
    func handleCommit(msg: Data, senderId: String) throws -> String
    func encryptFrame(_ frame: Data) throws -> Data
    func decryptFrame(_ frame: Data) throws -> Data
    func exportState() throws -> Data
    func importState(_ snapshot: Data) throws
    func encryptApplication(_ plaintext: Data) throws -> Data
    func decryptApplication(_ ciphertext: Data) throws -> Data

    // Durable AuthorityChat contexts. These never share the process-global live-call state.
    func persistentNewState(context: String, credential: String) throws -> String
    func persistentNewStateAndCreateGroup(context: String, credential: String) throws -> String
    func persistentAddUser(
        context: String,
        keyPkg: Data,
        expectedCredential: String,
        expectedSigningKey: Data
    ) throws -> String
    func persistentIdentity(context: String) throws -> String
    func persistentRoster(context: String) throws -> String
    func persistentSafetyNumber(context: String) throws -> Data
    func persistentRemoveUser(context: String, credential: String) throws -> String
    func persistentJoinGroup(context: String, welcome: Data, rtree: Data) throws -> String
    func persistentHandleCommit(context: String, msg: Data, senderCredential: String) throws -> String
    func persistentExportState(context: String) throws -> Data
    func persistentImportState(context: String, snapshot: Data) throws
    func persistentEncryptApplication(context: String, plaintext: Data) throws -> Data
    func persistentDecryptApplication(context: String, ciphertext: Data) throws -> Data
    func persistentClose(context: String) throws
}

/// Registry for the native backend; nil until the Rust xcframework ships.
enum MlsWorker {
    static var backend: MlsWorkerBackend?
    static var available: Bool { backend != nil }
}

final class MlsSession: @unchecked Sendable {
    private let myUid: String
    private let room: SfuRoomClient
    private let onSafetyNumber: (String) -> Void
    private let onFailure: (Error) -> Void

    /// Single serial queue for every native call: the Rust core's state is thread-local
    /// (same constraint as Android's pinned HandlerThread).
    private let queue = DispatchQueue(label: "mls.session", qos: .userInitiated)
    private var registration: ListenerRegistration?

    init(
        myUid: String,
        room: SfuRoomClient,
        onSafetyNumber: @escaping (String) -> Void = { _ in },
        onFailure: @escaping (Error) -> Void = { _ in }
    ) {
        self.myUid = myUid
        self.room = room
        self.onSafetyNumber = onSafetyNumber
        self.onFailure = onFailure
    }

    /// Start the group: create it (first member) or announce our KeyPackage to join, then listen.
    func start(isCreator: Bool) {
        guard let backend = MlsWorker.backend else {
            onFailure(NSError(domain: "MlsSession", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Mandatory MLS backend is unavailable."
            ]))
            return
        }
        // Attach the listener BEFORE initializing (mirrors web use-sfu-room ordering): our own
        // initialize may publish, and a peer's reply must never race past an unattached listener.
        registration = room.listenMlsMessages(
            onMessage: { [weak self] payload, fromUid in
                self?.queue.async { self?.onIncoming(payload, fromUid: fromUid, backend: backend) }
            },
            onError: { [weak self] error in self?.onFailure(error) }
        )
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let json = try (isCreator
                    ? backend.newStateAndCreateGroup(uid: self.myUid)
                    : backend.newState(uid: self.myUid))
                self.handleResponse(json)
            } catch {
                self.onFailure(error)
            }
        }
    }

    func stop() {
        registration?.remove()
        registration = nil
    }

    private func onIncoming(_ payload: String, fromUid: String, backend: MlsWorkerBackend) {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["senderId"] as? String == fromUid else {
            onFailure(SfuRoomAuthorizationError.roomBindingMismatch)
            return
        }
        guard let message = MlsHandshakeCodec.decode(payload) else {
            onFailure(NSError(domain: "MlsSession", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "Incoming MLS relay payload is not decodable."
            ]))
            return
        }
        do {
            let json: String
            switch message {
            case .shareKeyPackage(let keyPkg):
                json = try backend.addUser(keyPkg: keyPkg)
            case .sendMlsWelcome(_, let welcome, let rtree):
                json = try backend.joinGroup(welcome: welcome, rtree: rtree)
            case .sendMlsMessage(let msg, let senderId):
                json = try backend.handleCommit(msg: msg, senderId: senderId)
            }
            handleResponse(json)
        } catch {
            onFailure(error)
        }
    }

    /// Publish each broadcast-able message the worker emitted; surface a new safety number locally.
    private func handleResponse(_ json: String) {
        let parsed = Self.parseWorkerResponse(json, senderId: myUid)
        for payload in parsed.broadcast {
            room.publishMlsMessage(payload, onError: onFailure)
        }
        if let safetyNumber = parsed.safetyNumber {
            onSafetyNumber(safetyNumber)
        }
    }

    /// Pure parse of the worker's response JSON ({broadcast:[wireMsg…], safetyNumber?:[int…]}) —
    /// static so the contract stays unit-testable without Firebase or a native core.
    static func parseWorkerResponse(
        _ json: String,
        senderId: String? = nil
    ) -> (broadcast: [String], safetyNumber: String?) {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return ([], nil)
        }
        var broadcast: [String] = []
        if let rawBroadcast = object["broadcast"] as? [[String: Any]] {
            for var message in rawBroadcast {
                if let senderId { message["senderId"] = senderId }
                if let framed = try? JSONSerialization.data(withJSONObject: message) {
                    broadcast.append(String(decoding: framed, as: UTF8.self))
                }
            }
        }
        var safetyNumber: String?
        if let rawNumber = object["safetyNumber"] as? [Any] {
            let values = rawNumber.compactMap { ($0 as? NSNumber)?.intValue }
            if !values.isEmpty {
                safetyNumber = MlsHandshakeCodec.formatSafetyNumber(values)
            }
        }
        return (broadcast, safetyNumber)
    }
}
