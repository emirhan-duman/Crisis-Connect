import { randomUUID } from "node:crypto";
import { getFirestore, Timestamp, FieldPath } from "firebase-admin/firestore";
import { onDocumentCreated, onDocumentDeleted } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { apnsAuthKeyP8, apnsKeyId, apnsTeamId } from "./apnsVoip";
import { onMessageCreated } from "./onMessageCreated";
import { regionalRelay } from "./regionalGateway";
import { LEGACY_MESSAGING_DATABASE, MULTIREGION_MESSAGING_DATABASE, withMessagingDatabase } from "./databaseTarget";
import { acknowledgeHaMessage, copyLegacyMessageToHa, haFirestore, isHaAccountDeleted, messageDigest, usesHighAvailability } from "./highAvailabilityStore";
import { mirrorHaPushToken } from "./highAvailabilityTokens";

const enabled = () => process.env.HA_COMPATIBILITY_ENABLED === "true";

/** Multiple compute regions compete for one short, renewable server lease, not duplicate pushes. */
export async function deliverHaMessage(id: string): Promise<void> {
  const db = haFirestore(), ref = db.doc(`messages/${id}`), owner = randomUUID();
  const initial = await ref.get();
  if (!initial.exists) return;
  const uid = initial.get("recipientUid");
  if (typeof uid !== "string") return;
  if (await isHaAccountDeleted(initial.get("senderUid")) || await isHaAccountDeleted(uid)) { await ref.delete(); return; }
  if (Number(initial.get("createdAtMs")) + Number(initial.get("ttlMs")) <= Date.now()) { await ref.delete(); return; }
  const active = await usesHighAvailability(uid);
  const claimed = await db.runTransaction(async tx => {
    const current = await tx.get(ref);
    if (!current.exists || current.get("bridgeState") === "complete") return false;
    if (Number(current.get("deliveryLeaseUntil") ?? 0) > Date.now()) throw new Error("Delivery lease is active");
    tx.update(ref, { deliveryLeaseOwner: owner, deliveryLeaseUntil: Date.now() + 30_000,
      ...(!active ? { legacyForwarded: true } : {}) });
    return true;
  });
  if (!claimed) return;
  const renew = setInterval(() => {
    void db.runTransaction(async tx => {
      const current = await tx.get(ref);
      if (current.get("deliveryLeaseOwner") === owner && current.get("bridgeState") !== "complete") {
        tx.update(ref, { deliveryLeaseUntil: Date.now() + 30_000 });
      }
    }).catch(() => { /* Ownership is checked again before the next external effect. */ });
  }, 10_000);
  renew.unref();
  try {
    const current = await ref.get();
    if (!current.exists || current.get("deliveryLeaseOwner") !== owner) return;
    if (!active) await withMessagingDatabase(LEGACY_MESSAGING_DATABASE,
      () => regionalRelay({ ...current.data(), messageId: id }, current.get("senderUid")));
    if (active || await usesHighAvailability(uid)) {
      const latest = await ref.get();
      if (latest.exists && latest.get("deliveryLeaseOwner") === owner) {
        await withMessagingDatabase(MULTIREGION_MESSAGING_DATABASE,
          () => onMessageCreated.run({ data: latest, params: { messageId: id } } as any));
      }
    }
    await db.runTransaction(async tx => {
      const latest = await tx.get(ref);
      if (latest.get("deliveryLeaseOwner") === owner) tx.update(ref, { bridgeState: "complete", deliveryLeaseUntil: 0 });
    });
  } catch (error) {
    await db.runTransaction(async tx => {
      const latest = await tx.get(ref);
      if (latest.get("deliveryLeaseOwner") === owner) tx.update(ref, { deliveryLeaseUntil: 0 });
    }).catch(() => {});
    throw error;
  } finally { clearInterval(renew); }
}

export async function removeLegacyCopy(id: string, data: FirebaseFirestore.DocumentData): Promise<void> {
  const db = getFirestore(), message = db.doc(`messages/${id}`);
  const receipt = db.doc(`messagingRegionalReceipts/${messageDigest(id)}`);
  await db.runTransaction(async tx => {
    const [previous, existing] = await tx.getAll(receipt, message);
    for (const snapshot of [previous, existing]) {
      if (snapshot.exists && (snapshot.get("senderUid") !== data.senderUid || snapshot.get("recipientUid") !== data.recipientUid ||
          snapshot.get("conversationId") !== data.conversationId)) throw new Error("Legacy message binding conflict");
    }
    if (!previous.exists) tx.create(receipt, { senderUid: data.senderUid, recipientUid: data.recipientUid,
      conversationId: data.conversationId, expireAt: Timestamp.fromMillis(Number(data.createdAtMs) + Number(data.ttlMs) + 86_400_000) });
    if (existing.exists) tx.delete(message);
  });
}

