//
//  CrisisSentinelModelDownloadManager.swift
//  Crisis Connect
//

import Foundation

extension Notification.Name {
    /// Posted (on any thread) once a freshly downloaded Crisis Sentinel model has been validated
    /// and committed to disk, so any live view models can refresh their status.
    static let crisisSentinelModelDidInstall = Notification.Name("crisisSentinel.modelDidInstall")
}

/// Drives the Crisis Sentinel model download on a *background* `URLSession` so it keeps running when
/// the app is suspended and reports real byte-level progress. Both behaviours were missing from the
/// previous `URLSession.shared.download(from:)` implementation, which sat at 0% and then jumped to
/// "ready" once it finished, and stalled whenever the app left the foreground.
final class CrisisSentinelModelDownloadManager: NSObject, @unchecked Sendable {
    static let shared = CrisisSentinelModelDownloadManager()
    static let backgroundSessionIdentifier = "com.auralis.crisisconnect.crisisSentinelModelDownload"

    private let store: CrisisSentinelModelFileStore
    private let manifestCache: CrisisSentinelModelManifestCache
    private let lock = NSLock()
    private var active: ActiveDownload?
    private var pendingBackgroundCompletion: (() -> Void)?

    private struct ActiveDownload {
        let task: URLSessionDownloadTask
        let release: CrisisSentinelModelRelease
        let onProgress: @Sendable (Double) -> Void
        let continuation: CheckedContinuation<CrisisSentinelModelStatus, Error>
    }

    init(
        store: CrisisSentinelModelFileStore = CrisisSentinelModelFileStore(),
        manifestCache: CrisisSentinelModelManifestCache = CrisisSentinelModelManifestCache()
    ) {
        self.store = store
        self.manifestCache = manifestCache
        super.init()
    }

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        configuration.waitsForConnectivity = true
        configuration.allowsConstrainedNetworkAccess = true
        configuration.allowsExpensiveNetworkAccess = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    /// Called from the app delegate when iOS relaunches the app to deliver background download
    /// events. Touching `session` recreates it so the pending delegate callbacks are delivered.
    func handleBackgroundSessionEvents(completionHandler: @escaping () -> Void) {
        lock.lock()
        pendingBackgroundCompletion = completionHandler
        lock.unlock()
        _ = session
    }

    func download(
        release: CrisisSentinelModelRelease,
        from signedURL: URL,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> CrisisSentinelModelStatus {
        // Replace any in-flight download (re-tap, or a second screen kicking one off).
        cancelActive(with: CrisisSentinelModelDownloadError.superseded)

        let temporaryURL = store.temporaryDownloadURL(for: release)
        try? FileManager.default.removeItem(at: temporaryURL)

        return try await withCheckedThrowingContinuation { continuation in
            let task = session.downloadTask(with: signedURL)
            lock.lock()
            active = ActiveDownload(
                task: task,
                release: release,
                onProgress: onProgress,
                continuation: continuation
            )
            lock.unlock()
            onProgress(0)
            task.resume()
        }
    }

    private func cancelActive(with error: Error) {
        lock.lock()
        let existing = active
        active = nil
        lock.unlock()
        guard let existing else { return }
        existing.task.cancel()
        existing.continuation.resume(throwing: error)
    }

    /// Resumes the in-flight continuation only if it belongs to `taskIdentifier`, guarding against
    /// late callbacks from a download we already superseded.
    private func finish(taskIdentifier: Int, with result: Result<CrisisSentinelModelStatus, Error>) {
        lock.lock()
        guard let existing = active, existing.task.taskIdentifier == taskIdentifier else {
            lock.unlock()
            return
        }
        active = nil
        lock.unlock()
        existing.continuation.resume(with: result)
    }
}

extension CrisisSentinelModelDownloadManager: URLSessionDownloadDelegate {
    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let fraction = max(0, min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)))
        lock.lock()
        let handler = (active?.task.taskIdentifier == downloadTask.taskIdentifier) ? active?.onProgress : nil
        lock.unlock()
        handler?(fraction)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        lock.lock()
        let current = active
        lock.unlock()
        // The file at `location` is only valid for the duration of this callback, so move it out
        // synchronously before doing any validation work.
        let release = current?.release ?? manifestCache.load() ?? .defaultRelease
        let temporaryURL = store.temporaryDownloadURL(for: release)
        try? FileManager.default.removeItem(at: temporaryURL)
        do {
            try FileManager.default.moveItem(at: location, to: temporaryURL)
        } catch {
            if let current { finish(taskIdentifier: current.task.taskIdentifier, with: .failure(error)) }
            return
        }

        let status = store.commitDownloadedModel(from: temporaryURL, release: release)
        guard status.isReady else {
            store.deleteModel(release: release)
            if let current {
                finish(
                    taskIdentifier: current.task.taskIdentifier,
                    with: .failure(CrisisSentinelModelDownloadError.validationFailed(status.reason))
                )
            }
            return
        }

        NotificationCenter.default.post(name: .crisisSentinelModelDidInstall, object: nil)
        if let current {
            finish(taskIdentifier: current.task.taskIdentifier, with: .success(status))
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        // Success is handled in didFinishDownloadingTo; only surface real failures here.
        guard let error else { return }
        finish(taskIdentifier: task.taskIdentifier, with: .failure(error))
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        lock.lock()
        let handler = pendingBackgroundCompletion
        pendingBackgroundCompletion = nil
        lock.unlock()
        guard let handler else { return }
        DispatchQueue.main.async(execute: handler)
    }
}
