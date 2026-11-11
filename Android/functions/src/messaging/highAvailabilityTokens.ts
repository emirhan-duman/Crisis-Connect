import { createHash } from "node:crypto";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { haFirestore, isHaAccountDeleted } from "./highAvailabilityStore";

const tokenHash = (token: string) => createHash("sha256").update(token).digest("base64url");
const jobHash = (uid: string, token: string) => createHash("sha256").update(JSON.stringify([uid, token])).digest("hex");

/** Canonical registration and durable compatibility mirror intent are committed together. */
export async function setHaPushToken(uid: string, token: string, value: Record<string, unknown> | null) {
  const db = haFirestore(), id = jobHash(uid, token), registry = db.doc(`messagingTokens/${uid}/tokens/${tokenHash(token)}`);
  const intent = db.doc(`messagingTokenBridge/${id}`);
  await db.runTransaction(async tx => {
    const previous = await tx.get(intent);
    const version = Math.max(Date.now(), Number(previous.get("version") ?? 0) + 1);
    if (value) tx.set(registry, value); else tx.delete(registry);
    tx.set(intent, { uid, tokenId: tokenHash(token), version, pending: true, value });
  });
}

/** Older group/call services retain their default token registry, including ordered deletes. */
export async function mirrorHaPushToken(id: string, erased?: FirebaseFirestore.DocumentData) {
  const source = haFirestore().doc(`messagingTokenBridge/${id}`), snapshot = await source.get();
  if (snapshot.exists && snapshot.get("pending") !== true) return;
  const intent = snapshot.data() ?? erased;
  if (!intent || typeof intent.uid !== "string" || typeof intent.tokenId !== "string") return;
  const deleted = await isHaAccountDeleted(intent.uid);
  const version = deleted ? Math.max(Date.now(), Number(intent.version) + 1) : Number(intent.version);
  if (!snapshot.exists && !deleted) return;
  const value = deleted ? null : intent.value;
  const legacy = getFirestore(), target = legacy.doc(`messagingTokens/${intent.uid}/tokens/${intent.tokenId}`);
  const fence = legacy.doc(`messagingTokenMirror/${id}`);
  await legacy.runTransaction(async tx => {
    const previous = await tx.get(fence);
    if (previous.get("deleted") === true || Number(previous.get("version") ?? 0) >= version) return;
    if (value) tx.set(target, value); else tx.delete(target);
    tx.set(fence, { uid: intent.uid, version, deleted,
      ...(!value ? { expireAt: Timestamp.fromMillis(Date.now() + 31 * 86_400_000) } : {}) });
  });
  if (snapshot.exists) await haFirestore().runTransaction(async tx => {
    const current = await tx.get(source);
    if (current.get("version") === intent.version) tx.update(source, { pending: false,
      ...(!value ? { value: null, expireAt: Timestamp.fromMillis(Date.now() + 31 * 86_400_000) } : {}) });
  });
}

export const onHaPushTokenChanged = onDocumentWritten({ document: "messagingTokenBridge/{id}",
  database: MULTIREGION_MESSAGING_DATABASE, region: "europe-west1", retry: true, timeoutSeconds: 90, maxInstances: 10 },
async event => {
  if (process.env.HA_COMPATIBILITY_ENABLED === "true") await mirrorHaPushToken(event.params.id, event.data?.before.data());
});
