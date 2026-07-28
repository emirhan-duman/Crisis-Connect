//
//  CrisisSentinelViewModel.swift
//  Crisis Connect
//

import Combine
import FirebaseAuth
import Foundation
import Network

/// Which engine answers: the web dashboard's cloud chat API or the on-device LiteRT model.
enum CrisisSentinelEngine: String {
    case online
    case edge
}

@MainActor
final class CrisisSentinelViewModel: ObservableObject {
    @Published var prompt: String = ""
    @Published var mode: CrisisSentinelUserMode {
        didSet {
            conversationStore.saveDefaultMode(mode)
        }
    }
    @Published private(set) var response: CrisisSentinelResponse?
    @Published private(set) var conversations: [CrisisSentinelConversationSummary] = []
    @Published private(set) var activeConversation: CrisisSentinelConversation?
    @Published private(set) var modelStatus: CrisisSentinelModelStatus
    @Published private(set) var isGenerating = false
    @Published private(set) var isResolvingModelRelease = false
    @Published private(set) var isDownloadingModel = false
    @Published private(set) var downloadProgress: Double = 0
    @Published private(set) var isProcessingInput = false
    @Published private(set) var isDictating = false
    @Published private(set) var availableModes: [CrisisSentinelUserMode] = [.publicUser]
    @Published private(set) var certificateRole: String?
    @Published var messageKey: String?

    // Online (cloud) engine — Android parity: allowed for admin/fieldteam certificate roles with a
    // real (non-anonymous) sign-in; available when also online. The server re-enforces the gate.
    @Published private(set) var isOnlineEngineAllowed = false
    @Published private(set) var hasInternet = false
    @Published var selectedEngine: CrisisSentinelEngine {
        didSet { UserDefaults.standard.set(selectedEngine.rawValue, forKey: Self.engineDefaultsKey) }
    }
    /// Partial online reply while it streams; nil outside an online generation.
    @Published private(set) var streamingReply: String?

    // Provider/model pinning (S2): the panel's configured providers, 15-min cached, and the
    // user's pinned choice (nil = server default) — sent as providerId+model on every stream.
    @Published private(set) var onlineProviders: [CrisisSentinelOnlineProvider] = []
    @Published private(set) var isLoadingProviders = false
    @Published private(set) var providersLoadFailed = false
    @Published var selectedOnlineModel: CrisisSentinelOnlineModelChoice? {
        didSet {
            let defaults = UserDefaults.standard
            if let choice = selectedOnlineModel, let data = try? JSONEncoder().encode(choice) {
                defaults.set(data, forKey: Self.onlineModelDefaultsKey)
            } else {
                defaults.removeObject(forKey: Self.onlineModelDefaultsKey)
            }
        }
    }

    private static let engineDefaultsKey = "crisisSentinel.selectedEngine"
    private static let onlineModelDefaultsKey = "crisisSentinel.onlineModelChoice"
    private static let onlineEngineRoles: Set<String> = ["admin", "fieldteam"]
    private static let providersCacheTtl: TimeInterval = 15 * 60
    private var providersFetchedAt: Date?
    private let onlineClient = CrisisSentinelOnlineClient()
    private let pathMonitor = NWPathMonitor()

    // Cloud chat sync (S3): online chats live in Firestore alongside the web dashboard.
    private let cloudStore = CrisisSentinelCloudChatStore.shared
    private var cloudSummaries: [CrisisSentinelConversationSummary] = []
    private var cloudSummariesTask: Task<Void, Never>?
    private var cloudMessagesTask: Task<Void, Never>?
    // "This chat is saved on this device only" is surfaced once per view-model lifetime, then the
    // quieter "sync failed" notice takes over (Android parity: cloudUnavailableNoticeShown).
    private var cloudUnavailableNoticeShown = false

