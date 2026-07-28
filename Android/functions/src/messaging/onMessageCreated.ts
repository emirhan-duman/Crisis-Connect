import { onDocumentCreated } from "firebase-functions/v2/firestore";
import * as logger from "firebase-functions/logger";
import { getFirestore } from "firebase-admin/firestore";
import { ApnsConfig, getMessaging, MulticastMessage } from "firebase-admin/messaging";
import type { RelayEnvelope } from "./envelope";
import {
  sendVoipPush,
  apnsAuthKeyP8,
  apnsKeyId,
  apnsTeamId,
} from "./apnsVoip";

const IOS_BUNDLE_ID = "com.auralis.crisisconnect";

/**
 * Unified delivery: whenever a message doc appears (via the relay callable in crisis mode
 * OR a direct Firestore SDK write in normal mode), push it to every registered device of
 * the recipient over FCM. FCM is the OS's single shared push channel, so this is the
 * low-bandwidth, low-battery downlink — no per-app socket, minimal radio signaling.
 *
 * The push carries the already-E2E-encrypted envelope as a DATA message (no notification
 * body — the server has no plaintext to show; the client decrypts and posts a local
 * notification). If the envelope is too large for FCM's 4 KB data budget we fall back to
 * a wake ping and the client fetches the ciphertext from Firestore.
 */

const FCM_DATA_BUDGET_BYTES = 3800; // leave headroom under FCM's ~4 KB data limit

function utf8Len(value: string): number {
  return Buffer.byteLength(value, "utf8");
}

function dataSize(data: Record<string, string>): number {
  let total = 0;
  for (const [k, v] of Object.entries(data)) {
    total += utf8Len(k) + utf8Len(v);
  }
  return total;
}

