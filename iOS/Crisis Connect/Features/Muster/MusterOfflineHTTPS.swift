#if DEBUG
import Foundation
import Security

struct MusterOfflineQAStatus: Decodable {
    struct Event: Decodable {
        let eventId: String
        let answeredAtMillis: Int64
        let receivedAtMillis: Int64
        let hasNote: Bool
        let voiceBytes: Int
    }

    let checkedAtMillis: Int64
    let events: [Event]
    let eventCount: Int
    let voiceCount: Int
}

/// The local QA server issues an identity for one synthetic check-in and expires it after 30 minutes.
@MainActor
final class MusterOfflineHTTPS {
    static let configurationVariable = "MUSTER_OFFLINE_QA_JSON"
    static let certificateVariable = "MUSTER_OFFLINE_QA_CA_PEM"
    static let lanAddressVariable = "MUSTER_OFFLINE_QA_LAN_IP"

    private struct Manifest: Decodable {
        let origin: String
        let bearer: String
        let uid: String
        let panelId: String
        let sessionId: String
        let expiresAtMillis: Int64
    }

    let uid: String
    let panelId: String
    let sessionId: String
    private let origin: URL
    private let expiry: Date
    private var bearer: String?
    private let network: URLSession
    private let audioNetwork: URLSession
    private var expiration: Task<Void, Never>?

    static func takeLaunchConfiguration() throws -> MusterOfflineHTTPS {
        let environment = ProcessInfo.processInfo.environment
        unsetenv(configurationVariable)
        unsetenv(certificateVariable)
        unsetenv(lanAddressVariable)
        guard let manifest = environment[configurationVariable] else { throw MusterError.invalid }
        return try MusterOfflineHTTPS(json: manifest, caPEM: environment[certificateVariable],
                                      approvedLANIPv4: environment[lanAddressVariable])
    }

    init(json: String, caPEM: String?, approvedLANIPv4: String? = nil) throws {
        guard json.utf8.count <= 4096 else { throw MusterError.invalid }
        let manifest = try JSONDecoder().decode(Manifest.self, from: Data(json.utf8))
        let allowedHosts: Set<String> = ["127.0.0.1", "localhost", "bs-local.com"]
        guard let components = URLComponents(string: manifest.origin), components.scheme == "https",
              let host = components.host, components.port == 19089,
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/", let origin = components.url,
              manifest.uid == "qa-muster-ios",
              manifest.panelId.range(of: #"^native-qa-[a-f0-9-]{36}$"#, options: .regularExpression) != nil,
              manifest.sessionId.range(of: #"^[a-f0-9]{32}$"#, options: .regularExpression) != nil,
              manifest.bearer.range(of: #"^[A-Za-z0-9_-]{43}$"#, options: .regularExpression) != nil else {
            throw MusterError.invalid
        }
        if !allowedHosts.contains(host) {
            let canonicalOrigin = "https://\(host):19089"
            guard approvedLANIPv4 == host, Self.isCanonicalLANIPv4(host), caPEM != nil,
                  manifest.origin == canonicalOrigin || manifest.origin == canonicalOrigin + "/" else {
                throw MusterError.invalid
            }
        }
        let expiry = Date(timeIntervalSince1970: Double(manifest.expiresAtMillis) / 1000)
        let seconds = expiry.timeIntervalSinceNow
        guard seconds > 0, seconds <= 30 * 60 + 30 else { throw MusterError.rejected }

        self.uid = manifest.uid
        self.panelId = manifest.panelId
        self.sessionId = manifest.sessionId
        self.origin = origin
        self.expiry = expiry
        self.bearer = manifest.bearer
        let anchor = try caPEM.map(Self.certificate)
        let delegate = MusterOfflineTrust(host: host, anchor: anchor)
        network = Self.makeSession(request: 20, resource: 30, delegate: delegate)
        audioNetwork = Self.makeSession(request: 60, resource: 180, delegate: delegate)
        expiration = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            if !Task.isCancelled { self?.close() }
        }
    }

    static func isCanonicalLANIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let octets = parts.compactMap { part -> Int? in
            guard let value = Int(part), (0...255).contains(value), String(value) == part else { return nil }
            return value
        }
        guard octets.count == 4 else { return false }
        return octets[0] == 10 || (octets[0] == 172 && (16...31).contains(octets[1]))
            || (octets[0] == 192 && octets[1] == 168)
    }

    var isActive: Bool { bearer != nil && Date() < expiry }

    func close() {
        bearer = nil
        expiration?.cancel()
        expiration = nil
        network.invalidateAndCancel()
        audioNetwork.invalidateAndCancel()
    }

    func send(_ event: MusterEvent, body: Data) async throws -> (Int, Data) {
        guard event.uid == uid, event.panelId == panelId, event.sessionId == sessionId else {
            throw MusterError.invalid
        }
        return try await request(path: "/api/mobile/muster", body: body, client: event.voice == nil ? network : audioNetwork)
    }

    func status() async throws -> MusterOfflineQAStatus {
        let (status, data) = try await request(path: "/qa/status", client: network)
        guard status == 200 else { throw MusterError.rejected }
        return try JSONDecoder().decode(MusterOfflineQAStatus.self, from: data)
    }

    private func request(path: String, body: Data? = nil, client: URLSession) async throws -> (Int, Data) {
        guard isActive, let bearer else {
            close()
            throw MusterError.rejected
        }
        var request = URLRequest(url: origin.appending(path: path))
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let (data, response) = try await client.data(for: request)
        guard let http = response as? HTTPURLResponse, data.count <= 2_000_000 else { throw MusterError.invalid }
        return (http.statusCode, data)
    }

    private static func makeSession(request: TimeInterval, resource: TimeInterval, delegate: MusterOfflineTrust) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = request
        config.timeoutIntervalForResource = resource
        config.httpShouldSetCookies = false
        return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    private static func certificate(_ pem: String) throws -> SecCertificate {
        let lines = pem.split(whereSeparator: \.isNewline)
        guard pem.utf8.count <= 4096, lines.first == "-----BEGIN CERTIFICATE-----",
              lines.last == "-----END CERTIFICATE-----", lines.count > 2,
              let der = Data(base64Encoded: lines.dropFirst().dropLast().joined()),
              let cert = SecCertificateCreateWithData(nil, der as CFData) else { throw MusterError.invalid }
        return cert
    }
}

private final class MusterOfflineTrust: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let host: String
    private let anchor: SecCertificate?

    init(host: String, anchor: SecCertificate?) {
        self.host = host
        self.anchor = anchor
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let space = challenge.protectionSpace
        guard space.host == host, space.port == 19089,
              space.authenticationMethod == NSURLAuthenticationMethodServerTrust, let trust = space.serverTrust else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        guard let anchor else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        guard SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString)) == errSecSuccess,
              SecTrustSetAnchorCertificates(trust, [anchor] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess,
              SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
#endif