export async function backfillLegacyInbox(uid: string): Promise<void> {
  if (await isHaAccountDeleted(uid) || !await usesHighAvailability(uid)) return;
  const job = haFirestore().doc(`messagingMigrationJobs/${uid}`);
  const progress = await job.get();
  let query = getFirestore().collection("messages").where("recipientUid", "==", uid).orderBy(FieldPath.documentId()).limit(100);
  const cursor = progress.get("cursor");
  if (typeof cursor === "string") query = query.startAfter(cursor);
  const page = await query.get();
  for (const message of page.docs) {
    await copyLegacyMessageToHa(message.id, message.data());
  }
  // Keep the legacy copy until a recipient ACK. Deletion is an ACK signal to older clients.
  await job.set({
    state: page.size === 100 ? "pending" : "complete", updatedAtMs: Date.now(),
    ...(page.size ? { cursor: page.docs[page.size - 1].id } : {}),
    ...(page.size < 100 ? { expireAt: Timestamp.fromMillis(Date.now() + 7 * 86_400_000) } : {}),
  }, { merge: true });
}

const haMessageCreated = (region: string) => onDocumentCreated({ document: "messages/{messageId}", database: MULTIREGION_MESSAGING_DATABASE,
  region, retry: true, timeoutSeconds: 90, maxInstances: 10,
  secrets: [apnsAuthKeyP8, apnsKeyId, apnsTeamId] }, async event => {
  if (enabled() && event.data) await deliverHaMessage(event.params.messageId);
});
export const onHaMessageCreatedEurope = haMessageCreated("europe-west1");
export const onHaMessageCreatedUS = haMessageCreated("us-central1");

export const onLegacyMessageForHa = onDocumentCreated({ document: "messages/{messageId}",
  region: "europe-west4", retry: true, timeoutSeconds: 90, maxInstances: 10 }, async event => {
  if (!enabled() || !event.data) return;
  const uid = event.data.get("recipientUid");
  if (await isHaAccountDeleted(event.data.get("senderUid")) || await isHaAccountDeleted(uid)) return;
  if (typeof uid === "string" && await usesHighAvailability(uid)) await copyLegacyMessageToHa(event.params.messageId, event.data.data());
});

export const onLegacyMessageDeletedForHa = onDocumentDeleted({ document: "messages/{messageId}",
  region: "europe-west4", retry: true, timeoutSeconds: 90, maxInstances: 10 }, async event => {
  if (!enabled() || !event.data) return;
  const uid = event.data.get("recipientUid");
  if (typeof uid !== "string") return;
  if (await isHaAccountDeleted(uid) || await isHaAccountDeleted(event.data.get("senderUid"))) return;
  const receipt = await haFirestore().doc(`messagingRegionalReceipts/${messageDigest(event.params.messageId)}`).get();
  if (receipt.exists || await usesHighAvailability(uid)) await acknowledgeHaMessage(uid, event.params.messageId);
});

const haMessageDeleted = (region: string) => onDocumentDeleted({ document: "messages/{messageId}", database: MULTIREGION_MESSAGING_DATABASE,
  region, retry: true, timeoutSeconds: 90, maxInstances: 10 }, async event => {
  if (!enabled() || !event.data) return;
  const data = event.data.data();
  if (await isHaAccountDeleted(data.senderUid) || await isHaAccountDeleted(data.recipientUid)) return;
  if (data.bridgeOrigin !== "legacy" && data.legacyForwarded !== true) return;
  const job = haFirestore().doc(`messagingBridgeDeletes/${messageDigest(event.params.messageId)}`);
  await job.set({ state: "pending", messageId: event.params.messageId, senderUid: data.senderUid,
    recipientUid: data.recipientUid, conversationId: data.conversationId, createdAtMs: data.createdAtMs, ttlMs: data.ttlMs });
  await removeLegacyCopy(event.params.messageId, data);
  await job.delete();
});
export const onHaMessageDeletedEurope = haMessageDeleted("europe-west1");
export const onHaMessageDeletedUS = haMessageDeleted("us-central1");

export const onHaInboxMigration = onDocumentCreated({ document: "messagingMigrationJobs/{uid}", database: MULTIREGION_MESSAGING_DATABASE,
  region: "europe-west1", retry: true, timeoutSeconds: 120, maxInstances: 10 }, async event => {
  if (enabled()) await backfillLegacyInbox(event.params.uid);
});

/** Recovers expired leases and bridge work after Eventarc retry retention or a long outage. */
export const repairHaDelivery = onSchedule({ schedule: "every 5 minutes", region: "us-central1", timeoutSeconds: 300,
  maxInstances: 1, secrets: [apnsAuthKeyP8, apnsKeyId, apnsTeamId] }, async () => {
  if (!enabled()) return;
  const db = haFirestore();
  const messages = await db.collection("messages").where("bridgeState", "==", "pending").limit(20).get();
  for (const message of messages.docs) await deliverHaMessage(message.id).catch(() => {});
  const deletions = await db.collection("messagingBridgeDeletes").where("state", "==", "pending").limit(20).get();
  for (const job of deletions.docs) {
    try { await removeLegacyCopy(job.get("messageId"), job.data()); await job.ref.delete(); } catch {}
  }
  const migrations = await db.collection("messagingMigrationJobs").where("state", "==", "pending").limit(10).get();
  for (const job of migrations.docs) await backfillLegacyInbox(job.id).catch(() => {});
  const tokens = await db.collection("messagingTokenBridge").where("pending", "==", true).limit(20).get();
  for (const token of tokens.docs) await mirrorHaPushToken(token.id).catch(() => {});
});
