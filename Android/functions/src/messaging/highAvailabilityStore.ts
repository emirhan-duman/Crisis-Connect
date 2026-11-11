import { createHash } from "node:crypto";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";

export const HA_PROTOCOL = 4;
const UID = /^[A-Za-z0-9]{1,128}$/;
const MESSAGE_ID = /^[A-Za-z0-9._:~-]{1,128}$/;
const DAY = 86_400_000;
export const messageDigest = (id: string) => createHash("sha256").update(id).digest("hex");
export const haFirestore = () => getFirestore(MULTIREGION_MESSAGING_DATABASE);

/** Optional profile fields must not block encrypted message delivery during a legacy outage. */
export async function readLegacyProfile(uid: string): Promise<FirebaseFirestore.DocumentData | undefined> {
  if (process.env.PROFILE_HA_ENABLED === "true") {
    return (await haFirestore().doc(`users/${uid}`).get()).data();
  }
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      getFirestore().doc(`users/${uid}`).get().then(s => s.data()).catch(() => undefined),
      new Promise<undefined>(resolve => { timer = setTimeout(() => resolve(undefined), 1500); }),
    ]);
  } finally { if (timer) clearTimeout(timer); }
}

export async function usesHighAvailability(uid: string): Promise<boolean> {
  if (!UID.test(uid)) throw new HttpsError("invalid-argument", "Invalid messaging account.");
  const record = await haFirestore().doc(`messagingAccounts/${uid}`).get();
  if (record.get("state") === "deleted") throw new HttpsError("not-found", "Messaging account is unavailable.");
  return record.get("protocol") === HA_PROTOCOL && record.get("state") === "active";
}

export async function isHaAccountDeleted(uid: string): Promise<boolean> {
  if (typeof uid !== "string" || !UID.test(uid)) return false;
  return (await haFirestore().doc(`messagingAccounts/${uid}`).get()).get("state") === "deleted";
}

/** Data erasure also covers parentless token, ACK, key-generation and traffic subcollections. */
export async function deleteHighAvailabilityAccount(uid: string): Promise<void> {
  if (!UID.test(uid)) throw new HttpsError("invalid-argument", "Invalid messaging account.");
  const db = haFirestore();
  await db.doc(`messagingAccounts/${uid}`).set({ protocol: HA_PROTOCOL, state: "deleted",
    expireAt: Timestamp.fromMillis(Date.now() + 31 * DAY) });
  const key = await db.doc(`messagingKeys/${uid}`).get();
  for (const hash of key.get("directoryHashes") ?? []) {
    if (typeof hash !== "string" || hash.includes("/")) continue;
    const entry = db.doc(`messagingDirectory/${hash}`);
    await db.runTransaction(async tx => {
      if ((await tx.get(entry)).get("uid") === uid) tx.delete(entry);
    });
  }
  for (const root of ["signalPreKeys", "messagingKeys", "messagingTokens", "messagingTraffic", "messagingBridgeAcks",
    "messagingRelayBudgets", "signalBundleThrottle", "messagingScanThrottle", "messagingMigrationJobs", "users",
    "profileMirrorState", "authorityHaDevices"]) {
    await db.recursiveDelete(db.doc(`${root}/${uid}`));
  }
  for (const collection of ["messages", "messagingRegionalReceipts", "messagingBridgeDeletes"]) {
    for (const field of ["senderUid", "recipientUid"]) {
      for (;;) {
        const rows = await db.collection(collection).where(field, "==", uid).limit(200).get();
        if (rows.empty) break;
        const batch = db.batch(); rows.docs.forEach(row => batch.delete(row.ref)); await batch.commit();
      }
    }
  }
  for (;;) {
    const rows = await db.collection("messagingTokenBridge").where("uid", "==", uid).limit(200).get();
    if (rows.empty) break;
    const batch = db.batch(); rows.docs.forEach(row => batch.delete(row.ref)); await batch.commit();
  }
}

