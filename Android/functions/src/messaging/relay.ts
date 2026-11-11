import { compatibleCallable } from "./compatibilityRouting";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { getMessagingFirestore as getFirestore, currentMessagingDatabaseId, MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { regionalRelay } from "./regionalGateway";
import { requireUid } from "../certificates/callerRole";
import { parseRelayEnvelope } from "./envelope";
import { reserveRelayBudget } from "./relayBudget";

/**
 * Crisis-mode (low-bandwidth) uplink: a single authenticated call that hands the server
 * one already-E2E-encrypted envelope. The server persists it to `messages/{messageId}`
 * (durability + offline delivery) and the {@link onMessageCreated} trigger fans it out
 * over FCM. Normal mode instead writes the same doc directly via the Firestore SDK; both
 * paths converge on the trigger, so delivery is unified.
 *
 * The write is idempotent per messageId (`create` throws on an existing doc) so client
 * retries during congested networks never duplicate a message or its push.
 */
export const relayMessage = compatibleCallable("relayMessage",
  // Released clients use this fixed US endpoint when their direct write fails.
  { enforceAppCheck: true, minInstances: 1 },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    if (currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE) return regionalRelay(request.data, uid);
    const envelope = parseRelayEnvelope(request.data, uid);

    const db = getFirestore();
    const ref = db.doc(`messages/${envelope.messageId}`);
    // Only clients explicitly advertising v2 opt in; released legacy clients keep their contract.
    if ((request.data as Record<string, unknown>)?.trafficPolicyVersion === 2) {
      return db.runTransaction(async transaction => {
        const budgetRef = db.doc(`messagingRelayBudgets/${uid}`);
        const [existing,budget] = await transaction.getAll(ref,budgetRef);
        if (existing.exists) {
          if (existing.get("senderUid") !== uid) throw new HttpsError("already-exists","Message ID belongs to another sender.");
          return { ok:true,messageId:envelope.messageId,duplicate:true };
        }
        const reserved = reserveRelayBudget(budget.data() ?? {},Date.now(),envelope.priority === "high");
        if (reserved.retryAfterMs) throw new HttpsError("resource-exhausted","Relay pacing required.",
          { retryAfterMs:Math.max(30_000,reserved.retryAfterMs) });
        transaction.set(budgetRef,{...reserved.state,expireAt:Timestamp.fromMillis(Date.now() + 86_400_000)});
        transaction.create(ref,{...envelope,serverReceivedAt:FieldValue.serverTimestamp(),delivered:false,
          expireAt:Timestamp.fromMillis(Date.now() + envelope.ttlMs)});
        return { ok:true,messageId:envelope.messageId };
      });
    }
    try {
      await ref.create({
        ...envelope,
        serverReceivedAt: FieldValue.serverTimestamp(),
        delivered: false,
        // Safety-net expiry: the recipient deletes this doc as soon as it stores the message
        // (acknowledgeMessage). expireAt lets a scheduled purge drop anything never acknowledged
        // (recipient gone / never came online) so nothing lingers on the server.
        expireAt: Timestamp.fromMillis(Date.now() + envelope.ttlMs),
      });
    } catch (error) {
      const code = (error as { code?: number }).code;
      // ALREADY_EXISTS (6) → a retry of a message we already accepted. Treat as success.
      if (code === 6) {
        return { ok: true, messageId: envelope.messageId, duplicate: true };
      }
      throw new HttpsError("internal", "Failed to persist message.");
    }

    return { ok: true, messageId: envelope.messageId };
  }
);
