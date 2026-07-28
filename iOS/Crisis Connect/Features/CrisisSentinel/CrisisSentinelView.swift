//
//  CrisisSentinelView.swift
//  Crisis Connect
//

import Network
import PhotosUI
import Combine
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private struct CrisisSentinelConversationRoute: Identifiable, Hashable {
    let id = UUID()
    let conversationID: String?
}

struct CrisisSentinelView: View {
    @StateObject private var viewModel = CrisisSentinelViewModel()
    var onOpenMainMenu: () -> Void = {}

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                CrisisSentinelIntroCard()
                CrisisSentinelModelStatusCard(viewModel: viewModel)
                downloadOnlyCard
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 18)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle("CRISIS_SENTINEL_TITLE")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.refreshModelStatus()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel(Text("CRISIS_SENTINEL_REFRESH_STATUS"))
            }
        }
        .crisisSentinelAlert(viewModel)
        .accessibilityIdentifier("crisis-sentinel-download-screen")
    }

    private var downloadOnlyCard: some View {
        // Android parity (DownloadOnlyCard): field-team users can chat via the CLOUD engine before
        // (or instead of) downloading the on-device model — the card unlocks when EITHER engine is
        // usable, with dedicated copy for the online-only state.
        let canUseAi = viewModel.modelStatus.isReady || viewModel.onlineEngineAvailable
        let titleKey: String
        let bodyKey: String
        if viewModel.modelStatus.isReady {
            titleKey = "CRISIS_SENTINEL_DOWNLOAD_READY_TITLE"
            bodyKey = "CRISIS_SENTINEL_DOWNLOAD_READY_BODY"
        } else if viewModel.onlineEngineAvailable {
            titleKey = "CRISIS_SENTINEL_DOWNLOAD_FIELDTEAM_ONLINE_TITLE"
            bodyKey = "CRISIS_SENTINEL_DOWNLOAD_FIELDTEAM_ONLINE_BODY"
        } else {
            titleKey = "CRISIS_SENTINEL_DOWNLOAD_ONLY_TITLE"
            bodyKey = "CRISIS_SENTINEL_DOWNLOAD_ONLY_BODY"
        }
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: canUseAi ? "checkmark.circle.fill" : "icloud.and.arrow.down")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(canUseAi ? Color.appSuccess : Color.appPrimary)

                Text(LocalizedStringKey(titleKey))
                    .font(.headline)
            }

            Text(LocalizedStringKey(bodyKey))
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)

            Button {
                onOpenMainMenu()
            } label: {
                Label("CRISIS_SENTINEL_OPEN_MAIN_MENU", systemImage: "house.fill")
            }
            .buttonStyle(AppPrimaryButtonStyle(fill: canUseAi ? .appPrimary : .appTextSecondary))
            .disabled(!canUseAi)
        }
        .appSurface(style: .regular, padding: 16)
    }
}

