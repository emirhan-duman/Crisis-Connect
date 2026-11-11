import { createHash } from "node:crypto";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { haFirestore } from "./highAvailabilityStore";

interface Key { keyId: number; publicKey: string; signature?: string }
function base64(value: unknown, max: number): string {
  if (typeof value !== "string" || !value.length || value.length > max || !/^[A-Za-z0-9+/]+=*$/.test(value)) {
    throw new HttpsError("invalid-argument", "Invalid public key encoding.");
  }
  return value;
}
function key(value: unknown, max: number, signed: boolean): Key {
  const raw = (value ?? {}) as Record<string, unknown>;
  if (!Number.isInteger(raw.keyId) || Number(raw.keyId) < 0 || Number(raw.keyId) > 0xffffff) {
    throw new HttpsError("invalid-argument", "Invalid prekey identifier.");
  }
  return { keyId: Number(raw.keyId), publicKey: base64(raw.publicKey, max),
    ...(signed ? { signature: base64(raw.signature, 128) } : {}) };
}
const digest = (value: unknown) => createHash("sha256").update(JSON.stringify(value)).digest("hex");

/** New installations upload fresh, locally persisted keys; legacy one-time pools are NEVER copied. */
export async function publishHaPrekeys(uid: string, input: Record<string, unknown>) {
  if (!Number.isInteger(input.registrationId) || Number(input.registrationId) < 1 || Number(input.registrationId) > 0x3fff) {
    throw new HttpsError("invalid-argument", "Invalid Signal registration identifier.");
  }
  const identityKey = base64(input.identityKey, 128);
  const signedPreKey = key(input.signedPreKey, 128, true);
  const lastResortKyberPreKey = key(input.lastResortKyberPreKey, 4096, true);
  const ec = (Array.isArray(input.preKeys) ? input.preKeys : []).map(v => key(v, 128, false));
  const kyber = (Array.isArray(input.kyberPreKeys) ? input.kyberPreKeys : []).map(v => key(v, 4096, true));
  // The released managers use 100 + 100 keys. Two writes per key retain immutable ID ownership.
  if (ec.length > 200 || kyber.length > 100 || ec.length + kyber.length > 200 ||
      new Set(ec.map(k => k.keyId)).size !== ec.length || new Set(kyber.map(k => k.keyId)).size !== kyber.length) {
    throw new HttpsError("invalid-argument", "Upload at most 200 distinct one-time keys per batch.");
  }
  const db = haFirestore(), meta = db.doc(`signalPreKeys/${uid}`), generation = digest(identityKey);
  const owner = meta.collection("generations").doc(generation);
  const entries = [...ec.map(k => ({ key: k, pool: "ecPreKeys", ledger: "ecIdentifiers" })),
    ...kyber.map(k => ({ key: k, pool: "kyberPreKeys", ledger: "kyberIdentifiers" }))];
  const publication = meta.collection("publications").doc(digest({ identityKey, signedPreKey,
    lastResortKyberPreKey, ec, kyber, registrationId: input.registrationId }));
  return db.runTransaction(async tx => {
    const [current, previous, ...identifiers] = await tx.getAll(meta, publication,
      ...entries.map(e => owner.collection(e.ledger).doc(String(e.key.keyId))));
    const same = current.get("poolGeneration") === generation;
    const ecCount = same ? Number(current.get("ecCount") ?? 0) : 0;
    const kyberCount = same ? Number(current.get("kyberCount") ?? 0) : 0;
    if (previous.exists) return { ecCount, kyberCount, duplicate: true };
    let ecAdded = 0, kyberAdded = 0;
    for (let i = 0; i < entries.length; i++) {
      const e = entries[i], owned = identifiers[i];
      if (owned.exists) {
        if (owned.get("fingerprint") !== digest(e.key)) {
          throw new HttpsError("failed-precondition", "A prekey ID cannot be rebound to different key material.");
        }
        continue; // Already published, possibly consumed: never resurrect it.
      }
      if (e.pool === "ecPreKeys") ecAdded++; else kyberAdded++;
    }
    if (ecCount + ecAdded > 500 || kyberCount + kyberAdded > 500) {
      throw new HttpsError("resource-exhausted", "Prekey pool limit reached.");
    }
    for (let i = 0; i < entries.length; i++) {
      if (identifiers[i].exists) continue;
      const e = entries[i];
      tx.create(owner.collection(e.ledger).doc(String(e.key.keyId)), { fingerprint: digest(e.key) });
      tx.create(owner.collection(e.pool).doc(String(e.key.keyId)), e.key);
    }
    tx.set(meta, { identityKey, registrationId: input.registrationId, deviceId: 1, signedPreKey,
      lastResortKyberPreKey, poolGeneration: generation, ecCount: ecCount + ecAdded,
      kyberCount: kyberCount + kyberAdded, updatedAt: FieldValue.serverTimestamp() });
    tx.create(publication, { generation, createdAt: FieldValue.serverTimestamp() });
    return { ecCount: ecCount + ecAdded, kyberCount: kyberCount + kyberAdded };
  });
}

