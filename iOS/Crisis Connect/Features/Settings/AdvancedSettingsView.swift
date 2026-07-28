//
//  AdvancedSettingsView.swift
//  Crisis Connect
//
//  Created by Codex on 25.03.2026.
//

import SwiftUI

struct AdvancedSettingsView: View {
    @StateObject private var settings = AdvancedSettingsStore.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                AdvancedPublicMeshCard(
                    publicMeshEnabled: $settings.publicMeshEnabled,
                    notificationsEnabled: $settings.gattMeshNotificationsEnabled,
                    available: settings.canUsePublicMesh
                )

                AdvancedToggleCard(
                    titleKey: "ADVANCED_SETTING_BATTERY_SAVER_TITLE",
                    descriptionKey: "ADVANCED_SETTING_BATTERY_SAVER_DESCRIPTION",
                    iconName: "battery.75",
                    tint: .appPrimary,
                    isOn: $settings.batterySaverMode
                )

                AdvancedToggleCard(
                    titleKey: "ADVANCED_SETTING_DELIVERY_RETRY_TITLE",
                    descriptionKey: "ADVANCED_SETTING_DELIVERY_RETRY_DESCRIPTION",
                    iconName: "arrow.triangle.2.circlepath",
                    tint: .appPrimary,
                    isOn: $settings.deliveryRetryEnabled
                )

                AdvancedToggleCard(
                    titleKey: "ADVANCED_SETTING_SHARE_LAST_SEEN_TITLE",
                    descriptionKey: "ADVANCED_SETTING_SHARE_LAST_SEEN_DESCRIPTION",
                    iconName: "eye.fill",
                    tint: .appPrimary,
                    isOn: $settings.shareLastSeenEnabled
                )

                AdvancedToggleCard(
                    titleKey: "ADVANCED_SETTING_DIAGNOSTICS_UPLOAD_TITLE",
                    descriptionKey: "ADVANCED_SETTING_DIAGNOSTICS_UPLOAD_DESCRIPTION",
                    iconName: "exclamationmark.triangle.fill",
                    tint: .appWarning,
                    isOn: $settings.diagnosticsUploadEnabled
                )

                AdvancedToggleCard(
                    titleKey: "ADVANCED_SETTING_EXPERIMENTAL_FEATURES_TITLE",
                    descriptionKey: "ADVANCED_SETTING_EXPERIMENTAL_FEATURES_DESCRIPTION",
                    iconName: "sparkles",
                    tint: .appDanger,
                    isOn: $settings.experimentalFeaturesEnabled
                )
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 18)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle(LocalizedStringKey("ADVANCED_SETTINGS_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AdvancedPublicMeshCard: View {
    @Binding var publicMeshEnabled: Bool
    @Binding var notificationsEnabled: Bool
    let available: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 12) {
                AppIconBadge(
                    systemName: "point.3.connected.trianglepath.dotted",
                    tint: .appDanger,
                    size: 46
                )

                VStack(alignment: .leading, spacing: 6) {
                    Text(LocalizedStringKey("ADVANCED_SETTING_PUBLIC_MESH_TITLE"))
                        .font(.headline)
                        .foregroundStyle(.primary)

                    Text(LocalizedStringKey("ADVANCED_SETTING_PUBLIC_MESH_DESCRIPTION"))
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }

                Spacer(minLength: 0)
            }

            if !available {
                Text(LocalizedStringKey("ADVANCED_SETTING_UNAVAILABLE_NOTE"))
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Color.appWarning)
            }

            AdvancedToggleRow(
                titleKey: "ADVANCED_SETTING_PUBLIC_MESH_TITLE",
                descriptionKey: "ADVANCED_SETTING_PUBLIC_MESH_DESCRIPTION",
                isOn: $publicMeshEnabled,
                enabled: available
            )

            if available && publicMeshEnabled {
                Divider()

                AdvancedToggleRow(
                    titleKey: "ADVANCED_SETTING_GATT_MESH_NOTIFICATIONS_TITLE",
                    descriptionKey: "ADVANCED_SETTING_GATT_MESH_NOTIFICATIONS_DESCRIPTION",
                    isOn: $notificationsEnabled,
                    enabled: true
                )
            }
        }
        .appSurface(style: .regular, padding: 18)
    }
}

private struct AdvancedToggleCard: View {
    let titleKey: String
    let descriptionKey: String
    let iconName: String
    let tint: Color
    @Binding var isOn: Bool
    var enabled: Bool = true
    var supportingNoteKey: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                AppIconBadge(systemName: iconName, tint: tint, size: 44)

                VStack(alignment: .leading, spacing: 4) {
                    Text(LocalizedStringKey(titleKey))
                        .font(.headline)
                        .foregroundStyle(.primary)

                    Text(LocalizedStringKey(descriptionKey))
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }

                Spacer(minLength: 0)

                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .disabled(!enabled)
            }

            if let supportingNoteKey {
                Text(LocalizedStringKey(supportingNoteKey))
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Color.appWarning)
            }
        }
        .opacity(enabled ? 1 : 0.74)
        .appSurface(style: .regular, padding: 18)
    }
}

private struct AdvancedToggleRow: View {
    let titleKey: String
    let descriptionKey: String
    @Binding var isOn: Bool
    var enabled: Bool = true

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringKey(titleKey))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(LocalizedStringKey(descriptionKey))
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }

            Spacer(minLength: 0)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .disabled(!enabled)
        }
        .opacity(enabled ? 1 : 0.74)
    }
}

#Preview {
    NavigationStack {
        AdvancedSettingsView()
    }
}
