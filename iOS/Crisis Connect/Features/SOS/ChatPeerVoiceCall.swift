//
//  ChatPeerVoiceCall.swift
//  Crisis Connect
//
//  Created by Codex on 27.03.2026.
//

import AVFoundation
import AudioToolbox
import CallKit
import Combine
import CoreImage
import Foundation
import MediaPlayer
import MultipeerConnectivity
import SwiftUI
import UIKit

private enum ChatPeerVoiceCallConstants {
    static let serviceType = "ccvoicechat"
    static let protocolMagic = Data("CCV2".utf8)
    static let sampleRate = 24_000.0
    static let channelCount: AVAudioChannelCount = 1
    static let framesPerPacket: AVAudioFrameCount = 480
    static let inviteTimeout: TimeInterval = 18
    static let unansweredTimeout: TimeInterval = 30
    static let pendingOfferTimeout: TimeInterval = 12
    static let terminalResetDelay: TimeInterval = 6
    static let tapBufferSize: AVAudioFrameCount = 1_024
    static let maxPacketSize = 512
    static let videoFrameInterval: TimeInterval = 1.0 / 6.0
    static let videoCompressionQuality = 0.32
    static let videoPreviewMaxDimension: CGFloat = 240
    static let remoteVideoStaleTimeout: TimeInterval = 2.0
}

private enum ChatPeerVoicePacketType: UInt8 {
    case control = 1
    case audio = 2
    case video = 3
}

private enum ChatPeerVoiceControlKind: String, Codable {
    case offer
    case ring
    case accept
    case reject
    case busy
    case end
}

enum ChatPeerCallKind: String, Codable, Equatable {
    case audio
    case video

    var isVideo: Bool {
        self == .video
    }
}

private struct ChatPeerVoiceInviteContext: Codable {
    let version: Int
    let callId: String
    let callerSessionCode: String
    let calleeSessionCode: String
    let callerDisplayName: String
    let callKind: ChatPeerCallKind

    init(
        version: Int = 2,
        callId: String,
        callerSessionCode: String,
        calleeSessionCode: String,
        callerDisplayName: String,
        callKind: ChatPeerCallKind
    ) {
        self.version = version
        self.callId = callId
        self.callerSessionCode = callerSessionCode
        self.calleeSessionCode = calleeSessionCode
        self.callerDisplayName = callerDisplayName
        self.callKind = callKind
    }
}

private struct ChatPeerVoiceControlMessage: Codable {
    let kind: ChatPeerVoiceControlKind
    let callId: String
    let displayName: String?
    let reason: String?
}

enum ChatPeerVoiceCallDirection: Equatable {
    case incoming
    case outgoing
}

enum ChatPeerVoiceCallPhase: Equatable {
    case idle
    case dialing
    case ringing
    case connecting
    case active
    case ended(message: String)
    case failed(message: String)
}

extension ChatPeerVoiceCallPhase {
    var isLivePresentationPhase: Bool {
        switch self {
        case .dialing, .ringing, .connecting, .active:
            return true
        case .idle, .ended, .failed:
            return false
        }
    }

    var isTerminalPhase: Bool {
        switch self {
        case .ended, .failed:
            return true
        case .idle, .dialing, .ringing, .connecting, .active:
            return false
        }
    }
}

struct ChatPeerVoiceCallSnapshot: Equatable {
    let sessionId: UUID
    let contactName: String
    let callKind: ChatPeerCallKind
    let phase: ChatPeerVoiceCallPhase
    let direction: ChatPeerVoiceCallDirection
    let statusMessage: String
    let startedAt: Date
    let connectedAt: Date?
    let isMuted: Bool
    let isSpeakerEnabled: Bool
    let isLocalVideoEnabled: Bool
    let isRemoteVideoAvailable: Bool
}

struct ChatPeerVoiceIncomingPresentation: Identifiable, Equatable {
    let id: String
    let sessionId: UUID
    let contactName: String
    let callKind: ChatPeerCallKind
}

private struct ChatPeerVoiceDiscoveredPeer {
    let sessionCode: String
    let displayName: String?
}

private struct ChatPeerVoicePendingInvite {
    let callId: String
    let sessionId: UUID
    let contact: ContactRecord
    let contactName: String
    let peerID: MCPeerID
    let callKind: ChatPeerCallKind
}

private struct ChatPeerVoiceSystemCallRecord {
    let callUUID: UUID
    let sessionId: UUID
    let callId: String
    let callKind: ChatPeerCallKind
}

private final class ChatPeerVoiceSystemCallBridge: NSObject, CXProviderDelegate {
    static let shared = ChatPeerVoiceSystemCallBridge()

    private let syncQueue = DispatchQueue(label: "chat.peer.voice.system-call")
    private let provider: CXProvider
    private let callController = CXCallController()

    private weak var coordinator: ChatPeerVoiceCallCoordinator?
    private var callsByUUID: [UUID: ChatPeerVoiceSystemCallRecord] = [:]
    private var callUUIDByCallId: [String: UUID] = [:]
    private var callUUIDBySessionId: [UUID: UUID] = [:]

    private override init() {
        let appName = (Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let configuration = CXProviderConfiguration(localizedName: (appName?.isEmpty == false) ? appName! : "Crisis Connect")
        configuration.supportedHandleTypes = [.generic]
        configuration.supportsVideo = true
        configuration.maximumCallsPerCallGroup = 1
        configuration.maximumCallGroups = 1
        configuration.includesCallsInRecents = false
        provider = CXProvider(configuration: configuration)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    func bind(coordinator: ChatPeerVoiceCallCoordinator) {
        self.coordinator = coordinator
    }

    func isManagingCall(callId: String?) -> Bool {
        guard let callId else { return false }
        return syncQueue.sync {
            callUUIDByCallId[callId] != nil
        }
    }

    func requestAnswerIfNeeded(sessionId: UUID) -> Bool {
        guard let callUUID = syncQueue.sync(execute: { callUUIDBySessionId[sessionId] }) else {
            return false
        }

        let transaction = CXTransaction(action: CXAnswerCallAction(call: callUUID))
        callController.request(transaction) { [weak self] error in
            guard error != nil else { return }
            self?.coordinator?.performAnswerCall(sessionId: sessionId)
        }
        return true
    }

    func requestEndIfNeeded(sessionId: UUID?) -> Bool {
        let callUUID: UUID? = syncQueue.sync {
            if let sessionId,
               let callUUID = callUUIDBySessionId[sessionId] {
                return callUUID
            }
            return callsByUUID.keys.first
        }
        guard let callUUID else { return false }

        let transaction = CXTransaction(action: CXEndCallAction(call: callUUID))
        callController.request(transaction) { [weak self] error in
            guard error != nil else { return }
            self?.coordinator?.performEndCall()
        }
        return true
    }

    func reportIncomingCallIfNeeded(
        callId: String,
        sessionId: UUID,
        contactName: String,
        callKind: ChatPeerCallKind,
        completion: @escaping (Bool) -> Void
    ) {
        guard let callUUID = UUID(uuidString: callId) else {
            completion(false)
            return
        }

        let alreadyManaged = syncQueue.sync {
            callsByUUID[callUUID] != nil
        }
        guard !alreadyManaged else {
            completion(true)
            return
        }

        let update = CXCallUpdate()
        update.localizedCallerName = contactName
        update.remoteHandle = CXHandle(type: .generic, value: contactName)
        update.hasVideo = callKind.isVideo
        update.supportsDTMF = false
        update.supportsGrouping = false
        update.supportsHolding = false
        update.supportsUngrouping = false

        provider.reportNewIncomingCall(with: callUUID, update: update) { [weak self] error in
            guard let self else {
                completion(false)
                return
            }

            if error == nil {
                let record = ChatPeerVoiceSystemCallRecord(
                    callUUID: callUUID,
                    sessionId: sessionId,
                    callId: callId,
                    callKind: callKind
                )
                self.syncQueue.sync {
                    self.callsByUUID[callUUID] = record
                    self.callUUIDByCallId[callId] = callUUID
                    self.callUUIDBySessionId[sessionId] = callUUID
                }
                completion(true)
                return
            }

            completion(false)
        }
    }

    func reportEndedIfNeeded(callId: String?, reason: CXCallEndedReason) {
        guard let callId,
              let record = record(forCallId: callId) else {
            return
        }
        provider.reportCall(with: record.callUUID, endedAt: Date(), reason: reason)
        _ = removeRecord(for: record.callUUID)
    }

    func startOutgoingCallIfNeeded(
        callId: String,
        sessionId: UUID,
        contactName: String,
        callKind: ChatPeerCallKind
    ) {
        guard let callUUID = UUID(uuidString: callId) else {
            return
        }

        let shouldStart = syncQueue.sync {
            guard callsByUUID[callUUID] == nil else { return false }
            let record = ChatPeerVoiceSystemCallRecord(
                callUUID: callUUID,
                sessionId: sessionId,
                callId: callId,
                callKind: callKind
            )
            callsByUUID[callUUID] = record
            callUUIDByCallId[callId] = callUUID
            callUUIDBySessionId[sessionId] = callUUID
            return true
        }
        guard shouldStart else { return }

        let action = CXStartCallAction(
            call: callUUID,
            handle: CXHandle(type: .generic, value: contactName)
        )
        action.isVideo = callKind.isVideo
        let transaction = CXTransaction(action: action)
        callController.request(transaction) { [weak self] error in
            guard let self, error != nil else { return }
            _ = self.removeRecord(for: callUUID)
        }
    }

    func reportOutgoingConnectedIfNeeded(callId: String?) {
        guard let callId,
              let record = record(forCallId: callId) else {
            return
        }
        provider.reportOutgoingCall(with: record.callUUID, connectedAt: Date())
    }

    func providerDidReset(_ provider: CXProvider) {
        guard !AppStoreScreenshotSupport.isAnySceneEnabled else { return }
        syncQueue.sync {
            callsByUUID.removeAll()
            callUUIDByCallId.removeAll()
            callUUIDBySessionId.removeAll()
        }
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard let record = record(forCallUUID: action.callUUID) else {
            action.fail()
            return
        }
        coordinator?.requestOpenSession(record.sessionId)
        coordinator?.performAnswerCall(sessionId: record.sessionId)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        if AppStoreScreenshotSupport.isAnySceneEnabled {
            _ = removeRecord(for: action.callUUID)
            action.fulfill()
            return
        }
        coordinator?.performEndCall()
        _ = removeRecord(for: action.callUUID)
        action.fulfill()
    }

    private func record(forCallId callId: String) -> ChatPeerVoiceSystemCallRecord? {
        syncQueue.sync {
            guard let callUUID = callUUIDByCallId[callId] else { return nil }
            return callsByUUID[callUUID]
        }
    }

    private func record(forCallUUID callUUID: UUID) -> ChatPeerVoiceSystemCallRecord? {
        syncQueue.sync {
            callsByUUID[callUUID]
        }
    }

    @discardableResult
    private func removeRecord(for callUUID: UUID) -> ChatPeerVoiceSystemCallRecord? {
        syncQueue.sync {
            guard let record = callsByUUID.removeValue(forKey: callUUID) else {
                return nil
            }
            callUUIDByCallId[record.callId] = nil
            if callUUIDBySessionId[record.sessionId] == callUUID {
                callUUIDBySessionId[record.sessionId] = nil
            }
            return record
        }
    }
}

final class ChatPeerVoiceCallCoordinator: NSObject, ObservableObject {
    static let shared = ChatPeerVoiceCallCoordinator()

    @Published private(set) var activeCall: ChatPeerVoiceCallSnapshot?
    @Published private(set) var incomingPresentation: ChatPeerVoiceIncomingPresentation?
    @Published private(set) var requestedOpenSessionId: UUID?
    @Published private(set) var localVideoPreviewImage: UIImage?
    @Published private(set) var remoteVideoPreviewImage: UIImage?

    private let transportQueue = DispatchQueue(label: "chat.peer.voice.transport", qos: .userInitiated)
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()
    private let localSessionCode = SecureLocalStore.shared.getOrCreateP2pSessionCode()
    private let audioPipeline = ChatPeerVoiceAudioPipeline()
    private let videoPipeline = ChatPeerVideoCapturePipeline()
    private let screenshotKeepAlivePlayer = ChatPeerVoiceScreenshotKeepAlivePlayer()
    private let screenshotNowPlaying = ChatPeerVoiceScreenshotNowPlayingBridge()
    private let ringtonePlayer = ChatPeerVoiceRingtonePlayer()
    private let systemCallBridge = ChatPeerVoiceSystemCallBridge.shared

    private var contactCancellables = Set<AnyCancellable>()
    private var eligibleContactsBySessionId: [UUID: ContactRecord] = [:]
    private var eligibleContactsByRemoteSessionCode: [String: ContactRecord] = [:]
    private var discoveredPeers: [MCPeerID: ChatPeerVoiceDiscoveredPeer] = [:]

    private var peerID: MCPeerID?
    private var browser: MCNearbyServiceBrowser?
    private var advertiser: MCNearbyServiceAdvertiser?
    private var session: MCSession?
    private var activePeer: MCPeerID?
    private var invitedPeer: MCPeerID?

    private var callSessionId: UUID?
    private var callContact: ContactRecord?
    private var callDisplayName = ""
    private var callId: String?
    private var callKind: ChatPeerCallKind?
    private var callDirection: ChatPeerVoiceCallDirection?
    private var callPhase: ChatPeerVoiceCallPhase = .idle
    private var callStatusMessage = ""
    private var callStartedAt: Date?
    private var callConnectedAt: Date?
    private var pendingOutgoingCall = false
    private var pendingIncomingInvite: ChatPeerVoicePendingInvite?
    private var locallyEndingCall = false
    private var pendingTerminalReset: DispatchWorkItem?
    private var pendingScreenshotActivationWorkItem: DispatchWorkItem?
    private var unansweredTimeoutWorkItem: DispatchWorkItem?
    private var pendingOfferTimeoutWorkItem: DispatchWorkItem?
    private var lastReceivedAudioSequence: UInt32?
    private var lastReceivedVideoSequence: UInt32?
    private var nextOutgoingVideoSequence: UInt32 = 0
    private var lastNotifiedIncomingCallId: String?
    private var shouldPresentFallbackIncomingUI = false
    private var isMuted = false
    private var isSpeakerEnabled = true
    private var isLocalVideoEnabled = false
    private var isRemoteVideoAvailable = false
    private var remoteVideoStaleWorkItem: DispatchWorkItem?
    private var hasPersistedCurrentCallEvent = false

    private override init() {
        super.init()
        systemCallBridge.bind(coordinator: self)
        audioPipeline.onEncodedPacket = { [weak self] payload in
            self?.sendAudioPacket(payload)
        }
        audioPipeline.onFailure = { [weak self] _ in
            guard let self else { return }
            self.handleAudioFailure(self.localizedStatusMessage(for: .unavailable, kind: self.callKind ?? .audio))
        }
        videoPipeline.onEncodedFrame = { [weak self] payload in
            self?.sendVideoFrame(payload)
        }
        videoPipeline.onPreviewImage = { [weak self] image in
            self?.publishLocalVideoPreview(image)
        }
        videoPipeline.onFailure = { [weak self] message in
            self?.handleVideoFailure(message)
        }

        ContactStore.shared.$contacts
            .receive(on: DispatchQueue.main)
            .sink { [weak self] contacts in
                self?.handleContactsUpdated(contacts)
            }
            .store(in: &contactCancellables)

        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in
                self?.handleApplicationDidBecomeActive()
            }
            .store(in: &contactCancellables)

        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)
            .sink { [weak self] _ in
                self?.handleApplicationDidEnterBackground()
            }
            .store(in: &contactCancellables)
    }

