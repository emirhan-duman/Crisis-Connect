import Foundation

/// Pure decision logic for the single-speaker telsiz floor lock (iOS port of Android `PttFloorRules`),
/// kept free of audio/BLE dependencies so it can be reasoned about and unit-tested in isolation.
///
/// Tie-break is by `claimId` (a random Int64 carried in each FLOOR_CLAIM): the higher claimId wins, so
/// two devices that press at the same instant independently converge on the same speaker. A claim from
/// the peer that already holds the floor always refreshes it.
enum PttFloorRules {

    /// Whether an incoming remote FLOOR_CLAIM should (re)assign the floor to `fromOrigin`.
    ///
    /// - Parameters:
    ///   - floor: current local floor state
    ///   - localClaimId: our claimId when we hold the floor (else nil)
    ///   - currentRemoteSpeaker: origin id of the current remote speaker (when `floor` is `.remoteSpeaking`)
    ///   - currentRemoteClaimId: claimId of the current remote speaker
    ///   - fromOrigin: origin id the incoming claim came from
    ///   - incomingClaimId: claimId carried by the incoming claim
    static func shouldGrantRemoteClaim(
        floor: PttFloorState,
        localClaimId: Int64?,
        currentRemoteSpeaker: String?,
        currentRemoteClaimId: Int64,
        fromOrigin: String,
        incomingClaimId: Int64
    ) -> Bool {
        switch floor {
        case .idle:
            return true
        case .localSpeaking:
            return incomingClaimId > (localClaimId ?? Int64.min)
        case .remoteSpeaking:
            return fromOrigin == currentRemoteSpeaker || incomingClaimId > currentRemoteClaimId
        }
    }
}