    /// Merge local + cloud summaries, newest first — the unified conversation list.
    private func rebuildConversations() {
        conversations = (conversationStore.summaries() + cloudSummaries)
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    /// Subscribe to the panel's cloud chats (idempotent). Restarted whenever online access changes.
    private func startCloudSummaries() {
        cloudSummariesTask?.cancel()
        guard isOnlineEngineAllowed else {
            cloudSummaries = []
            rebuildConversations()
            return
        }
        cloudSummariesTask = Task { [weak self] in
            guard let self else { return }
            for await summaries in await self.cloudStore.summariesStream() {
                self.cloudSummaries = summaries.map {
                    CrisisSentinelConversationSummary(
                        id: CrisisSentinelConversationIds.cloud($0.docId),
                        title: $0.title,
                        mode: .fieldTeam,
                        updatedAt: $0.updatedAt,
                        lastMessagePreview: nil,
                        isDraft: false,
                        isCloud: true
                    )
                }
                self.rebuildConversations()
            }
        }
    }

    /// Loads the panel's providers for the model sheet; 15-minute cache like Android.
    func refreshOnlineProviders(force: Bool = false) {
        guard onlineEngineAvailable, isLoadingProviders == false else { return }
        if !force, let at = providersFetchedAt,
           Date().timeIntervalSince(at) < Self.providersCacheTtl, !onlineProviders.isEmpty {
            return
        }
        isLoadingProviders = true
        providersLoadFailed = false
        Task { [weak self] in
            guard let self else { return }
            do {
                let providers = try await self.onlineClient.fetchProviders()
                self.onlineProviders = providers
                self.providersFetchedAt = Date()
                // A pinned model whose provider/model vanished server-side falls back to default.
                if let choice = self.selectedOnlineModel,
                   !providers.contains(where: { provider in
                       provider.id == choice.providerId && provider.models.contains { $0.id == choice.modelId }
                   }) {
                    self.selectedOnlineModel = nil
                }
            } catch {
                self.providersLoadFailed = true
            }
            self.isLoadingProviders = false
        }
    }

    /// The chip subtitle for a pinned model (nil when using the server default).
    var selectedOnlineModelLabel: String? {
        guard let choice = selectedOnlineModel else { return nil }
        let provider = onlineProviders.first { $0.id == choice.providerId }
        return provider?.models.first { $0.id == choice.modelId }?.label ?? choice.modelId
    }

    var onlineEngineAvailable: Bool { isOnlineEngineAllowed && hasInternet }
    var edgeEngineAvailable: Bool { modelStatus.isReady }

    // The on-device model is ~3.6GB; devices under ~4GB RAM run first-load + generation slowly, so
    // warn before running it (Android parity: shouldShowModelPerformanceWarning, <4GB RAM).
    private static let minRecommendedRamBytes: UInt64 = 4_000_000_000
    var showsModelPerformanceWarning: Bool {
        ProcessInfo.processInfo.physicalMemory < Self.minRecommendedRamBytes
    }
    /// Either engine can answer → the chat surface is usable (an online-allowed user can chat
    /// without ever downloading the on-device model).
    var canChat: Bool { onlineEngineAvailable || edgeEngineAvailable }

    /// The engine a send actually uses: the selection when available, else whichever side is up
    /// (Android's auto-fallback), else nil (can't chat).
    var activeEngine: CrisisSentinelEngine? {
        if selectedEngine == .online && onlineEngineAvailable { return .online }
        if selectedEngine == .edge && edgeEngineAvailable { return .edge }
        if onlineEngineAvailable { return .online }
        if edgeEngineAvailable { return .edge }
        return nil
    }

    private let store: CrisisSentinelModelFileStore
    private let downloader: CrisisSentinelModelDownloader
    private let manifestClient: CrisisSentinelModelManifestClient
    private let manifestCache: CrisisSentinelModelManifestCache
    private let conversationStore: CrisisSentinelConversationStore
    private let modelRuntime: CrisisSentinelLiteRTLMRuntime
    private let engine: CrisisSentinelOfflineEngine
    private let speechDictation: CrisisSentinelSpeechDictation
    private var currentRelease: CrisisSentinelModelRelease
    private var generationTask: Task<Void, Never>?
    private var generationTimeoutTask: Task<Void, Never>?
    private var modelInstallObserver: NSObjectProtocol?
    private var roleChangeObserver: NSObjectProtocol?
    // Gemma 3n E2B (~3.6GB) is far larger than the old 1B model; first-load + generation can run
    // well past a minute on mid/low-end devices, so 90s cut answers off prematurely.
    private let generationTimeoutNanoseconds: UInt64 = 200_000_000_000

    init(
        store: CrisisSentinelModelFileStore = CrisisSentinelModelFileStore(),
        downloader: CrisisSentinelModelDownloader = CrisisSentinelModelDownloader(),
        manifestClient: CrisisSentinelModelManifestClient = CrisisSentinelModelManifestClient(),
        manifestCache: CrisisSentinelModelManifestCache = CrisisSentinelModelManifestCache(),
        conversationStore: CrisisSentinelConversationStore = CrisisSentinelConversationStore(),
        speechDictation: CrisisSentinelSpeechDictation? = nil,
        engine: CrisisSentinelOfflineEngine? = nil
    ) {
        self.store = store
        self.downloader = downloader
        self.manifestClient = manifestClient
        self.manifestCache = manifestCache
        self.conversationStore = conversationStore
        self.speechDictation = speechDictation ?? CrisisSentinelSpeechDictation()
        let release = manifestCache.load() ?? .defaultRelease
        self.currentRelease = release
        self.modelStatus = store.status(for: release, validateChecksum: false)
        self.mode = conversationStore.defaultMode()
        // Local-only here (cloudSummaries is empty and self isn't fully initialized yet); the
        // cloud subscription in refreshRoleAccess merges the shared chats in shortly.
        self.conversations = conversationStore.summaries()
        self.selectedEngine = UserDefaults.standard.string(forKey: Self.engineDefaultsKey)
            .flatMap(CrisisSentinelEngine.init(rawValue:)) ?? .edge
        self.selectedOnlineModel = UserDefaults.standard.data(forKey: Self.onlineModelDefaultsKey)
            .flatMap { try? JSONDecoder().decode(CrisisSentinelOnlineModelChoice.self, from: $0) }
        let modelRuntime = CrisisSentinelLiteRTLMRuntime(store: store, release: release)
        self.modelRuntime = modelRuntime
        // Failure diagnostics (Android parity: Log.w in the Android view model) — a silent fall
        // back to rules output made "the model gave a weird answer" bugs undiagnosable.
        self.engine = engine ?? CrisisSentinelOfflineEngine(
            modelRuntime: modelRuntime,
            onModelFailure: { error in
                NSLog("Crisis Sentinel local model generation failed: %@", String(describing: error))
            },
            onModelRejected: { preview in
                NSLog("Crisis Sentinel local model output rejected. preview=%@", String(preview.prefix(160)))
            }
        )

        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.hasInternet = online }
        }
        pathMonitor.start(queue: DispatchQueue(label: "crisis.sentinel.reachability"))

