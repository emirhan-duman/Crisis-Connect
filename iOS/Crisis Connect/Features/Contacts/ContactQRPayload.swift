//
//  ContactQRPayload.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation

struct ContactQRPayload: Codable {
    static let currentVersion = 1
    private static let schemePrefix = "dcs://"

    let v: Int
    let code: String
    let key: String
    let platform: String
    let bleFallbackCapable: Bool
    let name: String?
    let bluetoothName: String?
    let shareId: String?
    // Our internet-messaging identity, so the scanner can reach us online later (mirrors Android's
    // NewChat QR fields). Only the PUBLIC key travels; the private key never leaves the device.
    let peerUid: String?
    let peerPublicKey: String?

    init(
        v: Int = currentVersion,
        code: String,
        key: String,
        platform: String,
        bleFallbackCapable: Bool = true,
        name: String? = nil,
        bluetoothName: String? = nil,
        shareId: String? = nil,
        peerUid: String? = nil,
        peerPublicKey: String? = nil
    ) {
        self.v = v
        self.code = code
        self.key = key
        self.platform = platform
        self.bleFallbackCapable = bleFallbackCapable
        self.name = name?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.bluetoothName = bluetoothName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.shareId = shareId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.peerUid = peerUid?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.peerPublicKey = peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    private enum CodingKeys: String, CodingKey {
        case v
        case code
        case key
        case platform
        case bleFallbackCapable
        case name
        case bluetoothName
        case shareId
        case peerUid
        case peerPublicKey
        case legacyName = "n"
        case legacyBroadcastId = "b"
        case legacyKey = "k"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        let version = try container.decodeIfPresent(Int.self, forKey: .v) ?? Self.currentVersion
        let code = try container.decodeIfPresent(String.self, forKey: .code)
            ?? container.decodeIfPresent(String.self, forKey: .legacyBroadcastId)
            ?? ""
        let key = try container.decodeIfPresent(String.self, forKey: .key)
            ?? container.decodeIfPresent(String.self, forKey: .legacyKey)
            ?? ""
        let platform = try container.decodeIfPresent(String.self, forKey: .platform) ?? "ios"
        let bleFallbackCapable = try container.decodeIfPresent(Bool.self, forKey: .bleFallbackCapable) ?? true
        let name = try container.decodeIfPresent(String.self, forKey: .name)
            ?? container.decodeIfPresent(String.self, forKey: .legacyName)
        let bluetoothName = try container.decodeIfPresent(String.self, forKey: .bluetoothName)
        let shareId = try container.decodeIfPresent(String.self, forKey: .shareId)
        let peerUid = try container.decodeIfPresent(String.self, forKey: .peerUid)
        let peerPublicKey = try container.decodeIfPresent(String.self, forKey: .peerPublicKey)

        self.init(
            v: version,
            code: code,
            key: key,
            platform: platform,
            bleFallbackCapable: bleFallbackCapable,
            name: name,
            bluetoothName: bluetoothName,
            shareId: shareId,
            peerUid: peerUid,
            peerPublicKey: peerPublicKey
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(v, forKey: .v)
        try container.encode(code, forKey: .code)
        try container.encode(key, forKey: .key)
        try container.encode(platform, forKey: .platform)
        try container.encode(bleFallbackCapable, forKey: .bleFallbackCapable)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeIfPresent(bluetoothName, forKey: .bluetoothName)
        try container.encodeIfPresent(shareId, forKey: .shareId)
        try container.encodeIfPresent(peerUid, forKey: .peerUid)
        try container.encodeIfPresent(peerPublicKey, forKey: .peerPublicKey)
    }

    func encodedString() -> String? {
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return nil
        }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let escaped = json.addingPercentEncoding(withAllowedCharacters: allowed) ?? json
        return Self.schemePrefix + escaped
    }

    static func decode(from string: String) -> ContactQRPayload? {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let rawJson: String
        if trimmed.lowercased().hasPrefix(Self.schemePrefix) {
            let encoded = String(trimmed.dropFirst(Self.schemePrefix.count))
            rawJson = encoded.removingPercentEncoding ?? encoded
        } else {
            rawJson = trimmed
        }

        guard let data = rawJson.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(ContactQRPayload.self, from: data)
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
