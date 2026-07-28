//
//  AddFromContactsView.swift
//  Crisis Connect
//
//  WhatsApp/Signal-style contact discovery — the iOS port of Android's AddFromContactsScreen +
//  DeviceContactsReader. Reads the address book, normalizes each number to E.164 and asks the
//  directory which of them opted into discovery (findContactsOnCrisisConnect). Privacy-first:
//  the server hashes the queried numbers, never stores them, and only ever returns users who
//  published a phone hash themselves.
//

import Combine
import Contacts
import FirebaseAuth
import SwiftUI

// MARK: - Address book reader

/// A phone-book entry normalized to an E.164 number, ready for directory matching.
struct DeviceContact {
    let displayName: String
    let e164: String
}

enum DeviceContactsReader {

    /// Reads the address book (call off-main; requires granted contacts access). National-format
    /// numbers are resolved against the device region's dial code; numbers that can't be made
    /// E.164-ish are skipped rather than guessed.
    static func read() -> [DeviceContact] {
        let store = CNContactStore()
        let keys: [CNKeyDescriptor] = [
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName)
        ]
        let request = CNContactFetchRequest(keysToFetch: keys)
        let dialCode = Self.deviceDialCode()

        var seen = Set<String>()
        var out: [DeviceContact] = []
        try? store.enumerateContacts(with: request) { contact, _ in
            let name = CNContactFormatter.string(from: contact, style: .fullName)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            for number in contact.phoneNumbers {
                guard let e164 = normalizeToE164(number.value.stringValue, defaultDialCode: dialCode) else {
                    continue
                }
                if seen.insert(e164).inserted {
                    out.append(DeviceContact(displayName: name.isEmpty ? e164 : name, e164: e164))
                }
            }
        }
        return out
    }

    /// Best-effort E.164 normalization without a full libphonenumber: keeps international input
    /// as-is, converts 00-prefixed international dialing, and resolves national numbers (leading
    /// 0 or bare) against `defaultDialCode`.
    static func normalizeToE164(_ raw: String, defaultDialCode: String?) -> String? {
        var cleaned = raw.filter { $0.isNumber || $0 == "+" }
        if let plus = cleaned.firstIndex(of: "+"), plus != cleaned.startIndex {
            cleaned.removeAll { $0 == "+" } // stray '+' mid-string: treat as national
        }
        if cleaned.hasPrefix("00") {
            cleaned = "+" + cleaned.dropFirst(2)
        }
        if cleaned.hasPrefix("+") {
            let digits = cleaned.dropFirst()
            guard (6...15).contains(digits.count), digits.allSatisfy(\.isNumber) else { return nil }
            return cleaned
        }
        guard let dialCode = defaultDialCode else { return nil }
        var national = Substring(cleaned)
        while national.hasPrefix("0") { national = national.dropFirst() }
        guard (6...15).contains(national.count + dialCode.count) else { return nil }
        return "+" + dialCode + national
    }

    /// The device region's international dial code (e.g. "90" for TR), from the current locale.
    static func deviceDialCode() -> String? {
        let region = Locale.current.region?.identifier.uppercased() ?? ""
        return Self.dialCodeByRegion[region]
    }

    /// ISO 3166-1 alpha-2 region → ITU international calling code (no leading +).
    private static let dialCodeByRegion: [String: String] = [
        "AF": "93", "AL": "355", "DZ": "213", "AS": "1", "AD": "376", "AO": "244", "AI": "1",
        "AG": "1", "AR": "54", "AM": "374", "AW": "297", "AU": "61", "AT": "43", "AZ": "994",
        "BS": "1", "BH": "973", "BD": "880", "BB": "1", "BY": "375", "BE": "32", "BZ": "501",
        "BJ": "229", "BM": "1", "BT": "975", "BO": "591", "BA": "387", "BW": "267", "BR": "55",
        "BN": "673", "BG": "359", "BF": "226", "BI": "257", "KH": "855", "CM": "237", "CA": "1",
        "CV": "238", "KY": "1", "CF": "236", "TD": "235", "CL": "56", "CN": "86", "CO": "57",
        "KM": "269", "CG": "242", "CD": "243", "CR": "506", "CI": "225", "HR": "385", "CU": "53",
        "CY": "357", "CZ": "420", "DK": "45", "DJ": "253", "DM": "1", "DO": "1", "EC": "593",
        "EG": "20", "SV": "503", "GQ": "240", "ER": "291", "EE": "372", "SZ": "268", "ET": "251",
        "FJ": "679", "FI": "358", "FR": "33", "GA": "241", "GM": "220", "GE": "995", "DE": "49",
        "GH": "233", "GI": "350", "GR": "30", "GL": "299", "GD": "1", "GU": "1", "GT": "502",
        "GN": "224", "GW": "245", "GY": "592", "HT": "509", "HN": "504", "HK": "852", "HU": "36",
        "IS": "354", "IN": "91", "ID": "62", "IR": "98", "IQ": "964", "IE": "353", "IL": "972",
        "IT": "39", "JM": "1", "JP": "81", "JO": "962", "KZ": "7", "KE": "254", "KI": "686",
        "KP": "850", "KR": "82", "KW": "965", "KG": "996", "LA": "856", "LV": "371", "LB": "961",
        "LS": "266", "LR": "231", "LY": "218", "LI": "423", "LT": "370", "LU": "352", "MO": "853",
        "MG": "261", "MW": "265", "MY": "60", "MV": "960", "ML": "223", "MT": "356", "MH": "692",
        "MR": "222", "MU": "230", "MX": "52", "FM": "691", "MD": "373", "MC": "377", "MN": "976",
        "ME": "382", "MA": "212", "MZ": "258", "MM": "95", "NA": "264", "NR": "674", "NP": "977",
        "NL": "31", "NZ": "64", "NI": "505", "NE": "227", "NG": "234", "MK": "389", "NO": "47",
        "OM": "968", "PK": "92", "PW": "680", "PS": "970", "PA": "507", "PG": "675", "PY": "595",
        "PE": "51", "PH": "63", "PL": "48", "PT": "351", "PR": "1", "QA": "974", "RO": "40",
        "RU": "7", "RW": "250", "KN": "1", "LC": "1", "VC": "1", "WS": "685", "SM": "378",
        "ST": "239", "SA": "966", "SN": "221", "RS": "381", "SC": "248", "SL": "232", "SG": "65",
        "SK": "421", "SI": "386", "SB": "677", "SO": "252", "ZA": "27", "SS": "211", "ES": "34",
        "LK": "94", "SD": "249", "SR": "597", "SE": "46", "CH": "41", "SY": "963", "TW": "886",
        "TJ": "992", "TZ": "255", "TH": "66", "TL": "670", "TG": "228", "TO": "676", "TT": "1",
        "TN": "216", "TR": "90", "TM": "993", "TV": "688", "UG": "256", "UA": "380", "AE": "971",
        "GB": "44", "US": "1", "UY": "598", "UZ": "998", "VU": "678", "VA": "39", "VE": "58",
        "VN": "84", "YE": "967", "ZM": "260", "ZW": "263", "XK": "383"
    ]
}

