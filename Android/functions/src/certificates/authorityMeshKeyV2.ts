import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import * as crypto from "crypto";
import { requireUid, resolveCallerRoleKey, CERT_ELIGIBLE_ROLE_KEYS } from "./callerRole";
import { authorityKeyEpoch, validateAuthorityKeyProof, authorityRolloutAllows } from "./authorityMeshKeyPolicy";

const GROUP_KEY_BYTES = 32;

async function assertAuthorityEligible(request: CallableRequest<unknown>): Promise<string> {
  const uid = requireUid(request);
  const role = await resolveCallerRoleKey(request, uid);
  if (!CERT_ELIGIBLE_ROLE_KEYS.has(role)) {
    throw new HttpsError(
      "permission-denied",
      "Only admin or fieldteam roles may fetch the authority mesh key."
    );
  }
  return uid;
}

/**
 * Returns the shared authority-mesh group key to a verified admin/fieldteam device.
 *
 * The key lets only authorities decrypt/produce authority-mesh traffic. It is generated lazily as
 * a random 256-bit key on first request and persisted to that epoch's authorityMeshKeys document, so every
 * authority device that calls this derives the *same* key and can interoperate. Civilians never
 * pass the role check and never receive it. The doc is only ever read/written via the Admin SDK
 * here (clients are denied direct read access in firestore.rules).
 *
 * Each 72-hour epoch has an independent random key. No future key is issued in advance, and no
 * legacy key is returned. Revoked devices cannot fetch a subsequent epoch even with a live token.
 */
export const issueAuthorityMeshKeyV2 = onCall(
  { enforceAppCheck: true },
  async (request) => {
    const uid = await assertAuthorityEligible(request);

    const db = getFirestore();
    const now = Date.now();
    const epoch = authorityKeyEpoch(now);
    const ref = db.doc(`authorityMeshKeys/${epoch.keyId}`);

    return await db.runTransaction(async (tx) => {
      const device = await tx.get(db.doc(`certificates/cert_${uid}`));
      const certificate = device.data() as Record<string, unknown> | undefined;
      try { validateAuthorityKeyProof(uid, request.data, certificate, now); }
      catch { throw new HttpsError("permission-denied", "An active device certificate and a fresh device signature are required."); }
      const rollout = await tx.get(db.doc("securityProtocolRollouts/authority-v2"));
      if (!authorityRolloutAllows(uid, rollout.data())) {
        throw new HttpsError("failed-precondition", "The secure authority protocol is not enabled for this account yet.");
      }
      const snap = await tx.get(ref);
      const existing = snap.data() as { keyId?: string; keyBase64?: string } | undefined;
      const lease = { ...epoch, expiresAtMs: Math.min(epoch.expiresAtMs, certificate!.expiresAtMs as number) };
      if (
        snap.exists &&
        typeof existing?.keyBase64 === "string" &&
        Buffer.from(existing.keyBase64, "base64").length === GROUP_KEY_BYTES && existing.keyId === epoch.keyId
      ) {
        return { ...lease, keyBase64: existing.keyBase64 };
      }
      const keyBase64 = crypto.randomBytes(GROUP_KEY_BYTES).toString("base64");
      tx.set(ref, {
        ...epoch,
        keyBase64,
        createdAt: FieldValue.serverTimestamp(),
      });
      return { ...lease, keyBase64 };
    });
  }
);
