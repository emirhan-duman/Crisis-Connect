//
//  ContentViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine

@MainActor
final class ContentViewModel: ObservableObject {
    enum Tab: Hashable {
        case messages
        case tools
        case guide
        case settings

        /// Stable screen-view route name for analytics (no user data). Mirrors Android's nav routes.
        var screenRoute: String {
            switch self {
            case .messages: return "messages"
            case .tools: return "tools"
            case .guide: return "survival_guide"
            case .settings: return "settings"
            }
        }
    }

    @Published var showingNewChat: Bool = false
    @Published var showingSOS: Bool = false
    @Published var showingRecentDisasters: Bool = false
    @Published var searchText: String = ""
    @Published var selectedTab: Tab = .messages

    func openNewChat() {
        showingNewChat = true
    }

    func openSOS() {
        showingSOS = true
    }

    func closeNewChat() {
        showingNewChat = false
    }
}
