import { createHash, randomUUID } from "node:crypto";
import { FieldValue, Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";

// Membership and phone verification remain server-owned. Only the legacy administrative
// authority may originate changes to these fields; an HA mirror can never grant a role.
export const PROFILE_AUTHORITY_FIELDS = new Set([
  "id", "role", "verified", "dashboardAccess", "agency", "agencyName", "agencyKey", "agencySlug",
  "authority", "institution", "organization", "panelId", "defaultDashboardPanelId", "defaultPanelId",
  "platformAdmin", "globalDashboardAccess", "superAdmin", "workflowRole", "workflowRoleUpdatedAt",
  "workflowRoleUpdatedBy", "orgRole", "permissions", "rolePermissions", "dashboardPermissions",
  "licenseManagement", "canManageLicense", "phoneVerifiedAt", "phoneVerifiedE164", "disabled",
]);
const UID = /^[A-Za-z0-9_-]{1,128}$/;
const RETENTION_MS = 31 * 86_400_000;
export const PROFILE_MIRROR_REVISION = "_profileMirrorRevision";
type Data = FirebaseFirestore.DocumentData;
type Stamp = { clock: string; hash: string };

function canonical(value: unknown): unknown {
  if (value === undefined) return ["absent"];
  if (value === null) return ["null"];
  if (value instanceof Timestamp) return ["timestamp", value.seconds, value.nanoseconds];
  if (value instanceof Date) return ["date", value.toISOString()];
  if (Buffer.isBuffer(value)) return ["bytes", value.toString("base64")];
  if (Array.isArray(value)) return ["array", value.map(canonical)];
  if (value && typeof value === "object") {
    if ("path" in value && "firestore" in value) return ["reference", (value as { path: string }).path];
    return ["object", Object.keys(value).sort().map(k => [k, canonical((value as Data)[k])])];
  }
  return [typeof value, value];
}
export const profileValueHash = (value: unknown): string =>
  createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
export const profileClock = (time: Timestamp, origin: string): string =>
  `${String(time.seconds).padStart(12, "0")}:${String(time.nanoseconds).padStart(9, "0")}:${origin}`;

export interface ProfileChange {
  uid: string;
  before?: Data;
  after?: Data;
  time: Timestamp;
  origin: "legacy" | "ha";
}

/**
 * Merge only changed fields, ordered by their original commit time. A mirror transaction
 * records a reserved revision and full-value digest, so mirrored events cannot echo.
 * Released clients preserve/ignore the additive _profileMirrorRevision field. Transaction retries re-read concurrent native SDK writes.
 */
export async function mirrorProfile(change: ProfileChange, source: Firestore, target: Firestore): Promise<boolean> {
  if (!UID.test(change.uid)) throw new Error("Invalid profile UID");
  const { uid, before, after, time, origin } = change;
  if (origin === "ha" && (await source.doc(`messagingAccounts/${uid}`).get()).get("state") === "deleted") return false;
  const receipts = source.collection(`profileMirrorState/${uid}/profileMirrorRevisions`);
  const bodyHash = (value: Data) => profileValueHash(Object.fromEntries(
    Object.entries(value).filter(([key]) => key !== PROFILE_MIRROR_REVISION)));
  const revision = after?.[PROFILE_MIRROR_REVISION];
  // Server timestamps and document updateTime need not have identical precision.
  // The unguessable receipt AND the complete value digest bind only our exact write.
  // A native edit retains the marker. Even reverting to the last mirrored value is a new
  // user write; only our own write introduces a fresh marker and can be treated as an echo.
  if (revision !== before?.[PROFILE_MIRROR_REVISION] && typeof revision === "string" && /^[0-9a-f-]{36}$/.test(revision) &&
      (await receipts.doc(revision).get()).get("valueHash") === bodyHash(after!)) return false;
  const ref = target.doc(`users/${uid}`), stateRef = target.doc(`profileMirrorState/${uid}`);
  const clock = profileClock(time, origin);
  const changed = [...new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})])]
    .filter(key => key !== PROFILE_MIRROR_REVISION && profileValueHash(before?.[key]) !== profileValueHash(after?.[key]));
  if (after && !changed.length) return false;
  return target.runTransaction(async tx => {
    const [current, state, account] = await tx.getAll(ref, stateRef, target.doc(`messagingAccounts/${uid}`));
    if (account.get("state") === "deleted") return false;
    const previous = current.data() ?? {};
    const stamps = Object.assign(Object.create(null), state.get("fields") ?? {}) as Record<string, Stamp>;
    const deletedClock = String(state.get("deletedClock") ?? "");
    if (clock <= deletedClock) return false;
    const localClock = current.updateTime ? profileClock(current.updateTime, origin === "ha" ? "legacy" : "ha") : "";
    const effective = (key: string): string => {
      const stamp = stamps[key];
      return stamp && stamp.hash === profileValueHash(previous[key]) ? stamp.clock
        : (stamp || Object.prototype.hasOwnProperty.call(previous, key) ? localClock : "");
    };
    const write = target.collection(`profileMirrorState/${uid}/profileMirrorRevisions`).doc(randomUUID());
    const expiry = Timestamp.fromMillis(Date.now() + RETENTION_MS);
    if (!after) {
      // A delayed deletion cannot erase any newer committed field or resurrected profile.
      const latest = Object.keys(previous).filter(k => k !== PROFILE_MIRROR_REVISION)
        .reduce((v, k) => effective(k) > v ? effective(k) : v, deletedClock);
      if (clock <= latest) return false;
      tx.delete(ref);
      tx.set(stateRef, { deletedClock: clock, fields: {}, writtenAt: FieldValue.serverTimestamp() });
      tx.create(write, { writtenAt: FieldValue.serverTimestamp(), expireAt: expiry, deletion: true });
      return true;
    }
    const merged = Object.assign(Object.create(null), previous) as Data;
    let applied = false;
    for (const key of changed) {
      // HA clients may seed the ordinary-user defaults, but cannot overwrite authority.
      if (origin === "ha" && PROFILE_AUTHORITY_FIELDS.has(key)) {
        const safeDefault = !current.exists && ((key === "id" && after[key] === uid) ||
          (key === "role" && after[key] === "user") || (key === "verified" && after[key] === false));
        const clearPhoneVerification = ["phoneVerifiedAt", "phoneVerifiedE164"].includes(key)
          && after[key] == null && changed.includes("phoneNumber");
        if (!safeDefault && !clearPhoneVerification) continue;
      }
      if (clock <= effective(key)) continue;
      if (Object.prototype.hasOwnProperty.call(after, key)) merged[key] = after[key];
      else delete merged[key];
      stamps[key] = { clock, hash: profileValueHash(after[key]) };
      applied = true;
    }
    if (!applied) return false;
    merged[PROFILE_MIRROR_REVISION] = write.id;
    tx.set(ref, merged);
    tx.set(stateRef, { fields: stamps, deletedClock, writtenAt: FieldValue.serverTimestamp() });
    tx.create(write, { valueHash: bodyHash(merged), writtenAt: FieldValue.serverTimestamp(), expireAt: expiry });
    return true;
  });
}

