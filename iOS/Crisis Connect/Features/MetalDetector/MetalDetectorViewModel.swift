//
//  MetalDetectorViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine
import CoreMotion

final class MetalDetectorViewModel: ObservableObject {
    private let manager = CMMotionManager()

    @Published var magnitude: Double = 0
    @Published var isActive: Bool = false

    var isAvailable: Bool { manager.isMagnetometerAvailable }
    var actionTitleKey: LocalizedStringKey { isActive ? "Pause" : "Resume" }

    func start() {
        guard manager.isMagnetometerAvailable else { return }
        if manager.isMagnetometerActive { return }
        manager.magnetometerUpdateInterval = 0.1
        isActive = true
        manager.startMagnetometerUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let field = data?.magneticField else { return }
            let mag = sqrt(field.x * field.x + field.y * field.y + field.z * field.z)
            DispatchQueue.main.async {
                self.magnitude = mag
            }
        }
    }

    func stop() {
        guard manager.isMagnetometerActive else { return }
        manager.stopMagnetometerUpdates()
        isActive = false
    }

    func toggle() {
        isActive ? stop() : start()
    }

    deinit {
        manager.stopMagnetometerUpdates()
    }
}
