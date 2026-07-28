//
//  OnboardingPhoneStep.swift
//  Crisis Connect
//
//  The welcome flow's "internet" step — iOS port of Android's WelcomeInternetStep: an optional,
//  skippable phone verification so contacts who know the user's number can find them over the
//  internet (the messaging directory only lists phone-verified users). On success the phone
//  credential is LINKED to the anonymous account (uid — and thus the messaging identity — is
//  preserved) and the identity key is republished as discoverable by this number, mirroring
//  Android's `MessagingBootstrap.ensureRegistered(context, phone)`.
//

import Foundation
import Combine
import SwiftUI
import FirebaseAuth
import FirebaseFunctions

// MARK: - Country dial data

/// One selectable country: ISO region, localized display name, ITU dial code, emoji flag.
struct PhoneCountry: Identifiable, Hashable {
    let iso: String
    let dialCode: Int
    var id: String { iso }

    var name: String {
        Locale.current.localizedString(forRegionCode: iso) ?? iso
    }

    /// Regional-indicator emoji flag — native look on iOS with zero assets.
    var flag: String {
        iso.unicodeScalars.reduce(into: "") { result, scalar in
            if let flagScalar = UnicodeScalar(127397 + scalar.value) {
                result.unicodeScalars.append(flagScalar)
            }
        }
    }

    /// Full country list from the embedded ITU table, sorted by localized name.
    static let all: [PhoneCountry] = dialCodes
        .map { PhoneCountry(iso: $0.key, dialCode: $0.value) }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

    /// Best-guess starting country: the device region, else Turkey (primary audience), else US.
    static func defaultCountry() -> PhoneCountry {
        let region = Locale.current.region?.identifier ?? ""
        return all.first { $0.iso == region }
            ?? all.first { $0.iso == "TR" }
            ?? PhoneCountry(iso: "US", dialCode: 1)
    }

    // ITU E.164 country calling codes (public assignment data).
    private static let dialCodes: [String: Int] = [
        "AF": 93, "AL": 355, "DZ": 213, "AS": 1, "AD": 376, "AO": 244, "AI": 1, "AG": 1,
        "AR": 54, "AM": 374, "AW": 297, "AU": 61, "AT": 43, "AZ": 994, "BS": 1, "BH": 973,
        "BD": 880, "BB": 1, "BY": 375, "BE": 32, "BZ": 501, "BJ": 229, "BM": 1, "BT": 975,
        "BO": 591, "BA": 387, "BW": 267, "BR": 55, "BN": 673, "BG": 359, "BF": 226, "BI": 257,
        "KH": 855, "CM": 237, "CA": 1, "CV": 238, "KY": 1, "CF": 236, "TD": 235, "CL": 56,
        "CN": 86, "CO": 57, "KM": 269, "CG": 242, "CD": 243, "CR": 506, "CI": 225, "HR": 385,
        "CU": 53, "CW": 599, "CY": 357, "CZ": 420, "DK": 45, "DJ": 253, "DM": 1, "DO": 1,
        "EC": 593, "EG": 20, "SV": 503, "GQ": 240, "ER": 291, "EE": 372, "SZ": 268, "ET": 251,
        "FJ": 679, "FI": 358, "FR": 33, "GF": 594, "PF": 689, "GA": 241, "GM": 220, "GE": 995,
        "DE": 49, "GH": 233, "GI": 350, "GR": 30, "GL": 299, "GD": 1, "GP": 590, "GU": 1,
        "GT": 502, "GN": 224, "GW": 245, "GY": 592, "HT": 509, "HN": 504, "HK": 852, "HU": 36,
        "IS": 354, "IN": 91, "ID": 62, "IR": 98, "IQ": 964, "IE": 353, "IL": 972, "IT": 39,
        "JM": 1, "JP": 81, "JO": 962, "KZ": 7, "KE": 254, "KI": 686, "KP": 850, "KR": 82,
        "KW": 965, "KG": 996, "LA": 856, "LV": 371, "LB": 961, "LS": 266, "LR": 231, "LY": 218,
        "LI": 423, "LT": 370, "LU": 352, "MO": 853, "MG": 261, "MW": 265, "MY": 60, "MV": 960,
        "ML": 223, "MT": 356, "MH": 692, "MQ": 596, "MR": 222, "MU": 230, "MX": 52, "FM": 691,
        "MD": 373, "MC": 377, "MN": 976, "ME": 382, "MA": 212, "MZ": 258, "MM": 95, "NA": 264,
        "NR": 674, "NP": 977, "NL": 31, "NC": 687, "NZ": 64, "NI": 505, "NE": 227, "NG": 234,
        "MK": 389, "NO": 47, "OM": 968, "PK": 92, "PW": 680, "PS": 970, "PA": 507, "PG": 675,
        "PY": 595, "PE": 51, "PH": 63, "PL": 48, "PT": 351, "PR": 1, "QA": 974, "RE": 262,
        "RO": 40, "RU": 7, "RW": 250, "KN": 1, "LC": 1, "VC": 1, "WS": 685, "SM": 378,
        "ST": 239, "SA": 966, "SN": 221, "RS": 381, "SC": 248, "SL": 232, "SG": 65, "SX": 1,
        "SK": 421, "SI": 386, "SB": 677, "SO": 252, "ZA": 27, "SS": 211, "ES": 34, "LK": 94,
        "SD": 249, "SR": 597, "SE": 46, "CH": 41, "SY": 963, "TW": 886, "TJ": 992, "TZ": 255,
        "TH": 66, "TL": 670, "TG": 228, "TO": 676, "TT": 1, "TN": 216, "TR": 90, "TM": 993,
        "TC": 1, "TV": 688, "UG": 256, "UA": 380, "AE": 971, "GB": 44, "US": 1, "UY": 598,
        "UZ": 998, "VU": 678, "VA": 39, "VE": 58, "VN": 84, "VG": 1, "VI": 1, "YE": 967,
        "ZM": 260, "ZW": 263, "XK": 383,
    ]
}

