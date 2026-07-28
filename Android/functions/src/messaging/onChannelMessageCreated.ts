import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging, MulticastMessage } from "firebase-admin/messaging";

// Silent pushes are invisible to a force-quit iPhone: iOS delivers content-available only to a
// running/backgrounded process, so a kurum message to a quit app simply never surfaced — no banner,
// nothing. Android is the opposite: its FCM service decrypts and posts a rich local notification
// from a data push even when dead. Hence the split below (the same one onMessageCreated does):
// Android keeps the silent data push, iOS gets a real alert with generic loc-keys (content stays
// E2E; a running app replaces the banner with the decrypted local notification).

/**
 * Background push for authority CHANNEL messages (agency + hierarchy). These secureMessages are E2E
 * ciphertext — the server can't read them — so the push carries only the plaintext sender name +
 * channel routing; the Android/iOS client posts a local "new message" notification and loads the
 * content when the messaging screen opens. Mirrors onMessageCreated's token fanout + invalid-token
 * pruning. Recipients: the whole agency (minus the sender) for an agency message; the addressed peer
 * for a hierarchy (1:1-within-channel) message.
 */

async function pushToUsers(recipientUids: string[], data: Record<string, string>): Promise<void> {
  const db = getFirestore();
  await Promise.all(
    recipientUids.map(async (uid) => {
      const tokensSnap = await db.collection(`messagingTokens/${uid}/tokens`).get();
      const tokenDocs = tokensSnap.docs.filter((d) => typeof d.data().token === "string");
      const tokens = tokenDocs.map((d) => d.data().token as string);
      if (tokens.length === 0) return;
      const iosDocs = tokenDocs.filter((d) => d.data().platform === "ios");
      const androidDocs = tokenDocs.filter((d) => d.data().platform !== "ios");
      const groups: { docs: typeof tokenDocs; message: MulticastMessage }[] = [];
      if (androidDocs.length > 0) {
        groups.push({
          docs: androidDocs,
          message: {
            tokens: androidDocs.map((d) => d.data().token as string),
            data,
            android: { priority: "high" },
          },
        });
      }
      if (iosDocs.length > 0) {
        groups.push({
          docs: iosDocs,
          message: {
            tokens: iosDocs.map((d) => d.data().token as string),
            data,
            apns: {
              headers: {
                "apns-priority": "10",
                "apns-push-type": "alert",
                // One banner per channel for a force-quit device, not one per message.
                "apns-collapse-id": (data.channelId ?? "").slice(0, 64),
              },
              payload: {
                aps: {
                  alert: {
                    titleLocKey: "NOTIF_ENC_MESSAGE_TITLE",
                    locKey: "NOTIF_ENC_MESSAGE_BODY",
                  },
                  sound: "default",
                  // Without a category the tap falls through the client switch and drops navigation.
                  category: "chat.message",
                  threadId: data.channelId ?? "",
                  mutableContent: true,
                  // Wake a backgrounded app so it can swap in the decrypted local notification.
                  contentAvailable: true,
                },
              },
            },
          },
        });
      }
      for (const group of groups) {
        const response = await getMessaging().sendEachForMulticast(group.message);
        const cleanups: Promise<unknown>[] = [];
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
        if (cleanups.length > 0) await Promise.allSettled(cleanups);
      }
    })
  );
}

function readStr(value: unknown): string {
  return typeof value === "string" ? value : "";
}

export const onAgencyMessageCreated = onDocumentCreated(
  "agencyPanels/{slug}/secureMessages/{id}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const msg = snap.data() as Record<string, unknown>;
    const senderUid = readStr(msg.senderUid);
    const slug = event.params.slug;
    const db = getFirestore();
    // Agency members share the stored agencySlug (the message path slug, normalized by
    // issueAgencyMessagingKey). Case-inconsistent provisioning could miss members — same caveat as
    // listAuthorityRoster.
    const usersSnap = await db.collection("users").where("agencySlug", "==", slug).get();
    const recipients = usersSnap.docs.map((d) => d.id).filter((uid) => uid && uid !== senderUid);
    if (recipients.length === 0) return;
    await pushToUsers(recipients, {
      type: "channel_chat",
      channelKind: "agency",
      channelId: slug,
      senderUid,
      senderName: readStr(msg.senderName),
    });
  }
);

export const onHierarchyMessageCreated = onDocumentCreated(
  "hierarchyChannels/{channelId}/secureMessages/{id}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const msg = snap.data() as Record<string, unknown>;
    const senderUid = readStr(msg.senderUid);
    const recipientUid = readStr(msg.recipientUid);
    if (!recipientUid || recipientUid === senderUid) return;
    await pushToUsers([recipientUid], {
      type: "channel_chat",
      channelKind: "hierarchy",
      channelId: event.params.channelId,
      senderUid,
      senderName: readStr(msg.senderName),
    });
  }
);
