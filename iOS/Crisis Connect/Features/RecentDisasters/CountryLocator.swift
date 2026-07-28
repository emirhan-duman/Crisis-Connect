//
//  CountryLocator.swift
//  Crisis Connect
//
//  Detects the user's current country for the "your country" disaster tab.
//  Ported from the Android CountryLocator. The layered strategy mirrors Android,
//  adapted to iOS: GPS reverse-geocode → IP geolocation → device locale region.
//  (SIM/network country is omitted: CTCarrier is deprecated and unreliable on
//  modern iOS, so the locale fallback covers that role.)
//

import Foundation
import CoreLocation

actor CountryLocator {
    private let session: URLSession
    private let geocoder = CLGeocoder()

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 12
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: configuration)
    }

    /// Resolves a [DisasterRegion] for the "local" tab, or nil if nothing could
    /// be determined. `coordinate` is the best last-known device location (if
    /// any); the locator does not request permission itself.
    func detect(coordinate: CLLocationCoordinate2D?) async -> DisasterRegion? {
        var iso: String?
        var coords: (lat: Double, lon: Double)?

        if let coordinate {
            coords = (coordinate.latitude, coordinate.longitude)
            let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
            iso = await reverseGeocodeCountry(location)
        }

        // 2. IP geolocation
        if iso.isNilOrBlank {
            if let ip = await ipLookup() {
                iso = ip.iso
                if coords == nil, let lat = ip.lat, let lon = ip.lon {
                    coords = (lat, lon)
                }
            }
        }

        // 3. Device locale region (last resort)
        if iso.isNilOrBlank {
            iso = localeRegionCode()
        }

        return buildRegion(iso: iso, coords: coords)
    }

    private func buildRegion(iso: String?, coords: (lat: Double, lon: Double)?) -> DisasterRegion? {
        let code = iso?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let validCode = (code?.count == 2) ? code : nil
        let box = validCode.flatMap { CountryBounds.bbox($0) }
        let name = validCode.flatMap { displayCountry($0) }

        if let box, let validCode {
            return DisasterRegion(
                countryCode: validCode,
                countryName: name ?? validCode,
                minLat: box[0],
                maxLat: box[1],
                minLon: box[2],
                maxLon: box[3]
            )
        }

        if let coords {
            return DisasterRegion(
                countryCode: validCode ?? "",
                countryName: name ?? "",
                minLat: coords.lat - Self.radiusDegrees,
                maxLat: coords.lat + Self.radiusDegrees,
                minLon: coords.lon - Self.radiusDegrees,
                maxLon: coords.lon + Self.radiusDegrees
            )
        }

        return nil
    }

    private func displayCountry(_ iso: String) -> String {
        let localized = RDLocalized.locale.localizedString(forRegionCode: iso)
        return (localized?.isEmpty == false) ? localized! : iso
    }

    private func reverseGeocodeCountry(_ location: CLLocation) async -> String? {
        await withCheckedContinuation { continuation in
            geocoder.reverseGeocodeLocation(location, preferredLocale: RDLocalized.locale) { placemarks, _ in
                continuation.resume(returning: placemarks?.first?.isoCountryCode)
            }
        }
    }

    private func localeRegionCode() -> String? {
        let region: String?
        if #available(iOS 16.0, *) {
            region = Locale.current.region?.identifier
        } else {
            region = Locale.current.regionCode
        }
        let trimmed = region?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed?.isEmpty == false) ? trimmed : nil
    }

    // ─── IP geolocation ────────────────────────────────────────────────────────

    private struct IpResult {
        let iso: String
        let lat: Double?
        let lon: Double?
    }

    private func ipLookup() async -> IpResult? {
        if let primary = await ipLookup(from: "https://ipapi.co/json/") {
            return primary
        }
        return await ipLookup(from: "https://ipwho.is/")
    }

    private func ipLookup(from urlString: String) async -> IpResult? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("CrisisConnect", forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return nil
            }
            if (json["error"] as? Bool) == true { return nil }
            if let success = json["success"] as? Bool, success == false { return nil }

            let iso = (json["country_code"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !iso.isEmpty else { return nil }
            return IpResult(iso: iso.uppercased(), lat: doubleValue(json["latitude"]), lon: doubleValue(json["longitude"]))
        } catch {
            return nil
        }
    }

    private func doubleValue(_ value: Any?) -> Double? {
        switch value {
        case let double as Double where double.isFinite: return double
        case let int as Int: return Double(int)
        case let number as NSNumber: return number.doubleValue.isFinite ? number.doubleValue : nil
        case let string as String: return Double(string)
        default: return nil
        }
    }

    // Half-size of the fallback bounding box (degrees) when a country isn't in the
    // table but we know the user's coordinates (~500 km each way).
    private static let radiusDegrees = 4.5
}

private extension Optional where Wrapped == String {
    var isNilOrBlank: Bool {
        switch self {
        case .none: return true
        case .some(let value): return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }
}
