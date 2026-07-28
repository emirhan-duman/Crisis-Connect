import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import * as crypto from "crypto";
import {
  AttestationVerificationError,
  verifyAttestationChain,
} from "../attestation/verifyChain";
import { SecurityLevel } from "../attestation/parseExtension";
import {
  IntegrityVerificationError,
  decodeAndVerifyIntegrity,
  DeviceRecognitionVerdict,
} from "../integrity/playIntegrity";
import {
  AppleAttestationVerificationError,
  verifyAppleAttestation,
} from "../attestation/apple/verifyAttestation";
import { requireUid, resolveCallerRoleKey } from "./callerRole";

const masterPrivateKeySecret = defineSecret("MASTER_PRIVATE_KEY_PEM");

// Version 3 binds the issuing user's agency (e.g. AFAD/FEMA) into the signed
// payload so peers can display a *verified* institution. Bumping the version
// invalidates v2 certificates (their canonical payload lacks the agency field),
// so every device must re-provision once.
const CERTIFICATE_VERSION = 3;
const CERTIFICATE_TTL_MS = 72 * 60 * 60 * 1000;
// Agency is a short display label. It is sanitised before signing so the
// canonical payload can never be ambiguous (the '|' delimiter is stripped) and
// stays bounded. The Android client applies the identical sanitisation when it
// rebuilds the canonical payload for local signature verification.
const AGENCY_MAX_LENGTH = 64;
const RESCUE_DEVICE_ID_REGEX = /^cc-[0-9a-f]{24}$/;
const EXPECTED_PACKAGE_NAME = "com.auralis.crisisconnect";
// Apple App Attest binds the attestation to the app's *App ID* — the 10-char
// Apple Team ID, a dot, then the bundle id — and authData carries
// rpIdHash = SHA-256(appId). The bundle id alone is NOT enough, so iOS
// attestation must be verified against the full App ID.
const APPLE_TEAM_ID = "XY9479JQWV";
const APPLE_APP_ID = `${APPLE_TEAM_ID}.${EXPECTED_PACKAGE_NAME}`;
const ACCEPTED_DEVICE_VERDICTS: DeviceRecognitionVerdict[] = [
  "MEETS_DEVICE_INTEGRITY",
  "MEETS_STRONG_INTEGRITY",
];
const ACCEPTED_SECURITY_LEVELS: SecurityLevel[] = [
  SecurityLevel.trustedEnvironment,
  SecurityLevel.strongBox,
];
const INTEGRITY_MAX_AGE_MS = 5 * 60 * 1000;

function parseUidAllowlist(value: string | undefined, fallback: string): ReadonlySet<string> {
  return new Set(
    (value ?? fallback)
      .split(",")
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0)
  );
}

// TESTER allowlist: these UIDs may provision from a debug/sideloaded build whose Play Integrity
// `appRecognitionVerdict` is UNRECOGNIZED_VERSION. Only the Play-recognition check is relaxed —
// device-integrity verdicts, the nonce, the package name, and the full hardware key-attestation
// chain are STILL enforced, and the role still comes from Firestore users/{uid}.role. Lets the
// developer iterate on a real device without a Play release each time. emirhanduman2009@gmail.com.
const TESTER_UIDS = parseUidAllowlist(
  process.env.CC_TESTER_UIDS,
  "x23DVPQVj2UQlGQTlxDhi4GXR1h2"
);

// DEMO allowlist: these UIDs bypass the ENTIRE device-attestation chain (Play Integrity + device
// integrity + hardware key attestation / Apple App Attest), so the account can obtain a certificate
// on ANY device — including emulators and unlocked/rooted test devices. The client-supplied public
// key is trusted as-is; the role still comes from Firestore users/{uid}.role.
// ⚠ SECURITY: anyone who can sign in as a demo account can obtain its rescue role on any device.
// Keep its password strong, prefer a non-admin role, and DISABLE for production by setting
// CC_DEMO_UIDS="". demo@crisisconnect.network.
const DEMO_UIDS = parseUidAllowlist(
  process.env.CC_DEMO_UIDS,
  "8blrCEoszTWBVWb3lA1TnbSn7aJ3"
);

