import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { requireUid, requireNonAnonymous } from "../certificates/callerRole";
import { WATCH_LEASE_MS, WATCH_SCOPES, enrollmentPayload, object, verifyWatchSignature, watchID, watchPublicKey, WatchRegistration } from "./deviceProtocol";

/** Separate namespace: never replaces messagingKeys/{uid} or signalPreKeys/{uid}. */
export const enrollIndependentWatch = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  requireNonAnonymous(request);
  const uid = requireUid(request);
  // Provisioning is disabled until both receiving clients support independent device identities.
  if (process.env.WATCH_INDEPENDENT_PROVISIONING !== "true")
    throw new HttpsError("failed-precondition", "Independent watch provisioning is not released.");
  const data = object(request.data), deviceID = watchID(data.deviceID), publicKey = watchPublicKey(data.publicKey);
  if (data.confirmed !== true) throw new HttpsError("failed-precondition", "Explicit phone approval is required.");
  const proof = enrollmentPayload(uid, deviceID, publicKey);
  verifyWatchSignature(publicKey, proof, data.signature);
  const db = getFirestore(), account = db.doc(`watchAccounts/${uid}`), device = db.doc(`watchInstallations/${deviceID}`);
  return db.runTransaction(async tx => {
    const [current, owner] = await tx.getAll(device, account);
    const old = current.data() as WatchRegistration | undefined;
    if (old) {
      if (old.ownerUid !== uid || old.publicKey !== publicKey || old.revokedAt !== null || old.expiresAt <= Date.now())
        throw new HttpsError("permission-denied", "Installation cannot be reassigned or resurrected.");
      return { deviceID, expiresAt: old.expiresAt, scopes: old.scopes, signalDeviceId: current.get("signalDeviceId") };
    }
    const ids: string[] = owner.get("deviceIDs") ?? [];
    if (ids.length >= 8) throw new HttpsError("resource-exhausted", "Revoke an existing watch first.");
    const signalDeviceId = Number(owner.get("nextSignalDeviceId") ?? 2);
    if(!Number.isInteger(signalDeviceId) || signalDeviceId > 127)throw new HttpsError("resource-exhausted", "Signal device indices exhausted.");
    const now = Date.now();
    const registration: WatchRegistration = { ownerUid: uid, deviceID, publicKey, scopes: [...WATCH_SCOPES], createdAt: now, expiresAt: now + WATCH_LEASE_MS, revokedAt: null };
    const kind = data.kind === "phoneInbox" ? "phoneInbox" : "watch";
    // Phone chat mailboxes can never start SOS, even if their signing key is reused by a caller.
    if (kind === "phoneInbox") registration.scopes = ["shortMessage"];
    tx.create(device, { ...registration, signalDeviceId, kind });
    tx.set(account, { deviceIDs: [...ids, deviceID], nextSignalDeviceId: signalDeviceId + 1 }, { merge: true });
    return { deviceID, expiresAt: registration.expiresAt, scopes: registration.scopes, signalDeviceId };
  });
});

export const revokeIndependentWatch = onCall({ region: "europe-west1", enforceAppCheck: true }, async request => {
  requireNonAnonymous(request);
  const uid = requireUid(request), deviceID = watchID(object(request.data).deviceID), db = getFirestore();
  const device = db.doc(`watchInstallations/${deviceID}`), account = db.doc(`watchAccounts/${uid}`);
  return db.runTransaction(async tx => {
    const [snapshot, owner] = await tx.getAll(device, account);
    if (!snapshot.exists || snapshot.get("ownerUid") !== uid) throw new HttpsError("permission-denied", "Unknown watch.");
    tx.update(device, { revokedAt: snapshot.get("revokedAt") ?? Date.now() });
    tx.set(account, { deviceIDs: (owner.get("deviceIDs") as string[] ?? []).filter(id => id !== deviceID) }, { merge: true });
    // The permanent tombstone prevents replay of an old enrollment proof.
    return { revoked: true, deviceID };
  });
});
