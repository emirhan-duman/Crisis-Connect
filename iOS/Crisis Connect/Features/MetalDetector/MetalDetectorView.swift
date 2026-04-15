//
//  MetalDetectorView.swift
//  Crisis Connect
//
//  Created by Assistant on 09.12.2025
//

import SwiftUI

struct MetalDetectorView: View {
    @StateObject private var viewModel = MetalDetectorViewModel()

    var body: some View {
        VStack(spacing: 24) {
            if !viewModel.isAvailable {
                ContentUnavailableView("Magnetometer not available on this device.", systemImage: "xmark.octagon")
                Text("Move slowly near metal objects. Readings are relative to Earth's magnetic field.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            } else {
                VStack(spacing: 16) {
                    Gauge(value: min(viewModel.magnitude, 200), in: 0...200) {
                        Text("Magnetic Field")
                    } currentValueLabel: {
                        Text("\(viewModel.magnitude, format: .number.precision(.fractionLength(0))) µT")
                            .monospacedDigit()
                    }
                    .gaugeStyle(.linearCapacity)
                    .tint(Gradient(colors: [.green, .yellow, .orange, .red]))

                    Text("\(viewModel.magnitude, format: .number.precision(.fractionLength(1))) µT")
                        .font(.system(size: 42, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .padding(.top, 8)

                    Button(viewModel.actionTitleKey) {
                        viewModel.toggle()
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            Spacer()
        }
        .padding()
        .navigationTitle("Metal Detector")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }
}

#Preview {
    NavigationStack { MetalDetectorView() }
}
