//
//  LiDARViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import Foundation
import Combine
import ARKit
import CoreGraphics
import UIKit

enum LiDARAlertPreset: String, CaseIterable, Identifiable {
    case near
    case balanced
    case early

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .near: return "LIDAR_ALERT_PRESET_NEAR"
        case .balanced: return "LIDAR_ALERT_PRESET_BALANCED"
        case .early: return "LIDAR_ALERT_PRESET_EARLY"
        }
    }

    var cautionDistanceMeters: Double {
        switch self {
        case .near: return 1.0
        case .balanced: return 1.4
        case .early: return 1.9
        }
    }

    var dangerDistanceMeters: Double {
        switch self {
        case .near: return 0.55
        case .balanced: return 0.85
        case .early: return 1.15
        }
    }

    var formattedDistance: String {
        String(format: "%.1f m", cautionDistanceMeters)
    }
}

enum LiDARScanProfile: String, CaseIterable, Identifiable {
    case precision
    case balanced
    case wide

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .precision: return "LIDAR_SCAN_PROFILE_PRECISION"
        case .balanced: return "LIDAR_SCAN_PROFILE_BALANCED"
        case .wide: return "LIDAR_SCAN_PROFILE_WIDE"
        }
    }

    var evaluationRegion: CGRect {
        switch self {
        case .precision:
            return CGRect(x: 0.2, y: 0.3, width: 0.6, height: 0.34)
        case .balanced:
            return CGRect(x: 0.11, y: 0.24, width: 0.78, height: 0.46)
        case .wide:
            return CGRect(x: 0.05, y: 0.18, width: 0.9, height: 0.56)
        }
    }

}

enum LiDARSignalQuality: Equatable {
    case weak
    case medium
    case strong

    var titleKey: String {
        switch self {
        case .weak: return "LIDAR_SIGNAL_WEAK"
        case .medium: return "LIDAR_SIGNAL_MEDIUM"
        case .strong: return "LIDAR_SIGNAL_STRONG"
        }
    }
}

enum LiDARAlertState: Equatable {
    case clear
    case caution
    case danger
    case signalLost

    var titleKey: String {
        switch self {
        case .clear: return "LIDAR_ALERT_CLEAR"
        case .caution: return "LIDAR_ALERT_CAUTION"
        case .danger: return "LIDAR_ALERT_DANGER"
        case .signalLost: return "LIDAR_ALERT_SIGNAL"
        }
    }

    var detailKey: String {
        switch self {
        case .clear: return "LIDAR_ALERT_CLEAR_DETAIL"
        case .caution: return "LIDAR_ALERT_CAUTION_DETAIL"
        case .danger: return "LIDAR_ALERT_DANGER_DETAIL"
        case .signalLost: return "LIDAR_ALERT_SIGNAL_DETAIL"
        }
    }
}

struct LiDARAlertTransition: Equatable {
    let state: LiDARAlertState
    let saferFrameStreak: Int
}

enum LiDARProcessing {
    static func clampMaxDepth(_ value: Float, min minDepth: Float = 1.0, max maxDepth: Float = 5.0) -> Float {
        min(max(value, minDepth), maxDepth)
    }

    static func formattedDistance(_ distanceMeters: Double?) -> String {
        guard let distanceMeters else { return "--" }
        if distanceMeters >= 10 {
            return String(format: "%.1f m", distanceMeters)
        }
        return String(format: "%.2f m", distanceMeters)
    }

    static func representativeDepth(from samples: [Float]) -> Float? {
        let sorted = samples
            .filter { $0.isFinite && $0 > 0 }
            .sorted()
        guard !sorted.isEmpty else { return nil }

        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }

    static func percentileDepth(from samples: [Float], percentile: Double) -> Float? {
        let sorted = samples
            .filter { $0.isFinite && $0 > 0 }
            .sorted()
        guard !sorted.isEmpty else { return nil }

        let clampedPercentile = min(max(percentile, 0), 1)
        let index = Int((Double(sorted.count - 1) * clampedPercentile).rounded(.toNearestOrAwayFromZero))
        return sorted[index]
    }

