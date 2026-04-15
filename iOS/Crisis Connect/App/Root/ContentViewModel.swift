//
//  ContentViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine

final class ContentViewModel: ObservableObject {
    enum Tab: Hashable {
        case messages
        case tools
        case guide
        case settings
    }

    @Published var showingNewChat: Bool = false
    @Published var showingSOS: Bool = false
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
