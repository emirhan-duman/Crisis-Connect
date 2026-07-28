//
//  RecentDisastersWidget.swift
//  CrisisConnectWidgets
//
//  Latest nearby disasters on the home screen. The provider reuses the app's
//  DisasterRepository (compiled into this target) with its own extension-side
//  cache, so the widget refreshes itself every ~30 minutes without needing the
//  app to run. The region comes from the App Group (written by the app) with a
//  device-locale fallback — the widget never touches location services.
//

import SwiftUI
import UIKit
import WidgetKit

struct DisastersEntry: TimelineEntry {
    let date: Date
    let events: [DisasterEvent]
    let regionName: String?
    let updatedMillis: Int64
}

struct DisastersProvider: TimelineProvider {
    func placeholder(in context: Context) -> DisastersEntry {
        DisastersEntry(date: .now, events: Self.sampleEvents, regionName: nil, updatedMillis: 0)
    }

    func getSnapshot(in context: Context, completion: @escaping (DisastersEntry) -> Void) {
        completion(placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DisastersEntry>) -> Void) {
        Task {
            let region = WidgetSharedState.loadRegion() ?? WidgetSharedState.regionFromDeviceLocale()
            let feed: DisasterFeed = region == nil ? .global : .local
            let repository = DisasterRepository()
            let result = await repository.load(feed: feed, region: region, forceRefresh: false)
            let entry = DisastersEntry(
                date: .now,
                events: Array(result.events.prefix(8)),
                regionName: region?.countryName,
                updatedMillis: result.lastUpdatedMillis
            )
            let next = Date.now.addingTimeInterval(30 * 60)
            completion(Timeline(entries: [entry], policy: .after(next)))
        }
    }

    static let sampleEvents: [DisasterEvent] = [
        DisasterEvent(
            eventId: 1, title: "Aegean Sea", eventDescription: "", htmlDescription: "",
            eventType: "EQ", alertLevel: "Orange", country: "", fromDate: "", toDate: "",
            severityText: "", magnitude: 5.8, source: "AFAD",
            eventTimeMillis: Int64(Date.now.timeIntervalSince1970 * 1000) - 12 * 60 * 1000,
            latitude: 0, longitude: 0
        ),
        DisasterEvent(
            eventId: 2, title: "Van", eventDescription: "", htmlDescription: "",
            eventType: "EQ", alertLevel: "Green", country: "", fromDate: "", toDate: "",
            severityText: "", magnitude: 4.1, source: "Kandilli",
            eventTimeMillis: Int64(Date.now.timeIntervalSince1970 * 1000) - 2 * 60 * 60 * 1000,
            latitude: 0, longitude: 0
        ),
        DisasterEvent(
            eventId: 3, title: "Balıkesir", eventDescription: "", htmlDescription: "",
            eventType: "EQ", alertLevel: "Green", country: "", fromDate: "", toDate: "",
            severityText: "", magnitude: 3.6, source: "EMSC",
            eventTimeMillis: Int64(Date.now.timeIntervalSince1970 * 1000) - 5 * 60 * 60 * 1000,
            latitude: 0, longitude: 0
        )
    ]
}

struct RecentDisastersWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: WidgetSharedState.disastersWidgetKind,
            provider: DisastersProvider()
        ) { entry in
            DisastersWidgetView(entry: entry)
                .widgetURL(URL(string: "crisisconnect://disasters"))
        }
        .configurationDisplayName(String(localized: "WIDGET_DISASTERS_TITLE"))
        .description(String(localized: "WIDGET_DISASTERS_DESC"))
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

private struct DisastersWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DisastersEntry

    private var maxRows: Int { family == .systemLarge ? 7 : 3 }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            if entry.events.isEmpty {
                Spacer(minLength: 0)
                Text(String(localized: "WIDGET_DISASTERS_EMPTY"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                Spacer(minLength: 0)
            } else {
                ForEach(entry.events.prefix(maxRows)) { event in
                    EventRow(event: event)
                }
                Spacer(minLength: 0)
            }
        }
        .containerBackground(for: .widget) {
            Color(UIColor.systemBackground)
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(headerTitle)
                .font(.system(size: 14, weight: .bold))
                .lineLimit(1)
            Spacer(minLength: 6)
            if entry.updatedMillis > 0 {
                Text(Self.relativeText(millis: entry.updatedMillis))
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }

    private var headerTitle: String {
        let base = String(localized: "WIDGET_DISASTERS_TITLE")
        guard let region = entry.regionName, !region.isEmpty else { return base }
        return "\(base) · \(region)"
    }

    static func relativeText(millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: .now)
    }
}

private struct EventRow: View {
    let event: DisasterEvent

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(accentColor.opacity(0.16))
                    .frame(width: 36, height: 36)
                Text(badgeText)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(accentColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(event.title)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                Text(subLine)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
    }

    private var subLine: String {
        var parts: [String] = []
        if event.eventTimeMillis > 0 {
            parts.append(DisastersWidgetView.relativeText(millis: event.eventTimeMillis))
        }
        if !event.source.isEmpty {
            parts.append(event.source)
        }
        return parts.joined(separator: " · ")
    }

    /// Mirrors the in-app badge: "M5.2" for earthquakes, a type glyph otherwise.
    private var badgeText: String {
        if event.eventType.uppercased() == "EQ", let magnitude = event.magnitude, magnitude > 0 {
            return String(format: "M%.1f", magnitude)
        }
        switch event.eventType.uppercased() {
        case "FL": return "🌊"
        case "TC": return "🌀"
        case "VO": return "🌋"
        case "WF": return "🔥"
        case "DR": return "🏜"
        default: return "⚠️"
        }
    }

    /// Same palette as the in-app alertColorFor(), with a magnitude fallback.
    private var accentColor: Color {
        let level = event.alertLevel.lowercased()
        let magnitude = event.magnitude ?? 0
        let known = ["red", "orange", "green"]
        switch true {
        case level == "red" || (!known.contains(level) && magnitude >= 6.0):
            return Color(red: 0.83, green: 0.18, blue: 0.18)
        case level == "orange" || (!known.contains(level) && magnitude >= 5.0):
            return Color(red: 0.94, green: 0.42, blue: 0.0)
        case level == "green" || (!known.contains(level) && magnitude > 0):
            return Color(red: 0.18, green: 0.49, blue: 0.2)
        default:
            return Color(red: 0.38, green: 0.49, blue: 0.55)
        }
    }
}
