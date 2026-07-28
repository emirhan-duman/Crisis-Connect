//
//  RustMlsWorkerBackend.swift
//  Crisis Connect
//
//  MlsWorkerBackend over the shared Rust MLS core (OrangeMlsWorker.xcframework, built from
//  crisis-connect-web/rust-mls-ios — the SAME OpenMLS 0.7.1 `mls_ops.rs` the web wasm worker and
//  Android's JNI build use, so group state is byte-identical across the fleet). Handshake ops
//  return the wire-format JSON MlsSession relays to Firestore untouched. State inside the core
//  is a process-global mutex on iOS, so calls may arrive from any queue.
//

import Foundation
import OrangeMlsWorker

struct RustMlsWorkerBackend: MlsWorkerBackend {

    /// Wires the Rust core into MlsWorker. Call once at startup; safe to call repeatedly.
    static func activate() {
        if MlsWorker.backend == nil {
            MlsWorker.backend = RustMlsWorkerBackend()
        }
    }

    enum BackendError: Error {
        case nullResult
    }

    func newState(uid: String) throws -> String {
        try takeString(uid.withCString { mls_ios_new_state($0) })
    }

    func newStateAndCreateGroup(uid: String) throws -> String {
        try takeString(uid.withCString { mls_ios_new_state_and_create_group($0) })
    }

    func addUser(keyPkg: Data) throws -> String {
        try takeString(keyPkg.withUnsafeBytes { buffer in
            mls_ios_add_user(buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
        })
    }

    func removeUser(uid: String) throws -> String {
        try takeString(uid.withCString { mls_ios_remove_user($0) })
    }

    func joinGroup(welcome: Data, rtree: Data) throws -> String {
        try takeString(welcome.withUnsafeBytes { welcomeBuffer in
            rtree.withUnsafeBytes { rtreeBuffer in
                mls_ios_join_group(
                    welcomeBuffer.bindMemory(to: UInt8.self).baseAddress, welcomeBuffer.count,
                    rtreeBuffer.bindMemory(to: UInt8.self).baseAddress, rtreeBuffer.count
                )
            }
        })
    }

    func handleCommit(msg: Data, senderId: String) throws -> String {
        try takeString(msg.withUnsafeBytes { buffer in
            senderId.withCString { sender in
                mls_ios_handle_commit(
                    buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count, sender
                )
            }
        })
    }

    func encryptFrame(_ frame: Data) throws -> Data {
        try takeBytes { outLen in
            frame.withUnsafeBytes { buffer in
                mls_ios_encrypt_frame(buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count, outLen)
            }
        }
    }

    func decryptFrame(_ frame: Data) throws -> Data {
        try takeBytes { outLen in
            frame.withUnsafeBytes { buffer in
                mls_ios_decrypt_frame(buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count, outLen)
            }
        }
    }

    // MARK: - Ownership helpers (Rust allocates; we copy then free through the matching FFI)

    private func takeString(_ pointer: UnsafeMutablePointer<CChar>?) throws -> String {
        guard let pointer else { throw BackendError.nullResult }
        defer { mls_ios_free_string(pointer) }
        return String(cString: pointer)
    }

    private func takeBytes(_ produce: (UnsafeMutablePointer<Int>) -> UnsafeMutablePointer<UInt8>?) throws -> Data {
        var length = 0
        guard let pointer = produce(&length) else { throw BackendError.nullResult }
        defer { mls_ios_free_bytes(pointer, length) }
        return Data(bytes: pointer, count: length)
    }
}
