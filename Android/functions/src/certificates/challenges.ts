import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import * as crypto from "crypto";
import { requireUid, resolveCallerRoleKey, CERT_ELIGIBLE_ROLE_KEYS } from "./callerRole";

const NONCE_TTL_MS = 5 * 60 * 1000;
const NONCE_BYTES = 32;
const RESCUE_DEVICE_ID_REGEX = /^cc-[0-9a-f]{24}$/;

async function assertCertEligible(request: CallableRequest<unknown>): Promise<string> {
  const uid = requireUid(request);
  const role = await resolveCallerRoleKey(request, uid);
  if (!CERT_ELIGIBLE_ROLE_KEYS.has(role)) {
    throw new HttpsError(
      "permission-denied",
      "Only admin or fieldteam roles may request attestation challenges."
    );
  }
  return uid;
}

function parseDeviceId(data: unknown): string {
  if (!data || typeof data !== "object") {
    throw new HttpsError("invalid-argument", "Request body must be an object.");
  }
  const deviceId = (data as Record<string, unknown>).deviceId;
  if (typeof deviceId !== "string" || !RESCUE_DEVICE_ID_REGEX.test(deviceId)) {
    throw new HttpsError(
      "invalid-argument",
      "deviceId must match 'cc-' followed by 24 hex characters."
    );
  }
  return deviceId;
}

async function assertDeviceOwnership(deviceId: string, uid: string): Promise<void> {
  const db = getFirestore();
  const snap = await db.doc(`rescueDevices/${deviceId}`).get();
  if (!snap.exists) {
    throw new HttpsError(
      "failed-precondition",
      "Rescue device record does not exist for this deviceId. Create it first."
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

export const requestAttestationChallenge = onCall(
  { enforceAppCheck: true },
  async (request) => {
    const uid = await assertCertEligible(request);
    const deviceId = parseDeviceId(request.data);
    await assertDeviceOwnership(deviceId, uid);

    const nonceBytes = crypto.randomBytes(NONCE_BYTES);
    const challengeBase64 = nonceBytes.toString("base64");
    const issuedAtMs = Date.now();
    const expiresAtMs = issuedAtMs + NONCE_TTL_MS;

    await getFirestore().doc(`attestation_nonces/${deviceId}`).set({
      ownerUid: uid,
      challenge: challengeBase64,
      issuedAtMs,
      expiresAtMs,
      consumed: false,
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      challenge: challengeBase64,
      issuedAtMs,
      expiresAtMs,
      ttlMs: NONCE_TTL_MS,
    };
  }
);

export const NONCE_TTL_MS_VALUE = NONCE_TTL_MS;
