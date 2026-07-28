import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue, Transaction } from "firebase-admin/firestore";
import { requireUid } from "../certificates/callerRole";

/**
 * X3DH/PQXDH prekey distribution for the Signal-protocol (v3) messaging layer.
 *
 * Layout (all server-only in Firestore rules; the only way in is these callables):
 *   signalPreKeys/{uid}                     — meta: registrationId, identityKey, signedPreKey,
 *                                             lastResortKyberPreKey (never consumed).
 *   signalPreKeys/{uid}/ecPreKeys/{keyId}   — one-time EC prekeys, deleted as they're handed out.
 *   signalPreKeys/{uid}/kyberPreKeys/{keyId}— one-time Kyber prekeys, deleted as handed out;
 *                                             when the pool is dry the bundle falls back to the
 *                                             last-resort Kyber prekey (Signal's PQXDH design).
 *
 * Rotation is CLIENT-driven: the app re-publishes a fresh signed prekey (~30 days) and tops up
 * one-time pools when checkSignalPreKeys reports them low. Publishing with a NEW identityKey
 * (reinstall) wipes the old pools — they belong to the dead identity.
 */

const B64_REGEX = /^[A-Za-z0-9+/]+=*$/;
const MAX_KEY_ID = 0xffffff; // libsignal Medium.MAX_VALUE
const MAX_EC_PREKEYS_PER_CALL = 200;
const MAX_KYBER_PREKEYS_PER_CALL = 100;
const MAX_POOL = 500; // hard cap per pool; publishes beyond this are rejected
const DAILY_BUNDLE_FETCH_CAP = 200; // new conversations per caller per UTC day

interface PublicKeyRecord {
  keyId: number;
  publicKey: string;
  signature?: string;
}

function b64Field(value: unknown, field: string, maxChars: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxChars || !B64_REGEX.test(value)) {
    throw new HttpsError("invalid-argument", `${field} must be non-empty base64 (≤${maxChars} chars).`);
  }
  return value;
}

function keyIdField(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0 || value > MAX_KEY_ID) {
    throw new HttpsError("invalid-argument", `${field} must be an integer in [0, ${MAX_KEY_ID}].`);
  }
  return value;
}

/** {keyId, publicKey[, signature]} — EC pubs are ~44 b64 chars, Kyber-1024 pubs ~2.1k. */
function keyRecord(raw: unknown, field: string, maxPubChars: number, requireSignature: boolean): PublicKeyRecord {
  const data = (raw ?? {}) as Record<string, unknown>;
  const record: PublicKeyRecord = {
    keyId: keyIdField(data.keyId, `${field}.keyId`),
    publicKey: b64Field(data.publicKey, `${field}.publicKey`, maxPubChars),
  };
  if (requireSignature) {
    record.signature = b64Field(data.signature, `${field}.signature`, 128);
  }
  return record;
}

const metaRef = (uid: string) => getFirestore().doc(`signalPreKeys/${uid}`);
const ecPool = (uid: string) => metaRef(uid).collection("ecPreKeys");
const kyberPool = (uid: string) => metaRef(uid).collection("kyberPreKeys");

async function deletePool(pool: FirebaseFirestore.CollectionReference): Promise<void> {
  const db = getFirestore();
  // Pools are capped at MAX_POOL docs, so a paged batch delete is plenty.
  for (;;) {
    const snap = await pool.limit(400).get();
    if (snap.empty) return;
    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
}

/**
 * Publish/refresh the caller's Signal prekey state. Meta fields are replaced wholesale;
 * one-time keys are ADDED to the pools (same keyId overwrites). Returns pool counts so the
 * client can decide whether to top up further.
 */
export const publishSignalPreKeys = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const db = getFirestore();

    const registrationId = data.registrationId;
    if (
      typeof registrationId !== "number" ||
      !Number.isInteger(registrationId) ||
      registrationId < 1 ||
      registrationId > 0x3fff
    ) {
      throw new HttpsError("invalid-argument", "registrationId must be an integer in [1, 16383].");
    }
    const identityKey = b64Field(data.identityKey, "identityKey", 128);
    const signedPreKey = keyRecord(data.signedPreKey, "signedPreKey", 128, true);
    const lastResortKyberPreKey = keyRecord(data.lastResortKyberPreKey, "lastResortKyberPreKey", 4096, true);

    const rawEc = Array.isArray(data.preKeys) ? data.preKeys : [];
    if (rawEc.length > MAX_EC_PREKEYS_PER_CALL) {
      throw new HttpsError("invalid-argument", `Too many preKeys; max ${MAX_EC_PREKEYS_PER_CALL} per call.`);
    }
    const ecPreKeys = rawEc.map((k, i) => keyRecord(k, `preKeys[${i}]`, 128, false));

    const rawKyber = Array.isArray(data.kyberPreKeys) ? data.kyberPreKeys : [];
    if (rawKyber.length > MAX_KYBER_PREKEYS_PER_CALL) {
      throw new HttpsError("invalid-argument", `Too many kyberPreKeys; max ${MAX_KYBER_PREKEYS_PER_CALL} per call.`);
    }
    const kyberPreKeys = rawKyber.map((k, i) => keyRecord(k, `kyberPreKeys[${i}]`, 4096, true));

    // Reinstall / identity rotation: the old pools belong to the dead identity — wipe them.
    const existing = await metaRef(uid).get();
    const identityChanged = existing.exists && existing.data()?.identityKey !== identityKey;
    if (identityChanged) {
      await deletePool(ecPool(uid));
      await deletePool(kyberPool(uid));
    }

    // Enforce the pool cap against what's already stored (post-wipe when identity changed).
    const [ecBefore, kyberBefore] = await Promise.all([
      ecPool(uid).count().get(),
      kyberPool(uid).count().get(),
    ]);
    if (ecBefore.data().count + ecPreKeys.length > MAX_POOL) {
      throw new HttpsError("resource-exhausted", `ecPreKeys pool would exceed ${MAX_POOL}.`);
    }
    if (kyberBefore.data().count + kyberPreKeys.length > MAX_POOL) {
      throw new HttpsError("resource-exhausted", `kyberPreKeys pool would exceed ${MAX_POOL}.`);
    }

    await metaRef(uid).set({
      registrationId,
      deviceId: 1,
      identityKey,
      signedPreKey,
      lastResortKyberPreKey,
      updatedAt: FieldValue.serverTimestamp(),
    });

    // Chunked batch writes (Firestore batches cap at 500 ops).
    const writes = [
      ...ecPreKeys.map((k) => ({ ref: ecPool(uid).doc(String(k.keyId)), data: { publicKey: k.publicKey } })),
      ...kyberPreKeys.map((k) => ({
        ref: kyberPool(uid).doc(String(k.keyId)),
        data: { publicKey: k.publicKey, signature: k.signature },
      })),
    ];
    for (let i = 0; i < writes.length; i += 400) {
      const batch = db.batch();
      writes.slice(i, i + 400).forEach((w) => batch.set(w.ref, w.data));
      await batch.commit();
    }

    return {
      ecCount: ecBefore.data().count + ecPreKeys.length,
      kyberCount: kyberBefore.data().count + kyberPreKeys.length,
    };
  }
);

