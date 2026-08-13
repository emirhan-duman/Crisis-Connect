//
//  ProfileViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine
import PhotosUI
import SwiftData
import UIKit
import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import GoogleSignIn

/// UI representation of the device-bound role certificate. Mirrors Android's
/// `CertificateStatus` sealed class so the profile screen and the certificate
/// card share a single source of truth (e.g. revoking the cert immediately
/// flips the card to its inactive state).
enum CertificateStatus {
    case loading
    case missing
    case loaded(certificate: RoleCertificate, isExpired: Bool, isRevoked: Bool)
    case failure(message: String)
}

struct CertificateUiState {
    var status: CertificateStatus = .loading
    var provisioning: Bool = false
    var revoking: Bool = false
    var statusMessage: String?
    var errorMessage: String?
}

final class ProfileViewModel: ObservableObject {
    @Published var pickerItem: PhotosPickerItem?
    @Published var avatarImage: UIImage?
    @Published var fullName: String = ""
    @Published var phone: String = ""
    @Published var email: String = ""
    @Published var country: String = ""
    @Published var agency: String = ""
    @Published var bio: String = ""
    @Published var authStatusText: String = "PROFILE_AUTH_SIGNED_OUT"
    @Published var firebaseRole: String = "user"
    @Published var isVerified: Bool = false
    @Published var isAuthLoading: Bool = false
    @Published var isProfileRefreshing: Bool = false
    @Published var authErrorMessage: String?
    @Published var isAnonymous: Bool = true
    @Published var deviceKeySecured: Bool = false
    @Published var certificateReady: Bool = false
    @Published var certificateState = CertificateUiState()
    @Published var signInEmail: String = ""
    @Published var signInPassword: String = ""
    @Published var phoneSignInNumber: String = ""
    @Published var phoneVerificationCode: String = ""
    @Published var isAwaitingPhoneCode: Bool = false

    // Firebase requires the OAuth provider to stay alive for the whole web sign-in flow.
    private var pendingOAuthProvider: OAuthProvider?
    private var ssoWebAuthSession: ASWebAuthenticationSession?
    private let ssoPresentationProvider = SsoPresentationContextProvider()
    private var saveTask: Task<Void, Never>?
    private var profile: Profile?
    private var hasLoaded: Bool = false
    private var avatarNeedsCloudUpload = false
    private var lastAppliedRemotePhotoVersion: String?
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var privacyChangeObserver: NSObjectProtocol?
    private var isSigningOut = false
    private var currentAppleSignInNonce: String?
    private var pendingPhoneOtpTransport: PhoneOtpTransport?
    private lazy var auth: Auth = {
        FirebaseRuntime.ensureConfigured()
        return Auth.auth()
    }()
    private lazy var firestore: Firestore = {
        FirebaseRuntime.ensureConfigured()
        return Firestore.firestore()
    }()
    private lazy var functions: Functions = {
        FirebaseRuntime.ensureConfigured()
        return Functions.functions(region: "us-central1")
    }()
    private let secureStore = SecureLocalStore.shared
    private let securityRepository = SecurityRepository.shared

    func loadIfNeeded(context: ModelContext) {
        guard !hasLoaded else { return }
        hasLoaded = true
        let descriptor = FetchDescriptor<Profile>()
        if let existing = try? context.fetch(descriptor).first {
            profile = existing
            fullName = existing.fullName
            phone = existing.phone
            email = existing.email
            bio = existing.bio
            signInEmail = existing.email
            if let data = existing.avatarImageData, let uiImage = UIImage(data: data) {
                avatarImage = uiImage
                if ProfileMetadataStore.loadAvatarThumbnailData() == nil {
                    ProfileMetadataStore.saveAvatarThumbnailData(uiImage.profileMetadataThumbnailData())
                }
            }
            if ProfileMetadataStore.loadFullName().isEmpty {
                ProfileMetadataStore.saveFullName(existing.fullName)
            }
        }
        let storedFullName = ProfileMetadataStore.loadFullName()
        if !storedFullName.isEmpty {
            fullName = storedFullName
        }
        country = ProfileMetadataStore.loadCountry()
        agency = ProfileMetadataStore.loadAgency()
        firebaseRole = secureStore.loadRole() ?? "user"
        deviceKeySecured = (try? DeviceIdentityStore.shared.getOrCreatePrivateKey()) != nil
        startAuthListener()
        startPrivacyListener()
        FirebaseBootstrapper.shared.start()
        refreshFirebaseProfile()
    }

    func save(context: ModelContext) {
        let current: Profile
        if let existing = profile {
            current = existing
        } else {
            current = Profile()
            context.insert(current)
            profile = current
        }
        current.fullName = fullName
        current.phone = phone
        current.email = email
        current.bio = bio
        ProfileMetadataStore.saveFullName(fullName)
        ProfileMetadataStore.saveCountry(country)
        ProfileMetadataStore.saveAgency(agency)
        if let avatarImage, let normalizedData = avatarImage.profileUploadData() {
            current.avatarImageData = normalizedData
            ProfileMetadataStore.saveAvatarThumbnailData(avatarImage.profileMetadataThumbnailData())
            // Autosave also runs for name/email edits and on screen close. Upload only after a new
            // image selection; otherwise the cached photo can overwrite a deletion from another app.
            if avatarNeedsCloudUpload {
                avatarNeedsCloudUpload = false
                ProfilePhotoUploader.schedule(jpegData: normalizedData)
            }
        } else if current.avatarImageData == nil {
            ProfileMetadataStore.saveAvatarThumbnailData(nil)
        }
        try? context.save()
        syncProfileToFirebase()
    }

