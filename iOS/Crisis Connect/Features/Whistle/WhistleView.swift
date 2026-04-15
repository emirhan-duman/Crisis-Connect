//
//  WhistleView.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import SwiftUI

struct WhistleView: View {
    @StateObject private var viewModel = WhistleViewModel()

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                WhistleStatusCard(
                    isActive: viewModel.isPlaying,
                    titleKey: viewModel.statusTitleKey,
                    systemImage: viewModel.statusSystemImage
                )

                GroupBox {
                    VStack(spacing: 12) {
                        HStack {
                            Text("\(Int(viewModel.frequency)) Hz")
                                .font(.title3.weight(.semibold))
                                .monospacedDigit()
                            Spacer()
                        }

                        Slider(
                            value: $viewModel.frequency,
                            in: viewModel.frequencyRange,
                            step: viewModel.frequencyStep,
                            onEditingChanged: viewModel.frequencyEditingChanged
                        )

                        HStack {
                            Text("\(Int(viewModel.frequencyRange.lowerBound)) Hz")
                            Spacer()
                            Text("\(Int(viewModel.frequencyRange.upperBound)) Hz")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                } label: {
                    Label("WHISTLE_FREQUENCY_LABEL", systemImage: "waveform")
                }

                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        Picker("WHISTLE_MODE_LABEL", selection: $viewModel.mode) {
                            ForEach(WhistleViewModel.Mode.allCases) { mode in
                                Text(mode.titleKey).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        Text(viewModel.mode.descriptionKey)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } label: {
                    Label("WHISTLE_MODE_LABEL", systemImage: "dot.radiowaves.left.and.right")
                }

                Button(action: viewModel.togglePlayback) {
                    Label(viewModel.actionTitleKey, systemImage: viewModel.actionSystemImage)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(viewModel.isPlaying ? .red : .blue)

                Text(LocalizedStringKey("WHISTLE_HINT"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
            }
            .padding()
        }
        .background(Color.appBackground)
        .navigationTitle("Whistle")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { viewModel.stop() }
        .alert(LocalizedStringKey("WHISTLE_WARNING_TITLE"), isPresented: $viewModel.showLoudnessWarning) {
            Button("WHISTLE_WARNING_CANCEL", role: .cancel) { }
            Button("WHISTLE_WARNING_CONFIRM") {
                viewModel.confirmWarningAndStart()
            }
        } message: {
            Text(LocalizedStringKey("WHISTLE_WARNING_MESSAGE"))
        }
    }
}

private struct WhistleStatusCard: View {
    var isActive: Bool
    var titleKey: LocalizedStringKey
    var systemImage: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(isActive ? .red : .secondary)
                .frame(width: 48, height: 48)
                .background(Circle().fill(Color.appRowBackground))

            Text(titleKey)
                .font(.headline)
                .foregroundStyle(.primary)

            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.appRowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
    }
}

#Preview {
    NavigationStack { WhistleView() }
}