/** Positive enrollment is permanent. Never switch an enrolled account back to a stale key pool. */
export async function activateHighAvailability(uid: string): Promise<void> {
  if (!UID.test(uid)) throw new HttpsError("invalid-argument", "Invalid messaging account.");
  const db = haFirestore();
  await db.runTransaction(async tx => {
    const account = db.doc(`messagingAccounts/${uid}`);
    const [identity, previous] = await tx.getAll(db.doc(`messagingKeys/${uid}`), account);
    if (!identity.exists || typeof identity.get("publicKey") !== "string" || !identity.get("publicKey").length) {
      throw new HttpsError("failed-precondition", "Publish the identity before enrollment.");
    }
    tx.set(account, {
      protocol: HA_PROTOCOL, state: "active", updatedAt: FieldValue.serverTimestamp(),
    });
    if (previous.get("state") !== "active") tx.set(db.doc(`messagingMigrationJobs/${uid}`), {
      state: "pending", updatedAtMs: Date.now(),
    });
  });
}

/**
 * Import only an opaque message from the trusted legacy bridge, never consumable prekeys.
 * A recipient-scoped ACK can precede the asynchronous import. It must still suppress it.
 */
export async function copyLegacyMessageToHa(id: string, data: FirebaseFirestore.DocumentData): Promise<boolean> {
  if (!MESSAGE_ID.test(id) || typeof data.senderUid !== "string" || !UID.test(data.senderUid) ||
      typeof data.recipientUid !== "string" || !UID.test(data.recipientUid) ||
      typeof data.conversationId !== "string" || !MESSAGE_ID.test(data.conversationId)) {
    throw new HttpsError("invalid-argument", "Invalid bridge envelope.");
  }
  const created = Number(data.createdAtMs), ttl = Number(data.ttlMs);
  if (!Number.isFinite(created) || created > Date.now() + 300_000 || !Number.isFinite(ttl) ||
      ttl <= 0 || ttl > 30 * DAY || created + ttl <= Date.now()) return false;
  const db = haFirestore(), hash = messageDigest(id);
  const message = db.doc(`messages/${id}`);
  const receipt = db.doc(`messagingRegionalReceipts/${hash}`);
  const ack = db.doc(`messagingBridgeAcks/${data.recipientUid}/ackedMessages/${hash}`);
  return db.runTransaction(async tx => {
    const [previous, existing, received] = await tx.getAll(receipt, message, ack);
    const binding = previous.exists ? previous : existing;
    if (binding.exists) {
      if (binding.get("senderUid") !== data.senderUid || binding.get("recipientUid") !== data.recipientUid ||
          binding.get("conversationId") !== data.conversationId) {
        throw new HttpsError("already-exists", "Message ID belongs to another route.");
      }
      if (!previous.exists) tx.create(receipt, { senderUid: data.senderUid, recipientUid: data.recipientUid,
        conversationId: data.conversationId, expireAt: Timestamp.fromMillis(created + ttl + DAY) });
      return false;
    }
    tx.create(receipt, { senderUid: data.senderUid, recipientUid: data.recipientUid,
      conversationId: data.conversationId, expireAt: Timestamp.fromMillis(created + ttl + DAY) });
    if (received.exists) return false;
    tx.create(message, { ...data, messageId: id, expireAt: Timestamp.fromMillis(created + ttl),
      bridgeOrigin: "legacy", bridgeState: "pending" });
    return true;
  });
}

/** ACK tombstones are scoped by recipient, so an absent ID cannot reserve another user's ID. */
export async function acknowledgeHaMessage(uid: string, id: string): Promise<void> {
  if (!UID.test(uid) || !MESSAGE_ID.test(id)) throw new HttpsError("invalid-argument", "Invalid acknowledgement.");
  const db = haFirestore(), message = db.doc(`messages/${id}`);
  await db.runTransaction(async tx => {
    const snapshot = await tx.get(message);
    if (snapshot.exists && snapshot.get("recipientUid") !== uid) {
      throw new HttpsError("permission-denied", "Not the recipient of this message.");
    }
    tx.set(db.doc(`messagingBridgeAcks/${uid}/ackedMessages/${messageDigest(id)}`), {
      expireAt: Timestamp.fromMillis(Date.now() + 31 * DAY), acknowledgedAt: FieldValue.serverTimestamp(),
    });
    if (snapshot.exists) tx.delete(message);
  });
}
