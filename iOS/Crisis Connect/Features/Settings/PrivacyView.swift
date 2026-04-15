//
//  PrivacyView.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import SwiftUI

struct PrivacyView: View {
    @StateObject private var viewModel = PrivacySettingsViewModel()

    var body: some View {
        List {
            Section(header: Text(LocalizedStringKey("PRIVACY_SHARING_SECTION")),
                    footer: Text(LocalizedStringKey("PRIVACY_SHARING_FOOTER"))) {
                Toggle(isOn: $viewModel.shareLocationInSOS) {
                    Text(LocalizedStringKey("PRIVACY_SHARE_LOCATION"))
                }
                .disabled(!viewModel.canShareLiveLocation)
                .listRowBackground(Color.appRowBackground)

                Toggle(isOn: $viewModel.shareProfileDetails) {
                    Text(LocalizedStringKey("PRIVACY_SHARE_PROFILE"))
                }
                .listRowBackground(Color.appRowBackground)
            }

            Section(header: Text(LocalizedStringKey("PRIVACY_ANALYTICS_SECTION")),
                    footer: Text(LocalizedStringKey("PRIVACY_ANALYTICS_FOOTER"))) {
                Toggle(isOn: $viewModel.shareAnalytics) {
                    Text(LocalizedStringKey("PRIVACY_ANALYTICS"))
                }
                .disabled(!viewModel.canShareAnalytics)
                .listRowBackground(Color.appRowBackground)
            }

            Section(footer: Text(LocalizedStringKey("PRIVACY_OPEN_SETTINGS_FOOTER"))) {
                Button {
                    viewModel.openSystemSettings()
                } label: {
                    Label(LocalizedStringKey("PRIVACY_OPEN_SETTINGS"), systemImage: "gearshape")
                }
                .listRowBackground(Color.appRowBackground)
            }
        }
        .navigationTitle("Privacy")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
    }
}

#Preview {
    NavigationStack { PrivacyView() }
}
