//
//  SharedCallProvider.swift
//  Crisis Connect
//
//  The app's SINGLE CXProvider. CallKit requires exactly ONE provider per app — with more than one,
//  the system delivers call actions (answer / end / mute) to an ambiguous provider, so e.g. answering
//  an incoming call never reaches the manager that reported it and the call simply never connects.
//  Previously each call manager (internet 1:1, GATT/peer, SFU authority) created its own CXProvider,
//  which is exactly that bug. They now all report through this one provider and make themselves its
//  delegate for the duration of the call they own (only one call is allowed at a time).
//

import CallKit
import UIKit

final class SharedCallProvider {
    static let shared = SharedCallProvider()
    let provider: CXProvider

    private init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        // Our calls belong in the system call history: without this there is no Recents entry, so
        // the call-back affordance has nothing to act on. The handles we report are opaque contact
        // ids (see InternetCallManager), not phone numbers, so nothing personal is written.
        config.includesCallsInRecents = true
        config.iconTemplateImageData = UIImage(systemName: "cross.circle.fill")?.pngData()
        provider = CXProvider(configuration: config)
    }

    /// Route subsequent CallKit callbacks to the manager that owns the current call. Call this right
    /// before reporting a new outgoing/incoming call to CallKit.
    func makeActive(_ delegate: CXProviderDelegate) {
        provider.setDelegate(delegate, queue: nil)
    }
}