const DEMO_ATTESTATION_OUTCOME_LABEL = "Demo";
const DEMO_INTEGRITY_VERDICT = "DEMO_BYPASS";

type RescueRole = "admin" | "fieldteam";
type Platform = "android" | "ios";

interface RawIssueInput {
  platform?: unknown;
  deviceId?: unknown;
  publicKey?: unknown;
  attestationChainPem?: unknown;
  integrityToken?: unknown;
  attestationChallenge?: unknown;
  // Display-only device metadata (Android Build.MANUFACTURER/MODEL,
  // iOS "Apple" + friendly model name). Not security-relevant; surfaced in the
  // dashboard certificates list so operators can recognise a responder's device.
  deviceBrand?: unknown;
  deviceModel?: unknown;
  // iOS-only fields
  appAttestKeyId?: unknown;
  appAttestObject?: unknown;
}

interface ValidatedInputBase {
  platform: Platform;
  deviceId: string;
  attestationChallenge: Buffer;
}

interface ValidatedAndroidInput extends ValidatedInputBase {
  platform: "android";
  publicKeyBase64: string;
  publicKeyDer: Buffer;
  attestationChainPem: string[];
  integrityToken: string;
}

interface ValidatedIosInput extends ValidatedInputBase {
  platform: "ios";
  publicKeyBase64: string;
  publicKeyDer: Buffer;
  appAttestKeyId: string;
  appAttestObject: Buffer;
}

type ValidatedInput = ValidatedAndroidInput | ValidatedIosInput;

function resolveSignerPrivateKey(): string {
  const fromSecret = masterPrivateKeySecret.value()?.trim();
  const fromEnv = process.env.MASTER_PRIVATE_KEY_PEM?.trim() ||
    process.env.MASTER_PRIVATE_KEY?.trim();
  const resolved = fromSecret || fromEnv;
  if (!resolved) {
    throw new HttpsError(
      "failed-precondition",
      "Signer private key is not configured."
    );
  }
  return resolved;
}

async function assertAuthed(
  request: CallableRequest<unknown>
): Promise<{ uid: string; role: RescueRole }> {
  const uid = requireUid(request);
  // Role is resolved from Firestore users/{uid}.role (with custom-claim preference);
  // resolveCallerRoleKey normalises "field-team"/"field_team" -> "fieldteam".
  const role = await resolveCallerRoleKey(request, uid);
  if (role !== "admin" && role !== "fieldteam") {
    throw new HttpsError(
      "permission-denied",
      "Only admin or fieldteam devices may request certificates."
    );
  }
  return { uid, role: role as RescueRole };
}

/**
 * Normalises the agency display label so it is safe to embed in the signed
 * canonical payload: strips control characters and the '|' delimiter, collapses
 * whitespace, trims, and bounds the length. Mirrors `RoleCertificate.sanitizeAgency`
 * on the Android client so both sides build an identical canonical string.
 */
function sanitizeAgency(raw: unknown): string {
  if (typeof raw !== "string") {
    return "";
  }
  let cleaned = "";
  for (const ch of raw) {
    const code = ch.codePointAt(0) ?? 0;
    // Drop control characters, DEL, and the '|' canonical delimiter so the
    // signed canonical payload stays unambiguous.
    cleaned += code < 0x20 || code === 0x7f || ch === "|" ? " " : ch;
  }
  return cleaned.replace(/\s+/g, " ").trim().slice(0, AGENCY_MAX_LENGTH).trim();
}

// Max length for the display-only device brand/model labels persisted on the
// certificate document.
const DEVICE_LABEL_MAX_LENGTH = 64;

/**
 * Sanitises a client-supplied device brand/model label for display. Strips
 * control characters and the '|' delimiter, collapses whitespace, trims and
 * bounds the length. Returns null when nothing usable remains. These fields are
 * display-only (never signed), so this is defensive hygiene rather than a
 * security boundary.
 */