struct CrisisSentinelHomeView: View {
    @StateObject private var viewModel = CrisisSentinelViewModel()
    @State private var pendingConversationRoute: CrisisSentinelConversationRoute?
    @State private var pendingDeletion: CrisisSentinelConversationSummary?
    @State private var isInfoPresented = false
    @State private var isSettingsPresented = false
    @State private var isDownloadPresented = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if viewModel.canChat {
                        if viewModel.conversations.isEmpty {
                            emptyConversationState
                        } else {
                            conversationList
                        }
                    } else {
                        modelNotReadyCard
                    }
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 14)
                .padding(.bottom, viewModel.canChat ? 112 : 24)
            }
            .scrollIndicators(.hidden)

            if viewModel.canChat {
                newChatFloatingButton
                    .padding(.trailing, AppTheme.screenPadding)
                    .padding(.bottom, 18)
            }
        }
        .background(AppScreenBackground())
        .navigationTitle("CRISIS_SENTINEL_TITLE")
        .navigationBarTitleDisplayMode(.inline)
        // Android re-checks the role on every screen open — a certificate issued after this view
        // model was created (fresh sign-in, auto-provisioning) must unlock the cloud engine here.
        .task {
            await viewModel.refreshRoleAccess()
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 16) {
                    Button {
                        isInfoPresented = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .accessibilityLabel(Text("CRISIS_SENTINEL_INFO"))

                    Button {
                        isSettingsPresented = true
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .accessibilityLabel(Text("CRISIS_SENTINEL_SETTINGS"))
                }
            }
        }
        .navigationDestination(item: $pendingConversationRoute) { route in
            CrisisSentinelChatView(conversationID: route.conversationID)
        }
        .navigationDestination(isPresented: $isSettingsPresented) {
            CrisisSentinelSettingsView()
        }
        .navigationDestination(isPresented: $isDownloadPresented) {
            CrisisSentinelView()
        }
        .sheet(isPresented: $isInfoPresented) {
            NavigationStack {
                CrisisSentinelInfoView()
                    .navigationTitle("CRISIS_SENTINEL_INFO")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("COMMON_CLOSE") {
                                isInfoPresented = false
                            }
                        }
                    }
            }
        }
        .confirmationDialog(
            "CRISIS_SENTINEL_DELETE_CHAT_TITLE",
            isPresented: Binding(
                get: { pendingDeletion != nil },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingDeletion
        ) { conversation in
            Button("CRISIS_SENTINEL_DELETE_CHAT_ACTION", role: .destructive) {
                viewModel.deleteConversation(id: conversation.id)
                pendingDeletion = nil
            }
            Button("COMMON_CANCEL", role: .cancel) {
                pendingDeletion = nil
            }
        } message: { _ in
            Text("CRISIS_SENTINEL_DELETE_CHAT_MESSAGE")
        }
        .onAppear {
            viewModel.refreshModelStatus()
            viewModel.refreshConversations()
        }
        .accessibilityIdentifier("crisis-sentinel-home-screen")
    }

    private var conversationList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("CRISIS_SENTINEL_RECENT_CHATS")
                    .font(.headline)

                Spacer()

                Text("\(viewModel.conversations.count)")
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(Color.appTextSecondary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(Capsule(style: .continuous).fill(Color.appSurfaceMuted))
            }
            .padding(.horizontal, 4)

            VStack(spacing: 0) {
                ForEach(viewModel.conversations.indices, id: \.self) { index in
                    let conversation = viewModel.conversations[index]

                    Button {
                        pendingConversationRoute = CrisisSentinelConversationRoute(conversationID: conversation.id)
                    } label: {
                        CrisisSentinelConversationRow(conversation: conversation)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("CRISIS_SENTINEL_DELETE_CHAT_ACTION", systemImage: "trash", role: .destructive) {
                            pendingDeletion = conversation
                        }
                    }

                    if index < viewModel.conversations.count - 1 {
                        Divider()
                            .padding(.leading, 76)
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
        }
    }

    private var emptyConversationState: some View {
        VStack(spacing: 10) {
            Text("CRISIS_SENTINEL_NO_CHATS_TITLE")
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)

            Text("CRISIS_SENTINEL_NO_CHATS_BODY")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 96)
        .padding(.horizontal, 24)
    }

    private var newChatFloatingButton: some View {
        Button {
            pendingConversationRoute = CrisisSentinelConversationRoute(conversationID: nil)
        } label: {
            Label("CRISIS_SENTINEL_NEW_CHAT", systemImage: "square.and.pencil")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 13)
                .background(Capsule(style: .continuous).fill(Color.appPrimary))
                .shadow(color: Color.appPrimary.opacity(0.26), radius: 16, y: 8)
        }
        .buttonStyle(.plain)
    }

    private var modelNotReadyCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CRISIS_SENTINEL_MODEL_ANSWER_NOT_READY")
                .font(.headline)
            Text("CRISIS_SENTINEL_MODEL_FALLBACK")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
            Button {
                isDownloadPresented = true
            } label: {
                Label("CRISIS_SENTINEL_MODEL_DOWNLOAD", systemImage: "icloud.and.arrow.down")
            }
            .buttonStyle(AppSecondaryButtonStyle(tint: .appPrimary))
        }
        .appSurface(style: .regular, padding: 16)
    }
}

struct CrisisSentinelSettingsView: View {
    @StateObject private var viewModel = CrisisSentinelViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                CrisisSentinelModelStatusCard(viewModel: viewModel)
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 18)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle("CRISIS_SENTINEL_SETTINGS")
        .navigationBarTitleDisplayMode(.inline)
        .crisisSentinelAlert(viewModel)
    }
}

private struct CrisisSentinelInfoView: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("SETTINGS_AI_NOTICES_DESCRIPTION")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)

                VStack(alignment: .leading, spacing: 10) {
                    noticeRow("SETTINGS_AI_NOTICES_BASED_ON_GEMMA", icon: "cpu")
                    noticeRow("SETTINGS_AI_NOTICES_POLICY", icon: "exclamationmark.triangle")
                    noticeRow("SETTINGS_AI_NOTICES_NOT_AFFILIATED", icon: "info.circle")
                }

                Button {
                    openExternalURL("https://ai.google.dev/gemma/terms")
                } label: {
                    Label("SETTINGS_AI_NOTICES_TERMS_BUTTON", systemImage: "doc.text")
                }
                .buttonStyle(AppSecondaryButtonStyle(tint: .appPrimary))

                Button {
                    openExternalURL("https://ai.google.dev/gemma/prohibited_use_policy")
                } label: {
                    Label("SETTINGS_AI_NOTICES_POLICY_BUTTON", systemImage: "shield.lefthalf.filled")
                }
                .buttonStyle(AppSecondaryButtonStyle(tint: .appPrimary))
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 18)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
    }

    private func noticeRow(_ key: LocalizedStringKey, icon: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appPrimary)
                .frame(width: 22)

            Text(key)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
    }

    private func openExternalURL(_ rawURL: String) {
        guard let url = URL(string: rawURL) else { return }
        openURL(url)
    }
}