// MARK: - Verification controller

/// Drives the optional phone verification: send code → confirm → link to the anonymous account →
/// republish the identity as phone-discoverable. All best-effort; the step is always skippable.
@MainActor
final class OnboardingPhoneVerification: ObservableObject {
    @Published var country: PhoneCountry = PhoneCountry.defaultCountry()
    @Published var nationalNumber = ""
    @Published var code = ""
    @Published var codeSent = false
    @Published var isBusy = false
    @Published var errorText: String?
    @Published var verifiedPhone: String?

    /// The number in E.164. A leading "+" in the field overrides the country selection, matching
    /// what users paste from their own contact card. National trunk '0' is dropped.
    var e164Number: String {
        let raw = nationalNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.hasPrefix("+") {
            return "+" + raw.dropFirst().filter(\.isNumber)
        }
        var digits = raw.filter(\.isNumber)
        if digits.hasPrefix("0") { digits.removeFirst() }
        return "+\(country.dialCode)\(digits)"
    }

    var hasNumberInput: Bool {
        !nationalNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // Verification rides the backend's Twilio Verify callables (requestPhoneOtp/verifyPhoneOtp)
    // instead of FirebaseAuth's native phone flow: the native path needs APNs/attestation plumbing
    // that is broken on current iOS + SDK combinations (Auth's internal phone managers never
    // initialize — verifyPhoneNumber errors or crashes). The server checks the OTP with Twilio and
    // binds the proven number to the caller's account with the Admin SDK — no client plumbing.

    func sendCode() {
        let number = e164Number
        guard number.count > 4 else { return }
        errorText = nil
        isBusy = true
        Task { @MainActor in
            do {
                _ = try await Functions.functions(region: InternetMessagingClient.region)
                    .httpsCallable("requestPhoneOtp").call({
                        // Localizes the Twilio SMS; shape must be ll or ll-RR or the backend's
                        // validator silently drops it.
                        var payload: [String: Any] = ["phone": number]
                        if let lang = Locale.current.language.languageCode?.identifier(.alpha2) {
                            let region = Locale.current.region?.identifier
                            payload["locale"] = (region?.count == 2) ? "\(lang)-\(region!)" : lang
                        }
                        return payload
                    }())
                self.isBusy = false
                self.codeSent = true
            } catch {
                self.isBusy = false
                let nsError = error as NSError
                NSLog(
                    "OnboardingPhone: requestPhoneOtp failed domain=%@ code=%ld: %@",
                    nsError.domain, nsError.code, nsError.localizedDescription
                )
                self.errorText = "\(NSLocalizedString("ONBOARDING_PHONE_UNAVAILABLE", comment: "")) (\(nsError.localizedDescription))"
            }
        }
    }

    func verify(displayName: String) {
        let trimmedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedCode.isEmpty else { return }
        errorText = nil
        isBusy = true
        let number = e164Number
        Task { @MainActor in
            do {
                // Nobody may be signed in yet this early in onboarding — the callable requires
                // auth, and the anonymous account is also what the number gets bound to.
                if Auth.auth().currentUser == nil {
                    _ = try await Auth.auth().signInAnonymously()
                }
                let result = try await Functions.functions(region: InternetMessagingClient.region)
                    .httpsCallable("verifyPhoneOtp").call(["phone": number, "code": trimmedCode])
                let data = result.data as? [String: Any] ?? [:]
                let outcome = data["outcome"] as? String ?? ""
                let verifiedNumber = data["phone"] as? String ?? number
                switch outcome {
                case "linked":
                    break // Number attached to this account server-side; uid (and identity) kept.
                case "signin":
                    // The number already belongs to another account — proving possession signs
                    // the user into it (same semantics as Firebase phone sign-in).
                    guard let customToken = data["customToken"] as? String else {
                        throw InternetMessagingClientError.malformedResponse
                    }
                    _ = try await Auth.auth().signIn(withCustomToken: customToken)
                default:
                    throw InternetMessagingClientError.malformedResponse
                }
                self.isBusy = false
                self.verifiedPhone = verifiedNumber
                // The number is proven: make this device discoverable to contacts who have it
                // (default-on, never overriding an explicit opt-out) — and remember the number
                // locally, because on the "linked" outcome Auth doesn't expose it until the next
                // token refresh and the SPAKE2 responder needs it NOW.
                NearbyPairingSupport.recordVerifiedOwnPhone(verifiedNumber)
                NearbyDiscoveryPreferences.enableByDefaultIfUnset()
                // Republish the identity key as discoverable by this number so "add from
                // contacts" on peers' devices can find the user (Android parity). Runs under
                // whichever uid we ended up with.
                let name = displayName
                Task.detached {
                    guard let publicKey = try? MessagingIdentity.shared.publicKeyBase64() else { return }
                    try? await InternetMessagingClient().publishIdentityKey(
                        publicKeyBase64: publicKey,
                        discoverable: true,
                        phone: verifiedNumber,
                        displayName: name.isEmpty ? nil : name
                    )
                }
            } catch {
                self.isBusy = false
                let nsError = error as NSError
                NSLog(
                    "OnboardingPhone: verifyPhoneOtp failed domain=%@ code=%ld: %@",
                    nsError.domain, nsError.code, nsError.localizedDescription
                )
                self.errorText = NSLocalizedString("ONBOARDING_PHONE_CODE_ERROR", comment: "")
            }
        }
    }
}

// MARK: - Step card

/// The internet-step content card: three benefit rows, then the phone form / code entry /
/// verified banner. The step's primary button is hoisted to the onboarding container (Skip →
/// Send code → Verify → Continue), matching Android's single-bottom-action flow.
struct OnboardingInternetStepCard: View {
    @ObservedObject var verification: OnboardingPhoneVerification

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 12) {
                AppIconBadge(systemName: "antenna.radiowaves.left.and.right", tint: .appPrimary, size: 42)
                VStack(alignment: .leading, spacing: 3) {
                    Text("ONBOARDING_INTERNET_TITLE")
                        .font(.headline)
                    Text("ONBOARDING_INTERNET_BODY")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            VStack(alignment: .leading, spacing: 12) {
                benefitRow(icon: "arrow.down.circle", titleKey: "ONBOARDING_INTERNET_LOWBW_TITLE", detailKey: "ONBOARDING_INTERNET_LOWBW_DETAIL")
                benefitRow(icon: "antenna.radiowaves.left.and.right.circle", titleKey: "ONBOARDING_INTERNET_BASESTATION_TITLE", detailKey: "ONBOARDING_INTERNET_BASESTATION_DETAIL")
                benefitRow(icon: "lock.circle", titleKey: "ONBOARDING_INTERNET_E2E_TITLE", detailKey: "ONBOARDING_INTERNET_E2E_DETAIL")
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(Color.appSurfaceMuted)
            )

            Divider()

            if let verified = verification.verifiedPhone {
                verifiedBanner(number: verified)
            } else {
                phoneForm
            }

            Text("ONBOARDING_INTERNET_OPTIONAL_NOTE")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var phoneForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("ONBOARDING_INTERNET_PHONE_TITLE")
                    .font(.subheadline.weight(.semibold))
                Text("ONBOARDING_INTERNET_PHONE_DETAIL")
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }

            HStack(spacing: 8) {
                Menu {
                    ForEach(PhoneCountry.all) { country in
                        Button("\(country.flag) \(country.name) (+\(country.dialCode))") {
                            verification.country = country
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(verification.country.flag)
                        Text("+\(verification.country.dialCode)")
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.primary)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption2)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 12)
                    .background(fieldBackground)
                }
                .disabled(verification.codeSent)

                TextField(NSLocalizedString("ONBOARDING_INTERNET_PHONE_LABEL", comment: ""), text: $verification.nationalNumber)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .disabled(verification.codeSent)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(fieldBackground)
            }

            if verification.codeSent {
                TextField(NSLocalizedString("ONBOARDING_INTERNET_CODE_LABEL", comment: ""), text: $verification.code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(fieldBackground)
            }

            if verification.isBusy {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("ONBOARDING_INTERNET_WORKING")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }

            if let error = verification.errorText {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Color.appDanger)
            }
        }
    }

    private func verifiedBanner(number: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.seal.fill")
                .foregroundStyle(Color.appSuccess)
            Text(String(format: NSLocalizedString("ONBOARDING_INTERNET_VERIFIED", comment: ""), number))
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appSuccess.opacity(0.10))
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(Color.appSuccess.opacity(0.2), lineWidth: 1)
        )
    }

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
            .fill(Color.appSurfaceMuted)
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
    }

    private func benefitRow(icon: String, titleKey: String, detailKey: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.body.weight(.semibold))
                .foregroundStyle(Color.appPrimary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(LocalizedStringKey(titleKey))
                    .font(.subheadline.weight(.semibold))
                Text(LocalizedStringKey(detailKey))
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
