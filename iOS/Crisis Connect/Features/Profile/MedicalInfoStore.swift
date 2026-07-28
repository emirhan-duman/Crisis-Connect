//
//  MedicalInfoStore.swift
//  Crisis Connect
//
//  The user's optional emergency medical details (blood type, allergies, medication, note).
//  Deliberately LOCAL + BLE-ONLY: shared over the encrypted SOS peer_info with a verified
//  rescuer during an emergency, never uploaded to the cloud. Wire format matches Android's
//  MedicalInfoStore / BlePeerIdentityUtils ("medical": {blood, allergies, meds, notes}).
//

import Foundation

struct MedicalInfo: Equatable {
    var bloodType: String = ""
    var allergies: String = ""
    var medication: String = ""
    var notes: String = ""

    var isEmpty: Bool {
        bloodType.isBlank && allergies.isBlank && medication.isBlank && notes.isBlank
    }

    func sanitized() -> MedicalInfo {
        MedicalInfo(
            bloodType: String(bloodType.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(MedicalInfoStore.maxBloodLength)),
            allergies: String(allergies.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(MedicalInfoStore.maxFieldLength)),
            medication: String(medication.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(MedicalInfoStore.maxFieldLength)),
            notes: String(notes.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(MedicalInfoStore.maxFieldLength))
        )
    }

    /// The `"medical"` object for the peer_info payload; nil when everything is blank.
    func toPeerInfoJSON() -> [String: Any]? {
        let sanitized = sanitized()
        guard !sanitized.isEmpty else { return nil }
        var json: [String: Any] = [:]
        if !sanitized.bloodType.isBlank { json["blood"] = sanitized.bloodType }
        if !sanitized.allergies.isBlank { json["allergies"] = sanitized.allergies }
        if !sanitized.medication.isBlank { json["meds"] = sanitized.medication }
        if !sanitized.notes.isBlank { json["notes"] = sanitized.notes }
        return json.isEmpty ? nil : json
    }
}

enum MedicalInfoStore {
    static let maxBloodLength = 8
    static let maxFieldLength = 200

    private static let bloodKey = "medical.bloodType"
    private static let allergiesKey = "medical.allergies"
    private static let medicationKey = "medical.medication"
    private static let notesKey = "medical.notes"

    static func load(userDefaults: UserDefaults = .standard) -> MedicalInfo {
        MedicalInfo(
            bloodType: userDefaults.string(forKey: bloodKey) ?? "",
            allergies: userDefaults.string(forKey: allergiesKey) ?? "",
            medication: userDefaults.string(forKey: medicationKey) ?? "",
            notes: userDefaults.string(forKey: notesKey) ?? ""
        )
    }

    static func save(_ info: MedicalInfo, userDefaults: UserDefaults = .standard) {
        let sanitized = info.sanitized()
        userDefaults.set(sanitized.bloodType, forKey: bloodKey)
        userDefaults.set(sanitized.allergies, forKey: allergiesKey)
        userDefaults.set(sanitized.medication, forKey: medicationKey)
        userDefaults.set(sanitized.notes, forKey: notesKey)
    }
}

private extension String {
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
