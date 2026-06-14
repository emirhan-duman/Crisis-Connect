import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import * as crypto from "crypto";
import { requireUid, resolveCallerRoleKey, CERT_ELIGIBLE_ROLE_KEYS } from "./callerRole";

const GROUP_KEY_BYTES = 32;
const CURRENT_KEY_DOC = "authorityMeshKeys/current";
const DEFAULT_KEY_ID = "authority-v1";

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
 * a random 256-bit key on first request and persisted to `authorityMeshKeys/current`, so every
 * authority device that calls this derives the *same* key and can interoperate. Civilians never
 * pass the role check and never receive it. The doc is only ever read/written via the Admin SDK
 * here (clients are denied direct read access in firestore.rules).
 *
 * Rotation (deferred): write a new doc with a bumped keyId and have clients accept both for a grace
 * window. For now keyId is fixed at "authority-v1" to match the mesh payload keyId.
 */
export const issueAuthorityMeshKey = onCall(
  { enforceAppCheck: true },
  async (request) => {
    await assertAuthorityEligible(request);

    const db = getFirestore();
    const ref = db.doc(CURRENT_KEY_DOC);

    return await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const existing = snap.data() as { keyId?: string; keyBase64?: string } | undefined;
      if (
        snap.exists &&
        typeof existing?.keyBase64 === "string" &&
        typeof existing?.keyId === "string"
      ) {
        return { keyId: existing.keyId, keyBase64: existing.keyBase64 };
      }
      const keyBase64 = crypto.randomBytes(GROUP_KEY_BYTES).toString("base64");
      tx.set(ref, {
        keyId: DEFAULT_KEY_ID,
        keyBase64,
        createdAt: FieldValue.serverTimestamp(),
      });
      return { keyId: DEFAULT_KEY_ID, keyBase64 };
    });
  }
);
