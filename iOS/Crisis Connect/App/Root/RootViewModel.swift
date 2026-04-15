//
//  RootViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine

final class RootViewModel: ObservableObject {
    @Published var showSplash: Bool = true
    private var hasStarted: Bool = false

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task {
            try? await Task.sleep(nanoseconds: 900_000_000) // 0.9s
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.28)) { self.showSplash = false }
            }
        }
    }
}
