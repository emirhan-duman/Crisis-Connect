//
//  SplashViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine

final class SplashViewModel: ObservableObject {
    @Published var detailsOpacity: Double = 0.0
    @Published var detailsOffset: CGFloat = 10
    private var hasAnimated: Bool = false

    func animate() {
        guard !hasAnimated else { return }
        hasAnimated = true
        withAnimation(.easeOut(duration: 0.28).delay(0.12)) {
            detailsOpacity = 1.0
            detailsOffset = 0
        }
    }
}
