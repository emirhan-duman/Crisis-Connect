//
//  SosEmergencyContactsView.swift
//  Crisis Connect
//
//  Hand-picked SOS emergency contacts — the iOS counterpart of Android's
//  SosEmergencyContactsScreen/Store. When set, SosContactNotifier alerts exactly these people;
//  when empty, the automatic most-messaged/longest-known blend picks recipients. Only
//  internet-capable contacts qualify (the alert rides the relay).
//

import Combine
import SwiftUI

enum SosEmergencyContactsStore {
    /// Matches Android's SosEmergencyContactsStore.MAX_CONTACTS — the two platforms feed the same
    /// notifier, so a different cap meant the same user could designate a different number of people
    /// depending on which device they set it up from.
    static let maxContacts = 5
    private static let defaultsKey = "sos.emergencyContactIds"

    static func load() -> Set<UUID> {
        let raw = UserDefaults.standard.stringArray(forKey: defaultsKey) ?? []
        return Set(raw.compactMap(UUID.init(uuidString:)))
    }

    static func save(_ ids: Set<UUID>) {
        UserDefaults.standard.set(
            ids.map(\.uuidString).sorted(),
            forKey: defaultsKey
        )
    }

    // MARK: - One-time "save as emergency contact?" prompt (parity with Android)

    private static let promptedKey = "sos.emergencyContactPromptedIds"
    /// How long after its first message a conversation still counts as freshly established.
    private static let newConversationWindow: TimeInterval = 72 * 60 * 60

    private static func prompted() -> Set<UUID> {
        Set((UserDefaults.standard.stringArray(forKey: promptedKey) ?? [])
            .compactMap(UUID.init(uuidString:)))
    }

    @MainActor
    static func markPrompted(_ id: UUID) {
        var seen = prompted()
        seen.insert(id)
        UserDefaults.standard.set(seen.map(\.uuidString).sorted(), forKey: promptedKey)
    }

    /// Adds the contact (respecting the cap) and records that we asked, so we never ask twice.
    @MainActor
    static func addEmergencyContact(_ id: UUID) {
        var chosen = load()
        if chosen.count < maxContacts { chosen.insert(id) }
        save(chosen)
        markPrompted(id)
    }

    /// Whether the chat screen should offer the one-time prompt for this conversation.
    ///
    /// Gates mirror Android's SosEmergencyContactsStore.shouldPromptFor exactly: an internet-capable
    /// contact, never asked before, not already chosen, list not full — and the conversation must be
    /// freshly established, so an old thread is never spammed retroactively. An old thread is marked
    /// as asked instead, so it is never re-evaluated.
    @MainActor
    static func shouldPrompt(for contact: ContactRecord) -> Bool {
        // On a child device the emergency contacts ARE the confirmed parents — never ask.
        if ChildProfileManager.shared.isEnabled { return false }
        guard contact.supportsInternet else { return false }
        // Never offer a child-profile peer, or a hidden bridge, as an emergency contact: this must
        // match SosContactNotifier.selectRecipients or the user picks someone who is never alerted.
        guard contact.peerIsChild != true, contact.isAuthorityBridge != true else { return false }
        if prompted().contains(contact.id) { return false }
        let chosen = load()
        if chosen.contains(contact.id) { return false }
        if chosen.count >= maxContacts { return false }
        let firstMessageAt = SOSChatStore.shared.messages(for: contact.id)
            .map(\.timestamp)
            .min()
        guard let firstMessageAt else { return true }
        if Date().timeIntervalSince(firstMessageAt) > newConversationWindow {
            markPrompted(contact.id)
            return false
        }
        return true
    }
}

struct SosEmergencyContactsView: View {
    @ObservedObject private var contactStore = ContactStore.shared
    @ObservedObject private var childProfile = ChildProfileManager.shared
    @State private var selected: Set<UUID> = SosEmergencyContactsStore.load()

    // Match SosContactNotifier.selectRecipients EXACTLY: child-profile peers and hidden
    // authority-bridge contacts are silently dropped by the notifier, so they must not be
    // pickable here either (otherwise a user picks someone who never gets alerted).
    private var eligibleContacts: [ContactRecord] {
        contactStore.contacts
            .filter { $0.supportsInternet && $0.peerIsChild != true && $0.isAuthorityBridge != true }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        List {
            if childProfile.isEnabled {
                // On a child device the confirmed parents are the SOS recipients; this manual
                // picker only serves as the no-parents-yet fallback, so say so up front.
                Section {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "info.circle.fill")
                            .foregroundStyle(Color.appDanger)
                        Text("CHILD_PROFILE_SOS_PARENTS_BANNER")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
            }

            Section {
                Text("SOS_EMERGENCY_CONTACTS_EXPLAIN")
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }

            if eligibleContacts.isEmpty {
                Section {
                    Text("SOS_EMERGENCY_CONTACTS_EMPTY")
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                }
            } else {
                Section(footer: Text(String(
                    format: NSLocalizedString("SOS_EMERGENCY_CONTACTS_LIMIT", comment: ""),
                    SosEmergencyContactsStore.maxContacts
                ))) {
                    ForEach(eligibleContacts) { contact in
                        contactRow(contact)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .navigationTitle(LocalizedStringKey("SOS_EMERGENCY_CONTACTS_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: selected) { _, next in
            SosEmergencyContactsStore.save(next)
        }
    }

    private func contactRow(_ contact: ContactRecord) -> some View {
        let isSelected = selected.contains(contact.id)
        let atLimit = selected.count >= SosEmergencyContactsStore.maxContacts
        return Button {
            if isSelected {
                selected.remove(contact.id)
            } else if !atLimit {
                selected.insert(contact.id)
            }
        } label: {
            HStack(spacing: 12) {
                ChatAvatarCircleView(
                    avatarImageRelativePath: nil,
                    initials: AvatarGenerator.initials(from: contact.name),
                    avatarHue: AvatarGenerator.hue(for: contact.id),
                    size: 40
                )
                Text(contact.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Spacer()
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? Color.appDanger : Color.appTextSecondary.opacity(0.4))
            }
        }
        .disabled(!isSelected && atLimit)
        .opacity(!isSelected && atLimit ? 0.5 : 1)
    }
}
