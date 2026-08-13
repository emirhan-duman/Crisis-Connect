import { getFirestore, QueryDocumentSnapshot, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import * as logger from "firebase-functions/logger";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import {
  apnsAuthKeyP8,
  apnsKeyId,
  apnsTeamId,
  sendVoipPush,
} from "./apnsVoip";

const IOS_BUNDLE_ID = "com.auralis.crisisconnect";
const UUID = /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$/;
const MAX_SIGNAL_AGE_MS = 45_000;
const MAX_RETENTION_MS = 25 * 60 * 60 * 1000;
const BASE_KEYS = new Set([
  "signalVersion", "scopeType", "channelId", "from", "to", "callId", "type",
  "createdAt", "expireAt", "roomId", "video", "sfuVersion",
]);

type AuthorityScope = "agency" | "hierarchy";

function exactOffer(raw: Record<string, unknown>, scopeType: AuthorityScope, channelId: string): boolean {
  if (Object.keys(raw).length !== BASE_KEYS.size || Object.keys(raw).some((key) => !BASE_KEYS.has(key))) return false;
  const createdAt = raw.createdAt;
  const expireAt = raw.expireAt;
  const now = Date.now();
  if (!(createdAt instanceof Timestamp) || !(expireAt instanceof Timestamp)) return false;
  const age = now - createdAt.toMillis();
  const retention = expireAt.toMillis() - createdAt.toMillis();
  return raw.signalVersion === 2 && raw.scopeType === scopeType && raw.channelId === channelId &&
    raw.type === "offer" && raw.sfuVersion === 2 && typeof raw.video === "boolean" &&
    typeof raw.from === "string" && raw.from.length > 0 && raw.from.length <= 128 &&
    typeof raw.to === "string" && raw.to.length > 0 && raw.to.length <= 128 && raw.from !== raw.to &&
    typeof raw.callId === "string" && UUID.test(raw.callId) &&
    typeof raw.roomId === "string" && UUID.test(raw.roomId) &&
    age >= -5_000 && age <= MAX_SIGNAL_AGE_MS && retention > 0 && retention <= MAX_RETENTION_MS &&
    expireAt.toMillis() > now;
}

async function handleAuthorityCallOffer(
  snap: QueryDocumentSnapshot | undefined,
  scopeType: AuthorityScope,
  channelId: string,
  signalId: string,
): Promise<void> {
  if (!snap || !channelId || !signalId) return;
  const raw = snap.data() as Record<string, unknown>;
  if (!exactOffer(raw, scopeType, channelId)) {
    logger.warn("Dropped malformed authority call offer", { scopeType, channelId, signalId });
    return;
  }

  const senderUid = raw.from as string;
  const recipientUid = raw.to as string;
  const db = getFirestore();
  const parentPath = scopeType === "agency" ? `agencyPanels/${channelId}` : `hierarchyChannels/${channelId}`;
  const [sender, recipient, parent, tokenSnapshot] = await Promise.all([
    db.doc(`users/${senderUid}`).get(),
    db.doc(`users/${recipientUid}`).get(),
    db.doc(parentPath).get(),
    db.collection(`messagingTokens/${recipientUid}/tokens`).get(),
  ]);
  if (!sender.exists || !recipient.exists || !parent.exists) return;
  const senderAgency = sender.get("agencySlug");
  const recipientAgency = recipient.get("agencySlug");
  const scoped = scopeType === "agency"
    ? senderAgency === channelId && recipientAgency === channelId
    : Array.isArray(parent.get("panelIds")) &&
      parent.get("panelIds").includes(senderAgency) && parent.get("panelIds").includes(recipientAgency);
  if (!scoped) {
    logger.warn("Dropped cross-scope authority call offer", { scopeType, channelId, signalId });
    return;
  }

  const tokenDocs = tokenSnapshot.docs.filter((doc) => typeof doc.get("token") === "string");
  const voipDocs = tokenDocs.filter((doc) => doc.get("platform") === "ios-voip");
  await Promise.allSettled(voipDocs.map(async (doc) => {
    const result = await sendVoipPush(doc.get("token") as string, IOS_BUNDLE_ID, {
      type: "authority_call_v2",
      callId: raw.callId as string,
      conversationId: "",
      senderUid,
      callerName: "",
      hasVideo: raw.video as boolean,
      authorityScopeType: scopeType,
      authorityChannelId: channelId,
      authoritySignalId: signalId,
    });
    if (result.shouldPrune) await doc.ref.delete();
  }));

  const androidDocs = tokenDocs.filter((doc) => {
    const platform = doc.get("platform");
    return platform !== "ios" && platform !== "ios-voip";
  });
  for (let offset = 0; offset < androidDocs.length; offset += 500) {
    const docs = androidDocs.slice(offset, offset + 500);
    const response = await getMessaging().sendEachForMulticast({
      tokens: docs.map((doc) => doc.get("token") as string),
      data: {
        type: "authority_call_v2",
        scopeType,
        channelId,
        signalId,
        callId: raw.callId as string,
        senderUid,
        hasVideo: (raw.video as boolean) ? "1" : "0",
      },
      android: { priority: "high", ttl: 60_000 },
    });
    await Promise.allSettled(response.responses.map(async (result, index) => {
      const code = result.error?.code ?? "";
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
        await docs[index].ref.delete();
      }
    }));
  }
}

const triggerOptions = {
  secrets: [apnsAuthKeyP8, apnsKeyId, apnsTeamId],
  minInstances: 1,
};

export const onAgencyAuthorityCallCreated = onDocumentCreated(
  { ...triggerOptions, document: "agencyPanels/{channelId}/callSignals/{signalId}" },
  (event) => handleAuthorityCallOffer(event.data, "agency", event.params.channelId, event.params.signalId),
);

export const onHierarchyAuthorityCallCreated = onDocumentCreated(
  { ...triggerOptions, document: "hierarchyChannels/{channelId}/callSignals/{signalId}" },
  (event) => handleAuthorityCallOffer(event.data, "hierarchy", event.params.channelId, event.params.signalId),
);
