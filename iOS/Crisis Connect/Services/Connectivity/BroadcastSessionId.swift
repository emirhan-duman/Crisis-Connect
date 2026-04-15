//
//  BroadcastSessionId.swift
//  Crisis Connect
//
//  Created by Assistant on 16.01.2026
//

import Foundation
import CryptoKit

enum BroadcastSessionId {
    static func fromBroadcastId(_ broadcastId: String) -> UUID {
        fromRawIdentifier(broadcastId)
    }

    static func fromRawIdentifier(_ identifier: String) -> UUID {
        let hash = SHA256.hash(data: Data(identifier.utf8))
        var bytes = Array(hash.prefix(16))
        if bytes.count < 16 {
            bytes.append(contentsOf: Array(repeating: 0, count: 16 - bytes.count))
        }
        bytes[6] = (bytes[6] & 0x0F) | 0x40
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}
