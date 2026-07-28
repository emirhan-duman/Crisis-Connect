//
//  ChildProfileView.swift
//  Crisis Connect
//
//  Settings screen for child profile mode — the iOS counterpart of Android's ChildProfileScreen.
//  Child side: turn the mode on with a parent-chosen PIN (required to turn it off or edit the
//  parent list while on), pick parents from internet-capable contacts (request → peer accepts).
//  Parent side: incoming requests wait here with accept/decline; accepted contacts are listed as
//  the children this device is a parent of.
//

import Combine
import SwiftUI

struct ChildProfileView: View {
    @ObservedObject private var manager = ChildProfileManager.shared
    @ObservedObject private var contactStore = ContactStore.shared

    private enum PinPurpose: String, Identifiable {
        case enable, disable, gate
        var id: String { rawValue }
    }

    @State private var pinPurpose: PinPurpose?
    @State private var pendingGatedAction: (() -> Void)?
    @State private var showsParentPicker = false

    var body: some View {
        List {
            Section(footer: Text("CHILD_PROFILE_DESCRIPTION")) {
                Toggle(isOn: Binding(
                    get: { manager.isEnabled },
                    set: { next in pinPurpose = next ? .enable : .disable }
                )) {
                    Label("CHILD_PROFILE_TITLE", systemImage: "figure.and.child.holdinghands")
                }
                .tint(Color.appPrimary)
            }

            parentsSection

            childrenSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .navigationTitle(LocalizedStringKey("CHILD_PROFILE_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $pinPurpose) { purpose in
            ChildPinSheet(purpose: pinSheetPurpose(for: purpose)) { success in
                if success, purpose == .gate {
                    pendingGatedAction?()
                }
                if success, purpose == .enable || purpose == .disable {
                    // Republish the identity so the directory's isChild flag tracks the mode.
                    Task { await MessagingRegistrar.ensureRegistered() }
                }
                pendingGatedAction = nil
                pinPurpose = nil
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showsParentPicker) {
            NavigationStack {
                parentPicker
            }
            .presentationDetents([.medium, .large])
        }
    }

    // MARK: - Parents (child side)

    private var parentsSection: some View {
        Section(
            header: Text("CHILD_PROFILE_PARENTS_TITLE"),
            footer: Text("CHILD_PROFILE_PARENTS_DESCRIPTION")
        ) {
            let parentRows = contacts(for: manager.parents)
            let pendingRows = contacts(for: manager.pendingParents)

            if parentRows.isEmpty && pendingRows.isEmpty {
                Text("CHILD_PROFILE_NO_PARENTS")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }

            ForEach(parentRows) { contact in
                contactRow(contact) {
                    Button(role: .destructive) {
                        gated { manager.removeParent(contact.id) }
                    } label: {
                        Image(systemName: "minus.circle")
                            .foregroundStyle(Color.appDanger)
                    }
                }
            }

            ForEach(pendingRows) { contact in
                contactRow(contact, subtitleKey: "CHILD_PROFILE_PARENT_PENDING") {
                    Button {
                        gated { manager.removePendingParent(contact.id) }
                    } label: {
                        Image(systemName: "xmark.circle")
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
            }

            Button {
                if manager.isEnabled {
                    gated { showsParentPicker = true }
                } else {
                    showsParentPicker = true
                }
            } label: {
                Label("CHILD_PROFILE_ADD_PARENT", systemImage: "plus")
            }
        }
    }

    private var parentPicker: some View {
        List {
            let candidates = contactStore.contacts
                .filter {
                    $0.supportsInternet
                        && $0.peerIsChild != true
                        && !manager.parents.contains($0.id)
                        && !manager.pendingParents.contains($0.id)
                }
                .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            if candidates.isEmpty {
                Text("CHILD_PROFILE_PICKER_EMPTY")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }
            ForEach(candidates) { contact in
                Button {
                    showsParentPicker = false
                    manager.addPendingParent(contact.id)
                    Task {
                        _ = await InternetChatTransport.shared.sendParentRequest(contact: contact)
                    }
                } label: {
                    contactRow(contact) { EmptyView() }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(LocalizedStringKey("CHILD_PROFILE_PICKER_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("COMMON_CLOSE") { showsParentPicker = false }
            }
        }
    }

    // MARK: - Children + incoming requests (parent side)

    private var childrenSection: some View {
        Section(
            header: Text("CHILD_PROFILE_CHILDREN_TITLE"),
            footer: Text("CHILD_PROFILE_CHILDREN_DESCRIPTION")
        ) {
            let requests = contacts(for: manager.incomingRequests)
            let childRows = contacts(for: manager.children)

            if requests.isEmpty && childRows.isEmpty {
                Text("CHILD_PROFILE_NO_CHILDREN")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }

            ForEach(requests) { contact in
                VStack(alignment: .leading, spacing: 8) {
                    contactRow(contact, subtitleKey: "CHILD_PROFILE_REQUEST_SUBTITLE") { EmptyView() }
                    HStack(spacing: 12) {
                        Button {
                            answerRequest(contact, accepted: true)
                        } label: {
                            Text("CHILD_PROFILE_ACCEPT")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.appPrimary)

                        Button {
                            answerRequest(contact, accepted: false)
                        } label: {
                            Text("CHILD_PROFILE_DECLINE")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.vertical, 2)
            }

            ForEach(childRows) { contact in
                contactRow(contact) {
                    Button(role: .destructive) {
                        manager.removeChild(contact.id)
                    } label: {
                        Image(systemName: "minus.circle")
                            .foregroundStyle(Color.appDanger)
                    }
                }
            }
        }
    }

    private func answerRequest(_ contact: ContactRecord, accepted: Bool) {
        manager.resolveIncomingRequest(contact.id, accepted: accepted)
        Task {
            _ = await InternetChatTransport.shared.sendParentResponse(contact: contact, accepted: accepted)
        }
    }

    // MARK: - Shared bits

    private func contacts(for ids: Set<UUID>) -> [ContactRecord] {
        contactStore.contacts
            .filter { ids.contains($0.id) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private func contactRow(
        _ contact: ContactRecord,
        subtitleKey: String? = nil,
        @ViewBuilder trailing: () -> some View
    ) -> some View {
        HStack(spacing: 12) {
            ChatAvatarCircleView(
                avatarImageRelativePath: nil,
                initials: AvatarGenerator.initials(from: contact.name),
                avatarHue: AvatarGenerator.hue(for: contact.id),
                size: 38
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if let subtitleKey {
                    Text(LocalizedStringKey(subtitleKey))
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            Spacer()
            trailing()
        }
    }

    /// PIN-gates an edit while child mode is on; runs it directly otherwise.
    private func gated(_ action: @escaping () -> Void) {
        if manager.isEnabled {
            pendingGatedAction = action
            pinPurpose = .gate
        } else {
            action()
        }
    }

    private func pinSheetPurpose(for purpose: PinPurpose) -> ChildPinSheet.Purpose {
        switch purpose {
        case .enable: return .enable
        case .disable: return .disable
        case .gate: return .verify
        }
    }
}

// MARK: - PIN sheet

private struct ChildPinSheet: View {
    enum Purpose {
        case enable
        case disable
        case verify
    }

    let purpose: Purpose
    let completion: (Bool) -> Void

    @State private var pin = ""
    @State private var confirmPin = ""
    @State private var errorText: String?
    @FocusState private var pinFocused: Bool

    var body: some View {
        VStack(spacing: 18) {
            Text(titleKey)
                .font(.headline)
                .padding(.top, 22)

            Text(messageKey)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            SecureField(LocalizedStringKey("CHILD_PROFILE_PIN_PLACEHOLDER"), text: $pin)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)
                .focused($pinFocused)
                .padding(.horizontal, 24)

            if purpose == .enable {
                SecureField(LocalizedStringKey("CHILD_PROFILE_PIN_CONFIRM_PLACEHOLDER"), text: $confirmPin)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal, 24)
            }

            if let errorText {
                Text(errorText)
                    .font(.footnote)
                    .foregroundStyle(Color.appDanger)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }

            Button {
                submit()
            } label: {
                Text("CHILD_PROFILE_PIN_SUBMIT")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.appPrimary)
            .padding(.horizontal, 24)

            Button("COMMON_CLOSE") { completion(false) }
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)

            Spacer()
        }
        .onAppear { pinFocused = true }
    }

    private var titleKey: LocalizedStringKey {
        switch purpose {
        case .enable: return "CHILD_PROFILE_SET_PIN_TITLE"
        case .disable, .verify: return "CHILD_PROFILE_ENTER_PIN_TITLE"
        }
    }

    private var messageKey: LocalizedStringKey {
        switch purpose {
        case .enable: return "CHILD_PROFILE_SET_PIN_MESSAGE"
        case .disable, .verify: return "CHILD_PROFILE_ENTER_PIN_MESSAGE"
        }
    }

    private func submit() {
        switch purpose {
        case .enable:
            guard ChildProfileManager.isValidPin(pin) else {
                errorText = NSLocalizedString("CHILD_PROFILE_PIN_INVALID", comment: "")
                return
            }
            guard pin == confirmPin else {
                errorText = NSLocalizedString("CHILD_PROFILE_PIN_MISMATCH", comment: "")
                return
            }
            completion(ChildProfileManager.shared.enable(pin: pin))
        case .disable:
            handle(ChildProfileManager.shared.disable(pin: pin))
        case .verify:
            handle(ChildProfileManager.shared.verifyPin(pin))
        }
    }

    private func handle(_ result: ChildPinResult) {
        switch result {
        case .success:
            completion(true)
        case .wrongPin(let attemptsLeft):
            errorText = String(
                format: NSLocalizedString("CHILD_PROFILE_WRONG_PIN", comment: ""),
                attemptsLeft
            )
            pin = ""
        case .lockedOut(let seconds):
            errorText = String(
                format: NSLocalizedString("CHILD_PROFILE_LOCKED_OUT", comment: ""),
                seconds
            )
            pin = ""
        }
    }
}
