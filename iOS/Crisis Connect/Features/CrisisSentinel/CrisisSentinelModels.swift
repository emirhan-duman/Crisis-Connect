//
//  CrisisSentinelModels.swift
//  Crisis Connect
//

import Foundation

enum CrisisSentinelUserMode: String, Codable, Equatable {
    case publicUser
    case fieldTeam
    case coordinator
}

enum CrisisSentinelResponseSource: String, Codable, Equatable {
    case offlineRules
    case localModel
    /// Answered by the dashboard's cloud chat API (online engine).
    case onlineModel
}

enum CrisisSentinelQueryDomain: String, Codable, Equatable {
    case crisisGuided
    case general
}

enum CrisisSentinelIncidentType: String, Equatable {
    case unknown
    case earthquake
    case fire
    case flood
    case hazard
    case medical
    case trapped
    case communication
    case logistics
    case shelter
    case publicHealth
    case translation
    case strategy
    case evacuation
    case mentalHealth
}

enum CrisisSentinelPriority: String, Equatable {
    case routine
    case high
    case immediate
}

struct CrisisSentinelRequest: Equatable {
    var prompt: String
    var mode: CrisisSentinelUserMode
    var locale: Locale
    var recentMessages: [String]

    init(
        prompt: String,
        mode: CrisisSentinelUserMode = .publicUser,
        locale: Locale = .current,
        recentMessages: [String] = []
    ) {
        self.prompt = prompt
        self.mode = mode
        self.locale = locale
        self.recentMessages = recentMessages
    }
}

struct CrisisSentinelResponse: Equatable {
    let answer: String
    let mode: CrisisSentinelUserMode
    let source: CrisisSentinelResponseSource
    let citations: [CrisisSentinelCitation]
    let incidentDraft: CrisisSentinelIncidentDraft?
    let followUpQuestions: [String]
    let safetyNotices: [String]
    let confidence: Float
}

struct CrisisSentinelCitation: Identifiable, Equatable {
    var id: String { articleId }

    let articleId: String
    let title: String
    let category: String
    let snippet: String
    let score: Int
}

struct CrisisSentinelIncidentDraft: Equatable {
    let type: CrisisSentinelIncidentType
    let priority: CrisisSentinelPriority
    let casualtyCount: Int?
    let trappedCount: Int?
    let locationText: String?
    let hazards: [String]
    let needs: [String]
    let missingFields: [String]
    let confidence: Float
}

struct CrisisSentinelModelRequest: Equatable {
    let systemInstruction: String
    let userPrompt: String
    let locale: Locale
    let mode: CrisisSentinelUserMode
    let queryDomain: CrisisSentinelQueryDomain
    let responseSchema: String
    let contextSnippets: [String]
    /// Prior conversation turns (already truncated by the engine). Without these the model treats
    /// every message as a fresh chat, so follow-ups like "devam et" had nothing to follow.
    var recentMessages: [String] = []
}

struct CrisisSentinelModelResponse: Equatable {
    let text: String
    let structuredJSON: String?
    let confidence: Float?
}

extension CrisisSentinelUserMode {
    var promptRoleLabel: String {
        switch self {
        case .publicUser:
            return "genel kullanıcı"
        case .fieldTeam:
            return "saha ekibi"
        case .coordinator:
            return "yetkili koordinasyon"
        }
    }
}

protocol CrisisSentinelModelRuntime {
    var id: String { get }
    var isReady: Bool { get }

    func generate(_ request: CrisisSentinelModelRequest) async throws -> CrisisSentinelModelResponse
    func cancelGeneration()
}

extension CrisisSentinelModelRuntime {
    func cancelGeneration() {}
}
