import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import * as crypto from "crypto";
import { requireUid, resolveCallerRoleKey } from "../certificates/callerRole";

/**
 * Per-agency shared symmetric key for the dashboard's agency-internal E2E messaging.
 *
 * Unlike the global authority-mesh key, this is scoped to ONE agency (agencySlug): only members of
 * that agency ever receive it, so agency messages stored (encrypted) in Firestore can be read by the
 * agency's own authorities + field teams and by no one else — the server never sees plaintext.
 *
 * Membership: the caller must be an admin/authority/fieldteam whose own agencySlug matches the
 * requested one, or a platform admin. The key is generated lazily (random 256-bit) and persisted to
 * `agencyMessagingKeys/{agencySlug}` (Admin-SDK only; clients are denied direct read in rules), so
 * every member of the same agency derives the same key.
 */
const GROUP_KEY_BYTES = 32;
const DEFAULT_KEY_ID = "agency-v1";
const ELIGIBLE_ROLES = new Set(["admin", "authority", "fieldteam"]);

function normalizeSlug(value: unknown): string | null {
  if (typeof value !== "string") return null;
  // Lower-case so a caller's stored agencySlug ("AFAD") matches the panel id ("afad") the client
  // sends, and so all members of an agency derive the same key doc regardless of casing.
  const trimmed = value.trim().toLowerCase().replace(/\//g, "-");
  return trimmed.length > 0 ? trimmed : null;
}

function readFlag(value: unknown): boolean {
  return value === true || value === 1
    || (typeof value === "string" && ["true", "1", "yes", "on", "enabled"].includes(value.trim().toLowerCase()));
}

export const issueAgencyMessagingKey = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    try {
    const uid = requireUid(request);
    const role = await resolveCallerRoleKey(request, uid);
    if (!ELIGIBLE_ROLES.has(role)) {
      throw new HttpsError("permission-denied", "Only agency members may fetch the agency messaging key.");
    }

    const data = (request.data ?? {}) as Record<string, unknown>;
    const requestedSlug = normalizeSlug(data.agencySlug);
    if (!requestedSlug) {
      throw new HttpsError("invalid-argument", "agencySlug is required.");
    }

    const db = getFirestore();
    const userDoc = (await db.doc(`users/${uid}`).get()).data() ?? {};
    const callerSlug = normalizeSlug(userDoc.agencySlug) ?? normalizeSlug(userDoc.agencyKey);
    const isPlatformAdmin = readFlag(userDoc.platformAdmin)
      || readFlag(userDoc.globalDashboardAccess)
      || readFlag(userDoc.superAdmin);

    if (!isPlatformAdmin && (!callerSlug || callerSlug !== requestedSlug)) {
      throw new HttpsError("permission-denied", "Not a member of that agency.");
    }

    const ref = db.doc(`agencyMessagingKeys/${requestedSlug}`);
    return await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const existing = snap.data() as { keyId?: string; keyBase64?: string } | undefined;
      if (snap.exists && typeof existing?.keyBase64 === "string" && typeof existing?.keyId === "string") {
        return { keyId: existing.keyId, keyBase64: existing.keyBase64, agencySlug: requestedSlug };
      }
      const keyBase64 = crypto.randomBytes(GROUP_KEY_BYTES).toString("base64");
      tx.set(ref, {
        keyId: DEFAULT_KEY_ID,
        keyBase64,
        agencySlug: requestedSlug,
        createdAt: FieldValue.serverTimestamp(),
      });
      return { keyId: DEFAULT_KEY_ID, keyBase64, agencySlug: requestedSlug };
    });
    } catch (err) {
      if (err instanceof HttpsError) throw err;
      const e = err as { name?: string; message?: string };
      throw new HttpsError("internal", `AGENCYKEY_FAIL ${e.name ?? ""}: ${e.message ?? String(err)}`);
    }
  }
);
