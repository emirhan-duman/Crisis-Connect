//
//  BleCrypto.swift
//  Crisis Connect
//
//  Created by Assistant on 11.01.2026
//

import Foundation
import CryptoKit

enum BleKeyDecoder {
    private static let x509Prefix: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    ]

    static func x963PublicKey(from x509: Data) throws -> Data {
        guard x509.count >= x509Prefix.count + 65 else {
            throw KeyDecoderError.invalidKeyLength
        }
        let prefixData = Data(x509Prefix)
        guard x509.starts(with: prefixData) else {
            throw KeyDecoderError.invalidKeyPrefix
        }
        let x963 = x509.suffix(65)
        guard x963.first == 0x04 else {
            throw KeyDecoderError.invalidKeyPrefix
        }
        return Data(x963)
    }

    enum KeyDecoderError: Error {
        case invalidKeyLength
        case invalidKeyPrefix
    }
}

enum BleAesGcm {
    static func decryptPacket(
        key: SymmetricKey,
        transportPacket: Data,
        maxPacketSize: Int
    ) throws -> Data {
        let encrypted = try unwrapTransportPacket(transportPacket, maxPacketSize: maxPacketSize)
        let sealedBox = try AES.GCM.SealedBox(combined: encrypted)
        return try AES.GCM.open(sealedBox, using: key)
    }

    static func encryptPacket(
        key: SymmetricKey,
        plaintext: Data,
        maxPacketSize: Int
    ) -> Data {
        guard let sealedBox = try? AES.GCM.seal(plaintext, using: key),
              let combined = sealedBox.combined,
              combined.count <= maxPacketSize else {
            return Data()
        }
        return wrapTransportPacket(combined)
    }

    static func unwrapTransportPacket(_ transportPacket: Data, maxPacketSize: Int) throws -> Data {
        guard transportPacket.count >= 2 else {
            throw TransportError.missingHeader
        }
        let length = (Int(transportPacket[0]) << 8) | Int(transportPacket[1])
        guard length >= 1 && length <= maxPacketSize else {
            throw TransportError.invalidLength
        }
        guard transportPacket.count - 2 == length else {
            throw TransportError.truncatedPayload
        }
        return transportPacket.subdata(in: 2..<transportPacket.count)
    }

    static func wrapTransportPacket(_ payload: Data) -> Data {
        var packet = Data()
        let length = payload.count
        packet.append(UInt8((length >> 8) & 0xFF))
        packet.append(UInt8(length & 0xFF))
        packet.append(payload)
        return packet
    }

    enum TransportError: Error {
        case missingHeader
        case invalidLength
        case truncatedPayload
    }
}

final class BleChunkReceiver {
    private let maxPacketSize: Int
    private var header = Data()
    private var payload = Data()
    private var expectedLength: Int?

    init(maxPacketSize: Int) {
        self.maxPacketSize = maxPacketSize
    }

    func reset() {
        header.removeAll()
        payload.removeAll()
        expectedLength = nil
    }

    func onChunk(_ chunk: Data) throws -> Data? {
        guard !chunk.isEmpty else { return nil }
        var offset = 0

        if expectedLength == nil {
            while header.count < 2 && offset < chunk.count {
                header.append(chunk[offset])
                offset += 1
            }
            if header.count == 2 {
                let length = (Int(header[0]) << 8) | Int(header[1])
                guard length >= 1 && length <= maxPacketSize else {
                    throw ChunkError.invalidLength
                }
                expectedLength = length
            }
        }

        guard let expectedLength else { return nil }
        if offset < chunk.count {
            payload.append(chunk.subdata(in: offset..<chunk.count))
        }
        guard payload.count <= expectedLength else {
            throw ChunkError.payloadOverflow
        }
        if payload.count == expectedLength {
            var packet = Data()
            packet.append(header)
            packet.append(payload)
            reset()
            return packet
        }
        return nil
    }

    enum ChunkError: Error {
        case invalidLength
        case payloadOverflow
    }
}
