import { onCall } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { requireUid } from "../certificates/callerRole";

/** Read-only comparison of the full authenticated callable -> Firestore path; no customer routing changes. */
function probe(region: string) {
  return onCall({ region,enforceAppCheck:true,invoker:"public" },async request => {
    requireUid(request);
    const started = Date.now();
    await getFirestore().doc("messagingRuntime/traffic").get();
    return { region,serverDurationMs:Date.now() - started,version:1 };
  });
}
export const messagingRegionProbe = probe("us-central1");
export const messagingRegionProbeEurope = probe("europe-west4");
export const messagingRegionProbeMumbai = probe("asia-south1");