struct CrisisSentinelChatView: View {
    @StateObject private var viewModel = CrisisSentinelViewModel()
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var isFileImporterPresented = false
    @State private var currentConversationID: String?
    @State private var isChatAtBottom = true
    @State private var showNewReplyChip = false
    @State private var isModelPickerPresented = false
    let conversationID: String?

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ZStack(alignment: .bottom) {
                    ScrollView {
                        LazyVStack(spacing: 14) {
                            if viewModel.activeConversation?.messages.isEmpty != false {
                                chatEmptyState
                            } else {
                                let messages = viewModel.activeConversation?.messages ?? []
                                let lastMessageID = messages.last?.id
                                ForEach(messages) { message in
                                    CrisisSentinelChatBubble(
                                        message: message,
                                        canRegenerate: message.role == .assistant &&
                                            message.id == lastMessageID &&
                                            viewModel.isGenerating == false,
                                        onRegenerate: { viewModel.regenerateLastResponse() },
                                        onShowOnMap: { points in viewModel.showPointsOnMap(points) }
                                    )
                                    .id(message.id)
                                }
                            }

                            if viewModel.isGenerating {
                                assistantThinkingBubble
                                    .id("crisis-sentinel-thinking")
                            }

                            // Bottom sentinel for smart-scroll: tracks whether the user is
                            // currently reading the end of the conversation.
                            Color.clear
                                .frame(height: 1)
                                .id("crisis-sentinel-bottom")
                                .onAppear {
                                    isChatAtBottom = true
                                    showNewReplyChip = false
                                }
                                .onDisappear {
                                    isChatAtBottom = false
                                }
                        }
                        .padding(.horizontal, AppTheme.screenPadding)
                        .padding(.vertical, 18)
                    }
                    .scrollIndicators(.hidden)
                    .onChange(of: viewModel.activeConversation?.messages.count ?? 0) { _, _ in
                        let lastIsUser = viewModel.activeConversation?.messages.last?.role == .user
                        if lastIsUser || isChatAtBottom {
                            scrollToBottom(proxy)
                            showNewReplyChip = false
                        } else if viewModel.isGenerating == false {
                            showNewReplyChip = true
                        }
                    }
                    .onChange(of: viewModel.isGenerating) { _, _ in
                        if isChatAtBottom {
                            scrollToBottom(proxy)
                        }
                    }

                    if showNewReplyChip {
                        Button {
                            showNewReplyChip = false
                            scrollToBottom(proxy)
                        } label: {
                            Text("↓  " + NSLocalizedString("CRISIS_SENTINEL_NEW_RESPONSE", comment: ""))
                                .font(.callout.weight(.semibold))
                                .foregroundStyle(Color.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(Capsule(style: .continuous).fill(Color.appPrimary))
                        }
                        .buttonStyle(.plain)
                        .padding(.bottom, 12)
                    }
                }
            }

            engineChips