/** Cheap own-pool inventory so the client can decide to top up (<20 → publish 100 more). */
export const checkSignalPreKeys = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const meta = await metaRef(uid).get();
    if (!meta.exists) {
      return { published: false, ecCount: 0, kyberCount: 0 };
    }
    const [ec, kyber] = await Promise.all([ecPool(uid).count().get(), kyberPool(uid).count().get()]);
    return {
      published: true,
      ecCount: ec.data().count,
      kyberCount: kyber.data().count,
      signedPreKeyId: meta.data()?.signedPreKey?.keyId ?? null,
      updatedAtMs: meta.data()?.updatedAt?.toMillis?.() ?? null,
    };
  }
);

/**
 * Hand out a PQXDH prekey bundle for {targetUid}, transactionally CONSUMING one one-time EC
 * prekey and one one-time Kyber prekey (falling back to the last-resort Kyber, and to a
 * signed-prekey-only bundle, when the pools are dry — X3DH permits both). `not-found` means the
 * target has never published Signal prekeys → the caller falls back to the v2 envelope.
 */
export const fetchSignalPreKeyBundle = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const targetUid = data.targetUid;
    if (typeof targetUid !== "string" || targetUid.length === 0 || targetUid.length > 128) {
      throw new HttpsError("invalid-argument", "targetUid must be a non-empty string.");
    }
    if (targetUid === uid) {
      throw new HttpsError("invalid-argument", "Cannot fetch a bundle for yourself.");
    }
    const db = getFirestore();

    // Daily per-caller cap: each NEW conversation costs one fetch, so a generous cap stops
    // pool-draining abuse (forcing victims onto last-resort keys) without touching real use.
    const throttleRef = db.doc(`signalBundleThrottle/${uid}`);
    const dayKey = new Date().toISOString().slice(0, 10);
    const throttleSnap = await throttleRef.get();
    const t = throttleSnap.data() ?? {};
    const usedToday = t.dayKey === dayKey && typeof t.count === "number" ? t.count : 0;
    if (usedToday >= DAILY_BUNDLE_FETCH_CAP) {
      throw new HttpsError("resource-exhausted", "Daily prekey bundle limit reached; try again tomorrow.");
    }
    await throttleRef.set({ dayKey, count: usedToday + 1, updatedAt: FieldValue.serverTimestamp() });

    return db.runTransaction(async (tx: Transaction) => {
      const meta = await tx.get(metaRef(targetUid));
      if (!meta.exists) {
        throw new HttpsError("not-found", "Target has not published Signal prekeys.");
      }
      const m = meta.data() as Record<string, unknown>;

      const ecSnap = await tx.get(ecPool(targetUid).limit(1));
      const kyberSnap = await tx.get(kyberPool(targetUid).limit(1));

      let preKey: { keyId: number; publicKey: string } | null = null;
      if (!ecSnap.empty) {
        const doc = ecSnap.docs[0];
        preKey = { keyId: Number(doc.id), publicKey: doc.data().publicKey as string };
        tx.delete(doc.ref);
      }

      let kyberPreKey: { keyId: number; publicKey: string; signature: string; lastResort: boolean };
      if (!kyberSnap.empty) {
        const doc = kyberSnap.docs[0];
        kyberPreKey = {
          keyId: Number(doc.id),
          publicKey: doc.data().publicKey as string,
          signature: doc.data().signature as string,
          lastResort: false,
        };
        tx.delete(doc.ref);
      } else {
        const lastResort = m.lastResortKyberPreKey as PublicKeyRecord;
        kyberPreKey = {
          keyId: lastResort.keyId,
          publicKey: lastResort.publicKey,
          signature: lastResort.signature as string,
          lastResort: true,
        };
      }

      return {
        registrationId: m.registrationId,
        deviceId: m.deviceId ?? 1,
        identityKey: m.identityKey,
        signedPreKey: m.signedPreKey,
        preKey,
        kyberPreKey,
      };
    });
  }
);
