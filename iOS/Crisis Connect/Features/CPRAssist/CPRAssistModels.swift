import Foundation

enum CPRAssistMode: String, CaseIterable, Identifiable {
    case handsOnly
    case thirtyToTwo

    var id: String { rawValue }
}

enum CPRAssistPhase: Equatable {
    case ready
    case compressions
    case breaths
    case ended
}

enum CPRPauseReason: Equatable {
    case manual
    case aedAnalysis
}

enum CPRAEDStep: Int, CaseIterable, Identifiable {
    case powerOn
    case attachPads
    case analyze
    case shockDecision
    case resumeCPR

    var id: Int { rawValue }
}

enum CPRAssistTiming {
    static let targetBPM = 110
    static let minimumRecommendedBPM = 100
    static let maximumRecommendedBPM = 120
    static let compressionsPerSet = 30
    static let breathPause: TimeInterval = 6
    static let roundDuration: TimeInterval = 120
    static let tickResolution: UInt64 = 20_000_000
    static let beatInterval = 60.0 / Double(targetBPM)

    static func nextCompression(inSet current: Int) -> Int {
        guard (1..<compressionsPerSet).contains(current) else { return 1 }
        return current + 1
    }

    static func completedSet(after count: Int) -> Bool {
        count == compressionsPerSet
    }

    static func roundRemaining(elapsed: TimeInterval) -> TimeInterval {
        min(max(roundDuration - elapsed, 0), roundDuration)
    }

    static func formatDuration(_ seconds: TimeInterval) -> String {
        let totalSeconds = max(0, Int(seconds.rounded(.down)))
        return String(format: "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
