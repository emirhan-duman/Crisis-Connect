//
//  ThemeSettingsView.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI

struct ThemeSettingsView: View {
    @StateObject private var viewModel: ThemeSettingsViewModel

    init(settings: AppSettingsViewModel) {
        _viewModel = StateObject(wrappedValue: ThemeSettingsViewModel(settings: settings))
    }

    var body: some View {
        List {
            Section(footer: Text("Theme changes apply instantly.")) {
                ForEach(viewModel.options) { option in
                    Button {
                        viewModel.select(option)
                    } label: {
                        HStack {
                            Text(LocalizedStringKey(option.titleKey))
                            Spacer()
                            if viewModel.selectedTheme == option.id {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                    .listRowBackground(Color.appRowBackground)
                }
            }
        }
        .id(viewModel.selectedTheme)
        .navigationTitle("Theme")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .onAppear { viewModel.syncFromSettings() }
    }
}

#Preview {
    NavigationStack { ThemeSettingsView(settings: AppSettingsViewModel()) }
}
