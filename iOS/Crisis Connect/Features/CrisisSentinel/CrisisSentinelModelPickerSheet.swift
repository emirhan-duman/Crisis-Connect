//
//  CrisisSentinelModelPickerSheet.swift
//  Crisis Connect
//
//  Provider/model picker for the online (cloud) engine — S2, iOS parity with Android's model
//  sheet: the panel's configured providers grouped with their models, plus a "Server default"
//  option (nil choice). The pick is sent as providerId+model on every stream and persisted.
//

import SwiftUI

struct CrisisSentinelModelPickerSheet: View {
    @ObservedObject var viewModel: CrisisSentinelViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    modelRow(
                        title: NSLocalizedString("CRISIS_SENTINEL_MODEL_DEFAULT", comment: ""),
                        subtitle: NSLocalizedString("CRISIS_SENTINEL_MODEL_DEFAULT_SUBTITLE", comment: ""),
                        isSelected: viewModel.selectedOnlineModel == nil
                    ) {
                        viewModel.selectedOnlineModel = nil
                        dismiss()
                    }
                }

                if viewModel.isLoadingProviders && viewModel.onlineProviders.isEmpty {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("CRISIS_SENTINEL_MODEL_LOADING")
                                .foregroundStyle(Color.appTextSecondary)
                        }
                    }
                } else if viewModel.providersLoadFailed && viewModel.onlineProviders.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("CRISIS_SENTINEL_MODEL_LOAD_FAILED")
                                .foregroundStyle(Color.appTextSecondary)
                            Button("CRISIS_SENTINEL_MODEL_RETRY") {
                                viewModel.refreshOnlineProviders(force: true)
                            }
                        }
                    }
                }

                ForEach(viewModel.onlineProviders) { provider in
                    Section(provider.label) {
                        ForEach(provider.models) { model in
                            let isSelected = viewModel.selectedOnlineModel?.providerId == provider.id
                                && viewModel.selectedOnlineModel?.modelId == model.id
                            modelRow(
                                title: model.label,
                                subtitle: contextWindowLabel(model.contextWindow),
                                isSelected: isSelected
                            ) {
                                viewModel.selectedOnlineModel = CrisisSentinelOnlineModelChoice(
                                    providerId: provider.id,
                                    modelId: model.id
                                )
                                dismiss()
                            }
                        }
                    }
                }
            }
            .navigationTitle("CRISIS_SENTINEL_MODEL_PICKER_TITLE")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("COMMON_CLOSE") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func modelRow(
        title: String,
        subtitle: String?,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(.primary)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.appPrimary)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func contextWindowLabel(_ window: Int64?) -> String? {
        guard let window, window > 0 else { return nil }
        let thousands = window / 1000
        return String(format: NSLocalizedString("CRISIS_SENTINEL_MODEL_CONTEXT", comment: ""), thousands)
    }
}
