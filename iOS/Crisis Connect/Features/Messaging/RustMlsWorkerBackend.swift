//
//  RustMlsWorkerBackend.swift
//  Crisis Connect
//
//  MlsWorkerBackend over the shared Rust MLS core (OrangeMlsWorker.xcframework, built from
//  crisis-connect-web/rust-mls-ios — the SAME OpenMLS 0.8.1 `mls_ops.rs` the web wasm worker and
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

    func exportState() throws -> Data {
        try takeBytes { outLen in mls_ios_export_state(outLen) }
    }

    func importState(_ snapshot: Data) throws {
        let imported = snapshot.withUnsafeBytes { buffer in
            mls_ios_import_state(buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
        }
        guard imported else { throw BackendError.nullResult }
    }

    func encryptApplication(_ plaintext: Data) throws -> Data {
        try takeBytes { outLen in
            plaintext.withUnsafeBytes { buffer in
                mls_ios_encrypt_application(
                    buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count, outLen
                )
            }
        }
    }

    func decryptApplication(_ ciphertext: Data) throws -> Data {
        try takeBytes { outLen in
            ciphertext.withUnsafeBytes { buffer in
                mls_ios_decrypt_application(
                    buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count, outLen
                )
            }
        }
    }

    func persistentNewState(context: String, credential: String) throws -> String {
        try takeString(context.withCString { contextPointer in
            credential.withCString { credentialPointer in
                mls_ios_persistent_new_state(contextPointer, credentialPointer)
            }
        })
    }

    func persistentNewStateAndCreateGroup(context: String, credential: String) throws -> String {
        try takeString(context.withCString { contextPointer in
            credential.withCString { credentialPointer in
                mls_ios_persistent_new_state_and_create_group(contextPointer, credentialPointer)
            }
        })
    }

    func persistentAddUser(
        context: String,
        keyPkg: Data,
        expectedCredential: String,
        expectedSigningKey: Data
    ) throws -> String {
        try takeString(context.withCString { contextPointer in
            keyPkg.withUnsafeBytes { buffer in
                expectedCredential.withCString { credentialPointer in
                    expectedSigningKey.withUnsafeBytes { signingBuffer in
                        mls_ios_persistent_add_user(
                            contextPointer,
                            buffer.bindMemory(to: UInt8.self).baseAddress,
                            buffer.count,
                            credentialPointer,
                            signingBuffer.bindMemory(to: UInt8.self).baseAddress,
                            signingBuffer.count
                        )
                    }
                }
            }
        })
    }

    func persistentIdentity(context: String) throws -> String {
        try takeString(context.withCString { mls_ios_persistent_identity($0) })
    }

    func persistentRoster(context: String) throws -> String {
        try takeString(context.withCString { mls_ios_persistent_roster($0) })
    }

    func persistentSafetyNumber(context: String) throws -> Data {
        try takeBytes { outLen in
            context.withCString { mls_ios_persistent_safety_number($0, outLen) }
        }
    }

    func persistentRemoveUser(context: String, credential: String) throws -> String {
        try takeString(context.withCString { contextPointer in
            credential.withCString { credentialPointer in
                mls_ios_persistent_remove_user(contextPointer, credentialPointer)
            }
        })
    }

    func persistentJoinGroup(context: String, welcome: Data, rtree: Data) throws -> String {
        try takeString(context.withCString { contextPointer in
            welcome.withUnsafeBytes { welcomeBuffer in
                rtree.withUnsafeBytes { treeBuffer in
                    mls_ios_persistent_join_group(
                        contextPointer,
                        welcomeBuffer.bindMemory(to: UInt8.self).baseAddress,
                        welcomeBuffer.count,
                        treeBuffer.bindMemory(to: UInt8.self).baseAddress,
                        treeBuffer.count
                    )
                }
            }
        })
    }

    func persistentHandleCommit(context: String, msg: Data, senderCredential: String) throws -> String {
        try takeString(context.withCString { contextPointer in
            msg.withUnsafeBytes { buffer in
                senderCredential.withCString { senderPointer in
                    mls_ios_persistent_handle_commit(
                        contextPointer,
                        buffer.bindMemory(to: UInt8.self).baseAddress,
                        buffer.count,
                        senderPointer
                    )
                }
            }
        })
    }

    func persistentExportState(context: String) throws -> Data {
        try takeBytes { outLen in
            context.withCString { mls_ios_persistent_export_state($0, outLen) }
        }
    }

    func persistentImportState(context: String, snapshot: Data) throws {
        let imported = context.withCString { contextPointer in
            snapshot.withUnsafeBytes { buffer in
                mls_ios_persistent_import_state(
                    contextPointer,
                    buffer.bindMemory(to: UInt8.self).baseAddress,
                    buffer.count
                )
            }
        }
        guard imported else { throw BackendError.nullResult }
    }

    func persistentEncryptApplication(context: String, plaintext: Data) throws -> Data {
        try takeBytes { outLen in
            context.withCString { contextPointer in
                plaintext.withUnsafeBytes { buffer in
                    mls_ios_persistent_encrypt_application(
                        contextPointer,
                        buffer.bindMemory(to: UInt8.self).baseAddress,
                        buffer.count,
                        outLen
                    )
                }
            }
        }
    }

    func persistentDecryptApplication(context: String, ciphertext: Data) throws -> Data {
        try takeBytes { outLen in
            context.withCString { contextPointer in
                ciphertext.withUnsafeBytes { buffer in
                    mls_ios_persistent_decrypt_application(
                        contextPointer,
                        buffer.bindMemory(to: UInt8.self).baseAddress,
                        buffer.count,
                        outLen
                    )
                }
            }
        }
    }

    func persistentClose(context: String) throws {
        let closed = context.withCString { mls_ios_persistent_close($0) }
        guard closed else { throw BackendError.nullResult }
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
