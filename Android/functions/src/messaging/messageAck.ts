import { compatibleCallable } from "./compatibilityRouting";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getMessagingFirestore as getFirestore, currentMessagingDatabaseId, MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { acknowledgeHaMessage } from "./highAvailabilityStore";
import { requireUid } from "../certificates/callerRole";

// '~' is part of the store-and-forward id shape ("uuid~createdAtMs") used by direct Firestore
// writes; rejecting it made those docs un-ackable (immortal replay zombies).
const ID_REGEX = /^[A-Za-z0-9._:~-]{1,128}$/;

/**
 * WhatsApp/Signal-style delivery receipt: once the recipient has decrypted and stored a message
 * locally it acknowledges receipt, and the server DELETES the ciphertext. Together with FCM
 * inline delivery this means the server holds a message only for the brief window before the
 * recipient confirms it — minimal storage, minimal load.
 */
export const acknowledgeMessage = compatibleCallable("acknowledgeMessage",
  // Released clients await this ACK before sending the peer's delivery receipt.
  { enforceAppCheck: true, minInstances: 1 },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const messageId = data.messageId;
    if (typeof messageId !== "string" || !ID_REGEX.test(messageId)) {
      throw new HttpsError("invalid-argument", "messageId is required.");
    }

    if (currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE) {
      await acknowledgeHaMessage(uid, messageId);
      return { ok: true };
    }

    const ref = getFirestore().doc(`messages/${messageId}`);
    const snap = await ref.get();
    if (!snap.exists) return { ok: true, alreadyGone: true };
    if (snap.data()?.recipientUid !== uid) {
      throw new HttpsError("permission-denied", "Not the recipient of this message.");
    }
    await ref.delete();
    return { ok: true };
  }
);
