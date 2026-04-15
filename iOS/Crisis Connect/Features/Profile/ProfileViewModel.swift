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
import GoogleSignIn

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
    @Published var signInEmail: String = ""
    @Published var signInPassword: String = ""

    private var saveTask: Task<Void, Never>?
    private var profile: Profile?
    private var hasLoaded: Bool = false
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var privacyChangeObserver: NSObjectProtocol?
    private var isSigningOut = false
    private var currentAppleSignInNonce: String?
    private lazy var auth: Auth = {
        FirebaseRuntime.ensureConfigured()
        return Auth.auth()
    }()
    private lazy var firestore: Firestore = {
        FirebaseRuntime.ensureConfigured()
        return Firestore.firestore()
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
        if let avatarImage, let data = avatarImage.jpegData(compressionQuality: 0.9) {
            current.avatarImageData = data
            ProfileMetadataStore.saveAvatarThumbnailData(avatarImage.profileMetadataThumbnailData())
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
                payload["username"] = trimmedName.isEmpty ? FieldValue.delete() : trimmedName
                payload["email"] = trimmedEmail.isEmpty ? FieldValue.delete() : trimmedEmail
                payload["country"] = normalizedCountry.isEmpty ? FieldValue.delete() : normalizedCountry

                let effectiveAgency = !resolvedAgency.isEmpty ? resolvedAgency : preservedAgency
                if effectiveAgency.isEmpty {
                    payload["agency"] = FieldValue.delete()
                    payload["agencySlug"] = FieldValue.delete()
                    payload["agencyKey"] = FieldValue.delete()
                } else {
                    payload["agency"] = effectiveAgency
                    let agencySlug = AgencyRouter.resolvePanelId(
                        candidates: [
                            existingSnapshot?.get("agencySlug") as? String,
                            existingSnapshot?.get("agencyKey") as? String,
                            existingSnapshot?.get("panelId") as? String,
                            existingSnapshot?.get("agencyPanelId") as? String,
                            existingSnapshot?.get("panelSlug") as? String,
                            existingSnapshot?.get("panelKey") as? String,
                        ],
                        fallbackAgency: effectiveAgency
                    )
                    payload["agencySlug"] = agencySlug.isEmpty ? FieldValue.delete() : agencySlug
                    payload["agencyKey"] = agencySlug.isEmpty ? FieldValue.delete() : agencySlug
                }
            } else {
                payload["username"] = FieldValue.delete()
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
                self.completeGoogleSignIn(with: credential)
            } catch {
                self.handleAuthError(error)
            }
        }
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

    func clearAuthError() {
        if authErrorMessage != nil {
            authErrorMessage = nil
        }
    }

    private func completeGoogleSignIn(with credential: AuthCredential) {
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

            if let user {
                secureStore.saveUid(user.uid)
                let doc = firestore.collection("users").document(user.uid)
                if let snapshot = try? await doc.getDocumentAsync(), snapshot.exists {
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

            await MainActor.run {
                self.applyAuthState(user)
                self.firebaseRole = role
                self.isVerified = verified
                self.deviceKeySecured = keySecured
                self.certificateReady = certificateReady
                self.country = resolvedCountry
                self.agency = resolvedAgency
                self.isProfileRefreshing = false
            }
            NSLog("Finished refreshing Firebase profile state")
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

    private func startPrivacyListener() {
        guard privacyChangeObserver == nil else { return }
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