        Task { [weak self] in
            await self?.refreshRoleAccess()
            await self?.resolveLatestModelRelease(
                shouldDownloadIfNeeded: false,
                forceDownload: false,
                showUserMessage: false
            )
        }

        // A background download can finish while this view model is alive but idle (e.g. the model
        // was committed after the app was suspended), so refresh status when that happens.
        modelInstallObserver = NotificationCenter.default.addObserver(
            forName: .crisisSentinelModelDidInstall,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refreshModelStatus()
            }
        }

        // A rescue certificate can be provisioned (or revoked) while this view model is alive —
        // e.g. auto-provisioning finishing right after sign-in. Without this, a field-team user
        // who opened Sentinel first stayed locked to "download the model" until an app restart.
        roleChangeObserver = NotificationCenter.default.addObserver(
            forName: .rescueRoleDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                await self?.refreshRoleAccess()
            }
        }
    }

    deinit {
        if let modelInstallObserver {
            NotificationCenter.default.removeObserver(modelInstallObserver)
        }
        if let roleChangeObserver {
            NotificationCenter.default.removeObserver(roleChangeObserver)
        }
    }

    func refreshModelStatus() {
        modelStatus = store.status(for: currentRelease, validateChecksum: false)
    }

    func refreshConversations() {
        rebuildConversations()
    }

    /// Map points a card/reply wants shown. The offline-map hop is wired in S5; published here so
    /// the view can present it.
    @Published var pendingMapPoints: [CrisisSentinelMapPoint] = []
    func showPointsOnMap(_ points: [CrisisSentinelMapPoint]) {
        guard !points.isEmpty else { return }
        pendingMapPoints = points
    }

    func refreshRoleAccess() async {
        let certRole = await SecurityRepository.shared
            .getUsableStoredCertificateRole(allowExpired: true)?
            .lowercased()
        // The online engine's REAL authorization is the server's Firestore role check (see
        // CrisisSentinelOnlineClient's header: "the server enforces the same gate from the Firestore
        // user document") — NOT the device certificate. A field-team user who is signed in but whose
        // rescue certificate hasn't been provisioned yet (or failed to) is still allowed by the
        // server, so fall back to the cached rescue role (mirrors users/{uid}.role, populated by
        // FirebaseBootstrapper). This lets field team chat via the CLOUD without being forced to
        // download the on-device model, matching the authorized-user experience on Android.
        let cachedRole = SecureLocalStore.shared.loadRole()?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let role = certRole ?? (cachedRole?.isEmpty == false ? cachedRole : nil)
        let modes = Self.availableModes(for: role)
        let preferredMode = Self.preferredMode(for: role)
        // Mode is derived purely from the role (no manual default-mode picker):
        // field team → field, authorized → coordinator, everyone else → public.
        availableModes = modes
        certificateRole = role
        mode = modes.contains(preferredMode) ? preferredMode : .publicUser
        // The FirebaseAuth check is deliberately independent of the certificate: a stored cert can
        // outlive the sign-in session, but the online engine needs a real (non-anonymous) ID token.
        let signedIn = Auth.auth().currentUser.map { !$0.isAnonymous } ?? false
        isOnlineEngineAllowed = role.map(Self.onlineEngineRoles.contains) == true && signedIn
        startCloudSummaries()
    }

    func openConversation(id: String?) {
        cloudMessagesTask?.cancel()
        cloudMessagesTask = nil
        guard let id else {
            activeConversation = nil
            rebuildConversations()
            prompt = ""
            response = nil
            return
        }
        if CrisisSentinelConversationIds.isCloud(id) {
            openCloudConversation(docId: CrisisSentinelConversationIds.cloudDocId(id))
            rebuildConversations()
            prompt = ""
            response = nil
            return
        }
        let conversation = conversationStore.load(id: id)
        activeConversation = conversation
        rebuildConversations()
        prompt = conversation?.draftText ?? ""
        response = nil
    }

    /// Listens to a cloud chat's messages and reflects them into `activeConversation`.
    private func openCloudConversation(docId: String) {
        let id = CrisisSentinelConversationIds.cloud(docId)
        activeConversation = CrisisSentinelConversation(
            id: id, title: "", mode: .fieldTeam,
            createdAt: Date(), updatedAt: Date(), messages: [], draftText: nil
        )
        cloudMessagesTask = Task { [weak self] in
            guard let self else { return }
            for await update in await self.cloudStore.messagesStream(docId: docId) {
                guard self.activeConversation?.id == id else { continue }
                if !update.chatExists {
                    // Deleted on the web side while open.
                    self.activeConversation = nil
                    self.cloudSummaries.removeAll { $0.id == id }
                    self.rebuildConversations()
                    continue
                }
                // Firestore's local cache echoes writes immediately, so the listener is the single
                // source of truth — no optimistic copies to reconcile.
                self.activeConversation?.messages = update.messages
            }
        }
    }

    @discardableResult
    func saveDraft(conversationID: String?) -> String? {
        // Cloud conversations live in Firestore — never mirror a draft into the local store (it
        // would spawn a phantom local conversation). A cloud chat with an active online engine
        // also shouldn't fall into a fresh local draft.
        if let conversationID, CrisisSentinelConversationIds.isCloud(conversationID) {
            return conversationID
        }
        if activeEngine == .online && conversationID == nil {
            return nil
        }
        let cleanPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanPrompt.isEmpty == false || conversationID != nil else { return nil }
        activeConversation = conversationStore.saveDraft(id: conversationID, text: cleanPrompt, mode: mode)
        rebuildConversations()
        return activeConversation?.id
    }

    func importImageDataForOCR(_ data: Data) {
        processConnectorInput(blockTitle: "Fotoğraf OCR") {
            try await CrisisSentinelInputProcessor.extractText(fromImageData: data)
        }
    }

    func importTextFile(_ url: URL) {
        processConnectorInput(blockTitle: "Dosya metni") {
            try CrisisSentinelInputProcessor.extractText(fromFileURL: url)
        }
    }

    func toggleDictation() {
        if isDictating {
            let transcript = speechDictation.latestTranscript
            speechDictation.stop()
            isDictating = false
            appendVoiceDictation(transcript)
            return
        }

        Task {
            guard await speechDictation.requestAuthorization() else {
                messageKey = "CRISIS_SENTINEL_INPUT_UNAVAILABLE"
                return
            }
            do {
                try speechDictation.start { [weak self] transcript, isFinal in
                    guard let self else { return }
                    if isFinal {
                        self.isDictating = false
                        self.appendVoiceDictation(transcript)
                    }
                }
                isDictating = true
                messageKey = "CRISIS_SENTINEL_DICTATION_STARTED"
            } catch {
                isDictating = false
                messageKey = "CRISIS_SENTINEL_INPUT_UNAVAILABLE"
            }
        }
    }

    private func appendVoiceDictation(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.isEmpty == false else {
            messageKey = "CRISIS_SENTINEL_INPUT_EMPTY"
            return
        }
        appendInputBlock(
            blockTitle: "Sesli dikte",
            text: clean,
            messageKey: "CRISIS_SENTINEL_INPUT_ADDED"
        )
    }

    private func processConnectorInput(
        blockTitle: String,
        loader: @escaping () async throws -> String
    ) {
        guard isProcessingInput == false else { return }
        isProcessingInput = true
        messageKey = nil
        Task {
            do {
                let raw = try await loader()
                let extracted = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                guard extracted.isEmpty == false else {
                    isProcessingInput = false
                    messageKey = "CRISIS_SENTINEL_INPUT_EMPTY"
                    return
                }
                appendInputBlock(
                    blockTitle: blockTitle,
                    text: extracted,
                    messageKey: "CRISIS_SENTINEL_INPUT_ADDED"
                )
                isProcessingInput = false
            } catch {
                isProcessingInput = false
                messageKey = "CRISIS_SENTINEL_INPUT_FAILED"
            }
        }
    }

    private func appendInputBlock(
        blockTitle: String,
        text: String,
        messageKey: String
    ) {
        let existing = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let block = "[\(blockTitle)]\n\(text.trimmingCharacters(in: .whitespacesAndNewlines))"
        prompt = existing.isEmpty ? block : "\(existing)\n\n\(block)"
        self.messageKey = messageKey
    }

    func submit() {
        let cleanPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanPrompt.isEmpty == false else {
            messageKey = "CRISIS_SENTINEL_PROMPT_EMPTY"
            return
        }
        guard isGenerating == false else { return }

        AppAnalytics.sentinelPrompt(engine: "edge")
        isGenerating = true
        messageKey = nil
        let request = CrisisSentinelRequest(
            prompt: cleanPrompt,
            mode: mode,
            locale: CrisisSentinelText.responseLocale(for: cleanPrompt)
        )

        startGenerationTimeout()
        generationTask = Task { [weak self] in
            guard let self else { return }
            let nextResponse = await engine.respondWithModel(to: request)
            guard Task.isCancelled == false else { return }
            response = nextResponse
            finishGeneration()
        }
    }

    @discardableResult
    func submitChatMessage(conversationID: String?) -> String? {
        let cleanPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanPrompt.isEmpty == false else {
            messageKey = "CRISIS_SENTINEL_PROMPT_EMPTY"
            return conversationID
        }
        guard isGenerating == false else { return conversationID }

        // A cloud-homed conversation stays in Firestore no matter which engine answers — the
        // isCloud check comes BEFORE the engine check (Android parity), so an Edge send into an open
        // cloud chat writes the reply back to the SAME cloud thread instead of forking a fresh local
        // conversation.
        if let conversationID, CrisisSentinelConversationIds.isCloud(conversationID) {
            return submitCloudMessage(conversationID: conversationID, prompt: cleanPrompt)
        }
        // A fresh chat started on the Online engine is cloud-homed (Firestore, shared with web).
        if activeEngine == .online {
            return submitCloudMessage(conversationID: conversationID, prompt: cleanPrompt)
        }

        AppAnalytics.sentinelPrompt(engine: "edge")
        let targetConversationID: String
        if let conversationID, conversationStore.load(id: conversationID) != nil {
            targetConversationID = conversationID
        } else {
            targetConversationID = conversationStore.create(mode: mode).id
        }

        let conversationAfterUserMessage = conversationStore.appendUserMessage(
            id: targetConversationID,
            text: cleanPrompt
        ) ?? {
            let conversation = conversationStore.create(mode: mode)
            return conversationStore.appendUserMessage(id: conversation.id, text: cleanPrompt) ?? conversation
        }()

        activeConversation = conversationAfterUserMessage
        rebuildConversations()
        prompt = ""
        isGenerating = true
        messageKey = nil
        refreshModelStatus()

        runAssistantGeneration(conversation: conversationAfterUserMessage, cleanPrompt: cleanPrompt)

        return conversationAfterUserMessage.id
    }

    /// Re-runs the last user prompt in the active conversation, replacing the trailing assistant
    /// reply in place.
    func regenerateLastResponse() {
        guard isGenerating == false else { return }
        guard let conversation = activeConversation else { return }
        guard let lastPrompt = conversation.messages.last(where: { $0.role == .user })?.text,
              lastPrompt.isEmpty == false else {
            return
        }
        // Cloud chats: drop the superseded trailing assistant turn(s) from Firestore too, so the
        // regenerated reply replaces them instead of piling up on the web dashboard / other devices
        // (Android's regenerateLastResponse does the same). The local message id == the cloud doc id.
        if CrisisSentinelConversationIds.isCloud(conversation.id) {
            let trailingAssistantIds = conversation.messages
                .reversed()
                .prefix { $0.role == .assistant }
                .map { $0.id }
            if !trailingAssistantIds.isEmpty {
                let docId = CrisisSentinelConversationIds.cloudDocId(conversation.id)
                Task { await cloudStore.deleteMessages(docId: docId, messageIds: trailingAssistantIds) }
            }
        }
        let trimmedConversation = conversationStore.removeTrailingAssistantMessages(id: conversation.id)
            ?? conversation
        activeConversation = trimmedConversation
        rebuildConversations()
        isGenerating = true
        messageKey = nil
        refreshModelStatus()
        runAssistantGeneration(conversation: trimmedConversation, cleanPrompt: lastPrompt)
    }

    /// A generated turn (online stream or on-device model), ready to persist to Firestore or the
    /// local store. Mirrors Android's `GenerationOutcome`.
    private struct CrisisSentinelGeneratedTurn {
        let response: CrisisSentinelResponse
        var modelName: String? = nil
        var cardJson: String? = nil
        var mapPoints: [CrisisSentinelMapPoint] = []
        var groundingMetadataJson: String? = nil
        var generationDurationMillis: Int64? = nil
    }

    /// Cloud-homed turn: reserve seqs, create the chat on the first message, write the user turn,
    /// generate with whichever engine is active (streaming the reply into the thinking bubble for
    /// online), then write the bot turn — all to Firestore so the same conversation shows on the web
    /// dashboard. An Edge reply is labeled with the edge model name (Android parity). If Firestore
    /// can't be reached (no panel / write failure) the turn transparently continues on-device rather
    /// than dropping the user's message. Returns the conversation id for the composer (nil for a
    /// brand-new chat; the view syncs from activeConversation.id).
    private func submitCloudMessage(conversationID: String?, prompt: String) -> String? {
        let engineForGeneration = activeEngine ?? .edge
        AppAnalytics.sentinelPrompt(engine: engineForGeneration == .online ? "online" : "edge")
        let existingDocId = conversationID
            .flatMap { CrisisSentinelConversationIds.isCloud($0) ? CrisisSentinelConversationIds.cloudDocId($0) : nil }
        // History BEFORE this turn (empty for a new chat).
        let priorMessages: [CrisisSentinelChatMessage] = existingDocId != nil
            ? (activeConversation?.messages ?? [])
            : []
        // In the online API's sender vocabulary. Card/map-only turns have blank text; the wire
        // format wants text turns, so drop them (Android parity).
        var apiHistory: [CrisisSentinelOnlineMessage] = priorMessages
            .filter { $0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false }
            .map { message in
                CrisisSentinelOnlineMessage(
                    sender: message.role == .user
                        ? CrisisSentinelOnlineMessage.senderUser
                        : CrisisSentinelOnlineMessage.senderBot,
                    text: message.text
                )
            }
        apiHistory.append(CrisisSentinelOnlineMessage(
            sender: CrisisSentinelOnlineMessage.senderUser, text: prompt
        ))
        let edgeRequest = makeEdgeRequest(mode: mode, priorMessages: priorMessages, prompt: prompt)

        self.prompt = ""
        isGenerating = true
        messageKey = nil
        streamingReply = engineForGeneration == .online ? "" : nil
        startGenerationTimeout()

        generationTask = Task { [weak self] in
            guard let self else { return }
            let seqs = await self.cloudStore.reserveTurnSeqs()

            // Resolve the chat doc — create it on the first message.
            let docId: String
            if let existingDocId {
                docId = existingDocId
            } else {
                do {
                    docId = try await self.cloudStore.createChat(firstUserText: prompt)
                } catch {
                    // Cloud sync isn't available (no panel) or the write failed — DON'T drop the
                    // typed message: continue this turn on-device and say so once (Android parity:
                    // transparent local fallback instead of a misleading "not configured" error).
                    await self.continueLocallyAfterCloudFailure(
                        engine: engineForGeneration,
                        apiHistory: apiHistory,
                        edgeRequest: edgeRequest,
                        prompt: prompt,
                        error: error
                    )
                    return
                }
                self.openCloudConversation(docId: docId)
            }

            // Write the user turn (the cache-backed listener echoes it immediately).
            await self.cloudStore.appendUserMessage(docId: docId, text: prompt, seq: seqs.userSeq)

            let startedAt = Date()
            let turn: CrisisSentinelGeneratedTurn
            do {
                turn = try await self.generateTurn(
                    engine: engineForGeneration,
                    apiHistory: apiHistory,
                    edgeRequest: edgeRequest,
                    startedAt: startedAt
                )
            } catch let error as CrisisSentinelOnlineError {
                if Task.isCancelled == false { self.messageKey = self.onlineErrorKey(error.kind) }
                self.finishGeneration()
                return
            } catch {
                if Task.isCancelled == false { self.messageKey = self.onlineErrorKey(.network) }
                self.finishGeneration()
                return
            }
            guard Task.isCancelled == false else { return }
            await self.cloudStore.appendBotMessage(
                docId: docId,
                messageId: UUID().uuidString,
                text: turn.response.answer,
                modelName: turn.modelName,
                cardJson: turn.cardJson,
                mapPoints: turn.mapPoints,
                groundingMetadataJson: turn.groundingMetadataJson,
                seq: seqs.assistantSeq
            )
            self.finishGeneration()
        }

        return existingDocId.map { CrisisSentinelConversationIds.cloud($0) } ?? conversationID
    }

    /// Runs the active engine and returns the reply. Online streams into the thinking bubble; Edge
    /// runs the on-device model and is labeled with the edge model name so the web side can tell the
    /// two apart in a synced chat.
    private func generateTurn(
        engine engineForGeneration: CrisisSentinelEngine,
        apiHistory: [CrisisSentinelOnlineMessage],
        edgeRequest: CrisisSentinelRequest,
        startedAt: Date
    ) async throws -> CrisisSentinelGeneratedTurn {
        switch engineForGeneration {
        case .online:
            let result = try await onlineClient.streamChat(
                messages: apiHistory,
                modelChoice: selectedOnlineModel
            ) { accumulated in
                Task { @MainActor [weak self] in
                    guard let self, self.isGenerating else { return }
                    self.streamingReply = accumulated
                }
            }
            var answer = result.text
            if !result.unsupportedTools.isEmpty && answer.count < 40 {
                if !answer.isEmpty { answer += "\n\n" }
                answer += NSLocalizedString("CRISIS_SENTINEL_ONLINE_TOOL_ON_WEB", comment: "")
            }
            let response = CrisisSentinelResponse(
                answer: answer,
                mode: edgeRequest.mode,
                source: .onlineModel,
                citations: [],
                incidentDraft: nil,
                followUpQuestions: [],
                safetyNotices: [],
                confidence: 1
            )
            return CrisisSentinelGeneratedTurn(
                response: response,
                modelName: result.modelName,
                cardJson: result.cardJson,
                mapPoints: result.mapPoints,
                groundingMetadataJson: result.groundingMetadataJson,
                generationDurationMillis: Int64(Date().timeIntervalSince(startedAt) * 1_000)
            )
        case .edge:
            let response = await engine.respondWithModel(to: edgeRequest)
            return CrisisSentinelGeneratedTurn(
                response: response,
                modelName: CrisisSentinelCloudChatStore.edgeModelName,
                generationDurationMillis: Int64(Date().timeIntervalSince(startedAt) * 1_000)
            )
        }
    }

    /// Firestore was unreachable for a cloud-homed turn — re-home it in the local store so the
    /// user's typed message and the generated answer survive, and surface a one-time notice. Mirrors
    /// Android's transparent local fallback (submitNewCloudChat).
    private func continueLocallyAfterCloudFailure(
        engine engineForGeneration: CrisisSentinelEngine,
        apiHistory: [CrisisSentinelOnlineMessage],
        edgeRequest: CrisisSentinelRequest,
        prompt: String,
        error: Error
    ) async {
        // This turn now lives on-device; stop mirroring a (non-existent) cloud chat.
        cloudMessagesTask?.cancel()
        cloudMessagesTask = nil
        let created = conversationStore.create(mode: mode)
        let withUser = conversationStore.appendUserMessage(id: created.id, text: prompt) ?? created
        activeConversation = withUser
        rebuildConversations()
        messageKey = cloudFallbackNoticeKey(for: error)

        let startedAt = Date()
        let turn: CrisisSentinelGeneratedTurn
        do {
            turn = try await generateTurn(
                engine: engineForGeneration,
                apiHistory: apiHistory,
                edgeRequest: edgeRequest,
                startedAt: startedAt
            )
        } catch let onlineError as CrisisSentinelOnlineError {
            if Task.isCancelled == false { messageKey = onlineErrorKey(onlineError.kind) }
            finishGeneration()
            return
        } catch {
            if Task.isCancelled == false { messageKey = onlineErrorKey(.network) }
            finishGeneration()
            return
        }
        guard Task.isCancelled == false else { return }
        activeConversation = conversationStore.appendAssistantResponse(
            id: withUser.id,
            response: turn.response,
            generationDurationMillis: turn.generationDurationMillis
        ) ?? conversationStore.load(id: withUser.id)
        response = turn.response
        rebuildConversations()
        finishGeneration()
    }

    /// Which one-time "continuing on this device" notice to show when a cloud write can't land. A
    /// missing panel / permission means the chat simply won't sync ("saved on this device only",
    /// shown once); any other write failure is an unexpected sync error.
    private func cloudFallbackNoticeKey(for error: Error) -> String {
        if let onlineError = error as? CrisisSentinelOnlineError,
           onlineError.kind == .notConfigured || onlineError.kind == .unauthorized {
            if cloudUnavailableNoticeShown {
                return "CRISIS_SENTINEL_CLOUD_SYNC_ERROR_LOCAL_FALLBACK"
            }
            cloudUnavailableNoticeShown = true
            return "CRISIS_SENTINEL_CLOUD_SYNC_UNAVAILABLE"
        }
        return "CRISIS_SENTINEL_CLOUD_SYNC_ERROR_LOCAL_FALLBACK"
    }

    /// Builds the on-device model request from a conversation's prior turns (Android parity:
    /// last 8 turns as context, last 3 user turns for locale detection).
    private func makeEdgeRequest(
        mode: CrisisSentinelUserMode,
        priorMessages: [CrisisSentinelChatMessage],
        prompt: String
    ) -> CrisisSentinelRequest {
        // Real conversational context: include the assistant's own replies (not just the user
        // prompts) so follow-ups like "devam et" actually have something to follow.
        let recentMessages = priorMessages
            .suffix(8)
            .map { message -> String in
                let speaker = message.role == .user ? "User" : "Assistant"
                let compact = message.text
                    .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .prefix(200)
                return "\(speaker): \(compact)"
            }
        let recentUserMessages = priorMessages
            .filter { $0.role == .user }
            .suffix(3)
            .map(\.text)
        return CrisisSentinelRequest(
            prompt: prompt,
            mode: mode,
            locale: CrisisSentinelText.responseLocale(
                for: prompt,
                recentMessages: Array(recentUserMessages)
            ),
            recentMessages: Array(recentMessages)
        )
    }

    private func runAssistantGeneration(
        conversation: CrisisSentinelConversation,
        cleanPrompt: String
    ) {
        let request = makeEdgeRequest(
            mode: conversation.mode,
            priorMessages: Array(conversation.messages.dropLast()),
            prompt: cleanPrompt
        )

        startGenerationTimeout()
        generationTask = Task { [weak self] in
            guard let self else { return }
            let startedAt = Date()
            let nextResponse = await engine.respondWithModel(to: request)
            guard Task.isCancelled == false else { return }
            let generationDurationMillis = Int64(Date().timeIntervalSince(startedAt) * 1_000)
            response = nextResponse
            activeConversation = conversationStore.appendAssistantResponse(
                id: conversation.id,
                response: nextResponse,
                generationDurationMillis: generationDurationMillis
            ) ?? conversationStore.load(id: conversation.id)
            rebuildConversations()
            finishGeneration()
        }
    }

    func deleteConversation(id: String) {
        if CrisisSentinelConversationIds.isCloud(id) {
            let docId = CrisisSentinelConversationIds.cloudDocId(id)
            cloudSummaries.removeAll { $0.id == id }
            Task { try? await cloudStore.deleteChat(docId: docId) }
        } else {
            conversationStore.deleteConversation(id: id)
        }
        if activeConversation?.id == id {
            cloudMessagesTask?.cancel()
            cloudMessagesTask = nil
            activeConversation = nil
            prompt = ""
            response = nil
        }
        rebuildConversations()
    }

    func cancelGeneration() {
        guard isGenerating else { return }
        modelRuntime.cancelGeneration()
        generationTask?.cancel()
        generationTimeoutTask?.cancel()
        generationTask = nil
        generationTimeoutTask = nil
        isGenerating = false
        streamingReply = nil
        messageKey = "CRISIS_SENTINEL_GENERATION_CANCELLED"
        refreshModelStatus()
    }

    private func startGenerationTimeout() {
        generationTask?.cancel()
        generationTimeoutTask?.cancel()
        generationTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: self?.generationTimeoutNanoseconds ?? 200_000_000_000)
            guard Task.isCancelled == false else { return }
            await MainActor.run {
                self?.timeoutGeneration()
            }
        }
    }

    private func onlineErrorKey(_ kind: CrisisSentinelOnlineError.Kind) -> String {
        switch kind {
        case .unauthorized: return "CRISIS_SENTINEL_ONLINE_ERROR_UNAUTHORIZED"
        case .forbidden: return "CRISIS_SENTINEL_ONLINE_ERROR_FORBIDDEN"
        case .quota: return "CRISIS_SENTINEL_ONLINE_ERROR_QUOTA"
        // A network/server failure is recoverable by switching to the on-device model, so nudge the
        // user toward it when it's available (Android parity: online_error_network_edge_hint).
        case .network:
            return edgeEngineAvailable
                ? "CRISIS_SENTINEL_ONLINE_ERROR_NETWORK_EDGE_HINT"
                : "CRISIS_SENTINEL_ONLINE_ERROR_NETWORK"
        case .server:
            return edgeEngineAvailable
                ? "CRISIS_SENTINEL_ONLINE_ERROR_NETWORK_EDGE_HINT"
                : "CRISIS_SENTINEL_ONLINE_ERROR_SERVER"
        case .notConfigured: return "CRISIS_SENTINEL_ONLINE_ERROR_NOT_CONFIGURED"
        }
    }

    private func finishGeneration() {
        generationTimeoutTask?.cancel()
        generationTask = nil
        generationTimeoutTask = nil
        isGenerating = false
        streamingReply = nil
        refreshModelStatus()
    }

    private func timeoutGeneration() {
        guard isGenerating else { return }
        modelRuntime.cancelGeneration()
        generationTask?.cancel()
        generationTask = nil
        generationTimeoutTask = nil
        isGenerating = false
        messageKey = "CRISIS_SENTINEL_GENERATION_TIMEOUT"
        refreshModelStatus()
    }

    func requestModelDownload() {
        Task {
            await resolveLatestModelRelease(
                shouldDownloadIfNeeded: true,
                forceDownload: true,
                showUserMessage: true
            )
        }
    }

    func deleteDownloadedModel() {
        let didDelete = store.deleteModel(release: currentRelease)
        modelRuntime.cancelGeneration()
        refreshModelStatus()
        messageKey = didDelete ? "CRISIS_SENTINEL_MODEL_DELETE_DONE" : "CRISIS_SENTINEL_MODEL_DELETE_FAILED"
        if didDelete {
            NotificationCenter.default.post(name: .crisisSentinelModelDidInstall, object: nil)
        }
    }

    private func resolveLatestModelRelease(
        shouldDownloadIfNeeded: Bool,
        forceDownload: Bool,
        showUserMessage: Bool
    ) async {
        guard isResolvingModelRelease == false, isDownloadingModel == false else { return }

        do {
            isResolvingModelRelease = true
            if showUserMessage {
                messageKey = nil
            }
            guard let release = try await manifestClient.fetchLatest() else {
                isResolvingModelRelease = false
                refreshModelStatus()
                if showUserMessage {
                    messageKey = "CRISIS_SENTINEL_DOWNLOAD_MANIFEST_MISSING"
                }
                return
            }

            currentRelease = release
            modelRuntime.updateRelease(release)
            manifestCache.save(release)
            modelStatus = store.status(for: release, validateChecksum: false)

            let shouldDownload = forceDownload || (shouldDownloadIfNeeded && modelStatus.isReady == false)
            guard shouldDownload else {
                isResolvingModelRelease = false
                return
            }

            isResolvingModelRelease = false
            isDownloadingModel = true
            downloadProgress = 0
            modelStatus = try await downloader.download(release: release) { [weak self] fraction in
                Task { @MainActor in
                    self?.downloadProgress = fraction
                }
            }
            downloadProgress = 1
            isDownloadingModel = false
            if showUserMessage {
                messageKey = "CRISIS_SENTINEL_DOWNLOAD_QUEUED"
            }
        } catch CrisisSentinelModelDownloadError.missingHTTPSURL {
            isResolvingModelRelease = false
            isDownloadingModel = false
            refreshModelStatus()
            if showUserMessage {
                messageKey = "CRISIS_SENTINEL_DOWNLOAD_MANIFEST_MISSING"
            }
        } catch {
            isResolvingModelRelease = false
            isDownloadingModel = false
            refreshModelStatus()
            if showUserMessage {
                messageKey = "CRISIS_SENTINEL_DOWNLOAD_FAILED"
            }
        }
    }

    private static func availableModes(for role: String?) -> [CrisisSentinelUserMode] {
        switch role {
        case "admin":
            return [.publicUser, .fieldTeam, .coordinator]
        case "fieldteam":
            return [.publicUser, .fieldTeam]
        default:
            return [.publicUser]
        }
    }

    private static func preferredMode(for role: String?) -> CrisisSentinelUserMode {
        switch role {
        case "admin":
            return .coordinator
        case "fieldteam":
            return .fieldTeam
        default:
            return .publicUser
        }
    }
}
