import { compatibleCallable } from "./compatibilityRouting";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { FieldPath, FieldValue, Timestamp } from "firebase-admin/firestore";
import { getMessagingFirestore as getFirestore, currentMessagingDatabaseId, MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { messageDigest } from "./highAvailabilityStore";
import { requireUid } from "../certificates/callerRole";
import { isAttachmentChunk } from "./compactDeliveryPolicy";
import { trafficPolicy } from "./trafficRuntimePolicy";

const ID = /^[A-Za-z0-9._:~-]{1,128}$/;
const FIELDS = ["v", "conversationId", "senderUid", "recipientUid", "priority",
  "createdAtMs", "ttlMs", "ctype", "alg", "nonce", "ephemeralPubKey", "ciphertext"];

export function parseCompactRequest(input: unknown): { acknowledgements: string[]; cursor?: string; saving: boolean; installationId: string; receive: boolean; profileRevision?: number; policyVersion?: number } {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new HttpsError("invalid-argument", "Object required.");
  const data = input as Record<string, unknown>;
  if (data.policyVersion !== undefined && data.policyVersion !== 2) throw new HttpsError("invalid-argument", "Unsupported traffic policy.");
  if (typeof data.installationId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(data.installationId)) {
    throw new HttpsError("invalid-argument", "Installation ID required.");
  }
  if (data.receive !== undefined && typeof data.receive !== "boolean") throw new HttpsError("invalid-argument", "Invalid inbox mode.");
  if (data.profileRevision !== undefined && (typeof data.profileRevision !== "number" || !Number.isSafeInteger(data.profileRevision) || data.profileRevision < 1)) {
    throw new HttpsError("invalid-argument", "Invalid traffic revision.");
  }
  const ids = data.acknowledgements ?? [];
  if (data.saving !== undefined && typeof data.saving !== "boolean") throw new HttpsError("invalid-argument", "Invalid traffic mode.");
  if (!Array.isArray(ids) || ids.length > 100 || ids.some(id => typeof id !== "string" || !ID.test(id))) {
    throw new HttpsError("invalid-argument", "Up to 100 message acknowledgements required.");
  }
  let cursor: string | undefined;
  if (data.cursor !== undefined) {
    if (typeof data.cursor !== "string" || !/^[A-Za-z0-9_-]{1,2048}$/.test(data.cursor)) {
      throw new HttpsError("invalid-argument", "Invalid cursor.");
    }
    cursor = Buffer.from(data.cursor, "base64url").toString("utf8");
    if (!cursor || cursor.includes("/") || Buffer.byteLength(cursor, "utf8") > 1500 ||
        Buffer.from(cursor).toString("base64url") !== data.cursor) {
      throw new HttpsError("invalid-argument", "Invalid cursor.");
    }
  }
  return { acknowledgements: [...new Set(ids)] as string[], cursor, saving: data.saving !== false, installationId: data.installationId, receive: data.receive !== false, profileRevision: data.profileRevision as number | undefined, policyVersion: data.policyVersion as number | undefined };
}

/** Whitelist scalar wire fields; never return Firestore timestamps or attachment-sized envelopes. */
export function compactEnvelope(data: Record<string, unknown>, id: string): Record<string, unknown> | null {
  if (!ID.test(id) || isAttachmentChunk(id)) return null;
  // A fresh PQXDH session is larger than an established-session short text; it must still arrive.
  if (typeof data.ciphertext !== "string" || data.ciphertext.length > 8_000) return null;
  const envelope: Record<string, unknown> = { messageId: id };
  for (const field of FIELDS) {
    const value = data[field];
    if (typeof value === "string" || typeof value === "number") envelope[field] = value;
  }
  return Buffer.byteLength(JSON.stringify(envelope), "utf8") <= 10_000 ? envelope : null;
}

/** Push is primary. This bounded, foreground catch-up also batches recipient-owned deletes. */
export const compactInbox = compatibleCallable("compactInbox",{ enforceAppCheck: true }, request => compactInboxResponse(request));
export async function compactInboxResponse(request: CallableRequest<unknown>, extraMetadata: Record<string, unknown> = {}) {
  const uid = requireUid(request);
  const input = parseCompactRequest(request.data);
  const db = getFirestore();
  // A leased, server-only delivery hint. Never publish it as the user's online/last-seen status.
  const profile = db.doc(`messagingTraffic/${uid}/installations/${input.installationId}`);
  // Validate ACK ownership and apply the device hint atomically. A delayed older request cannot
  // reactivate conservation after recovery; retain the last revision as a normal-mode tombstone.
  await db.runTransaction(async transaction => {
    const refs = input.acknowledgements.map(id => db.doc(`messages/${id}`));
    const snapshots = await transaction.getAll(profile, ...refs);
    const [previous, ...messages] = snapshots;
    if (messages.some(snapshot => snapshot.exists && snapshot.get("recipientUid") !== uid)) {
      throw new HttpsError("permission-denied", "Only the recipient may acknowledge messages.");
    }
    for (const message of messages) if (message.exists) transaction.delete(message.ref);
    if (currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE) {
      for (const id of input.acknowledgements) transaction.set(
        db.doc(`messagingBridgeAcks/${uid}/ackedMessages/${messageDigest(id)}`), {
          expireAt: Timestamp.fromMillis(Date.now() + 31 * 86_400_000),
          acknowledgedAt: FieldValue.serverTimestamp(),
        });
    }
    if (input.profileRevision !== undefined) {
      const lastRevision = Number(previous.get("revision") ?? 0);
      if (input.profileRevision > lastRevision) transaction.set(profile, {
        savingUntil: input.saving ? Date.now() + 5 * 60 * 1000 : 0,
        revision: input.profileRevision,
      });
    } else if (input.saving) transaction.set(profile, { savingUntil: Date.now() + 5 * 60 * 1000 });
    else transaction.delete(profile); // Preserve the original optional-field contract.
  });
  const policy = input.policyVersion === 2 ? await trafficPolicy(input.installationId) : undefined;
  const metadata = { ...extraMetadata, ...(policy ? { policy } : {}) };
  if (!input.receive) return { messages: [], cursor: null, ...metadata };
  let query = db.collection("messages").where("recipientUid", "==", uid).orderBy(FieldPath.documentId()).limit(100);
  if (input.cursor) query = query.startAfter(input.cursor);
  const page = await query.get();
  const messages: Record<string, unknown>[] = [];
  // Re-scan urgent mail on every page, including when the ordinary cursor is far ahead.
  // Reserve at least half the item capacity for ordinary traffic to prevent starvation.
  if (policy?.emergencyFirst) {
    const urgent = await db.collection("messages").where("recipientUid", "==", uid)
      .where("priority", "==", "high").limit(100).get();
    for (const doc of urgent.docs) {
      const data = doc.data();
      if (data.callRing === true || (Number(data.ttlMs) > 0 && Number(data.ttlMs) <= 300_000) ||
          (data.expireAt?.toMillis?.() ?? Infinity) <= Date.now()) continue;
      const envelope = compactEnvelope(data, doc.id);
      if (!envelope) continue;
      // Reserve a byte share too: a large prekey SOS cannot consume the ordinary-mail budget.
      if (messages.length >= 8 || Buffer.byteLength(JSON.stringify([...messages, envelope])) > 12_000) break;
      messages.push(envelope);
    }
  }
  const urgentIds = new Set(messages.map(message => message.messageId));
  let cursor: string | undefined;
  let budgetFull = false;
  for (const doc of page.docs) {
    const data = doc.data();
    const expires = data.expireAt?.toMillis?.();
    const envelope = urgentIds.has(doc.id) || (typeof expires === "number" && expires <= Date.now()) ? null : compactEnvelope(data, doc.id);
    const candidate = envelope ? [...messages, envelope] : messages;
    const candidateCursor = Buffer.from(doc.id).toString("base64url");
    // Count the complete JSON response, including framing and a possibly long legacy cursor.
    if ((envelope && messages.length >= 16) ||
        Buffer.byteLength(JSON.stringify({ messages: candidate, cursor: candidateCursor, ...metadata }), "utf8") > 24_000) {
      budgetFull = true;
      break; // The next page must start BEFORE the message we couldn't fit.
    }
    cursor = doc.id;
    if (envelope) messages.push(envelope);
  }
  const next = (budgetFull || page.size === 100) ? cursor ?? input.cursor : undefined;
  return { messages, cursor: next ? Buffer.from(next).toString("base64url") : null, ...metadata };
}
