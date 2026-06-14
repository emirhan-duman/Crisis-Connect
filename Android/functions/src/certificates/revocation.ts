import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import {
  requireUid,
  resolveCallerRoleKey,
  MANAGER_ROLE_KEYS,
  CERT_ELIGIBLE_ROLE_KEYS,
} from "./callerRole";

const MAX_REASON_LEN = 280;

interface RawRevokeInput {
  targetUid?: unknown;
  reason?: unknown;
}

interface ValidatedRevokeInput {
  targetUid: string;
  reason: string;
  isAdminAction: boolean;
}

function validateInput(
  raw: RawRevokeInput | undefined | null,
  callerUid: string,
  callerRole: string
): ValidatedRevokeInput {
  const body = (raw ?? {}) as RawRevokeInput;
  const reasonRaw = typeof body.reason === "string" ? body.reason.trim() : "";
  const reason = reasonRaw.slice(0, MAX_REASON_LEN);

  let targetUid: string;
  let isAdminAction = false;
  if (typeof body.targetUid === "string" && body.targetUid.trim() !== "") {
    targetUid = body.targetUid.trim();
    if (targetUid !== callerUid) {
      if (!MANAGER_ROLE_KEYS.has(callerRole)) {
        throw new HttpsError(
          "permission-denied",
          "Only admin or authority roles may revoke certificates for other users."
        );
      }
      isAdminAction = true;
    }
  } else {
    if (!CERT_ELIGIBLE_ROLE_KEYS.has(callerRole)) {
      throw new HttpsError(
        "permission-denied",
        "Only admin or fieldteam roles may revoke their own certificate."
      );
    }
    targetUid = callerUid;
  }
  return { targetUid, reason, isAdminAction };
}

export const revokeRoleCertificate = onCall(
  // Revocation requires Firebase Auth plus a verified app client.
  { enforceAppCheck: true },
  async (request) => {
    const callerUid = requireUid(request);
    const callerRole = await resolveCallerRoleKey(request, callerUid);
    const input = validateInput(
      request.data as RawRevokeInput | undefined,
      callerUid,
      callerRole
    );
    const db = getFirestore();
    const certId = `cert_${input.targetUid}`;
    const ref = db.doc(`certificates/${certId}`);

    let revokedNow = false;
    let priorDeviceId: string | null = null;
    let priorStatus: string | null = null;
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) {
        throw new HttpsError(
          "not-found",
          "No certificate exists for this user."
        );
      }
      const data = snap.data() as { status?: string; deviceId?: string } | undefined;
      priorStatus = data?.status ?? null;
      priorDeviceId = data?.deviceId ?? null;
      if (priorStatus === "revoked") {
        return;
      }
      tx.update(ref, {
        status: "revoked",
        revokedAt: FieldValue.serverTimestamp(),
        revokedByUid: callerUid,
        revokedReason: input.reason || null,
      });
      revokedNow = true;
    });

    if (revokedNow) {
      try {
        await db.collection("auditLogs").add({
          type: input.isAdminAction
            ? "CERTIFICATE_REVOKED"
            : "CERTIFICATE_REVOKED_SELF",
          event: input.isAdminAction
            ? "CERTIFICATE_REVOKED"
            : "CERTIFICATE_REVOKED_SELF",
          actorUid: callerUid,
          targetUid: input.targetUid,
          details: `deviceId=${priorDeviceId ?? "unknown"} reason=${input.reason || "(none)"}`,
          createdAt: FieldValue.serverTimestamp(),
        });
      } catch (error) {
        console.warn("Audit log write failed (revocation)", error);
      }
    }

    return {
      certId,
      ownerUid: input.targetUid,
      status: "revoked",
      revokedNow,
      priorStatus,
    };
  }
);
