//
//  LiDARViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import Foundation
import Combine
import ARKit
import CoreImage
import CoreGraphics
import UIKit

enum LiDARPalette: String, Identifiable {
    case nightVision

    var id: String { rawValue }

    var titleKey: String { "LIDAR_PALETTE_NIGHT" }

    var darkColor: CIColor {
        CIColor(red: 0.0, green: 0.05, blue: 0.02)
    }

    var lightColor: CIColor {
        CIColor(red: 0.2, green: 1.0, blue: 0.45)
    }

    var contrast: Double { 1.35 }
    var brightness: Double { 0.04 }
    var saturation: Double { 1.2 }
    var gamma: Double { 0.78 }
}

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

    fileprivate func normalizedRegion(for lane: LiDARSamplingLane) -> CGRect {
        let region = evaluationRegion
        switch lane {
        case .left:
            return CGRect(
                x: region.minX + region.width * 0.04,
                y: region.minY + region.height * 0.24,
                width: region.width * 0.24,
                height: region.height * 0.52
            )
        case .center:
            return CGRect(
                x: region.minX + region.width * 0.31,
                y: region.minY + region.height * 0.08,
                width: region.width * 0.38,
                height: region.height * 0.76
            )
        case .right:
            return CGRect(
                x: region.minX + region.width * 0.72,
                y: region.minY + region.height * 0.24,
                width: region.width * 0.24,
                height: region.height * 0.52
            )
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

enum LiDARGuidanceState: Equatable {
    case moveForward
    case moveLeft
    case moveRight
    case stop
    case scanSlowly

    var titleKey: String {
        switch self {
        case .moveForward: return "LIDAR_GUIDANCE_FORWARD"
        case .moveLeft: return "LIDAR_GUIDANCE_LEFT"
        case .moveRight: return "LIDAR_GUIDANCE_RIGHT"
        case .stop: return "LIDAR_GUIDANCE_STOP"
        case .scanSlowly: return "LIDAR_GUIDANCE_SCAN"
        }
    }

    var detailKey: String {
        switch self {
        case .moveForward: return "LIDAR_GUIDANCE_FORWARD_DETAIL"
        case .moveLeft: return "LIDAR_GUIDANCE_LEFT_DETAIL"
        case .moveRight: return "LIDAR_GUIDANCE_RIGHT_DETAIL"
        case .stop: return "LIDAR_GUIDANCE_STOP_DETAIL"
        case .scanSlowly: return "LIDAR_GUIDANCE_SCAN_DETAIL"
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

struct LiDARLaneSnapshot: Equatable {
    let left: Float?
    let center: Float?
    let right: Float?
}

enum LiDARProcessing {
    static func clampMaxDepth(_ value: Float, min minDepth: Float = 1.0, max maxDepth: Float = 8.0) -> Float {
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

    static func guidance(
        for snapshot: LiDARLaneSnapshot,
        forwardDistance: Float?,
        signalQuality: LiDARSignalQuality,
        isRunning: Bool,
        isFrozen: Bool
    ) -> LiDARGuidanceState {
        guard isRunning || isFrozen else { return .scanSlowly }
        guard signalQuality != .weak else { return .scanSlowly }

        let center = laneValue(snapshot.center, fallback: forwardDistance)
        let left = laneValue(snapshot.left, fallback: center)
        let right = laneValue(snapshot.right, fallback: center)
        let forward = laneValue(forwardDistance, fallback: center)

        if forward < 0.85 {
            if right > left + 0.4, right > 1.1 {
                return .moveRight
            }
            if left > right + 0.4, left > 1.1 {
                return .moveLeft
            }
            return .stop
        }

        if center < 1.2 {
            if right > center + 0.35, right > 1.25 {
                return .moveRight
            }
            if left > center + 0.35, left > 1.25 {
                return .moveLeft
            }
            return .stop
        }

        if right > left + 0.75, right > center + 0.35 {
            return .moveRight
        }
        if left > right + 0.75, left > center + 0.35 {
            return .moveLeft
        }

        return .moveForward
    }

    static func recommendedMaxDepth(
        from samples: [Float],
        focusDistance: Float?,
        min minDepth: Float = 2.5,
        max maxDepth: Float = 8.0
    ) -> Float {
        let upperPercentile = percentileDepth(from: samples, percentile: 0.85)
        let focus = focusDistance ?? upperPercentile ?? minDepth
        let candidate = max(minDepth, focus * 2.2, (upperPercentile ?? focus) * 1.15)
        return clampMaxDepth(candidate, min: minDepth, max: maxDepth)
    }

    static func smoothedValue(current: Float, target: Float, alpha: Float = 0.22) -> Float {
        current + alpha * (target - current)
    }

    private static func laneValue(_ value: Float?, fallback: Float?) -> Float {
        if let value, value.isFinite, value > 0 {
            return value
        }
        if let fallback, fallback.isFinite, fallback > 0 {
            return fallback
        }
        return 0
    }
}

private enum LiDARSamplingLane: CaseIterable {
    case left
    case center
    case right
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
    let guidance: LiDARGuidanceState
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
    @Published private(set) var depthImage: CGImage?
    @Published private(set) var depthDisplayTransform: CGAffineTransform = .identity
    @Published private(set) var palette: LiDARPalette = .nightVision
    @Published private(set) var alertState: LiDARAlertState = .signalLost
    @Published private(set) var adaptiveMaxDepthMeters: Float = 5.0
    @Published private(set) var signalQuality: LiDARSignalQuality = .weak
    @Published private(set) var guidanceState: LiDARGuidanceState = .scanSlowly
    @Published private(set) var alertPreset: LiDARAlertPreset
    @Published private(set) var scanProfile: LiDARScanProfile
    @Published private(set) var hapticsEnabled: Bool
    @Published var isFrozen: Bool = false

    private let defaults: UserDefaults
    private weak var session: ARSession?
    private var pendingStart: Bool = false
    private var lastUpdateTime: TimeInterval = 0
    private var lastDistance: Double?
    private let distanceSmoothingAlpha: Double = 0.18
    private let depthSmoothingAlpha: Float = 0.22
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])
    private let minDepthMeters: Float = 1.0
    private let maxDepthLimit: Float = 8.0
    private let minimumConfidence: UInt8 = 1
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
        session.delegate = self
        if pendingStart {
            pendingStart = false
            start()
        }
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
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.smoothedSceneDepth) {
            configuration.frameSemantics.insert(.smoothedSceneDepth)
        } else {
            configuration.frameSemantics.insert(.sceneDepth)
        }
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.meshWithClassification) {
            configuration.sceneReconstruction = .meshWithClassification
        } else if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            configuration.sceneReconstruction = .mesh
        }
        session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        lastUpdateTime = 0
        distanceMeters = nil
        lastDistance = nil
        depthImage = nil
        adaptiveMaxDepthMeters = 5.0
        signalQuality = .weak
        guidanceState = .scanSlowly
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
        depthImage = nil
        lastDistance = nil
        adaptiveMaxDepthMeters = 5.0
        signalQuality = .weak
        guidanceState = .scanSlowly
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
            for: distanceMeters,
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
            for: distanceMeters,
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
        if frame.timestamp - lastUpdateTime < 0.2 { return }
        lastUpdateTime = frame.timestamp
        let displayTransform = makeDisplayTransform(for: frame)

        guard let depthData = frame.smoothedSceneDepth ?? frame.sceneDepth else {
            DispatchQueue.main.async { [weak self] in
                self?.applyMeasurement(
                    distance: nil,
                    depthImage: nil,
                    displayTransform: displayTransform,
                    adaptiveMaxDepth: nil,
                    signalQuality: .weak,
                    guidance: .scanSlowly
                )
            }
            return
        }
        let depthMap = depthData.depthMap
        let confidenceMap = depthData.confidenceMap

        let analysis = analyzeDepthMap(depthMap, confidenceMap: confidenceMap)
        let palette = self.palette
        let maxDepth = analysis.adaptiveMaxDepth
        let depthImage = makeNightVisionImage(
            capturedImage: frame.capturedImage,
            from: depthMap,
            confidenceMap: confidenceMap,
            palette: palette,
            maxDepth: maxDepth
        )

        DispatchQueue.main.async { [weak self] in
            self?.applyMeasurement(
                distance: analysis.forwardDistance.map(Double.init),
                depthImage: depthImage,
                displayTransform: displayTransform,
                adaptiveMaxDepth: analysis.adaptiveMaxDepth,
                signalQuality: analysis.signalQuality,
                guidance: analysis.guidance
            )
        }
    }

    func session(_ session: ARSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            self?.applyMeasurement(
                distance: nil,
                depthImage: nil,
                displayTransform: nil,
                adaptiveMaxDepth: nil,
                signalQuality: .weak,
                guidance: .scanSlowly
            )
        }
    }

    func sessionWasInterrupted(_ session: ARSession) {
        DispatchQueue.main.async { [weak self] in
            self?.applyMeasurement(
                distance: nil,
                depthImage: nil,
                displayTransform: nil,
                adaptiveMaxDepth: nil,
                signalQuality: .weak,
                guidance: .scanSlowly
            )
        }
    }

    func sessionInterruptionEnded(_ session: ARSession) {
        guard isRunning else { return }
        DispatchQueue.main.async { [weak self] in
            self?.start()
        }
    }

    private func analyzeDepthMap(_ depthMap: CVPixelBuffer, confidenceMap: CVPixelBuffer?) -> LiDARFrameAnalysis {
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
                signalQuality: .weak,
                guidance: .scanSlowly
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
        let laneWindows = Dictionary(uniqueKeysWithValues: LiDARSamplingLane.allCases.map {
            ($0, pixelWindow(for: activeScanProfile.normalizedRegion(for: $0), width: width, height: height))
        })

        var allSamples: [Float] = []
        var leftSamples: [Float] = []
        var centerSamples: [Float] = []
        var rightSamples: [Float] = []
        let capacity = (evaluationWindow.xRange.upperBound - evaluationWindow.xRange.lowerBound + 1)
            * (evaluationWindow.yRange.upperBound - evaluationWindow.yRange.lowerBound + 1)
        allSamples.reserveCapacity(capacity)
        leftSamples.reserveCapacity(capacity / 3)
        centerSamples.reserveCapacity(capacity / 3)
        rightSamples.reserveCapacity(capacity / 3)

        var totalValidPoints = 0
        var confidentPoints = 0

        for y in evaluationWindow.yRange {
            let rowOffset = y * stride
            for x in evaluationWindow.xRange {
                let depth = buffer[rowOffset + x]
                guard depth.isFinite, depth > 0 else { continue }
                totalValidPoints += 1

                if let confidenceBuffer, confidenceStride > 0 {
                    let confidenceValue = confidenceBuffer[y * confidenceStride + x]
                    guard confidenceValue >= minimumConfidence else { continue }
                }

                confidentPoints += 1
                allSamples.append(depth)

                if let leftWindow = laneWindows[.left], leftWindow.contains(x: x, y: y) {
                    leftSamples.append(depth)
                }
                if let centerWindow = laneWindows[.center], centerWindow.contains(x: x, y: y) {
                    centerSamples.append(depth)
                }
                if let rightWindow = laneWindows[.right], rightWindow.contains(x: x, y: y) {
                    rightSamples.append(depth)
                }
            }
        }

        let snapshot = LiDARLaneSnapshot(
            left: laneDepth(from: leftSamples),
            center: laneDepth(from: centerSamples),
            right: laneDepth(from: rightSamples)
        )
        let forwardDistance = LiDARProcessing.conservativeDepth(from: allSamples, percentile: 0.22)
            ?? snapshot.center
            ?? LiDARProcessing.representativeDepth(from: allSamples)
        let confidentRatio = totalValidPoints > 0 ? Double(confidentPoints) / Double(totalValidPoints) : 0
        let signalQuality = LiDARProcessing.signalQuality(for: confidentRatio)
        let guidance = LiDARProcessing.guidance(
            for: snapshot,
            forwardDistance: forwardDistance,
            signalQuality: signalQuality,
            isRunning: isRunning,
            isFrozen: isFrozen
        )
        let targetMaxDepth = LiDARProcessing.recommendedMaxDepth(
            from: allSamples,
            focusDistance: snapshot.center ?? forwardDistance
        )
        let adaptiveMaxDepth = LiDARProcessing.smoothedValue(
            current: adaptiveMaxDepthMeters,
            target: targetMaxDepth,
            alpha: depthSmoothingAlpha
        )

        return LiDARFrameAnalysis(
            forwardDistance: forwardDistance,
            adaptiveMaxDepth: adaptiveMaxDepth,
            signalQuality: signalQuality,
            guidance: guidance
        )
    }

    private func makeNightVisionImage(
        capturedImage: CVPixelBuffer,
        from depthMap: CVPixelBuffer,
        confidenceMap: CVPixelBuffer?,
        palette: LiDARPalette,
        maxDepth: Float
    ) -> CGImage? {
        let cameraImage = makeNightVisionCameraImage(from: capturedImage)
        guard let overlayImage = makeDepthOverlayImage(
            from: depthMap,
            confidenceMap: confidenceMap,
            palette: palette,
            maxDepth: maxDepth
        ) else {
            return ciContext.createCGImage(cameraImage, from: cameraImage.extent)
        }

        let overlayCIImage = CIImage(cgImage: overlayImage)
        let scaleX = cameraImage.extent.width / overlayCIImage.extent.width
        let scaleY = cameraImage.extent.height / overlayCIImage.extent.height
        let scaledOverlay = overlayCIImage
            .transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
            .applyingFilter("CIGaussianBlur", parameters: ["inputRadius": 1.6])
            .cropped(to: cameraImage.extent)
        let composited = scaledOverlay.applyingFilter("CISourceOverCompositing", parameters: [
            kCIInputBackgroundImageKey: cameraImage
        ])
        return ciContext.createCGImage(composited, from: cameraImage.extent)
    }

    private func makeNightVisionCameraImage(from capturedImage: CVPixelBuffer) -> CIImage {
        let baseImage = CIImage(cvPixelBuffer: capturedImage)
        let exposed = baseImage.applyingFilter("CIExposureAdjust", parameters: [
            "inputEV": 0.65
        ])
        let tuned = exposed.applyingFilter("CIColorControls", parameters: [
            kCIInputContrastKey: 1.2,
            kCIInputBrightnessKey: -0.02,
            kCIInputSaturationKey: 0.18
        ])
        return tuned.applyingFilter("CIColorMonochrome", parameters: [
            "inputColor": CIColor(red: 0.5, green: 1.0, blue: 0.58),
            "inputIntensity": 0.92
        ])
    }

    private func makeDepthOverlayImage(
        from depthMap: CVPixelBuffer,
        confidenceMap: CVPixelBuffer?,
        palette: LiDARPalette,
        maxDepth: Float
    ) -> CGImage? {
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

        guard let baseAddress = CVPixelBufferGetBaseAddress(depthMap) else { return nil }
        let width = CVPixelBufferGetWidth(depthMap)
        let height = CVPixelBufferGetHeight(depthMap)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(depthMap)
        let stride = bytesPerRow / MemoryLayout<Float32>.size
        let buffer = baseAddress.assumingMemoryBound(to: Float32.self)
        let confidenceBuffer = confidenceMap.flatMap { CVPixelBufferGetBaseAddress($0) }?.assumingMemoryBound(to: UInt8.self)
        let confidenceStride = confidenceMap.map {
            CVPixelBufferGetBytesPerRow($0) / MemoryLayout<UInt8>.size
        } ?? 0

        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let maxDepth = max(maxDepth, minDepthMeters)
        for y in 0..<height {
            let rowOffset = y * stride
            for x in 0..<width {
                let outputOffset = ((y * width) + x) * 4
                if let confidenceBuffer, confidenceStride > 0 {
                    let confidenceValue = confidenceBuffer[y * confidenceStride + x]
                    if confidenceValue < minimumConfidence {
                        continue
                    }
                }
                let depth = buffer[rowOffset + x]
                guard depth.isFinite else {
                    continue
                }

                let normalized = Double(min(max(depth / maxDepth, 0), 1))
                let proximity = pow(1.0 - normalized, palette.gamma)
                let rightDepth = x + 1 < width ? buffer[rowOffset + x + 1] : depth
                let downDepth = y + 1 < height ? buffer[(y + 1) * stride + x] : depth
                let gradient = (rightDepth.isFinite ? abs(depth - rightDepth) : 0)
                    + (downDepth.isFinite ? abs(depth - downDepth) : 0)
                let edgeBoost = min(max(Double(gradient) / 0.45, 0), 1)

                var alpha = proximity * 0.1 + edgeBoost * 0.32
                if depth < 1.1 {
                    alpha = max(alpha, 0.42)
                } else if depth < 2.2 {
                    alpha = max(alpha, 0.22)
                }
                let clampedAlpha = min(max(alpha, 0), 0.55)

                let tint = (
                    red: palette.lightColor.red,
                    green: palette.lightColor.green,
                    blue: palette.lightColor.blue
                )
                rgba[outputOffset] = UInt8(tint.red * clampedAlpha * 255.0)
                rgba[outputOffset + 1] = UInt8(tint.green * clampedAlpha * 255.0)
                rgba[outputOffset + 2] = UInt8(tint.blue * clampedAlpha * 255.0)
                rgba[outputOffset + 3] = UInt8(clampedAlpha * 255.0)
            }
        }

        let data = Data(rgba)
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        return CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    private func applyMeasurement(
        distance newDistance: Double?,
        depthImage: CGImage?,
        displayTransform: CGAffineTransform?,
        adaptiveMaxDepth: Float?,
        signalQuality: LiDARSignalQuality,
        guidance: LiDARGuidanceState
    ) {
        if let newDistance, newDistance.isFinite, newDistance > 0 {
            let smoothed = lastDistance.map {
                $0 + distanceSmoothingAlpha * (newDistance - $0)
            } ?? newDistance
            lastDistance = smoothed
            distanceMeters = smoothed
        } else {
            distanceMeters = nil
            lastDistance = nil
        }

        self.depthImage = depthImage
        if let displayTransform {
            depthDisplayTransform = displayTransform
        }
        if let adaptiveMaxDepth {
            adaptiveMaxDepthMeters = LiDARProcessing.clampMaxDepth(adaptiveMaxDepth, min: 2.5, max: maxDepthLimit)
        }
        self.signalQuality = signalQuality
        guidanceState = guidance
        statusKey = LiDARProcessing.statusKey(isSupported: isSupported, isRunning: isRunning, isFrozen: isFrozen)
        let nextAlertState = LiDARProcessing.alertState(
            for: distanceMeters,
            alertPreset: alertPreset,
            isRunning: isRunning,
            isFrozen: isFrozen
        )
        emitFeedbackIfNeeded(for: nextAlertState)
        alertState = nextAlertState
    }

    private func makeDisplayTransform(for frame: ARFrame) -> CGAffineTransform {
        guard viewportSize.width > 0, viewportSize.height > 0 else { return .identity }
        return frame.displayTransform(for: interfaceOrientation, viewportSize: viewportSize)
    }

    private func laneDepth(from samples: [Float]) -> Float? {
        LiDARProcessing.conservativeDepth(from: samples, percentile: 0.28)
            ?? LiDARProcessing.representativeDepth(from: samples)
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