            CrisisSentinelChatComposer(
                selectedPhotoItem: $selectedPhotoItem,
                prompt: $viewModel.prompt,
                isGenerating: viewModel.isGenerating,
                isProcessingInput: viewModel.isProcessingInput,
                isDictating: viewModel.isDictating,
                onSubmit: {
                    currentConversationID = viewModel.submitChatMessage(conversationID: currentConversationID)
                },
                onCancel: { viewModel.cancelGeneration() },
                onFile: { isFileImporterPresented = true },
                onVoice: { viewModel.toggleDictation() },
                modelReady: viewModel.modelStatus.isReady,
                isDownloading: viewModel.isDownloadingModel,
                onDownload: { viewModel.requestModelDownload() }
            )
        }
        .background(AppScreenBackground())
        .navigationTitle(chatTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .onAppear {
            currentConversationID = currentConversationID ?? conversationID
            viewModel.openConversation(id: currentConversationID)
        }
        .onChange(of: viewModel.activeConversation?.id) { _, newId in
            // A brand-new cloud chat gets its id after createChat lands — sync the composer's id.
            if let newId { currentConversationID = newId }
        }
        .onDisappear {
            updateCurrentDraftID(viewModel.saveDraft(conversationID: currentConversationID))
        }
        .onChange(of: viewModel.prompt) { _, _ in
            guard viewModel.isGenerating == false else { return }
            updateCurrentDraftID(viewModel.saveDraft(conversationID: currentConversationID))
        }
        .onChange(of: selectedPhotoItem) { _, item in
            guard let item else { return }
            Task {
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else {
                        viewModel.messageKey = "CRISIS_SENTINEL_INPUT_FAILED"
                        selectedPhotoItem = nil
                        return
                    }
                    viewModel.importImageDataForOCR(data)
                } catch {
                    viewModel.messageKey = "CRISIS_SENTINEL_INPUT_FAILED"
                }
                selectedPhotoItem = nil
            }
        }
        .fileImporter(
            isPresented: $isFileImporterPresented,
            allowedContentTypes: [.text, .plainText, .json],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    viewModel.importTextFile(url)
                }
            case .failure:
                viewModel.messageKey = "CRISIS_SENTINEL_INPUT_FAILED"
            }
        }
        .crisisSentinelAlert(viewModel)
        .sheet(isPresented: $isModelPickerPresented) {
            CrisisSentinelModelPickerSheet(viewModel: viewModel)
        }
        .sheet(isPresented: Binding(
            get: { !viewModel.pendingMapPoints.isEmpty },
            set: { if !$0 { viewModel.pendingMapPoints = [] } }
        )) {
            NavigationStack {
                OfflineMapView(initialPins: viewModel.pendingMapPoints.map {
                    OfflineMapPin(lat: $0.lat, lng: $0.lng, label: $0.label, details: $0.details)
                })
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("COMMON_CLOSE") { viewModel.pendingMapPoints = [] }
                    }
                }
            }
        }
        .accessibilityIdentifier("crisis-sentinel-chat-screen")
    }

    private var chatTitle: String {
        guard let title = viewModel.activeConversation?.title,
              title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            return localized("CRISIS_SENTINEL_NEW_CHAT")
        }
        return title
    }

    private var chatEmptyState: some View {
        let suggestionKeys: [String] = viewModel.mode == .publicUser
            ? ["CRISIS_SENTINEL_SUGGEST_PUBLIC_1", "CRISIS_SENTINEL_SUGGEST_PUBLIC_2",
               "CRISIS_SENTINEL_SUGGEST_PUBLIC_3", "CRISIS_SENTINEL_SUGGEST_PUBLIC_4"]
            : ["CRISIS_SENTINEL_SUGGEST_FIELD_1", "CRISIS_SENTINEL_SUGGEST_FIELD_2",
               "CRISIS_SENTINEL_SUGGEST_FIELD_3", "CRISIS_SENTINEL_SUGGEST_FIELD_4"]
        return VStack(spacing: 12) {
            AppCustomIconBadge(tint: .appPrimary, size: 58) {
                CrisisSentinelSparklesIcon()
            }
            Text("CRISIS_SENTINEL_CHAT_EMPTY_TITLE")
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            Text("CRISIS_SENTINEL_CHAT_EMPTY_BODY")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)

            // Tappable example prompts make the empty screen functional + teach what the AI can do.
            VStack(spacing: 8) {
                ForEach(suggestionKeys, id: \.self) { key in
                    Button {
                        sendSuggestion(localized(key))
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "sparkles")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Color.appPrimary)
                                .frame(width: 32, height: 32)
                                .background(Circle().fill(Color.appPrimary.opacity(0.12)))
                            Text(LocalizedStringKey(key))
                                .font(.subheadline)
                                .multilineTextAlignment(.leading)
                                .foregroundStyle(Color.primary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(Color.appSurfaceElevated)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .stroke(Color.appBorder, lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 44)
        .padding(.horizontal, AppTheme.screenPadding)
    }

    private func sendSuggestion(_ text: String) {
        viewModel.prompt = text
        currentConversationID = viewModel.submitChatMessage(conversationID: currentConversationID)
    }

    private var assistantThinkingBubble: some View {
        // Bubble-less to match the assistant message style; animated dots instead of a spinner.
        // During an ONLINE generation the reply streams live into this slot (Android parity).
        VStack(alignment: .leading, spacing: 8) {
            if let streaming = viewModel.streamingReply, !streaming.isEmpty {
                Text(streaming)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 10) {
                TypingDotsView()
                Text("CRISIS_SENTINEL_GENERATING")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
                Spacer()
            }
        }
        .padding(.vertical, 2)
    }

    /// Online/Edge engine chips — shown only when the online engine is even allowed (normal users
    /// keep the single-engine experience with nothing to choose). When Cloud is the active engine,
    /// a tappable model chip opens the provider/model picker.
    @ViewBuilder
    private var engineChips: some View {
        if viewModel.isOnlineEngineAllowed {
            HStack(spacing: 8) {
                engineChip(
                    engine: .online,
                    titleKey: "CRISIS_SENTINEL_ENGINE_ONLINE",
                    systemImage: "cloud",
                    available: viewModel.onlineEngineAvailable
                )
                engineChip(
                    engine: .edge,
                    titleKey: "CRISIS_SENTINEL_ENGINE_EDGE",
                    systemImage: "iphone",
                    available: viewModel.edgeEngineAvailable
                )
                if viewModel.activeEngine == .online {
                    Button {
                        viewModel.refreshOnlineProviders()
                        isModelPickerPresented = true
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "cpu")
                                .font(.caption2.weight(.semibold))
                            Text(viewModel.selectedOnlineModelLabel
                                 ?? NSLocalizedString("CRISIS_SENTINEL_MODEL_DEFAULT", comment: ""))
                                .font(.caption2.weight(.medium))
                                .lineLimit(1)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 8, weight: .bold))
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Capsule(style: .continuous).fill(Color.appSurfaceElevated))
                        .foregroundStyle(Color.appTextSecondary)
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.top, 6)
        }
    }

    private func engineChip(
        engine: CrisisSentinelEngine,
        titleKey: String,
        systemImage: String,
        available: Bool
    ) -> some View {
        let selected = viewModel.selectedEngine == engine
        return Button {
            guard available else { return }
            viewModel.selectedEngine = engine
        } label: {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.caption.weight(.semibold))
                Text(LocalizedStringKey(titleKey))
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                Capsule(style: .continuous)
                    .fill(selected ? Color.appPrimary.opacity(0.15) : Color.appSurfaceElevated)
            )
            .overlay(
                Capsule(style: .continuous)
                    .stroke(selected ? Color.appPrimary : Color.appBorder, lineWidth: 1)
            )
            .foregroundStyle(
                available ? (selected ? Color.appPrimary : .primary) : Color.appTextSecondary.opacity(0.6)
            )
        }
        .buttonStyle(.plain)
        .disabled(!available)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        let lastID = viewModel.activeConversation?.messages.last?.id
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            withAnimation(.easeOut(duration: 0.2)) {
                if viewModel.isGenerating {
                    proxy.scrollTo("crisis-sentinel-thinking", anchor: .bottom)
                } else if let lastID {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
        }
    }

    private func updateCurrentDraftID(_ savedID: String?) {
        if let savedID {
            currentConversationID = savedID
        } else if viewModel.activeConversation == nil,
                  viewModel.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            currentConversationID = nil
        }
    }
}

