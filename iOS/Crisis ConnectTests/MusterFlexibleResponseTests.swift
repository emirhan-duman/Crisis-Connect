import Foundation
import XCTest
import AVFoundation
@testable import Crisis_Connect

private final class FlexibleMusterStore: MusterPersisting, @unchecked Sendable {
    var data: Data?
    var fail = false
    func load() throws -> Data? { data }
    func save(_ data: Data) throws { if fail { throw MusterError.persistence }; self.data = data }
}
final class MusterFlexibleResponseTests: XCTestCase {
    private let config = MusterResponseConfig(version: 1, question: "Where are you?", options: [
        MusterResponseOption(id: "ready", label: "At my desk", meaning: "safe"),
        MusterResponseOption(id: "other", label: "I need help (quoted)", meaning: "message")
    ], allowText: true, allowVoice: true)
    private func files() -> (URL, MusterVoiceFiles) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        return (root, MusterVoiceFiles(root: root, key: Data(repeating: 42, count: 32)))
    }
    private let recording = MusterVoiceRecording(data: Data("fixture: encrypted durable attachment bytes, not microphone input".utf8), durationMillis: 1700)
    private func receipt(_ event: MusterEvent, eventId: String? = nil) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["ok": true, "id": event.sessionId, "eventId": eventId ?? event.serverEventId, "applied": false])
    }
    @MainActor func testAppleAACEncodingMatchesRecorderSettingsWithoutCapturingMicrophone() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("muster-native-aac.m4a")
        do {
            let audio = try AVAudioFile(forWriting: file, settings: MusterAudio.recordingSettings)
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: 16_000))
            buffer.frameLength = 16_000
            let samples = try XCTUnwrap(buffer.floatChannelData?[0])
            for index in 0..<16_000 { samples[index] = Float(sin(Double(index) * 2 * .pi * 440 / 16_000) * 0.05) }
            try audio.write(from: buffer)
        }
        let data = try Data(contentsOf: file), player = try AVAudioPlayer(contentsOf: file)
        let finalized = try AVAudioFile(forReading: file)
        let encodedSeconds = Double(finalized.length) / finalized.fileFormat.sampleRate
        // A delayed auto-stop callback sees currentTime == 0. Use the actual AAC duration, not callback latency.
        let stoppedDuration = try XCTUnwrap(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 100, currentUptime: 145, encodedSeconds: encodedSeconds))
        XCTAssertGreaterThan(stoppedDuration, 900); XCTAssertLessThan(stoppedDuration, 1200)
        XCTAssertLessThanOrEqual(data.count, MusterVoiceFiles.maximumBytes)
        XCTAssertGreaterThan(player.duration, 0.9); XCTAssertLessThan(player.duration, 1.2)
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.mpeg-4-audio")
        attachment.name = "muster-native-aac.m4a"; attachment.lifetime = .keepAlways; add(attachment)
        print("MUSTER_NATIVE_AAC_FIXTURE=\(file.path)")
        // Native-format interoperability fixture is synthetic; no permission request or microphone access occurs.
    }
    func testRecorderAutoStopKeepsItsDurationUsingMonotonicTiming() {
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 500, currentUptime: 530), 30_000)
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 500, currentUptime: 535), 30_000)
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 500, currentUptime: 535, encodedSeconds: 29.8), 29_800)
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 500, currentUptime: 535, encodedSeconds: 30.125), 30_000)
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: 1.25, startedUptime: 500, currentUptime: 502), 1250)
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: .nan, startedUptime: 500, currentUptime: 501.5), 1500)
        XCTAssertEqual(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 500, currentUptime: .greatestFiniteMagnitude), 30_000)
        XCTAssertNil(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: nil, currentUptime: 530))
        XCTAssertNil(musterRecordingDurationMillis(recorderSeconds: .infinity, startedUptime: 500, currentUptime: 499))
        XCTAssertNil(musterRecordingDurationMillis(recorderSeconds: 0, startedUptime: 500, currentUptime: 535, encodedSeconds: 31))
    }
    func testLegacyJournalAndSessionDecodeWithoutNewFields() throws {
        let old = Data(#"{"uid":"alice","panelId":"factory","sessionId":"check","operationId":"original","answer":"safe","note":"","answeredAtMillis":1000,"state":"pending","attempts":0,"nextAt":0,"error":""}"#.utf8)
        let event = try JSONDecoder().decode(MusterEvent.self, from: old)
        XCTAssertNil(event.schemaVersion); XCTAssertNil(event.optionId); XCTAssertNil(event.voice)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: event.body()) as? [String: Any])
        XCTAssertEqual(body["schemaVersion"] as? Int, 1); XCTAssertNil(body["voice"])
        let session = try JSONDecoder().decode(MusterSession.self, from: Data(#"{"id":"check","kind":"live","title":"Check","instructions":"Respond","status":"active"}"#.utf8))
        XCTAssertNil(session.responseConfig)
    }
    func testMeaningIsDerivedFromFrozenOptionAndFreeMessageIsNeutral() throws {
        XCTAssertEqual(try config.meaning(optionId: "ready", note: "", hasVoice: false), "safe")
        XCTAssertEqual(try config.meaning(optionId: "other", note: "", hasVoice: false), "message")
        XCTAssertEqual(try config.meaning(optionId: nil, note: "I need help", hasVoice: false), "message")
        XCTAssertEqual(try config.meaning(optionId: nil, note: "", hasVoice: true), "message")
        XCTAssertThrowsError(try config.meaning(optionId: "unknown", note: "", hasVoice: false))
        XCTAssertThrowsError(try config.meaning(optionId: nil, note: " \n ", hasVoice: false))
        XCTAssertThrowsError(try config.meaning(optionId: nil, note: String(repeating: "🙂", count: 1001), hasVoice: false))
        let locked = MusterResponseConfig(version: 1, question: "", options: config.options, allowText: false, allowVoice: false)
        XCTAssertThrowsError(try locked.meaning(optionId: "ready", note: "note", hasVoice: false))
        XCTAssertThrowsError(try locked.meaning(optionId: "ready", note: "", hasVoice: true))
        XCTAssertEqual(try locked.meaning(optionId: "ready", note: "", hasVoice: false), "safe")
    }
    func testMessageAndVoiceOnlyResponsesClearCachedChoiceAndAttachment() {
        let oldVoice = MusterVoiceMetadata(eventId: String(repeating: "a", count: 32), mimeType: "audio/mp4", durationMillis: 1000, bytes: 20, sha256: String(repeating: "b", count: 64))
        let helpVoice = MusterVoiceMetadata(eventId: String(repeating: "c", count: 32), mimeType: "audio/mp4", durationMillis: 1000, bytes: 20, sha256: String(repeating: "d", count: 64))
        let server = MusterOwnResponse(answer: "safe", helpOpen: true, optionId: "ready", note: "Previous response", voice: oldVoice, helpVoice: helpVoice)
        let message = MusterEvent(uid: "alice", panelId: "factory", sessionId: "check", operationId: "new-message", answer: "message", note: "", answeredAtMillis: 200, schemaVersion: 2)
        let projected = MusterProjectedResponse(latest: message, server: server)
        XCTAssertEqual(projected.answer, "message"); XCTAssertNil(projected.optionId)
        XCTAssertEqual(projected.note, ""); XCTAssertNil(projected.voice)
        XCTAssertEqual(projected.displayLabel(config: config), musterText("message"))
        XCTAssertEqual(projected.helpVoice, helpVoice)
        let voiceOnly = MusterEvent(uid: "alice", panelId: "factory", sessionId: "check", operationId: "new-voice", answer: "message", note: "", answeredAtMillis: 201, schemaVersion: 2,
            voice: MusterLocalVoice(fileId: UUID().uuidString, durationMillis: 1700, bytes: 21, sha256: String(repeating: "e", count: 64)))
        let voiceProjection = MusterProjectedResponse(latest: voiceOnly, server: server)
        XCTAssertNil(voiceProjection.optionId)
        XCTAssertEqual(voiceProjection.displayLabel(config: config), musterText("message"))
        XCTAssertEqual(voiceProjection.voice?.eventId, voiceOnly.serverEventId)
        XCTAssertEqual(voiceProjection.voice?.sha256, voiceOnly.voice?.sha256)
        XCTAssertNotEqual(voiceProjection.voice, oldVoice)
        XCTAssertEqual(voiceProjection.helpVoice, helpVoice)
    }
    func testProjectionUsesItsOwnChoiceAndPreservesLegacyMeaningLabels() {
        let server = MusterOwnResponse(answer: "safe", optionId: "ready", note: "Previous response")
        let configured = MusterEvent(uid: "alice", panelId: "factory", sessionId: "check", operationId: "new-option", answer: "message", note: "New response", answeredAtMillis: 200, schemaVersion: 2, optionId: "other")
        let projected = MusterProjectedResponse(latest: configured, server: server)
        XCTAssertEqual(projected.optionId, "other"); XCTAssertEqual(projected.note, "New response")
        XCTAssertEqual(projected.displayLabel(config: config), "I need help (quoted)")
        let legacy = MusterEvent(uid: "alice", panelId: "factory", sessionId: "check", operationId: "legacy", answer: "help", note: "", answeredAtMillis: 200)
        let legacyProjection = MusterProjectedResponse(latest: legacy, server: server)
        XCTAssertNil(legacyProjection.optionId)
        XCTAssertEqual(legacyProjection.displayLabel(config: config), musterText("help"))
        XCTAssertEqual(MusterProjectedResponse(latest: nil, server: server).displayLabel(config: config), "At my desk")
    }
    @MainActor func testProjectionStillYieldsToNewerServerAndKeepsHelpIndependent() {
        let model = MusterViewModel()
        model.events = [MusterEvent(uid: "alice", panelId: "factory", sessionId: "check", operationId: "old-sent", answer: "safe", note: "Old response", answeredAtMillis: 100, state: "sent", schemaVersion: 2, optionId: "ready")]
        let helpVoice = MusterVoiceMetadata(eventId: String(repeating: "c", count: 32), mimeType: "audio/mp4", durationMillis: 1000, bytes: 20, sha256: String(repeating: "d", count: 64))
        let server = MusterOwnResponse(answer: "message", helpOpen: true, answeredAtMillis: 200, note: "New server response", helpVoice: helpVoice)
        let row = CachedMusterAssignment(panelId: "factory", assignment: MusterAssignment(session: MusterSession(id: "check", kind: "live", title: "Check", instructions: "Respond", status: "active", responseConfig: config), response: server))
        XCTAssertNil(model.latest(row))
        let projected = MusterProjectedResponse(latest: model.latest(row), server: server)
        XCTAssertEqual(projected.answer, "message"); XCTAssertEqual(projected.note, "New server response")
        XCTAssertNil(projected.optionId); XCTAssertNil(projected.voice)
        XCTAssertEqual(projected.helpVoice, helpVoice)
        var resolved = server; resolved.helpOpen = false
        XCTAssertNil(MusterProjectedResponse(latest: nil, server: resolved).helpVoice)
        let hiddenReceiptId = model.events.first?.id
        XCTAssertFalse(musterResetsComposerAfterLatestEventChange(from: hiddenReceiptId, to: model.latest(row)?.id, editing: true), "Newer server adoption must preserve an unsent edit")
        XCTAssertTrue(musterResetsComposerAfterLatestEventChange(from: hiddenReceiptId, to: model.latest(row)?.id, editing: false))
        model.events.append(MusterEvent(uid: "alice", panelId: "factory", sessionId: "check", operationId: "new-enqueued", answer: "message", note: "My saved update", answeredAtMillis: 300, schemaVersion: 2))
        let enqueuedId = model.latest(row)?.id
        XCTAssertNotNil(enqueuedId)
        XCTAssertTrue(musterResetsComposerAfterLatestEventChange(from: nil, to: enqueuedId, editing: true), "A durable local enqueue must close its composer")
        XCTAssertTrue(musterResetsComposerAfterLatestEventChange(from: hiddenReceiptId, to: enqueuedId, editing: true))
        XCTAssertFalse(musterResetsComposerAfterLatestEventChange(from: enqueuedId, to: enqueuedId, editing: true), "Receipt/state refresh must not reset an unchanged composer")
    }
    func testVoiceSurvivesRestartWithoutPuttingAudioInKeychainAndReplaysExactly() async throws {
        let (root, files) = files(); defer { try? FileManager.default.removeItem(at: root) }
        let storage = FlexibleMusterStore(), first = MusterQueue(storage: FlexibleMusterStore())
        _ = try await first.enqueue(uid: "unrelated", panel: "factory", session: "check", answer: "safe")
        let queue = MusterQueue(storage: storage, voiceFiles: files), now = Date(timeIntervalSince1970: 100)
        let event = try await queue.enqueueConfigured(uid: "alice", panel: "factory", session: "check", config: config, optionId: nil, note: "Voice update", recording: recording, now: now, operationId: "stable-id")
        XCTAssertEqual(event.answer, "message"); XCTAssertEqual(event.schemaVersion, 2)
        XCTAssertFalse(try XCTUnwrap(String(data: XCTUnwrap(storage.data), encoding: .utf8)).contains(recording.data.base64EncodedString()))
        let restarted = MusterQueue(storage: storage, voiceFiles: files)
        let originalBody = try await queue.body(event)
        let claimedValue = try await restarted.claim(uid: "alice", now: now)
        let claimed = try XCTUnwrap(claimedValue)
        try await restarted.finish(claimed, state: "pending", error: "connection", now: now)
        let retryValue = try await restarted.claim(uid: "alice", now: now.addingTimeInterval(1000))
        let retry = try XCTUnwrap(retryValue)
        let retryBody = try await restarted.body(retry); XCTAssertEqual(retryBody, originalBody)
        let replay = try await restarted.enqueueConfigured(uid: "alice", panel: "factory", session: "check", config: config, optionId: nil, note: "Voice update", recording: recording, now: now.addingTimeInterval(2000), operationId: "stable-id")
        XCTAssertEqual(replay.answeredAtMillis, event.answeredAtMillis); XCTAssertEqual(replay.voice, event.voice)
        let bob = try await restarted.events(uid: "bob"); XCTAssertTrue(bob.isEmpty)
        XCTAssertThrowsError(try files.load(uid: "bob", voice: XCTUnwrap(event.voice)))
        let encrypted = try Data(contentsOf: root.appendingPathComponent(MusterVoiceFiles.digest(Data("alice".utf8))).appendingPathComponent(try XCTUnwrap(event.voice).fileId + ".sealed"))
        XCTAssertNil(encrypted.range(of: recording.data))
    }
    func testOnlyReceiptBoundToOriginalOperationDeletesDurableVoice() async throws {
        let (root, files) = files(); defer { try? FileManager.default.removeItem(at: root) }
        let queue = MusterQueue(storage: FlexibleMusterStore(), voiceFiles: files)
        let event = try await queue.enqueueConfigured(uid: "alice", panel: "factory", session: "check", config: config, optionId: "ready", recording: recording)
        do { try await queue.acknowledge(event, status: 200, receipt: receipt(event, eventId: String(repeating: "a", count: 32))); XCTFail("Foreign receipt must fail") } catch {}
        XCTAssertEqual(try files.load(uid: "alice", voice: XCTUnwrap(event.voice)), recording.data)
        try await queue.finish(event, state: "failed", error: "http-403")
        XCTAssertEqual(try files.load(uid: "alice", voice: XCTUnwrap(event.voice)), recording.data)
        try await queue.acknowledge(event, status: 200, receipt: receipt(event))
        let events = try await queue.events(uid: "alice"); XCTAssertEqual(events.first?.state, "sent")
        XCTAssertThrowsError(try files.load(uid: "alice", voice: XCTUnwrap(event.voice)))
        // An idempotent receipt remains valid even when the server reports applied:false.
        try await queue.acknowledge(event, status: 200, receipt: receipt(event))
    }
    func testFailedJournalWriteRollsBackNewAttachmentAndVoiceLimitsRejectBeforeSave() async throws {
        let (root, files) = files(); defer { try? FileManager.default.removeItem(at: root) }
        let storage = FlexibleMusterStore(); storage.fail = true
        let queue = MusterQueue(storage: storage, voiceFiles: files)
        do { _ = try await queue.enqueueConfigured(uid: "alice", panel: "factory", session: "check", config: config, optionId: nil, recording: recording); XCTFail("Persistence should fail") } catch {}
        let remaining = (FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? []).filter { $0.pathExtension == "sealed" }
        XCTAssertTrue(remaining.isEmpty)
        XCTAssertThrowsError(try files.save(uid: "alice", recording: MusterVoiceRecording(data: Data(repeating: 0, count: 131_073), durationMillis: 1000)))
        XCTAssertThrowsError(try files.save(uid: "alice", recording: MusterVoiceRecording(data: Data([0]), durationMillis: 30_001)))
    }
    func testAccountAndFileIdentityAreAuthenticatedAndOrphanCleanupKeepsPending() throws {
        let (root, files) = files(); defer { try? FileManager.default.removeItem(at: root) }
        let pending = try files.save(uid: "alice", recording: recording)
        let orphan = try files.save(uid: "alice", recording: recording)
        func path(_ uid: String, _ voice: MusterLocalVoice) -> URL {
            root.appendingPathComponent(MusterVoiceFiles.digest(Data(uid.utf8))).appendingPathComponent(voice.fileId + ".sealed")
        }
        let alice = path("alice", pending), bob = path("bob", pending)
        try FileManager.default.createDirectory(at: bob.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: alice, to: bob)
        XCTAssertThrowsError(try files.load(uid: "bob", voice: pending))
        for file in [alice, path("alice", orphan)] { try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -700)], ofItemAtPath: file.path) }
        try files.pruneOrphans(references: ["alice": [pending.fileId]])
        XCTAssertEqual(try files.load(uid: "alice", voice: pending), recording.data)
        XCTAssertFalse(FileManager.default.fileExists(atPath: path("alice", orphan).path))
    }
    func testDiskBudgetRefusesNewVoiceWithoutEvictingPendingAudio() throws {
        let (root, files) = files(); defer { try? FileManager.default.removeItem(at: root) }
        let large = MusterVoiceRecording(data: Data(repeating: 7, count: 128 * 1024), durationMillis: 30_000)
        let first = try files.save(uid: "alice", recording: large)
        var reachedBudget = false
        for _ in 0..<70 {
            do { _ = try files.save(uid: "alice", recording: large) } catch MusterError.capacity { reachedBudget = true; break }
        }
        XCTAssertTrue(reachedBudget)
        XCTAssertEqual(try files.load(uid: "alice", voice: first), large.data)
    }
}
