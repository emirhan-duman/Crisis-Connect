import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { requireUid } from "./callerRole";
import { authorityRolloutAllows } from "./authorityMeshKeyPolicy";

// Cohorts cover accounts whose devices have been upgraded. Version hints from a peer's
// advertisement or message are never a reason to send new controls to an old client.
export const getMessagingSecurityCapabilitiesV2 = onCall({ enforceAppCheck: true }, async request => {
  const uid = requireUid(request);
  const targetUid = request.data?.targetUid;
  if (typeof targetUid !== "string" || !targetUid || targetUid.length > 128 || targetUid.includes("/") || targetUid !== targetUid.trim()) {
    throw new HttpsError("invalid-argument", "A target account is required.");
  }
  const rollout = await getFirestore().doc("securityProtocolRollouts/messaging-v2").get();
  const config = rollout.data();
  return { protocolVersion: 2, signalIdentityBinding:
    authorityRolloutAllows(uid, config, "messaging-v2") && authorityRolloutAllows(targetUid, config, "messaging-v2") };
});
