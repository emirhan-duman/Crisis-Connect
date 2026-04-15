import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { initializeApp } from "firebase-admin/app";
import * as crypto from "crypto";

initializeApp();

const masterPrivateKeySecret = defineSecret("MASTER_PRIVATE_KEY_PEM");
const CERTIFICATE_VERSION = 1;
const CERTIFICATE_TTL_MS = 72 * 60 * 60 * 1000;
type RescueRole = "admin" | "fieldteam";

function resolveSignerPrivateKey(): string {
  const fromSecret = masterPrivateKeySecret.value()?.trim();
  const fromEnv = process.env.MASTER_PRIVATE_KEY_PEM?.trim() ||
    process.env.MASTER_PRIVATE_KEY?.trim();
  const resolvedKey = fromSecret || fromEnv;
  if (!resolvedKey) {
    throw new HttpsError(
      "failed-precondition",
      "Signer private key is not configured. Set MASTER_PRIVATE_KEY_PEM secret."
    );
  }
  return resolvedKey;
}

function normalizeRescueRole(rawRole: unknown): RescueRole | null {
  if (typeof rawRole !== "string") {
    return null;
  }
  const normalized = rawRole.trim().toLowerCase();
  if (normalized === "admin" || normalized === "fieldteam") {
    return normalized;
  }
  return null;
}

function validatePublicKeyBase64(publicKeyBase64: string): void {
  let publicKeyBytes: Buffer;
  try {
    publicKeyBytes = Buffer.from(publicKeyBase64, "base64");
  } catch (error) {
    throw new HttpsError("invalid-argument", "publicKey is not valid Base64.");
  }

  if (publicKeyBytes.length < 64) {
    throw new HttpsError("invalid-argument", "publicKey has an unexpected length.");
  }

  const normalizedInput = publicKeyBase64.replace(/=+$/g, "");
  const canonical = publicKeyBytes.toString("base64").replace(/=+$/g, "");
  if (normalizedInput !== canonical) {
    throw new HttpsError("invalid-argument", "publicKey is not canonical Base64.");
  }
}

function buildCertificateSigningPayload(
  publicKeyBase64: string,
  ownerUid: string,
  role: RescueRole,
  issuedAtMs: number,
  expiresAtMs: number
): Buffer {
  const canonicalPayload = `${publicKeyBase64}|${ownerUid}|${role}|${issuedAtMs}|${expiresAtMs}`;
  return Buffer.from(canonicalPayload, "utf8");
}

export const issueRoleCertificate = onCall({
  secrets: [masterPrivateKeySecret],
  enforceAppCheck: true,
}, async (request) => {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }

  const role = normalizeRescueRole((auth.token as Record<string, unknown>).role);
  if (!role) {
    throw new HttpsError(
      "permission-denied",
      "Only fieldteam or admin devices may request certificates."
    );
  }

  const ownerUid = auth.uid?.trim();
  if (!ownerUid) {
    throw new HttpsError("failed-precondition", "Authenticated user ID is missing.");
  }

  const rawPublicKey = request.data?.publicKey;
  if (typeof rawPublicKey !== "string") {
    throw new HttpsError("invalid-argument", "publicKey must be a Base64-encoded string.");
  }
  const publicKeyBase64 = rawPublicKey.trim();
  if (!publicKeyBase64) {
    throw new HttpsError("invalid-argument", "publicKey must not be empty.");
  }
  validatePublicKeyBase64(publicKeyBase64);

  const issuedAtMs = Date.now();
  const expiresAtMs = issuedAtMs + CERTIFICATE_TTL_MS;
  const payload = buildCertificateSigningPayload(
    publicKeyBase64,
    ownerUid,
    role,
    issuedAtMs,
    expiresAtMs
  );

  const signerPrivateKey = resolveSignerPrivateKey();
  let signature: Buffer;
  try {
    signature = crypto.sign("sha256", payload, {
      key: signerPrivateKey,
      dsaEncoding: "der",
    });
  } catch (error) {
    console.error("Failed to sign role certificate payload", error);
    throw new HttpsError("internal", "Signer failed to issue certificate.");
  }

  return {
    certificateVersion: CERTIFICATE_VERSION,
    ownerUid,
    role,
    issuedAtMs,
    expiresAtMs,
    certificate: signature.toString("base64"),
    algorithm: "SHA256withECDSA",
    curve: "P-256",
  };
});
