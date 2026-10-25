import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { onDocumentWritten } from "firebase-functions/v2/firestore";

/** The existing account-deletion callable already writes this HA tombstone. */
export async function eraseApplicationAccount(uid: string, db = getFirestore("messaging-live-eur3"),
  eraseFiles: (prefix: string) => Promise<void> = async prefix => {
    const bucket = getStorage().bucket("crisis-connect-1-media-eu");
    await bucket.deleteFiles({ prefix });
  }, legacy = getFirestore()): Promise<void> {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(uid)) throw new Error("Invalid account ID");
  if ((await db.doc(`messagingAccounts/${uid}`).get()).get("state") !== "deleted") return;
  // Remove media before its route so a retry can still discover every object prefix.
  const routes = await db.collection("authorityHaRoutesById").where("participants", "array-contains", uid).get();
  await eraseFiles(`users/${uid}/`);
  for (const route of routes.docs) {
    // The entire conversation is erased below. Remove its attachments before removing the
    // route, otherwise a later deletion of the other participant cannot discover orphan files.
    await eraseFiles(`authorityMessageAttachments/${route.id}/`);
    await db.recursiveDelete(db.doc(`authorityMlsV2/${route.id}`));
    const oldId = route.get("legacyConversationId");
    if (typeof oldId === "string" && /^am2_[A-Za-z0-9_-]{43}$/.test(oldId))
      await db.doc(`authorityHaRoutes/${oldId}`).delete();
    await route.ref.delete();
  }
  for (const root of ["users", "profileMirrorState", "authorityHaDevices"])
    await db.recursiveDelete(db.doc(`${root}/${uid}`));
  await legacy.recursiveDelete(legacy.doc(`profileMirrorState/${uid}`));
}

function accountErasure(region: string) {
  return onDocumentWritten({ document: "messagingAccounts/{uid}", database: "messaging-live-eur3",
    region, retry: true, timeoutSeconds: 540 }, async event => {
    if (event.data?.after.get("state") === "deleted") await eraseApplicationAccount(event.params.uid);
  });
}
export const onHaApplicationAccountDeletedEurope = accountErasure("europe-west1");
export const onHaApplicationAccountDeletedUS = accountErasure("us-central1");