    func scheduleAutosave(context: ModelContext) {
        guard !isSigningOut else { return }
        saveTask?.cancel()
        saveTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            self?.save(context: context)
        }
    }

    func handleDisappear(context: ModelContext) {
        saveTask?.cancel()
        save(context: context)
    }

    func loadImage(from item: PhotosPickerItem?) async {
        guard let item else { return }
        if let data = try? await item.loadTransferable(type: Data.self),
           let uiImage = UIImage(data: data) {
            await MainActor.run {
                self.avatarImage = uiImage
                self.avatarNeedsCloudUpload = true
                self.lastAppliedRemotePhotoVersion = nil
            }
        }
    }

    private func syncProfileToFirebase() {
        guard let user = auth.currentUser, !user.isAnonymous else { return }
        let uid = user.uid
        let shouldShareProfileDetails = PrivacyPreferences.isShareProfileDetailsEnabled()
        let normalizedCountry = ProfileMetadataStore.normalizeCountryCode(country)
        let resolvedAgency = ProfileMetadataStore.resolvedAgency(
            email: email,
            country: normalizedCountry,
            explicitAgency: agency
        )
        let trimmedName = fullName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)

        Task {
            let doc = firestore.collection("users").document(uid)
            let existingSnapshot = try? await doc.getDocumentAsync()
            let preservedAgency = (existingSnapshot?.get("agency") as? String ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let preservedPanelId = AgencyRouter.resolvePanelId(
                candidates: [
                    existingSnapshot?.get("agencySlug") as? String,
                    existingSnapshot?.get("agencyKey") as? String,
                    existingSnapshot?.get("panelId") as? String,
                    existingSnapshot?.get("agencyPanelId") as? String,
                    existingSnapshot?.get("panelSlug") as? String,
                    existingSnapshot?.get("panelKey") as? String,
                ],
                fallbackAgency: preservedAgency
            )
            let shouldPreserveAgencyContext = (existingSnapshot?.get("verified") as? Bool == true)
                || RescueRoleAccess.isAuthorized(existingSnapshot?.get("role") as? String)
            var payload: [String: Any] = [
                "updatedAt": FieldValue.serverTimestamp()
            ]

            if shouldShareProfileDetails {
                // Only fields THIS DEVICE owns are written. Android client-writes username only;
                // country and the whole agency context are set by the dashboard/backend. This
                // autosave runs on a 700ms debounce and on screen close, so echoing local copies of
                // server-owned fields let a stale device silently overwrite a dashboard-side agency
                // or country change minutes after it was made. They now flow one-way via
                // refreshFirebaseProfile.
                //
                // An explicit non-empty edit wins over the cloud value, but an empty local field
                // means "no name entered on this device" — leave the cloud username (possibly set
                // on the web panel or Android) alone: deleting on empty silently wiped names set
                // elsewhere.
                if !trimmedName.isEmpty {
                    payload["username"] = trimmedName
                }
                if !trimmedEmail.isEmpty {
                    payload["email"] = trimmedEmail
                }
            } else {
                // Sharing off = stop publishing from this device, not purge the account's
                // cross-platform username (matches FirebaseBootstrapper / PrivacyRemoteSync).
                payload["email"] = FieldValue.delete()
                payload["country"] = FieldValue.delete()
                if shouldPreserveAgencyContext {
                    payload["agency"] = preservedAgency.isEmpty ? FieldValue.delete() : preservedAgency
                    payload["agencySlug"] = preservedPanelId.isEmpty ? FieldValue.delete() : preservedPanelId
                    payload["agencyKey"] = preservedPanelId.isEmpty ? FieldValue.delete() : preservedPanelId
                } else {
                    payload["agency"] = FieldValue.delete()
                    payload["agencySlug"] = FieldValue.delete()
                    payload["agencyKey"] = FieldValue.delete()
                }
            }

            _ = try? await doc.setDataAsync(payload, merge: true)
        }
    }

    func signInWithGoogle() {
        clearAuthError()
        isAuthLoading = true
        NSLog("Starting Google Sign-In flow")
        guard let presenter = FirebaseAuthPresenter.shared.presentingViewController() else {
            handleAuthError(NSError(
                domain: "CrisisConnect.Firebase",
                code: -8,
                userInfo: [NSLocalizedDescriptionKey: "Unable to present Google sign-in screen."]
            ))
            return
        }

        guard let clientID = FirebaseApp.app()?.options.clientID
                ?? Bundle.main.object(forInfoDictionaryKey: "CLIENT_ID") as? String else {
            handleAuthError(NSError(
                domain: "CrisisConnect.Firebase",
                code: -9,
                userInfo: [NSLocalizedDescriptionKey: "Missing Google client ID."]
            ))
            return
        }

        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
                NSLog("Google Sign-In returned from web flow")
                guard let idToken = result.user.idToken?.tokenString else {
                    self.handleAuthError(NSError(
                        domain: "CrisisConnect.Firebase",
                        code: -10,
                        userInfo: [NSLocalizedDescriptionKey: "Google ID token is missing."]
                    ))
                    return
                }
                let accessToken = result.user.accessToken.tokenString
                let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
                NSLog("Google Sign-In produced credential, continuing with Firebase Auth")
                self.completeFederatedSignIn(with: credential)
            } catch {
                self.handleAuthError(error)
            }
        }
    }

    // MARK: - Microsoft / Enterprise SSO / Phone (mirrors Android provider set)

    func signInWithMicrosoft() {
        signInWithFederatedProvider(
            providerID: "microsoft.com",
            customParameters: ["prompt": "select_account"]
        )
    }

    // Enterprise SSO = full web parity: the app drives the SAME custom-OIDC backend the dashboard
    // uses (discover → OIDC → one-time code → Firebase custom token). Adding a tenant on the
    // dashboard works on mobile with no rebuild and no Firebase IdP registration.
    func signInWithEnterpriseSso() {
        // Open the dashboard's own login page (client=mobile) so the user enters their org email
        // there — exactly like the web sign-in — and the web resolves the tenant + redirects to the
        // IdP. The callback returns a one-time code on the crisisconnect:// deep link.
        let base = Self.crisisConnectWebURL
        clearAuthError()
        isAuthLoading = true

        var components = URLComponents(
            url: base.appendingPathComponent("/login"),
            resolvingAgainstBaseURL: false
        )
        let localeCode = Locale.current.language.languageCode?.identifier == "tr" ? "tr" : "en"
        components?.queryItems = [
            URLQueryItem(name: "client", value: "mobile"),
            URLQueryItem(name: "locale", value: localeCode),
        ]
        guard let startURL = components?.url else {
            isAuthLoading = false
            return
        }

        let session = ASWebAuthenticationSession(
            url: startURL,
            callbackURLScheme: "crisisconnect"
        ) { [weak self] callbackURL, error in
            // The session completion may fire off the main thread; @Published mutations need main.
            DispatchQueue.main.async {
                guard let self else { return }
                if let error = error as? ASWebAuthenticationSessionError,
                   error.code == .canceledLogin {
                    self.isAuthLoading = false
                    return
                }
                if let error {
                    self.handleAuthError(error)
                    return
                }
                guard let callbackURL,
                      let items = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems else {
                    self.isAuthLoading = false
                    return
                }
                if items.first(where: { $0.name == "error" })?.value != nil {
                    self.handleAuthError(NSError(
                        domain: "CrisisConnect.SSO",
                        code: -20,
                        userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_SSO_FAILED", comment: "")]
                    ))
                    return
                }
                guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else {
                    self.isAuthLoading = false
                    return
                }
                self.exchangeSsoCode(code, base: base)
            }
        }
        session.presentationContextProvider = ssoPresentationProvider
        session.prefersEphemeralWebBrowserSession = false
        ssoWebAuthSession = session
        session.start()
    }

    private func exchangeSsoCode(_ code: String, base: URL) {
        Task { [weak self] in
            guard let self else { return }
            do {
                var request = URLRequest(url: base.appendingPathComponent("/api/auth/sso/mobile-token"))
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONSerialization.data(withJSONObject: ["code": code])
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                      let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = json["token"] as? String, !token.isEmpty else {
                    throw NSError(
                        domain: "CrisisConnect.SSO",
                        code: -21,
                        userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_SSO_EXCHANGE_FAILED", comment: "")]
                    )
                }
                await MainActor.run {
                    self.auth.signIn(withCustomToken: token) { [weak self] result, error in
                        self?.handleAuthResult(result: result, error: error)
                    }
                }
            } catch {
                await MainActor.run { self.handleAuthError(error) }
            }
        }
    }

    // Dashboard origin: Info.plist override, else the deployed default so SSO works out of the box.
    private static let defaultCrisisConnectWebURL = "https://crisis-connect-1.web.app"
    private static var crisisConnectWebURL: URL {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !raw.isEmpty, let url = URL(string: raw) {
            return url
        }
        return URL(string: defaultCrisisConnectWebURL)!
    }

    private func signInWithFederatedProvider(
        providerID: String,
        customParameters: [String: String] = [:],
        scopes: [String] = []
    ) {
        clearAuthError()
        isAuthLoading = true
        let provider = OAuthProvider(providerID: providerID)
        if !customParameters.isEmpty {
            provider.customParameters = customParameters
        }
        if !scopes.isEmpty {
            provider.scopes = scopes
        }
        pendingOAuthProvider = provider
        provider.getCredentialWith(nil) { [weak self] credential, error in
            guard let self else { return }
            self.pendingOAuthProvider = nil
            if let error = error as NSError? {
                // Treat an explicit user cancel as a no-op rather than an error banner.
                if error.code == AuthErrorCode.webContextCancelled.rawValue {
                    self.isAuthLoading = false
                    return
                }
                self.handleAuthError(error)
                return
            }
            guard let credential else {
                self.isAuthLoading = false
                return
            }
            self.completeFederatedSignIn(with: credential)
        }
    }

    // Native Firebase Phone Auth is primary. Twilio is the guarded fallback when Firebase cannot
    // send a verification code. The code is always verified by the provider that issued it.

    func startPhoneSignIn() {
        let number = phoneSignInNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !number.isEmpty else {
            authErrorMessage = NSLocalizedString("PROFILE_PHONE_REQUIRED", comment: "")
            return
        }
        clearAuthError()
        isAuthLoading = true
        isAwaitingPhoneCode = false
        pendingPhoneOtpTransport = nil
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let verificationID = try await FirebasePhoneAuthFallback.requestCode(
                    phoneNumber: number
                )
                self.pendingPhoneOtpTransport = .firebase(verificationID: verificationID)
                self.isAuthLoading = false
                self.isAwaitingPhoneCode = true
            } catch let firebaseError {
                let nsError = firebaseError as NSError
                NSLog(
                    "Profile phone auth: Firebase send failed domain=%@ code=%ld: %@; trying Twilio fallback.",
                    nsError.domain, nsError.code, nsError.localizedDescription
                )
                do {
                    // The backend localizes the Twilio SMS by this optional param; without it the
                    // SMS falls back to the number's country default. Shape must be ll or ll-RR.
                    var otpPayload: [String: Any] = ["phone": number]
                    if let lang = Locale.current.language.languageCode?.identifier(.alpha2) {
                        let region = Locale.current.region?.identifier
                        otpPayload["locale"] = (region?.count == 2) ? "\(lang)-\(region!)" : lang
                    }
                    _ = try await Functions.functions(region: "us-central1")
                        .httpsCallable("requestPhoneOtp").call(otpPayload)
                    self.pendingPhoneOtpTransport = .twilio
                    self.isAuthLoading = false
                    self.isAwaitingPhoneCode = true
                    NSLog("Profile phone auth: Twilio fallback sent the code.")
                } catch let twilioError {
                    self.isAuthLoading = false
                    self.authErrorMessage = self.phoneServiceErrorMessage(twilioError)
                }
            }
        }
    }

    func confirmPhoneCode() {
        let code = phoneVerificationCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else {
            authErrorMessage = NSLocalizedString("PROFILE_PHONE_CODE_REQUIRED", comment: "")
            return
        }
        let number = phoneSignInNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        clearAuthError()
        isAuthLoading = true
        isAwaitingPhoneCode = false
        phoneVerificationCode = ""
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                guard let pendingPhoneOtpTransport = self.pendingPhoneOtpTransport else {
                    throw NSError(
                        domain: "CrisisConnect.Firebase",
                        code: -23,
                        userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_PHONE_CODE_REQUIRED", comment: "")]
                    )
                }
                let verifiedPhone: String
                switch pendingPhoneOtpTransport {
                case .twilio:
                    let result = try await Functions.functions(region: "us-central1")
                        .httpsCallable("verifyPhoneOtp").call(["phone": number, "code": code])
                    let data = result.data as? [String: Any] ?? [:]
                    let outcome = data["outcome"] as? String ?? ""
                    verifiedPhone = data["phone"] as? String ?? number
                    switch outcome {
                    case "linked":
                        self.handleAuthResult(result: nil, error: nil)
                    case "signin":
                        guard let customToken = data["customToken"] as? String else {
                            throw NSError(
                                domain: "CrisisConnect.Firebase",
                                code: -21,
                                userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_PHONE_CODE_REQUIRED", comment: "")]
                            )
                        }
                        let authResult = try await Auth.auth().signIn(withCustomToken: customToken)
                        self.handleAuthResult(result: authResult, error: nil)
                    default:
                        throw NSError(
                            domain: "CrisisConnect.Firebase",
                            code: -22,
                            userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_PHONE_CODE_REQUIRED", comment: "")]
                        )
                    }
                case .firebase(let verificationID):
                    let authResult = try await FirebasePhoneAuthFallback.verifyCode(
                        verificationID: verificationID,
                        code: code,
                        expectedPhoneNumber: number
                    )
                    verifiedPhone = number
                    self.handleAuthResult(result: authResult, error: nil)
                }
                self.pendingPhoneOtpTransport = nil
                // Number ownership proven → discoverable-by-number default flips on (never
                // overriding an explicit opt-out), and the number is remembered locally: on the
                // "linked" outcome Auth doesn't expose it until the next token refresh, and the
                // SPAKE2 responder keys its handshakes on it.
                NearbyPairingSupport.recordVerifiedOwnPhone(verifiedPhone)
                NearbyDiscoveryPreferences.enableByDefaultIfUnset()
                // Show the number NOW: Auth only exposes it after the next token refresh on the
                // "linked" outcome, and the profile row reads the local copy as its fallback.
                self.phone = verifiedPhone
                self.profile?.phone = verifiedPhone
                // Republish the identity as phone-discoverable so peers' "add from contacts" can
                // find this user (Android parity).
                let displayName = ProfileMetadataStore.loadFullName()
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                Task.detached {
                    guard let publicKey = try? MessagingIdentity.shared.publicKeyBase64() else { return }
                    try? await InternetMessagingClient().publishIdentityKey(
                        publicKeyBase64: publicKey,
                        discoverable: true,
                        phone: verifiedPhone,
                        displayName: displayName.isEmpty ? nil : displayName
                    )
                }
            } catch {
                self.isAwaitingPhoneCode = true
                self.handleAuthError(error)
            }
        }
    }

    func cancelPhoneSignIn() {
        isAwaitingPhoneCode = false
        phoneVerificationCode = ""
        isAuthLoading = false
        pendingPhoneOtpTransport = nil
    }

    func prepareAppleSignInRequest(_ request: ASAuthorizationAppleIDRequest) {
        clearAuthError()
        isAuthLoading = true
        let nonce = Self.randomNonceString()
        currentAppleSignInNonce = nonce
        request.requestedScopes = [.fullName, .email]
        request.nonce = Self.sha256(nonce)
    }

    func handleAppleSignInCompletion(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case .failure(let error):
            currentAppleSignInNonce = nil
            if let authorizationError = error as? ASAuthorizationError,
               authorizationError.code == .canceled {
                isAuthLoading = false
                return
            }
            handleAuthError(error)

        case .success(let authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
                currentAppleSignInNonce = nil
                handleAuthError(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: -11,
                    userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_APPLE_SIGN_IN_UNAVAILABLE", comment: "")]
                ))
                return
            }

            guard let nonce = currentAppleSignInNonce else {
                handleAuthError(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: -12,
                    userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_APPLE_SIGN_IN_NONCE_MISSING", comment: "")]
                ))
                return
            }
            currentAppleSignInNonce = nil

            guard let identityToken = credential.identityToken,
                  let tokenString = String(data: identityToken, encoding: .utf8) else {
                handleAuthError(NSError(
                    domain: "CrisisConnect.Firebase",
                    code: -13,
                    userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("PROFILE_APPLE_SIGN_IN_TOKEN_MISSING", comment: "")]
                ))
                return
            }

            let firebaseCredential = OAuthProvider.appleCredential(
                withIDToken: tokenString,
                rawNonce: nonce,
                fullName: credential.fullName
            )

            if let user = auth.currentUser, user.isAnonymous {
                user.link(with: firebaseCredential) { [weak self] result, error in
                    guard let self else { return }
                    if let error = error as NSError?,
                       error.code == AuthErrorCode.credentialAlreadyInUse.rawValue {
                        self.auth.signIn(with: firebaseCredential) { result, error in
                            self.handleAuthResult(result: result, error: error)
                        }
                        return
                    }
                    self.handleAuthResult(result: result, error: error)
                }
            } else {
                auth.signIn(with: firebaseCredential) { [weak self] result, error in
                    self?.handleAuthResult(result: result, error: error)
                }
            }
        }
    }

    func signInWithEmailPassword() {
        clearAuthError()
        let normalizedEmail = signInEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        let password = signInPassword

        guard !normalizedEmail.isEmpty else {
            authErrorMessage = NSLocalizedString("PROFILE_EMAIL_REQUIRED", comment: "")
            isAuthLoading = false
            return
        }
        guard !password.isEmpty else {
            authErrorMessage = NSLocalizedString("PROFILE_PASSWORD_REQUIRED", comment: "")
            isAuthLoading = false
            return
        }

        isAuthLoading = true
        NSLog("Starting email/password sign-in flow")
        auth.signIn(withEmail: normalizedEmail, password: password) { [weak self] result, error in
            guard let self else { return }
            if let error {
                self.handleEmailSignInError(error)
                return
            }
            self.handleAuthResult(result: result, error: nil)
        }
    }

    func signOut() {
        do {
            NSLog("Signing out current user")
            isSigningOut = true
            saveTask?.cancel()
            saveTask = nil
            try auth.signOut()
            GIDSignIn.sharedInstance.signOut()
            secureStore.clearUid()
            secureStore.clearRole()
            securityRepository.clearStoredCertificate()
            signInEmail = ""
            signInPassword = ""
            email = ""
            applyAuthState(nil)
            firebaseRole = "user"
            isVerified = false
            certificateReady = false
            isAuthLoading = false
            isSigningOut = false
        } catch {
            isSigningOut = false
            handleAuthError(error)
        }
    }

    /// Erases the account and everything attached to it, through the `deleteAccountAndData` callable.
    ///
    /// This used to delete `users/{uid}` straight from the client and then call `user.delete()`. That
    /// Firestore write is REFUSED by the security rules — `users/{userId}` carries
    /// `allow delete: if false` — and `try?` swallowed the denial, so every deletion this app
    /// reported as successful actually left the profile document (name, e-mail, phone, role, agency)
    /// behind forever, owned by an account that no longer existed. The collections that make someone
    /// identifiable are closed to clients as well (`messagingKeys`, the phone-hash discovery
    /// directory, push tokens, prekey bundles are all `allow read, write: if false`), so a client
    /// cannot do this job at all: the erase runs server-side, and every platform calls the same
    /// callable. No re-authentication step is needed any more, because the server no longer relies on
    /// `user.delete()`.
    func deleteAccount(context: ModelContext) {
        guard let user = auth.currentUser else { return }
        clearAuthError()
        isAuthLoading = true
        NSLog("Starting account deletion for uid=\(user.uid)")
        saveTask?.cancel()
        saveTask = nil

        Task { [weak self] in
            guard let self else { return }

            // Apple's own requirement, before anything is destroyed: an app offering Sign in with
            // Apple must revoke the user's token on account deletion. Cancelling here aborts the
            // whole erase, which is why it runs first — nothing has been deleted yet.
            guard await self.revokeAppleTokenIfNeeded() else {
                await MainActor.run { self.isAuthLoading = false }
                return
            }

            do {
                _ = try await self.functions.httpsCallable("deleteAccountAndData").call()
            } catch {
                // The server refuses to erase an account on a stale session — the same rule Firebase
                // itself puts on user.delete(). Prove identity, then retry exactly once.
                if self.requiresRecentLogin(error), await self.reauthenticateForDeletion() {
                    do {
                        _ = try await self.functions.httpsCallable("deleteAccountAndData").call()
                    } catch {
                        NSLog("Account deletion failed after reauth: \(error.localizedDescription)")
                        await MainActor.run {
                            self.authErrorMessage = NSLocalizedString(
                                "PROFILE_DELETE_ACCOUNT_ERROR_GENERIC", comment: ""
                            )
                            self.isAuthLoading = false
                        }
                        return
                    }
                } else if self.requiresRecentLogin(error) {
                    NSLog("Account deletion needs a fresh sign-in")
                    await MainActor.run {
                        self.authErrorMessage = NSLocalizedString(
                            "PROFILE_DELETE_ACCOUNT_ERROR_RECENT_LOGIN", comment: ""
                        )
                        self.isAuthLoading = false
                    }
                    return
                } else {
                    NSLog("Account deletion failed: \(error.localizedDescription)")
                    await MainActor.run {
                        self.authErrorMessage = NSLocalizedString(
                            "PROFILE_DELETE_ACCOUNT_ERROR_GENERIC", comment: ""
                        )
                        self.isAuthLoading = false
                    }
                    return
                }
            }

            // Server state is gone. The on-device half has to follow even if part of it fails —
            // stopping here would leave this device holding a readable copy of the conversations of
            // an account that no longer exists.
            try? self.auth.signOut()
            LocalDataEraser.eraseAll()

            await MainActor.run {
                NSLog("Account deletion completed successfully")
                if let existing = self.profile {
                    context.delete(existing)
                    try? context.save()
                    self.profile = nil
                }
                ProfileMetadataStore.saveFullName("")
                ProfileMetadataStore.saveCountry("")
                ProfileMetadataStore.saveAgency("")
                ProfileMetadataStore.saveAvatarThumbnailData(nil)
                GIDSignIn.sharedInstance.signOut()
                self.secureStore.clearUid()
                self.secureStore.clearRole()
                self.securityRepository.clearStoredCertificate()
                self.signInEmail = ""
                self.signInPassword = ""
                self.email = ""
                self.fullName = ""
                self.phone = ""
                self.bio = ""
                self.avatarImage = nil
                self.applyAuthState(nil)
                self.firebaseRole = "user"
                self.isVerified = false
                self.certificateReady = false
                self.isAuthLoading = false
            }
        }
    }

    /// Providers whose sign-in can be replayed in place. Phone accounts are absent on purpose: their
    /// credential is attached server-side by the OTP flow, so there is nothing to replay here.
    private static let reauthProviderIds: Set<String> = ["google.com", "apple.com", "microsoft.com"]

    /// True when the callable rejected the erase because the caller's sign-in is too old.
    private func requiresRecentLogin(_ error: Error) -> Bool {
        let nsError = error as NSError
        guard nsError.domain == FunctionsErrorDomain,
              nsError.code == FunctionsErrorCode.failedPrecondition.rawValue
        else {
            return false
        }
        return nsError.localizedDescription.contains("requires-recent-login")
    }

    /// Refreshes the sign-in so the erase can proceed, without sending the user hunting for the
    /// button again.
    ///
    /// Only browser-based providers can be refreshed in place — Firebase gives them a single
    /// reauthenticate call. A phone account has no client-side credential to replay, so those users
    /// are told to sign in again instead; that mints a fresh session through the same OTP path and
    /// satisfies the server on the next attempt. Apple permits exactly this kind of verification
    /// step, and forbids only making deletion hard to reach.
    private func reauthenticateForDeletion() async -> Bool {
        guard let user = auth.currentUser else { return false }
        guard let providerId = user.providerData
            .map(\.providerID)
            .first(where: { Self.reauthProviderIds.contains($0) })
        else {
            return false
        }

        do {
            _ = try await user.reauthenticate(
                with: OAuthProvider(providerID: providerId, auth: auth),
                uiDelegate: nil
            )
            NSLog("Reauthenticated with \(providerId) before account deletion")
            return true
        } catch {
            NSLog("Reauthentication before deletion failed: \(error.localizedDescription)")
            return false
        }
    }

    /// Revokes the Sign in with Apple token when the account being deleted has an Apple provider.
    ///
    /// Apple requires this of any app offering Sign in with Apple: deleting the account must also
    /// revoke the token, otherwise the Apple ID keeps listing an app the user has left. Firebase's
    /// `revokeToken` needs an authorization code, and Apple's codes stay valid for only a few
    /// minutes — so a code captured at sign-in months ago is useless here. The only correct source is
    /// a fresh authorization, which is why Apple users get one extra confirmation step.
    ///
    /// - Returns: `false` only when the user backed out of the Apple sheet, meaning the erase should
    ///   be abandoned with nothing destroyed. A revocation *failure* returns `true`: the most likely
    ///   cause is the Apple provider missing its OAuth code-flow key in the Firebase console, and
    ///   refusing to delete the account over that would trap the user in an account they asked to
    ///   leave. Deleting the Firebase user ends the app's access either way.
    private func revokeAppleTokenIfNeeded() async -> Bool {
        let providerIds = auth.currentUser?.providerData.map(\.providerID) ?? []
        guard providerIds.contains("apple.com") else { return true }

        let authorization = AppleAuthorizationCodeRequest()
        do {
            let code = try await authorization.perform()
            try await auth.revokeToken(withAuthorizationCode: code)
            NSLog("Sign in with Apple token revoked before account deletion")
            return true
        } catch {
            if let authorizationError = error as? ASAuthorizationError,
               authorizationError.code == .canceled {
                NSLog("Account deletion cancelled at the Apple confirmation step")
                return false
            }
            NSLog("Sign in with Apple token revocation failed: \(error.localizedDescription)")
            return true
        }
    }

    func clearAuthError() {
        if authErrorMessage != nil {
            authErrorMessage = nil
        }
    }

    // MARK: - Role certificate (mirrors Android ProfileViewModel)

    /// Loads the cached role certificate (if any) for display. Uses
    /// allowExpired=true so the user can still see an expired cert and decide to
    /// renew/revoke. This is UI gating only; signing paths use strict checks.
    func refreshCertificate() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.certificateState.status = .loading
            self.certificateState.errorMessage = nil
            do {
                guard let stored = try await self.securityRepository.getStoredCertificate(allowExpired: true) else {
                    self.certificateState.status = .missing
                    return
                }
                let cert = stored.roleCertificate
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                // Show the cached cert immediately so offline rescue users still see it.
                self.certificateState.status = .loaded(
                    certificate: cert,
                    isExpired: cert.expiresAtMillis < now,
                    isRevoked: false
                )
                // Then confirm it is still active server-side. Only an explicit revoked/missing
                // status downgrades the card (and wipes the local copy inside revalidate);
                // transient network failures return nil and leave the shown cert untouched.
                let serverStatus = await self.securityRepository.revalidateAgainstServer()
                if serverStatus == "revoked" || serverStatus == "missing" {
                    self.certificateState.status = .loaded(
                        certificate: cert,
                        isExpired: cert.expiresAtMillis < now,
                        isRevoked: true
                    )
                }
            } catch {
                self.certificateState.status = .failure(message: self.certificateOperationErrorMessage(error))
            }
        }
    }

    /// Runs the full provisioning flow (register device → challenge → App Attest
    /// → backend issue) and persists the resulting certificate, then refreshes
    /// the card state. Only rescue roles are issued a certificate by the backend.
    func provisionCertificate() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.certificateState.provisioning = true
            self.certificateState.errorMessage = nil

            let authenticatedUser = self.auth.currentUser.flatMap { $0.isAnonymous ? nil : $0 }
            let uid = authenticatedUser?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !uid.isEmpty else {
                self.certificateState.provisioning = false
                self.certificateState.errorMessage = NSLocalizedString("PROFILE_CERT_SIGNIN_REQUIRED", comment: "")
                return
            }

            do {
                _ = try await self.securityRepository.getOrFetchCertificate()
                self.certificateState.provisioning = false
                self.certificateState.statusMessage = NSLocalizedString("PROFILE_CERT_PROVISION_SUCCESS", comment: "")
                self.certificateReady = true
                self.refreshCertificate()
            } catch {
                self.certificateState.provisioning = false
                self.certificateState.errorMessage = self.certificateOperationErrorMessage(error)
            }
        }
    }

    /// Revokes the certificate server-side (`revokeRoleCertificate` callable) and
    /// wipes the locally stored copy so rescue mode is disabled immediately.
    func revokeCertificate(reason: String?) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.certificateState.revoking = true
            self.certificateState.errorMessage = nil
            do {
                let trimmedReason = reason?.trimmingCharacters(in: .whitespacesAndNewlines)
                let reasonValue: Any = (trimmedReason?.isEmpty == false) ? trimmedReason! : NSNull()
                _ = try await self.functions
                    .httpsCallable("revokeRoleCertificate")
                    .call(["reason": reasonValue])
                self.securityRepository.clearStoredCertificate()
                self.certificateState.revoking = false
                self.certificateState.statusMessage = NSLocalizedString("PROFILE_CERT_REVOKE_SUCCESS", comment: "")
                self.certificateReady = false
                self.refreshCertificate()
            } catch {
                self.certificateState.revoking = false
                self.certificateState.errorMessage = self.certificateOperationErrorMessage(error)
            }
        }
    }

    func consumeCertificateMessages() {
        certificateState.statusMessage = nil
        certificateState.errorMessage = nil
    }

    private func certificateOperationErrorMessage(_ error: Error) -> String {
        let message = (error as NSError).localizedDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if message.isEmpty {
            return NSLocalizedString("PROFILE_CERT_FAILURE_TITLE", comment: "")
        }
        return String(message.prefix(180))
    }

    private func completeFederatedSignIn(with credential: AuthCredential) {
        if let user = auth.currentUser, user.isAnonymous {
            user.link(with: credential) { [weak self] result, error in
                guard let self else { return }
                if let error = error as NSError?,
                   error.code == AuthErrorCode.credentialAlreadyInUse.rawValue {
                    self.auth.signIn(with: credential) { result, error in
                        self.handleAuthResult(result: result, error: error)
                    }
                    return
                }
                self.handleAuthResult(result: result, error: error)
            }
        } else {
            auth.signIn(with: credential) { [weak self] result, error in
                self?.handleAuthResult(result: result, error: error)
            }
        }
    }

    func refreshFirebaseProfile() {
        isProfileRefreshing = true
        NSLog("Refreshing Firebase profile state")
        Task { [weak self] in
            guard let self else { return }
            var role = secureStore.loadRole() ?? "user"
            var verified = false
            let user = auth.currentUser?.isAnonymous == false ? auth.currentUser : nil
            var resolvedCountry = ProfileMetadataStore.loadCountry()
            var resolvedAgency = ProfileMetadataStore.loadAgency()
            var resolvedName: String?
            var remotePhotoURL: String?
            var remotePhotoVersion: String?
            var remotePhotoIsAuthoritative = false

            if let user {
                secureStore.saveUid(user.uid)
                let doc = firestore.collection("users").document(user.uid)
                if let snapshot = try? await doc.getDocumentAsync(), snapshot.exists {
                    let data = snapshot.data() ?? [:]
                    resolvedName = ["username", "name", "displayName"]
                        .compactMap { data[$0] as? String }
                        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                        .first(where: { !$0.isEmpty })
                    remotePhotoIsAuthoritative = data.keys.contains("photoURL") || data.keys.contains("photoDeletedAt")
                    remotePhotoURL = (data["photoURL"] as? String)?
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if let timestamp = data["photoUpdatedAt"] as? Timestamp {
                        remotePhotoVersion = String(Int64(timestamp.dateValue().timeIntervalSince1970 * 1_000))
                    } else {
                        remotePhotoVersion = remotePhotoURL
                    }
                    if let remoteRole = snapshot.get("role") as? String {
                        let normalizedRole = remoteRole.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                        if !normalizedRole.isEmpty {
                            role = normalizedRole
                            if RescueRoleAccess.isAuthorized(normalizedRole) {
                                secureStore.saveRole(normalizedRole)
                            }
                        }
                    }
                    let remoteCountry = ProfileMetadataStore.normalizeCountryCode(snapshot.get("country") as? String)
                    if !remoteCountry.isEmpty {
                        resolvedCountry = remoteCountry
                    }
                    let remoteAgency = ProfileMetadataStore.sanitize(snapshot.get("agency") as? String)
                    let userEmail = user.email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? email
                    resolvedAgency = ProfileMetadataStore.resolvedAgency(
                        email: userEmail,
                        country: resolvedCountry,
                        explicitAgency: remoteAgency.isEmpty ? resolvedAgency : remoteAgency
                    )
                    if let remoteVerified = snapshot.get("verified") as? Bool {
                        verified = remoteVerified
                    }
                    if let aesKey = snapshot.get("aesKey") as? String {
                        secureStore.saveAesKey(base64: aesKey)
                    }
                }
            }

            if remotePhotoIsAuthoritative {
                await self.applyRemoteProfilePhoto(urlString: remotePhotoURL, version: remotePhotoVersion)
            }

            let rescueAccess = await FirebaseBootstrapper.shared.refreshRescueAccess()
            let storedRole = secureStore.loadRole()?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if let storedRole, RescueRoleAccess.isAuthorized(storedRole) {
                role = storedRole
            } else {
                role = "user"
            }

            let keySecured = (try? DeviceIdentityStore.shared.getOrCreatePrivateKey()) != nil
            let hasStoredCertificate = await securityRepository.hasUsableStoredCertificate(allowExpired: true)
            let certificateReady = rescueAccess.certificateReady || hasStoredCertificate
            ProfileMetadataStore.saveCountry(resolvedCountry)
            ProfileMetadataStore.saveAgency(resolvedAgency)
            if let resolvedName {
                ProfileMetadataStore.saveFullName(resolvedName)
            }

            await MainActor.run {
                self.applyAuthState(user)
                self.firebaseRole = role
                self.isVerified = verified
                self.deviceKeySecured = keySecured
                self.certificateReady = certificateReady
                self.country = resolvedCountry
                self.agency = resolvedAgency
                if let resolvedName {
                    self.fullName = resolvedName
                    self.profile?.fullName = resolvedName
                }
                self.isProfileRefreshing = false
            }
            // Same trigger Android uses (enqueueMobileProfileSync after a remote refresh): without
            // it the dashboard roster never hears about iOS users at all.
            if let user, !user.isAnonymous {
                await MobileSyncClient.syncProfile(
                    user: user,
                    username: (resolvedName ?? self.fullName).trimmingCharacters(in: .whitespacesAndNewlines),
                    email: self.email.trimmingCharacters(in: .whitespacesAndNewlines),
                    phoneNumber: self.displayPhoneNumber,
                    country: resolvedCountry,
                    agency: resolvedAgency,
                    role: role,
                    verified: verified
                )
            }
            NSLog("Finished refreshing Firebase profile state")
        }
    }

    private func applyRemoteProfilePhoto(urlString: String?, version: String?) async {
        let cleanURL = (urlString ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanURL.isEmpty {
            await MainActor.run {
                guard !self.avatarNeedsCloudUpload else { return }
                self.avatarImage = nil
                self.profile?.avatarImageData = nil
                self.lastAppliedRemotePhotoVersion = version ?? "deleted"
                ProfileMetadataStore.saveAvatarThumbnailData(nil)
            }
            return
        }

        let shouldDownload = await MainActor.run {
            !self.avatarNeedsCloudUpload && self.lastAppliedRemotePhotoVersion != (version ?? cleanURL)
        }
        guard shouldDownload, var components = URLComponents(string: cleanURL) else { return }
        if let version, !version.isEmpty {
            var items = components.queryItems ?? []
            items.removeAll(where: { $0.name == "ccv" })
            items.append(URLQueryItem(name: "ccv", value: version))
            components.queryItems = items
        }
        guard let url = components.url else { return }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            if let http = response as? HTTPURLResponse, !(200 ... 299).contains(http.statusCode) { return }
            guard data.count <= 2_500_000, let image = UIImage(data: data) else { return }
            await MainActor.run {
                guard !self.avatarNeedsCloudUpload else { return }
                self.avatarImage = image
                self.profile?.avatarImageData = data
                self.lastAppliedRemotePhotoVersion = version ?? cleanURL
                ProfileMetadataStore.saveAvatarThumbnailData(image.profileMetadataThumbnailData())
            }
        } catch {
            NSLog("ProfileViewModel: remote avatar refresh failed: %@", String(describing: error))
        }
    }

    private func handleAuthResult(result: AuthDataResult?, error: Error?) {
        if let error = error {
            handleAuthError(error)
            return
        }
        NSLog("Firebase Auth sign-in completed successfully")
        isAuthLoading = false
        if let user = result?.user {
            secureStore.saveUid(user.uid)
            let normalizedEmail = user.email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !normalizedEmail.isEmpty {
                signInEmail = normalizedEmail
                email = normalizedEmail
            }
        }
        signInPassword = ""
        Task { [weak self] in
            await FirebaseBootstrapper.shared.syncAuthenticatedSession()
            await MainActor.run {
                self?.refreshFirebaseProfile()
            }
        }
    }

    private func handleEmailSignInError(_ error: Error) {
        let nsError = error as NSError
        NSLog(
            "Email sign-in error [%@:%ld]: %@",
            nsError.domain,
            nsError.code,
            nsError.localizedDescription
        )
        DispatchQueue.main.async {
            self.authErrorMessage = self.resolveEmailSignInErrorMessage(error)
            self.isAuthLoading = false
        }
    }

    private func resolveEmailSignInErrorMessage(_ error: Error) -> String {
        let nsError = error as NSError
        let code = nsError.code

        NSLog(
            "Resolving email sign-in error — domain: %@, code: %ld, description: %@",
            nsError.domain, code, error.localizedDescription
        )

        let invalidCredentialsMessage = NSLocalizedString(
            "PROFILE_EMAIL_SIGN_IN_INVALID_CREDENTIALS",
            comment: ""
        )

        guard let authErrorCode = AuthErrorCode(rawValue: code) else {
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_ERROR", comment: "")
        }

        switch authErrorCode {
        case .wrongPassword, .invalidEmail, .invalidCredential, .userNotFound:
            return invalidCredentialsMessage
        case .operationNotAllowed:
            NSLog("Firebase Email/Password sign-in provider is DISABLED in Firebase Console.")
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_ERROR", comment: "")
        case .networkError:
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_NETWORK_ERROR", comment: "")
        case .tooManyRequests:
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_TOO_MANY_REQUESTS", comment: "")
        case .userDisabled:
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_USER_DISABLED", comment: "")
        case .appNotAuthorized:
            NSLog("Firebase App Check rejected the auth request — verify debug token or App Attest configuration.")
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_ERROR", comment: "")
        default:
            NSLog("Unhandled email sign-in AuthErrorCode: %ld", code)
            return NSLocalizedString("PROFILE_EMAIL_SIGN_IN_ERROR", comment: "")
        }
    }

    /// Classifies a failure from the Twilio fallback callable. Infrastructure failures should read
    /// as service unavailable, while policy failures keep the backend's concrete reason.
    private func phoneServiceErrorMessage(_ error: Error) -> String {
        let nsError = error as NSError
        if nsError.domain == FunctionsErrorDomain,
           let code = FunctionsErrorCode(rawValue: nsError.code) {
            switch code {
            case .resourceExhausted, .invalidArgument, .permissionDenied, .failedPrecondition:
                return error.localizedDescription // genuine policy rejection — show the real reason
            default:
                return NSLocalizedString("PROFILE_PHONE_SERVICE_UNAVAILABLE", comment: "")
            }
        }
        return NSLocalizedString("PROFILE_PHONE_SERVICE_UNAVAILABLE", comment: "")
    }

    private func handleAuthError(_ error: Error) {
        let nsError = error as NSError
        NSLog(
            "Auth error [%@:%ld]: %@",
            nsError.domain,
            nsError.code,
            nsError.localizedDescription
        )
        DispatchQueue.main.async {
            self.authErrorMessage = error.localizedDescription
            self.isAuthLoading = false
        }
    }

    private static func sha256(_ input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func randomNonceString(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var nonce = ""
        nonce.reserveCapacity(length)

        while nonce.count < length {
            let randomValues = (0..<16).map { _ in UInt8.random(in: .min ... .max) }
            for random in randomValues {
                if nonce.count == length {
                    break
                }
                if Int(random) < charset.count {
                    nonce.append(charset[Int(random)])
                }
            }
        }

        return nonce
    }

    private func startAuthListener() {
        guard authHandle == nil else { return }
        authHandle = auth.addStateDidChangeListener { [weak self] _, user in
            guard let self else { return }
            DispatchQueue.main.async {
                self.applyAuthState(user)
            }
        }
    }

    /// One-shot user feedback (Android's snackbar equivalent — iOS had NO feedback channel at all,
    /// so a failed rename or photo save looked identical to a successful one). Rendered as a
    /// bottom banner by ProfileView; auto-cleared there.
    @Published var transientMessage: String?

    /// The verified phone for display. Auth exposes phoneNumber lazily after the "linked" OTP
    /// outcome (only on the next token refresh), so fall back to the locally stored number or the
    /// row never appears in the very session the user verified in.
    var displayPhoneNumber: String {
        let authPhone = (auth.currentUser?.phoneNumber ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !authPhone.isEmpty { return authPhone }
        return phone.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Commits a rename with Android's semantics: reject blank up front (the autosave deliberately
    /// skips blank names, so the old path silently "renamed" locally while the cloud kept the old
    /// value — the user thought it worked), write with a REAL error path instead of try?, and only
    /// report success on a confirmed cloud write.
    func commitUsername(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            transientMessage = NSLocalizedString("PROFILE_USERNAME_REQUIRED", comment: "")
            return
        }
        fullName = trimmed
        guard let user = auth.currentUser, !user.isAnonymous else { return }
        let uid = user.uid
        Task { @MainActor [weak self] in
            do {
                try await self?.firestore.collection("users").document(uid)
                    .setDataAsync([
                        "username": trimmed,
                        "name": trimmed,
                        "displayName": trimmed,
                        "updatedAt": FieldValue.serverTimestamp()
                    ], merge: true)
                let profileChange = user.createProfileChangeRequest()
                profileChange.displayName = trimmed
                try? await profileChange.commitChanges()
                self?.transientMessage = NSLocalizedString("PROFILE_USERNAME_UPDATE_SUCCESS", comment: "")
                if let self, let user = self.auth.currentUser, !user.isAnonymous {
                    await MobileSyncClient.syncProfile(
                        user: user,
                        username: trimmed,
                        email: self.email,
                        phoneNumber: self.displayPhoneNumber,
                        country: self.country,
                        agency: self.agency,
                        role: self.firebaseRole,
                        verified: self.isVerified
                    )
                }
            } catch {
                self?.transientMessage = NSLocalizedString("PROFILE_USERNAME_UPDATE_ERROR", comment: "")
            }
        }
    }

    func removeProfilePhoto(context: ModelContext) {
        saveTask?.cancel()
        avatarNeedsCloudUpload = false
        lastAppliedRemotePhotoVersion = "deleted"
        pickerItem = nil
        avatarImage = nil
        profile?.avatarImageData = nil
        ProfileMetadataStore.saveAvatarThumbnailData(nil)
        try? context.save()

        Task { @MainActor [weak self] in
            do {
                try await ProfilePhotoUploader.removeCurrentPhoto()
                self?.transientMessage = NSLocalizedString("PROFILE_PHOTO_REMOVED", comment: "")
            } catch {
                self?.transientMessage = NSLocalizedString("PROFILE_PHOTO_REMOVE_ERROR", comment: "")
            }
        }
    }

    private var certificateEventCancellable: AnyCancellable?

    private func startPrivacyListener() {
        guard privacyChangeObserver == nil else { return }
        // The background cert warm-up can finish while this screen is open; without this the card
        // kept showing "missing" plus a Request button and invited a redundant manual provision.
        if certificateEventCancellable == nil {
            Task { @MainActor [weak self] in
                guard let self, self.certificateEventCancellable == nil else { return }
                self.certificateEventCancellable = CertificateProvisioningNotifier.shared.$event
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] event in
                        switch event {
                        case .success, .failure: self?.refreshCertificate()
                        default: break
                        }
                    }
            }
        }
        privacyChangeObserver = NotificationCenter.default.addObserver(
            forName: .privacyPreferencesDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncProfileToFirebase()
        }
    }

    private func applyAuthState(_ user: User?) {
        if let user = user {
            isAnonymous = user.isAnonymous
            let email = user.email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !email.isEmpty {
                signInEmail = email
            }
            if isAnonymous {
                authStatusText = "PROFILE_AUTH_ANONYMOUS"
            } else if !email.isEmpty {
                authStatusText = email
            } else {
                authStatusText = "PROFILE_AUTH_SIGNED_IN"
            }
        } else {
            isAnonymous = true
            authStatusText = "PROFILE_AUTH_SIGNED_OUT"
        }
    }

    deinit {
        if let authHandle {
            auth.removeStateDidChangeListener(authHandle)
        }
        if let privacyChangeObserver {
            NotificationCenter.default.removeObserver(privacyChangeObserver)
        }
    }
}

