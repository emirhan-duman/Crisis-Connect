import Foundation
import CryptoKit
import Security

/// Request signing key belongs only to the watch; it is not a phone login or Signal key.
struct IndependentWatchIdentity {
    static let domain = "crisis-connect/watch-request/v1\n"
    let deviceID: String
    private var service: String { "crisis.watch.request.v1.\(deviceID)" }

    init(deviceID: String) throws {
        guard UUID(uuidString: deviceID)?.uuidString.lowercased() == deviceID else { throw IndependentWatchError.invalid }
        self.deviceID = deviceID
    }
    func publicKey() throws -> String { try key(create: true).publicKey.derRepresentation.base64EncodedString() }
    func sign(_ encodedPayload: String) throws -> String {
        // Losing an enrolled key must fail, rather than silently becoming a new identity.
        try key(create: false).signature(for: Data((Self.domain + encodedPayload).utf8)).derRepresentation.base64EncodedString()
    }
    func enrollment(ownerUid: String) throws -> [String: String] {
        guard ownerUid.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil else { throw IndependentWatchError.invalid }
        let pub = try publicKey()
        let json = "{\"version\":1,\"ownerUid\":\"\(ownerUid)\",\"deviceID\":\"\(deviceID)\",\"publicKey\":\"\(pub)\",\"scopes\":[\"shortMessage\",\"sos\"]}"
        return ["deviceID": deviceID, "publicKey": pub, "signature": try sign(Data(json.utf8).base64EncodedString())]
    }
    private func key(create: Bool) throws -> P256.Signing.PrivateKey {
        if let data = try IndependentWatchKeychain.read(service) { return try P256.Signing.PrivateKey(rawRepresentation: data) }
        guard create else { throw IndependentWatchError.missingKey }
        let key = P256.Signing.PrivateKey()
        try IndependentWatchKeychain.insert(key.rawRepresentation, service: service)
        return key
    }
}

enum IndependentWatchError: Error { case invalid, missingKey, uncertain, storage(OSStatus) }
enum IndependentWatchKeychain {
    static func read(_ service: String) throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
            kSecAttrAccount: "watch", kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let bytes = result as? Data else { throw IndependentWatchError.storage(status) }
        return bytes
    }
    static func insert(_ data: Data, service: String) throws {
        let status = SecItemAdd([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
            kSecAttrAccount: "watch", kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly] as CFDictionary, nil)
        guard status == errSecSuccess else { throw IndependentWatchError.storage(status) }
    }
}
