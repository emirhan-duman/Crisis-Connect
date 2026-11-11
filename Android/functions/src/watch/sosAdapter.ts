import { createHash } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { getFirestore, Transaction } from "firebase-admin/firestore";
import { prepareSosSignalReport } from "../sos/reportSignal";
import { object, watchID, WatchRegistration, WatchRequest } from "./deviceProtocol";
import { PreparedWatchEffect } from "./commandJournal";

export async function prepareWatchSOS(tx: Transaction, registration: WatchRegistration, request: WatchRequest, now = Date.now()): Promise<PreparedWatchEffect> {
  if (request.action !== "startSOS" && request.action !== "stopSOS") throw new HttpsError("invalid-argument", "Not an SOS command.");
  const body = object(request.body), sessionID = watchID(body.sessionID);
  const db = getFirestore(), currentRef = db.doc(`watchSOS/${registration.ownerUid}`);
  const sessionRef = currentRef.collection("sessions").doc(sessionID);
  const [current, session] = await tx.getAll(currentRef, sessionRef);
  const signalId = "cc-" + createHash("sha256").update(`${registration.ownerUid}\n${registration.deviceID}\n${sessionID}`).digest("hex").slice(0,24);
  if (request.action === "startSOS") {
    // A stopped/previous session is a tombstone: a new request ID cannot resurrect it.
    if (session.exists || current.get("status") === "active") throw new HttpsError("failed-precondition", "Resolve the existing SOS first.");
    const at = body.capturedAtMillis;
    if (typeof at !== "number" || !Number.isSafeInteger(at) || at > now + 5000 || now - at > 120_000)
      throw new HttpsError("invalid-argument", "Fresh watch location is required.");
    if (typeof body.accuracyMeters !== "number" || !Number.isFinite(body.accuracyMeters) || body.accuracyMeters <= 0 || body.accuracyMeters > 10_000)
      throw new HttpsError("invalid-argument", "Watch location accuracy is required.");
  } else if (!session.exists || session.get("deviceID") !== registration.deviceID || session.get("status") !== "active" || current.get("sessionID") !== sessionID) {
    throw new HttpsError("failed-precondition", "The active SOS session has changed.");
  }
  const status = request.action === "startSOS" ? "active" : "resolved";
  const report = await prepareSosSignalReport(tx, registration.ownerUid,
    { ...body, signalId, status, provider: "watch" }, now);
  const state = { sessionID, signalId, deviceID: registration.deviceID, status,
    startedAt: session.get("startedAt") ?? now, updatedAt: now, panelId: report.result.panelId };
  return {
    outcome: "accepted",
    details: { ...state, ...report.result },
    write(transaction) {
      report.write();
      transaction.set(currentRef, state);
      transaction.set(sessionRef, state);
    }
  };
}
