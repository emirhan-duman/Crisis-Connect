import { createHash } from "node:crypto";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { haFirestore } from "./highAvailabilityStore";
import { normalizeRoleKey } from "../certificates/callerRole";

export interface ResilientGroupBinding { scopeType: "agency" | "hierarchy"; channelId: string; participants: string[] }
const UID = /^[A-Za-z0-9_-]{1,128}$/;
export function groupBinding(raw: unknown): ResilientGroupBinding {
  const data = raw as ResilientGroupBinding;
  if (!data || !["agency", "hierarchy"].includes(data.scopeType) || typeof data.channelId !== "string" ||
      !data.channelId.length || Buffer.byteLength(data.channelId) > 256 || data.channelId.includes("/") ||
      !Array.isArray(data.participants) || data.participants.length !== 2 ||
      data.participants.some(uid => typeof uid !== "string" || !UID.test(uid)) || data.participants[0] === data.participants[1]) {
    throw new HttpsError("invalid-argument", "Invalid group binding.");
  }
  return { scopeType: data.scopeType, channelId: data.channelId,
    participants: [...data.participants].sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b))) };
}

export function groupConversationId(binding: ResilientGroupBinding, resilient: boolean): string {
  const b = groupBinding(binding), digest = createHash("sha256");
  for (const field of [`cc-authority-mls-conversation:v${resilient ? 11 : 10}`, b.scopeType, b.channelId, "2", ...b.participants]) {
    const bytes = Buffer.from(field), length = Buffer.alloc(4); length.writeUInt32BE(bytes.length);
    digest.update(length).update(bytes);
  }
  return "am2_" + digest.digest("base64url");
}

function panelId(data: FirebaseFirestore.DocumentData): string | undefined {
  return [data.panelId, data.agencySlug, data.agencyKey].find(x => typeof x === "string" && x.length > 0);
}

type Direction = "up" | "down" | "sibling";
function relationship(a: string, x: FirebaseFirestore.DocumentData, b: string, y: FirebaseFirestore.DocumentData): Direction | undefined {
  if (x.parentPanelId === b || (x.ancestorPanelIds ?? []).includes(b)) return "up";
  if (y.parentPanelId === a || (y.ancestorPanelIds ?? []).includes(a)) return "down";
  if (x.parentPanelId && x.parentPanelId === y.parentPanelId) return "sibling";
  return undefined;
}
export function hierarchyPolicyAllows(a: string, b: string, direction: Direction,
  policies: Map<string, FirebaseFirestore.DocumentData>): boolean {
  const inverse = direction === "up" ? "down" : direction === "down" ? "up" : "sibling";
  const global = policies.get("global") ?? {}, first = policies.get(a) ?? {}, second = policies.get(b) ?? {};
  return global[direction] !== false && global[inverse] !== false &&
    !(global.blockedPairs ?? []).includes([a, b].sort().join("|")) && first[direction] !== false &&
    second[inverse] !== false && !(first.blockedPeers ?? []).includes(b) && !(second.blockedPeers ?? []).includes(a);
}

/** Materialize only structurally related, policy-authorized channels; never mint shared keys. */
export async function listResilientHierarchy(uid: string) {
  const db = haFirestore(), user = (await db.doc(`users/${uid}`).get()).data() ?? {};
  if (!activeGroupProfile(user) || !["admin", "authority"].includes(normalizeRoleKey(user.role)))
    throw new HttpsError("permission-denied", "Hierarchy requires an active manager.");
  const mine = panelId(user);
  if (!mine) throw new HttpsError("permission-denied", "No panel membership.");
  const [panels, users, policyRows] = await Promise.all([
    db.collection("agencyPanels").get(), db.collection("users").get(), db.collection("messagingPolicies").get(),
  ]);
  const own = panels.docs.find(p => p.id === mine)?.data();
  if (!own) throw new HttpsError("failed-precondition", "Panel metadata is unavailable.");
  const policies = new Map(policyRows.docs.map(p => [p.id, p.data()]));
  const channels: Record<string, unknown>[] = [];
  for (const peer of panels.docs) {
    if (peer.id === mine) continue;
    const group = relationship(mine, own, peer.id, peer.data());
    if (!group) continue;
    const active = hierarchyPolicyAllows(mine, peer.id, group, policies);
    const pair = [mine, peer.id].sort();
    const id = "hc_" + createHash("sha256").update(`crisisconnect:hchan:v1:${pair[0]}:${pair[1]}`).digest("base64url").slice(0, 32);
    await db.doc(`hierarchyChannels/${id}`).set({ panelIds: pair, relationship: group,
      relationshipOwnerPanelId: mine, active, policyUpdatedAt: FieldValue.serverTimestamp() }, { merge: true });
    if (!active) continue;
    const peers = users.docs.filter(p => p.id !== uid && panelId(p.data()) === peer.id &&
      activeGroupProfile(p.data()) && ["admin", "authority"].includes(normalizeRoleKey(p.get("role")))).map(p => ({
        uid: p.id, name: p.get("username") ?? p.get("name") ?? p.get("displayName") ?? p.id,
        role: p.get("role"), photoUrl: p.get("photoURL") ?? null, agency: p.get("agencyName") ?? null,
        phone: p.get("phoneVerifiedE164") === p.get("phoneNumber") ? p.get("phoneVerifiedE164") ?? null : null,
      }));
    if (peers.length) channels.push({ channelId: id, peerPanelId: peer.id,
      peerPanelName: peer.get("authorityName") ?? peer.get("agencyName") ?? peer.get("displayName") ?? peer.id,
      group, peers: peers.slice(0, 2000) });
    if (channels.length >= 60) break;
  }
  return { channels };
}
export function activeGroupProfile(data: FirebaseFirestore.DocumentData): boolean {
  const role = normalizeRoleKey(data.role);
  const enabled = data.dashboardAccess === undefined || data.dashboardAccess === true || data.dashboardAccess === 1 ||
    (typeof data.dashboardAccess === "string" && ["true", "1", "yes", "on", "enabled"].includes(data.dashboardAccess.toLowerCase()));
  const denied = data.orgRole?.permissions?.dashboard === false && !["admin", "authority"].includes(role) &&
    ![data.superAdmin, data.platformAdmin, data.globalDashboardAccess].includes(true);
  return data.disabled !== true && !denied && ["admin", "authority", "fieldteam"].includes(role) &&
    (enabled || (["admin", "fieldteam"].includes(role) && data.verified === true));
}