    var currentSessionId: UUID? {
        activeCall?.sessionId
    }

    func isSystemManagingCall(sessionId: UUID) -> Bool {
        transportQueue.sync {
            guard callSessionId == sessionId else { return false }
            return systemCallBridge.isManagingCall(callId: callId)
        }
    }

    func placeCall(sessionId: UUID, contact: ContactRecord?, kind: ChatPeerCallKind = .audio) {
        guard let contact else {
            return
        }
        if AppStoreScreenshotSupport.isAnySceneEnabled {
            guard kind == .audio else {
                transitionToFailure(message: localizedStatusMessage(for: .unavailable, kind: kind))
                return
            }
            placeScreenshotCall(sessionId: sessionId, contact: contact)
            return
        }
        guard Self.isFeatureSupported,
              Self.isEligibleContact(contact) else {
            return
        }
        guard let targetSessionCode = Self.targetSessionCode(for: contact) else {
            transitionToFailure(message: localizedStatusMessage(for: .unavailable, kind: kind))
            return
        }

        requestPermissions(for: kind) { [weak self] granted in
            guard let self else { return }
            guard granted else {
                self.transitionToFailure(message: self.localizedPermissionRequiredMessage(for: kind))
                return
            }

            self.startServicesIfNeeded()
            self.transportQueue.async {
                guard self.callId == nil else { return }

                let displayName = Self.displayName(for: contact)
                let callId = UUID().uuidString

                self.beginCall(
                    sessionId: sessionId,
                    contact: contact,
                    displayName: displayName,
                    callKind: kind,
                    direction: .outgoing,
                    callId: callId,
                    phase: .dialing,
                    message: self.localizedStatusMessage(for: .dialing, kind: kind)
                )
                self.systemCallBridge.startOutgoingCallIfNeeded(
                    callId: callId,
                    sessionId: sessionId,
                    contactName: displayName,
                    callKind: kind
                )
                self.pendingOutgoingCall = true
                self.scheduleUnansweredTimeout(for: callId)
                self.rebuildSession()

                if let matchedPeer = self.discoveredPeers.first(where: { $0.value.sessionCode == targetSessionCode })?.key {
                    self.invite(peer: matchedPeer, targetSessionCode: targetSessionCode, callId: callId, callKind: kind)
                }
            }
        }
    }

    private func placeScreenshotCall(sessionId: UUID, contact: ContactRecord) {
        transportQueue.async { [weak self] in
            guard let self, self.callId == nil else { return }

            let displayName = Self.displayName(for: contact)
            let callId = UUID().uuidString

            self.beginCall(
                sessionId: sessionId,
                contact: contact,
                displayName: displayName,
                callKind: .audio,
                direction: .outgoing,
                callId: callId,
                phase: .dialing,
                message: NSLocalizedString("VOICE_CALL_STATUS_DIALING", comment: "")
            )
            // CallKit registration is deferred to activateScreenshotCallAndTransitionToActive()
            // so the native call screen does not flash over the in-app call UI.

            // Dialing → Connecting (1s)
            self.transportQueue.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                guard let self, self.callId == callId else { return }
                self.setPhase(.connecting, message: NSLocalizedString("VOICE_CALL_STATUS_CONNECTING", comment: ""))
                self.publishSnapshot()
            }

            // Connecting → Ringing (2s)
            self.transportQueue.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                guard let self, self.callId == callId else { return }
                self.setPhase(.ringing, message: NSLocalizedString("VOICE_CALL_STATUS_RINGING_OUTGOING", comment: ""))
                self.publishSnapshot()
            }

