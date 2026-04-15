//
//  LanguageSettingsView.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI

struct LanguageSettingsView: View {
    @StateObject private var viewModel: LanguageSettingsViewModel

    init(settings: AppSettingsViewModel) {
        _viewModel = StateObject(wrappedValue: LanguageSettingsViewModel(settings: settings))
    }

    var body: some View {
        List {
            Section(footer: Text("Language changes apply instantly.")) {
                ForEach(viewModel.options) { option in
                    Button {
                        viewModel.select(option)
                    } label: {
                        HStack {
                            Text(LocalizedStringKey(option.titleKey))
                            Spacer()
                            if viewModel.selectedLanguage == option.id {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                }
            }
        }
        .id(viewModel.selectedLanguage)
        .navigationTitle("Language")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .onAppear { viewModel.syncFromSettings() }
    }
}

#Preview {
    NavigationStack { LanguageSettingsView(settings: AppSettingsViewModel()) }
}