function sanitizeDeviceLabel(raw: unknown): string | null {
  if (typeof raw !== "string") {
    return null;
  }
  let cleaned = "";
  for (const ch of raw) {
    const code = ch.codePointAt(0) ?? 0;
    cleaned += code < 0x20 || code === 0x7f || ch === "|" ? " " : ch;
  }
  const result = cleaned
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, DEVICE_LABEL_MAX_LENGTH)
    .trim();
  return result.length > 0 ? result : null;
}

/**
 * Resolves the caller's agency from Firestore `users/{uid}.agency` — the same
 * authoritative document the role is read from. Returns "" when absent so the
 * certificate is still issuable for users without an assigned agency.
 */
async function resolveCallerAgency(uid: string): Promise<string> {
  try {
    const snap = await getFirestore().doc(`users/${uid}`).get();
    const data = snap.data() as { agency?: unknown } | undefined;
    return sanitizeAgency(data?.agency);
  } catch (error) {
    console.warn("Failed to resolve caller agency from Firestore", error);
    return "";
  }
}

function validatePublicKeyBase64(publicKeyBase64: string): Buffer {
  let bytes: Buffer;
  try {
    bytes = Buffer.from(publicKeyBase64, "base64");
  } catch {
    throw new HttpsError("invalid-argument", "publicKey is not valid Base64.");
  }
  if (bytes.length < 64) {
    throw new HttpsError("invalid-argument", "publicKey has an unexpected length.");
  }
  const normalizedInput = publicKeyBase64.replace(/=+$/g, "");
  const canonical = bytes.toString("base64").replace(/=+$/g, "");
  if (normalizedInput !== canonical) {
    throw new HttpsError("invalid-argument", "publicKey is not canonical Base64.");
  }
  return bytes;
}

function validateInput(raw: RawIssueInput | undefined | null): ValidatedInput {
  if (!raw || typeof raw !== "object") {
    throw new HttpsError("invalid-argument", "Request body must be an object.");
  }
  if (typeof raw.deviceId !== "string" || !RESCUE_DEVICE_ID_REGEX.test(raw.deviceId)) {
    throw new HttpsError(
      "invalid-argument",
      "deviceId must match 'cc-' followed by 24 hex characters."
    );
  }
  if (typeof raw.attestationChallenge !== "string" || raw.attestationChallenge.trim() === "") {
    throw new HttpsError("invalid-argument", "attestationChallenge is required.");
  }
  let challengeBytes: Buffer;
  try {
    challengeBytes = Buffer.from(raw.attestationChallenge, "base64");
  } catch {
    throw new HttpsError("invalid-argument", "attestationChallenge is not valid Base64.");
  }
  if (challengeBytes.length < 16) {
    throw new HttpsError(
      "invalid-argument",
      "attestationChallenge is too short."
    );
  }
  const platform: Platform =
    raw.platform === "ios" ? "ios" : "android";

  if (platform === "ios") {
    if (typeof raw.publicKey !== "string" || raw.publicKey.trim() === "") {
      throw new HttpsError(
        "invalid-argument",
        "publicKey is required for iOS (SecureEnclave device key, Base64 SPKI)."
      );
    }
    if (
      typeof raw.appAttestKeyId !== "string" ||
      raw.appAttestKeyId.trim() === ""
    ) {
      throw new HttpsError(
        "invalid-argument",
        "appAttestKeyId is required for iOS."
      );
    }
    if (
      typeof raw.appAttestObject !== "string" ||
      raw.appAttestObject.trim() === ""
    ) {
      throw new HttpsError(
        "invalid-argument",
        "appAttestObject is required for iOS (Base64-encoded CBOR attestation object)."
      );
    }
    let objectBytes: Buffer;
    try {
      objectBytes = Buffer.from(raw.appAttestObject, "base64");
    } catch {
      throw new HttpsError(
        "invalid-argument",
        "appAttestObject is not valid Base64."
      );
    }
    if (objectBytes.length < 64) {
      throw new HttpsError(
        "invalid-argument",
        "appAttestObject decoded payload is unreasonably small."
      );
    }
    const iosPublicKeyBase64 = raw.publicKey.trim();
    const iosPublicKeyDer = validatePublicKeyBase64(iosPublicKeyBase64);
    return {
      platform: "ios",
      deviceId: raw.deviceId,
      publicKeyBase64: iosPublicKeyBase64,
      publicKeyDer: iosPublicKeyDer,
      appAttestKeyId: raw.appAttestKeyId.trim(),
      appAttestObject: objectBytes,
      attestationChallenge: challengeBytes,
    };
  }

  if (typeof raw.publicKey !== "string" || raw.publicKey.trim() === "") {
    throw new HttpsError("invalid-argument", "publicKey is required.");
  }
  if (
    !Array.isArray(raw.attestationChainPem) ||
    raw.attestationChainPem.length === 0 ||
    raw.attestationChainPem.some((p) => typeof p !== "string" || p.trim() === "")
  ) {
    throw new HttpsError(
      "invalid-argument",
      "attestationChainPem must be a non-empty array of PEM strings."
    );
  }
  if (typeof raw.integrityToken !== "string" || raw.integrityToken.trim() === "") {
    throw new HttpsError("invalid-argument", "integrityToken is required.");
  }
  const publicKeyBase64 = raw.publicKey.trim();
  const publicKeyDer = validatePublicKeyBase64(publicKeyBase64);
  return {
    platform: "android",
    deviceId: raw.deviceId,
    publicKeyBase64,
    publicKeyDer,
    attestationChainPem: raw.attestationChainPem as string[],
    integrityToken: raw.integrityToken.trim(),
    attestationChallenge: challengeBytes,
  };
}

