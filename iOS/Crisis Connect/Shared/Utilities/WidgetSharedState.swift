//
//  WidgetSharedState.swift
//  Crisis Connect
//
//  Region handoff between the app and the widget extension via the shared App
//  Group. The app persists the last successfully detected country; the widget
//  uses it to fetch the same "local" disasters feed without location access.
//  Compiled into BOTH the app and the widget extension targets.
//

import Foundation

enum WidgetSharedState {
    static let appGroupId = "group.com.auralis.crisisconnect"
    /// WidgetKit kind of the Recent Disasters widget (used for targeted reloads).
    static let disastersWidgetKind = "RecentDisastersWidget"

    private static let regionKey = "widget.disasters.region"

    private static var defaults: UserDefaults? { UserDefaults(suiteName: appGroupId) }

    static func saveRegion(_ region: DisasterRegion) {
        let encoded = [
            region.countryCode,
            region.countryName,
            String(region.minLat),
            String(region.maxLat),
            String(region.minLon),
            String(region.maxLon)
        ].joined(separator: "|")
        defaults?.set(encoded, forKey: regionKey)
    }

    static func loadRegion() -> DisasterRegion? {
        guard let raw = defaults?.string(forKey: regionKey) else { return nil }
        let parts = raw.components(separatedBy: "|")
        guard parts.count == 6,
              let minLat = Double(parts[2]), let maxLat = Double(parts[3]),
              let minLon = Double(parts[4]), let maxLon = Double(parts[5]) else { return nil }
        return DisasterRegion(
            countryCode: parts[0],
            countryName: parts[1],
            minLat: minLat,
            maxLat: maxLat,
            minLon: minLon,
            maxLon: maxLon
        )
    }

    /// Fallback when the app never detected a region: the device-locale country,
    /// if we have a bounding box for it.
    static func regionFromDeviceLocale() -> DisasterRegion? {
        guard let code = Locale.current.region?.identifier.uppercased(),
              code.count == 2,
              let box = CountryBounds.bbox(code) else { return nil }
        let name = Locale.current.localizedString(forRegionCode: code) ?? code
        return DisasterRegion(
            countryCode: code,
            countryName: name,
            minLat: box[0],
            maxLat: box[1],
            minLon: box[2],
            maxLon: box[3]
        )
    }
}