export const onMessageCreated = onDocumentCreated(
  {
    document: "messages/{messageId}",
    // Only bound so a call-offer envelope can fire a PushKit VoIP ring; the secrets are unused
    // (and unread) for ordinary chat messages, which stay on the FCM path below.
    secrets: [apnsAuthKeyP8, apnsKeyId, apnsTeamId],
    // Keep one instance warm. This trigger delivers the incoming-call ring push; a cold start added
    // ~10-15s before a backgrounded callee rang. One reserved instance removes that cold-start delay
    // (small always-on cost) so calls ring promptly.
    minInstances: 1,
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const msg = snap.data() as Partial<RelayEnvelope> | undefined;
    if (!msg || typeof msg.recipientUid !== "string") return;

    const db = getFirestore();
    const tokensSnap = await db
      .collection(`messagingTokens/${msg.recipientUid}/tokens`)
      .get();
    if (tokensSnap.empty) {
      await snap.ref.set({ delivered: false, noTokens: true }, { merge: true });
      return;
    }
    const tokenDocs = tokensSnap.docs.filter(
      (d) => typeof d.data().token === "string"
    );

    // A call OFFER carries a top-level `callRing` marker (routing metadata only — the offer
    // itself stays E2E-encrypted). iOS registers its PushKit token under platform "ios-voip";
    // fire a VoIP push to those so a force-quit app wakes and CallKit rings. The regular FCM
    // fan-out below still runs (a foregrounded app rings via its Firestore listener / data push).
    const isCallRing =
      (msg as Record<string, unknown>).callRing === true;
    if (isCallRing) {
      // How long the offer sat between the caller writing it and this trigger running — tells us
      // whether ring latency is Firestore/Eventarc delivery or our own APNs hop.
      const sentAt = Number(msg.createdAtMs ?? 0);
      if (sentAt > 0) {
        logger.info(`callRing: ${Date.now() - sentAt}ms from sender write to trigger`);
      }
      const voipDocs = tokenDocs.filter(
        (d) => d.data().platform === "ios-voip"
      );
      if (voipDocs.length > 0) {
        await Promise.allSettled(
          voipDocs.map(async (d) => {
            const res = await sendVoipPush(d.data().token as string, IOS_BUNDLE_ID, {
              callId: String(msg.messageId ?? event.params.messageId),
              conversationId: String(msg.conversationId ?? ""),
              senderUid: String(msg.senderUid ?? ""),
              // No plaintext caller name server-side; the client resolves it from the uid.
              callerName: "",
              hasVideo: false,
            });
            if (res.shouldPrune) await d.ref.delete();
          })
        );
      }
    }

    // VoIP (PushKit) tokens are NOT valid FCM/APNs alert tokens — never fan the data push to them.
    const fcmTokenDocs = tokenDocs.filter(
      (d) => d.data().platform !== "ios-voip"
    );
    const tokens = fcmTokenDocs.map((d) => d.data().token as string);
    if (tokens.length === 0) {
      await snap.ref.set(
        { delivered: false, voipOnly: isCallRing },
        { merge: true }
      );
      return;
    }

    const highPriority = msg.priority === "high";

    // Full inline payload — the client can decrypt without a follow-up fetch. `v` selects the
    // crypto layer: 1 = ECIES (alg/ephemeralPubKey/nonce), 2 = Signal (ctype). Both carry ciphertext.
    const inlineData: Record<string, string> = {
      type: "chat",
      v: String(msg.v ?? 1),
      messageId: String(msg.messageId ?? event.params.messageId),
      conversationId: String(msg.conversationId ?? ""),
      senderUid: String(msg.senderUid ?? ""),
      alg: String(msg.alg ?? ""),
      ephemeralPubKey: String(msg.ephemeralPubKey ?? ""),
      nonce: String(msg.nonce ?? ""),
      ctype: String(msg.ctype ?? ""),
      ciphertext: String(msg.ciphertext ?? ""),
      createdAtMs: String(msg.createdAtMs ?? ""),
      ttlMs: String(msg.ttlMs ?? ""),
      priority: highPriority ? "high" : "normal",
    };

    const useInline = dataSize(inlineData) <= FCM_DATA_BUDGET_BYTES;
    const data: Record<string, string> = useInline
      ? inlineData
      : {
          type: "chat",
          v: String(msg.v ?? 1),
          messageId: String(msg.messageId ?? event.params.messageId),
          conversationId: String(msg.conversationId ?? ""),
          senderUid: String(msg.senderUid ?? ""),
          fetch: "1", // client pulls the ciphertext from Firestore
          priority: highPriority ? "high" : "normal",
        };

    const ttlSeconds = Math.max(
      60,
      Math.floor(Number(msg.ttlMs ?? 24 * 60 * 60 * 1000) / 1000)
    );

    const apnsExpiration = String(Math.floor(Date.now() / 1000) + ttlSeconds);

    // Split the fan-out by platform. Android decrypts inside its FCM service and posts a local
    // notification, so a silent data push suffices. iOS cannot execute code on a background push
    // once the app is force-quit — those tokens get a visible ALERT push instead. The alert uses
    // loc-keys so the DEVICE localizes the generic text (the server never has plaintext), and it
    // still carries content-available + the full data payload so a running (foreground/background)
    // app decrypts and files the message exactly as before.
    // Real content carries the 24h default TTL. Real-time CONTROL traffic — call signalling (60s)
    // and typing pulses (15s) — must never raise a user-visible banner, otherwise the callee sees a
    // couple of "encrypted message" notifications right before the call rings. The server can't read
    // the (encrypted) templateCode, but the TTL separates the two classes with a huge margin.
    const CONTROL_TRAFFIC_TTL_CUTOFF_MS = 5 * 60 * 1000;
    const envelopeTtlMs = Number(msg.ttlMs ?? 0);
    const isControlTraffic =
      envelopeTtlMs > 0 && envelopeTtlMs <= CONTROL_TRAFFIC_TTL_CUTOFF_MS;

    const androidTokenDocs = fcmTokenDocs.filter(
      (d) => d.data().platform !== "ios"
    );
    const iosTokenDocs = fcmTokenDocs.filter(
      (d) => d.data().platform === "ios"
    );

    const groups: {
      docs: typeof fcmTokenDocs;
      message: MulticastMessage;
    }[] = [];

    if (androidTokenDocs.length > 0) {
      groups.push({
        docs: androidTokenDocs,
        message: {
          tokens: androidTokenDocs.map((d) => d.data().token as string),
          data,
          android: {
            priority: "high",
            ttl: ttlSeconds * 1000,
          },
          apns: {
            headers: {
              // Background pushes must use priority 5 — APNs rejects/throttles 10 for
              // apns-push-type "background" (10 is reserved for alert pushes).
              "apns-priority": "5",
              "apns-push-type": "background",
              "apns-expiration": apnsExpiration,
            },
            payload: {
              aps: {
                "content-available": 1,
              },
            },
          },
        },
      });
    }

    if (iosTokenDocs.length > 0) {
      // Control traffic goes out SILENTLY (background push): a running app still decrypts and acts on
      // it, and a force-quit device is woken for an incoming call by the VoIP push above — no banner.
      const iosApns: ApnsConfig = isControlTraffic
        ? {
            headers: {
              // Background pushes must use priority 5; 10 is reserved for alert pushes.
              "apns-priority": "5",
              "apns-push-type": "background",
              "apns-expiration": apnsExpiration,
            },
            payload: {
              aps: {
                "content-available": 1,
              },
            },
          }
        : {
            headers: {
              "apns-priority": "10",
              "apns-push-type": "alert",
              "apns-expiration": apnsExpiration,
              // One banner per conversation for a force-quit device instead of N generic ones —
              // each new message replaces the previous banner for that thread.
              "apns-collapse-id": String(msg.conversationId ?? "").slice(0, 64),
            },
            payload: {
              aps: {
                // Device-side localization: the client's Localizable.strings render these keys.
                alert: {
                  titleLocKey: "NOTIF_ENC_MESSAGE_TITLE",
                  locKey: "NOTIF_ENC_MESSAGE_BODY",
                },
                sound: "default",
                // Without a category, a tap on this banner fell through the client's notification
                // switch to `default: break`: the app foregrounded and the navigation was dropped.
                category: "chat.message",
                threadId: String(msg.conversationId ?? ""),
                // mutable-content: a future Notification Service Extension can intercept this
                // alert, decrypt the ciphertext from the data payload, and rewrite the generic
                // title/body with the real sender + message before the banner is shown.
                mutableContent: true,
                // Also wake a backgrounded app so it decrypts + files the envelope immediately.
                contentAvailable: true,
              },
            },
          };
      groups.push({
        docs: iosTokenDocs,
        message: {
          tokens: iosTokenDocs.map((d) => d.data().token as string),
          data,
          apns: iosApns,
        },
      });
    }

    const messaging = getMessaging();
    let successCount = 0;
    const cleanups: Promise<unknown>[] = [];
    for (const group of groups) {
      const response = await messaging.sendEachForMulticast(group.message);
      successCount += response.successCount;

      // Prune tokens the FCM backend reports as permanently invalid.
      response.responses.forEach((res, idx) => {
        if (res.success) return;
        const code = res.error?.code ?? "";
        if (
          code === "messaging/registration-token-not-registered" ||
          code === "messaging/invalid-argument" ||
          code === "messaging/invalid-registration-token"
        ) {
          cleanups.push(group.docs[idx].ref.delete());
        }
      });
    }
    if (cleanups.length > 0) {
      await Promise.allSettled(cleanups);
    }

    await snap.ref.set(
      { delivered: successCount > 0, deliveredCount: successCount },
      { merge: true }
    );
  }
);