// MARK: - View model

@MainActor
final class AddFromContactsViewModel: ObservableObject {
    struct Match: Identifiable {
        var id: String { uid }
        let displayName: String
        let phone: String
        let uid: String
        let publicKey: String
        let photoUrl: String
        let isChild: Bool
    }

    enum State {
        case idle
        case loading
        case accessDenied
        case signInRequired
        case loaded([Match])
        case error
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var addedUids: Set<String> = []
    /// The normalized address book, for the Nearby tab's "who are you looking for?" picker —
    /// everyone with a usable number, not just directory matches (the person being searched for
    /// need not be discoverable online; that is the whole point of finding them over Bluetooth).
    @Published private(set) var deviceContacts: [DeviceContact] = []

    private let client = InternetMessagingClient()
    private static let lookupBatch = 1500

    func load() async {
        // The directory rejects anonymous callers (free unlimited uids would defeat the scan
        // throttle and enable enumeration), so require a real account up front.
        guard let user = Auth.auth().currentUser, !user.isAnonymous else {
            state = .signInRequired
            return
        }
        if case .loading = state { return }
        let refreshingBehindResults: Bool
        if case .loaded = state { refreshingBehindResults = true } else { refreshingBehindResults = false }
        if !refreshingBehindResults { state = .loading }

        // requestAccess resolves to false WITHOUT prompting once the user has denied permanently,
        // so re-running this can never escape .accessDenied on its own. Distinguishing the stored
        // status lets the UI offer Settings instead of a Retry that provably cannot succeed.
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .denied || status == .restricted {
            state = .accessDenied
            return
        }
        let granted = (try? await CNContactStore().requestAccess(for: .contacts)) ?? false
        guard granted else {
            state = .accessDenied
            return
        }

        do {
            let contacts = await Task.detached(priority: .userInitiated) {
                DeviceContactsReader.read()
            }.value
            deviceContacts = contacts.sorted {
                $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
            }
            let nameByPhone = Dictionary(
                contacts.map { ($0.e164, $0.displayName) },
                uniquingKeysWith: { first, _ in first }
            )
            var discovered: [DiscoveredContact] = []
            let phones = contacts.map(\.e164)
            for batchStart in stride(from: 0, to: phones.count, by: Self.lookupBatch) {
                let batch = Array(phones[batchStart..<min(batchStart + Self.lookupBatch, phones.count)])
                discovered.append(contentsOf: try await client.findContacts(phones: batch))
            }
            var seenUids = Set<String>()
            let matches = discovered
                .filter { seenUids.insert($0.uid).inserted }
                .map {
                    Match(
                        displayName: nameByPhone[$0.phone] ?? $0.phone,
                        phone: $0.phone,
                        uid: $0.uid,
                        publicKey: $0.publicKeyBase64,
                        photoUrl: $0.photoUrl,
                        isChild: $0.isChild
                    )
                }
                .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            state = .loaded(matches)
        } catch {
            NSLog("AddFromContacts: discovery failed: %@", String(describing: error))
            if !refreshingBehindResults { state = .error }
        }
    }