    static func conservativeDepth(from samples: [Float], percentile: Double = 0.25) -> Float? {
        percentileDepth(from: samples, percentile: percentile)
    }

    static func alertState(
        for distanceMeters: Double?,
        alertPreset: LiDARAlertPreset = .balanced,
        isRunning: Bool,
        isFrozen: Bool
    ) -> LiDARAlertState {
        guard isRunning || isFrozen else { return .signalLost }
        guard let distanceMeters else { return .signalLost }

        switch distanceMeters {
        case ..<alertPreset.dangerDistanceMeters:
            return .danger
        case ..<alertPreset.cautionDistanceMeters:
            return .caution
        default:
            return .clear
        }
    }

    static func stabilizedAlertState(
        current: LiDARAlertState,
        proposed: LiDARAlertState,
        distanceMeters: Double?,
        alertPreset: LiDARAlertPreset,
        saferFrameStreak: Int
    ) -> LiDARAlertTransition {
        guard proposed != .signalLost else {
            return LiDARAlertTransition(state: .signalLost, saferFrameStreak: 0)
        }
        guard current != .signalLost else {
            return LiDARAlertTransition(state: proposed, saferFrameStreak: 0)
        }

        let currentSeverity = alertSeverity(current)
        let proposedSeverity = alertSeverity(proposed)
        guard proposedSeverity < currentSeverity else {
            return LiDARAlertTransition(state: proposed, saferFrameStreak: 0)
        }

        let releaseDistance: Double
        switch current {
        case .danger:
            releaseDistance = alertPreset.dangerDistanceMeters + 0.18
        case .caution:
            releaseDistance = alertPreset.cautionDistanceMeters + 0.22
        case .clear, .signalLost:
            return LiDARAlertTransition(state: proposed, saferFrameStreak: 0)
        }

        guard let distanceMeters, distanceMeters >= releaseDistance else {
            return LiDARAlertTransition(state: current, saferFrameStreak: 0)
        }

        let nextStreak = saferFrameStreak + 1
        guard nextStreak >= 3 else {
            return LiDARAlertTransition(state: current, saferFrameStreak: nextStreak)
        }
        return LiDARAlertTransition(state: proposed, saferFrameStreak: 0)
    }

    static func statusKey(isSupported: Bool, isRunning: Bool, isFrozen: Bool) -> String {
        guard isSupported else { return "LIDAR_STATUS_UNSUPPORTED" }
        if isFrozen {
            return "LIDAR_STATUS_FROZEN"
        }
        return isRunning ? "LIDAR_STATUS_ACTIVE" : "LIDAR_STATUS_INACTIVE"
    }

    static func viewportTransform(from normalizedTransform: CGAffineTransform, in size: CGSize) -> CGAffineTransform {
        guard size.width > 0, size.height > 0 else { return .identity }

        return CGAffineTransform(
            a: normalizedTransform.a,
            b: normalizedTransform.b * size.height / size.width,
            c: normalizedTransform.c * size.width / size.height,
            d: normalizedTransform.d,
            tx: normalizedTransform.tx * size.width,
            ty: normalizedTransform.ty * size.height
        )
    }

    static func signalQuality(for confidentRatio: Double) -> LiDARSignalQuality {
        switch confidentRatio {
        case ..<0.18:
            return .weak
        case ..<0.42:
            return .medium
        default:
            return .strong
        }
    }

    static func recommendedMaxDepth(
        from samples: [Float],
        focusDistance: Float?,
        min minDepth: Float = 2.5,
        max maxDepth: Float = 5.0
    ) -> Float {
        let upperPercentile = percentileDepth(from: samples, percentile: 0.85)
        let focus = focusDistance ?? upperPercentile ?? minDepth
        let candidate = max(minDepth, focus * 2.2, (upperPercentile ?? focus) * 1.15)
        return clampMaxDepth(candidate, min: minDepth, max: maxDepth)
    }

