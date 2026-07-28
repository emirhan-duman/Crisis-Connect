//
//  CrisisConnectWidgetsBundle.swift
//  CrisisConnectWidgets
//
//  Home-screen widgets: one-tap SOS and the Recent Disasters feed.
//

import SwiftUI
import WidgetKit

@main
struct CrisisConnectWidgetsBundle: WidgetBundle {
    var body: some Widget {
        widgets()
    }

    @WidgetBundleBuilder
    private func widgets() -> some Widget {
        SosQuickWidget()
        RecentDisastersWidget()
        SosLiveActivity()
        if #available(iOS 18.0, *) {
            SosControlWidget()
        }
    }
}
