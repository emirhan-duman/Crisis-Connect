//
//  AuthorityChannelView.swift
//  Crisis Connect
//
//  Agency-internal encrypted channel (Kurum Mesajları) — the first increment of Android's
//  authority messaging UI on iOS. Uses the already-verified AgencyMessagingClient: the shared
//  agency key is issued membership-gated by the backend, messages live as ciphertext under
//  agencyPanels/{slug}/secureMessages, and the web panel reads/writes the same channel, so
//  field teams and the dashboard converse in one place.
//
//  Hierarchy (cross-panel 1:1) channels are the next increment; they ride a different store
//  (hierarchyChannels/{id}) plus the web API for channel discovery.
//

import Combine
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions
import SwiftUI

@MainActor
final class AuthorityChannelStore: ObservableObject {
    enum State: Equatable {
        case loading
        case ready
        /// The signed-in account has no agency panel membership (or the key request was refused).
        case notMember
        case error
    }

    @Published private(set) var state: State = .loading
    @Published private(set) var messages: [AgencyMessage] = []
    @Published private(set) var agencyTitle: String = ""

    private let client = AgencyMessagingClient()
    private var agencyKey: AgencyKey?
    private var listener: ListenerRegistration?
    private var hasStarted = false

    var myUid: String? { Auth.auth().currentUser?.uid }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task { await bootstrap() }
    }

    func stop() {
        listener?.remove()
        listener = nil
        hasStarted = false
    }

    private func bootstrap() async {
        guard let uid = Auth.auth().currentUser?.uid else {
            state = .notMember
            return
        }
        do {
            let userDoc = try await Firestore.firestore().collection("users").document(uid).getDocument()
            let slug = (userDoc.get("agencySlug") as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !slug.isEmpty else {
                state = .notMember
                return
            }
            let agencyName = (userDoc.get("agency") as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            agencyTitle = agencyName.isEmpty ? slug : agencyName

            // Membership-gated server-side: a non-member gets a permission error here.
            let key = try await client.fetchAgencyKey(agencySlug: slug)
            agencyKey = key
            listener = client.listen(agencyKey: key) { [weak self] messages in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.messages = messages
                    if self.state != .ready { self.state = .ready }
                }
            }
        } catch {
            NSLog("AuthorityChannelStore: bootstrap failed: %@", String(describing: error))
            let nsError = error as NSError
            let permissionDenied = nsError.domain == FunctionsErrorDomain
                && nsError.code == FunctionsErrorCode.permissionDenied.rawValue
            state = permissionDenied ? .notMember : .error
        }
    }

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let agencyKey else { return }
        let senderName = ProfileMetadataStore.loadFullName()
        Task {
            do {
                try await client.send(
                    agencyKey: agencyKey,
                    senderName: senderName.isEmpty ? "iOS" : senderName,
                    text: trimmed
                )
            } catch {
                NSLog("AuthorityChannelStore: send failed: %@", String(describing: error))
            }
        }
    }
}

struct AuthorityChannelView: View {
    @StateObject private var store = AuthorityChannelStore()
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

    var body: some View {
        Group {
            switch store.state {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .notMember:
                statusView(icon: "building.2", messageKey: "AUTHORITY_CHANNEL_NOT_MEMBER")
            case .error:
                statusView(icon: "wifi.exclamationmark", messageKey: "AUTHORITY_CHANNEL_ERROR")
            case .ready:
                conversation
            }
        }
        .background(Color.appBackground)
        .navigationTitle(store.agencyTitle.isEmpty
            ? NSLocalizedString("AUTHORITY_CHANNELS_TITLE", comment: "")
            : store.agencyTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .onAppear { store.start() }
        .onDisappear { store.stop() }
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(store.messages) { message in
                            bubble(message)
                                .id(message.id)
                        }
                    }
                    .padding(.horizontal, AppTheme.screenPadding)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: store.messages.last?.id) { _, lastId in
                    guard let lastId else { return }
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
                .onAppear {
                    if let lastId = store.messages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
            }

            Divider()

            composer
        }
    }

    private func bubble(_ message: AgencyMessage) -> some View {
        let isMine = message.senderUid == store.myUid
        return HStack {
            if isMine { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 3) {
                if !isMine, !message.senderName.isEmpty {
                    Text(message.senderName)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.appPrimary)
                }
                Text(message.text)
                    .font(.body)
                HStack {
                    Spacer(minLength: 0)
                    Text(message.at, style: .time)
                        .font(.caption2)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(isMine ? Color.appPrimarySoft : Color.appSurfaceElevated)
            )
            if !isMine { Spacer(minLength: 48) }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
    }

    private var composer: some View {
        HStack(spacing: 10) {
            TextField(LocalizedStringKey("AUTHORITY_COMPOSER_PLACEHOLDER"), text: $draft, axis: .vertical)
                .lineLimit(1...4)
                .textFieldStyle(.plain)
                .focused($composerFocused)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(
                    Capsule(style: .continuous)
                        .fill(Color.appSurfaceElevated)
                )

            Button {
                let text = draft
                draft = ""
                store.send(text)
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(Color.appPrimary))
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 10)
        .background(Color.appBackground)
    }

    private func statusView(icon: String, messageKey: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 40))
                .foregroundStyle(Color.appTextSecondary)
            Text(LocalizedStringKey(messageKey))
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
