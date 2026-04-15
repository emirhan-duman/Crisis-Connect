//
//  SOSChatDetailState.swift
//  Crisis Connect
//
//  Created by Assistant on 27.03.2026.
//

import Combine
import Foundation

@MainActor
final class SOSChatDetailState: ObservableObject {
    private static let initialMessageBatchSize = 12
    private static let olderMessagesBatchSize = 24

    @Published private(set) var session: SOSChatSession?
    @Published private(set) var messages: [SOSChatMessage]
    @Published private(set) var totalMessageCount: Int
    @Published private(set) var hasOlderMessages: Bool
    @Published private(set) var isPrepared = false

    private let sessionId: UUID
    private let store: SOSChatStore
    private var loadedMessageCount: Int
    private var cancellables = Set<AnyCancellable>()

    convenience init(sessionId: UUID) {
        self.init(sessionId: sessionId, store: .shared)
    }

    init(sessionId: UUID, store: SOSChatStore) {
        self.sessionId = sessionId
        self.store = store
        self.session = nil
        self.messages = []
        self.totalMessageCount = 0
        self.loadedMessageCount = 0
        self.hasOlderMessages = false
    }

    func prepare() {
        guard !isPrepared else { return }
        isPrepared = true

        session = store.session(for: sessionId)
        let initialTotalCount = store.messageCount(for: sessionId)
        loadedMessageCount = min(initialTotalCount, Self.initialMessageBatchSize)
        let initialMessages = store.recentMessages(for: sessionId, limit: loadedMessageCount)
        messages = initialMessages
        totalMessageCount = initialTotalCount
        hasOlderMessages = initialMessages.count < initialTotalCount

        store.updatesPublisher(for: sessionId)
            .debounce(for: .milliseconds(16), scheduler: DispatchQueue.main)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in
                guard let self else { return }
                self.refresh()
            }
            .store(in: &cancellables)
    }

    func loadOlderMessages() {
        guard hasOlderMessages else { return }
        loadedMessageCount = min(
            totalMessageCount,
            max(messages.count, loadedMessageCount) + Self.olderMessagesBatchSize
        )
        refresh()
    }

    private func refresh() {
        let nextSession = store.session(for: sessionId)
        let nextTotalMessageCount = store.messageCount(for: sessionId)
        let hadLoadedEntireHistory = loadedMessageCount >= totalMessageCount

        if nextTotalMessageCount == 0 {
            loadedMessageCount = 0
        } else if loadedMessageCount == 0 {
            loadedMessageCount = min(nextTotalMessageCount, Self.initialMessageBatchSize)
        } else if hadLoadedEntireHistory {
            loadedMessageCount = nextTotalMessageCount
        } else {
            loadedMessageCount = min(nextTotalMessageCount, loadedMessageCount)
        }

        let nextMessages = store.recentMessages(for: sessionId, limit: loadedMessageCount)
        let nextHasOlderMessages = nextMessages.count < nextTotalMessageCount
        if nextSession != session {
            session = nextSession
        }
        if nextTotalMessageCount != totalMessageCount {
            totalMessageCount = nextTotalMessageCount
        }
        if nextHasOlderMessages != hasOlderMessages {
            hasOlderMessages = nextHasOlderMessages
        }
        if nextMessages != messages {
            messages = nextMessages
        }
    }
}

@MainActor
final class SOSChatContactState: ObservableObject {
    @Published private(set) var contact: ContactRecord?

    private var cancellables = Set<AnyCancellable>()

    convenience init(sessionId: UUID) {
        self.init(sessionId: sessionId, contactStore: .shared)
    }

    private let sessionId: UUID
    private let contactStore: ContactStore
    private var hasPrepared = false

    init(sessionId: UUID, contactStore: ContactStore) {
        self.sessionId = sessionId
        self.contactStore = contactStore
        self.contact = nil
    }

    func prepare() {
        guard !hasPrepared else { return }
        hasPrepared = true

        contact = contactStore.contacts.first { $0.id == sessionId }

        contactStore.$contacts
            .map { [sessionId] contacts in
                contacts.first { $0.id == sessionId }
            }
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] nextContact in
                guard let self else { return }
                if nextContact != self.contact {
                    self.contact = nextContact
                }
            }
            .store(in: &cancellables)
    }
}

@MainActor
final class SOSChatHostConnectionState: ObservableObject {
    @Published private(set) var isConnected: Bool

    private var cancellables = Set<AnyCancellable>()

    convenience init(sessionId: UUID) {
        self.init(sessionId: sessionId, manager: .shared)
    }

    init(sessionId: UUID, manager: ContactBroadcastManager) {
        self.isConnected = manager.connectedSessionIds.contains(sessionId)

        manager.$connectedSessionIds
            .map { $0.contains(sessionId) }
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isConnected in
                self?.isConnected = isConnected
            }
            .store(in: &cancellables)
    }
}