private struct CrisisSentinelIntroCard: View {
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            AppCustomIconBadge(tint: .appPrimary) {
                CrisisSentinelSparklesIcon()
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("CRISIS_SENTINEL_TITLE")
                    .font(.title3.weight(.bold))
                Text("CRISIS_SENTINEL_INTRO")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .appSurface(style: .regular, padding: 16)
    }
}

@MainActor
private final class CrisisSentinelNetworkMonitor: ObservableObject {
    @Published private(set) var isExpensive = false

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "crisis-sentinel-network-monitor")

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.isExpensive = path.isExpensive
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}

private struct CrisisSentinelModelStatusCard: View {
    @ObservedObject var viewModel: CrisisSentinelViewModel
    @StateObject private var networkMonitor = CrisisSentinelNetworkMonitor()
    @State private var showCellularDownloadConfirmation = false
    @State private var showDeleteModelConfirmation = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CRISIS_SENTINEL_MODEL_STATUS_TITLE")
                .font(.headline)

            FlowLayout(spacing: 8) {
                StatusPill(
                    title: localized(modelStatusLabelKey(viewModel.modelStatus.availability)),
                    tint: viewModel.modelStatus.isReady ? .appSuccess : .appWarning
                )
                StatusPill(
                    title: String(
                        format: localized("CRISIS_SENTINEL_MODEL_RELEASE_FORMAT"),
                        viewModel.modelStatus.release.displayName
                    ),
                    tint: .appPrimary
                )
            }

            if viewModel.modelStatus.bytes > 0 {
                Text(
                    String(
                        format: localized("CRISIS_SENTINEL_MODEL_SIZE_FORMAT"),
                        formatBytes(viewModel.modelStatus.bytes)
                    )
                )
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
            }

            VStack(spacing: 8) {
                modelInfoRow(
                    icon: "internaldrive",
                    titleKey: "CRISIS_SENTINEL_MODEL_REQUIRED_SPACE_LABEL",
                    value: String(
                        format: localized("CRISIS_SENTINEL_MODEL_REQUIRED_SPACE_FORMAT"),
                        formatBytes(viewModel.modelStatus.release.minFreeBytes)
                    )
                )
                modelInfoRow(
                    icon: "arrow.down.circle",
                    titleKey: "CRISIS_SENTINEL_MODEL_DOWNLOAD_SIZE_LABEL",
                    value: modelDownloadSizeText
                )
            }

