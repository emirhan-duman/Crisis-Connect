//
//  DisasterRepository.swift
//  Crisis Connect
//
//  Fetches multiple disaster sources in parallel, merges + de-duplicates the
//  results, and caches a normalized event list. Ported from the Android
//  DisasterRepository.
//

import Foundation

/// One feed source: a URL and the parser that normalizes its response.
private struct SourceSpec {
    let url: String
    let parser: @Sendable (Data) throws -> [DisasterEvent]
}

final class DisasterRepository: Sendable {

    private let session: URLSession
    private let cacheDirectory: URL

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 25
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        self.session = URLSession(configuration: configuration)

        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.cacheDirectory = caches
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /// Loads the requested feed by querying every source in parallel and merging
    /// the results. Serves a fresh cache without hitting the network; falls back
    /// to a stale cache only when every source fails.
    func load(feed: DisasterFeed, region: DisasterRegion?, forceRefresh: Bool) async -> DisasterFetchResult {
        let cache = cacheFile(feed: feed, region: region)

        // 1. Fresh cache → serve directly; we are NOT stale/offline.
        if !forceRefresh, isCacheFresh(cache), let cached = readCache(cache), !cached.events.isEmpty {
            return DisasterFetchResult(
                events: cached.events,
                lastUpdatedMillis: cached.modifiedMillis,
                isStale: false,
                error: nil,
                primarySource: sourcesLabel(cached.events, feed: feed)
            )
        }

        // 2. Query every source in parallel; nil = that source failed.
        let specs = sources(for: feed, region: region)
        let results = await withTaskGroup(of: [DisasterEvent]?.self) { group -> [[DisasterEvent]?] in
            for spec in specs {
                group.addTask { [self] in
                    do {
                        let data = try await httpGet(spec.url)
                        return try spec.parser(data)
                    } catch {
                        return nil
                    }
                }
            }
            var collected: [[DisasterEvent]?] = []
            for await result in group { collected.append(result) }
            return collected
        }

        let anySuccess = results.contains { $0 != nil }
        let all = results.compactMap { $0 }.flatMap { $0 }

        if anySuccess {
            let merged = recentSorted(dedupEarthquakes(all))
            writeCache(cache, events: merged)
            return DisasterFetchResult(
                events: merged,
                lastUpdatedMillis: nowMillis(),
                isStale: false,
                error: nil,
                primarySource: sourcesLabel(merged, feed: feed)
            )
        }

        // 3. Every source failed → offline. Serve the stale cache if we have one.
        if let cached = readCache(cache), !cached.events.isEmpty {
            return DisasterFetchResult(
                events: cached.events,
                lastUpdatedMillis: cached.modifiedMillis,
                isStale: true,
                error: .offline,
                primarySource: sourcesLabel(cached.events, feed: feed)
            )
        }

        return DisasterFetchResult(
            events: [],
            lastUpdatedMillis: 0,
            isStale: false,
            error: .noData,
            primarySource: defaultSource(feed)
        )
    }

    // ─── Networking ──────────────────────────────────────────────────────────

    private func httpGet(_ urlString: String) async throws -> Data {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("CrisisConnect", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        guard !data.isEmpty else { throw URLError(.zeroByteResource) }
        return data
    }

    // ─── Source wiring ─────────────────────────────────────────────────────────

    private func sources(for feed: DisasterFeed, region: DisasterRegion?) -> [SourceSpec] {
        switch feed {
        case .global:
            return [
                SourceSpec(url: gdacsUrl()) { try self.parseGdacs($0) },
                SourceSpec(url: usgsUrl(nil)) { try self.parseUsgs($0) },
                SourceSpec(url: emscUrl(nil)) { try self.parseEmsc($0) }
            ]
        case .local:
            guard let region else {
                return [
                    SourceSpec(url: usgsUrl(nil)) { try self.parseUsgs($0) },
                    SourceSpec(url: emscUrl(nil)) { try self.parseEmsc($0) }
                ]
            }
            var specs: [SourceSpec] = []
            if region.countryCode.uppercased() == "TR" {
                specs.append(SourceSpec(url: afadUrl()) { try self.parseAfad($0) })
                specs.append(SourceSpec(url: kandilliUrl()) { try self.parseKandilli($0) })
            }
            specs.append(SourceSpec(url: emscUrl(region)) { try self.parseEmsc($0) })
            specs.append(SourceSpec(url: usgsUrl(region)) { try self.parseUsgs($0) })
            specs.append(SourceSpec(url: gdacsUrl()) { data in
                try self.parseGdacs(data).filter { region.contains($0.latitude, $0.longitude) }
            })
            return specs
        }
    }

    private func gdacsUrl() -> String {
        "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=EQ;TC;FL;VO;WF;DR&pagesize=50"
    }

    private func afadUrl() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        let end = formatter.string(from: Date())
        let start = formatter.string(from: Date(timeIntervalSinceNow: -3 * 24 * 60 * 60))
        return "https://deprem.afad.gov.tr/apiv2/event/filter?start=\(start)&end=\(end)&limit=100"
    }

