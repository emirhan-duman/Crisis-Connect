import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { requireUid, requireNonAnonymous } from "../certificates/callerRole";
import { object, watchID } from "./deviceProtocol";

/** Exact account lookup only. The client must select a saved contact and explicitly approve
 * the returned device fingerprint. Discovery itself grants neither sending nor receiving. */
export const listIndependentContactDevices = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  requireNonAnonymous(request);
  const uid = requireUid(request), data = object(request.data);
  if (process.env.WATCH_INDEPENDENT_PROVISIONING !== "true") throw new HttpsError("failed-precondition", "Independent messaging is unavailable.");
  if (typeof data.peerUid !== "string" || !/^[A-Za-z0-9]{1,128}$/.test(data.peerUid) || data.peerUid === uid)
    throw new HttpsError("invalid-argument", "Select a contact.");
  const db = getFirestore();
  const devices = await db.collection("watchInstallations").where("ownerUid", "==", data.peerUid).limit(8).get();
  const result = [];
  for (const device of devices.docs) {
    if (device.get("revokedAt") !== null || device.get("expiresAt") <= Date.now()) continue;
    const binding = await device.ref.collection("signal").doc("binding").get();
    if (binding.exists) result.push({ deviceID: device.id, ownerUid: data.peerUid,
      kind: device.get("kind") ?? "watch", expiresAt: device.get("expiresAt"), bundle: binding.data() });
  }
  return { devices: result };
});

export const revokeIndependentWatchContact = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  requireNonAnonymous(request);
  const uid = requireUid(request), data = object(request.data), db = getFirestore();
  const device = db.doc(`watchInstallations/${watchID(data.deviceID)}`);
  return db.runTransaction(async tx => {
    const own = await tx.get(device);
    if (own.get("ownerUid") !== uid) throw new HttpsError("permission-denied", "Unknown device.");
    tx.set(device.collection("contacts").doc(watchID(data.peerInstallation)), { enabled: false }, { merge: true });
    return { revoked: true };
  });
});

/** Owner-only receipt reconciliation, never a send/retry endpoint. */
export const readIndependentWatchReceipts = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  const uid = requireUid(request), data = object(request.data), db = getFirestore();
  const device = db.doc(`watchInstallations/${watchID(data.deviceID)}`);
  if (!Array.isArray(data.ids) || data.ids.length > 32) throw new HttpsError("invalid-argument", "Invalid receipt list.");
  const ids = [...new Set(data.ids.map(watchID))];
  return db.runTransaction(async tx => {
    if ((await tx.get(device)).get("ownerUid") !== uid) throw new HttpsError("permission-denied", "Unknown device.");
    const rows = ids.length ? await tx.getAll(...ids.map(id => device.collection("commands").doc(id))) : [];
    return {receipts: rows.filter(row => row.exists).map(row => ({id:row.id,outcome:row.get("outcome")}))};
  });
});