private extension UIImage {
    func profileUploadData(side: CGFloat = 512) -> Data? {
        guard size.width > 0, size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let targetSize = CGSize(width: side, height: side)
        let scaleFactor = max(side / size.width, side / size.height)
        let drawSize = CGSize(width: size.width * scaleFactor, height: size.height * scaleFactor)
        let drawOrigin = CGPoint(x: (side - drawSize.width) / 2, y: (side - drawSize.height) / 2)
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let normalized = renderer.image { context in
            context.cgContext.setFillColor(UIColor.systemBackground.cgColor)
            context.cgContext.fill(CGRect(origin: .zero, size: targetSize))
            draw(in: CGRect(origin: drawOrigin, size: drawSize))
        }
        return normalized.jpegData(compressionQuality: 0.82)
    }

    func profileMetadataThumbnailData(side: CGFloat = 72) -> Data? {
        let minSide = min(size.width, size.height)
        guard minSide > 0 else { return nil }
        let origin = CGPoint(x: (size.width - minSide) / 2, y: (size.height - minSide) / 2)
        let cropRect = CGRect(origin: origin, size: CGSize(width: minSide, height: minSide))
        let cropped: UIImage
        if let cgImage, let croppedImage = cgImage.cropping(to: cropRect) {
            cropped = UIImage(cgImage: croppedImage, scale: scale, orientation: imageOrientation)
        } else {
            cropped = self
        }
        let targetSize = CGSize(width: side, height: side)
        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let thumbnail = renderer.image { _ in
            cropped.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return thumbnail.jpegData(compressionQuality: 0.78)
    }
}

/// One-shot Sign in with Apple authorization used only to obtain a fresh authorization code.
///
/// The sign-in path uses SwiftUI's `SignInWithAppleButton`, which cannot be triggered from code.
/// Account deletion has to raise the Apple sheet itself, so it drives `ASAuthorizationController`
/// directly and bridges the delegate callbacks to an `async` call. No scopes are requested: this is
/// not a sign-in, and the only field that matters is the authorization code.
private final class AppleAuthorizationCodeRequest: NSObject,
    ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {

    enum Failure: LocalizedError {
        case missingAuthorizationCode

        var errorDescription: String? {
            "Apple did not return an authorization code."
        }
    }

    private var continuation: CheckedContinuation<String, Error>?
    /// Held for the duration of the request: the controller does not reliably keep itself alive
    /// while the Apple sheet is up, and a deallocated one never calls back — the continuation would
    /// hang and the deletion flow would sit on its spinner forever.
    private var controller: ASAuthorizationController?

    @MainActor
    func perform() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = []
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            self.controller = controller
            controller.performRequests()
        }
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let codeData = credential.authorizationCode,
              let code = String(data: codeData, encoding: .utf8),
              !code.isEmpty
        else {
            finish(with: .failure(Failure.missingAuthorizationCode))
            return
        }
        finish(with: .success(code))
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        finish(with: .failure(error))
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    /// Guards against a second delegate callback resuming an already-consumed continuation, which
    /// would trap at runtime rather than fail gracefully.
    private func finish(with result: Result<String, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        self.controller = nil
        continuation.resume(with: result)
    }
}

/// Anchors the enterprise-SSO web auth session to the foreground key window.
private final class SsoPresentationContextProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}