            // Ringing → Active (3.5s)
            let activationWorkItem = DispatchWorkItem { [weak self] in
                guard let self,
                      self.callId == callId,
                      self.callSessionId == sessionId else { return }
                self.pendingScreenshotActivationWorkItem = nil
                self.activateAudioAndTransitionToActive()
            }
            self.pendingScreenshotActivationWorkItem = activationWorkItem
            self.transportQueue.asyncAfter(deadline: .now() + 3.5, execute: activationWorkItem)
        }
    }

    func answerCall(sessionId: UUID) {
        if systemCallBridge.requestAnswerIfNeeded(sessionId: sessionId) {
            return
        }
        performAnswerCall(sessionId: sessionId)
    }

    func rejectIncomingCall(sessionId: UUID, reason: String = "declined") {
        if reason == "declined",
           systemCallBridge.requestEndIfNeeded(sessionId: sessionId) {
            return
        }
        performRejectIncomingCall(sessionId: sessionId, reason: reason)
    }

    func endCall() {
        if systemCallBridge.requestEndIfNeeded(sessionId: callSessionId) {
            return
        }
        performEndCall()
    }

    func handleIncomingCallNotificationOpen(sessionId: UUID) {
        requestOpenSession(sessionId)
    }

    func handleIncomingCallNotificationAnswer(sessionId: UUID) {
        requestOpenSession(sessionId)
        answerCall(sessionId: sessionId)
    }

    func handleIncomingCallNotificationReject(sessionId: UUID) {
        rejectIncomingCall(sessionId: sessionId)
    }

    func consumeRequestedOpenSession() {
        DispatchQueue.main.async {
            self.requestedOpenSessionId = nil
        }
    }

    fileprivate func performAnswerCall(sessionId: UUID) {
        SOSNotificationCenter.clearIncomingCallNotification(sessionId: sessionId)
        let kind = transportQueue.sync { callSessionId == sessionId ? (callKind ?? .audio) : .audio }
        requestPermissions(for: kind) { [weak self] granted in
            guard let self else { return }
            guard granted else {
                self.rejectIncomingCall(sessionId: sessionId, reason: "permissions_required")
                self.transitionToFailure(message: self.localizedPermissionRequiredMessage(for: kind))
                return
            }

            self.transportQueue.async {
                guard self.callSessionId == sessionId else { return }
                guard case .incoming? = self.callDirection else { return }
                guard case .ringing = self.callPhase else { return }
                guard let callId = self.callId else { return }
                let kind = self.callKind ?? .audio

                self.stopRinging()
                do {
                    self.isMuted = false
                    self.audioPipeline.isMuted = false
                    self.audioPipeline.setSpeakerEnabled(self.isSpeakerEnabled)
                    try self.audioPipeline.start()
                    try self.startVideoIfNeeded()
                    self.callConnectedAt = Date()
                    self.setPhase(.active, message: self.localizedStatusMessage(for: .active, kind: kind))
                    self.cancelUnansweredTimeout()
                    self.sendControl(.accept, callId: callId, reason: nil)
                    self.publishSnapshot()
                } catch {
                    self.sendControl(.reject, callId: callId, reason: "audio_error")
                    self.handleAudioFailure(self.localizedStatusMessage(for: .unavailable, kind: kind))
                }
            }
        }
    }

    fileprivate func performRejectIncomingCall(sessionId: UUID, reason: String) {
        SOSNotificationCenter.clearIncomingCallNotification(sessionId: sessionId)
        transportQueue.async {
            guard self.callSessionId == sessionId else { return }
            guard case .incoming? = self.callDirection else { return }
            guard let callId = self.callId else { return }
            self.stopRinging()
            self.sendControl(.reject, callId: callId, reason: reason)
            let kind = self.callKind ?? .audio
            let message = reason == "missed"
                ? self.localizedStatusMessage(for: .missed, kind: kind)
                : self.localizedStatusMessage(for: .rejected, kind: kind)
            let resultOverride: SOSChatCallResult = reason == "missed" ? .missed : .rejected
            self.transitionToEnded(
                message: message,
                resultOverride: resultOverride
            )
            self.disconnectAndReset()
        }
    }

    fileprivate func performEndCall() {
        if let sessionId = callSessionId {
            SOSNotificationCenter.clearIncomingCallNotification(sessionId: sessionId)
        }
        transportQueue.async {
            guard let callId = self.callId else { return }

            switch (self.callDirection, self.callPhase) {
            case (.incoming?, .ringing):
                self.stopRinging()
                self.sendControl(.reject, callId: callId, reason: "declined")
            default:
                self.locallyEndingCall = true
                self.sendControl(.end, callId: callId, reason: "local")
            }

            let resultOverride: SOSChatCallResult?
            if self.callConnectedAt != nil {
                resultOverride = nil
            } else if case (.incoming?, .ringing) = (self.callDirection, self.callPhase) {
                resultOverride = .rejected
            } else {
                resultOverride = .canceled
            }
            self.transitionToEnded(
                message: self.localizedStatusMessage(for: .ended, kind: self.callKind ?? .audio),
                resultOverride: resultOverride
            )
            self.disconnectAndReset()
        }
    }

    func toggleMute() {
        transportQueue.async {
            guard case .active = self.callPhase else { return }
            self.isMuted.toggle()
            self.audioPipeline.isMuted = self.isMuted
            self.publishSnapshot()
        }
    }

    func toggleSpeaker() {
        transportQueue.async {
            guard case .active = self.callPhase else { return }
            self.isSpeakerEnabled.toggle()
            self.audioPipeline.setSpeakerEnabled(self.isSpeakerEnabled)
            self.screenshotKeepAlivePlayer.setSpeakerEnabled(self.isSpeakerEnabled)
            self.publishSnapshot()
        }
    }

    func toggleLocalVideo() {
        transportQueue.async {
            guard self.callKind == .video else { return }
            guard case .active = self.callPhase else { return }

            self.isLocalVideoEnabled.toggle()
            if self.isLocalVideoEnabled {
                do {
                    try self.videoPipeline.start()
                } catch {
                    self.isLocalVideoEnabled = false
                    self.publishLocalVideoPreview(nil)
                    self.handleVideoFailure(self.localizedStatusMessage(for: .unavailable, kind: .video))
                    return
                }
            } else {
                self.videoPipeline.stop()
            }
            self.publishSnapshot()
        }
    }

    func dismissIncomingPresentation() {
        DispatchQueue.main.async {
            self.incomingPresentation = nil
        }
    }

    fileprivate func requestOpenSession(_ sessionId: UUID) {
        DispatchQueue.main.async {
            self.requestedOpenSessionId = sessionId
        }
    }

    func dismissTerminalState(for sessionId: UUID) {
        transportQueue.async {
            guard self.callSessionId == sessionId else { return }
            guard case .ended = self.callPhase else {
                guard case .failed = self.callPhase else { return }
                self.clearPublishedState()
                self.resetRuntimeState()
                return
            }
            self.clearPublishedState()
            self.resetRuntimeState()
        }
    }

    func bootstrap() {
        startServicesIfNeeded()
    }

    nonisolated static var isFeatureSupported: Bool {
        PlatformRuntime.supportsRescueRuntime
    }

    nonisolated static func isEligibleContact(_ contact: ContactRecord?) -> Bool {
        guard isFeatureSupported,
              let contact else {
            return false
        }
        return contact.preferredTransport == .bleGatt && contact.remotePlatform == .ios
    }

    nonisolated static func targetSessionCode(for contact: ContactRecord) -> String? {
        let remote = contact.remoteSessionCode?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if let remote, !remote.isEmpty {
            return remote
        }
        let fallback = contact.sessionCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return fallback.isEmpty ? nil : fallback
    }

    nonisolated static func displayName(for contact: ContactRecord) -> String {
        let trimmed = contact.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Crisis Connect" : trimmed
    }

    private var localDisplayName: String {
        ProfileMetadataStore.preferredDisplayName() ?? "Crisis Connect"
    }

    private func handleContactsUpdated(_ contacts: [ContactRecord]) {
        let eligibleContacts = contacts.filter(Self.isEligibleContact)
        let bySessionId = Dictionary(uniqueKeysWithValues: eligibleContacts.map { ($0.id, $0) })
        let byRemoteSessionCode: [String: ContactRecord] = Dictionary(
            uniqueKeysWithValues: eligibleContacts.compactMap { contact in
                guard let sessionCode = Self.targetSessionCode(for: contact) else { return nil }
                return (sessionCode, contact)
            }
        )

        transportQueue.async {
            self.eligibleContactsBySessionId = bySessionId
            self.eligibleContactsByRemoteSessionCode = byRemoteSessionCode
            if let sessionId = self.callSessionId,
               let updatedContact = bySessionId[sessionId] {
                self.callContact = updatedContact
                self.callDisplayName = Self.displayName(for: updatedContact)
                self.publishSnapshot()
            }
            if bySessionId.isEmpty, self.callId == nil {
                self.stopServices()
            } else {
                self.startServicesIfNeeded()
            }
        }
    }

    private func handleApplicationDidBecomeActive() {
        transportQueue.async {
            if self.callKind == .video,
               self.isLocalVideoEnabled,
               case .active = self.callPhase {
                try? self.videoPipeline.start()
            }
            if case .incoming? = self.callDirection, case .ringing = self.callPhase {
                if let sessionId = self.callSessionId {
                    SOSNotificationCenter.clearIncomingCallNotification(sessionId: sessionId)
                }
                self.presentIncomingCallOutsideAppIfNeeded()
            }
            self.publishIncomingPresentation()
        }
    }

    private func handleApplicationDidEnterBackground() {
        transportQueue.async {
            if self.callKind == .video {
                self.videoPipeline.stop()
            }
            if case .incoming? = self.callDirection, case .ringing = self.callPhase {
                self.presentIncomingCallOutsideAppIfNeeded()
            }
            self.publishIncomingPresentation()
        }
    }

    private func startServicesIfNeeded() {
        guard Self.isFeatureSupported else { return }
        guard !AppStoreScreenshotSupport.isAnySceneEnabled else { return }

        transportQueue.async {
            guard !self.eligibleContactsBySessionId.isEmpty || self.callId != nil else { return }
            if self.session == nil {
                self.rebuildSession()
            }
            guard self.browser == nil, self.advertiser == nil else { return }

            let peer = self.ensurePeerID()
            let discoveryInfo = [
                "sc": self.localSessionCode,
                "n": self.localDisplayName.prefix(32).description
            ]
            let browser = MCNearbyServiceBrowser(peer: peer, serviceType: ChatPeerVoiceCallConstants.serviceType)
            let advertiser = MCNearbyServiceAdvertiser(
                peer: peer,
                discoveryInfo: discoveryInfo,
                serviceType: ChatPeerVoiceCallConstants.serviceType
            )
            DispatchQueue.main.async {
                browser.delegate = self
                advertiser.delegate = self
                self.browser = browser
                self.advertiser = advertiser
                browser.startBrowsingForPeers()
                advertiser.startAdvertisingPeer()
            }
        }
    }

    private func stopServices() {
        guard !AppStoreScreenshotSupport.isAnySceneEnabled else { return }
        transportQueue.async {
            self.pendingTerminalReset?.cancel()
            self.pendingTerminalReset = nil
            self.pendingScreenshotActivationWorkItem?.cancel()
            self.pendingScreenshotActivationWorkItem = nil
            self.cancelUnansweredTimeout()
            self.cancelPendingOfferTimeout()
            self.cancelRemoteVideoStaleTimeout()
            self.stopRinging()
            self.audioPipeline.stop()
            self.videoPipeline.stop()
            self.screenshotKeepAlivePlayer.stop()
            self.browser?.stopBrowsingForPeers()
            self.browser?.delegate = nil
            self.browser = nil
            self.advertiser?.stopAdvertisingPeer()
            self.advertiser?.delegate = nil
            self.advertiser = nil
            self.session?.disconnect()
            self.session?.delegate = nil
            self.session = nil
            self.discoveredPeers.removeAll()
            self.resetRuntimeState()
            self.clearPublishedState()
        }
    }

    private func ensurePeerID() -> MCPeerID {
        if let peerID {
            return peerID
        }
        let nextPeerID = MCPeerID(displayName: "cc-\(localSessionCode)")
        peerID = nextPeerID
        return nextPeerID
    }

    private func rebuildSession() {
        let peerID = ensurePeerID()
        let nextSession = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .required)
        nextSession.delegate = self
        session?.delegate = nil
        session = nextSession
    }

    private func beginCall(
        sessionId: UUID,
        contact: ContactRecord,
        displayName: String,
        callKind: ChatPeerCallKind,
        direction: ChatPeerVoiceCallDirection,
        callId: String,
        phase: ChatPeerVoiceCallPhase,
        message: String
    ) {
        cancelPendingTerminalReset()
        pendingScreenshotActivationWorkItem?.cancel()
        pendingScreenshotActivationWorkItem = nil
        callSessionId = sessionId
        callContact = contact
        callDisplayName = displayName
        self.callId = callId
        self.callKind = callKind
        callDirection = direction
        callPhase = phase
        callStatusMessage = message
        callStartedAt = Date()
        callConnectedAt = nil
        pendingIncomingInvite = nil
        lastNotifiedIncomingCallId = nil
        shouldPresentFallbackIncomingUI = false
        locallyEndingCall = false
        isMuted = false
        isSpeakerEnabled = true
        isLocalVideoEnabled = callKind == .video
        isRemoteVideoAvailable = false
        hasPersistedCurrentCallEvent = false
        audioPipeline.isMuted = false
        audioPipeline.setSpeakerEnabled(true)
        nextOutgoingVideoSequence = 0
        lastReceivedVideoSequence = nil
        cancelRemoteVideoStaleTimeout()
        publishLocalVideoPreview(nil)
        publishRemoteVideoPreview(nil)
        publishSnapshot()
    }

    private func invite(peer: MCPeerID, targetSessionCode: String, callId: String, callKind: ChatPeerCallKind) {
        guard let session else { return }
        let context = ChatPeerVoiceInviteContext(
            callId: callId,
            callerSessionCode: localSessionCode,
            calleeSessionCode: targetSessionCode,
            callerDisplayName: localDisplayName,
            callKind: callKind
        )
        guard let payload = try? encoder.encode(context) else {
            transitionToFailure(message: localizedStatusMessage(for: .unavailable, kind: callKind))
            return
        }
        invitedPeer = peer
        setPhase(.connecting, message: localizedStatusMessage(for: .connecting, kind: callKind))
        browser?.invitePeer(peer, to: session, withContext: payload, timeout: ChatPeerVoiceCallConstants.inviteTimeout)
    }

    private func requestRecordPermissionIfNeeded(completion: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted:
                completion(true)
            case .denied:
                completion(false)
            case .undetermined:
                AVAudioApplication.requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        completion(granted)
                    }
                }
            @unknown default:
                completion(false)
            }
        } else {
            let audioSession = AVAudioSession.sharedInstance()
            switch audioSession.recordPermission {
            case .granted:
                completion(true)
            case .denied:
                completion(false)
            case .undetermined:
                audioSession.requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        completion(granted)
                    }
                }
            @unknown default:
                completion(false)
            }
        }
    }

    private func requestCameraPermissionIfNeeded(completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            completion(true)
        case .denied, .restricted:
            completion(false)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    completion(granted)
                }
            }
        @unknown default:
            completion(false)
        }
    }

    private func requestPermissions(for kind: ChatPeerCallKind, completion: @escaping (Bool) -> Void) {
        requestRecordPermissionIfNeeded { [weak self] granted in
            guard granted else {
                completion(false)
                return
            }
            guard kind == .video else {
                completion(true)
                return
            }
            self?.requestCameraPermissionIfNeeded(completion: completion)
        }
    }

    private func handleIncomingInvitation(
        from peerID: MCPeerID,
        contextData: Data?,
        invitationHandler: @escaping (Bool, MCSession?) -> Void
    ) {
        guard let contextData,
              let invite = try? decoder.decode(ChatPeerVoiceInviteContext.self, from: contextData),
              invite.calleeSessionCode == localSessionCode,
              let contact = eligibleContactsByRemoteSessionCode[invite.callerSessionCode] else {
            invitationHandler(false, nil)
            return
        }

        transportQueue.async {
            if self.callId != nil {
                invitationHandler(false, nil)
                return
            }

            self.startServicesIfNeeded()
            self.rebuildSession()
            self.activePeer = peerID
            self.invitedPeer = nil
            self.pendingIncomingInvite = ChatPeerVoicePendingInvite(
                callId: invite.callId,
                sessionId: contact.id,
                contact: contact,
                contactName: Self.displayName(for: contact),
                peerID: peerID,
                callKind: invite.callKind
            )
            invitationHandler(true, self.session)
        }
    }

    private func scheduleUnansweredTimeout(for callId: String) {
        cancelUnansweredTimeout()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            guard self.callId == callId else { return }

            if case .incoming? = self.callDirection {
                if let sessionId = self.callSessionId {
                    self.rejectIncomingCall(sessionId: sessionId, reason: "missed")
                }
            } else {
                self.sendControl(.end, callId: callId, reason: "timeout")
                self.transitionToFailure(message: self.localizedStatusMessage(for: .noAnswer, kind: self.callKind ?? .audio))
                self.disconnectAndReset()
            }
        }
        unansweredTimeoutWorkItem = workItem
        transportQueue.asyncAfter(deadline: .now() + ChatPeerVoiceCallConstants.unansweredTimeout, execute: workItem)
    }

    private func cancelUnansweredTimeout() {
        unansweredTimeoutWorkItem?.cancel()
        unansweredTimeoutWorkItem = nil
    }

    private func schedulePendingOfferTimeout(for callId: String) {
        cancelPendingOfferTimeout()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            guard self.callId == nil,
                  self.pendingIncomingInvite?.callId == callId else {
                return
            }
            self.session?.disconnect()
            self.rebuildSession()
            self.activePeer = nil
            self.pendingIncomingInvite = nil
        }
        pendingOfferTimeoutWorkItem = workItem
        transportQueue.asyncAfter(deadline: .now() + ChatPeerVoiceCallConstants.pendingOfferTimeout, execute: workItem)
    }

    private func cancelPendingOfferTimeout() {
        pendingOfferTimeoutWorkItem?.cancel()
        pendingOfferTimeoutWorkItem = nil
    }

    private func sendControl(_ kind: ChatPeerVoiceControlKind, callId: String, reason: String?) {
        let control = ChatPeerVoiceControlMessage(
            kind: kind,
            callId: callId,
            displayName: localDisplayName,
            reason: reason
        )
        guard let payload = try? encoder.encode(control) else { return }
        sendEnvelope(type: .control, sequence: 0, payload: payload, mode: .reliable)
    }

    private func sendAudioPacket(_ payload: Data) {
        transportQueue.async {
            let sequence = self.audioPipeline.nextOutgoingSequence()
            self.sendEnvelope(type: .audio, sequence: sequence, payload: payload, mode: .unreliable)
        }
    }

    private func sendVideoFrame(_ payload: Data) {
        transportQueue.async {
            guard self.callKind == .video, self.isLocalVideoEnabled else { return }
            let sequence = self.nextOutgoingVideoSequence
            self.nextOutgoingVideoSequence &+= 1
            self.sendEnvelope(type: .video, sequence: sequence, payload: payload, mode: .unreliable)
        }
    }

    private func sendEnvelope(
        type: ChatPeerVoicePacketType,
        sequence: UInt32,
        payload: Data,
        mode: MCSessionSendDataMode
    ) {
        guard let session,
              let peer = activePeer ?? invitedPeer,
              session.connectedPeers.contains(peer) else {
            return
        }

        var data = Data()
        data.append(ChatPeerVoiceCallConstants.protocolMagic)
        data.append(type.rawValue)
        var bigEndianSequence = sequence.bigEndian
        withUnsafeBytes(of: &bigEndianSequence) { bytes in
            data.append(contentsOf: bytes)
        }
        data.append(payload)

        do {
            try session.send(data, toPeers: [peer], with: mode)
        } catch {
            handleAudioFailure(localizedStatusMessage(for: .unavailable, kind: callKind ?? .audio))
        }
    }

    private func handleReceivedData(_ data: Data, from peer: MCPeerID) {
        guard (activePeer == nil || activePeer == peer || invitedPeer == peer),
              let envelope = Self.parseEnvelope(data) else {
            return
        }

        switch envelope.type {
        case .control:
            guard let control = try? decoder.decode(ChatPeerVoiceControlMessage.self, from: envelope.payload) else {
                return
            }
            handleControl(control, from: peer)
        case .audio:
            guard shouldAcceptIncomingAudio(sequence: envelope.sequence) else { return }
            audioPipeline.enqueueIncomingOpusPacket(envelope.payload)
        case .video:
            guard shouldAcceptIncomingVideo(sequence: envelope.sequence) else { return }
            handleIncomingVideoFrame(envelope.payload)
        }
    }

    private func handleControl(_ control: ChatPeerVoiceControlMessage, from peer: MCPeerID) {
        switch control.kind {
        case .offer:
            handleIncomingOffer(control, from: peer)
        case .ring:
            guard callId == control.callId,
                  case .outgoing? = callDirection else {
                return
            }
            setPhase(.ringing, message: localizedStatusMessage(for: .ringingOutgoing, kind: callKind ?? .audio))
        case .accept:
            guard callId == control.callId,
                  case .outgoing? = callDirection else {
                return
            }
            stopRinging()
            cancelUnansweredTimeout()
            activateAudioAndTransitionToActive()
        case .reject:
            guard callId == control.callId else { return }
            stopRinging()
            transitionToEnded(
                message: localizedStatusMessage(for: .rejected, kind: callKind ?? .audio),
                resultOverride: .rejected
            )
            disconnectAndReset()
        case .busy:
            guard callId == control.callId else { return }
            stopRinging()
            transitionToFailure(
                message: localizedStatusMessage(for: .busy, kind: callKind ?? .audio),
                resultOverride: .canceled
            )
            disconnectAndReset()
        case .end:
            guard callId == control.callId else { return }
            stopRinging()
            transitionToEnded(message: localizedStatusMessage(for: .ended, kind: callKind ?? .audio))
            disconnectAndReset()
        }
    }

    private func handleIncomingOffer(_ control: ChatPeerVoiceControlMessage, from peer: MCPeerID) {
        if callId != nil, callId != control.callId {
            sendControl(.busy, callId: control.callId, reason: "busy")
            session?.disconnect()
            rebuildSession()
            activePeer = nil
            return
        }

        guard let pendingIncomingInvite,
              pendingIncomingInvite.callId == control.callId,
              pendingIncomingInvite.peerID == peer else {
            return
        }

        cancelPendingOfferTimeout()
        beginCall(
            sessionId: pendingIncomingInvite.sessionId,
            contact: pendingIncomingInvite.contact,
            displayName: pendingIncomingInvite.contactName,
            callKind: pendingIncomingInvite.callKind,
            direction: .incoming,
            callId: control.callId,
            phase: .ringing,
            message: localizedStatusMessage(for: .ringingIncoming, kind: pendingIncomingInvite.callKind)
        )
        activePeer = peer
        sendControl(.ring, callId: control.callId, reason: nil)
        scheduleUnansweredTimeout(for: control.callId)
        presentIncomingCallOutsideAppIfNeeded()
    }

    private func activateAudioAndTransitionToActive() {
        if AppStoreScreenshotSupport.isAnySceneEnabled {
            activateScreenshotCallAndTransitionToActive()
            return
        }
        do {
            audioPipeline.isMuted = isMuted
            audioPipeline.setSpeakerEnabled(isSpeakerEnabled)
            try audioPipeline.start()
            try startVideoIfNeeded()
            callConnectedAt = callConnectedAt ?? Date()
            setPhase(.active, message: localizedStatusMessage(for: .active, kind: callKind ?? .audio))
            if case .outgoing? = callDirection {
                systemCallBridge.reportOutgoingConnectedIfNeeded(callId: callId)
            }
            publishSnapshot()
        } catch {
            handleAudioFailure(localizedStatusMessage(for: .unavailable, kind: callKind ?? .audio))
        }
    }

    private func activateScreenshotCallAndTransitionToActive() {
        do {
            screenshotKeepAlivePlayer.setSpeakerEnabled(isSpeakerEnabled)
            try screenshotKeepAlivePlayer.start()
        } catch {
            NSLog("Screenshot keep-alive audio failed: %@", String(describing: error))
        }
        callConnectedAt = callConnectedAt ?? Date()

        // Register with CallKit now – after the audio session is already
        // active the system reports the call without flashing the native UI.
        if let callId = callId, let sessionId = callSessionId {
            systemCallBridge.startOutgoingCallIfNeeded(
                callId: callId,
                sessionId: sessionId,
                contactName: callDisplayName,
                callKind: .audio
            )
            // Give CallKit a moment to process the start action, then
            // immediately report it as connected so it shows as an
            // ongoing audio call (green pill / Dynamic Island).
            transportQueue.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.systemCallBridge.reportOutgoingConnectedIfNeeded(callId: self?.callId)
            }
        }

        setPhase(.active, message: localizedStatusMessage(for: .active, kind: .audio))
        publishSnapshot()
    }

    private func shouldAcceptIncomingAudio(sequence: UInt32) -> Bool {
        if let lastReceivedAudioSequence, sequence <= lastReceivedAudioSequence {
            return false
        }
        lastReceivedAudioSequence = sequence
        return true
    }

    private func shouldAcceptIncomingVideo(sequence: UInt32) -> Bool {
        if let lastReceivedVideoSequence, sequence <= lastReceivedVideoSequence {
            return false
        }
        lastReceivedVideoSequence = sequence
        return true
    }

    private func startVideoIfNeeded() throws {
        guard callKind == .video, isLocalVideoEnabled else { return }
        try videoPipeline.start()
    }

    private func handleIncomingVideoFrame(_ payload: Data) {
        guard callKind == .video, let image = UIImage(data: payload) else { return }
        isRemoteVideoAvailable = true
        publishRemoteVideoPreview(image)
        publishSnapshot()
        scheduleRemoteVideoStaleTimeout()
    }

    private func scheduleRemoteVideoStaleTimeout() {
        cancelRemoteVideoStaleTimeout()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.isRemoteVideoAvailable = false
            self.publishRemoteVideoPreview(nil)
            self.publishSnapshot()
        }
        remoteVideoStaleWorkItem = workItem
        transportQueue.asyncAfter(deadline: .now() + ChatPeerVoiceCallConstants.remoteVideoStaleTimeout, execute: workItem)
    }

    private func cancelRemoteVideoStaleTimeout() {
        remoteVideoStaleWorkItem?.cancel()
        remoteVideoStaleWorkItem = nil
    }

    private func publishLocalVideoPreview(_ image: UIImage?) {
        DispatchQueue.main.async {
            self.localVideoPreviewImage = image
        }
    }

    private func publishRemoteVideoPreview(_ image: UIImage?) {
        DispatchQueue.main.async {
            self.remoteVideoPreviewImage = image
        }
    }

    private static func parseEnvelope(
        _ data: Data
    ) -> (type: ChatPeerVoicePacketType, sequence: UInt32, payload: Data)? {
        let headerLength = ChatPeerVoiceCallConstants.protocolMagic.count + 1 + MemoryLayout<UInt32>.size
        guard data.count >= headerLength else { return nil }
        let magic = data.prefix(ChatPeerVoiceCallConstants.protocolMagic.count)
        guard magic == ChatPeerVoiceCallConstants.protocolMagic else { return nil }
        guard let type = ChatPeerVoicePacketType(
            rawValue: data[data.startIndex.advanced(by: ChatPeerVoiceCallConstants.protocolMagic.count)]
        ) else {
            return nil
        }

        let sequenceRangeStart = ChatPeerVoiceCallConstants.protocolMagic.count + 1
        let sequenceRangeEnd = sequenceRangeStart + MemoryLayout<UInt32>.size
        var sequence: UInt32 = 0
        withUnsafeMutableBytes(of: &sequence) { rawBuffer in
            rawBuffer.copyBytes(from: data[sequenceRangeStart..<sequenceRangeEnd])
        }
        return (
            type: type,
            sequence: UInt32(bigEndian: sequence),
            payload: Data(data.dropFirst(headerLength))
        )
    }

    private func handleConnectedPeer(_ peer: MCPeerID) {
        activePeer = peer

        if let pendingIncomingInvite,
           pendingIncomingInvite.peerID == peer,
           callId == nil {
            schedulePendingOfferTimeout(for: pendingIncomingInvite.callId)
            return
        }

        if pendingOutgoingCall, let callId {
            if case .outgoing? = callDirection {
                setPhase(.connecting, message: localizedStatusMessage(for: .connecting, kind: callKind ?? .audio))
                sendControl(.offer, callId: callId, reason: nil)
            }
        }
    }

    private func handleDisconnectedPeer(_ peer: MCPeerID) {
        guard !AppStoreScreenshotSupport.isAnySceneEnabled else { return }
        let wasCurrentPeer = activePeer == peer || invitedPeer == peer
        activePeer = nil
        invitedPeer = nil
        cancelPendingOfferTimeout()
        cancelRemoteVideoStaleTimeout()
        audioPipeline.stop()
        videoPipeline.stop()
        stopRinging()
        lastReceivedAudioSequence = nil
        lastReceivedVideoSequence = nil
        if !eligibleContactsBySessionId.isEmpty || callId != nil {
            rebuildSession()
        }

        guard wasCurrentPeer else { return }
        if callId != nil {
            switch callPhase {
            case .ended, .failed:
                break
            case .ringing, .dialing, .connecting:
                transitionToFailure(message: localizedStatusMessage(for: .unavailable, kind: callKind ?? .audio))
            case .active:
                transitionToEnded(message: localizedStatusMessage(for: .ended, kind: callKind ?? .audio))
            case .idle:
                break
            }
            resetRuntimeState(keepTerminalSnapshot: true)
        }
    }

    private func handleAudioFailure(_ message: String) {
        transportQueue.async {
            self.transitionToFailure(message: message)
            self.disconnectAndReset()
        }
    }

    private func handleVideoFailure(_ message: String) {
        transportQueue.async {
            guard self.callKind == .video else { return }
            self.transitionToFailure(message: message)
            self.disconnectAndReset()
        }
    }

    private func disconnectAndReset() {
        if AppStoreScreenshotSupport.isAnySceneEnabled {
            activePeer = nil
            invitedPeer = nil
            pendingOutgoingCall = false
            pendingIncomingInvite = nil
            lastReceivedAudioSequence = nil
            locallyEndingCall = false
            return
        }
        pendingScreenshotActivationWorkItem?.cancel()
        pendingScreenshotActivationWorkItem = nil
        cancelUnansweredTimeout()
        cancelPendingOfferTimeout()
        cancelRemoteVideoStaleTimeout()
        stopRinging()
        audioPipeline.stop()
        videoPipeline.stop()
        screenshotKeepAlivePlayer.stop()
        session?.disconnect()
        if !eligibleContactsBySessionId.isEmpty {
            rebuildSession()
        } else {
            session?.delegate = nil
            session = nil
        }
        activePeer = nil
        invitedPeer = nil
        pendingOutgoingCall = false
        pendingIncomingInvite = nil
        lastReceivedAudioSequence = nil
        lastReceivedVideoSequence = nil
        shouldPresentFallbackIncomingUI = false
        locallyEndingCall = false
        isMuted = false
        audioPipeline.isMuted = false
        isLocalVideoEnabled = false
        isRemoteVideoAvailable = false
        nextOutgoingVideoSequence = 0
        publishLocalVideoPreview(nil)
        publishRemoteVideoPreview(nil)
    }

    private func resetRuntimeState(keepTerminalSnapshot: Bool = false) {
        pendingScreenshotActivationWorkItem?.cancel()
        pendingScreenshotActivationWorkItem = nil
        cancelUnansweredTimeout()
        cancelPendingOfferTimeout()
        cancelRemoteVideoStaleTimeout()
        pendingOutgoingCall = false
        pendingIncomingInvite = nil
        lastReceivedAudioSequence = nil
        lastReceivedVideoSequence = nil
        locallyEndingCall = false
        activePeer = nil
        invitedPeer = nil
        lastNotifiedIncomingCallId = nil
        shouldPresentFallbackIncomingUI = false
        stopRinging()
        audioPipeline.stop()
        videoPipeline.stop()
        isMuted = false
        audioPipeline.isMuted = false
        isLocalVideoEnabled = false
        isRemoteVideoAvailable = false
        nextOutgoingVideoSequence = 0
        publishLocalVideoPreview(nil)
        publishRemoteVideoPreview(nil)
        if keepTerminalSnapshot {
            publishIncomingPresentation()
            scheduleTerminalReset()
            return
        }
        callSessionId = nil
        callContact = nil
        callDisplayName = ""
        callId = nil
        callKind = nil
        callDirection = nil
        callPhase = .idle
        callStatusMessage = ""
        callStartedAt = nil
        callConnectedAt = nil
        hasPersistedCurrentCallEvent = false
        clearPublishedState()
    }

    private func clearPublishedState() {
        cancelPendingTerminalReset()
        DispatchQueue.main.async {
            self.activeCall = nil
            self.incomingPresentation = nil
            self.localVideoPreviewImage = nil
            self.remoteVideoPreviewImage = nil
        }
    }

    private func setPhase(_ phase: ChatPeerVoiceCallPhase, message: String) {
        callPhase = phase
        callStatusMessage = message
        publishSnapshot()
    }

    private func transitionToEnded(message: String, resultOverride: SOSChatCallResult? = nil) {
        let result = resultOverride ?? derivedCallEventResult()
        recordCurrentCallEventIfNeeded(resultOverride: result)
        systemCallBridge.reportEndedIfNeeded(callId: callId, reason: Self.systemEndedReason(for: result))
        callPhase = .ended(message: message)
        callStatusMessage = message
        publishSnapshot()
        scheduleTerminalReset()
    }

    private func transitionToFailure(message: String, resultOverride: SOSChatCallResult? = nil) {
        let result = resultOverride ?? derivedCallEventResult()
        recordCurrentCallEventIfNeeded(resultOverride: result)
        systemCallBridge.reportEndedIfNeeded(callId: callId, reason: .failed)
        callPhase = .failed(message: message)
        callStatusMessage = message
        publishSnapshot()
        scheduleTerminalReset()
    }

    private func recordCurrentCallEventIfNeeded(resultOverride: SOSChatCallResult? = nil) {
        guard !hasPersistedCurrentCallEvent,
              let sessionId = callSessionId,
              let callId,
              !callId.isEmpty,
              let direction = callDirection else {
            return
        }

        let result = resultOverride ?? derivedCallEventResult()
        let durationMillis: Int?
        if result == .answered, let connectedAt = callConnectedAt {
            durationMillis = max(0, Int(Date().timeIntervalSince(connectedAt) * 1000))
        } else {
            durationMillis = nil
        }

        SOSChatStore.shared.appendCallEvent(
            sessionId: sessionId,
            direction: direction == .incoming ? .incoming : .outgoing,
            result: result,
            durationMillis: durationMillis
        )
        hasPersistedCurrentCallEvent = true
    }

    private func derivedCallEventResult() -> SOSChatCallResult {
        if callConnectedAt != nil {
            return .answered
        }
        switch callDirection {
        case .incoming?:
            return .missed
        case .outgoing?:
            return .canceled
        case nil:
            return .canceled
        }
    }

    private static func systemEndedReason(for result: SOSChatCallResult) -> CXCallEndedReason {
        switch result {
        case .missed:
            return .unanswered
        case .answered, .rejected, .canceled:
            return .remoteEnded
        }
    }

    private func scheduleTerminalReset() {
        cancelPendingTerminalReset()
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.clearPublishedState()
            self.callSessionId = nil
            self.callContact = nil
            self.callDisplayName = ""
            self.callId = nil
            self.callKind = nil
            self.callDirection = nil
            self.callPhase = .idle
            self.callStatusMessage = ""
            self.callStartedAt = nil
            self.callConnectedAt = nil
            self.hasPersistedCurrentCallEvent = false
        }
        pendingTerminalReset = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + ChatPeerVoiceCallConstants.terminalResetDelay, execute: workItem)
    }

    private func cancelPendingTerminalReset() {
        pendingTerminalReset?.cancel()
        pendingTerminalReset = nil
    }

    private func publishSnapshot() {
        let snapshot: ChatPeerVoiceCallSnapshot?
        if let sessionId = callSessionId,
           let callKind = callKind,
           let direction = callDirection,
           let startedAt = callStartedAt {
            snapshot = ChatPeerVoiceCallSnapshot(
                sessionId: sessionId,
                contactName: callDisplayName,
                callKind: callKind,
                phase: callPhase,
                direction: direction,
                statusMessage: callStatusMessage,
                startedAt: startedAt,
                connectedAt: callConnectedAt,
                isMuted: isMuted,
                isSpeakerEnabled: isSpeakerEnabled,
                isLocalVideoEnabled: isLocalVideoEnabled,
                isRemoteVideoAvailable: isRemoteVideoAvailable
            )
        } else {
            snapshot = nil
        }

        if AppStoreScreenshotSupport.isAnySceneEnabled {
            screenshotNowPlaying.update(with: snapshot)
        } else {
            screenshotNowPlaying.clear()
        }

        DispatchQueue.main.async {
            self.activeCall = snapshot
        }
        publishIncomingPresentation()
    }

    private func publishIncomingPresentation() {
        let sessionId = callSessionId
        let callId = self.callId
        let callDirection = self.callDirection
        let callPhase = self.callPhase
        let callDisplayName = self.callDisplayName
        let callKind = self.callKind
        let bridge = systemCallBridge

        DispatchQueue.main.async { [weak self] in
            let presentation: ChatPeerVoiceIncomingPresentation?
            if let sessionId,
               let callId,
               let callKind,
               self?.shouldPresentFallbackIncomingUI == true,
               !bridge.isManagingCall(callId: callId),
               UIApplication.shared.applicationState == .active,
               case .incoming? = callDirection,
               case .ringing = callPhase {
                presentation = ChatPeerVoiceIncomingPresentation(
                    id: callId,
                    sessionId: sessionId,
                    contactName: callDisplayName,
                    callKind: callKind
                )
            } else {
                presentation = nil
            }
            self?.incomingPresentation = presentation
        }
    }

    private func startRingingIfNeeded() {
        guard case .incoming? = callDirection else { return }
        guard case .ringing = callPhase else { return }
        ringtonePlayer.start()
    }

    private func stopRinging() {
        ringtonePlayer.stop()
    }

    private func notifyIncomingCallIfNeeded() {
        guard case .incoming? = callDirection else { return }
        guard case .ringing = callPhase else { return }
        guard let sessionId = callSessionId,
              let callId else { return }
        guard lastNotifiedIncomingCallId != callId else { return }
        lastNotifiedIncomingCallId = callId
        SOSNotificationCenter.notifyIncomingCall(
            sessionId: sessionId,
            title: callDisplayName,
            body: callKind == .video
                ? NSLocalizedString("VIDEO_CALL_NOTIFICATION_BODY", comment: "")
                : NSLocalizedString("VOICE_CALL_NOTIFICATION_BODY", comment: "")
        )
    }

    private func presentIncomingCallOutsideAppIfNeeded() {
        guard case .incoming? = callDirection else { return }
        guard case .ringing = callPhase else { return }
        guard let sessionId = callSessionId,
              let callId else { return }

        stopRinging()
        shouldPresentFallbackIncomingUI = false

        systemCallBridge.reportIncomingCallIfNeeded(
            callId: callId,
            sessionId: sessionId,
            contactName: callDisplayName,
            callKind: callKind ?? .audio
        ) { [weak self] reported in
            guard let self else { return }
            self.transportQueue.async {
                guard self.callId == callId,
                      self.callSessionId == sessionId,
                      case .incoming? = self.callDirection,
                      case .ringing = self.callPhase else {
                    return
                }

                if reported {
                    self.lastNotifiedIncomingCallId = callId
                    self.shouldPresentFallbackIncomingUI = false
                    SOSNotificationCenter.clearIncomingCallNotification(sessionId: sessionId)
                } else {
                    let isAppActive = self.isApplicationActive
                    self.shouldPresentFallbackIncomingUI = isAppActive
                    if isAppActive {
                        self.startRingingIfNeeded()
                    } else {
                        self.notifyIncomingCallIfNeeded()
                    }
                }
                self.publishIncomingPresentation()
            }
        }
    }

    private enum LocalizedStatusSemantic {
        case active
        case dialing
        case connecting
        case ringingOutgoing
        case ringingIncoming
        case ended
        case noAnswer
        case rejected
        case busy
        case missed
        case unavailable
    }

    private func localizedStatusMessage(for semantic: LocalizedStatusSemantic, kind: ChatPeerCallKind) -> String {
        let key: String
        switch (kind, semantic) {
        case (.audio, .active):
            key = "VOICE_CALL_STATUS_ACTIVE"
        case (.audio, .dialing):
            key = "VOICE_CALL_STATUS_DIALING"
        case (.audio, .connecting):
            key = "VOICE_CALL_STATUS_CONNECTING"
        case (.audio, .ringingOutgoing):
            key = "VOICE_CALL_STATUS_RINGING_OUTGOING"
        case (.audio, .ringingIncoming):
            key = "VOICE_CALL_STATUS_RINGING_INCOMING"
        case (.audio, .ended):
            key = "VOICE_CALL_STATUS_ENDED"
        case (.audio, .noAnswer):
            key = "VOICE_CALL_STATUS_NO_ANSWER"
        case (.audio, .rejected):
            key = "VOICE_CALL_STATUS_REJECTED"
        case (.audio, .busy):
            key = "VOICE_CALL_STATUS_BUSY"
        case (.audio, .missed):
            key = "VOICE_CALL_STATUS_MISSED"
        case (.audio, .unavailable):
            key = "VOICE_CALL_STATUS_UNAVAILABLE"
        case (.video, .active):
            key = "VIDEO_CALL_STATUS_ACTIVE"
        case (.video, .dialing):
            key = "VIDEO_CALL_STATUS_DIALING"
        case (.video, .connecting):
            key = "VIDEO_CALL_STATUS_CONNECTING"
        case (.video, .ringingOutgoing):
            key = "VIDEO_CALL_STATUS_RINGING_OUTGOING"
        case (.video, .ringingIncoming):
            key = "VIDEO_CALL_STATUS_RINGING_INCOMING"
        case (.video, .ended):
            key = "VIDEO_CALL_STATUS_ENDED"
        case (.video, .noAnswer):
            key = "VIDEO_CALL_STATUS_NO_ANSWER"
        case (.video, .rejected):
            key = "VIDEO_CALL_STATUS_REJECTED"
        case (.video, .busy):
            key = "VIDEO_CALL_STATUS_BUSY"
        case (.video, .missed):
            key = "VIDEO_CALL_STATUS_MISSED"
        case (.video, .unavailable):
            key = "VIDEO_CALL_STATUS_UNAVAILABLE"
        }
        return NSLocalizedString(key, comment: "")
    }

    private func localizedPermissionRequiredMessage(for kind: ChatPeerCallKind) -> String {
        switch kind {
        case .audio:
            return NSLocalizedString("VOICE_CALL_STATUS_MIC_REQUIRED", comment: "")
        case .video:
            return NSLocalizedString("VIDEO_CALL_STATUS_PERMISSIONS_REQUIRED", comment: "")
        }
    }

    private var isApplicationActive: Bool {
        if Thread.isMainThread {
            return UIApplication.shared.applicationState == .active
        }
        return DispatchQueue.main.sync {
            UIApplication.shared.applicationState == .active
        }
    }
}

