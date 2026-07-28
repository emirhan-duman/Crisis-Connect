//
//  SosLiveActivity.swift
//  CrisisConnectWidgets
//
//  Lock-screen card + Dynamic Island for an active SOS broadcast. The elapsed
//  time renders with the native timer style, so the activity needs no updates
//  while it runs. Tapping opens the app's SOS status via crisisconnect://sos.
//

import ActivityKit
import SwiftUI
import WidgetKit

struct SosLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SosActivityAttributes.self) { context in
            // Lock screen / notification banner presentation.
            HStack(spacing: 12) {
                SosGlyph()
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "WIDGET_SOS_LIVE_TITLE"))
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(String(localized: "WIDGET_SOS_LIVE_BODY"))
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Text(context.state.startedAt, style: .timer)
                    .font(.system(size: 22, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .frame(maxWidth: 80, alignment: .trailing)
            }
            .padding(14)
            .activityBackgroundTint(Color(red: 0.78, green: 0.13, blue: 0.13))
            .activitySystemActionForegroundColor(.white)
            .widgetURL(URL(string: "crisisconnect://sos"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    SosGlyph()
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(String(localized: "WIDGET_SOS_LIVE_TITLE"))
                        .font(.headline)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.startedAt, style: .timer)
                        .monospacedDigit()
                        .font(.system(.body, design: .rounded).weight(.semibold))
                        .frame(maxWidth: 64, alignment: .trailing)
                        .padding(.trailing, 4)
                }
            } compactLeading: {
                Text("SOS")
                    .font(.caption.bold())
                    .foregroundStyle(.red)
            } compactTrailing: {
                Text(context.state.startedAt, style: .timer)
                    .monospacedDigit()
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: 44)
            } minimal: {
                Text("!")
                    .font(.headline.bold())
                    .foregroundStyle(.red)
            }
            .widgetURL(URL(string: "crisisconnect://sos"))
            .keylineTint(.red)
        }
    }
}

private struct SosGlyph: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(.white.opacity(0.22))
                .frame(width: 36, height: 36)
            Text("SOS")
                .font(.system(size: 11, weight: .heavy, design: .rounded))
                .foregroundStyle(.white)
        }
    }
}
