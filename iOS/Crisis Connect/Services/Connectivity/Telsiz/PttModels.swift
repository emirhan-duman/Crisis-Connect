import Foundation

// Telsiz (push-to-talk) over the authority GATT mesh — iOS port of the Android `service.gattmesh.ptt`
// package. Half-duplex walkie-talkie: one speaker at a time (single-speaker floor lock), Opus audio
// broadcast (multi-hop) to mesh peers. These public types back the telsiz UI.

/// Single-speaker telsiz floor state (mirrors Android `PttFloorState`).
enum PttFloorState {
    /// Nobody is talking; the floor is free to claim.
    case idle
    /// This device holds the floor and is transmitting.
    case localSpeaking
    /// A remote peer holds the floor and is transmitting.
    case remoteSpeaking
}

/// Control message kinds exchanged for telsiz session/floor coordination.
enum PttControlKind {
    case join
    case leave
    case floorClaim
    case floorRelease
}

struct PttParticipant: Identifiable, Equatable {
    /// Stable peer origin id, or `PttParticipant.selfAddress` for the local device.
    let address: String
    let displayName: String
    var isSelf: Bool = false
    var isSpeaking: Bool = false
    /// Verified institution (kurum, e.g. AFAD/FEMA) the participant belongs to, shown beside the name.
    var agency: String? = nil

    var id: String { address }

    static let selfAddress = "self"
}

struct PttSessionState: Equatable {
    /// Whether the local user has joined the telsiz session.
    var joined: Bool = false
    /// Everyone currently joined (including self when `joined`).
    var participants: [PttParticipant] = []
    var floor: PttFloorState = .idle
    /// Display name of whoever currently holds the floor, or nil when idle.
    var speakerName: String? = nil
    /// Monotonic counter bumped when the local press was rejected because the floor was taken.
    /// The UI observes it to surface a transient "telsiz busy" hint.
    var busyTick: Int = 0
    /// Monotonic counter bumped when the local transmission auto-released at the max talk duration.
    var maxTalkTick: Int = 0

    var isActive: Bool { joined || !participants.isEmpty }
    var participantCount: Int { participants.count }
}
