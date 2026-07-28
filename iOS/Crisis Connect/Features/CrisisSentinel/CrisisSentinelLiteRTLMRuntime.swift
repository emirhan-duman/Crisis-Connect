//
//  CrisisSentinelLiteRTLMRuntime.swift
//  Crisis Connect
//

import Foundation
import LiteRTLM

enum CrisisSentinelLiteRTLMRuntimeError: Error, Equatable {
    case modelNotInstalled
    case engineUnavailable
}

final class CrisisSentinelLiteRTLMRuntime: CrisisSentinelModelRuntime {
    private struct GenerationConfig {
        let maxTokens: Int
        let topK: Int
        let topP: Float
        let temperature: Float
        let seed: Int
    }

    private let store: CrisisSentinelModelFileStore
    private let lock = NSLock()
    private let engineBox = CrisisSentinelLiteRTLMEngineBox()
    private var release: CrisisSentinelModelRelease
    private let maxTokens: Int
    private let topK: Int
    private let topP: Float
    private let temperature: Float
    private let seed: Int

    init(
        store: CrisisSentinelModelFileStore = CrisisSentinelModelFileStore(),
        release: CrisisSentinelModelRelease = .defaultRelease,
        maxTokens: Int = 1024,
        topK: Int = 32,
        topP: Float = 0.90,
        temperature: Float = 0.15,
        seed: Int = 7
    ) {
        self.store = store
        self.release = release
        self.maxTokens = maxTokens
        self.topK = topK
        self.topP = topP
        self.temperature = temperature
        self.seed = seed
    }

    var id: String {
        "litert-lm:\(currentRelease.id)"
    }

    var isReady: Bool {
        store.installedModel(for: currentRelease, validateChecksum: false) != nil
    }

    func updateRelease(_ release: CrisisSentinelModelRelease) {
        lock.lock()
        self.release = release
        lock.unlock()
    }

    func generate(_ request: CrisisSentinelModelRequest) async throws -> CrisisSentinelModelResponse {
        let release = currentRelease
        guard let installed = store.installedModel(for: release, validateChecksum: false) else {
            throw CrisisSentinelLiteRTLMRuntimeError.modelNotInstalled
        }

        let generationConfig = generationConfig(for: request.queryDomain)
        let text = try await engineBox.generate(
            modelURL: installed.fileURL,
            cacheURL: Self.cacheDirectoryURL(),
            maxTokens: generationConfig.maxTokens,
            topK: generationConfig.topK,
            topP: generationConfig.topP,
            temperature: generationConfig.temperature,
            seed: generationConfig.seed,
            systemInstruction: request.systemInstruction,
            prompt: renderPrompt(request)
        )

        return CrisisSentinelModelResponse(
            text: text,
            structuredJSON: text,
            confidence: 0.68
        )
    }

    func cancelGeneration() {
        Task {
            await engineBox.cancelGeneration()
        }
    }

    private var currentRelease: CrisisSentinelModelRelease {
        lock.lock()
        defer { lock.unlock() }
        return release
    }

    private func renderPrompt(_ request: CrisisSentinelModelRequest) -> String {
        if request.queryDomain == .general {
            return renderGeneralPrompt(request)
        }
        var lines: [String] = [
            "Kullanıcı rolü: \(request.mode.promptRoleLabel)",
            "Cevap dili: \(CrisisSentinelText.responseLanguageName(request.locale))",
            "",
            "Kullanıcı sorusu:",
            request.userPrompt.isEmpty ? (request.recentMessages.last ?? "") : request.userPrompt
        ]

        // Conversation memory (Android parity): without the prior turns the model answers every
        // message as a brand-new chat and follow-up questions land in a void.
        if request.recentMessages.isEmpty == false {
            lines.append("")
            lines.append("Son konuşma:")
            lines.append(contentsOf: request.recentMessages.map { "- \($0.prefix(140))" })
        }

        lines.append("")
        lines.append("Yerel kaynak bağlamı:")
        if request.contextSnippets.isEmpty {
            lines.append("- Yerel bağlam yoksa genel, güvenli ve sınırları belirten cevap ver.")
        } else {
            lines.append(contentsOf: request.contextSnippets.map { "- \($0)" })
        }

        lines.append(contentsOf: [
            "",
            "Cevap formatı: 3-5 kısa cümle. Gerekirse en fazla 2 net soru sor. JSON yazma.",
            "Kurallar: Kullanıcının dilinde cevap ver; sistem/politika/model metni yazma; ilgisiz önceki cevapları kopyalama."
        ])

        return lines.joined(separator: "\n")
    }

