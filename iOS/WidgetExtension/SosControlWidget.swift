//
//  SosControlWidget.swift
//  CrisisConnectWidgets
//
//  iOS 18 control: puts SOS in Control Center, on the lock screen's control
//  slots, and on the Action Button. Launching goes through crisisconnect://sos
//  into the in-app countdown, so the 5-second arming guard is never skipped.
//

import AppIntents
import SwiftUI
import WidgetKit

@available(iOS 18.0, *)
struct SosControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "SosControl") {
            ControlWidgetButton(action: OpenSosIntent()) {
                // "SOS" is the universal distress signal — deliberately untranslated.
                Label("SOS", systemImage: "sos.circle")
            }
        }
        .displayName(LocalizedStringResource("WIDGET_SOS_TITLE"))
        .description(LocalizedStringResource("WIDGET_SOS_DESC"))
    }
}

@available(iOS 18.0, *)
struct OpenSosIntent: AppIntent {
    static let title: LocalizedStringResource = "WIDGET_SOS_TITLE"
    static let openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult & OpensIntent {
        .result(opensIntent: OpenURLIntent(URL(string: "crisisconnect://sos")!))
    }
}
