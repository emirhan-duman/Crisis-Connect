//
//  NavigationBarStyle.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI

struct AppNavigationBarStyle: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    private var background: Color {
        .appSurfaceElevated
    }

    func body(content: Content) -> some View {
        content
            .toolbarBackground(background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(colorScheme, for: .navigationBar)
    }
}

extension View {
    func appNavigationBarStyle() -> some View {
        modifier(AppNavigationBarStyle())
    }
}
