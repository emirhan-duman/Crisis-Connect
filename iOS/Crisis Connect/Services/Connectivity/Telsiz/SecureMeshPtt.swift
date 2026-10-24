import Foundation
import CryptoKit

/// CPT2: hop is the only relay-mutable byte. Every other header/body byte is GCM-bound
/// and signed by a fresh talk-session key certified by the responder's role identity.
final class SecureMeshPtt {
    struct VerifiedRole { let agency: String?; let expiresAt: Int64 }
    struct Frame {
        let kind: UInt8; let origin: String; let label: String; let timestamp: Int64
        let sequence: UInt32; let hop: Int; let body: Data; let agency: String?
    }
    private struct Peer { let key: P256.Signing.PublicKey; let role: VerifiedRole; var sequence: UInt32 }
    private let signingKey = P256.Signing.PrivateKey()
    private let groupKey: () -> Data?
    private let createProof: (String) -> Data?
    private let verifyProof: (Data, String) -> VerifiedRole?
    private let clock: () -> Int64
    private var sequence: UInt32 = 0
    private var proof: Data?
    private var proofAt: Int64 = 0
    private var peers: [String: Peer] = [:]
    static let magic = Data("CPT2".utf8)

    init(groupKey: @escaping () -> Data?, createProof: @escaping (String) -> Data?,
         verifyProof: @escaping (Data, String) -> VerifiedRole?,
         clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.groupKey = groupKey; self.createProof = createProof; self.verifyProof = verifyProof; self.clock = clock
    }

    static func isFrame(_ data: Data) -> Bool { data.starts(with: magic) }
    private static func nonce(_ publicKey: Data) -> String { "cc-ptt-v2|" + publicKey.base64EncodedString() }
    private static func origin(_ publicKey: Data) -> Data { Data(SHA256.hash(data: publicKey).prefix(16)) }
    private static func put(_ value: UInt64, count: Int, into data: inout Data) {
        for shift in stride(from: (count - 1) * 8, through: 0, by: -8) { data.append(UInt8((value >> shift) & 255)) }
    }

    func encode(kind: UInt8, label: String, body: Data) -> Data? {
        let now = clock()
        guard (1...6).contains(kind), body.count <= 4096, sequence < UInt32.max,
              let key = groupKey(), key.count == 32 else { return nil }
        let pub = signingKey.publicKey.derRepresentation
        if kind != 5 && (proof == nil || now - proofAt >= 30_000) {
            proof = createProof(Self.nonce(pub)); proofAt = now
        }
        guard let proof, now >= proofAt, now - proofAt < 60_000, proof.count <= 3000 else { return nil }
        sequence += 1
        let name = Data(String(label.prefix(16)).utf8)
        guard name.count <= 64 else { return nil }
        var credential = Data()
        if kind != 5 {
            Self.put(UInt64(pub.count), count: 2, into: &credential); credential.append(pub); credential.append(proof)
        }
        var header = Self.magic
        header.append(0); header.append(kind); header.append(Self.origin(pub))
        Self.put(UInt64(now), count: 8, into: &header); Self.put(UInt64(sequence), count: 4, into: &header)
        header.append(UInt8(name.count)); header.append(name)
        Self.put(UInt64(credential.count), count: 2, into: &header); header.append(credential)
        let aad = Data(header.prefix(4) + header.dropFirst(5))
        guard let sealed = try? AES.GCM.seal(body, using: SymmetricKey(data: key), authenticating: aad).combined,
              let signature = try? signingKey.signature(for: aad + sealed).derRepresentation,
              signature.count <= 80 else { return nil }
        return header + sealed + signature + Data([UInt8(signature.count)])
    }

    /// Relays retain the exact origin ciphertext/signature and change only the bounded hop byte.
    static func relayed(_ wire: Data, hop: Int) -> Data? {
        guard isFrame(wire), wire.count > 5, (0...4).contains(hop) else { return nil }
        var copy = wire; copy[4] = UInt8(hop); return copy
    }

    func decode(_ wire: Data) -> Frame? {
        guard Self.isFrame(wire), wire.count >= 45, wire.count <= 4096,
              let key = groupKey(), key.count == 32 else { return nil }
        let bytes = [UInt8](wire); var offset = 4
        func read(_ count: Int) -> Data? {
            guard count >= 0, offset <= bytes.count - count else { return nil }
            defer { offset += count }; return Data(bytes[offset..<(offset + count)])
        }
        func number(_ count: Int) -> UInt64? { read(count)?.reduce(0) { ($0 << 8) | UInt64($1) } }
        guard let hop = number(1), hop <= 4, let kindValue = number(1), (1...6).contains(kindValue),
              let originBytes = read(16), let timestamp = number(8), timestamp <= UInt64(Int64.max),
              let seq = number(4), seq > 0, let nameLength = number(1), nameLength <= 64,
              let nameBytes = read(Int(nameLength)), let label = String(data: nameBytes, encoding: .utf8),
              let credentialLength = number(2), credentialLength <= 3200,
              let credential = read(Int(credentialLength)) else { return nil }
        let now = clock(), ts = Int64(timestamp)
        guard ts >= now - 10_000, ts <= now + 2_000 else { return nil }
        let signatureLength = Int(bytes.last!)
        guard (8...80).contains(signatureLength), offset + 28 + signatureLength + 1 <= wire.count else { return nil }
        let end = wire.count - signatureLength - 1
        let ciphertext = wire.subdata(in: offset..<end)
        let aad = Data(wire.prefix(4) + wire.dropFirst(5).prefix(offset - 5))
        let origin = originBytes.map { String(format: "%02x", $0) }.joined()
        guard originBytes != Self.origin(signingKey.publicKey.derRepresentation) else { return nil }
        peers = peers.filter { $0.value.role.expiresAt > now }
        var candidate = peers[origin]
        if !credential.isEmpty {
            guard credential.count >= 2 else { return nil }
            let pubLength = Int(credential[0]) << 8 | Int(credential[1])
            guard pubLength > 0, pubLength <= 256, 2 + pubLength < credential.count else { return nil }
            let pub = credential.subdata(in: 2..<(2 + pubLength))
            guard Self.origin(pub) == originBytes, let signingPublic = try? P256.Signing.PublicKey(derRepresentation: pub),
                  let role = verifyProof(Data(credential.dropFirst(2 + pubLength)), Self.nonce(pub)), role.expiresAt > now else { return nil }
            candidate = Peer(key: signingPublic, role: role, sequence: candidate?.sequence ?? 0)
        }
        guard var peer = candidate, peer.role.expiresAt > now, seq > peer.sequence,
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: wire.subdata(in: end..<(wire.count - 1))),
              peer.key.isValidSignature(signature, for: aad + ciphertext),
              let box = try? AES.GCM.SealedBox(combined: ciphertext),
              let body = try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad) else { return nil }
        guard peers[origin] != nil || peers.count < 128 else { return nil }
        peer.sequence = UInt32(seq); peers[origin] = peer
        return Frame(kind: UInt8(kindValue), origin: origin, label: label, timestamp: ts,
            sequence: UInt32(seq), hop: Int(hop), body: body, agency: peer.role.agency)
    }
}
