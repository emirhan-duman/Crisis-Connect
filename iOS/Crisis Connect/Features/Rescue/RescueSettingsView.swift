//
//  RescueSettingsView.swift
//  Crisis Connect
//
//  Created by Assistant on 12.03.2026.
//

import SwiftUI

struct RescueSettingsView: View {
    @StateObject private var settings = RescueSettingsStore.shared

    var body: some View {
        Form {
            Section {
                Toggle("RESCUE_SETTINGS_AUTO_START", isOn: $settings.autoStartScanning)
                Toggle("RESCUE_SETTINGS_AUTO_CONNECT", isOn: $settings.autoConnectToScannedBroadcasts)
                Toggle("RESCUE_SETTINGS_ONLY_ACTIVE", isOn: $settings.showOnlyActiveSignals)
            } header: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_DISCOVERY_SECTION"))
            } footer: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_DISCOVERY_FOOTER"))
            }

            Section {
                Toggle("RESCUE_SETTINGS_DEVICE_IDENTIFIER", isOn: $settings.showDeviceIdentifier)
            } header: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_DISPLAY_SECTION"))
            } footer: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_DEVICE_IDENTIFIER_FOOTER"))
            }

            Section {
                Toggle("RESCUE_SETTINGS_MESH_ALWAYS_ON", isOn: $settings.meshAlwaysOn)
                    .disabled(!settings.canUseMeshAlwaysOn)

                Toggle("RESCUE_SETTINGS_CRISIS_LINK", isOn: $settings.crisisLinkEnabled)
                    .disabled(!settings.canUseCrisisLink)

                Toggle("RESCUE_SETTINGS_LIVE_LOCATION", isOn: $settings.crisisLinkLiveLocationEnabled)
                    .disabled(!settings.canUseCrisisLink)

                Picker(
                    LocalizedStringKey("RESCUE_SETTINGS_LIVE_LOCATION_INTERVAL"),
                    selection: $settings.crisisLinkLiveLocationIntervalSeconds
                ) {
                    ForEach(RescueSettingsStore.liveLocationIntervalOptionsSeconds, id: \.self) { interval in
                        Text(
                            String(
                                format: NSLocalizedString("RESCUE_SETTINGS_LIVE_LOCATION_INTERVAL_FORMAT", comment: ""),
                                interval
                            )
                        )
                        .tag(interval)
                    }
                }
                .disabled(!settings.canUseCrisisLink)
            } header: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_RELAY_SECTION"))
            } footer: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_RELAY_FOOTER"))
            }

            Section {
                RescueSettingsStatusRow(
                    titleKey: "RESCUE_SETTINGS_MESH_ALWAYS_ON",
                    statusKey: settings.canUseMeshAlwaysOn
                        ? "RESCUE_SETTINGS_STATUS_AVAILABLE"
                        : "RESCUE_SETTINGS_STATUS_UNAVAILABLE"
                )
                RescueSettingsStatusRow(
                    titleKey: "RESCUE_SETTINGS_CRISIS_LINK",
                    statusKey: settings.canUseCrisisLink
                        ? "RESCUE_SETTINGS_STATUS_AVAILABLE"
                        : "RESCUE_SETTINGS_STATUS_UNAVAILABLE"
                )
            } header: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_AVAILABILITY_SECTION"))
            } footer: {
                Text(LocalizedStringKey("RESCUE_SETTINGS_AVAILABILITY_FOOTER"))
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .navigationTitle("RESCUE_SETTINGS_TITLE")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct RescueSettingsStatusRow: View {
    let titleKey: String
    let statusKey: String

    var body: some View {
        HStack(spacing: 12) {
            Text(LocalizedStringKey(titleKey))
            Spacer()
            Text(LocalizedStringKey(statusKey))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}

#Preview {
    NavigationStack {
        RescueSettingsView()
    }
}
