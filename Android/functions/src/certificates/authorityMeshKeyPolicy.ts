import { createHash, createPublicKey, verify } from "node:crypto";

export const AUTHORITY_KEY_PERIOD_MS = 72 * 60 * 60 * 1000;
export const AUTHORITY_KEY_PROOF_NONCE = "cc-authority-mesh-key-v2";

export function authorityKeyEpoch(now: number) {
  const epoch = Math.floor(now / AUTHORITY_KEY_PERIOD_MS);
  return { keyId: `authority-v2-${epoch}`, notBeforeMs: epoch * AUTHORITY_KEY_PERIOD_MS,
    expiresAtMs: (epoch + 1) * AUTHORITY_KEY_PERIOD_MS };
}

/** Only the still-active, server-recorded device key may obtain this epoch's key. */
export function validateAuthorityKeyProof(uid: string, input: unknown, certificate: Record<string, unknown> | undefined, now: number) {
  const proof = input as Record<string, unknown> | undefined;
  if (!proof || !certificate || certificate.ownerUid !== uid || certificate.status !== "active"
    || !["admin", "fieldteam"].includes(String(certificate.role))
    || typeof certificate.expiresAtMs !== "number" || certificate.expiresAtMs <= now
    || typeof certificate.issuedAtMs !== "number" || certificate.issuedAtMs > now + 30_000
    || proof.devicePublicKey !== certificate.publicKeyBase64
    || proof.sessionNonce !== AUTHORITY_KEY_PROOF_NONCE || proof.allowExpiredCertificate !== false
    || typeof proof.timestamp !== "number" || !Number.isSafeInteger(proof.timestamp)
    || proof.timestamp < now - 60_000 || proof.timestamp > now + 30_000
    || typeof proof.devicePublicKey !== "string" || proof.devicePublicKey.length > 512
    || typeof proof.certificate !== "string" || proof.certificate.length > 8192
    || typeof proof.signature !== "string" || proof.signature.length > 128) throw new Error("invalid-device-proof");
  const decoded = JSON.parse(Buffer.from(proof.certificate, "base64").toString("utf8")) as Record<string, unknown>;
  if (decoded.uid !== uid || decoded.did !== certificate.deviceId || decoded.sig !== certificate.signatureBase64
    || decoded.iat !== certificate.issuedAtMs || decoded.exp !== certificate.expiresAtMs) throw new Error("certificate-mismatch");
  const key = createPublicKey({ key: Buffer.from(proof.devicePublicKey, "base64"), format: "der", type: "spki" });
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") throw new Error("invalid-device-key");
  const canonical = `${proof.devicePublicKey}|${proof.certificate}|${proof.timestamp}|${AUTHORITY_KEY_PROOF_NONCE}`;
  if (!verify("sha256", Buffer.from(canonical), key, Buffer.from(proof.signature, "base64"))) throw new Error("invalid-device-signature");
}

/** Admin-owned rollout. Missing/malformed config never enables the new authority network. */
export function authorityRolloutAllows(uid: string, input: unknown, feature = "authority-v2"): boolean {
  if (!uid || !input || typeof input !== "object" || Array.isArray(input)) return false;
  const config = input as Record<string, unknown>;
  if (config.enabled !== true) return false;
  if (Array.isArray(config.allowedUids) && config.allowedUids.length <= 1000 && config.allowedUids.includes(uid)) return true;
  const percent = config.percent;
  if (typeof percent !== "number" || !Number.isInteger(percent) || percent < 0 || percent > 100) return false;
  const bucket = createHash("sha256").update(`cc-security-rollout-v2|${feature}|${uid}`).digest().readUInt32BE(0) % 10000;
  return bucket < percent * 100;
}
