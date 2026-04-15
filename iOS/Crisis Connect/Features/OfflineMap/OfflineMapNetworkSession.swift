//
//  OfflineMapNetworkSession.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import Foundation

enum OfflineMapNetworkSession {
    static let interactive: URLSession = makeSession(backgroundFriendly: false)
    static let backgroundContinuation: URLSession = makeSession(backgroundFriendly: true)

    private static func makeSession(backgroundFriendly: Bool) -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 6 * 60 * 60
        configuration.httpMaximumConnectionsPerHost = backgroundFriendly ? 2 : 4
        configuration.allowsCellularAccess = true
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        return URLSession(configuration: configuration)
    }
}
