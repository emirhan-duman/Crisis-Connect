//
//  NotificationsView.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import SwiftUI

struct NotificationsView: View {
    @StateObject private var viewModel = NotificationsSettingsViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        List {
            Section(header: Text(LocalizedStringKey("NOTIFICATIONS_PERMISSIONS_SECTION")),
                    footer: Text(LocalizedStringKey(viewModel.permissionsFooterKey))) {
                Toggle(isOn: Binding(
                    get: { viewModel.appNotificationsEnabled },
                    set: { viewModel.handleAppNotificationsToggle($0) }
                )) {
                    Text(LocalizedStringKey("NOTIFICATIONS_APP_ENABLE"))
                }
                .disabled(viewModel.authorizationStatus == .denied)
                .listRowBackground(Color.appRowBackground)

                HStack {
                    Text(LocalizedStringKey("NOTIFICATIONS_STATUS_LABEL"))
                    Spacer()
                    Text(LocalizedStringKey(viewModel.statusKey))
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(Color.appRowBackground)
            }

            Section(header: Text(LocalizedStringKey("NOTIFICATIONS_ALERTS_SECTION")),
                    footer: Text(LocalizedStringKey("NOTIFICATIONS_ALERTS_FOOTER"))) {
                Toggle(isOn: $viewModel.sosAlertsEnabled) {
                    Text(LocalizedStringKey("NOTIFICATIONS_ALERT_SOS"))
                }
                .listRowBackground(Color.appRowBackground)

                Toggle(isOn: $viewModel.contactUpdatesEnabled) {
                    Text(LocalizedStringKey("NOTIFICATIONS_ALERT_CONTACTS"))
                }
                .listRowBackground(Color.appRowBackground)
            }
            .disabled(!viewModel.canConfigureNotifications)

            Section(header: Text(LocalizedStringKey("NOTIFICATIONS_SOUND_SECTION"))) {
                Toggle(isOn: $viewModel.playSoundEnabled) {
                    Text(LocalizedStringKey("NOTIFICATIONS_SOUND"))
                }
                .listRowBackground(Color.appRowBackground)
            }
            .disabled(!viewModel.canConfigureNotifications)

            Section(footer: Text(LocalizedStringKey("NOTIFICATIONS_OPEN_SETTINGS_FOOTER"))) {
                Button {
                    viewModel.openSystemSettings()
                } label: {
                    Label(LocalizedStringKey("NOTIFICATIONS_OPEN_SETTINGS"), systemImage: "gearshape")
                }
                .listRowBackground(Color.appRowBackground)
            }
        }
        .navigationTitle(LocalizedStringKey("Notifications"))
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .onAppear { viewModel.refreshAuthorization() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                viewModel.refreshAuthorization()
            }
        }
    }
}

#Preview {
    NavigationStack { NotificationsView() }
}