function eventTimestamp(value: string): Timestamp {
  const match = /^(.*?)(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) throw new Error("Invalid Firestore event timestamp");
  const seconds = Math.floor(Date.parse(match[1] + "Z") / 1000);
  return new Timestamp(seconds, Number((match[2] ?? "").padEnd(9, "0")));
}

function profileTrigger(database: string, region: string, origin: "legacy" | "ha") {
  return onDocumentWritten({ database, region, document: "users/{uid}", retry: true, timeoutSeconds: 120 }, async event => {
    if (process.env.PROFILE_HA_ENABLED !== "true" || !event.data) return;
    const source = database === "(default)" ? getFirestore() : getFirestore(database);
    const target = origin === "legacy" ? getFirestore(MULTIREGION_MESSAGING_DATABASE) : getFirestore();
    await mirrorProfile({ uid: event.params.uid, before: event.data.before.data(), after: event.data.after.data(),
      time: event.data.after.updateTime ?? eventTimestamp(event.time), origin }, source, target);
  });
}

export const onLegacyProfileChanged = profileTrigger("(default)", "europe-west4", "legacy");
export const onHaProfileChangedEurope = profileTrigger(MULTIREGION_MESSAGING_DATABASE, "europe-west1", "ha");
export const onHaProfileChangedUS = profileTrigger(MULTIREGION_MESSAGING_DATABASE, "us-central1", "ha");