extension ChatPeerVoiceCallCoordinator: MCNearbyServiceBrowserDelegate {
    func browser(
        _ browser: MCNearbyServiceBrowser,
        foundPeer peerID: MCPeerID,
        withDiscoveryInfo info: [String : String]?
    ) {
        guard let sessionCode = info?["sc"]?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              !sessionCode.isEmpty else {
            return
        }
        transportQueue.async {
            self.discoveredPeers[peerID] = ChatPeerVoiceDiscoveredPeer(
                sessionCode: sessionCode,
                displayName: {
                    let trimmed = info?["n"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    return trimmed.isEmpty ? nil : trimmed
                }()
            )

            guard self.pendingOutgoingCall,
                  let contact = self.callContact,
                  let targetSessionCode = Self.targetSessionCode(for: contact),
                  self.invitedPeer == nil else {
                return
            }
            guard case .dialing = self.callPhase else { return }
            if sessionCode == targetSessionCode, let callId = self.callId {
                self.invite(peer: peerID, targetSessionCode: targetSessionCode, callId: callId, callKind: self.callKind ?? .audio)
            }
        }
    }

    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        transportQueue.async {
            self.discoveredPeers.removeValue(forKey: peerID)
        }
    }

    func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        guard !AppStoreScreenshotSupport.isAnySceneEnabled else { return }
        transitionToFailure(message: localizedStatusMessage(for: .unavailable, kind: callKind ?? .audio))
    }
}

