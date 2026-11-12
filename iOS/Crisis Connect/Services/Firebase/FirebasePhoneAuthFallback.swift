import Foundation
import FirebaseAuth
import UIKit

/// Identifies which backend issued the code currently shown in the OTP form. The code must be
/// verified by the same provider that sent it; a Twilio code is not valid for Firebase and vice
/// versa.
enum PhoneOtpTransport: Equatable {
    case twilio
    case firebase(verificationID: String)
}

/// Native Firebase Phone Auth used as the primary phone-verification transport. Callers fall back
/// to the guarded Twilio callable only when Firebase cannot send a code.
@MainActor
enum FirebasePhoneAuthFallback {
    static func requestCode(phoneNumber: String) async throws -> String {
        FirebaseRuntime.ensureConfigured()
        // Registration is idempotent and does not request notification permission. Firebase Auth
        // uses the APNs token for a silent app-verification push and falls back to reCAPTCHA when
        // silent push is unavailable (including the simulator).
        UIApplication.shared.registerForRemoteNotifications()
        return try await PhoneAuthProvider.provider(auth: Auth.auth()).verifyPhoneNumber(
            phoneNumber,
            uiDelegate: FirebaseAuthPresenter.shared
        )
    }

    /// Verifies the Firebase-issued code and preserves the current UID whenever the phone
    /// credential is new. If the credential already belongs to another Firebase account, proving
    /// possession signs into that account, matching the server/Twilio flow's `signin` outcome.
    static func verifyCode(
        verificationID: String,
        code: String,
        expectedPhoneNumber: String
    ) async throws -> AuthDataResult? {
        FirebaseRuntime.ensureConfigured()
        let auth = Auth.auth()
        let credential = PhoneAuthProvider.provider(auth: auth).credential(
            withVerificationID: verificationID,
            verificationCode: code
        )

        guard let currentUser = auth.currentUser else {
            return try await auth.signIn(with: credential)
        }

        // Firebase Auth persists its current user in the Keychain, while deleting/reinstalling the
        // app resets onboarding's UserDefaults. A returning user can therefore reach onboarding
        // with a phone provider already attached. Calling link(with:) in that state fails locally
        // with providerAlreadyLinked *before Firebase validates the freshly received SMS code*.
        // Reauthenticate the same phone account instead; if the cached profile has not hydrated a
        // phone number yet (or the user entered another number), normal phone sign-in lets Firebase
        // resolve the credential to the account that owns it.
        if hasPhoneProvider(currentUser) {
            try? await currentUser.reload()
            if samePhoneNumber(linkedPhoneNumber(currentUser), expectedPhoneNumber) {
                return try await currentUser.reauthenticate(with: credential)
            }
            return try await auth.signIn(with: credential)
        }

        do {
            return try await currentUser.link(with: credential)
        } catch {
            let nsError = error as NSError
            let authCode = AuthErrorCode(rawValue: nsError.code)
            if authCode == .providerAlreadyLinked {
                // The provider can become linked between the preflight check and this callback.
                // link(with:) has not consumed the phone credential in this client-side branch.
                try? await currentUser.reload()
                if samePhoneNumber(linkedPhoneNumber(currentUser), expectedPhoneNumber) {
                    return try await currentUser.reauthenticate(with: credential)
                }
                return try await auth.signIn(with: credential)
            }
            if authCode == .credentialAlreadyInUse {
                // Phone linking returns a temporary proof as an updated credential. Firebase's
                // documented recovery path is to use that credential, not replay the original SMS
                // credential after the link RPC has already consumed it.
                let recoveryCredential = nsError.userInfo[AuthErrorUserInfoUpdatedCredentialKey]
                    as? AuthCredential ?? credential
                return try await auth.signIn(with: recoveryCredential)
            }
            throw error
        }
    }

    private static func hasPhoneProvider(_ user: User) -> Bool {
        user.providerData.contains { $0.providerID == PhoneAuthProviderID }
    }

    private static func linkedPhoneNumber(_ user: User) -> String? {
        user.phoneNumber
            ?? user.providerData.first(where: { $0.providerID == PhoneAuthProviderID })?.phoneNumber
    }

    private static func samePhoneNumber(_ lhs: String?, _ rhs: String?) -> Bool {
        guard let lhs, let rhs else { return false }
        let leftDigits = lhs.filter(\.isNumber)
        let rightDigits = rhs.filter(\.isNumber)
        return !leftDigits.isEmpty && leftDigits == rightDigits
    }
}
