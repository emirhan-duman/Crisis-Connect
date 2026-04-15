//
//  SensorsDashboardView.swift
//  Crisis Connect
//
//  Created by Assistant on 12.12.2025
//

import SwiftUI

struct SensorsDashboardView: View {
    @StateObject private var viewModel = SensorsDashboardViewModel()

    var body: some View {
        List {
            Section("Accelerometer (g)") {
                if viewModel.accelAvailable {
                    axesRow(x: viewModel.accelX, y: viewModel.accelY, z: viewModel.accelZ, unit: "g")
                } else {
                    unavailableRow()
                }
            }

            Section("Gyroscope (deg/s)") {
                if viewModel.gyroAvailable {
                    // Convert rad/s to deg/s
                    axesRow(x: viewModel.gyroX * 180 / .pi, y: viewModel.gyroY * 180 / .pi, z: viewModel.gyroZ * 180 / .pi, unit: "°/s")
                } else {
                    unavailableRow()
                }
            }

            Section("Magnetometer (µT)") {
                if viewModel.magAvailable {
                    axesRow(x: viewModel.magX, y: viewModel.magY, z: viewModel.magZ, unit: "µT")
                    HStack {
                        Label("Magnitude", systemImage: "circle")
                        Spacer()
                        Text("\(viewModel.magMagnitude, specifier: "%.1f") µT").monospacedDigit()
                    }
                } else {
                    unavailableRow()
                }
            }

            Section("Device Motion") {
                if viewModel.deviceMotionAvailable {
                    HStack {
                        Label("Roll", systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        Text("\(viewModel.rollDeg, specifier: "%.1f")°").monospacedDigit()
                    }
                    HStack {
                        Label("Pitch", systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        Text("\(viewModel.pitchDeg, specifier: "%.1f")°").monospacedDigit()
                    }
                    HStack {
                        Label("Yaw", systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        Text("\(viewModel.yawDeg, specifier: "%.1f")°").monospacedDigit()
                    }
                    HStack {
                        Label("User Accel", systemImage: "bolt")
                        Spacer()
                        Text("\(viewModel.userAccMag, specifier: "%.3f") g").monospacedDigit()
                    }
                    HStack {
                        Label("Gravity", systemImage: "arrow.down.circle")
                        Spacer()
                        Text("\(viewModel.gravityMag, specifier: "%.3f") g").monospacedDigit()
                    }
                } else {
                    unavailableRow()
                }
            }

            Section("Altimeter") {
                if viewModel.altimeterAvailable {
                    HStack {
                        Label("Relative Altitude", systemImage: "mountain.2")
                        Spacer()
                        Text("\(viewModel.altitudeMeters ?? .nan, specifier: "%.2f") m").monospacedDigit()
                    }
                    HStack {
                        Label("Pressure", systemImage: "gauge")
                        Spacer()
                        Text("\(viewModel.pressureKPa ?? .nan, specifier: "%.2f") kPa").monospacedDigit()
                    }
                } else {
                    unavailableRow()
                }
            }

            Section("Proximity") {
                if viewModel.proximityAvailable {
                    HStack {
                        Label("State", systemImage: viewModel.isNear ? "hand.raised.fill" : "hand.raised")
                        Spacer()
                        Text(viewModel.isNear ? LocalizedStringKey("Near") : LocalizedStringKey("Far"))
                            .foregroundStyle(viewModel.isNear ? .red : .secondary)
                            .monospaced()
                    }
                } else {
                    unavailableRow()
                }
            }

            Section("Heading") {
                if viewModel.headingAvailable {
                    let deg = viewModel.headingDegrees ?? .nan
                    let headingDegrees = viewModel.headingDegreesText(from: deg)
                    HStack {
                        Label("Direction", systemImage: "location.north")
                        Spacer()
                        (Text(verbatim: "\(headingDegrees)° ") + Text(LocalizedStringKey(viewModel.cardinalKey(for: deg))))
                            .monospacedDigit()
                    }
                    if let acc = viewModel.headingAccuracy {
                        HStack {
                            Label("Accuracy", systemImage: "scope")
                            Spacer()
                            Text("±\(Int(acc))°").monospacedDigit()
                        }
                    }
                } else {
                    unavailableRow()
                }
            }

            Section(footer: Text("Many sensors are not available in the Simulator. Use a real device for best results.").font(.footnote)) {
                EmptyView()
            }
        }
        .navigationTitle("Sensors")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }

    private func axesRow(x: Double, y: Double, z: Double, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("X", systemImage: "xmark")
                Spacer()
                Text("\(x, specifier: "%.3f") \(unit)").monospacedDigit()
            }
            HStack {
                Label("Y", systemImage: "y.circle")
                Spacer()
                Text("\(y, specifier: "%.3f") \(unit)").monospacedDigit()
            }
            HStack {
                Label("Z", systemImage: "z.circle")
                Spacer()
                Text("\(z, specifier: "%.3f") \(unit)").monospacedDigit()
            }
        }
    }

    private func unavailableRow() -> some View {
        HStack {
            Image(systemName: "xmark.octagon")
                .foregroundStyle(.secondary)
            Text("Not available on this device")
                .foregroundStyle(.secondary)
        }
    }

}

#Preview {
    NavigationStack { SensorsDashboardView() }
}