extension ChatPeerVoiceCallCoordinator: MCNearbyServiceAdvertiserDelegate {
    func advertiser(
        _ advertiser: MCNearbyServiceAdvertiser,
        didReceiveInvitationFromPeer peerID: MCPeerID,
        withContext context: Data?,
        invitationHandler: @escaping (Bool, MCSession?) -> Void
    ) {
        handleIncomingInvitation(from: peerID, contextData: context, invitationHandler: invitationHandler)
    }

    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        guard !AppStoreScreenshotSupport.isAnySceneEnabled else { return }
        transitionToFailure(message: localizedStatusMessage(for: .unavailable, kind: callKind ?? .audio))
    }
}

extension ChatPeerVoiceCallCoordinator: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        transportQueue.async {
            switch state {
            case .connected:
                self.handleConnectedPeer(peerID)
            case .connecting:
                if self.callId != nil {
                    self.setPhase(.connecting, message: self.localizedStatusMessage(for: .connecting, kind: self.callKind ?? .audio))
                }
            case .notConnected:
                self.handleDisconnectedPeer(peerID)
            @unknown default:
                break
            }
        }
    }

    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        transportQueue.async {
            self.handleReceivedData(data, from: peerID)
        }
    }

    func session(
        _ session: MCSession,
        didReceive stream: InputStream,
        withName streamName: String,
        fromPeer peerID: MCPeerID
    ) {}

    func session(
        _ session: MCSession,
        didStartReceivingResourceWithName resourceName: String,
        fromPeer peerID: MCPeerID,
        with progress: Progress
    ) {}

    func session(
        _ session: MCSession,
        didFinishReceivingResourceWithName resourceName: String,
        fromPeer peerID: MCPeerID,
        at localURL: URL?,
        withError error: Error?
    ) {}

    func session(
        _ session: MCSession,
        didReceiveCertificate certificate: [Any]?,
        fromPeer peerID: MCPeerID,
        certificateHandler: @escaping (Bool) -> Void
    ) {
        certificateHandler(true)
    }
}

private final class ChatPeerVoiceScreenshotNowPlayingBridge {
    func update(with snapshot: ChatPeerVoiceCallSnapshot?) {
        guard let snapshot,
              snapshot.phase.isLivePresentationPhase else {
            clear()
            return
        }

        // Use the contact name as the title so Control Center shows
        // "ContactName – Crisis Connect" which looks like a voice call
        // rather than a media/video stream.
        let appName = (Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedAppName = (appName?.isEmpty == false) ? appName! : "Crisis Connect"

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: snapshot.contactName,
            MPMediaItemPropertyArtist: resolvedAppName,
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: snapshot.connectedAt == nil ? 0.0 : 1.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: snapshot.connectedAt.map {
                max(0, Date().timeIntervalSince($0))
            } ?? 0.0
        ]

        if snapshot.connectedAt == nil {
            info[MPMediaItemPropertyPlaybackDuration] = 0
        }

        DispatchQueue.main.async {
            UIApplication.shared.beginReceivingRemoteControlEvents()
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    func clear() {
        DispatchQueue.main.async {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            UIApplication.shared.endReceivingRemoteControlEvents()
        }
    }
}

final class ChatPeerVoiceCallController: ObservableObject {
    @Published private(set) var phase: ChatPeerVoiceCallPhase = .idle
    @Published private(set) var isEligibleForCurrentContact = false
    @Published private(set) var statusMessage = ""
    @Published private(set) var callKind: ChatPeerCallKind = .audio
    @Published private(set) var isMuted = false
    @Published private(set) var isSpeakerEnabled = true
    @Published private(set) var isLocalVideoEnabled = false
    @Published private(set) var isRemoteVideoAvailable = false
    @Published private(set) var direction: ChatPeerVoiceCallDirection?
    @Published private(set) var connectedAt: Date?
    @Published private(set) var localPreviewImage: UIImage?
    @Published private(set) var remotePreviewImage: UIImage?

    let sessionId: UUID

    private let coordinator: ChatPeerVoiceCallCoordinator
    private var contact: ContactRecord?
    private var cancellables = Set<AnyCancellable>()

    init(sessionId: UUID, coordinator: ChatPeerVoiceCallCoordinator = .shared) {
        self.sessionId = sessionId
        self.coordinator = coordinator

        coordinator.$activeCall
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.refreshState()
            }
            .store(in: &cancellables)

        coordinator.$localVideoPreviewImage
            .receive(on: DispatchQueue.main)
            .sink { [weak self] image in
                guard let self else { return }
                if self.isCallForCurrentSession && self.callKind == .video {
                    self.localPreviewImage = image
                } else {
                    self.localPreviewImage = nil
                }
            }
            .store(in: &cancellables)

        coordinator.$remoteVideoPreviewImage
            .receive(on: DispatchQueue.main)
            .sink { [weak self] image in
                guard let self else { return }
                if self.isCallForCurrentSession && self.callKind == .video {
                    self.remotePreviewImage = image
                } else {
                    self.remotePreviewImage = nil
                }
            }
            .store(in: &cancellables)
    }

    var toolbarSystemImage: String {
        if isCallForCurrentSession {
            switch phase {
            case .dialing, .ringing, .connecting, .active:
                return "phone.down.fill"
            case .idle, .ended, .failed:
                return "phone.fill"
            }
        }
        return "phone.fill"
    }

    var videoToolbarSystemImage: String {
        if isCallForCurrentSession {
            return "phone.down.fill"
        }
        return "video.fill"
    }

    var toolbarAccessibilityLabel: String {
        if isCallForCurrentSession {
            switch phase {
            case .dialing, .ringing, .connecting, .active:
                return NSLocalizedString("VOICE_CALL_END_ACTION", comment: "")
            case .idle, .ended, .failed:
                break
            }
        }
        return NSLocalizedString("VOICE_CALL_ACTION", comment: "")
    }

    var videoToolbarAccessibilityLabel: String {
        if isCallForCurrentSession {
            return NSLocalizedString("VOICE_CALL_END_ACTION", comment: "")
        }
        return NSLocalizedString("VIDEO_CALL_ACTION", comment: "")
    }

    var toolbarTintColor: Color {
        if isCallForCurrentSession {
            switch phase {
            case .dialing, .ringing, .connecting, .active:
                return .red
            case .idle, .ended, .failed:
                break
            }
        }
        return .appPrimary
    }

    var shouldShowPanel: Bool {
        isCallForCurrentSession && phase != .idle
    }

    var shouldPresentFullScreenExperience: Bool {
        if callKind != .video {
            guard !coordinator.isSystemManagingCall(sessionId: sessionId) else { return false }
        }
        guard isCallForCurrentSession else { return false }
        switch phase {
        case .dialing, .connecting, .active:
            return true
        case .ringing:
            return direction == .outgoing
        case .idle, .ended, .failed:
            return false
        }
    }

    var canToggleMute: Bool {
        isCallForCurrentSession && phase == .active
    }

    var canToggleSpeaker: Bool {
        isCallForCurrentSession && phase == .active
    }

    var canToggleVideo: Bool {
        isCallForCurrentSession && callKind == .video && phase == .active
    }

    var isIncomingRinging: Bool {
        isCallForCurrentSession && direction == .incoming && phase == .ringing
    }

    var isCallForCurrentSession: Bool {
        coordinator.activeCall?.sessionId == sessionId
    }

    var isVideoCall: Bool {
        isCallForCurrentSession && callKind == .video
    }

    var shouldShowToolbarAction: Bool {
        isEligibleForCurrentContact || isCallForCurrentSession
    }

    var shouldShowToolbarVideoAction: Bool {
        isEligibleForCurrentContact || (isCallForCurrentSession && callKind == .video)
    }

    func updateContact(_ nextContact: ContactRecord?) {
        contact = nextContact
        isEligibleForCurrentContact = ChatPeerVoiceCallCoordinator.isEligibleContact(nextContact)
        coordinator.bootstrap()
        refreshState()
    }

    func toolbarPrimaryAction() {
        if isCallForCurrentSession {
            switch phase {
            case .dialing, .ringing, .connecting, .active:
                coordinator.endCall()
            case .idle, .ended, .failed:
                if let contact {
                    coordinator.placeCall(sessionId: sessionId, contact: contact, kind: .audio)
                }
            }
            return
        }

        guard let contact else { return }
        coordinator.placeCall(sessionId: sessionId, contact: contact, kind: .audio)
    }

    func toolbarVideoAction() {
        if isCallForCurrentSession {
            coordinator.endCall()
            return
        }

        guard let contact else { return }
        coordinator.placeCall(sessionId: sessionId, contact: contact, kind: .video)
    }

    func dismissBanner() {
        coordinator.dismissTerminalState(for: sessionId)
    }

    func toggleMute() {
        guard canToggleMute else { return }
        coordinator.toggleMute()
    }

    func toggleSpeaker() {
        guard canToggleSpeaker else { return }
        coordinator.toggleSpeaker()
    }

    func toggleVideo() {
        guard canToggleVideo else { return }
        coordinator.toggleLocalVideo()
    }

    func answerIncomingCall() {
        coordinator.answerCall(sessionId: sessionId)
    }

    func rejectIncomingCall() {
        coordinator.rejectIncomingCall(sessionId: sessionId)
    }

    func endCall() {
        coordinator.endCall()
    }

    func stop() {}

    private func refreshState() {
        guard let snapshot = coordinator.activeCall,
              snapshot.sessionId == sessionId else {
            phase = .idle
            statusMessage = ""
            callKind = .audio
            isMuted = false
            isSpeakerEnabled = true
            isLocalVideoEnabled = false
            isRemoteVideoAvailable = false
            direction = nil
            connectedAt = nil
            localPreviewImage = nil
            remotePreviewImage = nil
            return
        }

        phase = snapshot.phase
        statusMessage = snapshot.statusMessage
        callKind = snapshot.callKind
        isMuted = snapshot.isMuted
        isSpeakerEnabled = snapshot.isSpeakerEnabled
        isLocalVideoEnabled = snapshot.isLocalVideoEnabled
        isRemoteVideoAvailable = snapshot.isRemoteVideoAvailable
        direction = snapshot.direction
        connectedAt = snapshot.connectedAt
        if snapshot.callKind != .video {
            localPreviewImage = nil
            remotePreviewImage = nil
        }
    }
}

private final class ChatPeerVideoCapturePipeline: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onEncodedFrame: ((Data) -> Void)?
    var onPreviewImage: ((UIImage?) -> Void)?
    var onFailure: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let captureQueue = DispatchQueue(label: "chat.peer.voice.video.capture")
    private let processingQueue = DispatchQueue(label: "chat.peer.voice.video.processing", qos: .userInitiated)
    private let ciContext = CIContext()

