import AVFoundation
import Combine
import Foundation

@MainActor
final class FlashlightViewModel: ObservableObject {
    enum Mode: String, CaseIterable, Identifiable {
        case normal
        case sos
        case strobe
        case lowPower
        case screenLight
        case emergencyBeacon

        var id: String { rawValue }

        var titleKey: String {
            switch self {
            case .normal: return "FLASHLIGHT_MODE_NORMAL"
            case .sos: return "FLASHLIGHT_MODE_SOS"
            case .strobe: return "FLASHLIGHT_MODE_STROBE"
            case .lowPower: return "FLASHLIGHT_MODE_LOW_POWER"
            case .screenLight: return "FLASHLIGHT_MODE_SCREEN"
            case .emergencyBeacon: return "FLASHLIGHT_MODE_BEACON"
            }
        }

        var descriptionKey: String {
            "\(titleKey)_DESCRIPTION"
        }

        var systemImage: String {
            switch self {
            case .normal: return "flashlight.on.fill"
            case .sos: return "sos"
            case .strobe: return "bolt.fill"
            case .lowPower: return "battery.25percent"
            case .screenLight: return "sun.max.fill"
            case .emergencyBeacon: return "light.beacon.max.fill"
            }
        }
    }

    enum ScreenColor: String, CaseIterable, Identifiable {
        case white
        case warm
        case red

        var id: String { rawValue }
        var titleKey: String { "FLASHLIGHT_SCREEN_COLOR_\(rawValue.uppercased())" }
    }

    enum AutoOff: String, CaseIterable, Identifiable {
        case off
        case fiveMinutes
        case fifteenMinutes
        case thirtyMinutes

        var id: String { rawValue }

        var minutes: Int? {
            switch self {
            case .off: return nil
            case .fiveMinutes: return 5
            case .fifteenMinutes: return 15
            case .thirtyMinutes: return 30
            }
        }

        var titleKey: String {
            switch self {
            case .off: return "FLASHLIGHT_AUTO_OFF_NEVER"
            case .fiveMinutes: return "FLASHLIGHT_AUTO_OFF_5"
            case .fifteenMinutes: return "FLASHLIGHT_AUTO_OFF_15"
            case .thirtyMinutes: return "FLASHLIGHT_AUTO_OFF_30"
            }
        }
    }

    @Published private(set) var hasTorch: Bool
    @Published private(set) var isActive = false
    @Published var mode: Mode = .normal {
        didSet {
            guard mode != oldValue else { return }
            let shouldRestart = isActive
            stop()
            errorKey = nil
            if shouldRestart { requestStart() }
        }
    }
    @Published var intensity: Double {
        didSet {
            intensity = min(max(intensity, 0.1), 1)
            defaults.set(intensity, forKey: Keys.intensity)
            if isActive, mode == .normal {
                _ = applyTorch(enabled: true, intensity: intensity)
            }
        }
    }
    @Published var screenBrightness: Double {
        didSet {
            screenBrightness = min(max(screenBrightness, 0.2), 1)
            defaults.set(screenBrightness, forKey: Keys.screenBrightness)
        }
    }
    @Published var screenColor: ScreenColor = .white
    @Published var strobeRate: Int {
        didSet {
            strobeRate = min(max(strobeRate, 1), 3)
            defaults.set(strobeRate, forKey: Keys.strobeRate)
            if isActive, mode == .strobe { restartActiveMode() }
        }
    }
    @Published var autoOff: AutoOff {
        didSet {
            defaults.set(autoOff.rawValue, forKey: Keys.autoOff)
            if isActive { scheduleAutoOff() }
        }
    }
    @Published var showStrobeWarning = false
    @Published private(set) var errorKey: String?

    private let defaults: UserDefaults
    private let torch: AVTorchController
    private var patternTask: Task<Void, Never>?
    private var autoOffTask: Task<Void, Never>?
    private var permissionTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let torch = AVTorchController()
        self.torch = torch
        self.hasTorch = torch.hasTorch

