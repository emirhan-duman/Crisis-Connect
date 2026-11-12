import FirebaseAuth
import FirebaseAppCheck
import FirebaseCore
import Foundation

enum ResilientMediaClient {
    private static let policy = RegionalRoutePolicy()

    static func uploadAvatar(_ data: Data, generation: String) async throws -> URL {
        guard let uid = Auth.auth().currentUser?.uid else { throw InternetMessagingClientError.notSignedIn }
        let id = UUID(uuidString: generation) ?? UUID()
        let result = try await request("users/\(uid)/avatars/\(id.uuidString).jpg", method: "PUT", body: data)
        guard let object = try JSONSerialization.jsonObject(with: result) as? [String: Any],
              let value = object["downloadUrl"] as? String, let url = URL(string: value) else {
            throw InternetMessagingClientError.malformedResponse
        }
        return url
    }
    static func uploadAttachment(path: String, data: Data) async throws { _ = try await request(path, method: "PUT", body: data) }
    static func downloadAttachment(path: String) async throws -> Data { try await request(path, method: "GET") }
    static func delete(path: String) async throws { _ = try await request(path, method: "DELETE") }
    static func avatarPath(_ url: String) -> String? {
        guard let value = URLComponents(string: url), value.host == "firebasestorage.googleapis.com" else { return nil }
        let prefix = "/v0/b/crisis-connect-1-media-eu/o/"
        guard value.path.hasPrefix(prefix) else { return nil }
        let path = String(value.path.dropFirst(prefix.count))
        return path.contains("/avatars/") ? path : nil
    }

    static func request(_ path: String, method: String, body: Data? = nil, app: FirebaseApp? = nil,
                        route: RegionalRoutePolicy = policy) async throws -> Data {
        let auth = app.map { Auth.auth(app: $0) } ?? Auth.auth()
        guard let user = auth.currentUser else { throw InternetMessagingClientError.notSignedIn }
        let uid = user.uid, token = try await user.getIDToken()
        let check = try await (app.map { AppCheck.appCheck(app: $0)! } ?? AppCheck.appCheck()).token(forcingRefresh: false).token
        func attempt(_ region: String) async throws -> Data {
            guard auth.currentUser?.uid == uid else { throw InternetMessagingClientError.notSignedIn }
            let function = region == RegionalRoutePolicy.europe ? "applicationMediaEurope" : "applicationMediaUS"
            var components = URLComponents(string: "https://\(region)-crisis-connect-1.cloudfunctions.net/\(function)")!
            components.queryItems = [URLQueryItem(name: "path", value: path)]
            var request = URLRequest(url: components.url!)
            request.httpMethod = method; request.httpBody = body; request.timeoutInterval = 180
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue(check, forHTTPHeaderField: "X-Firebase-AppCheck")
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200..<300).contains(status) else { throw NSError(domain: "ApplicationMedia", code: status) }
            guard data.count <= 25 * 1024 * 1024 + 4096, auth.currentUser?.uid == uid else {
                throw InternetMessagingClientError.malformedResponse
            }
            return data
        }
        let first = route.preferred()
        do { return try await attempt(first) }
        catch {
            let e = error as NSError
            guard (RegionalMessagingCalls.retryable(error) || (e.domain == "ApplicationMedia" && e.code >= 500)),
                  let alternate = route.alternate(first) else { throw error }
            route.failed(first)
            return try await attempt(alternate)
        }
    }
}
