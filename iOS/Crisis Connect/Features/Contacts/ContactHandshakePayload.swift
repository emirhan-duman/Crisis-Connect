//
//  ContactHandshakePayload.swift
//  Crisis Connect
//
//  Created by Assistant on 16.01.2026
//

import Foundation

struct ContactHandshakePayload: Codable {
    static let kindValue = "psk_hello"
    static let currentVersion = 1

    let kind: String
    let v: Int
    let name: String
    let broadcastId: String

    static func make(name: String, broadcastId: String) -> ContactHandshakePayload {
        ContactHandshakePayload(kind: kindValue, v: currentVersion, name: name, broadcastId: broadcastId)
    }

    static func decode(from data: Data) -> ContactHandshakePayload? {
        guard let payload = try? JSONDecoder().decode(ContactHandshakePayload.self, from: data),
              payload.kind == kindValue else {
            return nil
        }
        return payload
    }
}