    /// Creates or reuses the internet contact for `match` (same create/reuse semantics as the
    /// receive path: keyed by peer uid, conversation id = symmetric pair id).
    @discardableResult
    func add(_ match: Match) -> UUID? {
        guard let myUid = client.currentUid else { return nil }
        let contact = ContactStore.shared.applyInternetIdentity(
            conversationSessionCode: InternetConversation.pairId(myUid, match.uid),
            peerUid: match.uid,
            peerPublicKey: match.publicKey,
            displayName: match.displayName,
            peerPhotoUrl: match.photoUrl,
            peerIsChild: match.isChild,
            // Keep the number: it's the SPAKE2 password NearbyAutoLink uses to bootstrap an offline
            // Bluetooth link when this internet contact is nearby (Android parity — dual transport).
            peerPhone: match.phone,
            analyticsSource: "directory"
        )
        guard let contact else { return nil }
        SOSChatStore.shared.ensureSession(id: contact.id, displayName: contact.name, role: .unknown)
        addedUids.insert(match.uid)
        return contact.id
    }
}

// MARK: - View

struct AddFromContactsView: View {
    @StateObject private var viewModel = AddFromContactsViewModel()
    @StateObject private var manualPairing = NearbyManualPairing()
    @StateObject private var deviceScanner = NearbyDeviceScanner()
    @Environment(\.scenePhase) private var scenePhase

    private enum Tab { case directory, nearby }
    /// Identifiable wrapper so navigationDestination(item:) can push the newly added chat.
    private struct ChatTarget: Identifiable, Hashable { let id: UUID }

    @State private var tab: Tab = .directory
    @State private var query = ""
    @State private var openChat: ChatTarget?
    @State private var discoveryEnabled = NearbyDiscoveryPreferences.isEnabled
    /// The address-book person the Nearby search is currently looking for.
    @State private var nearbyTarget: DeviceContact?

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                Text("CONTACTS_TAB_ONLINE").tag(Tab.directory)
                Text("CONTACTS_TAB_NEARBY").tag(Tab.nearby)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            switch tab {
            case .directory: directoryTab
            case .nearby: nearbyTab
            }
        }
        .background(Color.appBackground)
        .navigationTitle(LocalizedStringKey("CONTACTS_ADD_FROM_PHONE_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $openChat) { target in
            // Android lands the user in the chat right after adding; without this iOS stranded
            // them on the picker with a checkmark and no obvious next step.
            SOSChatDetailScreen(sessionId: target.id)
        }
        .analyticsScreen("add_from_contacts")
        .task { await viewModel.load() }
        .refreshable { await viewModel.load() }
        .onChange(of: scenePhase) { phase in
            // The user may have granted Contacts in Settings while backgrounded (Android rescans
            // on ON_RESUME); without this they return to the same denied screen forever.
            guard phase == .active else { return }
            if case .accessDenied = viewModel.state { Task { await viewModel.load() } }
        }
        .onChange(of: tab) { _ in refreshScanner() }
        .onChange(of: discoveryEnabled) { _ in refreshScanner() }
        .onAppear { refreshScanner() }
        .onDisappear {
            manualPairing.cancel()
            deviceScanner.stop()
        }
    }

    // MARK: Directory tab