/** All authorization reads remain in the multi-region store, including during a legacy outage. */
export async function authorizeGroup(uid: string, b: ResilientGroupBinding): Promise<void> {
  if (!b.participants.includes(uid) || uid === "8blrCEoszTWBVWb3lA1TnbSn7aJ3")
    throw new HttpsError("permission-denied", "Not a group participant.");
  const db = haFirestore();
  const profiles = await db.getAll(...b.participants.map(id => db.doc(`users/${id}`)));
  const panels = profiles.map(p => panelId(p.data() ?? {}));
  if (profiles.some(p => !p.exists || !activeGroupProfile(p.data()!)) || panels.some(p => !p))
    throw new HttpsError("permission-denied", "Inactive group membership.");
  const scope = await db.doc(`${b.scopeType === "agency" ? "agencyPanels" : "hierarchyChannels"}/${b.channelId}`).get();
  if (!scope.exists) throw new HttpsError("permission-denied", "Group scope is unavailable.");
  for (const panel of panels as string[]) {
    if (b.scopeType === "agency") {
      if (panel === b.channelId) continue;
      const own = (await db.doc(`agencyPanels/${panel}`).get()).data() ?? {};
      if (!(scope.get("childPanelIds") ?? []).includes(panel) && !(scope.get("ancestorPanelIds") ?? []).includes(panel) &&
          !(own.childPanelIds ?? []).includes(b.channelId) && !(own.ancestorPanelIds ?? []).includes(b.channelId))
        throw new HttpsError("permission-denied", "Participant is outside the agency scope.");
    } else {
      if (scope.get("active") === false || scope.get("disabled") === true)
        throw new HttpsError("permission-denied", "Hierarchy channel is disabled.");
      const allowed = scope.get("panelIds");
      if (!Array.isArray(allowed) || allowed.length !== 2) throw new HttpsError("permission-denied", "Invalid hierarchy membership.");
      const pair = await db.getAll(...allowed.map(id => db.doc(`agencyPanels/${id}`)));
      const direction = pair.every(p => p.exists) ? relationship(allowed[0], pair[0].data()!, allowed[1], pair[1].data()!) : undefined;
      const policies = await db.getAll(...["global", ...allowed].map(id => db.doc(`messagingPolicies/${id}`)));
      if (!direction || !hierarchyPolicyAllows(allowed[0], allowed[1], direction, new Map(policies.map(p => [p.id, p.data() ?? {}]))))
        throw new HttpsError("permission-denied", "Hierarchy policy no longer permits this channel.");
      const own = (await db.doc(`agencyPanels/${panel}`).get()).data() ?? {};
      if (!Array.isArray(allowed) || (!allowed.includes(panel) && !allowed.includes(own.parentPanelId)))
        throw new HttpsError("permission-denied", "Participant is outside the hierarchy scope.");
    }
  }
}