    // Community JSON mirror of Kandilli Observatory (KOERI) data.
    private func kandilliUrl() -> String {
        "https://api.orhanaydogdu.com.tr/deprem/kandilli/live?limit=100"
    }

    private func usgsUrl(_ region: DisasterRegion?) -> String {
        let base = "https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson"
            + "&orderby=time&limit=100&starttime=\(startDateIso())"
        if let region {
            return base + "&minmagnitude=2.5" + bboxQuery(region)
        }
        return base + "&minmagnitude=4.5"
    }

    private func emscUrl(_ region: DisasterRegion?) -> String {
        let base = "https://www.seismicportal.eu/fdsnws/event/1/query?format=json"
            + "&orderby=time&limit=100&starttime=\(startDateIso())"
        if let region {
            return base + "&minmagnitude=2.5" + bboxQuery(region)
        }
        return base + "&minmagnitude=4.5"
    }

    private func bboxQuery(_ region: DisasterRegion) -> String {
        "&minlatitude=\(region.minLat)&maxlatitude=\(region.maxLat)"
            + "&minlongitude=\(region.minLon)&maxlongitude=\(region.maxLon)"
    }

    private func startDateIso() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date(timeIntervalSinceNow: -Self.recentWindowSeconds))
    }

    // ─── Merge / de-duplication ──────────────────────────────────────────────

    private func dedupEarthquakes(_ events: [DisasterEvent]) -> [DisasterEvent] {
        let quakes = events.filter { $0.eventType.uppercased() == "EQ" }
        let others = events.filter { $0.eventType.uppercased() != "EQ" }
        var kept: [DisasterEvent] = []
        for event in quakes.sorted(by: { sourcePriority($0.source) > sourcePriority($1.source) }) {
            let isDuplicate = kept.contains { existing in
                existing.eventTimeMillis != 0 && event.eventTimeMillis != 0
                    && abs(existing.eventTimeMillis - event.eventTimeMillis) <= Self.dupTimeMillis
                    && DisasterGeo.haversineKm(existing.latitude, existing.longitude, event.latitude, event.longitude) <= Self.dupDistanceKm
            }
            if !isDuplicate { kept.append(event) }
        }
        return others + kept
    }

    private func sourcePriority(_ source: String) -> Int {
        switch source {
        case DisasterSource.afad: return 5
        case DisasterSource.kandilli: return 4
        case DisasterSource.usgs: return 3
        case DisasterSource.emsc: return 2
        case DisasterSource.gdacs: return 1
        default: return 0
        }
    }

    private func sourcesLabel(_ events: [DisasterEvent], feed: DisasterFeed) -> String {
        let labels = Array(Set(events.map { $0.source }.filter { !$0.isEmpty }))
            .sorted { sourcePriority($0) > sourcePriority($1) }
        return labels.isEmpty ? defaultSource(feed) : labels.joined(separator: " · ")
    }

    private func defaultSource(_ feed: DisasterFeed) -> String {
        feed == .global ? DisasterSource.gdacs : DisasterSource.afad
    }

    /// Drops events older than the recency window and sorts newest-first. Events
    /// whose date couldn't be parsed (epoch 0) are kept.
    private func recentSorted(_ events: [DisasterEvent]) -> [DisasterEvent] {
        let cutoff = nowMillis() - Int64(Self.recentWindowSeconds * 1000)
        return events
            .filter { $0.eventTimeMillis == 0 || $0.eventTimeMillis >= cutoff }
            .sorted { $0.eventTimeMillis > $1.eventTimeMillis }
    }

    // ─── Parsers ───────────────────────────────────────────────────────────────

    private func parseGdacs(_ data: Data) throws -> [DisasterEvent] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { return [] }
        var list: [DisasterEvent] = []
        for feature in features {
            guard let properties = feature["properties"] as? [String: Any],
                  let geometry = feature["geometry"] as? [String: Any] else { continue }
            let coordinates = geometry["coordinates"] as? [Any]
            let longitude = doubleAt(coordinates, 0)
            let latitude = doubleAt(coordinates, 1)

            let eventType = string(properties["eventtype"])
            let fromDate = string(properties["fromdate"])
            let severity = properties["severitydata"] as? [String: Any]
            let severityText = string(severity?["severitytext"])
            let magnitude: Double? = eventType.uppercased() == "EQ"
                ? doubleValue(severity?["severity"]) : nil

            list.append(DisasterEvent(
                eventId: intValue(properties["eventid"]) ?? 0,
                title: string(properties["name"], default: "Unknown Incident"),
                eventDescription: string(properties["description"]),
                htmlDescription: string(properties["htmldescription"]),
                eventType: eventType,
                alertLevel: string(properties["alertlevel"], default: "Green"),
                country: string(properties["country"], default: "Global"),
                fromDate: fromDate,
                toDate: string(properties["todate"]),
                severityText: severityText,
                magnitude: magnitude,
                source: DisasterSource.gdacs,
                eventTimeMillis: DisasterDate.epochMillis(fromDate, utc: true),
                latitude: latitude,
                longitude: longitude
            ))
        }
        return list
    }

    private func parseAfad(_ data: Data) throws -> [DisasterEvent] {
        guard let array = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        var list: [DisasterEvent] = []
        for object in array {
            let location = string(object["location"], default: "Türkiye")
            let latitude = Double(string(object["latitude"])) ?? 0.0
            let longitude = Double(string(object["longitude"])) ?? 0.0
            let depth = string(object["depth"], default: "0.0")
            let magnitude = string(object["magnitude"], default: "0.0")
            let magDouble = Double(magnitude) ?? 0.0
            let country = string(object["country"], default: "Türkiye")
            let dateStr = string(object["date"])
            let province = string(object["province"])
            let district = string(object["district"])
            let districtInfo = province.isEmpty ? location : "\(district), \(province)"

            list.append(DisasterEvent(
                eventId: Int(string(object["eventID"], default: "0")) ?? 0,
                title: RDLocalized.format("RECENT_DISASTERS_AFAD_TITLE", districtInfo),
                eventDescription: RDLocalized.format("RECENT_DISASTERS_AFAD_DESCRIPTION", location, magnitude, depth, dateStr),
                htmlDescription: "",
                eventType: "EQ",
                alertLevel: alertFromMagValue(magDouble),
                country: country,
                fromDate: dateStr,
                toDate: dateStr,
                severityText: RDLocalized.format("RECENT_DISASTERS_AFAD_SEVERITY", magnitude, depth),
                magnitude: magDouble > 0.0 ? magDouble : nil,
                source: DisasterSource.afad,
                eventTimeMillis: DisasterDate.epochMillis(dateStr, utc: false),
                latitude: latitude,
                longitude: longitude
            ))
        }
        return list
    }

    private func parseUsgs(_ data: Data) throws -> [DisasterEvent] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { return [] }
        var list: [DisasterEvent] = []
        for feature in features {
            guard let properties = feature["properties"] as? [String: Any],
                  let geometry = feature["geometry"] as? [String: Any] else { continue }
            let coordinates = geometry["coordinates"] as? [Any]
            let longitude = doubleAt(coordinates, 0)
            let latitude = doubleAt(coordinates, 1)
            let depth = doubleAt(coordinates, 2)

            let mag = doubleValue(properties["mag"])
            let place = string(properties["place"], default: "Unknown location")
            let title = string(properties["title"], default: place)
            let timeMs = int64Value(properties["time"]) ?? 0
            let usgsAlert = string(properties["alert"]).lowercased()

            let alertLevel: String
            switch usgsAlert {
            case "red": alertLevel = "Red"
            case "orange", "yellow": alertLevel = "Orange"
            case "green": alertLevel = "Green"
            default: alertLevel = alertFromMag(mag)
            }

            list.append(DisasterEvent(
                eventId: stableHash(string(properties["code"], default: "0")),
                title: title,
                eventDescription: place,
                htmlDescription: "",
                eventType: "EQ",
                alertLevel: alertLevel,
                country: place.components(separatedBy: ", ").last?.nilIfBlank ?? "—",
                fromDate: DisasterDate.isoUtc(timeMs),
                toDate: DisasterDate.isoUtc(timeMs),
                severityText: mag.map { String(format: "M %.1f • %.0f km", $0, abs(depth)) } ?? "",
                magnitude: mag,
                source: DisasterSource.usgs,
                eventTimeMillis: timeMs,
                latitude: latitude,
                longitude: longitude
            ))
        }
        return list
    }

    private func parseEmsc(_ data: Data) throws -> [DisasterEvent] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { return [] }
        var list: [DisasterEvent] = []
        for feature in features {
            guard let properties = feature["properties"] as? [String: Any] else { continue }
            let coordinates = (feature["geometry"] as? [String: Any])?["coordinates"] as? [Any]
            let longitude = doubleAt(coordinates, 0, fallback: doubleValue(properties["lon"]) ?? 0)
            let latitude = doubleAt(coordinates, 1, fallback: doubleValue(properties["lat"]) ?? 0)
            let depth = doubleAt(coordinates, 2, fallback: doubleValue(properties["depth"]) ?? 0)

            let mag = doubleValue(properties["mag"])
            let region = string(properties["flynn_region"], default: string(properties["region"]))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let timeMs = DisasterDate.epochMillis(string(properties["time"]), utc: true)

            list.append(DisasterEvent(
                eventId: stableHash(string(properties["unid"], default: string(properties["source_id"], default: "0"))),
                title: buildEqTitle(mag: mag, region: region),
                eventDescription: region,
                htmlDescription: "",
                eventType: "EQ",
                alertLevel: alertFromMag(mag),
                country: region.nilIfBlank ?? "—",
                fromDate: DisasterDate.isoUtc(timeMs),
                toDate: DisasterDate.isoUtc(timeMs),
                severityText: mag.map { String(format: "M %.1f • %.0f km", $0, abs(depth)) } ?? "",
                magnitude: mag,
                source: DisasterSource.emsc,
                eventTimeMillis: timeMs,
                latitude: latitude,
                longitude: longitude
            ))
        }
        return list
    }

    private func parseKandilli(_ data: Data) throws -> [DisasterEvent] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = root["result"] as? [[String: Any]] else { return [] }
        var list: [DisasterEvent] = []
        for object in result {
            let coordinates = (object["geojson"] as? [String: Any])?["coordinates"] as? [Any]
            let lon = doubleAt(coordinates, 0, fallback: doubleValue(object["lng"]) ?? 0)
            let lat = doubleAt(coordinates, 1, fallback: doubleValue(object["lat"]) ?? 0)

            let mag = doubleValue(object["mag"])
            let magStr = mag.map { String(format: "%.1f", $0) } ?? "0.0"
            let depthStr = String(format: "%.1f", doubleValue(object["depth"]) ?? 0)
            var place = string(object["title"])
            if place.isEmpty {
                let epiCenter = (object["location_properties"] as? [String: Any])?["epiCenter"] as? [String: Any]
                place = string(epiCenter?["name"])
            }
            var rawDate = string(object["date_time"])
            if rawDate.isEmpty {
                rawDate = string(object["date"]).replacingOccurrences(of: ".", with: "-")
            }
            let magDouble = mag ?? 0.0

            list.append(DisasterEvent(
                eventId: stableHash(string(object["earthquake_id"], default: string(object["_id"], default: "0"))),
                title: RDLocalized.format("RECENT_DISASTERS_AFAD_TITLE", place.nilIfBlank ?? "Türkiye"),
                eventDescription: place,
                htmlDescription: "",
                eventType: "EQ",
                alertLevel: alertFromMagValue(magDouble),
                country: "Türkiye",
                fromDate: rawDate,
                toDate: rawDate,
                severityText: RDLocalized.format("RECENT_DISASTERS_AFAD_SEVERITY", magStr, depthStr),
                magnitude: mag,
                source: DisasterSource.kandilli,
                eventTimeMillis: DisasterDate.epochMillis(rawDate, utc: false),
                latitude: lat,
                longitude: lon
            ))
        }
        return list
    }

    private func alertFromMag(_ mag: Double?) -> String {
        let value = mag ?? 0.0
        if value >= 6.0 { return "Red" }
        if value >= 4.5 { return "Orange" }
        return "Green"
    }

    private func alertFromMagValue(_ mag: Double) -> String {
        if mag >= 4.5 { return "Red" }
        if mag >= 3.0 { return "Orange" }
        return "Green"
    }

    private func buildEqTitle(mag: Double?, region: String) -> String {
        let m = mag.map { String(format: "M%.1f", $0) }
        switch (m, region.isEmpty) {
        case let (.some(magText), false): return "\(magText) — \(region)"
        case let (.some(magText), true): return magText
        case (.none, false): return region
        default: return "Earthquake"
        }
    }

    // ─── Cache ──────────────────────────────────────────────────────────────────

    private func cacheFile(feed: DisasterFeed, region: DisasterRegion?) -> URL {
        let key: String
        if feed == .global {
            key = "global"
        } else {
            let code = region?.countryCode.lowercased().nilIfBlank
            key = code ?? "local"
        }
        return cacheDirectory.appendingPathComponent("recent_disasters_\(key)_v2.json")
    }

    private func isCacheFresh(_ url: URL) -> Bool {
        guard let modified = modifiedDate(url) else { return false }
        return modified.timeIntervalSinceNow >= -Self.cacheTtlSeconds
    }

    private func readCache(_ url: URL) -> (events: [DisasterEvent], modifiedMillis: Int64)? {
        guard let data = try? Data(contentsOf: url),
              let events = try? JSONDecoder().decode([DisasterEvent].self, from: data) else {
            return nil
        }
        let modifiedMillis = modifiedDate(url).map { Int64($0.timeIntervalSince1970 * 1000) } ?? nowMillis()
        return (events, modifiedMillis)
    }

    private func writeCache(_ url: URL, events: [DisasterEvent]) {
        guard let data = try? JSONEncoder().encode(events) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private func modifiedDate(_ url: URL) -> Date? {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
    }

    // ─── Small helpers ────────────────────────────────────────────────────────

    private func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func string(_ value: Any?, default defaultValue: String = "") -> String {
        if let stringValue = value as? String { return stringValue }
        if let number = value as? NSNumber { return number.stringValue }
        return defaultValue
    }

    private func doubleValue(_ value: Any?) -> Double? {
        switch value {
        case let double as Double where double.isFinite: return double
        case let int as Int: return Double(int)
        case let number as NSNumber:
            let resolved = number.doubleValue
            return resolved.isFinite ? resolved : nil
        case let string as String: return Double(string)
        default: return nil
        }
    }

    private func intValue(_ value: Any?) -> Int? {
        switch value {
        case let int as Int: return int
        case let number as NSNumber: return number.intValue
        case let string as String: return Int(string)
        default: return nil
        }
    }

    private func int64Value(_ value: Any?) -> Int64? {
        switch value {
        case let int as Int: return Int64(int)
        case let number as NSNumber: return number.int64Value
        case let string as String: return Int64(string)
        case let double as Double where double.isFinite: return Int64(double)
        default: return nil
        }
    }

    private func doubleAt(_ array: [Any]?, _ index: Int, fallback: Double = 0.0) -> Double {
        guard let array, index < array.count else { return fallback }
        return doubleValue(array[index]) ?? fallback
    }

    /// Deterministic hash (djb2) for building stable ids out of source string ids.
    private func stableHash(_ string: String) -> Int {
        var hash: UInt64 = 5381
        for byte in string.utf8 { hash = (hash &* 33) ^ UInt64(byte) }
        return Int(truncatingIfNeeded: hash & 0x7FFF_FFFF)
    }

    // ─── Tuning knobs ─────────────────────────────────────────────────────────

    private static let cacheTtlSeconds: TimeInterval = 5 * 60
    private static let recentWindowSeconds: TimeInterval = 30 * 24 * 60 * 60
    private static let dupTimeMillis: Int64 = 120_000
    private static let dupDistanceKm: Double = 150.0
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
