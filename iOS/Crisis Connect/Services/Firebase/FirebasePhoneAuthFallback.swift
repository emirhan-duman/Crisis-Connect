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

        do {
            return try await currentUser.link(with: credential)
        } catch {
            let authCode = AuthErrorCode(rawValue: (error as NSError).code)
            if authCode == .providerAlreadyLinked,
               samePhoneNumber(currentUser.phoneNumber, expectedPhoneNumber) {
                return nil
            }
            if authCode == .credentialAlreadyInUse {
                return try await auth.signIn(with: credential)
            }
            throw error
        }
    }

    private static func samePhoneNumber(_ lhs: String?, _ rhs: String?) -> Bool {
        guard let lhs, let rhs else { return false }
        let leftDigits = lhs.filter(\.isNumber)
        let rightDigits = rhs.filter(\.isNumber)
        return !leftDigits.isEmpty && leftDigits == rightDigits
    }
}
