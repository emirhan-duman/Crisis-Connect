//
//  SOSNotificationCenter.swift
//  Crisis Connect
//
//  Created by Assistant on 10.01.2026
//

import Foundation
import Intents
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

enum AppNotificationMessageKind: String, Sendable {
    case sosAlert
    /// Genuine contact-state events: pairing requests, parent-child confirmations.
    case contactUpdate
    /// 1:1 chat content (text/voice/image/location). Its own kind because chat posts used to ride
    /// .contactUpdate — so the innocuous-looking "contact updates" settings toggle silently
    /// silenced EVERY chat message in the app.
    case chatMessage
    case generalChat
}

enum AppNotificationRoute: Equatable, Sendable {
    case session(UUID)
    case generalChat
    // Authority (kurum) channel push → the channels list (Android parity: a notification tap
    // lands in the kurum messaging surface instead of dead-ending on the home screen).
    case authorityChannels
    // Hierarchy (1:1-in-channel) push → straight into that peer's thread (Android deep-link parity).
    case authorityThread(channelId: String, peerUid: String)
    // Home-screen widget / crisisconnect:// URL deep links.
    case sosCountdown
    case recentDisasters
}

struct AppNotificationPreferences: Sendable {
    let appNotificationsEnabled: Bool
    let sosAlertsEnabled: Bool
    let contactUpdatesEnabled: Bool
    let chatMessagesEnabled: Bool

    init(userDefaults: UserDefaults = .standard) {
        appNotificationsEnabled = userDefaults.object(forKey: Keys.appNotificationsEnabled) as? Bool ?? true
        sosAlertsEnabled = userDefaults.object(forKey: Keys.sosAlertsEnabled) as? Bool ?? true
        contactUpdatesEnabled = userDefaults.object(forKey: Keys.contactUpdatesEnabled) as? Bool ?? true
        chatMessagesEnabled = userDefaults.object(forKey: Keys.chatMessagesEnabled) as? Bool ?? true
    }

    func allows(_ kind: AppNotificationMessageKind) -> Bool {
        guard appNotificationsEnabled else { return false }
        switch kind {
        case .sosAlert:
            return sosAlertsEnabled
        case .contactUpdate:
            return contactUpdatesEnabled
        case .chatMessage:
            return chatMessagesEnabled
        case .generalChat:
            return true
        }
    }

    private enum Keys {
        static let appNotificationsEnabled = "notifications.appEnabled"
        static let sosAlertsEnabled = "notifications.sosAlerts"
        static let contactUpdatesEnabled = "notifications.contactUpdates"
        static let chatMessagesEnabled = "notifications.chatMessages"
    }
}

enum SOSNotificationCenter {
    private enum NotificationCategory: String {
        case call = "chat.call"
        case message = "chat.message"
    }

    private enum NotificationAction: String {
        case answerCall = "chat.call.answer"
        case rejectCall = "chat.call.reject"
    }

    private enum RouteKind: String {
        case session
        case generalChat
        case authorityChannels
        case authorityThread
        case sosCountdown
        case recentDisasters
    }

    private enum UserInfoKey {
        static let sessionId = "sessionId"
        static let routeKind = "routeKind"
        static let messageKind = "messageKind"
        static let channelId = "channelId"
        static let peerUid = "peerUid"
    }

    private static let delegate = NotificationDelegate()
    private static let visibilityQueue = DispatchQueue(label: "sos.notification.visibility")
    private static let routeQueue = DispatchQueue(label: "sos.notification.route")
    private static var visibleSessionIds = Set<String>()
    private static var pendingRoute: AppNotificationRoute?

    static func configure() {
        let center = UNUserNotificationCenter.current()
        center.setNotificationCategories(notificationCategories())
        center.delegate = delegate
    }

    static func consumePendingRoute() -> AppNotificationRoute? {
        routeQueue.sync {
            let route = pendingRoute
            pendingRoute = nil
            return route
        }
    }