            Text("CRISIS_SENTINEL_MODEL_FALLBACK")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)

            // Low-RAM heads-up: the ~3.6GB on-device model runs slowly under ~4GB RAM.
            if viewModel.showsModelPerformanceWarning {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.appWarning)
                    Text("CRISIS_SENTINEL_GENERATION_PERFORMANCE_WARNING")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .fill(Color.appWarning.opacity(0.12))
                )
            }

            if viewModel.isDownloadingModel {
                ProgressView(value: viewModel.downloadProgress)
                    .progressViewStyle(.linear)
                    .tint(.appPrimary)
                Text(
                    String(
                        format: localized("CRISIS_SENTINEL_MODEL_DOWNLOAD_PROGRESS_FORMAT"),
                        Int((viewModel.downloadProgress * 100).rounded())
                    )
                )
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .monospacedDigit()
            }

            if viewModel.modelStatus.isReady {
                Button(role: .destructive) {
                    showDeleteModelConfirmation = true
                } label: {
                    Label("CRISIS_SENTINEL_MODEL_DELETE", systemImage: "trash")
                }
                .buttonStyle(AppSecondaryButtonStyle(tint: .appDanger))
                .disabled(viewModel.isResolvingModelRelease || viewModel.isDownloadingModel)
            } else {
                Button {
                    if networkMonitor.isExpensive {
                        showCellularDownloadConfirmation = true
                    } else {
                        viewModel.requestModelDownload()
                    }
                } label: {
                    Label(modelDownloadButtonTitle, systemImage: "icloud.and.arrow.down")
                }
                .buttonStyle(AppPrimaryButtonStyle())
                .disabled(viewModel.isResolvingModelRelease || viewModel.isDownloadingModel)
            }
        }
        .appSurface(style: .regular, padding: 16)
        .confirmationDialog(
            "CRISIS_SENTINEL_CELLULAR_DOWNLOAD_TITLE",
            isPresented: $showCellularDownloadConfirmation,
            titleVisibility: .visible
        ) {
            Button("CRISIS_SENTINEL_CELLULAR_DOWNLOAD_ACTION") {
                viewModel.requestModelDownload()
            }
            Button("COMMON_CANCEL", role: .cancel) {}
        } message: {
            Text("CRISIS_SENTINEL_CELLULAR_DOWNLOAD_MESSAGE")
        }
        .confirmationDialog(
            "CRISIS_SENTINEL_MODEL_DELETE_TITLE",
            isPresented: $showDeleteModelConfirmation,
            titleVisibility: .visible
        ) {
            Button("CRISIS_SENTINEL_MODEL_DELETE_ACTION", role: .destructive) {
                viewModel.deleteDownloadedModel()
            }
            Button("COMMON_CANCEL", role: .cancel) {}
        } message: {
            Text("CRISIS_SENTINEL_MODEL_DELETE_MESSAGE")
        }
    }

    private var modelDownloadButtonTitle: LocalizedStringKey {
        if viewModel.isDownloadingModel {
            return "CRISIS_SENTINEL_MODEL_DOWNLOADING"
        }
        if viewModel.isResolvingModelRelease {
            return "CRISIS_SENTINEL_MODEL_MANIFEST_LOADING"
        }
        return "CRISIS_SENTINEL_MODEL_DOWNLOAD"
    }

    private var modelDownloadSizeText: String {
        guard let expectedBytes = viewModel.modelStatus.release.expectedBytes else {
            return localized("CRISIS_SENTINEL_MODEL_DOWNLOAD_SIZE_UNKNOWN")
        }
        return formatBytes(expectedBytes)
    }

    private func modelInfoRow(icon: String, titleKey: LocalizedStringKey, value: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appPrimary)
                .frame(width: 22)

            Text(titleKey)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)

            Spacer(minLength: 8)

            Text(value)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.primary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
    }

    private func modelStatusLabelKey(_ availability: CrisisSentinelModelAvailability) -> String {
        switch availability {
        case .ready: return "CRISIS_SENTINEL_MODEL_ACTIVE"
        case .missing: return "CRISIS_SENTINEL_MODEL_MISSING"
        case .corrupt: return "CRISIS_SENTINEL_MODEL_CORRUPT"
        case .insufficientStorage: return "CRISIS_SENTINEL_MODEL_STORAGE_LOW"
        }
    }
}


private struct CrisisSentinelConversationRow: View {
    let conversation: CrisisSentinelConversationSummary

    var body: some View {
        HStack(spacing: 14) {
            Circle()
                .fill(Color.appPrimarySoft)
                .frame(width: 46, height: 46)
                .overlay(
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                )

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    Text(conversation.title.isEmpty ? localized("CRISIS_SENTINEL_NEW_CHAT") : conversation.title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    if conversation.isDraft {
                        Text("CRISIS_SENTINEL_DRAFT_BADGE")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(Color.appPrimary)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Capsule(style: .continuous).fill(Color.appPrimarySoft))
                    }
                }

                Text(previewText)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .lineLimit(2)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.footnote.weight(.bold))
                .foregroundStyle(Color.appTextSecondary)
        }
    }

    private var previewText: String {
        let preview = conversation.lastMessagePreview ?? localized("CRISIS_SENTINEL_EMPTY_CHAT_PREVIEW")
        guard conversation.isDraft else { return preview }
        return String(format: localized("CRISIS_SENTINEL_DRAFT_PREVIEW_FORMAT"), preview)
    }
}

private struct CrisisSentinelChatBubble: View {
    let message: CrisisSentinelChatMessage
    var canRegenerate: Bool = false
    var onRegenerate: () -> Void = {}
    var onShowOnMap: ([CrisisSentinelMapPoint]) -> Void = { _ in }

