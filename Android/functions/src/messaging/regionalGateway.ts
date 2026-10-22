import { createHash } from "node:crypto";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { getMessagingFirestore as getFirestore, currentMessagingDatabaseId, MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { messageDigest } from "./highAvailabilityStore";
import { runCompatibleOperation } from "./compatibilityRouting";
import { requireUid } from "../certificates/callerRole";
import { parseRelayEnvelope } from "./envelope";
import { reserveRelayBudget } from "./relayBudget";
import { compactInboxResponse } from "./compactInbox";
import { acknowledgeMessage } from "./messageAck";
import { publishIdentityKey, lookupIdentityKey } from "./identityKeys";
import { findContactsOnCrisisConnect } from "./contactDiscovery";
import { registerPushToken, unregisterPushToken } from "./pushTokens";
import { publishSignalPreKeys, checkSignalPreKeys, fetchSignalPreKeyBundle } from "./signalPreKeys";

// Separate names preserve every released client's us-central1 callable and its wire contract.
export function gateway(region: string) {
 return onCall({ region, enforceAppCheck: true, invoker: "public", timeoutSeconds: 30, maxInstances: 100 }, async request => {
  const uid = requireUid(request);
  const data = request.data;
  if (!data || typeof data !== "object" || !data.payload || typeof data.payload !== "object" || Array.isArray(data.payload)) {
   throw new HttpsError("invalid-argument", "Operation and object payload required.");
  }
  if (data.payload.requestUid !== undefined && data.payload.requestUid !== uid) {
   throw new HttpsError("unauthenticated", "Messaging account changed.");
  }
  // Optional operator rollback piggybacks on traffic; never add configuration polling/probes.
  let preferredRegion: string | undefined;
  try {
   const policy = await getFirestore().doc("messagingRuntime/regional").get();
   if (policy.get("europeEnabled") === false) preferredRegion = "us-central1";
  } catch { /* Successful delivery must not fail because an optional control read failed. */ }
  const routing = { servingRegion: region, ...(preferredRegion ? { preferredRegion } : {}), leaseSeconds: 300,
   ...(currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE ? { databaseId: MULTIREGION_MESSAGING_DATABASE, protocol: 4 } : {}) };
  let result: object;
  switch (data.operation) {
   case "relayMessage": result = await runCompatibleOperation("relayMessage", { ...request, data: data.payload },
    scoped => regionalRelay(scoped.data, uid)); break;
   case "compactInbox": return runCompatibleOperation("compactInbox", { ...request, data: data.payload },
    scoped => compactInboxResponse(scoped, { routing }));
   case "acknowledgeMessage": result = await acknowledgeMessage.run({ ...request, data: data.payload }); break;
   case "publishIdentityKey": result = await publishIdentityKey.run({ ...request, data: data.payload }); break;
   case "lookupIdentityKey": result = await lookupIdentityKey.run({ ...request, data: data.payload }); break;
   case "findContactsOnCrisisConnect": result = await findContactsOnCrisisConnect.run({ ...request, data: data.payload }); break;
   case "registerPushToken": result = await registerPushToken.run({ ...request, data: data.payload }); break;
   case "unregisterPushToken": result = await unregisterPushToken.run({ ...request, data: data.payload }); break;
   case "publishSignalPreKeys": result = await publishSignalPreKeys.run({ ...request, data: data.payload }); break;
   case "checkSignalPreKeys": result = await checkSignalPreKeys.run({ ...request, data: data.payload }); break;
   case "fetchSignalPreKeyBundle": result = await fetchSignalPreKeyBundle.run({ ...request, data: data.payload }); break;
   default: throw new HttpsError("invalid-argument", "Unsupported regional operation.");
  }
  return { ...result, routing };
 });
}

/** One transaction across both regional gateways, even after a legacy recipient deletes its copy. */
export async function regionalRelay(input: unknown, uid: string): Promise<Record<string, unknown>> {
 const envelope = parseRelayEnvelope(input, uid);
 const payload = input as Record<string, unknown>;
 if (payload.callRing !== undefined && typeof payload.callRing !== "boolean") throw new HttpsError("invalid-argument", "Invalid call marker.");
 const db = getFirestore();
 const message = db.doc(`messages/${envelope.messageId}`);
 const receipt = db.doc(`messagingRegionalReceipts/${createHash("sha256").update(envelope.messageId).digest("hex")}`);
 const budget = db.doc(`messagingRelayBudgets/${uid}`);
 const highAvailability = currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE;
 const ack = db.doc(`messagingBridgeAcks/${envelope.recipientUid}/ackedMessages/${messageDigest(envelope.messageId)}`);
 return db.runTransaction(async tx => {
  const [accepted, existing, balance, acknowledged, recipientAccount] = await tx.getAll(receipt, message, budget,
   ...(highAvailability ? [ack, db.doc(`messagingAccounts/${envelope.recipientUid}`)] : []));
  if (recipientAccount?.get("state") === "deleted") throw new HttpsError("not-found", "Messaging account is unavailable.");
  const previous = accepted.exists ? accepted : existing;
  if (previous.exists) {
   if (previous.get("senderUid") !== uid || previous.get("recipientUid") !== envelope.recipientUid || previous.get("conversationId") !== envelope.conversationId) {
    throw new HttpsError("already-exists", "Message ID already bound to another route.");
   }
   if (!accepted.exists) tx.create(receipt, { senderUid: uid, recipientUid: envelope.recipientUid, conversationId: envelope.conversationId,
    expireAt: Timestamp.fromMillis(Date.now() + 31 * 86_400_000) });
   return { ok: true, messageId: envelope.messageId, duplicate: true };
  }
  const now = Date.now();
  if (envelope.createdAtMs > now + 300_000 || envelope.createdAtMs + envelope.ttlMs <= now) {
   throw new HttpsError("failed-precondition", "Message timestamp outside delivery window.");
  }
  if (acknowledged?.exists) {
   tx.create(receipt, { senderUid: uid, recipientUid: envelope.recipientUid, conversationId: envelope.conversationId,
    expireAt: Timestamp.fromMillis(envelope.createdAtMs + envelope.ttlMs + 86_400_000) });
   return { ok: true, messageId: envelope.messageId, duplicate: true };
  }
  // Normal traffic keeps its existing contract; only explicit conservation opts into pacing.
  if (payload.trafficPolicyVersion === 2) {
   const reserved = reserveRelayBudget(balance.data() ?? {}, now, envelope.priority === "high");
   if (reserved.retryAfterMs) throw new HttpsError("resource-exhausted", "Relay pacing required.", { retryAfterMs: Math.max(30_000, reserved.retryAfterMs) });
   tx.set(budget, { ...reserved.state, expireAt: Timestamp.fromMillis(now + 86_400_000) });
  }
  tx.create(receipt, { senderUid: uid, recipientUid: envelope.recipientUid, conversationId: envelope.conversationId,
   expireAt: Timestamp.fromMillis(envelope.createdAtMs + envelope.ttlMs + 86_400_000) });
  tx.create(message, { ...envelope, ...(payload.callRing === true ? { callRing: true } : {}),
   ...(highAvailability ? { bridgeState: "pending" } : {}),
   serverReceivedAt: FieldValue.serverTimestamp(), delivered: false, expireAt: Timestamp.fromMillis(envelope.createdAtMs + envelope.ttlMs) });
  return { ok: true, messageId: envelope.messageId };
 });
}
export const messagingGatewayEurope = gateway("europe-west4");
export const messagingGatewayUS = gateway("us-central1");
// Measured candidate; mobile routing must qualify the full database path before selecting it.
export const messagingGatewayMumbai = gateway("asia-south1");
