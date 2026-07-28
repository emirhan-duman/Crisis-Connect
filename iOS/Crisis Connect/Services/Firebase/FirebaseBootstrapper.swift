//
//  FirebaseBootstrapper.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import Foundation
import FirebaseAuth
import FirebaseFirestore

struct RescueAccessState {
    let role: String
    let certificateReady: Bool
}

final class FirebaseBootstrapper {
    static let shared = FirebaseBootstrapper()

    private lazy var auth: Auth = {
        FirebaseRuntime.ensureConfigured()
        return Auth.auth()
    }()
    private lazy var db: Firestore = {
        FirebaseRuntime.ensureConfigured()
        return Firestore.firestore()
    }()
    private let secureStore = SecureLocalStore.shared
    private let securityRepository = SecurityRepository.shared
    private var hasStarted = false

    private init() {}

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task { await bootstrap() }
    }

    func syncAuthenticatedSession() async {
        await bootstrapAuthenticatedSession()
    }

    private func bootstrap() async {
        _ = secureStore.getOrCreateAesKeyBase64()
        _ = try? DeviceIdentityStore.shared.getOrCreatePrivateKey()
        await bootstrapAuthenticatedSession()
        // Make this device reachable over internet messaging. Signs in anonymously when there is no
        // account, so QR-added contacts work online without an explicit login. Anonymous sessions
        // are now a valid state (a QR-only messaging identity); every real-account feature already
        // guards on `!user.isAnonymous`, so keeping the session around is safe and it is no longer
        // cleared. See MessagingRegistrar.
        await MessagingRegistrar.ensureRegistered()
    }

    private func ensureUserDocument(for user: User) async {
        let doc = db.collection("users").document(user.uid)
        let snapshot = try? await doc.getDocumentAsync()
        let sharingEnabled = PrivacyPreferences.isShareProfileDetailsEnabled()

        if snapshot?.exists != true {
            let data: [String: Any] = [
                "id": user.uid,
                "platform": "ios",
                "role": "user",
                "verified": false,
                "createdAt": FieldValue.serverTimestamp()
            ]
            let payload = sharingEnabled
                ? data.merging(sharedProfileFields(for: user)) { _, new in new }
                : data
            _ = try? await doc.setDataAsync(payload, merge: false)
            return
        }

        if let snapshot {
            if let aesKey = snapshot.get("aesKey") as? String {
                secureStore.saveAesKey(base64: aesKey)
            }
            if let role = snapshot.get("role") as? String {
                let normalizedRole = role.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if RescueRoleAccess.isAuthorized(normalizedRole) {
                    secureStore.saveRole(normalizedRole)
                }
            }
        }

        var updates: [String: Any] = [:]
        if sharingEnabled {
            updates.merge(sharedProfileFields(for: user, existingSnapshot: snapshot)) { _, new in new }
        } else {
            // Sharing off means "don't publish from this device" — never "purge the account".
            // username is cross-platform state (web Settings and Android write it too), so this
            // launch-time sync must not delete it; doing so made names set on other platforms
            // vanish while every mobile UI kept showing its local cache.
            updates["email"] = FieldValue.delete()
            updates["country"] = FieldValue.delete()
            let shouldPreserveAgencyContext = (snapshot?.get("verified") as? Bool == true)
                || RescueRoleAccess.isAuthorized(snapshot?.get("role") as? String)
            if shouldPreserveAgencyContext {
                let preservedAgency = (snapshot?.get("agency") as? String ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let preservedPanelId = AgencyRouter.resolvePanelId(
                    candidates: [
                        snapshot?.get("agencySlug") as? String,
                        snapshot?.get("agencyKey") as? String,
                        snapshot?.get("panelId") as? String,
                        snapshot?.get("agencyPanelId") as? String,
                        snapshot?.get("panelSlug") as? String,
                        snapshot?.get("panelKey") as? String,
                    ],
                    fallbackAgency: preservedAgency
                )
                updates["agency"] = preservedAgency.isEmpty ? FieldValue.delete() : preservedAgency
                updates["agencySlug"] = preservedPanelId.isEmpty ? FieldValue.delete() : preservedPanelId
                updates["agencyKey"] = preservedPanelId.isEmpty ? FieldValue.delete() : preservedPanelId
            } else {
                updates["agency"] = FieldValue.delete()
                updates["agencySlug"] = FieldValue.delete()
                updates["agencyKey"] = FieldValue.delete()
            }
        }
        if !updates.isEmpty {
            _ = try? await doc.setDataAsync(updates, merge: true)
        }
    }

    private func sharedProfileFields(
        for user: User,
        existingSnapshot: DocumentSnapshot? = nil
    ) -> [String: Any] {
        var fields: [String: Any] = [:]
        let fullName = ProfileMetadataStore.loadFullName()
        let email = user.email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let country = ProfileMetadataStore.loadCountry()
        let explicitAgency = ProfileMetadataStore.loadAgency()
        let preservedAgency = (existingSnapshot?.get("agency") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // Passive launch-time sync: only seed username when the cloud has none. A name saved on
        // the web panel or Android must not be overwritten by this device's local cache — only an
        // explicit edit in the profile editor (ProfileViewModel) pushes a new name.
        let remoteUsername = ((existingSnapshot?.get("username") as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if remoteUsername.isEmpty {
            if !fullName.isEmpty {
                fields["username"] = fullName
            } else if let displayName = user.displayName, !displayName.isEmpty {
                fields["username"] = displayName
            }
        }
        if !email.isEmpty {
            fields["email"] = email
        }
        if !country.isEmpty {
            fields["country"] = country
        }

        let resolvedAgency = ProfileMetadataStore.resolvedAgency(
            email: email,
            country: country,
            explicitAgency: explicitAgency
        )
        let effectiveAgency = !resolvedAgency.isEmpty ? resolvedAgency : preservedAgency
        if !effectiveAgency.isEmpty {
            fields["agency"] = effectiveAgency
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
            if !agencySlug.isEmpty {
                fields["agencySlug"] = agencySlug
                fields["agencyKey"] = agencySlug
            }
        }

        return fields
    }

    private func bootstrapAuthenticatedSession() async {
        guard let user = auth.currentUser, !user.isAnonymous else {
            _ = await refreshRescueAccess()
            return
        }

        secureStore.saveUid(user.uid)
        CrashReporter.updateCurrentUser(uid: user.uid)
        await ensureUserDocument(for: user)
        await PrivacyRemoteSync.syncProfileDetails()
        _ = await refreshRescueAccess()
    }

    // Note: anonymous sessions are intentionally NOT cleared anymore — an anonymous session is a
    // valid QR-only internet-messaging identity (see MessagingRegistrar). Every real-account
    // feature already gates on `!user.isAnonymous`, so the app still presents as "signed out" until
    // an explicit login, while the messaging identity persists across launches.

    @discardableResult
    func refreshRescueAccess() async -> RescueAccessState {
        let cachedCertificateRole = await securityRepository.getUsableStoredCertificateRole(allowExpired: true)
        let cachedRescueRole = RescueRoleAccess.normalizedRole(cachedCertificateRole)

        guard let user = auth.currentUser, !user.isAnonymous else {
            if let cachedRescueRole {
                secureStore.saveRole(cachedRescueRole)
                CrashReporter.updateCurrentUser(
                    uid: secureStore.loadUid(),
                    role: cachedRescueRole,
                    certificateReady: true
                )
                return RescueAccessState(role: cachedRescueRole, certificateReady: true)
            }
            secureStore.clearRole()
            CrashReporter.updateCurrentUser(uid: secureStore.loadUid(), role: "user", certificateReady: false)
            return RescueAccessState(role: "user", certificateReady: false)
        }

        secureStore.saveUid(user.uid)

        switch await FirebaseRoleHelper.fetchRescueRole() {
        case .authorized(let role):
            secureStore.saveRole(role)
            let warmed = await warmUpCertificateWithBanner()
            let hasStoredCertificate = await securityRepository.hasUsableStoredCertificate(allowExpired: true)
            let certificateReady = warmed || hasStoredCertificate
            CrashReporter.updateCurrentUser(uid: user.uid, role: role, certificateReady: certificateReady)
            return RescueAccessState(role: role, certificateReady: certificateReady)

        case .unauthorized:
            secureStore.clearRole()
            securityRepository.clearStoredCertificate()
            CrashReporter.updateCurrentUser(uid: user.uid, role: "user", certificateReady: false)
            return RescueAccessState(role: "user", certificateReady: false)

        case .unauthenticated:
            if let cachedRescueRole {
                secureStore.saveRole(cachedRescueRole)
                CrashReporter.updateCurrentUser(uid: user.uid, role: cachedRescueRole, certificateReady: true)
                return RescueAccessState(role: cachedRescueRole, certificateReady: true)
            }
            secureStore.clearRole()
            CrashReporter.updateCurrentUser(uid: user.uid, role: "user", certificateReady: false)
            return RescueAccessState(role: "user", certificateReady: false)

        case .failure:
            if let cachedRescueRole {
                secureStore.saveRole(cachedRescueRole)
                CrashReporter.updateCurrentUser(uid: user.uid, role: cachedRescueRole, certificateReady: true)
                return RescueAccessState(role: cachedRescueRole, certificateReady: true)
            }
            secureStore.clearRole()
            CrashReporter.updateCurrentUser(uid: user.uid, role: "user", certificateReady: false)
            return RescueAccessState(role: "user", certificateReady: false)
        }
    }

    /// Provisions the rescue certificate, surfacing progress in the floating banner the first
    /// time this device obtains one (Android's attachCertificateAutoProvisioner behavior).
    /// A device that already holds a usable certificate re-provisions silently, and the role
    /// gate is already satisfied here: this only runs from the `.authorized` branch.
    private func warmUpCertificateWithBanner() async -> Bool {
        // Server-side revocation check first: a dashboard-revoked certificate is wiped here
        // (stored cert + attested key) so the provisioning below either re-issues a fresh one
        // — the role gate above is already satisfied — or leaves rescue mode off, instead of the
        // stale cert lingering until natural expiry. Transient network failures leave it untouched.
        await securityRepository.revalidateAgainstServer()
        let alreadyHadUsable = await securityRepository.hasUsableStoredCertificate()
        if !alreadyHadUsable {
            await CertificateProvisioningNotifier.shared.emitInProgress()
        }
        do {
            _ = try await securityRepository.getOrFetchCertificate()
            if !alreadyHadUsable {
                await CertificateProvisioningNotifier.shared.emitSuccess()
            }
            return true
        } catch {
            NSLog("FirebaseBootstrapper: rescue certificate provisioning failed: %@", String(describing: error))
            if !alreadyHadUsable {
                await CertificateProvisioningNotifier.shared.emitFailure(error)
            }
            return false
        }
    }
}