export async function checkHaPrekeys(uid: string) {
  const meta = await haFirestore().doc(`signalPreKeys/${uid}`).get();
  if (!meta.exists) return { published: false, ecCount: 0, kyberCount: 0 };
  return { published: true, ecCount: Number(meta.get("ecCount") ?? 0), kyberCount: Number(meta.get("kyberCount") ?? 0),
    signedPreKeyId: meta.get("signedPreKey.keyId"), updatedAtMs: meta.get("updatedAt")?.toMillis?.() ?? null };
}

/** Legacy-target and HA-target fetches charge the same per-caller daily allowance. */
export async function reserveHaPrekeyBudget(caller: string): Promise<void> {
  const db = haFirestore(), ref = db.doc(`signalBundleThrottle/${caller}`);
  const day = new Date().toISOString().slice(0, 10);
  await db.runTransaction(async tx => {
    const previous = await tx.get(ref);
    const count = previous.get("dayKey") === day ? Number(previous.get("count") ?? 0) : 0;
    if (count >= 200) throw new HttpsError("resource-exhausted", "Daily prekey bundle limit reached.");
    tx.set(ref, { dayKey: day, count: count + 1, updatedAt: FieldValue.serverTimestamp() });
  });
}

export async function fetchHaPrekeys(caller: string, target: string) {
  if (typeof target !== "string" || !/^[A-Za-z0-9]{1,128}$/.test(target) || target === caller) {
    throw new HttpsError("invalid-argument", "Invalid prekey recipient.");
  }
  const db = haFirestore(), meta = db.doc(`signalPreKeys/${target}`);
  const throttle = db.doc(`signalBundleThrottle/${caller}`), day = new Date().toISOString().slice(0, 10);
  return db.runTransaction(async tx => {
    const [state, quota] = await tx.getAll(meta, throttle);
    if (!state.exists) throw new HttpsError("not-found", "Target has not published Signal prekeys.");
    const count = quota.get("dayKey") === day ? Number(quota.get("count") ?? 0) : 0;
    if (count >= 200) throw new HttpsError("resource-exhausted", "Daily prekey bundle limit reached.");
    const generation = state.get("poolGeneration");
    if (typeof generation !== "string" || !/^[a-f0-9]{64}$/.test(generation)) {
      throw new HttpsError("failed-precondition", "Fresh prekey publication required.");
    }
    const owner = meta.collection("generations").doc(generation);
    const ec = await tx.get(owner.collection("ecPreKeys").limit(1));
    const kyber = await tx.get(owner.collection("kyberPreKeys").limit(1));
    tx.set(throttle, { dayKey: day, count: count + 1, updatedAt: FieldValue.serverTimestamp() });
    if (!ec.empty) tx.delete(ec.docs[0].ref);
    if (!kyber.empty) tx.delete(kyber.docs[0].ref);
    tx.update(meta, { ecCount: Math.max(0, Number(state.get("ecCount") ?? 0) - ec.size),
      kyberCount: Math.max(0, Number(state.get("kyberCount") ?? 0) - kyber.size) });
    return { registrationId: state.get("registrationId"), deviceId: 1, identityKey: state.get("identityKey"),
      signedPreKey: state.get("signedPreKey"), preKey: ec.empty ? null : ec.docs[0].data(),
      kyberPreKey: kyber.empty ? { ...state.get("lastResortKyberPreKey"), lastResort: true }
        : { ...kyber.docs[0].data(), lastResort: false } };
  });
}
