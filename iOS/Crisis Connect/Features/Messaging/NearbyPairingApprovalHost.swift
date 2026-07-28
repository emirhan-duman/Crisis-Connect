//
//  NearbyPairingApprovalHost.swift
//  Crisis Connect
//
//  Consent surface for the nearby SPAKE2 pairing responder (parity with Android's pairing
//  notification): when a nearby peer completes the handshake, the user must explicitly Accept
//  before our identity is revealed and the contact is saved. Declining reveals nothing.
//

import SwiftUI

struct NearbyPairingApprovalHost: View {
    @ObservedObject private var responder = NearbyPairingPeripheral.shared

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .alert(
                Text(String(
                    format: NSLocalizedString("NEARBY_PAIRING_REQUEST_TITLE", comment: ""),
                    responder.pendingApproval?.displayName ?? ""
                )),
                isPresented: Binding(
                    get: { responder.pendingApproval != nil },
                    // Dismissing without choosing must count as a decline: the peer is blocked
                    // mid-handshake waiting on our answer, and silence would leave them hanging.
                    set: { presented in
                        if !presented, let pending = responder.pendingApproval {
                            responder.decline(pending.id)
                        }
                    }
                )
            ) {
                Button("NEARBY_PAIRING_ACCEPT") {
                    if let pending = responder.pendingApproval {
                        responder.accept(pending.id)
                    }
                }
                Button("NEARBY_PAIRING_DECLINE", role: .cancel) {
                    if let pending = responder.pendingApproval {
                        responder.decline(pending.id)
                    }
                }
            } message: {
                Text("NEARBY_PAIRING_REQUEST_TEXT")
            }
    }
}
