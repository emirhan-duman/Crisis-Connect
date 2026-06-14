import { CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";

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