    @ViewBuilder private var directoryTab: some View {
        switch viewModel.state {
        case .idle, .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .accessDenied:
            statusView(
                icon: "person.crop.circle.badge.exclamationmark",
                messageKey: "CONTACTS_PERMISSION_DENIED",
                actionKey: "PRIVACY_OPEN_SETTINGS"
            ) {
                guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                UIApplication.shared.open(url)
            }
        case .signInRequired:
            statusView(icon: "person.crop.circle.badge.questionmark", messageKey: "CONTACTS_SIGNIN_REQUIRED")
        case .error:
            statusView(
                icon: "wifi.exclamationmark",
                messageKey: "CONTACTS_LOOKUP_FAILED",
                actionKey: "CONTACTS_RETRY"
            ) {
                Task { await viewModel.load() }
            }
        case .loaded(let matches):
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            let filtered = trimmed.isEmpty ? matches : matches.filter {
                $0.displayName.localizedCaseInsensitiveContains(trimmed)
                    || $0.phone.localizedCaseInsensitiveContains(trimmed)
            }
            if matches.isEmpty {
                statusView(icon: "person.2.slash", messageKey: "CONTACTS_NO_MATCHES")
            } else {
                List {
                    Section {
                        ForEach(filtered) { match in matchRow(match) }
                    } header: {
                        Text("CONTACTS_MATCH_SECTION") + Text(" (\(filtered.count))")
                    }
                    if filtered.isEmpty {
                        Text("CONTACTS_NO_MATCHES")
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
                .searchable(text: $query)
            }
        }
    }

    // MARK: Nearby tab (targeted SPAKE2 search — Android's NearbyContactsTab)

    private func refreshScanner() {
        // The passive counter runs only while the Nearby tab is visible AND discovery is opted in —
        // the same gate Android puts on its discovery components.
        if tab == .nearby && discoveryEnabled {
            deviceScanner.start()
        } else {
            deviceScanner.stop()
        }
    }

    @ViewBuilder private var nearbyTab: some View {
        // Discovery is keyed on the phone number (the SPAKE2 password IS the number), so without one
        // the switch would be a lie: it would flip and the responder would silently answer nothing.
        // Lock it and say why — an enabled-looking toggle that does nothing reads as "broken".
        let hasPhone = NearbyPairingSupport.ownPhoneE164() != nil
        List {
            Section {
                Toggle(isOn: Binding(
                    // Without a phone the toggle renders OFF regardless of the stored preference:
                    // it may have been left on by a since-signed-out account, and an "on" switch
                    // for a feature that cannot run is the same lie in reverse.
                    get: { discoveryEnabled && hasPhone },
                    set: { enabled in
                        discoveryEnabled = enabled
                        NearbyDiscoveryPreferences.setEnabled(enabled)
                    }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("NEARBY_DISCOVERY_TOGGLE_TITLE")
                            .font(.body.weight(.medium))
                        Text(hasPhone ? "NEARBY_DISCOVERY_TOGGLE_SUBTITLE" : "NEARBY_DISCOVERY_PHONE_REQUIRED")
                            .font(.caption)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }
                .tint(Color.appPrimary)
                .disabled(!hasPhone)
            }

            // Android's exact state cascade: opt-in hint → Bluetooth recovery → contacts recovery →
            // the live device count + picker. Each dead end names its fix instead of showing an
            // unexplained empty list.
            if !discoveryEnabled || !hasPhone {
                Section {
                    Label("NEARBY_ENABLE_HINT", systemImage: "person.crop.circle.badge.questionmark")
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                }
            } else if deviceScanner.radio == .unauthorized {
                Section {
                    inlineRecovery(
                        messageKey: "NEARBY_SCAN_PERMISSION_NEEDED",
                        actionKey: "PRIVACY_OPEN_SETTINGS"
                    ) {
                        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                        UIApplication.shared.open(url)
                    }
                }
            } else if deviceScanner.radio == .poweredOff {
                Section {
                    Label("RESCUE_ERROR_BLUETOOTH_DISABLED", systemImage: "antenna.radiowaves.left.and.right.slash")
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                }
            } else if case .accessDenied = viewModel.state {
                Section {
                    inlineRecovery(
                        messageKey: "NEARBY_CONTACTS_PERMISSION_NEEDED",
                        actionKey: "PRIVACY_OPEN_SETTINGS"
                    ) {
                        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                        UIApplication.shared.open(url)
                    }
                }
            } else {
                if let target = nearbyTarget {
                    Section {
                        searchStatusRow(target: target)
                    }
                }

                Section {
                    ForEach(viewModel.deviceContacts, id: \.e164) { person in
                        Button {
                            nearbyTarget = person
                            manualPairing.search(displayName: person.displayName, phone: person.e164)
                        } label: {
                            HStack(spacing: 12) {
                                ChatAvatarCircleView(
                                    avatarImageRelativePath: nil,
                                    initials: AvatarGenerator.initials(from: person.displayName),
                                    avatarHue: AvatarGenerator.hue(
                                        for: BroadcastSessionId.fromRawIdentifier(person.e164)
                                    ),
                                    size: 40
                                )
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(person.displayName)
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(Color.primary)
                                        .lineLimit(1)
                                    Text(person.e164)
                                        .font(.caption)
                                        .foregroundStyle(Color.appTextSecondary)
                                }
                                Spacer()
                                Image(systemName: "dot.radiowaves.left.and.right")
                                    .foregroundStyle(Color.appTextSecondary)
                            }
                        }
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(
                            format: NSLocalizedString("NEARBY_DEVICES_COUNT", comment: ""),
                            deviceScanner.deviceCount
                        ))
                        Text("NEARBY_PICK_CONTACT")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    private func inlineRecovery(
        messageKey: String,
        actionKey: String,
        action: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(LocalizedStringKey(messageKey))
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
            Button(LocalizedStringKey(actionKey), action: action)
                .buttonStyle(.borderedProminent)
                .tint(Color.appPrimary)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private func searchStatusRow(target: DeviceContact) -> some View {
        switch manualPairing.status {
        case .idle:
            EmptyView()
        case .searching:
            HStack(spacing: 10) {
                ProgressView()
                Text("NEARBY_SEARCHING")
                    .font(.subheadline)
                Spacer()
                Button("COMMON_CLOSE") {
                    manualPairing.cancel()
                    nearbyTarget = nil
                }
                .font(.subheadline)
            }
        case .paired(let name):
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Text(name)
                    .font(.subheadline.weight(.medium))
                Spacer()
                Button("CONTACTS_ADDED") {
                    // The initiator saved the contact under the shared pair id; jump into the chat.
                    if let record = ContactStore.shared.contacts.first(where: {
                        ($0.peerPhone ?? "") == target.e164 || $0.name == name
                    }) {
                        openChat = ChatTarget(id: record.id)
                    }
                    nearbyTarget = nil
                }
                .font(.subheadline.weight(.semibold))
            }
        case .notFound:
            Label("NEARBY_PAIR_NOT_FOUND", systemImage: "antenna.radiowaves.left.and.right.slash")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
        case .failed:
            Label("NEARBY_PAIR_FAILED", systemImage: "exclamationmark.triangle")
                .font(.subheadline)
                .foregroundStyle(Color.appWarning)
        }
    }

    // MARK: Shared pieces

    private func matchRow(_ match: AddFromContactsViewModel.Match) -> some View {
        HStack(spacing: 12) {
            ChatAvatarCircleView(
                avatarImageRelativePath: nil,
                initials: AvatarGenerator.initials(from: match.displayName),
                // Same uid-derived UUID the created contact will get, so the hue matches later.
                avatarHue: AvatarGenerator.hue(for: BroadcastSessionId.fromRawIdentifier(match.uid)),
                size: 40,
                // The directory already returned this URL and add() persists it as peerPhotoUrl;
                // without passing it the picker shows generated initials for people who DO have a
                // photo, so the row tapped looks nothing like the contact that appears.
                peerPhotoUrl: match.photoUrl
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(match.displayName)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                Text(match.phone)
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
            }

            Spacer()

            if viewModel.addedUids.contains(match.uid) {
                Label("CONTACTS_ADDED", systemImage: "checkmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
                    .labelStyle(.titleAndIcon)
            } else {
                Button {
                    if let sessionId = viewModel.add(match) {
                        openChat = ChatTarget(id: sessionId)
                    }
                } label: {
                    Text("CONTACTS_ADD")
                        .font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.appPrimary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private func statusView(
        icon: String,
        messageKey: String,
        actionKey: String? = nil,
        action: (() -> Void)? = nil
    ) -> some View {
        VStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 40))
                .foregroundStyle(Color.appTextSecondary)
            Text(LocalizedStringKey(messageKey))
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            if let actionKey, let action {
                Button(LocalizedStringKey(actionKey), action: action)
                    .buttonStyle(.borderedProminent)
                    .tint(Color.appPrimary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
