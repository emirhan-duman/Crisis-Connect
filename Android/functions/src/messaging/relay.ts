import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";
import { requireUid } from "../certificates/callerRole";
import { parseRelayEnvelope } from "./envelope";

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
export const relayMessage = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const envelope = parseRelayEnvelope(request.data, uid);

    const ref = getFirestore().doc(`messages/${envelope.messageId}`);
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
