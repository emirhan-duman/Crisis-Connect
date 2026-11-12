import SwiftUI
import FirebaseAuth
import CryptoKit
import WatchConnectivity

struct IndependentMessagingSettingsView: View {
    @State private var inbox: IndependentPhoneInbox?
    @State private var notice: String?
    @State private var isErrorNotice = false
    @State private var busy = false
    @State private var selectedContact: ContactRecord?
    @State private var devices: [[String: Any]] = []
    @State private var isLoadingDevices = false
    @State private var approval: [String: Any]?
    @State private var showApproval = false
    @State private var showUnpairWatch = false
    @State private var showQRScanner = false
    @State private var showAddReplyAlert = false
    @State private var newReplyText = ""
    @ObservedObject private var contacts = ContactStore.shared
    @ObservedObject private var batteryStore = WatchBatteryStore.shared
    @ObservedObject private var quickRepliesStore = WatchQuickRepliesStore.shared

    private var isPhoneReceiverEnabled: Bool {
        inbox?.isEnabled == true
    }

    private var pairedWatchID: String? {
        inbox?.pairedWatchID
    }

    private var isWatchPaired: Bool {
        pairedWatchID != nil
    }

    private var lastSyncString: String {
        if let date = inbox?.lastSyncDate {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm"
            return String(format: NSLocalizedString("WATCH_SETTINGS_LAST_SYNC", comment: ""), formatter.string(from: date))
        }
        return NSLocalizedString("WATCH_SETTINGS_NEVER_SYNCED", comment: "")
    }

    private var isWatchHardwareConnected: Bool {
        guard WCSession.isSupported() else { return false }
        return WCSession.default.isPaired && WCSession.default.isReachable
    }

