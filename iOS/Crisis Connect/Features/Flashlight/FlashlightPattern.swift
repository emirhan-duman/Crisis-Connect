import Foundation

struct FlashlightPulse: Equatable {
    let isOn: Bool
    let durationNanoseconds: UInt64
}

enum FlashlightPatterns {
    private static let morseUnit: UInt64 = 200_000_000

    static let sos: [FlashlightPulse] = {
        var pulses: [FlashlightPulse] = []
        func signal(onUnits: UInt64, offUnits: UInt64) {
            pulses.append(.init(isOn: true, durationNanoseconds: onUnits * morseUnit))
            pulses.append(.init(isOn: false, durationNanoseconds: offUnits * morseUnit))
        }

        // SOS is transmitted as one continuous procedural signal: ...---...
        signal(onUnits: 1, offUnits: 1)
        signal(onUnits: 1, offUnits: 1)
        signal(onUnits: 1, offUnits: 1)
        signal(onUnits: 3, offUnits: 1)
        signal(onUnits: 3, offUnits: 1)
        signal(onUnits: 3, offUnits: 1)
        signal(onUnits: 1, offUnits: 1)
        signal(onUnits: 1, offUnits: 1)
        signal(onUnits: 1, offUnits: 7)
        return pulses
    }()

    /// Six visible signals in one minute, followed by a one-minute pause.
    static let emergencyBeacon: [FlashlightPulse] = {
        var pulses: [FlashlightPulse] = []
        for _ in 0..<6 {
            pulses.append(.init(isOn: true, durationNanoseconds: 1_000_000_000))
            pulses.append(.init(isOn: false, durationNanoseconds: 9_000_000_000))
        }
        pulses.append(.init(isOn: false, durationNanoseconds: 60_000_000_000))
        return pulses
    }()

    static func strobe(flashesPerSecond: Int) -> [FlashlightPulse] {
        let safeRate = UInt64(min(max(flashesPerSecond, 1), 3))
        // Round up so timer quantization never pushes the effective rate above 3 Hz.
        let halfPeriod = (500_000_000 + safeRate - 1) / safeRate
        return [
            .init(isOn: true, durationNanoseconds: halfPeriod),
            .init(isOn: false, durationNanoseconds: halfPeriod),
        ]
    }
}