    static func smoothedValue(current: Float, target: Float, alpha: Float = 0.22) -> Float {
        current + alpha * (target - current)
    }

    private static func alertSeverity(_ state: LiDARAlertState) -> Int {
        switch state {
        case .clear, .signalLost: return 0
        case .caution: return 1
        case .danger: return 2
        }
    }
}

private struct LiDARPixelWindow {
    let xRange: ClosedRange<Int>
    let yRange: ClosedRange<Int>

    func contains(x: Int, y: Int) -> Bool {
        xRange.contains(x) && yRange.contains(y)
    }
}

private struct LiDARFrameAnalysis {
    let forwardDistance: Float?
    let adaptiveMaxDepth: Float
    let signalQuality: LiDARSignalQuality
}

private struct LiDARDepthHistogram {
    private static let binCount = 160
    private let maximumDepth: Float
    private(set) var sampleCount = 0
    private var bins = [Int](repeating: 0, count: binCount)

    init(maximumDepth: Float) {
        self.maximumDepth = maximumDepth
    }

    mutating func add(_ depth: Float) {
        guard depth.isFinite, depth > 0, depth <= maximumDepth else { return }
        let normalized = min(max(depth / maximumDepth, 0), 0.999999)
        let index = min(Int(normalized * Float(Self.binCount)), Self.binCount - 1)
        bins[index] += 1
        sampleCount += 1
    }

    func percentile(_ percentile: Double) -> Float? {
        guard sampleCount > 0 else { return nil }
        let clamped = min(max(percentile, 0), 1)
        let target = max(1, Int((Double(sampleCount) * clamped).rounded(.up)))
        var accumulated = 0
        for (index, count) in bins.enumerated() {
            accumulated += count
            if accumulated >= target {
                return (Float(index) + 0.5) / Float(Self.binCount) * maximumDepth
            }
        }
        return maximumDepth
    }
}

private enum LiDARPreferenceKey {
    static let alertPreset = "lidar.alertPreset"
    static let scanProfile = "lidar.scanProfile"
    static let hapticsEnabled = "lidar.hapticsEnabled"
}

final class LiDARViewModel: NSObject, ObservableObject, ARSessionDelegate {
    @Published private(set) var isSupported: Bool
    @Published private(set) var isRunning: Bool = false
    @Published private(set) var statusKey: String = "LIDAR_STATUS_INACTIVE"
    @Published private(set) var distanceMeters: Double?
    @Published private(set) var depthDisplayTransform: CGAffineTransform = .identity
    @Published private(set) var alertState: LiDARAlertState = .signalLost
    @Published private(set) var adaptiveMaxDepthMeters: Float = 5.0
    @Published private(set) var signalQuality: LiDARSignalQuality = .weak
    @Published private(set) var alertPreset: LiDARAlertPreset
    @Published private(set) var scanProfile: LiDARScanProfile
    @Published private(set) var hapticsEnabled: Bool
    @Published var isFrozen: Bool = false

