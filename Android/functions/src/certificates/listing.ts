import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { requireUid, resolveCallerRoleKey, MANAGER_ROLE_KEYS } from "./callerRole";

const MAX_LIMIT = 100;

interface RawListInput {
  scope?: unknown;
  limit?: unknown;
  statusFilter?: unknown;
  search?: unknown;
}

interface CertificateSummary {
  certId: string;
  ownerUid: string;
  deviceId: string;
  role: string;
  status: string;
  issuedAtMs: number;
  expiresAtMs: number;
  revokedAt?: number | null;
  revokedReason?: string | null;
  revokedByUid?: string | null;
  attestationSecurityLevel?: string | null;
  publicKeyPreview?: string | null;
}

function summarize(docId: string, raw: Record<string, unknown>): CertificateSummary {
  const publicKey = typeof raw.publicKeyBase64 === "string" ? raw.publicKeyBase64 : null;
  const preview = publicKey
    ? `${publicKey.slice(0, 16)}…${publicKey.slice(-8)}`
    : null;
  const revokedAt = raw.revokedAt as { toMillis?: () => number } | undefined;
  return {
    certId: docId,
    ownerUid: typeof raw.ownerUid === "string" ? raw.ownerUid : "",
    deviceId: typeof raw.deviceId === "string" ? raw.deviceId : "",
    role: typeof raw.role === "string" ? raw.role : "",
    status: typeof raw.status === "string" ? raw.status : "",
    issuedAtMs: typeof raw.issuedAtMs === "number" ? raw.issuedAtMs : 0,
    expiresAtMs: typeof raw.expiresAtMs === "number" ? raw.expiresAtMs : 0,
    revokedAt: revokedAt?.toMillis ? revokedAt.toMillis() : null,
    revokedReason: typeof raw.revokedReason === "string" ? raw.revokedReason : null,
    revokedByUid: typeof raw.revokedByUid === "string" ? raw.revokedByUid : null,
    attestationSecurityLevel:
      typeof raw.attestationSecurityLevel === "string"
        ? raw.attestationSecurityLevel
        : null,
    publicKeyPreview: preview,
  };
}

export const listCertificates = onCall(
  // Certificate inventory is available only to authenticated, verified app clients.
  { enforceAppCheck: true },
  async (request) => {
    const callerUid = requireUid(request);
    const body = (request.data ?? {}) as RawListInput;
    const scope = typeof body.scope === "string" ? body.scope : "self";
    const statusFilter = typeof body.statusFilter === "string" ? body.statusFilter : null;
    const limitInput = typeof body.limit === "number" ? body.limit : 50;
    const limit = Math.max(1, Math.min(MAX_LIMIT, Math.floor(limitInput)));
    const search = typeof body.search === "string" ? body.search.trim() : "";

    const db = getFirestore();

    if (scope === "self") {
      const snap = await db.doc(`certificates/cert_${callerUid}`).get();
      if (!snap.exists) {
        return { scope: "self", items: [], hasMore: false };
      }
      return {
        scope: "self",
        items: [summarize(snap.id, snap.data() as Record<string, unknown>)],
        hasMore: false,
      };
    }

    if (scope !== "all") {
      throw new HttpsError("invalid-argument", "scope must be 'self' or 'all'.");
    }

    const callerRole = await resolveCallerRoleKey(request, callerUid);
    if (!MANAGER_ROLE_KEYS.has(callerRole)) {
      throw new HttpsError(
        "permission-denied",
        "Only admin or authority roles may list all certificates."
      );
    }

    let query = db
      .collection("certificates")
      .orderBy("issuedAtMs", "desc")
      .limit(limit + 1);
    if (statusFilter === "active" || statusFilter === "revoked") {
      query = db
        .collection("certificates")
        .where("status", "==", statusFilter)
        .orderBy("issuedAtMs", "desc")
        .limit(limit + 1);
    }

    const snap = await query.get();
    const docs = snap.docs;
    const trimmed = docs.slice(0, limit);
    const hasMore = docs.length > limit;
    let items = trimmed.map((doc) => summarize(doc.id, doc.data() as Record<string, unknown>));

    if (search) {
      const needle = search.toLowerCase();
      items = items.filter(
        (item) =>
          item.ownerUid.toLowerCase().includes(needle) ||
          item.deviceId.toLowerCase().includes(needle) ||
          item.role.toLowerCase().includes(needle)
      );
    }

    try {
      await db.collection("auditLogs").add({
        type: "CERTIFICATE_LIST_VIEWED",
        event: "CERTIFICATE_LIST_VIEWED",
        actorUid: callerUid,
        details: `scope=all returned=${items.length} statusFilter=${statusFilter ?? "any"}`,
        createdAt: FieldValue.serverTimestamp(),
      });
    } catch (error) {
      console.warn("Audit log write failed (cert list)", error);
    }

    return { scope: "all", items, hasMore };
  }
);

interface RawValidateInput {
  certId?: unknown;
}

export const validateCertificate = onCall(
  { enforceAppCheck: true },
  async (request) => {
    const callerUid = requireUid(request);
    const body = (request.data ?? {}) as RawValidateInput;
    const certId = typeof body.certId === "string" && body.certId.trim() !== ""
      ? body.certId.trim()
      : `cert_${callerUid}`;

    const snap = await getFirestore().doc(`certificates/${certId}`).get();
    if (!snap.exists) {
      return { certId, status: "missing", knownToServer: false };
    }
    const data = snap.data() as Record<string, unknown>;
    const ownerUid = typeof data.ownerUid === "string" ? data.ownerUid : "";
    if (ownerUid !== callerUid) {
      throw new HttpsError(
        "permission-denied",
        "You may only validate your own certificate."
      );
    }
    const status = typeof data.status === "string" ? data.status : "unknown";
    const expiresAtMs = typeof data.expiresAtMs === "number" ? data.expiresAtMs : 0;
    const nowMs = Date.now();
    return {
      certId,
      ownerUid,
      knownToServer: true,
      status,
      expiresAtMs,
      revokedReason: typeof data.revokedReason === "string" ? data.revokedReason : null,
      isExpired: expiresAtMs > 0 && expiresAtMs < nowMs,
      stillValid: status === "active" && expiresAtMs > nowMs,
    };
  }
);
