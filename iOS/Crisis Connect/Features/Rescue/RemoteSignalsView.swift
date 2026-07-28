//
//  RemoteSignalsView.swift
//  Crisis Connect
//
//  The agency panel's live SOS feed on the phone — internet self-reports and other teams'
//  sightings — mirroring Android's RemoteSignalsScreen design: pulsing hero summary,
//  freshness-colored accent cards, prominent map action.
//

import SwiftUI
import UIKit

struct RemoteSignalsView: View {
    @ObservedObject private var service = RemoteSosSignalsService.shared
    @Environment(\.dismiss) private var dismiss

    private static let freshWindowMillis: Int64 = 10 * 60 * 1000

    var body: some View {
        NavigationStack {
            Group {
                if service.isLoading {
                    VStack(spacing: 14) {
                        ProgressView()
                        Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_LOADING"))
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if service.signals.isEmpty {
                    VStack(spacing: 14) {
                        Image(systemName: "icloud.slash")
                            .font(.system(size: 40, weight: .medium))
                            .foregroundStyle(Color.appTextSecondary)
                            .frame(width: 88, height: 88)
                            .background(Circle().fill(Color.appRowBackground))
                        Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_EMPTY_TITLE"))
                            .font(.headline)
                        Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_EMPTY"))
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 14) {
                            RemoteSignalsHero(count: service.signals.count)
                            ForEach(service.signals) { signal in
                                RemoteSignalRow(signal: signal)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                }
            }
            .navigationTitle(Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_TITLE")))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
            }
            .appNavigationBarStyle()
        }
        .task { service.start() }
    }
}

/// Live-feed summary header: pulsing beacon + active count.
private struct RemoteSignalsHero: View {
    let count: Int
    @State private var pulsing = false

    var body: some View {
        HStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(Color.red.opacity(pulsing ? 0.18 : 0.06))
                    .frame(width: 52, height: 52)
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(Color.red.opacity(pulsing ? 1 : 0.55))
            }
            .animation(
                .easeInOut(duration: 1.1).repeatForever(autoreverses: true),
                value: pulsing
            )
            VStack(alignment: .leading, spacing: 3) {
                Text(String(
                    format: NSLocalizedString("RESCUE_REMOTE_HERO_COUNT", comment: ""),
                    count
                ))
                .font(.title3.weight(.bold))
                Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_SUBTITLE"))
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }
            Spacer()
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color.red.opacity(0.14), Color.orange.opacity(0.10)],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
        )
        .onAppear { pulsing = true }
    }
}

private struct RemoteSignalRow: View {
    let signal: RemoteSosSignal

    private var isFresh: Bool {
        Int64(Date().timeIntervalSince1970 * 1000) - signal.lastSeenMillis <= 10 * 60 * 1000
    }

    private var accent: Color { isFresh ? .red : .orange }

    private var lastSeenText: String {
        let date = Date(timeIntervalSince1970: TimeInterval(signal.lastSeenMillis) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    var body: some View {
        HStack(spacing: 0) {
            // Status accent rail: red = heard within the last 10 minutes, amber otherwise.
            RoundedRectangle(cornerRadius: 3)
                .fill(accent.opacity(0.85))
                .frame(width: 5)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(signal.victimName ?? String(
                            format: NSLocalizedString("RESCUE_REMOTE_SIGNAL_UNNAMED", comment: ""),
                            String(signal.id.suffix(6)).uppercased()
                        ))
                        .font(.headline)
                        .lineLimit(1)
                        HStack(spacing: 6) {
                            Circle()
                                .fill(accent)
                                .frame(width: 7, height: 7)
                            Text(lastSeenText)
                                .font(.caption)
                                .foregroundStyle(Color.appTextSecondary)
                        }
                    }
                    Spacer()
                    HStack(spacing: 5) {
                        Image(systemName: "cloud.fill")
                            .font(.system(size: 11))
                        Text(LocalizedStringKey(
                            signal.source == "internet"
                                ? "RESCUE_REMOTE_SOURCE_INTERNET"
                                : "RESCUE_REMOTE_SOURCE_FIELD"
                        ))
                        .font(.caption.weight(.medium))
                    }
                    .foregroundStyle(accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(accent.opacity(0.12)))
                }

                HStack(spacing: 8) {
                    if let battery = signal.victimBatteryPercent {
                        RemoteInfoChip(
                            systemName: battery < 20 ? "battery.25" : "battery.75",
                            text: "%\(battery)",
                            tint: battery < 20 ? .red : Color.appTextSecondary
                        )
                    }
                    if signal.reporterCount > 0 {
                        RemoteInfoChip(
                            systemName: "person.2.fill",
                            text: String(
                                format: NSLocalizedString("RESCUE_REMOTE_REPORTERS_FORMAT", comment: ""),
                                signal.reporterCount
                            ),
                            tint: Color.appTextSecondary
                        )
                    }
                }

                if signal.hasLocation {
                    Button {
                        openInMaps()
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "map.fill")
                                .font(.subheadline)
                            Text(LocalizedStringKey("RESCUE_OPEN_VICTIM_LOCATION"))
                                .font(.subheadline.weight(.semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.appPrimary)
                    .background(
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .fill(Color.appPrimary.opacity(0.12))
                    )
                }
            }
            .padding(.leading, 14)
            .padding([.trailing, .vertical], 14)
        }
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.appRowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(accent.opacity(0.22), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func openInMaps() {
        guard let latitude = signal.latitude, let longitude = signal.longitude else { return }
        let name = (signal.victimName ?? "SOS")
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "SOS"
        guard let url = URL(
            string: "https://maps.apple.com/?ll=\(latitude),\(longitude)&q=\(name)"
        ) else { return }
        UIApplication.shared.open(url)
    }
}

private struct RemoteInfoChip: View {
    let systemName: String
    let text: String
    let tint: Color

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemName)
                .font(.system(size: 12))
            Text(text)
                .font(.caption.weight(.medium))
                .lineLimit(1)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
    }
}

/// Entry card for RescueClientView: live remote-signal count, tap → full feed.
struct RemoteSignalsEntryCard: View {
    @ObservedObject private var service = RemoteSosSignalsService.shared
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(Color.red.opacity(0.12))
                        .frame(width: 44, height: 44)
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.red)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_TITLE"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(LocalizedStringKey("RESCUE_REMOTE_SIGNALS_SUBTITLE"))
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
                Spacer()
                if !service.signals.isEmpty {
                    Text("\(service.signals.count)")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(Color.red))
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color.appRowBackground)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Color.primary.opacity(0.05), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .task { service.start() }
    }
}