        let storedIntensity = defaults.object(forKey: Keys.intensity) as? Double ?? 0.75
        self.intensity = min(max(storedIntensity, 0.1), 1)
        let storedScreenBrightness = defaults.object(forKey: Keys.screenBrightness) as? Double ?? 1
        self.screenBrightness = min(max(storedScreenBrightness, 0.2), 1)
        self.strobeRate = min(max(defaults.integer(forKey: Keys.strobeRate), 1), 3)
        if defaults.object(forKey: Keys.strobeRate) == nil {
            self.strobeRate = 2
        }
        self.autoOff = defaults.string(forKey: Keys.autoOff)
            .flatMap(AutoOff.init(rawValue:))
            ?? .fifteenMinutes
    }

    var isScreenLightActive: Bool {
        isActive && mode == .screenLight
    }

    func requestToggle() {
        if isActive {
            stop()
        } else if mode == .strobe && !defaults.bool(forKey: Keys.strobeWarningAcknowledged) {
            showStrobeWarning = true
        } else {
            requestStart()
        }
    }

    func confirmStrobeWarning() {
        defaults.set(true, forKey: Keys.strobeWarningAcknowledged)
        showStrobeWarning = false
        requestStart()
    }

    func dismissStrobeWarning() {
        showStrobeWarning = false
    }

    func stop() {
        permissionTask?.cancel()
        permissionTask = nil
        patternTask?.cancel()
        patternTask = nil
        autoOffTask?.cancel()
        autoOffTask = nil
        torch.disableBestEffort()
        isActive = false
        showStrobeWarning = false
    }

    private func requestStart() {
        errorKey = nil
        if mode == .screenLight {
            startAuthorized()
            return
        }
        guard hasTorch else {
            errorKey = "FLASHLIGHT_ERROR_UNAVAILABLE"
            return
        }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startAuthorized()
        case .notDetermined:
            permissionTask?.cancel()
            permissionTask = Task { [weak self] in
                let granted = await Self.requestCameraAccess()
                guard !Task.isCancelled, let self else { return }
                self.permissionTask = nil
                if granted {
                    self.startAuthorized()
                } else {
                    self.errorKey = "FLASHLIGHT_ERROR_CAMERA_PERMISSION"
                }
            }
        case .denied, .restricted:
            errorKey = "FLASHLIGHT_ERROR_CAMERA_PERMISSION"
        @unknown default:
            errorKey = "FLASHLIGHT_ERROR_UNAVAILABLE"
        }
    }

    private func startAuthorized() {
        patternTask?.cancel()
        isActive = true
        errorKey = nil
        scheduleAutoOff()

        switch mode {
        case .normal:
            _ = applyTorch(enabled: true, intensity: intensity)
        case .lowPower:
            _ = applyTorch(enabled: true, intensity: 0.1)
        case .sos:
            runPattern(FlashlightPatterns.sos)
        case .strobe:
            runPattern(FlashlightPatterns.strobe(flashesPerSecond: strobeRate))
        case .emergencyBeacon:
            runPattern(FlashlightPatterns.emergencyBeacon)
        case .screenLight:
            torch.disableBestEffort()
        }
    }

    private func restartActiveMode() {
        guard isActive else { return }
        patternTask?.cancel()
        torch.disableBestEffort()
        isActive = false
        startAuthorized()
    }

    private func runPattern(_ pattern: [FlashlightPulse]) {
        patternTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled && self.isActive {
                for pulse in pattern {
                    guard !Task.isCancelled, self.isActive else { return }
                    guard self.applyTorch(enabled: pulse.isOn, intensity: self.intensity) else {
                        return
                    }
                    try? await Task.sleep(nanoseconds: pulse.durationNanoseconds)
                }
            }
        }
    }

    private func scheduleAutoOff() {
        autoOffTask?.cancel()
        guard let minutes = autoOff.minutes else { return }
        autoOffTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60_000_000_000)
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    @discardableResult
    private func applyTorch(enabled: Bool, intensity: Double) -> Bool {
        do {
            try torch.set(enabled: enabled, intensity: Float(intensity))
            return true
        } catch {
            patternTask?.cancel()
            patternTask = nil
            autoOffTask?.cancel()
            autoOffTask = nil
            torch.disableBestEffort()
            isActive = false
            errorKey = "FLASHLIGHT_ERROR_CAMERA_IN_USE"
            return false
        }
    }

    private static func requestCameraAccess() async -> Bool {
        await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .video) { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    private enum Keys {
        static let intensity = "flashlight.intensity"
        static let screenBrightness = "flashlight.screenBrightness"
        static let strobeRate = "flashlight.strobeRate"
        static let autoOff = "flashlight.autoOff"
        static let strobeWarningAcknowledged = "flashlight.strobeWarningAcknowledged"
    }
}

private final class AVTorchController {
    private let device: AVCaptureDevice?

    init() {
        self.device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(for: .video)
    }

    var hasTorch: Bool {
        device?.hasTorch == true && device?.isTorchModeSupported(.on) == true
    }

    func set(enabled: Bool, intensity: Float) throws {
        guard let device, hasTorch, device.isTorchAvailable else {
            throw TorchError.unavailable
        }
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        if enabled {
            try device.setTorchModeOn(level: min(max(intensity, 0.1), AVCaptureDevice.maxAvailableTorchLevel))
        } else {
            device.torchMode = .off
        }
    }

    func disableBestEffort() {
        guard let device, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = .off
            device.unlockForConfiguration()
        } catch {
            // The device may be owned by another camera session; there is nothing else to release.
        }
    }

    private enum TorchError: Error {
        case unavailable
    }
}
