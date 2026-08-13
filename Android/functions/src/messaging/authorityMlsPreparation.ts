import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { requireUid } from "../certificates/callerRole";
import {
  AUTHORITY_MLS_CONVERSATION_ID,
  resolveAuthorityMlsPreparationRecipient,
} from "./authorityMlsPreparationPolicy";

const REQUEST_COOLDOWN_MS = 30_000;
const CLAIM_RETENTION_MS = 24 * 60 * 60 * 1000;
const INBOX_RETENTION_MS = 24 * 60 * 60 * 1000;
const INVALID_TOKEN_CODES = new Set([
  "messaging/invalid-registration-token",
  "messaging/registration-token-not-registered",
  "messaging/invalid-argument",
]);

/**
 * Sends a content-free wake so an offline peer can publish its own MLS device/KeyPackage.
 * The server never creates or receives an MLS private key and cannot decrypt the later message.
 */
export const requestAuthorityMlsPreparation = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const callerUid = requireUid(request);
    const raw = (request.data ?? {}) as Record<string, unknown>;
    const conversationId = raw.conversationId;
    if (typeof conversationId !== "string" || !AUTHORITY_MLS_CONVERSATION_ID.test(conversationId)) {
      throw new HttpsError("invalid-argument", "conversationId is invalid.");
    }

    const db = getFirestore();
    const parentRef = db.doc(`authorityMlsV2/${conversationId}`);
    // The canonical conversation id is already a collision-resistant, slash-free pair key.
    // Sharing one claim between both participants also prevents a ping-pong wake storm.
    const claimRef = db.doc(`authorityMlsPreparationClaims/${conversationId}`);
    const now = Date.now();
    const authorization = await db.runTransaction(async (transaction) => {
      const [parent, claim] = await Promise.all([
        transaction.get(parentRef),
        transaction.get(claimRef),
      ]);
      if (!parent.exists) throw new HttpsError("not-found", "Authority MLS conversation was not found.");
      const resolved = resolveAuthorityMlsPreparationRecipient(
        conversationId,
        callerUid,
        (parent.data() ?? {}) as Record<string, unknown>,
      );
      if (!resolved) throw new HttpsError("permission-denied", "Caller is not a participant in this conversation.");
      const lastRequestedAtMs = claim.data()?.lastRequestedAtMs;
      if (typeof lastRequestedAtMs === "number" && now - lastRequestedAtMs < REQUEST_COOLDOWN_MS) {
        return { ...resolved, throttled: true };
      }
      const inboxRef = db.doc(
        `authorityMlsPreparationInbox/${resolved.recipientUid}/requests/${conversationId}`,
      );
      transaction.set(claimRef, {
        conversationId,
        callerUid,
        recipientUid: resolved.recipientUid,
        lastRequestedAtMs: now,
        requestedAt: FieldValue.serverTimestamp(),
        expireAt: Timestamp.fromMillis(now + CLAIM_RETENTION_MS),
      });
      // This metadata-only inbox is the authoritative browser wake path. Unlike Web Push it does
      // not depend on notification permission, and recipient-only Firestore rules prevent another
      // account from observing or injecting preparation work. MLS private keys remain device-local.
      transaction.set(inboxRef, {
        version: 2,
        conversationId,
        recipientUid: resolved.recipientUid,
        requestedAt: FieldValue.serverTimestamp(),
        expireAt: Timestamp.fromMillis(now + INBOX_RETENTION_MS),
      });
      return { ...resolved, throttled: false };
    });
    if (authorization.throttled) return { ok: true, throttled: true, delivered: 0 };

    const tokenSnapshot = await db.collection(`messagingTokens/${authorization.recipientUid}/tokens`).get();
    const tokens = tokenSnapshot.docs.flatMap((snapshot) => {
      const token = snapshot.data().token;
      return typeof token === "string" && token ? [{ token, ref: snapshot.ref }] : [];
    });
    if (!tokens.length) return { ok: true, throttled: false, delivered: 0 };

    const response = await getMessaging().sendEachForMulticast({
      tokens: tokens.map((entry) => entry.token),
      data: { type: "authority_mls_prepare_v2", conversationId },
      android: { priority: "high", ttl: 60 * 60 * 1000, collapseKey: `mls-prepare-${conversationId}` },
      apns: {
        headers: {
          "apns-push-type": "background",
          "apns-priority": "5",
          "apns-expiration": String(Math.floor(now / 1000) + 3600),
          "apns-collapse-id": `mls-prepare-${conversationId}`.slice(0, 64),
        },
        payload: { aps: { contentAvailable: true } },
      },
      webpush: { headers: { Urgency: "high", TTL: "3600", Topic: `mls-prepare-${conversationId}`.slice(0, 32) } },
    });

    await Promise.all(response.responses.map((result, index) => {
      const code = result.error?.code;
      return code && INVALID_TOKEN_CODES.has(code) ? tokens[index].ref.delete() : Promise.resolve();
    }));
    return {
      ok: true,
      throttled: false,
      delivered: response.successCount,
      failed: response.failureCount,
    };
  },
);