    private var isConfigured = false
    private var activeCameraPosition: AVCaptureDevice.Position = .front
    private var lastFrameTimestamp: TimeInterval = 0

    func start() throws {
        try configureIfNeeded()
        lastFrameTimestamp = 0
        guard !session.isRunning else { return }
        captureQueue.sync {
            self.session.startRunning()
        }
    }

    func stop() {
        if session.isRunning {
            captureQueue.sync {
                self.session.stopRunning()
            }
        }
        lastFrameTimestamp = 0
        DispatchQueue.main.async {
            self.onPreviewImage?(nil)
        }
    }

    private func configureIfNeeded() throws {
        guard !isConfigured else { return }

        session.beginConfiguration()
        if session.canSetSessionPreset(.vga640x480) {
            session.sessionPreset = .vga640x480
        }

        guard let device = preferredCamera() else {
            session.commitConfiguration()
            throw NSError(domain: "ChatPeerVideoCapturePipeline", code: -1, userInfo: nil)
        }

        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else {
            session.commitConfiguration()
            throw NSError(domain: "ChatPeerVideoCapturePipeline", code: -2, userInfo: nil)
        }
        session.addInput(input)

        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        output.setSampleBufferDelegate(self, queue: processingQueue)
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            throw NSError(domain: "ChatPeerVideoCapturePipeline", code: -3, userInfo: nil)
        }
        session.addOutput(output)

        if let connection = output.connection(with: .video) {
            if connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
            if connection.isVideoMirroringSupported, device.position == .front {
                connection.isVideoMirrored = true
            }
        }

        activeCameraPosition = device.position
        session.commitConfiguration()
        isConfigured = true
    }

    private func preferredCamera() -> AVCaptureDevice? {
        if let frontCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) {
            return frontCamera
        }
        if let backCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            return backCamera
        }
        return AVCaptureDevice.default(for: .video)
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = CFAbsoluteTimeGetCurrent()
        guard now - lastFrameTimestamp >= ChatPeerVoiceCallConstants.videoFrameInterval else {
            return
        }
        lastFrameTimestamp = now

        autoreleasepool {
            guard let image = makePreviewImage(from: sampleBuffer) else { return }
            DispatchQueue.main.async {
                self.onPreviewImage?(image)
            }
            guard let jpegData = image.jpegData(compressionQuality: ChatPeerVoiceCallConstants.videoCompressionQuality) else {
                self.onFailure?(NSLocalizedString("VIDEO_CALL_STATUS_UNAVAILABLE", comment: ""))
                return
            }
            onEncodedFrame?(jpegData)
        }
    }

    private func makePreviewImage(from sampleBuffer: CMSampleBuffer) -> UIImage? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return nil
        }

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let extent = ciImage.extent
        guard extent.width > 0, extent.height > 0 else {
            return nil
        }

        let scale = min(1, ChatPeerVoiceCallConstants.videoPreviewMaxDimension / max(extent.width, extent.height))
        let scaledImage = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cgImage = ciContext.createCGImage(scaledImage, from: scaledImage.extent) else {
            return nil
        }

        let orientation: UIImage.Orientation = activeCameraPosition == .front ? .leftMirrored : .right
        return UIImage(cgImage: cgImage, scale: 1, orientation: orientation)
    }
}

private final class ChatPeerVoiceAudioPipeline {
    var onEncodedPacket: ((Data) -> Void)?
    var onFailure: ((String) -> Void)?
    var isMuted = false

    private let processingQueue = DispatchQueue(label: "chat.peer.voice.audio", qos: .userInitiated)
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let pcmFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: ChatPeerVoiceCallConstants.sampleRate,
        channels: ChatPeerVoiceCallConstants.channelCount,
        interleaved: false
    )!
    private let opusFormat = AVAudioFormat(settings: [
        AVFormatIDKey: kAudioFormatOpus,
        AVSampleRateKey: ChatPeerVoiceCallConstants.sampleRate,
        AVNumberOfChannelsKey: ChatPeerVoiceCallConstants.channelCount
    ])!

    private var inputConverter: AVAudioConverter?
    private var encoder: AVAudioConverter?
    private var decoder: AVAudioConverter?
    private var pendingCaptureSamples: [Float] = []
    private var nextSequence: UInt32 = 0
    private var hasInstalledTap = false
    private var engineConfigured = false
    private var isSpeakerEnabled = true
    private var notificationObservers: [NSObjectProtocol] = []

    init() {
        notificationObservers = [
            NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                self?.handleInterruption(notification)
            },
            NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.applyOutputRoute()
            },
            NotificationCenter.default.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.restartAfterMediaServicesReset()
            }
        ]
    }

    deinit {
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func start() throws {
        try configureAudioSession()
        try configureEngineIfNeeded()
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
        if !playerNode.isPlaying {
            playerNode.play()
        }
    }

    func stop() {
        processingQueue.sync {
            self.pendingCaptureSamples.removeAll(keepingCapacity: false)
            self.nextSequence = 0
        }
        if hasInstalledTap {
            engine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }
        playerNode.stop()
        engine.stop()
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.overrideOutputAudioPort(.none)
        try? audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        isSpeakerEnabled = enabled
        applyOutputRoute()
    }

    func nextOutgoingSequence() -> UInt32 {
        defer { nextSequence &+= 1 }
        return nextSequence
    }

    func enqueueIncomingOpusPacket(_ payload: Data) {
        processingQueue.async {
            self.decodeAndSchedule(payload)
        }
    }

    private func configureAudioSession() throws {
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetoothA2DP, .allowBluetoothHFP]
        )
        try audioSession.setPreferredSampleRate(ChatPeerVoiceCallConstants.sampleRate)
        try audioSession.setPreferredIOBufferDuration(0.02)
        try audioSession.setActive(true, options: [])
        applyOutputRoute()
    }

    private func applyOutputRoute() {
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.overrideOutputAudioPort(isSpeakerEnabled ? .speaker : .none)
        } catch {
            onFailure?(NSLocalizedString("VOICE_CALL_STATUS_UNAVAILABLE", comment: ""))
        }
    }

    private func configureEngineIfNeeded() throws {
        let inputFormat = engine.inputNode.inputFormat(forBus: 0)
        inputConverter = AVAudioConverter(from: inputFormat, to: pcmFormat)
        encoder = AVAudioConverter(from: pcmFormat, to: opusFormat)
        decoder = AVAudioConverter(from: opusFormat, to: pcmFormat)

        if !engineConfigured {
            engine.attach(playerNode)
            engine.connect(playerNode, to: engine.mainMixerNode, format: pcmFormat)
            engineConfigured = true
        }

        if !hasInstalledTap {
            engine.inputNode.installTap(
                onBus: 0,
                bufferSize: ChatPeerVoiceCallConstants.tapBufferSize,
                format: inputFormat
            ) { [weak self] buffer, _ in
                guard let self else { return }
                guard let copied = Self.copyBuffer(buffer) else { return }
                self.processingQueue.async {
                    self.processCapturedBuffer(copied)
                }
            }
            hasInstalledTap = true
        }
    }

    private func processCapturedBuffer(_ buffer: AVAudioPCMBuffer) {
        guard !isMuted else { return }
        let convertedBuffers = convertInputBuffer(buffer)
        for converted in convertedBuffers {
            appendSamples(from: converted)
        }
        emitReadyPacketsIfNeeded()
    }

    private func convertInputBuffer(_ buffer: AVAudioPCMBuffer) -> [AVAudioPCMBuffer] {
        guard let inputConverter else { return [] }
        var sourceConsumed = false
        var outputs: [AVAudioPCMBuffer] = []

        while true {
            guard let outputBuffer = AVAudioPCMBuffer(
                pcmFormat: pcmFormat,
                frameCapacity: ChatPeerVoiceCallConstants.framesPerPacket * 4
            ) else {
                return outputs
            }

            var conversionError: NSError?
            let status = inputConverter.convert(to: outputBuffer, error: &conversionError) { _, outStatus in
                if sourceConsumed {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                sourceConsumed = true
                outStatus.pointee = .haveData
                return buffer
            }

            if let conversionError {
                onFailure?(conversionError.localizedDescription)
                return outputs
            }

            switch status {
            case .haveData:
                if outputBuffer.frameLength > 0 {
                    outputs.append(outputBuffer)
                }
            case .inputRanDry, .endOfStream:
                if outputBuffer.frameLength > 0 {
                    outputs.append(outputBuffer)
                }
                return outputs
            case .error:
                onFailure?(NSLocalizedString("VOICE_CALL_STATUS_UNAVAILABLE", comment: ""))
                return outputs
            @unknown default:
                return outputs
            }
        }
    }

    private func appendSamples(from buffer: AVAudioPCMBuffer) {
        guard let samples = buffer.floatChannelData?[0] else { return }
        let frameCount = Int(buffer.frameLength)
        pendingCaptureSamples.append(contentsOf: UnsafeBufferPointer(start: samples, count: frameCount))
    }

    private func emitReadyPacketsIfNeeded() {
        let frameCount = Int(ChatPeerVoiceCallConstants.framesPerPacket)
        while pendingCaptureSamples.count >= frameCount {
            let frameSamples = Array(pendingCaptureSamples.prefix(frameCount))
            pendingCaptureSamples.removeFirst(frameCount)
            guard let packet = encodeFrame(frameSamples) else { continue }
            onEncodedPacket?(packet)
        }
    }

    private func encodeFrame(_ samples: [Float]) -> Data? {
        guard let encoder,
              let pcmBuffer = AVAudioPCMBuffer(
                pcmFormat: pcmFormat,
                frameCapacity: ChatPeerVoiceCallConstants.framesPerPacket
              ) else {
            return nil
        }
        pcmBuffer.frameLength = ChatPeerVoiceCallConstants.framesPerPacket
        guard let destination = pcmBuffer.floatChannelData?[0] else { return nil }
        destination.update(from: samples, count: samples.count)

        let outputBuffer = AVAudioCompressedBuffer(
            format: opusFormat,
            packetCapacity: 1,
            maximumPacketSize: ChatPeerVoiceCallConstants.maxPacketSize
        )
        var sourceProvided = false
        var conversionError: NSError?
        let status = encoder.convert(to: outputBuffer, error: &conversionError) { _, outStatus in
            if sourceProvided {
                outStatus.pointee = .noDataNow
                return nil
            }
            sourceProvided = true
            outStatus.pointee = .haveData
            return pcmBuffer
        }

        if let conversionError {
            onFailure?(conversionError.localizedDescription)
            return nil
        }

        guard status == .haveData,
              outputBuffer.byteLength > 0 else {
            return nil
        }
        return Data(bytes: outputBuffer.data, count: Int(outputBuffer.byteLength))
    }

    private func decodeAndSchedule(_ payload: Data) {
        guard let decoder else { return }
        let compressedBuffer = AVAudioCompressedBuffer(
            format: opusFormat,
            packetCapacity: 1,
            maximumPacketSize: max(1, payload.count)
        )
        payload.copyBytes(
            to: compressedBuffer.data.assumingMemoryBound(to: UInt8.self),
            count: payload.count
        )
        compressedBuffer.byteLength = UInt32(payload.count)
        compressedBuffer.packetCount = 1
        compressedBuffer.packetDescriptions?[0] = AudioStreamPacketDescription(
            mStartOffset: 0,
            mVariableFramesInPacket: 0,
            mDataByteSize: UInt32(payload.count)
        )

        guard let decodedBuffer = AVAudioPCMBuffer(
            pcmFormat: pcmFormat,
            frameCapacity: ChatPeerVoiceCallConstants.framesPerPacket * 4
        ) else {
            return
        }
        var sourceProvided = false
        var conversionError: NSError?
        let status = decoder.convert(to: decodedBuffer, error: &conversionError) { _, outStatus in
            if sourceProvided {
                outStatus.pointee = .noDataNow
                return nil
            }
            sourceProvided = true
            outStatus.pointee = .haveData
            return compressedBuffer
        }

        if let conversionError {
            onFailure?(conversionError.localizedDescription)
            return
        }

        guard status == .haveData, decodedBuffer.frameLength > 0 else { return }
        playerNode.scheduleBuffer(decodedBuffer, completionHandler: nil)
        if !playerNode.isPlaying {
            playerNode.play()
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            playerNode.pause()
        case .ended:
            do {
                try configureAudioSession()
                if !engine.isRunning {
                    try engine.start()
                }
                if !playerNode.isPlaying {
                    playerNode.play()
                }
            } catch {
                onFailure?(NSLocalizedString("VOICE_CALL_STATUS_UNAVAILABLE", comment: ""))
            }
        @unknown default:
            break
        }
    }

    private func restartAfterMediaServicesReset() {
        do {
            try configureAudioSession()
            try configureEngineIfNeeded()
            if !engine.isRunning {
                try engine.start()
            }
            if !playerNode.isPlaying {
                playerNode.play()
            }
        } catch {
            onFailure?(NSLocalizedString("VOICE_CALL_STATUS_UNAVAILABLE", comment: ""))
        }
    }

    private static func copyBuffer(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
            return nil
        }
        copy.frameLength = buffer.frameLength
        let frameCount = Int(buffer.frameLength)
        let channelCount = Int(buffer.format.channelCount)

        switch buffer.format.commonFormat {
        case .pcmFormatFloat32:
            guard let source = buffer.floatChannelData, let destination = copy.floatChannelData else { return nil }
            for channel in 0..<channelCount {
                destination[channel].update(from: source[channel], count: frameCount)
            }
        case .pcmFormatInt16:
            guard let source = buffer.int16ChannelData, let destination = copy.int16ChannelData else { return nil }
            for channel in 0..<channelCount {
                destination[channel].update(from: source[channel], count: frameCount)
            }
        case .pcmFormatInt32:
            guard let source = buffer.int32ChannelData, let destination = copy.int32ChannelData else { return nil }
            for channel in 0..<channelCount {
                destination[channel].update(from: source[channel], count: frameCount)
            }
        default:
            return nil
        }
        return copy
    }
}