    var body: some View {
        HStack(alignment: .bottom) {
            if message.role == .user {
                Spacer(minLength: 44)
            }

            VStack(alignment: .leading, spacing: 6) {
                if message.role == .user {
                    Text(message.text)
                        .font(.body)
                        .foregroundStyle(Color.white)
                } else {
                    if !message.text.isEmpty {
                        Text(markdownLiteAttributed(message.text))
                            .font(.body)
                            .foregroundStyle(Color.primary)
                    }
                    // Online-engine tool card (weather/quake/route/…) rendered inline.
                    if let cardJson = message.cardJson, !cardJson.isEmpty {
                        CrisisSentinelCardView(cardJson: cardJson, onShowOnMap: onShowOnMap)
                            .padding(.top, message.text.isEmpty ? 0 : 4)
                    }
                    // A "show on map" action for a reply that carried standalone map points.
                    if !message.mapPoints.isEmpty {
                        Button {
                            onShowOnMap(message.mapPoints)
                        } label: {
                            Label("CRISIS_SENTINEL_CARD_SHOW_ALL_ON_MAP", systemImage: "map")
                                .font(.caption.weight(.medium))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.appPrimary)
                    }
                }

                if message.role == .assistant, let source = message.source {
                    Text(assistantMetaLabel(source: source))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.appPrimary)
                }
            }
            // User messages stay as a bubble; assistant replies render as full-width plain text
            // (no bubble), ChatGPT/Claude style.
            .padding(.horizontal, message.role == .user ? 14 : 2)
            .padding(.vertical, message.role == .user ? 11 : 2)
            .background(
                Group {
                    if message.role == .user {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(Color.appPrimary)
                    }
                }
            )
            .frame(
                maxWidth: message.role == .user ? 340 : .infinity,
                alignment: message.role == .user ? .trailing : .leading
            )

            if message.role == .assistant {
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: message.role == .user ? .trailing : .leading)
        .contextMenu {
            if message.role == .assistant {
                Button {
                    UIPasteboard.general.string = message.text
                } label: {
                    Label("CHAT_ACTION_COPY", systemImage: "doc.on.doc")
                }
                ShareLink(item: message.text) {
                    Label("CRISIS_SENTINEL_ACTION_SHARE", systemImage: "square.and.arrow.up")
                }
                if canRegenerate {
                    Button {
                        onRegenerate()
                    } label: {
                        Label("CRISIS_SENTINEL_ACTION_REGENERATE", systemImage: "arrow.clockwise")
                    }
                }
            }
        }
    }

    private func assistantMetaLabel(source: CrisisSentinelResponseSource) -> String {
        let sourceKey = source == .localModel
            ? "CRISIS_SENTINEL_SOURCE_LOCAL_MODEL"
            : "CRISIS_SENTINEL_SOURCE_OFFLINE_RULES"
        var parts = [NSLocalizedString(sourceKey, comment: "")]
        if let millis = message.generationDurationMillis, millis > 0 {
            let seconds = String(format: "%.1f", Double(millis) / 1_000)
            parts.append(
                String(
                    format: NSLocalizedString("CRISIS_SENTINEL_DURATION_SECONDS", comment: ""),
                    seconds
                )
            )
        }
        return parts.joined(separator: " · ")
    }
}

/// Minimal markdown for model output: `**bold**`, `# headers`, and `-`/`*`/`•` bullets.
private func markdownLiteAttributed(_ raw: String) -> AttributedString {
    var result = AttributedString()
    let lines = raw.components(separatedBy: "\n")
    for (index, line) in lines.enumerated() {
        if index > 0 {
            result += AttributedString("\n")
        }
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.hasPrefix("#") {
            let content = String(trimmed.drop(while: { $0 == "#" }))
                .trimmingCharacters(in: .whitespaces)
            result += markdownLiteInline(content, forceBold: true)
        } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("• ") {
            result += AttributedString("•  ")
            result += markdownLiteInline(
                String(trimmed.dropFirst(2)).trimmingCharacters(in: .whitespaces),
                forceBold: false
            )
        } else {
            result += markdownLiteInline(line, forceBold: false)
        }
    }
    return result
}

private func markdownLiteInline(_ text: String, forceBold: Bool) -> AttributedString {
    var out = AttributedString()
    var remaining = Substring(text)
    while let start = remaining.range(of: "**") {
        markdownLiteAppend(&out, String(remaining[..<start.lowerBound]), bold: forceBold)
        let afterStart = remaining[start.upperBound...]
        if let end = afterStart.range(of: "**") {
            markdownLiteAppend(&out, String(afterStart[..<end.lowerBound]), bold: true)
            remaining = afterStart[end.upperBound...]
        } else {
            markdownLiteAppend(&out, String(afterStart), bold: forceBold)
            remaining = Substring("")
        }
    }
    markdownLiteAppend(&out, String(remaining), bold: forceBold)
    return out
}

private func markdownLiteAppend(_ target: inout AttributedString, _ text: String, bold: Bool) {
    guard text.isEmpty == false else { return }
    var piece = AttributedString(text)
    if bold {
        piece.font = .body.weight(.semibold)
    }
    target += piece
}

