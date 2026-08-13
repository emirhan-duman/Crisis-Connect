import FirebaseAuth
import Foundation

enum TrustDossierAPIError: LocalizedError, Equatable {
    case signedOut
    case invalidEndpoint
    case invalidResponse
    case rejected(status: Int, code: String)

    var errorDescription: String? {
        switch self {
        case .signedOut: return NSLocalizedString("Sign in with an authorized institutional account.", comment: "")
        case .invalidEndpoint: return NSLocalizedString("The secure dossier service address is invalid.", comment: "")
        case .invalidResponse: return NSLocalizedString("The secure dossier service returned an invalid response.", comment: "")
        case .rejected(_, let code): return code.replacingOccurrences(of: "-", with: " ")
        }
    }
}

struct TrustDossierActor: Codable, Equatable, Sendable { let uid: String; let name: String }

struct TrustDossierSource: Codable, Equatable, Sendable {
    let kind: String
    let systemKey: String?
    let integrationId: String?
    let externalId: String?
    let externalVersion: String?
}

struct TrustDossierPolicyDecision: Codable, Equatable, Sendable {
    let status: String
    let policyId: String?
    let policyPackId: String?
    let policyPackContentSha256: String?
    let jurisdiction: String
    let signatureRequirement: String
    let organizationSeal: String
    let signerRoles: [String]
    let deliveryReceipt: String
}

struct TrustDossierRecordPlan: Codable, Equatable, Sendable {
    let filePlanCode: String?
    let retentionClass: String
    let retentionUntil: String?
    let legalHold: Bool
}

struct TrustDossierComponentView: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let fileName: String
    let mediaType: String
    let bytes: Int
    let sha256: String
}

struct TrustDossierSummary: Codable, Identifiable, Equatable, Sendable {
    var id: String { dossierId }
    let dossierId: String
    let title: String
    let description: String
    let purpose: String
    let classification: String
    let state: String
    let revision: Int
    let source: TrustDossierSource
    let policy: TrustDossierPolicyDecision
    let recordPlan: TrustDossierRecordPlan
    let components: [TrustDossierComponentView]
    let manifestSha256: String?
    let updatedAtMillis: Int64
}

struct TrustDossierPolicyPackContent: Codable, Equatable, Sendable {
    let signatureRequirement: String
    let organizationSeal: String
    let signerRoles: [String]
    let deliveryReceipt: String
    let retentionClass: String
    let retentionDays: Int
    let filePlanCode: String?
}

struct TrustDossierPolicyPack: Codable, Identifiable, Equatable, Sendable {
    var id: String { packId }
    let packId: String
    let revision: Int
    let status: String
    let name: String
    let jurisdiction: String
    let purpose: String
    let effectiveFrom: String
    let effectiveUntil: String?
    let content: TrustDossierPolicyPackContent
    let contentSha256: String
    let createdBy: TrustDossierActor
}

private struct DossierListEnvelope: Decodable { let dossiers: [TrustDossierSummary] }
private struct DossierEnvelope: Decodable { let dossier: TrustDossierSummary }
private struct PolicyPackListEnvelope: Decodable { let policyPacks: [TrustDossierPolicyPack] }
private struct APIErrorEnvelope: Decodable { let error: String? }

