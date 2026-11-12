//
//  ForwardMetadata.swift
//  Crisis Connect
//
//  Created by Assistant on 08.11.2026.
//

import Foundation

enum ForwardMetadata {
    static let prefix = "⏩ "

    static func formatForwardedText(_ text: String) -> String {
        let stripped = stripForward(text)
        guard !stripped.isEmpty else { return prefix.trimmingCharacters(in: .whitespaces) }
        return "\(prefix)\(stripped)"
    }

    static func stripForward(_ text: String) -> String {
        var current = text.trimmingCharacters(in: .whitespacesAndNewlines)
        while isForwarded(current) {
            current = String(current.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return current
    }

    static func isForwarded(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix(prefix)
    }
}
