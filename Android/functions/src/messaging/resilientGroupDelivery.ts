import { randomUUID } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { haFirestore } from "./highAvailabilityStore";
import { authorizeGroup, groupBinding, groupConversationId } from "./resilientGroups";

export async function deliverResilientGroupMessage(conversationId: string, messageId: string): Promise<void> {
  const db = haFirestore(), parent = await db.doc(`authorityMlsV2/${conversationId}`).get();
  if (!parent.exists) return; // Account erasure may remove the group before this event arrives.
  const binding = groupBinding(parent.data());
  if (groupConversationId(binding, true) !== conversationId) throw new Error("Group delivery identity mismatch");
  const message = await parent.ref.collection("messages").doc(messageId).get();
  if (!message.exists) return;
  const sender = message.get("senderUid"), recipient = binding.participants.find(x => x !== sender);
  if (!binding.participants.includes(sender) || !recipient) throw new Error("Invalid group message participant");
  try { await authorizeGroup(sender, binding); } catch (error) {
    if ((error as { code?: string }).code === "permission-denied") return;
    throw error; // Retry temporary database failures instead of dropping the notification.
  }
  const profile = await db.doc(`users/${recipient}`).get();
  if (profile.get("notificationPreferences")?.push === false) return;
  const job = db.doc(`authorityHaDelivery/${conversationId}_${messageId}`), owner = randomUUID();
  const acquired = await db.runTransaction(async tx => {
    const old = await tx.get(job);
    if (old.get("complete") === true) return false;
    if (Number(old.get("leaseUntil") ?? 0) > Date.now()) throw new Error("Group delivery is already leased");
    tx.set(job, { owner, leaseUntil: Date.now() + 120_000,
      expireAt: Timestamp.fromMillis(Date.now() + 31 * 86_400_000) }, { merge: true });
    return true;
  });
  if (!acquired) return;
  try {
    const rows = await db.collection(`messagingTokens/${recipient}/tokens`).limit(500).get();
    const data = { type: "authority_mls_v2", store: "ha", conversationId, messageId };
    for (const platform of ["android", "ios"]) {
      const selected = rows.docs.filter(x => typeof x.get("token") === "string" &&
        (platform === "ios" ? x.get("platform") === "ios" : x.get("platform") !== "ios"));
      if (!selected.length) continue;
      const result = await getMessaging().sendEachForMulticast({ tokens: selected.map(x => x.get("token")), data,
        android: { priority: "high", ttl: 3_600_000, collapseKey: conversationId },
        apns: { headers: { "apns-push-type": "alert", "apns-priority": "10", "apns-collapse-id": conversationId },
          payload: { aps: { alert: { titleLocKey: "NOTIF_ENC_MESSAGE_TITLE", locKey: "NOTIF_ENC_MESSAGE_BODY" },
            contentAvailable: true, mutableContent: true, category: "chat.message", threadId: conversationId } } },
      });
      let retry = false;
      for (let i = 0; i < result.responses.length; i++) {
        const response = result.responses[i];
        if (response.success) continue;
        if (["messaging/invalid-registration-token", "messaging/registration-token-not-registered"].includes(response.error?.code ?? "")) {
          await selected[i].ref.delete();
        } else retry = true;
      }
      if (retry) throw new Error("A group notification requires retry");
    }
    await db.runTransaction(async tx => {
      if ((await tx.get(job)).get("owner") === owner) tx.update(job, { complete: true, leaseUntil: 0 });
    });
  } catch (error) {
    await db.runTransaction(async tx => {
      if ((await tx.get(job)).get("owner") === owner) tx.update(job, { leaseUntil: 0 });
    });
    throw error;
  }
}
const trigger = (region: string) => onDocumentCreated({
  document: "authorityMlsV2/{conversationId}/messages/{messageId}", database: MULTIREGION_MESSAGING_DATABASE,
  region, retry: true, timeoutSeconds: 90, maxInstances: 10,
}, async event => {
  if (process.env.PROFILE_HA_ENABLED === "true")
    await deliverResilientGroupMessage(event.params.conversationId, event.params.messageId);
});
export const onHaGroupMessageEurope = trigger("europe-west1");
export const onHaGroupMessageUS = trigger("us-central1");