actor TrustDossierAPI {
    static let shared = TrustDossierAPI()
    private let session: URLSession
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared) { self.session = session }

    func list() async throws -> ([TrustDossierSummary], [TrustDossierPolicyPack]) {
        async let dossiers: DossierListEnvelope = request(path: "/api/dashboard/dossiers?limit=60")
        async let packs: PolicyPackListEnvelope = request(path: "/api/dashboard/dossier-policy-packs")
        return try await (dossiers.dossiers, packs.policyPacks)
    }

    func create(title: String, description: String, purpose: String, classification: String,
                jurisdiction: String, filePlanCode: String?) async throws -> TrustDossierSummary {
        let recordPlan: [String: Any] = [
            "filePlanCode": filePlanCode ?? NSNull(),
            "retentionClass": "policy_pending",
        ]
        let body: [String: Any] = ["dossier": [
            "title": title, "description": description, "purpose": purpose,
            "classification": classification, "jurisdiction": jurisdiction,
            "source": ["kind": "standalone", "systemKey": NSNull(), "integrationId": NSNull(),
                       "externalId": NSNull(), "externalVersion": NSNull()],
            "recordPlan": recordPlan,
        ]]
        let envelope: DossierEnvelope = try await request(path: "/api/dashboard/dossiers", method: "POST",
            body: body, idempotencyKey: operationID())
        return envelope.dossier
    }

    func applyPolicy(pack: TrustDossierPolicyPack, to dossier: TrustDossierSummary) async throws -> TrustDossierSummary {
        let retentionUntil = ISO8601DateFormatter().string(from: Date().addingTimeInterval(TimeInterval(pack.content.retentionDays) * 86_400))
        let body: [String: Any] = ["action": "accept_policy", "expectedRevision": dossier.revision,
            "policyPackId": pack.packId, "policyId": pack.packId,
            "signatureRequirement": pack.content.signatureRequirement, "organizationSeal": pack.content.organizationSeal,
            "signerRoles": pack.content.signerRoles, "deliveryReceipt": pack.content.deliveryReceipt,
            "retentionClass": pack.content.retentionClass, "retentionUntil": retentionUntil,
            "filePlanCode": pack.content.filePlanCode ?? NSNull()]
        return try await mutate(dossier: dossier, body: body)
    }

    func freeze(_ dossier: TrustDossierSummary) async throws -> TrustDossierSummary {
        try await mutate(dossier: dossier, body: ["action": "freeze", "expectedRevision": dossier.revision])
    }

    func upload(data: Data, fileName: String, mediaType: String, to dossier: TrustDossierSummary) async throws -> TrustDossierSummary {
        guard data.count <= 10 * 1_024 * 1_024 else { throw TrustDossierAPIError.rejected(status: 413, code: "file-too-large") }
        let boundary = "CrisisConnect-(UUID().uuidString)"
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!)
        }
        field("operationId", operationID()); field("expectedRevision", String(dossier.revision))
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"document\"; filename=\"\(safeFileName(fileName))\"\r\nContent-Type: \(mediaType)\r\n\r\n".data(using: .utf8)!)
        body.append(data); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let envelope: DossierEnvelope = try await request(path: "/api/dashboard/dossiers/\(encodedPath(dossier.dossierId))/components",
            method: "POST", rawBody: body, contentType: "multipart/form-data; boundary=\(boundary)")
        return envelope.dossier
    }

    private func mutate(dossier: TrustDossierSummary, body: [String: Any]) async throws -> TrustDossierSummary {
        let envelope: DossierEnvelope = try await request(path: "/api/dashboard/dossiers/\(encodedPath(dossier.dossierId))",
            method: "POST", body: body, idempotencyKey: operationID())
        return envelope.dossier
    }

    private func request<T: Decodable>(path: String, method: String = "GET", body: [String: Any]? = nil,
                                       rawBody: Data? = nil, contentType: String = "application/json; charset=utf-8",
                                       idempotencyKey: String? = nil) async throws -> T {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { throw TrustDossierAPIError.signedOut }
        let token = try await user.getIDToken()
        var request = try makeRequest(path: path, method: method, token: token, body: body, rawBody: rawBody,
                                      contentType: contentType, idempotencyKey: idempotencyKey)
        var (data, response) = try await session.data(for: request)
        if (response as? HTTPURLResponse)?.statusCode == 401 {
            request.setValue("Bearer \(try await user.getIDToken(forcingRefresh: true))", forHTTPHeaderField: "Authorization")
            (data, response) = try await session.data(for: request)
        }
        guard let http = response as? HTTPURLResponse else { throw TrustDossierAPIError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            let code = (try? decoder.decode(APIErrorEnvelope.self, from: data).error) ?? "request-rejected"
            throw TrustDossierAPIError.rejected(status: http.statusCode, code: code)
        }
        guard let value = try? decoder.decode(T.self, from: data) else { throw TrustDossierAPIError.invalidResponse }
        return value
    }

    private func makeRequest(path: String, method: String, token: String, body: [String: Any]?, rawBody: Data?,
                             contentType: String, idempotencyKey: String?) throws -> URLRequest {
        guard let url = endpoint(path: path) else { throw TrustDossierAPIError.invalidEndpoint }
        var request = URLRequest(url: url); request.httpMethod = method; request.timeoutInterval = 45
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if method != "GET" { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let idempotencyKey { request.setValue(idempotencyKey, forHTTPHeaderField: "x-cc-idempotency-key") }
        request.httpBody = rawBody ?? (body.map { try? JSONSerialization.data(withJSONObject: $0) } ?? nil)
        return request
    }

    private func endpoint(path: String) -> URL? {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let base = configured.isEmpty ? "https://crisisconnect.network" : configured
        guard var components = URLComponents(string: base), components.scheme == "https", components.user == nil,
              components.password == nil, let route = URLComponents(string: path) else { return nil }
        components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = "/" + [components.path, route.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))]
            .filter { !$0.isEmpty }.joined(separator: "/")
        components.queryItems = route.queryItems; components.fragment = nil
        return components.url
    }

    private func operationID() -> String { "ios:\(UUID().uuidString)" }
    private func encodedPath(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "invalid" }
    private func safeFileName(_ value: String) -> String {
        let filtered = value.unicodeScalars.map { scalar -> Character in scalar.value < 32 || scalar.value == 127 ? "_" : Character(String(scalar)) }
        return String(filtered).replacingOccurrences(of: "\"", with: "_").replacingOccurrences(of: "\\", with: "_").prefix(180).description
    }
}