private final class ChatPeerVoiceRingtonePlayer: NSObject, AVAudioPlayerDelegate {
    private var audioPlayer: AVAudioPlayer?
    private var vibrationTimer: Timer?

    func start() {
        DispatchQueue.main.async {
            guard self.audioPlayer?.isPlaying != true else { return }
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.duckOthers])
                try session.setActive(true, options: [])

                let url = try Self.ringtoneURL()
                let player = try AVAudioPlayer(contentsOf: url)
                player.numberOfLoops = -1
                player.delegate = self
                player.prepareToPlay()
                player.play()
                self.audioPlayer = player
                self.startVibrationLoop()
            } catch {
                self.audioPlayer = nil
            }
        }
    }

    func stop() {
        DispatchQueue.main.async {
            self.audioPlayer?.stop()
            self.audioPlayer = nil
            self.vibrationTimer?.invalidate()
            self.vibrationTimer = nil
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }

    private func startVibrationLoop() {
        vibrationTimer?.invalidate()
        vibrationTimer = Timer.scheduledTimer(withTimeInterval: 2.2, repeats: true) { _ in
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    private static func ringtoneURL() throws -> URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("CrisisConnect", isDirectory: true)
        if !FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }

        let url = directory.appendingPathComponent("voice_call_ring.wav")
        if FileManager.default.fileExists(atPath: url.path) {
            return url
        }

        let sampleRate = 22_050
        let durationSeconds = 2
        let totalSamples = sampleRate * durationSeconds
        var pcmData = Data(capacity: totalSamples * MemoryLayout<Int16>.size)

        for sampleIndex in 0..<totalSamples {
            let time = Double(sampleIndex) / Double(sampleRate)
            let envelope: Double
            let frequency: Double

            switch time {
            case 0..<0.28:
                envelope = 0.45
                frequency = 660
            case 0.28..<0.40:
                envelope = 0
                frequency = 0
            case 0.40..<0.68:
                envelope = 0.45
                frequency = 880
            default:
                envelope = 0
                frequency = 0
            }

            let value = envelope == 0
                ? 0
                : Int16((sin(2 * .pi * frequency * time) * envelope * Double(Int16.max)).rounded())
            var littleEndianValue = value.littleEndian
            withUnsafeBytes(of: &littleEndianValue) { pcmData.append(contentsOf: $0) }
        }

        let header = Self.wavHeader(
            sampleRate: sampleRate,
            channels: 1,
            bitsPerSample: 16,
            dataSize: pcmData.count
        )
        var fileData = Data()
        fileData.append(header)
        fileData.append(pcmData)
        try fileData.write(to: url, options: .atomic)
        return url
    }

    private static func wavHeader(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Int
    ) -> Data {
        let byteRate = sampleRate * channels * bitsPerSample / 8
        let blockAlign = channels * bitsPerSample / 8
        let fileSize = 36 + dataSize

        var data = Data()
        data.append("RIFF".data(using: .ascii)!)
        data.append(littleEndianBytes(UInt32(fileSize)))
        data.append("WAVE".data(using: .ascii)!)
        data.append("fmt ".data(using: .ascii)!)
        data.append(littleEndianBytes(UInt32(16)))
        data.append(littleEndianBytes(UInt16(1)))
        data.append(littleEndianBytes(UInt16(channels)))
        data.append(littleEndianBytes(UInt32(sampleRate)))
        data.append(littleEndianBytes(UInt32(byteRate)))
        data.append(littleEndianBytes(UInt16(blockAlign)))
        data.append(littleEndianBytes(UInt16(bitsPerSample)))
        data.append("data".data(using: .ascii)!)
        data.append(littleEndianBytes(UInt32(dataSize)))
        return data
    }

    private static func littleEndianBytes<T: FixedWidthInteger>(_ value: T) -> Data {
        var littleEndianValue = value.littleEndian
        return withUnsafeBytes(of: &littleEndianValue) { Data($0) }
    }
}

private final class ChatPeerVoiceScreenshotKeepAlivePlayer: NSObject, AVAudioPlayerDelegate {
    private var audioPlayer: AVAudioPlayer?
    private var isSpeakerEnabled = true

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.defaultToSpeaker, .allowBluetooth]
            )
        } catch {
            try session.setCategory(
                .playback,
                mode: .default,
                options: [.mixWithOthers]
            )
        }
        try session.setActive(true, options: [])

        if audioPlayer?.isPlaying == true {
            return
        }

        let player = try AVAudioPlayer(contentsOf: try Self.silentLoopURL())
        player.numberOfLoops = -1
        player.volume = 0.2
        player.delegate = self
        player.prepareToPlay()
        player.play()
        audioPlayer = player
    }

    func stop() {
        audioPlayer?.stop()
        audioPlayer = nil
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(.none)
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        isSpeakerEnabled = enabled
        try? applyOutputRoute()
    }

    private func applyOutputRoute() throws {
        let _ = isSpeakerEnabled
    }

    private static func silentLoopURL() throws -> URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("CrisisConnect", isDirectory: true)
        if !FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }

        let url = directory.appendingPathComponent("voice_call_keepalive.wav")
        if FileManager.default.fileExists(atPath: url.path) {
            return url
        }

        let sampleRate = 8_000
        let durationSeconds = 1
        let totalSamples = sampleRate * durationSeconds
        let bitsPerSample = 16
        let channelCount = 1
        let bytesPerSample = bitsPerSample / 8
        let dataSize = totalSamples * channelCount * bytesPerSample
        let byteRate = sampleRate * channelCount * bytesPerSample
        let blockAlign = channelCount * bytesPerSample

        var fileData = Data()
        fileData.append(Data("RIFF".utf8))
        fileData.append(Self.littleEndianBytes(UInt32(36 + dataSize)))
        fileData.append(Data("WAVE".utf8))
        fileData.append(Data("fmt ".utf8))
        fileData.append(Self.littleEndianBytes(UInt32(16)))
        fileData.append(Self.littleEndianBytes(UInt16(1)))
        fileData.append(Self.littleEndianBytes(UInt16(channelCount)))
        fileData.append(Self.littleEndianBytes(UInt32(sampleRate)))
        fileData.append(Self.littleEndianBytes(UInt32(byteRate)))
        fileData.append(Self.littleEndianBytes(UInt16(blockAlign)))
        fileData.append(Self.littleEndianBytes(UInt16(bitsPerSample)))
        fileData.append(Data("data".utf8))
        fileData.append(Self.littleEndianBytes(UInt32(dataSize)))
        fileData.append(Data(count: dataSize))

        try fileData.write(to: url, options: .atomic)
        return url
    }

    private static func littleEndianBytes<T: FixedWidthInteger>(_ value: T) -> Data {
        var littleEndianValue = value.littleEndian
        return withUnsafeBytes(of: &littleEndianValue) { Data($0) }
    }
}