private struct TypingDotsView: View {
    @State private var phase = 0
    private let timer = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.appTextSecondary)
                    .frame(width: 7, height: 7)
                    .opacity(phase == index ? 1 : 0.3)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: phase)
        .onReceive(timer) { _ in
            phase = (phase + 1) % 3
        }
    }
}

private struct CrisisSentinelChatComposer: View {
    @Binding var selectedPhotoItem: PhotosPickerItem?
    @Binding var prompt: String
    let isGenerating: Bool
    let isProcessingInput: Bool
    let isDictating: Bool
    let onSubmit: () -> Void
    let onCancel: () -> Void
    let onFile: () -> Void
    let onVoice: () -> Void
    var modelReady: Bool = true
    var isDownloading: Bool = false
    var onDownload: () -> Void = {}

    var body: some View {
        VStack(spacing: 8) {
            if isProcessingInput {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                    Text("CRISIS_SENTINEL_INPUT_PROCESSING")
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                    Spacer()
                }
                .padding(.horizontal, 4)
            }

            if modelReady {
            HStack(alignment: .bottom, spacing: 8) {
                attachmentMenu

                TextField("CRISIS_SENTINEL_CHAT_PLACEHOLDER", text: $prompt, axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(.body)
                    .lineLimit(1...5)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 8)

                Button(action: onVoice) {
                    Image(systemName: isDictating ? "mic.circle.fill" : "mic")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(isDictating ? Color.appWarning : Color.appTextSecondary)
                        .frame(width: 34, height: 34)
                }
                .disabled(isGenerating || isProcessingInput)
                .accessibilityLabel(Text(isDictating ? "CRISIS_SENTINEL_INPUT_VOICE_STOP" : "CRISIS_SENTINEL_INPUT_VOICE"))

                Button(action: {
                    if isGenerating {
                        onCancel()
                    } else {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        onSubmit()
                    }
                }) {
                    Image(systemName: isGenerating ? "xmark" : "arrow.up")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(Color.white)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(isGenerating ? Color.appWarning : Color.appPrimary))
                }
                .disabled(canSubmit == false)
                .opacity(canSubmit ? 1 : 0.42)
                .animation(.easeInOut(duration: 0.2), value: canSubmit)
                .animation(.easeInOut(duration: 0.2), value: isGenerating)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
            } else {
                // Persistent download CTA instead of a transient alert when the model isn't ready.
                Button(action: onDownload) {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.down.circle.fill")
                        Text(LocalizedStringKey(
                            isDownloading
                                ? "CRISIS_SENTINEL_MODEL_DOWNLOADING_BUTTON"
                                : "CRISIS_SENTINEL_MODEL_DOWNLOAD"
                        ))
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppPrimaryButtonStyle(fill: .appPrimary))
                .disabled(isDownloading)
            }
        }
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(
            Color.appBackground
                .opacity(0.98)
                .ignoresSafeArea(edges: .bottom)
        )
        .overlay(alignment: .top) {
            Rectangle()
                .fill(Color.appBorder.opacity(0.65))
                .frame(height: 1)
        }
    }

    private var attachmentMenu: some View {
        Menu {
            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                Label("CRISIS_SENTINEL_INPUT_PHOTO_OCR", systemImage: "photo")
            }

            Button(action: onFile) {
                Label("CRISIS_SENTINEL_INPUT_FILE", systemImage: "doc.text")
            }
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.appTextSecondary)
                .frame(width: 34, height: 34)
        }
        .disabled(isGenerating || isProcessingInput)
    }

    private var canSubmit: Bool {
        isGenerating || prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }
}

private struct StatusPill: View {
    let title: String
    let tint: Color

    var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(Capsule().fill(tint.opacity(0.12)))
    }
}

private struct FlowLayout<Content: View>: View {
    let spacing: CGFloat
    let content: Content

    init(spacing: CGFloat, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        HStack(spacing: spacing) {
            content
        }
    }
}

private extension View {
    func crisisSentinelAlert(_ viewModel: CrisisSentinelViewModel) -> some View {
        alert(
            Text("CRISIS_SENTINEL_NOTICE_TITLE"),
            isPresented: Binding(
                get: { viewModel.messageKey != nil },
                set: { if !$0 { viewModel.messageKey = nil } }
            )
        ) {
            Button("COMMON_CLOSE", role: .cancel) {
                viewModel.messageKey = nil
            }
        } message: {
            Text(LocalizedStringKey(viewModel.messageKey ?? ""))
        }
    }
}

private func localized(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

private func formatBytes(_ bytes: Int64) -> String {
    let kb = Double(bytes) / 1024
    let mb = kb / 1024
    let gb = mb / 1024
    if gb >= 1 { return String(format: "%.1f GB", gb) }
    if mb >= 1 { return String(format: "%.1f MB", mb) }
    if kb >= 1 { return String(format: "%.1f KB", kb) }
    return "\(bytes) B"
}

#Preview {
    NavigationStack { CrisisSentinelHomeView() }
}
