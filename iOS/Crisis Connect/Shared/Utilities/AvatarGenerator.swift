//
//  AvatarGenerator.swift
//  Crisis Connect
//
//  Created by Assistant on 12.01.2026
//

import Foundation

struct AvatarGenerator {
    static func initials(from name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "?" }

        let separators = CharacterSet.whitespacesAndNewlines
            .union(CharacterSet(charactersIn: "-_") )
        let parts = trimmed
            .components(separatedBy: separators)
            .filter { !$0.isEmpty }

        if parts.count >= 2,
           let first = parts.first?.first,
           let last = parts.last?.first {
            return "\(first)\(last)".uppercased()
        }

        if let part = parts.first {
            let letters = part.filter { $0.isLetter || $0.isNumber }
            if letters.count >= 2 {
                return String(letters.prefix(2)).uppercased()
            }
            if let first = part.first {
                return String(first).uppercased()
            }
        }

        let compact = trimmed.filter { !$0.isWhitespace }
        if compact.count >= 2 {
            return String(compact.prefix(2)).uppercased()
        }
        if let first = compact.first {
            return String(first).uppercased()
        }
        return "?"
    }

    static func hue(for id: UUID) -> Double {
        let string = id.uuidString
        var hash: UInt64 = 5381
        for scalar in string.unicodeScalars {
            hash = ((hash << 5) &+ hash) &+ UInt64(scalar.value)
        }
        return Double(hash % 360) / 360.0
    }
}
