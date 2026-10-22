import { HttpsError } from "firebase-functions/v2/https";
import { Firestore, Transaction } from "firebase-admin/firestore";
import { authorizeWatch, parseWatchRequest, requestDigest, verifyWatchSignature, WatchRegistration, WatchRequest } from "./deviceProtocol";

export type WatchOutcome = "accepted" | "rejected" | "delivered" | "read";
export interface PreparedWatchEffect {
  outcome: WatchOutcome;
  details?: Record<string, unknown>;
  // The adapter must do all reads before returning. Effects and receipt commit atomically.
  write(transaction: Transaction): void;
}
export interface WatchReceipt { id: string; digest: string; outcome: WatchOutcome; recordedAt: number; details?: Record<string, unknown> }

/** Internal only. No public command route until encrypted recipient and SOS adapters exist. */
export async function commitWatchCommand(db: Firestore, encoded: string, signature: string,
  prepare: (tx: Transaction, registration: WatchRegistration, request: WatchRequest) => Promise<PreparedWatchEffect>,
  now = Date.now()): Promise<WatchReceipt> {
  const { request } = parseWatchRequest(encoded, now);
  if (request.action === "status") throw new HttpsError("invalid-argument", "Status must not create an effect.");
  const device = db.doc(`watchInstallations/${request.deviceID}`);
  const receipt = device.collection("commands").doc(request.id);
  const digest = requestDigest(encoded);
  return db.runTransaction(async tx => {
    const [installation, previous] = await tx.getAll(device, receipt);
    // Revocation/expiry applies even to duplicate commands; never authorize from cached state.
    const checkedAt = Math.max(now, Date.now());
    parseWatchRequest(encoded, checkedAt);
    const registration = authorizeWatch(installation.data() as WatchRegistration | undefined, request, checkedAt);
    verifyWatchSignature(registration.publicKey, encoded, signature);
    if (previous.exists) {
      if (previous.get("digest") !== digest) throw new HttpsError("already-exists", "Command ID was used for a different request.");
      return previous.data() as WatchReceipt;
    }
    const effect = await prepare(tx, registration, request);
    const result: WatchReceipt = { id: request.id, digest, outcome: effect.outcome, recordedAt: now, ...(effect.details ? { details: effect.details } : {}) };
    effect.write(tx);
    tx.create(receipt, result);
    return result;
  });
}