export async function resolveResilientGroup(uid: string, raw: unknown, deviceId: unknown,
  legacyStore: () => FirebaseFirestore.Firestore = () => getFirestore(), independent = false) {
  if (typeof deviceId !== "string" || !UID.test(deviceId)) throw new HttpsError("invalid-argument", "Invalid device ID.");
  const binding = groupBinding(raw);
  await authorizeGroup(uid, binding);
  const db = haFirestore(), oldId = groupConversationId(binding, false), newId = groupConversationId(binding, true);
  // A device only attests its own account. Existing un-upgraded devices retain the old session.
  await db.doc(`authorityHaDevices/${uid}/devices/${deviceId}`).set({ version: 1, updatedAt: FieldValue.serverTimestamp() });
  await db.doc(`authorityHaDevices/${uid}`).set({ version: 1 }, { merge: true });
  const routeRef = db.doc(`authorityHaRoutes/${oldId}`), route = await routeRef.get();
  const resolved = () => ({ resilient: true, conversationId: newId, legacyConversationId: oldId,
    compatibility: "separate-session", legacyDelivery: false });
  if (route.exists) return resolved();
  // Explicit new-client protocol: allocate an independent cryptographic context in HA. This is
  // not evidence that legacy devices upgraded, and never changes or copies their live session.
  // The MLS approved directory/Welcome still gates delivery to devices in this context.
  if (independent) {
    await createRoute();
    return resolved();
  }
  const capabilities = await db.getAll(...binding.participants.map(id => db.doc(`authorityHaDevices/${id}`)));
  const legacy = () => ({ resilient: false, conversationId: oldId, legacyConversationId: oldId });
  if (capabilities.some(cap => cap.get("version") !== 1)) return legacy();
  // Never migrate a live MLS ratchet. A new cryptographic context is allocated only after every
  // device currently registered in the old session has announced support for this protocol.
  const devices = await legacyStore().collection(`authorityMlsV2/${oldId}/devices`).limit(201).get();
  if (devices.size > 200) return legacy();
  for (const device of devices.docs) {
    const owner = device.get("uid");
    if (!binding.participants.includes(owner) || !UID.test(device.id)) return legacy();
    if ((await db.doc(`authorityHaDevices/${owner}/devices/${device.id}`).get()).get("version") !== 1) return legacy();
  }
  await createRoute();
  return resolved();

  async function createRoute() { await db.runTransaction(async tx => {
    const prior = await tx.get(routeRef);
    if (prior.exists) return;
    const data = { ...binding, conversationId: newId, legacyConversationId: oldId, generation: 11,
      bootstrap: independent ? "independent-v1" : "legacy-directory-v1", createdAt: FieldValue.serverTimestamp() };
    tx.create(routeRef, data);
    tx.create(db.doc(`authorityHaRoutesById/${newId}`), data);
  }); }
}

const METADATA_ROOTS = new Set(["agencyPanels", "hierarchyChannels", "messagingPolicies"]);
export function metadataEventTimestamp(value: string): Timestamp {
  const match = /^(.*?)(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) throw new Error("Invalid metadata event timestamp");
  return new Timestamp(Math.floor(Date.parse(match[1] + "Z") / 1000), Number((match[2] ?? "").padEnd(9, "0")));
}
export async function mirrorGroupMetadata(root: string, id: string, data: FirebaseFirestore.DocumentData | undefined, version: Timestamp) {
  if (!METADATA_ROOTS.has(root) || id.includes("/") || !id.length) throw new Error("Invalid group metadata path.");
  const db = haFirestore(), path = `${root}/${id}`, key = createHash("sha256").update(path).digest("hex");
  await db.runTransaction(async tx => {
    const state = db.doc(`groupMetadataMirror/${key}`), previous = await tx.get(state);
    const old = previous.get("sourceVersion") as Timestamp | undefined;
    const stamp = (value: Timestamp) => `${String(value.seconds).padStart(12, "0")}:${String(value.nanoseconds).padStart(9, "0")}`;
    const clock = stamp(version), oldClock = previous.get("sourceClock") ?? (old ? stamp(old) : "");
    if (oldClock >= clock) return;
    // One server-owned access projection keeps MLS atomic writes below the Rules ten-read limit.
    // It changes in the same transaction as the authoritative HA panel/policy document.
    if (root === "agencyPanels" || root === "messagingPolicies") {
      const projection = db.doc("applicationRuntime/hierarchyAccess"), snapshot = await tx.get(projection);
      const field = root === "agencyPanels" ? "panels" : "policies";
      const values = { ...(snapshot.get(field) ?? {}) };
      if (!data) delete values[id];
      else values[id] = root === "agencyPanels"
        ? { parentPanelId: data.parentPanelId ?? "", ancestorPanelIds: data.ancestorPanelIds ?? [] }
        : data;
      tx.set(projection, { ...(snapshot.data() ?? {}), [field]: values, updatedAt: FieldValue.serverTimestamp() });
    }
    if (data) tx.set(db.doc(path), data); else tx.delete(db.doc(path));
    tx.set(state, { sourceVersion: version, sourceClock: clock });
  });
}
function metadataTrigger(root: string) {
  return onDocumentWritten({ document: `${root}/{id}`, database: "(default)", region: "europe-west4", retry: true }, async event => {
    if (process.env.PROFILE_HA_ENABLED !== "true" || !event.data) return;
    const version = event.data.after.updateTime ?? metadataEventTimestamp(event.time);
    await mirrorGroupMetadata(root, event.params.id, event.data.after.data(), version);
  });
}
export const onLegacyAgencyMetadataChanged = metadataTrigger("agencyPanels");
export const onLegacyHierarchyMetadataChanged = metadataTrigger("hierarchyChannels");
export const onLegacyMessagingPolicyChanged = metadataTrigger("messagingPolicies");