async function consumeNonce(
  deviceId: string,
  uid: string,
  expectedChallenge: Buffer
): Promise<void> {
  const db = getFirestore();
  const ref = db.doc(`attestation_nonces/${deviceId}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new HttpsError(
        "failed-precondition",
        "Attestation challenge has not been issued for this device."
      );
    }
    const data = snap.data() as {
      ownerUid?: string;
      challenge?: string;
      expiresAtMs?: number;
      consumed?: boolean;
    };
    if (data.ownerUid !== uid) {
      throw new HttpsError(
        "permission-denied",
        "Attestation challenge is not owned by the calling user."
      );
    }
    if (data.consumed) {
      throw new HttpsError(
        "failed-precondition",
        "Attestation challenge has already been consumed."
      );
    }
    if (!data.expiresAtMs || data.expiresAtMs < Date.now()) {
      throw new HttpsError(
        "deadline-exceeded",
        "Attestation challenge has expired. Request a new challenge."
      );
    }
    const stored = Buffer.from(data.challenge ?? "", "base64");
    if (!stored.equals(expectedChallenge)) {
      throw new HttpsError(
        "permission-denied",
        "Attestation challenge does not match the server-issued nonce."
      );
    }
    tx.update(ref, {
      consumed: true,
      consumedAt: FieldValue.serverTimestamp(),
    });
  });
}

async function assertDeviceOwnership(
  deviceId: string,
  uid: string
): Promise<void> {
  const snap = await getFirestore().doc(`rescueDevices/${deviceId}`).get();
  if (!snap.exists) {
    throw new HttpsError(
      "failed-precondition",
      "Rescue device record does not exist for this deviceId."
    );
  }
  const data = snap.data() as { uid?: string } | undefined;
  if (!data || data.uid !== uid) {
    throw new HttpsError(
      "permission-denied",
      "This device is not registered under your account."
    );
  }
}

async function revokeExistingCertificate(
  uid: string,
  newDeviceId: string
): Promise<void> {
  const db = getFirestore();
  const certId = `cert_${uid}`;
  const ref = db.doc(`certificates/${certId}`);
  const snap = await ref.get();
  if (!snap.exists) return;
  const prev = snap.data() as { status?: string; deviceId?: string } | undefined;
  if (!prev || prev.status !== "active") return;
  await ref.update({
    status: "revoked",
    revokedAt: FieldValue.serverTimestamp(),
    revokedReason: prev.deviceId === newDeviceId
      ? "replaced_same_device"
      : "replaced_new_device",
    revokedByUid: uid,
  });
  await db.collection("auditLogs").add({
    type: "CERTIFICATE_REVOKED",
    event: "CERTIFICATE_REVOKED",
    actorUid: uid,
    targetUid: uid,
    details: `Auto-revoked previous certificate (deviceId=${prev.deviceId ?? "unknown"}) because a new one is being issued.`,
    createdAt: FieldValue.serverTimestamp(),
  }).catch((err) => {
    console.warn("Audit log write failed (auto-revoke)", err);
  });
}

function buildSigningPayload(
  publicKeyBase64: string,
  ownerUid: string,
  role: RescueRole,
  deviceId: string,
  issuedAtMs: number,
  expiresAtMs: number,
  agency: string
): Buffer {
  const canonical =
    `${publicKeyBase64}|${ownerUid}|${role}|${deviceId}|${issuedAtMs}|${expiresAtMs}|${agency}`;
  return Buffer.from(canonical, "utf8");
}

function signPayload(payload: Buffer): Buffer {
  const signerPrivateKey = resolveSignerPrivateKey();
  try {
    return crypto.sign("sha256", payload, {
      key: signerPrivateKey,
      dsaEncoding: "der",
    });
  } catch (error) {
    console.error("Failed to sign role certificate payload", error);
    throw new HttpsError("internal", "Signer failed to issue certificate.");
  }
}

async function writeCertificateDoc(args: {
  uid: string;
  deviceId: string;
  role: RescueRole;
  publicKeyBase64: string;
  signatureBase64: string;
  issuedAtMs: number;
  expiresAtMs: number;
  platform: Platform;
  attestationSecurityLevel: string;
  integrityVerdicts: string[];
  agency: string;
  deviceBrand: string | null;
  deviceModel: string | null;
}): Promise<void> {
  const db = getFirestore();
  const certId = `cert_${args.uid}`;
  await db.doc(`certificates/${certId}`).set({
    certificateVersion: CERTIFICATE_VERSION,
    ownerUid: args.uid,
    deviceId: args.deviceId,
    role: args.role,
    agency: args.agency,
    publicKeyBase64: args.publicKeyBase64,
    signatureBase64: args.signatureBase64,
    issuedAtMs: args.issuedAtMs,
    expiresAtMs: args.expiresAtMs,
    status: "active",
    platform: args.platform,
    attestationSecurityLevel: args.attestationSecurityLevel,
    integrityVerdicts: args.integrityVerdicts,
    // Display-only device metadata for the dashboard certificates list.
    deviceBrand: args.deviceBrand,
    deviceModel: args.deviceModel,
    issuedAt: Timestamp.fromMillis(args.issuedAtMs),
    expiresAt: Timestamp.fromMillis(args.expiresAtMs),
    createdAt: FieldValue.serverTimestamp(),
  });
}

async function writeIssuanceAuditLog(
  uid: string,
  deviceId: string,
  role: RescueRole
): Promise<void> {
  try {
    await getFirestore().collection("auditLogs").add({
      type: "CERTIFICATE_ISSUED",
      event: "CERTIFICATE_ISSUED",
      actorUid: uid,
      targetUid: uid,
      details: `Issued role=${role} certificate bound to deviceId=${deviceId}.`,
      createdAt: FieldValue.serverTimestamp(),
    });
  } catch (error) {
    console.warn("Audit log write failed (issuance)", error);
  }
}

async function logAttestationFailure(
  uid: string,
  deviceId: string,
  code: string,
  detail: string | undefined
): Promise<void> {
  try {
    await getFirestore().collection("auditLogs").add({
      type: "CERTIFICATE_ATTESTATION_FAILED",
      event: "CERTIFICATE_ATTESTATION_FAILED",
      actorUid: uid,
      targetUid: uid,
      details: `code=${code} deviceId=${deviceId} ${detail ?? ""}`.trim(),
      createdAt: FieldValue.serverTimestamp(),
    });
  } catch (error) {
    console.warn("Audit log write failed (attestation failure)", error);
  }
}

interface PlatformAttestationOutcome {
  publicKeyBase64: string;
  attestationSecurityLevel: string;
  integrityVerdicts: string[];
}

async function runAndroidAttestation(
  input: ValidatedAndroidInput,
  uid: string
): Promise<PlatformAttestationOutcome> {
  if (DEMO_UIDS.has(uid)) {
    console.warn(
      `[issueRoleCertificate] DEMO uid=${uid} deviceId=${input.deviceId} — bypassing ALL Android ` +
        "attestation (Play Integrity + device integrity + key attestation). Demo/test only."
    );
    return {
      publicKeyBase64: input.publicKeyBase64,
      attestationSecurityLevel: DEMO_ATTESTATION_OUTCOME_LABEL,
      integrityVerdicts: [DEMO_INTEGRITY_VERDICT],
    };
  }
  const isTester = TESTER_UIDS.has(uid);
  if (isTester) {
    console.warn(
      `[issueRoleCertificate] tester uid=${uid} — relaxing Play-recognition only ` +
        "(device integrity + key attestation still enforced)"
    );
  }
  try {
    await decodeAndVerifyIntegrity(input.integrityToken, {
      packageName: EXPECTED_PACKAGE_NAME,
      acceptedDeviceVerdicts: ACCEPTED_DEVICE_VERDICTS,
      // Testers may use a debug/sideloaded (UNRECOGNIZED_VERSION) build; everyone else must be
      // installed from Play (PLAY_RECOGNIZED).
      requirePlayRecognition: !isTester,
      expectedNonceBase64: input.attestationChallenge.toString("base64"),
      maxAgeMs: INTEGRITY_MAX_AGE_MS,
    });
  } catch (error) {
    if (error instanceof IntegrityVerificationError) {
      // TODO(diagnostic): remove after pinning down the app-not-play-recognized
      // verdict. Logs the exact Play Integrity verdict to Cloud Logging so we can
      // distinguish UNRECOGNIZED_VERSION (binary/signing mismatch) from
      // UNEVALUATED (account/licensing/project mismatch).
      console.warn(
        `[issueRoleCertificate] Play Integrity rejected uid=${uid} ` +
          `deviceId=${input.deviceId} code=${error.code} :: ${error.detail ?? error.message}`
      );
      await logAttestationFailure(uid, input.deviceId, error.code, error.detail);
      throw new HttpsError(
        "permission-denied",
        `Play Integrity check failed: ${error.code}`,
        { code: error.code }
      );
    }
    throw error;
  }

  let attestation;
  try {
    attestation = await verifyAttestationChain({
      chainPem: input.attestationChainPem,
      requirements: {
        acceptedSecurityLevels: ACCEPTED_SECURITY_LEVELS,
        requireDeviceLocked: true,
        requireVerifiedBoot: true,
        expectedChallenge: input.attestationChallenge,
        expectedPublicKeyDer: input.publicKeyDer,
      },
    });
  } catch (error) {
    if (error instanceof AttestationVerificationError) {
      // TODO(diagnostic): remove once chain-untrusted-root is understood.
      console.warn(
        `[issueRoleCertificate] Key attestation rejected uid=${uid} ` +
          `deviceId=${input.deviceId} code=${error.code} :: ${error.detail ?? error.message}`
      );
      await logAttestationFailure(uid, input.deviceId, error.code, error.detail);
      throw new HttpsError(
        "permission-denied",
        `Key attestation failed: ${error.code}`,
        { code: error.code }
      );
    }
    throw error;
  }

  const securityLevelLabel =
    attestation.attestation.attestationSecurityLevel === SecurityLevel.strongBox
      ? "StrongBox"
      : attestation.attestation.attestationSecurityLevel === SecurityLevel.trustedEnvironment
        ? "TrustedEnvironment"
        : "Software";

  return {
    publicKeyBase64: input.publicKeyBase64,
    attestationSecurityLevel: securityLevelLabel,
    integrityVerdicts: ACCEPTED_DEVICE_VERDICTS as unknown as string[],
  };
}

async function runIosAttestation(
  input: ValidatedIosInput,
  uid: string
): Promise<PlatformAttestationOutcome> {
  if (DEMO_UIDS.has(uid)) {
    console.warn(
      `[issueRoleCertificate] DEMO uid=${uid} deviceId=${input.deviceId} — bypassing ALL iOS ` +
        "attestation (Apple App Attest). Demo/test only."
    );
    return {
      publicKeyBase64: input.publicKeyBase64,
      attestationSecurityLevel: DEMO_ATTESTATION_OUTCOME_LABEL,
      integrityVerdicts: [DEMO_INTEGRITY_VERDICT],
    };
  }
  let verified;
  try {
    verified = await verifyAppleAttestation({
      attestationObjectBytes: input.appAttestObject,
      keyIdBase64: input.appAttestKeyId,
      challenge: input.attestationChallenge,
      // Bind the device's SecureEnclave SPKI to the attestation. The iOS
      // client must compute clientDataHash = SHA-256(challenge || publicKey)
      // and pass that to DCAppAttestService.attestKey(_:clientDataHash:).
      // This prevents an attacker who steals the attestation from re-using
      // it with a different device key.
      boundData: input.publicKeyDer,
      expectedBundleId: APPLE_APP_ID,
      // Allow development AAGUID for unsigned debug installs; production
      // builds shipped through the App Store land in the prod environment.
      allowDevelopmentEnvironment: true,
    });
  } catch (error) {
    if (error instanceof AppleAttestationVerificationError) {
      await logAttestationFailure(uid, input.deviceId, error.code, error.detail);
      throw new HttpsError(
        "permission-denied",
        `App Attest verification failed: ${error.code}`,
        { code: error.code }
      );
    }
    throw error;
  }
  return {
    // The issued certificate's `publicKey` field is the device's
    // SecureEnclave public key (which the client uses to sign role proofs)
    // — NOT the App Attest credential cert's public key. App Attest only
    // proves "this is a genuine Apple device that just bound this key".
    publicKeyBase64: input.publicKeyBase64,
    attestationSecurityLevel: verified.isProduction
      ? "AppAttestProduction"
      : "AppAttestDevelopment",
    integrityVerdicts: [verified.isProduction ? "APPLE_APPATTEST_PROD" : "APPLE_APPATTEST_DEV"],
  };
}

export const issueRoleCertificate = onCall(
  {
    secrets: [masterPrivateKeySecret],
    enforceAppCheck: true,
  },
  async (request) => {
    const { uid, role } = await assertAuthed(request);
    const rawInput = request.data as RawIssueInput | undefined;
    const input = validateInput(rawInput);
    const deviceBrand = sanitizeDeviceLabel(rawInput?.deviceBrand);
    const deviceModel = sanitizeDeviceLabel(rawInput?.deviceModel);

    await assertDeviceOwnership(input.deviceId, uid);
    await consumeNonce(input.deviceId, uid, input.attestationChallenge);

    const outcome = input.platform === "ios"
      ? await runIosAttestation(input, uid)
      : await runAndroidAttestation(input, uid);

    await revokeExistingCertificate(uid, input.deviceId);

    const agency = await resolveCallerAgency(uid);
    const issuedAtMs = Date.now();
    const expiresAtMs = issuedAtMs + CERTIFICATE_TTL_MS;
    const payload = buildSigningPayload(
      outcome.publicKeyBase64,
      uid,
      role,
      input.deviceId,
      issuedAtMs,
      expiresAtMs,
      agency
    );
    const signature = signPayload(payload);
    const signatureBase64 = signature.toString("base64");

    await writeCertificateDoc({
      uid,
      deviceId: input.deviceId,
      role,
      publicKeyBase64: outcome.publicKeyBase64,
      signatureBase64,
      issuedAtMs,
      expiresAtMs,
      platform: input.platform,
      attestationSecurityLevel: outcome.attestationSecurityLevel,
      integrityVerdicts: outcome.integrityVerdicts,
      agency,
      deviceBrand,
      deviceModel,
    });

    await writeIssuanceAuditLog(uid, input.deviceId, role);

    return {
      certificateVersion: CERTIFICATE_VERSION,
      ownerUid: uid,
      deviceId: input.deviceId,
      role,
      agency,
      issuedAtMs,
      expiresAtMs,
      certificate: signatureBase64,
      algorithm: "SHA256withECDSA",
      curve: "P-256",
      platform: input.platform,
      publicKey: outcome.publicKeyBase64,
    };
  }
);

export {
  CERTIFICATE_TTL_MS,
  CERTIFICATE_VERSION,
  EXPECTED_PACKAGE_NAME,
};
