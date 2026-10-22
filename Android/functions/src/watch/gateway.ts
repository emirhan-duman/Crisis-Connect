import { onRequest, HttpsError } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldPath } from "firebase-admin/firestore";
import { authorizeWatch, object, parseWatchRequest, verifyWatchSignature, watchID, WatchRegistration } from "./deviceProtocol";
import { commitWatchCommand } from "./commandJournal";
import { prepareWatchSOS } from "./sosAdapter";
import { prepareWatchMessage, acknowledgeWatchMessage } from "./messageAdapter";

/** Status is a read-only reconciliation, never a replay of a stored action. */
export async function executeWatchRequest(raw: unknown, accountAvailable: (uid: string) => Promise<boolean> = async uid => {
  try { return !(await getAuth().getUser(uid)).disabled; } catch { return false; }
}) {
  const wrapper = object(raw), { request, encoded } = parseWatchRequest(wrapper.payload, Date.now());
  if (typeof wrapper.signature !== "string") throw new HttpsError("invalid-argument", "Missing proof.");
  const signature = wrapper.signature;
  const db = getFirestore(), deviceRef = db.doc(`watchInstallations/${request.deviceID}`);
  const registration = authorizeWatch((await deviceRef.get()).data() as WatchRegistration | undefined, request, Date.now());
  verifyWatchSignature(registration.publicKey, encoded, signature);
  if (!await accountAvailable(registration.ownerUid)) throw new HttpsError("permission-denied", "Account is unavailable.");
  // Rate-limit reads as well as writes. This budget is never authorization for a side effect.
  await db.runTransaction(async tx => {
    const ref = deviceRef.collection("limits").doc("requests"), snap = await tx.get(ref), now = Date.now();
    const bucket = Math.floor(now / 60_000), count = snap.get("bucket") === bucket ? Number(snap.get("count") ?? 0) : 0;
    if (count >= 60) throw new HttpsError("resource-exhausted", "Slow down and check status later.");
    tx.set(ref, { bucket, count: count + 1 });
  });
  if (request.action === "status") {
    const ids = request.body.commandIDs ?? [];
    if (!Array.isArray(ids) || ids.length > 32) throw new HttpsError("invalid-argument", "Invalid receipt lookup.");
    const unique = [...new Set(ids.map(watchID))];
    return db.runTransaction(async tx => {
      const latest = await tx.get(deviceRef);
      authorizeWatch(latest.data() as WatchRegistration | undefined, request, Date.now());
      const state = await tx.get(db.doc(`watchSOS/${registration.ownerUid}`));
      let inboxQuery = deviceRef.collection("inbox").where("state", "==", "accepted").orderBy(FieldPath.documentId()).limit(2);
      if (request.body.inboxAfter !== undefined) inboxQuery = inboxQuery.startAfter(watchID(request.body.inboxAfter));
      const inbox = await tx.get(inboxQuery);
      const grants = await tx.get(deviceRef.collection("contacts").where("enabled", "==", true).limit(8));
      const contacts = [];
      for (const grant of grants.docs) {
        const peer = db.doc(`watchInstallations/${watchID(grant.id)}`);
        const [installation, binding, reciprocal] = await tx.getAll(peer, peer.collection("signal").doc("binding"), peer.collection("contacts").doc(request.deviceID));
        if (installation.get("revokedAt") !== null || installation.get("expiresAt") <= Date.now() ||
            !binding.exists || binding.get("identityKey") !== grant.get("identityKey") || reciprocal.get("enabled") !== true) continue;
        contacts.push({deviceID: grant.id, ownerUid: installation.get("ownerUid"), bundle: binding.data(), name: grant.get("name") ?? ""});
      }
      const receipts = unique.length ? await tx.getAll(...unique.map(id => deviceRef.collection("commands").doc(id))) : [];
      return { version: 1, deviceID: request.deviceID, observedAt: Date.now(),
        contacts, sos: state.exists ? state.data() : { status: "idle" }, inbox: inbox.docs.map(doc => doc.data()),
        receipts: receipts.filter(doc => doc.exists).map(doc => doc.data()),
        missing: receipts.filter(doc => !doc.exists).map(doc => doc.id) };
    });
  }
  if (request.action === "shortMessage") return commitWatchCommand(db, encoded, signature, prepareWatchMessage);
  if (request.action === "ackMessage") return commitWatchCommand(db, encoded, signature, acknowledgeWatchMessage);
  return commitWatchCommand(db, encoded, signature, prepareWatchSOS);
}

export const independentWatchGateway = onRequest({ region: "europe-west1", cors: false, maxInstances: 10 }, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (process.env.WATCH_INDEPENDENT_GATEWAY !== "true") { res.status(503).json({ error: "unavailable" }); return; }
  if (req.method !== "POST") { res.status(405).json({ error: "method-not-allowed" }); return; }
  if (!req.is("application/json") || req.rawBody.length > 40_000) { res.status(400).json({ error: "invalid-request" }); return; }
  try { res.status(200).json({ result: await executeWatchRequest(req.body) }); }
  catch (error) {
    const known = error instanceof HttpsError;
    const code = known ? error.code : "internal";
    const status = code === "permission-denied" || code === "unauthenticated" ? 403 : code === "resource-exhausted" ? 429 : code === "internal" ? 500 : 400;
    res.status(status).json({ error: code });
  }
});
