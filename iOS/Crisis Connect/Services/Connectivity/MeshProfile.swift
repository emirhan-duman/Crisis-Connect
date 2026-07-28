//
//  MeshProfile.swift
//  Crisis Connect
//
//  iOS counterpart of the Android `MeshProfile`. Describes everything that differs between the
//  public mesh and the role-gated authority mesh running side by side on the same radio.
//
//  NOTE: This is the configuration blueprint. Wiring it through `GattMeshProtocol`
//  (encrypt/decrypt + UUIDs) and running a second `GattMeshManager` instance registered as a
//  second `BlePeripheralHostProfile` is the remaining iOS runtime work (best done with Xcode in
//  the loop for compile + on-device verification). The Android side already runs this end to end.
//

import Foundation
import CoreBluetooth

struct MeshProfile {
    let id: String
    let serviceUUID: CBUUID
    let messageInCharacteristicUUID: CBUUID
    let messageOutCharacteristicUUID: CBUUID
    /// AES-GCM payload key id carried in the packet envelope (decryption rejects mismatches).
    let payloadKeyId: String

    /// Public, open mesh — identical UUIDs/keyId to the original `GattMeshProtocol`.
    static let publicMesh = MeshProfile(
        id: "public",
        serviceUUID: GattMeshProtocol.serviceUUID,
        messageInCharacteristicUUID: GattMeshProtocol.messageInCharacteristicUUID,
        messageOutCharacteristicUUID: GattMeshProtocol.messageOutCharacteristicUUID,
        payloadKeyId: "public-v1"
    )

    /// Authority-only mesh — separate UUID (Crisis Connect 0xCCxx range) + provisioned group key.
    static let authority = MeshProfile(
        id: "authority",
        serviceUUID: CBUUID(string: "0000CCA1-0000-1000-8000-00805F9B34FB"),
        messageInCharacteristicUUID: CBUUID(string: "0000CCA2-0000-1000-8000-00805F9B34FB"),
        messageOutCharacteristicUUID: CBUUID(string: "0000CCA3-0000-1000-8000-00805F9B34FB"),
        payloadKeyId: "authority-v1"
    )
}
