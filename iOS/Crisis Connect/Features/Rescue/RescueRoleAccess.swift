//
//  RescueRoleAccess.swift
//  Crisis Connect
//
//  Created by Assistant on 11.01.2026
//

import Foundation

enum RescueRoleAccess {
    static let allowedRoles: Set<String> = ["admin", "fieldteam"]

    static func normalizedRole(_ role: String?) -> String? {
        let normalized = role?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        guard !normalized.isEmpty else { return nil }
        switch normalized {
        case "admin":
            return "admin"
        case "fieldteam", "field_team", "field-team", "ft":
            return "fieldteam"
        default:
            return nil
        }
    }

    static func isAuthorized(_ role: String?) -> Bool {
        guard let normalized = normalizedRole(role) else { return false }
        return allowedRoles.contains(normalized)
    }
}