    private var eligibleContacts: [ContactRecord] {
        contacts.contacts
            .filter(WatchContactMessaging.eligible)
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        List {
            // Header Info Banner
            Section {
                HStack(alignment: .top, spacing: 14) {
                    AppIconBadge(
                        systemName: "applewatch",
                        tint: .appPrimary,
                        size: 42
                    )

                    VStack(alignment: .leading, spacing: 4) {
                        Text(LocalizedStringKey("WATCH_SETTINGS_TITLE"))
                            .font(.headline)
                            .foregroundStyle(.primary)

                        Text(LocalizedStringKey("WATCH_SETTINGS_NOTICE"))
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
                .padding(.vertical, 4)
            }
            .listRowBackground(Color.appRowBackground)

            // Dynamic Notice / Feedback Banner
            if let notice {
                Section {
                    HStack(spacing: 10) {
                        Image(systemName: isErrorNotice ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                            .foregroundStyle(isErrorNotice ? Color.appDanger : Color.appSuccess)
                        Text(notice)
                            .font(.footnote)
                            .foregroundStyle(isErrorNotice ? Color.appDanger : .primary)
                    }
                }
                .listRowBackground(Color.appRowBackground)
            }

            if let inbox {
                // Section: Status & Controls
                Section(header: Text(LocalizedStringKey("WATCH_SETTINGS_SECTION_STATUS"))) {
                    HStack {
                        Label {
                            Text(LocalizedStringKey("WATCH_SETTINGS_STATUS_RECEIVER"))
                        } icon: {
                            Image(systemName: "iphone")
                                .foregroundStyle(Color.appPrimary)
                        }
                        Spacer()
                        Text(LocalizedStringKey(isPhoneReceiverEnabled ? "WATCH_SETTINGS_ACTIVE" : "WATCH_SETTINGS_INACTIVE"))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(isPhoneReceiverEnabled ? Color.appSuccess : Color.appTextSecondary)
                    }

                    HStack {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(LocalizedStringKey("WATCH_SETTINGS_STATUS_WATCH"))
                                if let pairedWatchID {
                                    Text("ID: \(pairedWatchID.prefix(8)) · \(lastSyncString)")
                                        .font(.caption2)
                                        .foregroundStyle(Color.appTextSecondary)
                                }
                            }
                        } icon: {
                            Image(systemName: "applewatch")
                                .foregroundStyle(Color.appPrimary)
                        }
                        Spacer()
                        Text(LocalizedStringKey(isWatchPaired ? "WATCH_SETTINGS_PAIRED" : "WATCH_SETTINGS_NOT_PAIRED"))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(isWatchPaired ? Color.appSuccess : Color.appTextSecondary)
                    }

                    HStack {
                        Label {
                            Text(LocalizedStringKey("WATCH_SETTINGS_HW_TITLE"))
                        } icon: {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .foregroundStyle(Color.appWarning)
                        }
                        Spacer()
                        Text(LocalizedStringKey(isWatchHardwareConnected ? "WATCH_SETTINGS_HW_CONNECTED" : "WATCH_SETTINGS_HW_DISCONNECTED"))
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(isWatchHardwareConnected ? Color.appSuccess : Color.appTextSecondary)
                    }

                    batteryRow
                }
                .listRowBackground(Color.appRowBackground)

                // Section: Actions
                Section(header: Text(LocalizedStringKey("WATCH_SETTINGS_SECTION_ACTIONS"))) {
                    if !isPhoneReceiverEnabled {
                        Button {
                            perform {
                                try await inbox.enable()
                                notice = NSLocalizedString("WATCH_SETTINGS_READY_NOTICE", comment: "")
                                isErrorNotice = false
                            }
                        } label: {
                            Label(
                                NSLocalizedString("WATCH_SETTINGS_ENABLE_PHONE", comment: ""),
                                systemImage: "antenna.radiowaves.left.and.right"
                            )
                        }
                    } else {
                        Button {
                            perform {
                                try await inbox.pairWatch()
                                notice = NSLocalizedString("WATCH_SETTINGS_PAIRED_NOTICE", comment: "")
                                isErrorNotice = false
                            }
                        } label: {
                            Label(
                                NSLocalizedString("WATCH_SETTINGS_PAIR_WATCH", comment: ""),
                                systemImage: "applewatch.side.right"
                            )
                        }

                        Button {
                            showQRScanner = true
                        } label: {
                            Label(
                                NSLocalizedString("WATCH_SETTINGS_SCAN_QR_TITLE", comment: ""),
                                systemImage: "qrcode.viewfinder"
                            )
                        }

                        Button {
                            perform {
                                try await inbox.refresh()
                                notice = NSLocalizedString("WATCH_SETTINGS_REFRESHED_NOTICE", comment: "")
                                isErrorNotice = false
                            }
                        } label: {
                            Label(
                                "\(NSLocalizedString("WATCH_SETTINGS_REFRESH", comment: "")) (\(lastSyncString))",
                                systemImage: "arrow.clockwise"
                            )
                        }

                        if isWatchPaired {
                            Button(role: .destructive) {
                                showUnpairWatch = true
                            } label: {
                                Label(
                                    NSLocalizedString("WATCH_SETTINGS_UNPAIR_WATCH", comment: ""),
                                    systemImage: "link.badge.plus"
                                )
                                .foregroundStyle(Color.appWarning)
                            }
                        }

                        Button(role: .destructive) {
                            perform {
                                try await inbox.disable()
                                notice = NSLocalizedString("WATCH_SETTINGS_REVOKED_NOTICE", comment: "")
                                isErrorNotice = false
                            }
                        } label: {
                            Label(
                                NSLocalizedString("WATCH_SETTINGS_DISABLE_PHONE", comment: ""),
                                systemImage: "trash"
                            )
                        }
                    }
                }
                .listRowBackground(Color.appRowBackground)

                // Section: Eligible Contacts
                Section(
                    header: Text(LocalizedStringKey("WATCH_SETTINGS_SECTION_CONTACTS")),
                    footer: Text(LocalizedStringKey("WATCH_SETTINGS_CONTACTS_FOOTER"))
                ) {
                    if eligibleContacts.isEmpty {
                        Text(LocalizedStringKey("WATCH_SETTINGS_NO_CONTACTS"))
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                    } else {
                        ForEach(eligibleContacts) { contact in
                            Button {
                                perform {
                                    guard inbox.isEnabled else {
                                        notice = NSLocalizedString("WATCH_SETTINGS_PHONE_NOT_ENABLED", comment: "")
                                        isErrorNotice = true
                                        return
                                    }
                                    selectedContact = contact
                                    isLoadingDevices = true
                                    defer { isLoadingDevices = false }
                                    devices = try await inbox.devices(contact: contact)
                                    if devices.isEmpty {
                                        notice = NSLocalizedString("WATCH_SETTINGS_NO_DEVICES", comment: "")
                                        isErrorNotice = false
                                    }
                                }
                            } label: {
                                HStack {
                                    Text(contact.name)
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    if selectedContact?.id == contact.id && isLoadingDevices {
                                        ProgressView()
                                            .controlSize(.small)
                                    } else {
                                        Image(systemName: selectedContact?.id == contact.id ? "chevron.down" : "chevron.forward")
                                            .font(.footnote)
                                            .foregroundStyle(Color.appTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
                .listRowBackground(Color.appRowBackground)

                // Section: Devices for Selected Contact
                if let selectedContact {
                    Section(header: Text(selectedContact.name)) {
                        if devices.isEmpty && !isLoadingDevices {
                            Text(LocalizedStringKey("WATCH_SETTINGS_NO_DEVICES"))
                                .font(.subheadline)
                                .foregroundStyle(Color.appTextSecondary)
                        } else {
                            ForEach(devices.indices, id: \.self) { index in
                                let device = devices[index]
                                let kind = device["kind"] as? String == "phoneInbox" ? NSLocalizedString("WATCH_SETTINGS_DEVICE_PHONE", comment: "") : NSLocalizedString("WATCH_SETTINGS_DEVICE_WATCH", comment: "")
                                let rawDeviceID = device["deviceID"] as? String ?? ""
                                let isApproved = inbox.isDeviceApproved(rawDeviceID)
                                let deviceId = rawDeviceID.prefix(8)

                                Button {
                                    approval = device
                                    showApproval = true
                                } label: {
                                    HStack {
                                        Label {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text("\(kind) · \(deviceId)")
                                                    .foregroundStyle(.primary)
                                                Text(LocalizedStringKey(isApproved ? "WATCH_SETTINGS_ACTIVE" : "WATCH_SETTINGS_DEVICE_PENDING"))
                                                    .font(.caption2)
                                                    .foregroundStyle(isApproved ? Color.appSuccess : Color.appPrimary)
                                            }
                                        } icon: {
                                            Image(systemName: device["kind"] as? String == "phoneInbox" ? "iphone" : "applewatch")
                                                .foregroundStyle(isApproved ? Color.appSuccess : Color.appPrimary)
                                        }
                                        Spacer()
                                        Image(systemName: isApproved ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                                            .foregroundStyle(isApproved ? Color.appSuccess : Color.appPrimary)
                                    }
                                }
                            }
                        }
                    }
                    .listRowBackground(Color.appRowBackground)
                }

                // Section: Watch Companion & SOS Features
                Section(header: Text(LocalizedStringKey("WATCH_SETTINGS_COMPANION_TITLE"))) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 10) {
                            Image(systemName: "sos.circle.fill")
                                .font(.title3)
                                .foregroundStyle(Color.appDanger)
                            Text(LocalizedStringKey("WATCH_SETTINGS_COMPANION_TITLE"))
                                .font(.subheadline.weight(.semibold))
                        }

                        Text(LocalizedStringKey("WATCH_SETTINGS_COMPANION_DESC"))
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)

                        Divider().padding(.vertical, 2)

                        Text(LocalizedStringKey("WATCH_SETTINGS_QUICK_REPLIES_TITLE"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.appPrimary)

                        Text(LocalizedStringKey("WATCH_SETTINGS_QUICK_REPLIES_DESC"))
                            .font(.caption)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                    .padding(.vertical, 4)
                }
                .listRowBackground(Color.appRowBackground)

                // Section: Custom Quick Replies Management
                Section(
                    header: Text(LocalizedStringKey("WATCH_SETTINGS_QUICK_REPLIES_MANAGE")),
                    footer: Text(LocalizedStringKey("WATCH_SETTINGS_QUICK_REPLIES_MANAGE_DESC"))
                ) {
                    ForEach(quickRepliesStore.allReplies) { item in
                        QuickReplyItemRow(item: item) { text in
                            quickRepliesStore.removeReply(text)
                        }
                    }

                    Button {
                        newReplyText = ""
                        showAddReplyAlert = true
                    } label: {
                        Label(
                            NSLocalizedString("WATCH_SETTINGS_QUICK_REPLY_ADD", comment: ""),
                            systemImage: "plus.circle"
                        )
                    }

                    if !quickRepliesStore.customReplies.isEmpty {
                        Button(role: .destructive) {
                            quickRepliesStore.resetToDefaults()
                        } label: {
                            Label(
                                NSLocalizedString("WATCH_SETTINGS_QUICK_REPLY_RESET", comment: ""),
                                systemImage: "arrow.counterclockwise"
                            )
                            .foregroundStyle(Color.appWarning)
                        }
                    }
                }
                .listRowBackground(Color.appRowBackground)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .disabled(busy)
        .navigationTitle(LocalizedStringKey("WATCH_SETTINGS_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let user = Auth.auth().currentUser, !user.isAnonymous else {
                notice = NSLocalizedString("WATCH_SETTINGS_SIGN_IN_REQUIRED", comment: "")
                isErrorNotice = true
                return
            }
            do {
                inbox = try IndependentPhoneInbox(ownerUid: user.uid)
            } catch {
                notice = error.localizedDescription
                isErrorNotice = true
            }
        }
        .alert(
            isDeviceAlreadyApproved ? NSLocalizedString("WATCH_SETTINGS_DEVICE_ALREADY_APPROVED", comment: "") : NSLocalizedString("WATCH_SETTINGS_APPROVE_TITLE", comment: ""),
            isPresented: $showApproval
        ) {
            Button(NSLocalizedString("WATCH_SETTINGS_BTN_CANCEL", comment: ""), role: .cancel) {}
            if isDeviceAlreadyApproved {
                Button(NSLocalizedString("WATCH_SETTINGS_BTN_REVOKE", comment: ""), role: .destructive) {
                    if let inbox, let approval {
                        perform {
                            try await inbox.revokeContact(approval)
                            notice = NSLocalizedString("WATCH_SETTINGS_CONTACT_REVOKED_NOTICE", comment: "")
                            isErrorNotice = false
                        }
                    }
                }
            } else {
                Button(NSLocalizedString("WATCH_SETTINGS_BTN_APPROVE", comment: "")) {
                    if let inbox, let selectedContact, let approval {
                        perform {
                            try await inbox.approve(contact: selectedContact, descriptor: approval)
                            notice = NSLocalizedString("WATCH_SETTINGS_CONTACT_APPROVED_NOTICE", comment: "")
                            isErrorNotice = false
                        }
                    }
                }
            }
        } message: {
            Text(String(
                format: isDeviceAlreadyApproved ? NSLocalizedString("WATCH_SETTINGS_DEVICE_ALREADY_APPROVED_MSG", comment: "") : NSLocalizedString("WATCH_SETTINGS_APPROVE_MSG", comment: ""),
                fingerprint
            ))
        }
        .alert(
            NSLocalizedString("WATCH_SETTINGS_UNPAIR_CONFIRM_TITLE", comment: ""),
            isPresented: $showUnpairWatch
        ) {
            Button(NSLocalizedString("WATCH_SETTINGS_BTN_CANCEL", comment: ""), role: .cancel) {}
            Button(NSLocalizedString("WATCH_SETTINGS_UNPAIR_WATCH", comment: ""), role: .destructive) {
                if let inbox {
                    perform {
                        try await inbox.unpairWatch()
                        notice = NSLocalizedString("WATCH_SETTINGS_UNPAIRED_NOTICE", comment: "")
                        isErrorNotice = false
                    }
                }
            }
        } message: {
            Text(NSLocalizedString("WATCH_SETTINGS_UNPAIR_CONFIRM_MSG", comment: ""))
        }
        .sheet(isPresented: $showQRScanner) {
            ContactQRScannerScreen { scannedValue in
                showQRScanner = false
                notice = NSLocalizedString("WATCH_SETTINGS_QR_SCANNED_SUCCESS", comment: "")
                isErrorNotice = false
            } onError: { err in
                showQRScanner = false
                notice = err
                isErrorNotice = true
            }
        }
        .alert(NSLocalizedString("WATCH_SETTINGS_QUICK_REPLY_ADD", comment: ""), isPresented: $showAddReplyAlert) {
            TextField(NSLocalizedString("WATCH_SETTINGS_QUICK_REPLY_ADD_PLACEHOLDER", comment: ""), text: $newReplyText)
            Button(NSLocalizedString("WATCH_SETTINGS_BTN_CANCEL", comment: ""), role: .cancel) {}
            Button(NSLocalizedString("WATCH_SETTINGS_QUICK_REPLY_ADD", comment: "")) {
                let trimmed = newReplyText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    if !quickRepliesStore.addReply(trimmed) {
                        notice = NSLocalizedString("WATCH_SETTINGS_QUICK_REPLY_LIMIT_NOTICE", comment: "")
                        isErrorNotice = true
                    }
                }
            }
        }
    }

    private var isDeviceAlreadyApproved: Bool {
        guard let inbox, let deviceID = approval?["deviceID"] as? String else { return false }
        return inbox.isDeviceApproved(deviceID)
    }

    private var fingerprint: String {
        guard let bundle = approval?["bundle"] as? [String: Any],
              let key = bundle["identityKey"] as? String,
              let bytes = Data(base64Encoded: key) else { return "" }
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    private func perform(_ work: @escaping @MainActor () async throws -> Void) {
        busy = true
        Task { @MainActor in
            defer { busy = false }
            do {
                try await work()
            } catch {
                notice = error.localizedDescription
                isErrorNotice = true
            }
        }
    }

    private var batteryRow: some View {
        HStack {
            Label {
                Text(LocalizedStringKey("WATCH_SETTINGS_BATTERY_TITLE"))
            } icon: {
                batteryIcon
            }
            Spacer()
            batteryStatusText
        }
    }

    private var batteryIcon: some View {
        let pct = batteryStore.percent ?? 100
        let iconName: String
        if batteryStore.percent != nil && batteryStore.isCharging {
            iconName = "battery.100.bolt"
        } else if pct <= 20 {
            iconName = "battery.25"
        } else {
            iconName = "battery.100"
        }
        let color: Color = pct <= 20 ? .appDanger : pct <= 50 ? .appWarning : .appSuccess
        return Image(systemName: iconName).foregroundStyle(color)
    }

    @ViewBuilder
    private var batteryStatusText: some View {
        if let percent = batteryStore.percent {
            let statusDesc = batteryStore.isCharging ? NSLocalizedString("WATCH_SETTINGS_BATTERY_CHARGING", comment: "") : percent <= 20 ? NSLocalizedString("WATCH_SETTINGS_BATTERY_LOW", comment: "") : NSLocalizedString("WATCH_SETTINGS_BATTERY_NORMAL", comment: "")
            Text(String(format: NSLocalizedString("WATCH_SETTINGS_BATTERY_LEVEL", comment: ""), percent, statusDesc))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(percent <= 20 ? Color.appDanger : Color.appSuccess)
        } else {
            Text(LocalizedStringKey("WATCH_SETTINGS_BATTERY_UNKNOWN"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
        }
    }
}

private struct QuickReplyItemRow: View {
    let item: QuickReplyItem
    let onRemove: (String) -> Void

    var body: some View {
        HStack {
            Text("“\(item.text)”")
                .foregroundStyle(.primary)
            Spacer()
            let badgeColor = item.isDefault ? Color.appTextSecondary : Color.appPrimary
            Text(LocalizedStringKey(item.isDefault ? "WATCH_SETTINGS_QUICK_REPLY_DEFAULT_TAG" : "WATCH_SETTINGS_QUICK_REPLY_CUSTOM_TAG"))
                .font(.caption2.weight(.medium))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(badgeColor.opacity(0.15))
                .foregroundStyle(badgeColor)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            if !item.isDefault {
                Button(role: .destructive) {
                    onRemove(item.text)
                } label: {
                    Image(systemName: "trash")
                        .font(.footnote)
                        .foregroundStyle(Color.appDanger)
                }
                .buttonStyle(.borderless)
            }
        }
    }
}
