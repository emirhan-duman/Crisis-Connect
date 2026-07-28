//
//  MessagingDiagLog.swift
//  Crisis Connect
//
//  File-backed diagnostics for the internet-messaging send/receive path — iOS counterpart of
//  Android's conn_diag file. Wireless console streaming (devicectl --console) is too unstable
//  for field debugging, so the interesting failures are ALSO appended to
//  Documents/messaging_diag.log, which can be pulled off a dev device with one-shot tooling:
//
//    xcrun devicectl device copy from --domain-type appDataContainer \
//      --domain-identifier com.auralis.crisisconnect \
//      --source Documents/messaging_diag.log --destination ./diag.log
//
//  Content policy: transport decisions, error descriptions, uids and message ids only — never
//  message plaintext. Size-capped by halving: when the file exceeds the cap the oldest half is
//  dropped, so it never grows unbounded and never needs external rotation.
//

import Foundation

enum MessagingDiagLog {
    private static let queue = DispatchQueue(label: "messaging.diaglog", qos: .utility)
    private static let maxBytes = 256 * 1024

    private static var fileURL: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("messaging_diag.log")
    }

    /// Appends a timestamped line. Also mirrors to NSLog so an attached console sees it too.
    static func log(_ message: String) {
        NSLog("[MsgDiag] %@", message)
        queue.async {
            guard let url = fileURL else { return }
            let stamp = ISO8601DateFormatter().string(from: Date())
            let line = "\(stamp) \(message)\n"
            guard let data = line.data(using: .utf8) else { return }
            do {
                if FileManager.default.fileExists(atPath: url.path) {
                    let handle = try FileHandle(forWritingTo: url)
                    defer { try? handle.close() }
                    try handle.seekToEnd()
                    try handle.write(contentsOf: data)
                } else {
                    try data.write(to: url, options: .atomic)
                }
                trimIfNeeded(url)
            } catch {
                // Diagnostics must never break messaging; drop the line.
            }
        }
    }

    private static func trimIfNeeded(_ url: URL) {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int,
              size > maxBytes,
              let data = try? Data(contentsOf: url) else { return }
        let keep = data.suffix(maxBytes / 2)
        // Cut at the next newline so the file always starts on a whole line.
        if let nl = keep.firstIndex(of: UInt8(ascii: "\n")) {
            try? keep.suffix(from: keep.index(after: nl)).write(to: url, options: .atomic)
        } else {
            try? keep.write(to: url, options: .atomic)
        }
    }
}