    /// External entry (widget tap / crisisconnect:// URL) into the same
    /// pending-route pipeline that notification taps use.
    static func openRoute(_ route: AppNotificationRoute) {
        requestOpenRoute(route)
    }

    static func registerVisibleSession(_ sessionId: UUID) {
        let normalized = normalizedSessionId(sessionId.uuidString)
        visibilityQueue.async {
            visibleSessionIds.insert(normalized)
        }
    }

    static func unregisterVisibleSession(_ sessionId: UUID) {
        let normalized = normalizedSessionId(sessionId.uuidString)
        visibilityQueue.async {
            visibleSessionIds.remove(normalized)
        }
    }

    static func notifyIncomingMessage(
        sessionId: UUID,
        title: String?,
        body: String,
        kind: AppNotificationMessageKind = .contactUpdate,
        route: AppNotificationRoute? = nil
    ) {
        guard shouldNotify(kind: kind) else { return }
        let resolvedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !resolvedBody.isEmpty else { return }
        let normalizedSessionId = normalizedSessionId(sessionId.uuidString)
        let resolvedTitle = resolvedNotificationTitle(from: title)
        let resolvedRoute = route ?? .session(sessionId)

        UNUserNotificationCenter.current().getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized ||
                    settings.authorizationStatus == .provisional ||
                    settings.authorizationStatus == .ephemeral else { return }

            let content = UNMutableNotificationContent()
            content.title = resolvedTitle
            content.body = resolvedBody
            content.userInfo[UserInfoKey.sessionId] = normalizedSessionId
            content.userInfo[UserInfoKey.routeKind] = routeKind(for: resolvedRoute).rawValue
            content.userInfo[UserInfoKey.messageKind] = kind.rawValue
            content.threadIdentifier = normalizedSessionId
            content.categoryIdentifier = NotificationCategory.message.rawValue
            if shouldPlaySound() {
                content.sound = .default
            }
            let avatar: INImage?
            if #available(iOS 15.0, *) {
                avatar = NotificationAvatarRenderer.image(sessionId: sessionId, displayName: resolvedTitle)
            } else {
                avatar = nil
            }
            let requestContent = enrichedContent(
                from: content,
                sessionId: normalizedSessionId,
                senderDisplayName: resolvedTitle,
                body: resolvedBody,
                avatar: avatar
            )
            let request = UNNotificationRequest(
                identifier: uniqueMessageNotificationIdentifier(for: resolvedRoute),
                content: requestContent,
                trigger: nil
            )
            UNUserNotificationCenter.current().add(request) { error in
                guard let error else { return }
                NSLog("Failed to schedule incoming message notification: %@", String(describing: error))
            }
        }
    }

    static func notifyIncomingCall(sessionId: UUID, title: String?, body: String) {
        guard shouldNotify(kind: .sosAlert) else { return }
        let resolvedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !resolvedBody.isEmpty else { return }
        let normalizedSessionId = normalizedSessionId(sessionId.uuidString)
        let resolvedTitle = resolvedNotificationTitle(from: title)

        UNUserNotificationCenter.current().getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized ||
                    settings.authorizationStatus == .provisional ||
                    settings.authorizationStatus == .ephemeral else { return }

            let content = UNMutableNotificationContent()
            content.title = resolvedTitle
            content.body = resolvedBody
            content.userInfo[UserInfoKey.sessionId] = normalizedSessionId
            content.userInfo[UserInfoKey.routeKind] = RouteKind.session.rawValue
            content.threadIdentifier = normalizedSessionId
            content.categoryIdentifier = NotificationCategory.call.rawValue
            if shouldPlaySound() {
                content.sound = .default
            }
            if #available(iOS 15.0, *) {
                content.interruptionLevel = .timeSensitive
                content.relevanceScore = 1
            }
            let requestContent: UNNotificationContent
            if #available(iOS 15.0, *) {
                let avatar = NotificationAvatarRenderer.image(sessionId: sessionId, displayName: resolvedTitle)
                requestContent = enrichedContent(
                    from: content,
                    sessionId: normalizedSessionId,
                    senderDisplayName: resolvedTitle,
                    body: resolvedBody,
                    avatar: avatar
                )
            } else {
                requestContent = content
            }
            let request = UNNotificationRequest(
                identifier: incomingCallNotificationIdentifier(for: normalizedSessionId),
                content: requestContent,
                trigger: nil
            )
            UNUserNotificationCenter.current().add(request) { error in
                guard let error else { return }
                NSLog("Failed to schedule incoming call notification: %@", String(describing: error))
            }
        }
    }

    static func clearIncomingCallNotification(sessionId: UUID) {
        let normalizedSessionId = normalizedSessionId(sessionId.uuidString)
        let identifier = incomingCallNotificationIdentifier(for: normalizedSessionId)
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    static func clearMessageNotification(route: AppNotificationRoute) {
        // Each incoming message now posts as its OWN notification ("<prefix>.<uuid>") so the system
        // stacks a running thread instead of replacing the last line, so opening the conversation
        // has to sweep the whole stack — not a single fixed id. Older builds posted a single
        // "<prefix>" notification, so match that too. `prefix` is the conversation's stable id.
        let prefix = messageNotificationIdentifier(for: route)
        let center = UNUserNotificationCenter.current()
        func matches(_ identifier: String) -> Bool {
            identifier == prefix || identifier.hasPrefix(prefix + ".")
        }
        center.getPendingNotificationRequests { requests in
            let identifiers = requests.map(\.identifier).filter(matches)
            guard !identifiers.isEmpty else { return }
            center.removePendingNotificationRequests(withIdentifiers: identifiers)
        }
        center.getDeliveredNotifications { notifications in
            let identifiers = notifications.map(\.request.identifier).filter(matches)
            guard !identifiers.isEmpty else { return }
            center.removeDeliveredNotifications(withIdentifiers: identifiers)
        }
    }

    fileprivate static func shouldNotify(kind: AppNotificationMessageKind) -> Bool {
        AppNotificationPreferences().allows(kind)
    }

    fileprivate static func shouldPlaySound() -> Bool {
        let defaults = UserDefaults.standard
        return defaults.object(forKey: Keys.playSoundEnabled) as? Bool ?? true
    }

    fileprivate static func shouldSuppressForegroundPresentation(sessionId: String?) -> Bool {
        guard let sessionId else { return false }
        let normalized = normalizedSessionId(sessionId)
        return visibilityQueue.sync {
            visibleSessionIds.contains(normalized)
        }
    }

    fileprivate static func shouldPresentNotificationContent(_ content: UNNotificationContent) -> Bool {
        switch content.categoryIdentifier {
        case NotificationCategory.call.rawValue:
            return shouldNotify(kind: .sosAlert)
        case NotificationCategory.message.rawValue:
            let kind = messageKind(from: content.userInfo) ?? .contactUpdate
            return shouldNotify(kind: kind)
        default:
            return false
        }
    }

    private static func normalizedSessionId(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func notificationCategories() -> Set<UNNotificationCategory> {
        let answerAction = UNNotificationAction(
            identifier: NotificationAction.answerCall.rawValue,
            title: NSLocalizedString("VOICE_CALL_ANSWER_ACTION", comment: ""),
            options: [.foreground]
        )
        let rejectAction = UNNotificationAction(
            identifier: NotificationAction.rejectCall.rawValue,
            title: NSLocalizedString("VOICE_CALL_REJECT_ACTION", comment: ""),
            options: [.destructive]
        )
        let callCategory = UNNotificationCategory(
            identifier: NotificationCategory.call.rawValue,
            actions: [answerAction, rejectAction],
            intentIdentifiers: [],
            options: []
        )
        return [callCategory]
    }

    private static func incomingCallNotificationIdentifier(for normalizedSessionId: String) -> String {
        "call.\(normalizedSessionId)"
    }

    /// Stable per-conversation identifier prefix. Used as the `threadIdentifier`-adjacent grouping
    /// key and as the prefix that `clearMessageNotification` sweeps when the thread is opened.
    private static func messageNotificationIdentifier(for route: AppNotificationRoute) -> String {
        switch route {
        case .session(let sessionId):
            return "message.\(normalizedSessionId(sessionId.uuidString))"
        case .generalChat:
            return "message.general-chat"
        case .authorityChannels:
            return "message.authority-channels"
        case .authorityThread(let channelId, let peerUid):
            return "message.authority-thread.\(channelId).\(peerUid)"
        case .sosCountdown:
            return "message.sos-countdown"
        case .recentDisasters:
            return "message.recent-disasters"
        }
    }

    /// A UNIQUE request id per incoming message, prefixed with the conversation's stable id.
    ///
    /// The previous fixed-per-conversation id made every new message REPLACE the last one, so the
    /// user only ever saw the latest line. Posting each message under its own id (but the same
    /// `content.threadIdentifier`) lets iOS stack them as one conversation thread — the native
    /// equivalent of Android's `MessagingStyle` running history in `BleMessageNotifier`. The shared
    /// prefix lets `clearMessageNotification(route:)` remove the whole stack in one pass.
    private static func uniqueMessageNotificationIdentifier(for route: AppNotificationRoute) -> String {
        "\(messageNotificationIdentifier(for: route)).\(UUID().uuidString)"
    }

    /// Posts the "new encrypted authority message" alert. A hierarchy (1:1-in-channel) push deep-links
    /// straight into that peer's thread; an agency broadcast (or a push without a channelId) lands on
    /// the channels list. Content stays generic — the payload is E2E, the push carries only names.
    static func postAuthorityChannelNotification(
        senderUid: String,
        senderName: String,
        channelId: String? = nil,
        channelKind: String? = nil,
        body: String? = nil
    ) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized ||
                    settings.authorizationStatus == .provisional ||
                    settings.authorizationStatus == .ephemeral else { return }
            let content = UNMutableNotificationContent()
            content.title = senderName.isEmpty
                ? NSLocalizedString("AUTHORITY_CHANNELS_TITLE", comment: "")
                : senderName
            // Real decrypted preview when the receiver could produce one in time (Android parity),
            // generic body otherwise — the push payload itself never carries plaintext.
            content.body = body?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
                ? body!
                : NSLocalizedString("AUTHORITY_MESSAGE_NOTIFICATION_BODY", comment: "")
            content.threadIdentifier = "authority-channel"
            content.categoryIdentifier = NotificationCategory.message.rawValue
            // Deep-link only a hierarchy push with a resolvable channel; else the directory.
            if channelKind == "hierarchy", let channelId, !channelId.isEmpty, !senderUid.isEmpty {
                content.userInfo[UserInfoKey.routeKind] = RouteKind.authorityThread.rawValue
                content.userInfo[UserInfoKey.channelId] = channelId
                content.userInfo[UserInfoKey.peerUid] = senderUid
            } else {
                content.userInfo[UserInfoKey.routeKind] = RouteKind.authorityChannels.rawValue
            }
            content.userInfo[UserInfoKey.messageKind] = AppNotificationMessageKind.generalChat.rawValue
            if shouldPlaySound() {
                content.sound = .default
            }
            // Route-derived UNIQUE id, not the old fixed "authority-channel-<senderUid>": that id
            // made every new message from a sender REPLACE the previous banner, and it carried no
            // "message." prefix so the clear-on-open sweep could never find it.
            let route: AppNotificationRoute =
                (channelKind == "hierarchy" && channelId?.isEmpty == false && !senderUid.isEmpty)
                    ? .authorityThread(channelId: channelId ?? "", peerUid: senderUid)
                    : .authorityChannels
            let request = UNNotificationRequest(
                identifier: uniqueMessageNotificationIdentifier(for: route),
                content: content,
                trigger: nil
            )
            UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
        }
    }

    fileprivate static func handleNotificationResponse(_ response: UNNotificationResponse) {
        let content = response.notification.request.content

        switch content.categoryIdentifier {
        case NotificationCategory.call.rawValue:
            guard let rawSessionId = content.userInfo[UserInfoKey.sessionId] as? String,
                  let sessionId = UUID(uuidString: rawSessionId) else {
                return
            }

            switch response.actionIdentifier {
            case NotificationAction.answerCall.rawValue:
                clearIncomingCallNotification(sessionId: sessionId)
                ChatPeerVoiceCallCoordinator.shared.handleIncomingCallNotificationAnswer(sessionId: sessionId)
            case NotificationAction.rejectCall.rawValue:
                clearIncomingCallNotification(sessionId: sessionId)
                ChatPeerVoiceCallCoordinator.shared.handleIncomingCallNotificationReject(sessionId: sessionId)
            case UNNotificationDefaultActionIdentifier:
                clearIncomingCallNotification(sessionId: sessionId)
                ChatPeerVoiceCallCoordinator.shared.handleIncomingCallNotificationOpen(sessionId: sessionId)
            default:
                break
            }
        case NotificationCategory.message.rawValue:
            guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
                  let route = route(from: content.userInfo) ?? remoteChatRoute(from: content.userInfo)
            else {
                return
            }
            clearMessageNotification(route: route)
            requestOpenRoute(route)
        default:
            // The backend's alert pushes historically carried NO category, so a tap on one fell
            // through here and did nothing: the app foregrounded and the navigation was dropped.
            // Newer pushes carry category "chat.message", but installed bases keep receiving the
            // old shape — route them by their data payload instead of giving up.
            guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
                  let route = remoteChatRoute(from: content.userInfo) else {
                return
            }
            clearMessageNotification(route: route)
            requestOpenRoute(route)
        }
    }

    /// Resolves a REMOTE alert push to the local chat. The server only knows the wire
    /// conversationId and sender uid — it never has the local session — so resolve the contact the
    /// same way the receive path files the message: by conversation, then by peer uid.
    private static func remoteChatRoute(from userInfo: [AnyHashable: Any]) -> AppNotificationRoute? {
        guard (userInfo["type"] as? String) == "chat" else { return nil }
        let conversationId = (userInfo["conversationId"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let senderUid = (userInfo["senderUid"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let contact = ContactStore.shared.contacts.first {
            !conversationId.isEmpty
                && $0.sessionCode.caseInsensitiveCompare(conversationId) == .orderedSame
        } ?? (senderUid.isEmpty ? nil : ContactStore.shared.contactForPeerUid(senderUid))
        guard let contact else { return nil }
        return .session(contact.id)
    }

    private static func resolvedNotificationTitle(from title: String?) -> String {
        let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false
            ? trimmed!
            : NSLocalizedString("SOS_CHAT_NOTIFICATION_TITLE", comment: "")
    }

    private static func enrichedContent(
        from baseContent: UNMutableNotificationContent,
        sessionId: String,
        senderDisplayName: String,
        body: String,
        avatar: INImage? = nil
    ) -> UNNotificationContent {
        guard #available(iOS 15.0, *) else {
            return baseContent
        }

        let senderHandle = INPersonHandle(value: sessionId, type: .unknown)
        let sender = INPerson(
            personHandle: senderHandle,
            nameComponents: nil,
            displayName: senderDisplayName,
            image: avatar,
            contactIdentifier: nil,
            customIdentifier: sessionId,
            isMe: false,
            suggestionType: .instantMessageAddress
        )
        let intent = INSendMessageIntent(
            recipients: nil,
            outgoingMessageType: .outgoingMessageText,
            content: body,
            speakableGroupName: nil,
            conversationIdentifier: sessionId,
            serviceName: appDisplayName(),
            sender: sender,
            attachments: nil
        )

        guard let updated = try? baseContent.updating(from: intent) else {
            return baseContent
        }
        return updated
    }

    private static func appDisplayName() -> String {
        let bundle = Bundle.main
        let displayName = bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String
        let bundleName = bundle.object(forInfoDictionaryKey: "CFBundleName") as? String
        return nonEmptyTrimmed(displayName)
            ?? nonEmptyTrimmed(bundleName)
            ?? "Crisis Connect"
    }

    private static func nonEmptyTrimmed(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    private static func routeKind(for route: AppNotificationRoute) -> RouteKind {
        switch route {
        case .session:
            return .session
        case .generalChat:
            return .generalChat
        case .authorityChannels:
            return .authorityChannels
        case .authorityThread:
            return .authorityThread
        case .sosCountdown:
            return .sosCountdown
        case .recentDisasters:
            return .recentDisasters
        }
    }

    private static func route(from userInfo: [AnyHashable: Any]) -> AppNotificationRoute? {
        guard let rawRouteKind = userInfo[UserInfoKey.routeKind] as? String,
              let routeKind = RouteKind(rawValue: rawRouteKind) else {
            return nil
        }

        switch routeKind {
        case .session:
            guard let rawSessionId = userInfo[UserInfoKey.sessionId] as? String,
                  let sessionId = UUID(uuidString: rawSessionId) else {
                return nil
            }
            return .session(sessionId)
        case .generalChat:
            return .generalChat
        case .authorityChannels:
            return .authorityChannels
        case .authorityThread:
            guard let channelId = userInfo[UserInfoKey.channelId] as? String, !channelId.isEmpty,
                  let peerUid = userInfo[UserInfoKey.peerUid] as? String, !peerUid.isEmpty else {
                // A malformed deep-link still lands somewhere useful.
                return .authorityChannels
            }
            return .authorityThread(channelId: channelId, peerUid: peerUid)
        case .sosCountdown:
            return .sosCountdown
        case .recentDisasters:
            return .recentDisasters
        }
    }

    private static func messageKind(from userInfo: [AnyHashable: Any]) -> AppNotificationMessageKind? {
        guard let rawValue = userInfo[UserInfoKey.messageKind] as? String else { return nil }
        return AppNotificationMessageKind(rawValue: rawValue)
    }

    private static func requestOpenRoute(_ route: AppNotificationRoute) {
        routeQueue.sync {
            pendingRoute = route
        }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .appNotificationRouteDidChange, object: nil)
        }
    }

    private enum Keys {
        static let playSoundEnabled = "notifications.playSound"
    }
}

private final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // The backend's iOS chat push is an ALERT push (so a force-quit app still shows a banner),
        // but its userInfo carries the same E2E data payload the silent push did. In the
        // foreground the app decrypts and posts its OWN localized notification, so never present
        // the generic remote banner too — it would double the local one. Local notifications never
        // set a "type" key, so this only matches the remote push.
        if notification.request.trigger is UNPushNotificationTrigger,
           (notification.request.content.userInfo["type"] as? String) == "chat" {
            completionHandler([])
            return
        }

        guard SOSNotificationCenter.shouldPresentNotificationContent(notification.request.content) else {
            completionHandler([])
            return
        }

        let sessionId = notification.request.content.userInfo["sessionId"] as? String
        guard !SOSNotificationCenter.shouldSuppressForegroundPresentation(sessionId: sessionId) else {
            completionHandler([])
            return
        }

        var options: UNNotificationPresentationOptions = [.banner, .list]
        if SOSNotificationCenter.shouldPlaySound() {
            options.insert(.sound)
        }
        completionHandler(options)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        SOSNotificationCenter.handleNotificationResponse(response)
        completionHandler()
    }
}

extension Notification.Name {
    static let appNotificationRouteDidChange = Notification.Name("app.notificationRouteDidChange")
}
