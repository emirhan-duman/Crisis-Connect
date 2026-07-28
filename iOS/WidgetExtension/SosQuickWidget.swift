//
//  SosQuickWidget.swift
//  CrisisConnectWidgets
//
//  One-tap SOS entry point. Tapping opens the in-app SOS flow, whose 5-second
//  arming countdown remains the guard against accidental taps — the widget
//  never triggers the broadcast directly.
//

import SwiftUI
import WidgetKit

struct SosEntry: TimelineEntry {
    let date: Date
}

struct SosProvider: TimelineProvider {
    func placeholder(in context: Context) -> SosEntry { SosEntry(date: .now) }

    func getSnapshot(in context: Context, completion: @escaping (SosEntry) -> Void) {
        completion(SosEntry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SosEntry>) -> Void) {
        completion(Timeline(entries: [SosEntry(date: .now)], policy: .never))
    }
}

struct SosQuickWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "SosQuickWidget", provider: SosProvider()) { _ in
            SosWidgetView()
                .widgetURL(URL(string: "crisisconnect://sos"))
        }
        .configurationDisplayName(String(localized: "WIDGET_SOS_TITLE"))
        .description(String(localized: "WIDGET_SOS_DESC"))
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

private struct SosWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            switch family {
            case .accessoryCircular:
                ZStack {
                    AccessoryWidgetBackground()
                    Text("SOS")
                        .font(.system(size: 15, weight: .heavy, design: .rounded))
                }
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 1) {
                    Text("SOS")
                        .font(.system(size: 17, weight: .heavy, design: .rounded))
                    Text(String(localized: "WIDGET_SOS_SUBTITLE"))
                        .font(.caption2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            default:
                VStack(spacing: 4) {
                    Text("SOS")
                        .font(.system(size: 36, weight: .heavy, design: .rounded))
                        .foregroundStyle(.white)
                    Text(String(localized: "WIDGET_SOS_SUBTITLE"))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.white.opacity(0.88))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .containerBackground(for: .widget) {
            if family == .systemSmall {
                LinearGradient(
                    colors: [
                        Color(red: 0.94, green: 0.27, blue: 0.25),
                        Color(red: 0.78, green: 0.16, blue: 0.16)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            } else {
                Color.clear
            }
        }
    }
}
