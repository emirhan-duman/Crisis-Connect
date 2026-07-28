import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { requireUid, requireNonAnonymous } from "../certificates/callerRole";
import { hashIdentifier, normalizePhone } from "./identityKeys";

/**
 * WhatsApp/Signal-style contact discovery, but privacy-first: it only ever returns users who
 * OPTED INTO discovery (they published a phone hash via publishIdentityKey). The client sends
 * E.164 numbers from the address book; the server hashes them, matches against the directory,
 * returns only the hits (uid + identity public key) and NEVER stores the queried numbers.
 * A per-user throttle blunts repeated directory-scraping attempts.
 */

const MAX_PHONES = 1500;
const MIN_SCAN_INTERVAL_MS = 2000;
// Daily per-user cap on QUERIED (normalized, deduped) numbers. A real address book re-synced a
// handful of times a day stays far below this; a scraper goes from ~65M lookups/day (2s interval
// alone) to 15k/day per phone-verified account, which makes enumeration uneconomical.
const DAILY_PHONE_CAP = 15000;

interface ContactMatch {
  phone: string;
  uid: string;
  publicKey: string;
  keyId: string;
  alg: string;
  displayName: string;
  photoUrl: string;
  isChild: boolean;
}

export const findContactsOnCrisisConnect = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    // Anonymous (QR-only) devices may not bulk-scan the phone directory: their UIDs are free and
    // unlimited, which would defeat the per-UID throttle below and enable enumeration.
    requireNonAnonymous(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const rawPhones = data.phones;
    if (!Array.isArray(rawPhones)) {
      throw new HttpsError("invalid-argument", "phones must be an array.");
    }
    if (rawPhones.length > MAX_PHONES) {
      throw new HttpsError("invalid-argument", `Too many numbers; max ${MAX_PHONES}.`);
    }

    const db = getFirestore();

    // Lightweight per-user throttle (best-effort; not a transaction).
    const throttleRef = db.doc(`messagingScanThrottle/${uid}`);
    const now = Date.now();
    const throttleData = (await throttleRef.get()).data() ?? {};
    const lastAt = throttleData.lastScanAtMs as number | undefined;
    if (typeof lastAt === "number" && now - lastAt < MIN_SCAN_INTERVAL_MS) {
      throw new HttpsError("resource-exhausted", "Please wait before scanning again.");
    }

    // Normalize + hash each number. The raw query set is never persisted.
    const hashToPhone = new Map<string, string>();
    for (const raw of rawPhones) {
      const phone = normalizePhone(raw);
      if (!phone) continue;
      const hash = hashIdentifier("phone", phone);
      if (!hashToPhone.has(hash)) hashToPhone.set(hash, phone);
    }
    if (hashToPhone.size === 0) return { matches: [] };

    // Rolling daily cap on queried numbers (UTC day). Counted AFTER dedup/normalization so only
    // numbers that actually hit the directory consume quota.
    const dayKey = new Date(now).toISOString().slice(0, 10);
    const scannedToday =
      throttleData.dayKey === dayKey && typeof throttleData.phonesToday === "number"
        ? throttleData.phonesToday
        : 0;
    if (scannedToday + hashToPhone.size > DAILY_PHONE_CAP) {
      throw new HttpsError("resource-exhausted", "Daily contact scan limit reached; try again tomorrow.");
    }
    await throttleRef.set({
      lastScanAtMs: now,
      dayKey,
      phonesToday: scannedToday + hashToPhone.size,
      updatedAt: FieldValue.serverTimestamp(),
    });

    const hashes = [...hashToPhone.keys()];
    const dirSnaps = await db.getAll(...hashes.map((h) => db.doc(`messagingDirectory/${h}`)));
    const hashToUid = new Map<string, string>();
    dirSnaps.forEach((snap, i) => {
      const targetUid = snap.data()?.uid;
      if (typeof targetUid === "string" && targetUid !== uid) {
        hashToUid.set(hashes[i], targetUid);
      }
    });
    if (hashToUid.size === 0) return { matches: [] };

    const uids = [...new Set(hashToUid.values())];
    const keySnaps = await db.getAll(...uids.map((u) => db.doc(`messagingKeys/${u}`)));
    const uidToKey = new Map<
      string,
      {
        publicKey: string;
        keyId: string;
        alg: string;
        displayName: string;
        photoUrl: string;
        isChild: boolean;
      }
    >();
    for (const snap of keySnaps) {
      const d = snap.data();
      if (d && typeof d.publicKey === "string") {
        uidToKey.set(snap.id, {
          publicKey: d.publicKey,
          keyId: (d.keyId as string) ?? "",
          alg: (d.alg as string) ?? "",
          displayName: (d.displayName as string) ?? "",
          photoUrl: (d.photoUrl as string) ?? "",
          isChild: d.isChild === true,
        });
      }
    }

    const matches: ContactMatch[] = [];
    for (const [hash, targetUid] of hashToUid) {
      const key = uidToKey.get(targetUid);
      if (!key) continue;
      matches.push({
        phone: hashToPhone.get(hash) as string,
        uid: targetUid,
        publicKey: key.publicKey,
        keyId: key.keyId,
        alg: key.alg,
        displayName: key.displayName,
        photoUrl: key.photoUrl,
        isChild: key.isChild,
      });
    }
    return { matches };
  }
);
