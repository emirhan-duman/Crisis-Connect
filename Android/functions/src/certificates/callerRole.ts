import { CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";

/**
 * True when the caller signed in anonymously (no real account). Anonymous callers are allowed to
 * use QR-only messaging (publish their own key, look up a key by explicit UID, send/receive), but
 * NOT phone/username directory discovery — otherwise free, unlimited anonymous UIDs would defeat
 * the per-UID scan throttle and enable phone-number enumeration.
 */
export function isAnonymousCaller(request: CallableRequest<unknown>): boolean {
  const token = request.auth?.token as Record<string, unknown> | undefined;
  // A verified phone number upgrades the account to "real" even when the session
  // began anonymously: the server-side Twilio OTP flow attaches the number via the
  // Admin SDK, which leaves the session's sign_in_provider as "anonymous" but puts
  // the phone into the ID token (phone_number claim + firebase.identities.phone).
  if (typeof token?.phone_number === "string" && token.phone_number.length > 0) {
    return false;
  }
  const firebase = token?.firebase as
    | { sign_in_provider?: unknown; identities?: Record<string, unknown> }
    | undefined;
  const phoneIdentities = firebase?.identities?.["phone"];
  if (Array.isArray(phoneIdentities) && phoneIdentities.length > 0) {
    return false;
  }
  return firebase?.sign_in_provider === "anonymous";
}

/**
 * Rejects anonymous callers from a directory-discovery path. Discovery by phone/username is a
 * "find friends" feature reserved for real (e.g. phone-verified) accounts; QR key exchange stays
 * open to everyone and is the private, enumeration-proof path.
 */
export function requireNonAnonymous(request: CallableRequest<unknown>): void {
  if (isAnonymousCaller(request)) {
    throw new HttpsError(
      "permission-denied",
      "Directory discovery requires a full account. Add contacts by QR instead."
    );
  }
}

/**
 * Returns the authenticated caller's UID or throws an HttpsError.
 */
export function requireUid(request: CallableRequest<unknown>): string {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }
  const uid = auth.uid?.trim();
  if (!uid) {
    throw new HttpsError("failed-precondition", "Authenticated user ID missing.");
  }
  return uid;
}

/**
 * Normalises a role value: lower-cased with hyphens/underscores stripped so that
 * "field-team", "field_team" and "fieldteam" all collapse to "fieldteam". Mirrors
 * the web client's `normalizeRoleValue` in lib/dashboard-access.ts.
 */
export function normalizeRoleKey(role: unknown): string {
  if (typeof role !== "string") {
    return "";
  }
  return role.trim().toLowerCase().replace(/[-_]/g, "");
}

/**
 * Resolves the caller's normalised role key.
 *
 * Roles in Crisis Connect are authoritatively stored in Firestore `users/{uid}.role`
 * — the web `useAuth()` hook and the Firestore security rules both read from there.
 * Custom Firebase Auth claims (`auth.token.role`) are NOT consistently provisioned in
 * this project, so we prefer a claim when one is present (it is the stronger signal)
 * but otherwise fall back to the Firestore user document. This keeps backend
 * authorisation consistent with what the rest of the app sees.
 */
export async function resolveCallerRoleKey(
  request: CallableRequest<unknown>,
  uid: string
): Promise<string> {
  const tokenRole = (request.auth?.token as Record<string, unknown> | undefined)?.role;
  const fromToken = normalizeRoleKey(tokenRole);
  if (fromToken) {
    return fromToken;
  }
  try {
    const snap = await getFirestore().doc(`users/${uid}`).get();
    const data = snap.data() as { role?: unknown } | undefined;
    return normalizeRoleKey(data?.role);
  } catch (error) {
    console.warn("Failed to resolve caller role from Firestore", error);
    return "";
  }
}

/** Roles allowed to manage (list-all / revoke-others) certificates. */
export const MANAGER_ROLE_KEYS = new Set(["admin", "authority"]);

/** Roles eligible to hold a rescue role certificate (request challenge / issuance). */
export const CERT_ELIGIBLE_ROLE_KEYS = new Set(["admin", "fieldteam"]);
