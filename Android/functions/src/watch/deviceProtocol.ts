import { createHash, createPublicKey, verify } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";

export const WATCH_SCOPES = ["shortMessage", "sos"] as const;
export const WATCH_LEASE_MS = 7 * 86_400_000;
export const WATCH_DOMAIN = "crisis-connect/watch-request/v1\n";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function watchID(raw: unknown): string {
  if (typeof raw !== "string" || !UUID.test(raw)) throw new HttpsError("invalid-argument", "Invalid watch UUID.");
  return raw;
}
export function object(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new HttpsError("invalid-argument", "Expected an object.");
  return raw as Record<string, unknown>;
}
export function base64(raw: unknown, max: number): Buffer {
  if (typeof raw !== "string" || !raw.length || raw.length > max || !/^[A-Za-z0-9+/]+={0,2}$/.test(raw))
    throw new HttpsError("invalid-argument", "Invalid base64.");
  const bytes = Buffer.from(raw, "base64");
  if (bytes.toString("base64") !== raw) throw new HttpsError("invalid-argument", "Non-canonical base64.");
  return bytes;
}
export function watchPublicKey(raw: unknown): string {
  const bytes = base64(raw, 256);
  try {
    const key = createPublicKey({ key: bytes, format: "der", type: "spki" });
    if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") throw Error();
    if (!Buffer.from(key.export({ format: "der", type: "spki" })).equals(bytes)) throw Error();
    return bytes.toString("base64");
  } catch { throw new HttpsError("invalid-argument", "A P-256 SPKI public key is required."); }
}
export function requestDigest(payload: string): string {
  return createHash("sha256").update(payload).digest("hex");
}
export interface WatchRequest {
  version: 1; deviceID: string; id: string; issuedAt: number; expiresAt: number;
  action: "status" | "shortMessage" | "ackMessage" | "startSOS" | "stopSOS";
  body: Record<string, unknown>;
}
export function parseWatchRequest(payload: unknown, now: number): { request: WatchRequest; encoded: string } {
  const bytes = base64(payload, 32_768);
  let r: Record<string, unknown>;
  try { r = object(JSON.parse(bytes.toString("utf8"))); }
  catch { throw new HttpsError("invalid-argument", "Invalid request JSON."); }
  if (r.version !== 1 || !["status", "shortMessage", "ackMessage", "startSOS", "stopSOS"].includes(String(r.action)))
    throw new HttpsError("invalid-argument", "Unsupported watch request.");
  watchID(r.deviceID); watchID(r.id);
  if (!Number.isSafeInteger(r.issuedAt) || !Number.isSafeInteger(r.expiresAt) ||
      (r.issuedAt as number) <= 0 || (r.issuedAt as number) > now + 5000 || (r.expiresAt as number) <= now ||
      (r.expiresAt as number) <= (r.issuedAt as number) || (r.expiresAt as number) - (r.issuedAt as number) > 30_000)
    throw new HttpsError("deadline-exceeded", "Watch request expired or clock is invalid.");
  object(r.body);
  return { request: r as unknown as WatchRequest, encoded: payload as string };
}
export function verifyWatchSignature(publicKey: string, payload: string, signature: unknown): void {
  const sig = base64(signature, 128);
  const key = createPublicKey({ key: Buffer.from(watchPublicKey(publicKey), "base64"), type: "spki", format: "der" });
  if (!verify("sha256", Buffer.from(WATCH_DOMAIN + payload), key, sig))
    throw new HttpsError("permission-denied", "Invalid watch proof of possession.");
}
export function enrollmentPayload(ownerUid: string, deviceID: string, publicKey: string): string {
  // Fixed field order shared with both clients; no credentials are included.
  return Buffer.from(JSON.stringify({ version: 1, ownerUid, deviceID, publicKey, scopes: [...WATCH_SCOPES] })).toString("base64");
}
export interface WatchRegistration {
  ownerUid: string; deviceID: string; publicKey: string; scopes: string[];
  createdAt: number; expiresAt: number; revokedAt: number | null;
}
export function authorizeWatch(registration: WatchRegistration | undefined, request: WatchRequest, now: number): WatchRegistration {
  if (!registration || registration.deviceID !== request.deviceID || registration.revokedAt !== null || registration.expiresAt <= now)
    throw new HttpsError("permission-denied", "Watch registration is unavailable.");
  const scope = ["shortMessage", "ackMessage"].includes(request.action) ? "shortMessage" : request.action === "status" ? null : "sos";
  if (scope && !registration.scopes.includes(scope)) throw new HttpsError("permission-denied", "Watch scope is not authorized.");
  return registration;
}
