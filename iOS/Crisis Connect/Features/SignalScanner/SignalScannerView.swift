//
//  SignalScannerView.swift
//  Crisis Connect
//
//  Updated by Assistant on 12.12.2025
//

import SwiftUI
import CoreBluetooth

struct SignalScannerView: View {
    @StateObject private var viewModel = SignalScannerViewModel()

    var body: some View {
        List {
            Section("Bluetooth (BLE)") {
                HStack {
                    Label("Bluetooth", systemImage: "bolt.horizontal.circle")
                    Spacer()
                    Text(viewModel.stateKey).foregroundStyle(.secondary)
                }
                HStack {
                    if viewModel.state == .poweredOn {
                        Button(viewModel.scanTitleKey) {
                            viewModel.isScanning ? viewModel.stop() : viewModel.start()
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Text("Enable Bluetooth to scan").foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if !viewModel.devices.isEmpty {
                    ForEach(viewModel.devices.sorted(by: { $0.rssi > $1.rssi })) { dev in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(dev.name).font(.body)
                                Text(dev.id.uuidString).font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(dev.rssi) dBm").monospacedDigit()
                        }
                    }
                } else {
                    Text(viewModel.scanStatusKey)
                        .foregroundStyle(.secondary)
                }
            }

            Section(footer: Text("BLE scanning requires a real device. Simulator is not supported.").font(.footnote)) {
                EmptyView()
            }
        }
        .navigationTitle("Signal Finder")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .onDisappear {
            viewModel.stop()
        }
    }
}

#Preview {
    NavigationStack { SignalScannerView() }
}