struct ChatPeerVoiceCallPanel: View {
    @ObservedObject var controller: ChatPeerVoiceCallController
    let contactName: String

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: iconName)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(iconColor)
                    .frame(width: 36, height: 36)
                    .background(iconColor.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text(contactName)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    ChatPeerVoiceCallStatusText(
                        phase: controller.phase,
                        statusMessage: controller.statusMessage,
                        connectedAt: controller.connectedAt
                    )
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer(minLength: 8)

                if dismissable {
                    Button(action: controller.dismissBanner) {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                }
            }

            if controller.isIncomingRinging {
                HStack(spacing: 12) {
                    Button(action: controller.rejectIncomingCall) {
                        Label(LocalizedStringKey("VOICE_CALL_REJECT_ACTION"), systemImage: "phone.down.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)

                    Button(action: controller.answerIncomingCall) {
                        Label(LocalizedStringKey("VOICE_CALL_ANSWER_ACTION"), systemImage: answerSystemImage)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                }
            } else if actionRowVisible {
                HStack(spacing: 12) {
                    if controller.canToggleMute {
                        Button(action: controller.toggleMute) {
                            Label(
                                controller.isMuted
                                    ? LocalizedStringKey("VOICE_CALL_UNMUTE")
                                    : LocalizedStringKey("VOICE_CALL_MUTE"),
                                systemImage: controller.isMuted ? "mic.slash.fill" : "mic.fill"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }

                    if controller.canToggleSpeaker {
                        Button(action: controller.toggleSpeaker) {
                            Label(
                                controller.isSpeakerEnabled
                                    ? LocalizedStringKey("VOICE_CALL_EARPIECE")
                                    : LocalizedStringKey("VOICE_CALL_SPEAKER"),
                                systemImage: controller.isSpeakerEnabled ? "speaker.slash.fill" : "speaker.wave.2.fill"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }

                    if controller.canToggleVideo {
                        Button(action: controller.toggleVideo) {
                            Label(
                                controller.isLocalVideoEnabled
                                    ? LocalizedStringKey("VIDEO_CALL_DISABLE_CAMERA")
                                    : LocalizedStringKey("VIDEO_CALL_ENABLE_CAMERA"),
                                systemImage: controller.isLocalVideoEnabled ? "video.fill" : "video.slash.fill"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }

                    Button(action: controller.endCall) {
                        Label(LocalizedStringKey("VOICE_CALL_END_ACTION"), systemImage: "phone.down.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 1)
        )
    }

    private var dismissable: Bool {
        switch controller.phase {
        case .ended, .failed:
            return true
        case .idle, .dialing, .ringing, .connecting, .active:
            return false
        }
    }

    private var actionRowVisible: Bool {
        switch controller.phase {
        case .dialing, .ringing, .connecting, .active:
            return !controller.isIncomingRinging
        case .idle, .ended, .failed:
            return false
        }
    }

    private var iconName: String {
        switch controller.phase {
        case .dialing:
            return controller.callKind == .video ? "video.badge.ellipsis.fill" : "phone.arrow.up.right.fill"
        case .ringing:
            if controller.direction == .incoming {
                return controller.callKind == .video ? "video.fill" : "phone.arrow.down.left.fill"
            }
            return controller.callKind == .video ? "video.badge.waveform.fill" : "bell.and.waveform.fill"
        case .connecting:
            return controller.callKind == .video ? "video.bubble.left.fill" : "dot.radiowaves.left.and.right"
        case .active:
            return controller.callKind == .video ? "video.fill" : "waveform"
        case .ended:
            return "phone.down.fill"
        case .failed:
            return "exclamationmark.triangle.fill"
        case .idle:
            return controller.callKind == .video ? "video.fill" : "phone.fill"
        }
    }

    private var answerSystemImage: String {
        controller.callKind == .video ? "video.fill" : "phone.fill"
    }

    private var iconColor: Color {
        switch controller.phase {
        case .ended:
            return .orange
        case .failed:
            return .red
        case .dialing, .ringing, .connecting, .active, .idle:
            return .green
        }
    }
}

private struct ChatPeerVoiceCallStatusText: View {
    let phase: ChatPeerVoiceCallPhase
    let statusMessage: String
    let connectedAt: Date?

    var body: some View {
        if phase == .active, let connectedAt {
            TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                Text(verbatim: statusLine(referenceDate: context.date))
            }
        } else {
            Text(verbatim: statusMessage)
        }
    }

    private func statusLine(referenceDate: Date) -> String {
        guard phase == .active, let connectedAt else {
            return statusMessage
        }
        return "\(statusMessage) • \(formattedCallDuration(since: connectedAt, referenceDate: referenceDate))"
    }
}

private struct ChatPeerVoiceCallActionButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    let tint: Color
    let isProminent: Bool
    let isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(foregroundColor)
                    .frame(width: 72, height: 72)
                    .background(backgroundColor, in: Circle())

                Text(title)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white.opacity(isEnabled ? 0.88 : 0.42))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }

    private var backgroundColor: some ShapeStyle {
        if isProminent {
            return AnyShapeStyle(tint.opacity(isEnabled ? 0.96 : 0.3))
        }
        return AnyShapeStyle(Color.white.opacity(isEnabled ? 0.12 : 0.06))
    }

    private var foregroundColor: Color {
        guard isEnabled else { return .white.opacity(0.42) }
        return isProminent ? .white : tint
    }
}

struct ChatPeerVoiceOngoingCallScreen: View {
    @ObservedObject var controller: ChatPeerVoiceCallController
    let contactName: String
    let avatarImageRelativePath: String?
    let avatarHue: Double
    let initials: String
    let onReturnToChat: () -> Void

    var body: some View {
        ZStack {
            LinearGradient(
                colors: backgroundColors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 22) {
                topBar

                mainStage

                Spacer(minLength: 8)

                controlPad
                    .padding(.horizontal, 8)
                .padding(.bottom, 12)
            }
            .padding(.horizontal, 24)
            .padding(.top, 18)
            .padding(.bottom, 34)
        }
        .interactiveDismissDisabled(true)
    }

    private var topBar: some View {
        HStack {
            Button(action: onReturnToChat) {
                Label(LocalizedStringKey("VOICE_CALL_OPEN_CHAT"), systemImage: "bubble.left.and.bubble.right.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                .background(Color.white.opacity(0.12), in: Capsule())
            }
            .buttonStyle(.plain)

            Spacer()

            if controller.callKind == .video {
                Text(LocalizedStringKey("VIDEO_CALL_ACTION"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.white.opacity(0.84))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(Color.white.opacity(0.08), in: Capsule())
            }

            Image(systemName: phaseGlyphName)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.86))
                .frame(width: 40, height: 40)
                .background(Color.white.opacity(0.08), in: Circle())
        }
    }

    private var phaseGlyphName: String {
        switch controller.phase {
        case .dialing:
            return controller.callKind == .video ? "video.badge.ellipsis.fill" : "phone.arrow.up.right.fill"
        case .ringing:
            if controller.direction == .incoming {
                return controller.callKind == .video ? "video.fill" : "phone.arrow.down.left.fill"
            }
            return controller.callKind == .video ? "video.badge.waveform.fill" : "bell.and.waveform.fill"
        case .connecting:
            return controller.callKind == .video ? "video.bubble.left.fill" : "dot.radiowaves.left.and.right"
        case .active:
            return controller.callKind == .video ? "video.fill" : "waveform"
        case .ended:
            return "phone.down.fill"
        case .failed:
            return "exclamationmark.triangle.fill"
        case .idle:
            return controller.callKind == .video ? "video.fill" : "phone.fill"
        }
    }

    @ViewBuilder
    private var mainStage: some View {
        if controller.callKind == .video {
            videoStage
        } else {
            audioStage
        }
    }

    private var audioStage: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 12)

            ChatAvatarCircleView(
                avatarImageRelativePath: avatarImageRelativePath,
                initials: initials,
                avatarHue: avatarHue,
                size: 112,
                borderColor: Color.white.opacity(0.16),
                borderWidth: 2
            )
            .shadow(color: .black.opacity(0.24), radius: 24, y: 12)

            callTextStack

            Spacer(minLength: 12)
        }
    }

    private var videoStage: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: 34, style: .continuous)
                .fill(Color.white.opacity(0.08))
                .overlay(videoSurfaceOverlay)
                .overlay(
                    RoundedRectangle(cornerRadius: 34, style: .continuous)
                        .stroke(Color.white.opacity(0.08), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.28), radius: 28, y: 16)

            if controller.isLocalVideoEnabled, let localPreviewImage = controller.localPreviewImage {
                Image(uiImage: localPreviewImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 116, height: 168)
                    .clipped()
                    .background(Color.black.opacity(0.44))
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .stroke(Color.white.opacity(0.12), lineWidth: 1)
                    )
                    .padding(18)
                    .shadow(color: .black.opacity(0.22), radius: 16, y: 10)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 470)
    }

    @ViewBuilder
    private var videoSurfaceOverlay: some View {
        if let remotePreviewImage = controller.remotePreviewImage {
            Image(uiImage: remotePreviewImage)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 34, style: .continuous))
        } else {
            ZStack {
                LinearGradient(
                    colors: [Color.white.opacity(0.08), Color.black.opacity(0.22)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )

                VStack(spacing: 18) {
                    ChatAvatarCircleView(
                        avatarImageRelativePath: avatarImageRelativePath,
                        initials: initials,
                        avatarHue: avatarHue,
                        size: 104,
                        borderColor: Color.white.opacity(0.14),
                        borderWidth: 2
                    )
                    .shadow(color: .black.opacity(0.24), radius: 22, y: 12)

                    callTextStack
                        .padding(.horizontal, 24)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 34, style: .continuous))
        }
    }

    private var callTextStack: some View {
        VStack(spacing: 10) {
            Text(contactName)
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)

            if controller.phase == .active, let connectedAt = controller.connectedAt {
                TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                    Text(verbatim: formattedCallDuration(since: connectedAt, referenceDate: context.date))
                        .font(.system(size: 44, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white)
                        .monospacedDigit()
                }

                Text(controller.statusMessage)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Color.white.opacity(0.72))
                    .multilineTextAlignment(.center)
            } else {
                ChatPeerVoiceCallStatusText(
                    phase: controller.phase,
                    statusMessage: controller.statusMessage,
                    connectedAt: controller.connectedAt
                )
                .font(.title3.weight(.semibold))
                .foregroundStyle(Color.white.opacity(0.9))
                .multilineTextAlignment(.center)
            }
        }
    }

    @ViewBuilder
    private var controlPad: some View {
        if controller.isIncomingRinging {
            HStack(spacing: 20) {
                ChatPeerVoiceCallActionButton(
                    title: LocalizedStringKey("VOICE_CALL_REJECT_ACTION"),
                    systemImage: "phone.down.fill",
                    tint: .red,
                    isProminent: true,
                    isEnabled: true,
                    action: controller.rejectIncomingCall
                )

                ChatPeerVoiceCallActionButton(
                    title: LocalizedStringKey("VOICE_CALL_ANSWER_ACTION"),
                    systemImage: controller.callKind == .video ? "video.fill" : "phone.fill",
                    tint: .green,
                    isProminent: true,
                    isEnabled: true,
                    action: controller.answerIncomingCall
                )
            }
        } else if controller.callKind == .video {
            VStack(spacing: 18) {
                HStack(spacing: 18) {
                    ChatPeerVoiceCallActionButton(
                        title: controller.isMuted
                            ? LocalizedStringKey("VOICE_CALL_UNMUTE")
                            : LocalizedStringKey("VOICE_CALL_MUTE"),
                        systemImage: controller.isMuted ? "mic.slash.fill" : "mic.fill",
                        tint: .white,
                        isProminent: false,
                        isEnabled: controller.canToggleMute,
                        action: controller.toggleMute
                    )

                    ChatPeerVoiceCallActionButton(
                        title: controller.isSpeakerEnabled
                            ? LocalizedStringKey("VOICE_CALL_EARPIECE")
                            : LocalizedStringKey("VOICE_CALL_SPEAKER"),
                        systemImage: controller.isSpeakerEnabled ? "speaker.slash.fill" : "speaker.wave.2.fill",
                        tint: .white,
                        isProminent: false,
                        isEnabled: controller.canToggleSpeaker,
                        action: controller.toggleSpeaker
                    )
                }

                HStack(spacing: 18) {
                    ChatPeerVoiceCallActionButton(
                        title: controller.isLocalVideoEnabled
                            ? LocalizedStringKey("VIDEO_CALL_DISABLE_CAMERA")
                            : LocalizedStringKey("VIDEO_CALL_ENABLE_CAMERA"),
                        systemImage: controller.isLocalVideoEnabled ? "video.fill" : "video.slash.fill",
                        tint: .white,
                        isProminent: false,
                        isEnabled: controller.canToggleVideo,
                        action: controller.toggleVideo
                    )

                    ChatPeerVoiceCallActionButton(
                        title: LocalizedStringKey("VOICE_CALL_END_ACTION"),
                        systemImage: "phone.down.fill",
                        tint: .red,
                        isProminent: true,
                        isEnabled: true,
                        action: controller.endCall
                    )
                }
            }
        } else {
            HStack(spacing: 18) {
                ChatPeerVoiceCallActionButton(
                    title: controller.isMuted
                        ? LocalizedStringKey("VOICE_CALL_UNMUTE")
                        : LocalizedStringKey("VOICE_CALL_MUTE"),
                    systemImage: controller.isMuted ? "mic.slash.fill" : "mic.fill",
                    tint: .white,
                    isProminent: false,
                    isEnabled: controller.canToggleMute,
                    action: controller.toggleMute
                )

                ChatPeerVoiceCallActionButton(
                    title: controller.isSpeakerEnabled
                        ? LocalizedStringKey("VOICE_CALL_EARPIECE")
                        : LocalizedStringKey("VOICE_CALL_SPEAKER"),
                    systemImage: controller.isSpeakerEnabled ? "speaker.slash.fill" : "speaker.wave.2.fill",
                    tint: .white,
                    isProminent: false,
                    isEnabled: controller.canToggleSpeaker,
                    action: controller.toggleSpeaker
                )

                ChatPeerVoiceCallActionButton(
                    title: LocalizedStringKey("VOICE_CALL_END_ACTION"),
                    systemImage: "phone.down.fill",
                    tint: .red,
                    isProminent: true,
                    isEnabled: true,
                    action: controller.endCall
                )
            }
        }
    }

    private var backgroundColors: [Color] {
        switch controller.phase {
        case .active:
            return [Color(red: 0.08, green: 0.16, blue: 0.20), Color.black, Color(red: 0.04, green: 0.22, blue: 0.18)]
        case .connecting, .dialing, .ringing:
            return [Color(red: 0.10, green: 0.10, blue: 0.18), Color.black, Color(red: 0.08, green: 0.14, blue: 0.24)]
        case .ended:
            return [Color(red: 0.24, green: 0.16, blue: 0.06), Color.black]
        case .failed:
            return [Color(red: 0.24, green: 0.08, blue: 0.08), Color.black]
        case .idle:
            return [Color.appPrimary.opacity(0.3), Color.black]
        }
    }
}

struct ChatPeerVoiceIncomingCallScreen: View {
    let presentation: ChatPeerVoiceIncomingPresentation
    let onAnswer: () -> Void
    let onReject: () -> Void
    let onOpenChat: () -> Void

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.appPrimary.opacity(0.24), Color.black.opacity(0.9)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 24) {
                Spacer()

                VStack(spacing: 12) {
                    Image(systemName: presentation.callKind == .video ? "video.fill" : "phone.arrow.down.left.fill")
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(24)
                        .background(Color.white.opacity(0.12), in: Circle())

                    Text(presentation.contactName)
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)

                    Text(
                        presentation.callKind == .video
                            ? LocalizedStringKey("VIDEO_CALL_STATUS_RINGING_INCOMING")
                            : LocalizedStringKey("VOICE_CALL_STATUS_RINGING_INCOMING")
                    )
                        .font(.headline)
                        .foregroundStyle(Color.white.opacity(0.82))
                }

                Spacer()

                VStack(spacing: 14) {
                    Button(action: onOpenChat) {
                        Label(LocalizedStringKey("VOICE_CALL_OPEN_CHAT"), systemImage: "bubble.left.and.bubble.right.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(.white)

                    HStack(spacing: 16) {
                        Button(action: onReject) {
                            Label(LocalizedStringKey("VOICE_CALL_REJECT_ACTION"), systemImage: "phone.down.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.red)

                        Button(action: onAnswer) {
                            Label(
                                LocalizedStringKey("VOICE_CALL_ANSWER_ACTION"),
                                systemImage: presentation.callKind == .video ? "video.fill" : "phone.fill"
                            )
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.green)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 36)
        }
        .interactiveDismissDisabled(true)
    }
}

struct ChatPeerVoiceActiveCallBanner: View {
    let snapshot: ChatPeerVoiceCallSnapshot
    let onOpen: () -> Void
    let onEnd: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: snapshot.callKind == .video ? "video.fill" : "waveform")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.green)
                .frame(width: 36, height: 36)
                .background(Color.green.opacity(0.12), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(snapshot.contactName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                ChatPeerVoiceCallStatusText(
                    phase: snapshot.phase,
                    statusMessage: snapshot.statusMessage,
                    connectedAt: snapshot.connectedAt
                )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Button(action: onOpen) {
                Text(LocalizedStringKey("VOICE_CALL_OPEN_CHAT"))
                    .font(.caption.weight(.semibold))
            }
            .buttonStyle(.bordered)

            Button(action: onEnd) {
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 14, weight: .semibold))
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.08), radius: 16, y: 6)
    }

}

private func formattedCallDuration(since startDate: Date, referenceDate: Date = Date()) -> String {
    let totalSeconds = max(0, Int(referenceDate.timeIntervalSince(startDate)))
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    return String(format: "%02d:%02d", minutes, seconds)
}