    private let defaults: UserDefaults
    private weak var session: ARSession?
    private weak var frameRenderer: LiDARFrameRendering?
    private var pendingStart: Bool = false
    private var lastUpdateTime: TimeInterval = 0
    private var lastDistance: Double?
    private var lastRawDistance: Double?
    private var saferFrameStreak = 0
    private let analysisInterval: TimeInterval = 1.0 / 30.0
    private let depthSmoothingAlpha: Float = 0.22
    private let maxDepthLimit: Float = 5.0
    private let minimumConfidence: UInt8 = 1
    private let sessionDelegateQueue = DispatchQueue(
        label: "com.crisisconnect.lidar.session",
        qos: .userInteractive
    )
    private var viewportSize: CGSize = .zero
    private var interfaceOrientation: UIInterfaceOrientation = .portrait
    private let cautionFeedback = UIImpactFeedbackGenerator(style: .rigid)
    private let warningFeedback = UINotificationFeedbackGenerator()
    private let signalFeedback = UIImpactFeedbackGenerator(style: .soft)
    private var lastFeedbackState: LiDARAlertState = .signalLost
    private var lastFeedbackTime: TimeInterval = 0

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isSupported = ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
        alertPreset = defaults.string(forKey: LiDARPreferenceKey.alertPreset)
            .flatMap(LiDARAlertPreset.init(rawValue:))
            ?? .balanced
        scanProfile = defaults.string(forKey: LiDARPreferenceKey.scanProfile)
            .flatMap(LiDARScanProfile.init(rawValue:))
            ?? .balanced
        hapticsEnabled = defaults.object(forKey: LiDARPreferenceKey.hapticsEnabled) as? Bool ?? true
        super.init()
        if !isSupported {
            statusKey = "LIDAR_STATUS_UNSUPPORTED"
        }
    }

    var distanceText: String {
        LiDARProcessing.formattedDistance(distanceMeters)
    }

    var alertDistanceText: String {
        alertPreset.formattedDistance
    }

    var hapticsStatusKey: String {
        hapticsEnabled ? "LIDAR_HAPTICS_ON" : "LIDAR_HAPTICS_OFF"
    }

    var detectionRegion: CGRect {
        scanProfile.evaluationRegion
    }

    func attach(session: ARSession) {
        self.session = session
        session.delegateQueue = sessionDelegateQueue
        session.delegate = self
        if pendingStart {
            pendingStart = false
            start()
        }
    }

    func attach(renderer: LiDARFrameRendering) {
        frameRenderer = renderer
    }

    func start() {
        guard isSupported else {
            statusKey = "LIDAR_STATUS_UNSUPPORTED"
            return
        }
        guard let session else {
            pendingStart = true
            return
        }

        let configuration = ARWorldTrackingConfiguration()
        // Raw scene depth is captured with the current camera frame. The HUD applies its
        // own hysteresis, so ARKit's multi-frame smoothing would only add alert latency.
        configuration.frameSemantics.insert(.sceneDepth)
        session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        lastUpdateTime = 0
        distanceMeters = nil
        lastDistance = nil
        lastRawDistance = nil
        saferFrameStreak = 0
        frameRenderer?.clear()
        adaptiveMaxDepthMeters = 5.0
        signalQuality = .weak
        isFrozen = false
        isRunning = true
        lastFeedbackState = .signalLost
        lastFeedbackTime = 0
        statusKey = LiDARProcessing.statusKey(isSupported: isSupported, isRunning: isRunning, isFrozen: isFrozen)
        alertState = .signalLost
        cautionFeedback.prepare()
        warningFeedback.prepare()
        signalFeedback.prepare()
    }

    func updateViewport(size: CGSize, interfaceOrientation: UIInterfaceOrientation) {
        guard size.width > 0, size.height > 0 else { return }

        viewportSize = size
        self.interfaceOrientation = interfaceOrientation

        if let frame = session?.currentFrame {
            depthDisplayTransform = makeDisplayTransform(for: frame)
        }
    }

    func stop() {
        pendingStart = false
        session?.pause()
        lastUpdateTime = 0
        isRunning = false
        isFrozen = false
        statusKey = LiDARProcessing.statusKey(isSupported: isSupported, isRunning: isRunning, isFrozen: isFrozen)
        distanceMeters = nil
        lastDistance = nil
        lastRawDistance = nil
        saferFrameStreak = 0
        frameRenderer?.clear()
        adaptiveMaxDepthMeters = 5.0
        signalQuality = .weak
        alertState = .signalLost
    }

    func toggleFreeze() {
        guard isSupported, isRunning || isFrozen else { return }
        isFrozen.toggle()
        if !isFrozen {
            lastUpdateTime = 0
        }
        statusKey = LiDARProcessing.statusKey(isSupported: isSupported, isRunning: isRunning, isFrozen: isFrozen)
        alertState = LiDARProcessing.alertState(
            for: lastRawDistance,
            alertPreset: alertPreset,
            isRunning: isRunning,
            isFrozen: isFrozen
        )
    }

    func setAlertPreset(_ preset: LiDARAlertPreset) {
        guard alertPreset != preset else { return }
        alertPreset = preset
        defaults.set(preset.rawValue, forKey: LiDARPreferenceKey.alertPreset)
        alertState = LiDARProcessing.alertState(
            for: lastRawDistance,
            alertPreset: preset,
            isRunning: isRunning,
            isFrozen: isFrozen
        )
    }

    func setScanProfile(_ profile: LiDARScanProfile) {
        guard scanProfile != profile else { return }
        scanProfile = profile
        defaults.set(profile.rawValue, forKey: LiDARPreferenceKey.scanProfile)
        lastUpdateTime = 0
    }

    func toggleHaptics() {
        hapticsEnabled.toggle()
        defaults.set(hapticsEnabled, forKey: LiDARPreferenceKey.hapticsEnabled)
        if hapticsEnabled {
            cautionFeedback.prepare()
            warningFeedback.prepare()
            signalFeedback.prepare()
        }
    }

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard isRunning, !isFrozen else { return }
        let displayTransform = makeDisplayTransform(for: frame)
        let depthData = frame.sceneDepth

        frameRenderer?.enqueue(
            LiDARRenderFrame(
                capturedImage: frame.capturedImage,
                depthMap: depthData?.depthMap,
                confidenceMap: depthData?.confidenceMap,
                displayTransform: displayTransform,
                maxDepthMeters: adaptiveMaxDepthMeters
            )
        )

        if frame.timestamp - lastUpdateTime < analysisInterval { return }
        lastUpdateTime = frame.timestamp

        guard let depthData else {
            DispatchQueue.main.async { [weak self] in
                self?.applyMeasurement(
                    distance: nil,
                    displayTransform: displayTransform,
                    adaptiveMaxDepth: nil,
                    signalQuality: .weak
                )
            }
            return
        }
        let depthMap = depthData.depthMap
        let confidenceMap = depthData.confidenceMap

        let trackingIsNormal: Bool
        if case .normal = frame.camera.trackingState {
            trackingIsNormal = true
        } else {
            trackingIsNormal = false
        }
        let analysis = analyzeDepthMap(
            depthMap,
            confidenceMap: confidenceMap,
            trackingIsNormal: trackingIsNormal
        )

        DispatchQueue.main.async { [weak self] in
            self?.applyMeasurement(
                distance: analysis.forwardDistance.map(Double.init),
                displayTransform: displayTransform,
                adaptiveMaxDepth: analysis.adaptiveMaxDepth,
                signalQuality: analysis.signalQuality
            )
        }
    }

    func session(_ session: ARSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            self?.applyMeasurement(
                distance: nil,
                displayTransform: nil,
                adaptiveMaxDepth: nil,
                signalQuality: .weak
            )
        }
    }

    func sessionWasInterrupted(_ session: ARSession) {
        DispatchQueue.main.async { [weak self] in
            self?.applyMeasurement(
                distance: nil,
                displayTransform: nil,
                adaptiveMaxDepth: nil,
                signalQuality: .weak
            )
        }
    }

    func sessionInterruptionEnded(_ session: ARSession) {
        guard isRunning else { return }
        DispatchQueue.main.async { [weak self] in
            self?.start()
        }
    }

    private func analyzeDepthMap(
        _ depthMap: CVPixelBuffer,
        confidenceMap: CVPixelBuffer?,
        trackingIsNormal: Bool
    ) -> LiDARFrameAnalysis {
        CVPixelBufferLockBaseAddress(depthMap, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(depthMap, .readOnly) }

        let lockedConfidenceMap: CVPixelBuffer? = {
            guard let confidenceMap else { return nil }
            CVPixelBufferLockBaseAddress(confidenceMap, .readOnly)
            return confidenceMap
        }()
        defer {
            if let lockedConfidenceMap {
                CVPixelBufferUnlockBaseAddress(lockedConfidenceMap, .readOnly)
            }
        }

        guard let baseAddress = CVPixelBufferGetBaseAddress(depthMap) else {
            return LiDARFrameAnalysis(
                forwardDistance: nil,
                adaptiveMaxDepth: adaptiveMaxDepthMeters,
                signalQuality: .weak
            )
        }
        let width = CVPixelBufferGetWidth(depthMap)
        let height = CVPixelBufferGetHeight(depthMap)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(depthMap)
        let stride = bytesPerRow / MemoryLayout<Float32>.size

        let buffer = baseAddress.assumingMemoryBound(to: Float32.self)
        let confidenceBuffer = confidenceMap.flatMap { CVPixelBufferGetBaseAddress($0) }?.assumingMemoryBound(to: UInt8.self)
        let confidenceStride = confidenceMap.map {
            CVPixelBufferGetBytesPerRow($0) / MemoryLayout<UInt8>.size
        } ?? 0

        let activeScanProfile = scanProfile
        let evaluationWindow = pixelWindow(for: activeScanProfile.evaluationRegion, width: width, height: height)
        let evaluatedPixelCount = (evaluationWindow.xRange.upperBound - evaluationWindow.xRange.lowerBound + 1)
            * (evaluationWindow.yRange.upperBound - evaluationWindow.yRange.lowerBound + 1)
        var histogram = LiDARDepthHistogram(maximumDepth: maxDepthLimit)
        var confidentPoints = 0
        var highConfidencePoints = 0

        for y in evaluationWindow.yRange {
            let rowOffset = y * stride
            for x in evaluationWindow.xRange {
                let depth = buffer[rowOffset + x]
                guard depth.isFinite, depth > 0, depth <= maxDepthLimit else { continue }

                if let confidenceBuffer, confidenceStride > 0 {
                    let confidenceValue = confidenceBuffer[y * confidenceStride + x]
                    guard confidenceValue >= minimumConfidence else { continue }
                    if confidenceValue >= 2 {
                        highConfidencePoints += 1
                    }
                } else {
                    highConfidencePoints += 1
                }

                confidentPoints += 1
                histogram.add(depth)
            }
        }

        let forwardDistance = histogram.percentile(0.22)
        let highConfidenceRatio = confidentPoints > 0
            ? Double(highConfidencePoints) / Double(confidentPoints)
            : 0
        let confidentCoverage = evaluatedPixelCount > 0
            ? Double(confidentPoints) / Double(evaluatedPixelCount)
            : 0
        let qualityScore = confidentCoverage * (0.72 + 0.28 * highConfidenceRatio)
        let signalQuality = trackingIsNormal
            ? LiDARProcessing.signalQuality(for: qualityScore)
            : .weak

        let upperDepth = histogram.percentile(0.85)
        let focusDepth = forwardDistance ?? upperDepth ?? 2.5
        let targetMaxDepth = LiDARProcessing.clampMaxDepth(
            max(2.5, focusDepth * 2.2, (upperDepth ?? focusDepth) * 1.15),
            min: 2.5,
            max: maxDepthLimit
        )
        let adaptiveMaxDepth = LiDARProcessing.smoothedValue(
            current: adaptiveMaxDepthMeters,
            target: targetMaxDepth,
            alpha: depthSmoothingAlpha
        )

        return LiDARFrameAnalysis(
            forwardDistance: forwardDistance,
            adaptiveMaxDepth: adaptiveMaxDepth,
            signalQuality: signalQuality
        )
    }

    private func applyMeasurement(
        distance newDistance: Double?,
        displayTransform: CGAffineTransform?,
        adaptiveMaxDepth: Float?,
        signalQuality: LiDARSignalQuality
    ) {
        if let newDistance, newDistance.isFinite, newDistance > 0 {
            lastRawDistance = newDistance
            let smoothed = lastDistance.map {
                let alpha = newDistance < $0 ? 0.72 : 0.22
                return $0 + alpha * (newDistance - $0)
            } ?? newDistance
            lastDistance = smoothed
            distanceMeters = smoothed
        } else {
            lastRawDistance = nil
            distanceMeters = nil
            lastDistance = nil
        }

        if let displayTransform {
            depthDisplayTransform = displayTransform
        }
        if let adaptiveMaxDepth {
            adaptiveMaxDepthMeters = LiDARProcessing.clampMaxDepth(adaptiveMaxDepth, min: 2.5, max: maxDepthLimit)
        }
        self.signalQuality = signalQuality
        statusKey = LiDARProcessing.statusKey(isSupported: isSupported, isRunning: isRunning, isFrozen: isFrozen)
        let proposedAlertState = LiDARProcessing.alertState(
            for: lastRawDistance,
            alertPreset: alertPreset,
            isRunning: isRunning,
            isFrozen: isFrozen
        )
        let transition = LiDARProcessing.stabilizedAlertState(
            current: alertState,
            proposed: proposedAlertState,
            distanceMeters: lastRawDistance,
            alertPreset: alertPreset,
            saferFrameStreak: saferFrameStreak
        )
        saferFrameStreak = transition.saferFrameStreak
        let nextAlertState = transition.state
        emitFeedbackIfNeeded(for: nextAlertState)
        alertState = nextAlertState
    }

    private func makeDisplayTransform(for frame: ARFrame) -> CGAffineTransform {
        guard viewportSize.width > 0, viewportSize.height > 0 else { return .identity }
        return frame.displayTransform(for: interfaceOrientation, viewportSize: viewportSize)
    }

    private func pixelWindow(for rect: CGRect, width: Int, height: Int) -> LiDARPixelWindow {
        let minX = max(0, min(width - 1, Int((rect.minX * CGFloat(width)).rounded(.down))))
        let maxX = max(minX, min(width - 1, Int((rect.maxX * CGFloat(width)).rounded(.down))))
        let minY = max(0, min(height - 1, Int((rect.minY * CGFloat(height)).rounded(.down))))
        let maxY = max(minY, min(height - 1, Int((rect.maxY * CGFloat(height)).rounded(.down))))
        return LiDARPixelWindow(xRange: minX...maxX, yRange: minY...maxY)
    }

    private func emitFeedbackIfNeeded(for newState: LiDARAlertState) {
        guard isRunning || isFrozen else { return }
        guard hapticsEnabled else {
            lastFeedbackState = newState
            return
        }

        let now = ProcessInfo.processInfo.systemUptime
        let interval: TimeInterval
        switch newState {
        case .danger:
            interval = 0.9
        case .caution:
            interval = 1.6
        case .signalLost:
            interval = 2.4
        case .clear:
            lastFeedbackState = .clear
            return
        }

        let shouldEmit = newState != lastFeedbackState || now - lastFeedbackTime >= interval
        guard shouldEmit else { return }

        switch newState {
        case .danger:
            warningFeedback.notificationOccurred(.error)
            warningFeedback.prepare()
        case .caution:
            cautionFeedback.impactOccurred(intensity: 1.0)
            cautionFeedback.prepare()
        case .signalLost:
            signalFeedback.impactOccurred(intensity: 0.7)
            signalFeedback.prepare()
        case .clear:
            break
        }

        lastFeedbackState = newState
        lastFeedbackTime = now
    }
}
