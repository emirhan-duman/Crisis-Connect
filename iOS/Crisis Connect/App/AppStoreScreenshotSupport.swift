//
//  AppStoreScreenshotSupport.swift
//  Crisis Connect
//
//  Created by Codex on 26.03.2026.
//

import Foundation

enum AppStoreScreenshotSupport {
    private static let screenshotSceneEnvironmentKey = "CRISIS_SCREENSHOT_SCENE"
    private static let chatSceneValue = "chat"
    private static let messagesHomeSceneValue = "messages-home"
    private static let messagesSearchSceneValue = "messages-search"
    private static let onboardingBackgroundSceneValue = "onboarding-background"
    private static let chatStableIdentifier = "app-store-screenshot-chat-english-reference-v2"

    static var isChatSceneEnabled: Bool {
#if DEBUG
        ProcessInfo.processInfo.environment[screenshotSceneEnvironmentKey] == chatSceneValue
#else
        false
#endif
    }

    static var isMessagesHomeSceneEnabled: Bool {
#if DEBUG
        ProcessInfo.processInfo.environment[screenshotSceneEnvironmentKey] == messagesHomeSceneValue
#else
        false
#endif
    }

    static var isMessagesSearchSceneEnabled: Bool {
#if DEBUG
        ProcessInfo.processInfo.environment[screenshotSceneEnvironmentKey] == messagesSearchSceneValue
#else
        false
#endif
    }

    static var isOnboardingBackgroundSceneEnabled: Bool {
#if DEBUG
        ProcessInfo.processInfo.environment[screenshotSceneEnvironmentKey] == onboardingBackgroundSceneValue
#else
        false
#endif
    }

    static var isAnySceneEnabled: Bool {
        isChatSceneEnabled || isMessagesHomeSceneEnabled || isMessagesSearchSceneEnabled || isOnboardingBackgroundSceneEnabled
    }

    static var screenshotSearchQuery: String {
        "Maya"
    }

    static var chatSessionId: UUID {
        BroadcastSessionId.fromRawIdentifier(chatStableIdentifier)
    }

    @discardableResult
    static func prepareChatScenarioIfNeeded() -> UUID {
        let store = SOSChatStore.shared

        let contact = ContactStore.shared.upsertBleContact(
            name: "Maya Chen",
            sessionCode: chatStableIdentifier,
            aesKeyBase64: Data(repeating: 0x41, count: 32).base64EncodedString(),
            isVerified: true,
            verifiedIdentityKey: chatStableIdentifier,
            verifiedAt: Date(),
            remoteSessionCode: nil,
            remotePlatform: .ios,
            bleShareId: "APPSTORE-MAYA",
            lastKnownBleAddress: nil,
            remoteDeviceId: "maya-iphone"
        )

        if store.messageCount(for: contact.id) > 0, store.session(for: contact.id) != nil {
            return contact.id
        }

        store.ensureSession(
            id: contact.id,
            displayName: contact.name,
            role: .unknown,
            isVerified: true
        )

        guard store.messageCount(for: contact.id) == 0 else {
            return contact.id
        }

        _ = store.appendCallEvent(
            sessionId: contact.id,
            direction: .incoming,
            result: .missed,
            durationMillis: nil
        )
        _ = store.appendRemoteMessage(
            sessionId: contact.id,
            text: "Hey, I tried calling. Are you okay?"
        )
        if let sampleAudioRelativePath = SOSChatStore.persistVoiceData(
            Data(repeating: 0x4D, count: 512),
            messageId: "\(chatStableIdentifier)-voice-note",
            mimeType: "audio/mp4"
        ) {
            _ = store.appendRemoteAudioMessage(
                sessionId: contact.id,
                audioRelativePath: sampleAudioRelativePath,
                durationMillis: 12_000
            )
        }
        _ = store.appendLocalMessage(
            sessionId: contact.id,
            text: "Sorry, it was too crowded. I couldn't answer.",
            status: .read
        )
        _ = store.appendLocalMessage(
            sessionId: contact.id,
            text: "I'm okay. Let's meet at the park near my house.",
            status: .read
        )
        store.markRemoteRead(sessionId: contact.id)

        return contact.id
    }
}
