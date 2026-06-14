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

const CERTIFICATE_VERSION = 2;
const CERTIFICATE_TTL_MS = 72 * 60 * 60 * 1000;
const RESCUE_DEVICE_ID_REGEX = /^cc-[0-9a-f]{24}$/;
const EXPECTED_PACKAGE_NAME = "com.auralis.crisisconnect";
const ACCEPTED_DEVICE_VERDICTS: DeviceRecognitionVerdict[] = [
  "MEETS_DEVICE_INTEGRITY",
  "MEETS_STRONG_INTEGRITY",
];
const ACCEPTED_SECURITY_LEVELS: SecurityLevel[] = [
  SecurityLevel.trustedEnvironment,
  SecurityLevel.strongBox,
];
const INTEGRITY_MAX_AGE_MS = 5 * 60 * 1000;

type RescueRole = "admin" | "fieldteam";
type Platform = "android" | "ios";

interface RawIssueInput {
  platform?: unknown;
  deviceId?: unknown;
  publicKey?: unknown;
  attestationChainPem?: unknown;
  integrityToken?: unknown;
  attestationChallenge?: unknown;
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
  expiresAtMs: number
): Buffer {
  const canonical = `${publicKeyBase64}|${ownerUid}|${role}|${deviceId}|${issuedAtMs}|${expiresAtMs}`;
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
}): Promise<void> {
  const db = getFirestore();
  const certId = `cert_${args.uid}`;
  await db.doc(`certificates/${certId}`).set({
    certificateVersion: CERTIFICATE_VERSION,
    ownerUid: args.uid,
    deviceId: args.deviceId,
    role: args.role,
    publicKeyBase64: args.publicKeyBase64,
    signatureBase64: args.signatureBase64,
    issuedAtMs: args.issuedAtMs,
    expiresAtMs: args.expiresAtMs,
    status: "active",
    platform: args.platform,
    attestationSecurityLevel: args.attestationSecurityLevel,
    integrityVerdicts: args.integrityVerdicts,
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
  try {
    await decodeAndVerifyIntegrity(input.integrityToken, {
      packageName: EXPECTED_PACKAGE_NAME,
      acceptedDeviceVerdicts: ACCEPTED_DEVICE_VERDICTS,
      requirePlayRecognition: true,
      expectedNonceBase64: input.attestationChallenge.toString("base64"),
      maxAgeMs: INTEGRITY_MAX_AGE_MS,
    });
  } catch (error) {
    if (error instanceof IntegrityVerificationError) {
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
      expectedBundleId: EXPECTED_PACKAGE_NAME,
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
    const input = validateInput(request.data as RawIssueInput | undefined);

    await assertDeviceOwnership(input.deviceId, uid);
    await consumeNonce(input.deviceId, uid, input.attestationChallenge);

    const outcome = input.platform === "ios"
      ? await runIosAttestation(input, uid)
      : await runAndroidAttestation(input, uid);

    await revokeExistingCertificate(uid, input.deviceId);

    const issuedAtMs = Date.now();
    const expiresAtMs = issuedAtMs + CERTIFICATE_TTL_MS;
    const payload = buildSigningPayload(
      outcome.publicKeyBase64,
      uid,
      role,
      input.deviceId,
      issuedAtMs,
      expiresAtMs
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
    });

    await writeIssuanceAuditLog(uid, input.deviceId, role);

    return {
      certificateVersion: CERTIFICATE_VERSION,
      ownerUid: uid,
      deviceId: input.deviceId,
      role,
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