    /// General chat gets a plain conversation transcript, not the crisis scaffold — the general
    /// system instruction already carries the rules, and repeating the role/context frame here made
    /// the model echo it back into casual answers.
    private func renderGeneralPrompt(_ request: CrisisSentinelModelRequest) -> String {
        let prompt = (request.userPrompt.isEmpty ? (request.recentMessages.last ?? "") : request.userPrompt)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard request.recentMessages.isEmpty == false else { return prompt }

        // Keep the scaffold in the response language so the model doesn't drift into English.
        let isTurkish = CrisisSentinelText.isTurkish(request.locale)
        var lines: [String] = [isTurkish ? "Önceki konuşma:" : "Recent conversation:"]
        lines.append(contentsOf: request.recentMessages.map { String($0.prefix(180)) })
        lines.append("")
        lines.append(isTurkish ? "Kullanıcı:" : "User:")
        lines.append(prompt)
        return lines.joined(separator: "\n")
    }

    private func generationConfig(for queryDomain: CrisisSentinelQueryDomain) -> GenerationConfig {
        if queryDomain == .general {
            return GenerationConfig(
                maxTokens: maxTokens,
                topK: 64,
                topP: 0.95,
                // 0.85 amplified hallucinations on the small on-device release; 0.7 keeps general
                // chat natural while staying coherent (Android parity: generalTemperature = 0.7).
                temperature: 0.7,
                seed: 0
            )
        }

        return GenerationConfig(
            maxTokens: maxTokens,
            topK: topK,
            topP: topP,
            temperature: temperature,
            seed: seed
        )
    }

    private static func cacheDirectoryURL() -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let url = base.appendingPathComponent("CrisisSentinelLiteRTLM", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

private actor CrisisSentinelLiteRTLMEngineBox {
    private var engine: Engine?
    private var modelPath: String?
    private var engineMaxTokens: Int?
    private var activeConversation: Conversation?

    func generate(
        modelURL: URL,
        cacheURL: URL,
        maxTokens: Int,
        topK: Int,
        topP: Float,
        temperature: Float,
        seed: Int,
        systemInstruction: String,
        prompt: String
    ) async throws -> String {
        let engine = try await ensureEngine(
            modelURL: modelURL,
            cacheURL: cacheURL,
            maxTokens: maxTokens
        )

        let sampler = try SamplerConfig(
            topK: topK,
            topP: topP,
            temperature: temperature,
            seed: seed
        )
        let conversationConfig = ConversationConfig(
            systemMessage: Message(systemInstruction, role: .system),
            samplerConfig: sampler
        )
        let conversation = try await engine.createConversation(with: conversationConfig)
        activeConversation = conversation
        defer { activeConversation = nil }
        let response = try await conversation.sendMessage(Message(prompt))
        let text = response.toString.trimmingCharacters(in: .whitespacesAndNewlines)

        guard text.isEmpty == false else {
            throw CrisisSentinelLiteRTLMRuntimeError.engineUnavailable
        }
        return text
    }

    func cancelGeneration() {
        try? activeConversation?.cancel()
    }

    private func ensureEngine(
        modelURL: URL,
        cacheURL: URL,
        maxTokens: Int
    ) async throws -> Engine {
        let path = modelURL.path
        if let engine, modelPath == path, engineMaxTokens == maxTokens, await engine.isInitialized() {
            return engine
        }

        engine = nil
        modelPath = nil
        engineMaxTokens = nil

        let created: Engine
        if let gpuEngine = try await makeInitializedEngine(
            modelPath: path,
            cachePath: cacheURL.path,
            maxTokens: maxTokens,
            backend: .gpu
        ) {
            created = gpuEngine
        } else if let cpuEngine = try await makeInitializedEngine(
            modelPath: path,
            cachePath: cacheURL.path,
            maxTokens: maxTokens,
            backend: .cpu(threadCount: 4)
        ) {
            created = cpuEngine
        } else {
            throw CrisisSentinelLiteRTLMRuntimeError.engineUnavailable
        }

        engine = created
        modelPath = path
        engineMaxTokens = maxTokens
        return created
    }

    private func makeInitializedEngine(
        modelPath: String,
        cachePath: String,
        maxTokens: Int,
        backend: Backend
    ) async throws -> Engine? {
        let config = try EngineConfig(
            modelPath: modelPath,
            backend: backend,
            visionBackend: nil,
            audioBackend: nil,
            maxNumTokens: maxTokens,
            cacheDir: cachePath
        )
        let engine = Engine(engineConfig: config)
        do {
            try await engine.initialize()
            return engine
        } catch {
            if backend == .gpu {
                return nil
            }
            throw error
        }
    }
}
