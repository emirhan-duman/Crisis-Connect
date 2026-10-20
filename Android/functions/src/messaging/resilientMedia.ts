import { createHash, randomUUID } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { getAppCheck } from "firebase-admin/app-check";
import { getStorage } from "firebase-admin/storage";
import { onRequest } from "firebase-functions/v2/https";
import { onObjectFinalized, onObjectDeleted } from "firebase-functions/v2/storage";
import { haFirestore } from "./highAvailabilityStore";
import { authorizeGroup, groupBinding } from "./resilientGroups";

export const RESILIENT_MEDIA_BUCKET = "crisis-connect-1-media-eu";
export const LEGACY_MEDIA_BUCKET = "crisis-connect-1.firebasestorage.app";
const AVATAR = /^users\/([A-Za-z0-9_-]{1,128})\/avatars\/([a-fA-F0-9-]{36})\.jpg$/;
const ATTACHMENT = /^authorityMessageAttachments\/(am2_[A-Za-z0-9_-]{43})\/([A-Za-z0-9_-]{1,128})\/([a-fA-F0-9-]{36})$/;
const MAX_FILE = 25 * 1024 * 1024 + 16;

export function mediaBinding(path: string) {
  const avatar = AVATAR.exec(path);
  if (avatar) return { kind: "avatar", owner: avatar[1], conversationId: undefined, maxBytes: 2 * 1024 * 1024 };
  const attachment = ATTACHMENT.exec(path);
  if (attachment) return { kind: "attachment", owner: attachment[2], conversationId: attachment[1], maxBytes: MAX_FILE };
  throw new Error("invalid-path");
}
async function authorizeMedia(uid: string, path: string, writing: boolean, deleting = false) {
  const binding = mediaBinding(path), db = haFirestore();
  if ((await db.doc(`messagingAccounts/${uid}`).get()).get("state") === "deleted") throw new Error("forbidden");
  if (writing && binding.owner !== uid) throw new Error("forbidden");
  if (binding.kind === "avatar") {
    if (binding.owner !== uid) throw new Error("forbidden");
    const profile = (await db.doc(`users/${uid}`).get()).data() ?? {};
    const organization = ["agency", "agencyName", "agencyKey", "agencySlug", "authority", "institution", "organization"]
      .some(key => typeof profile[key] === "string" && profile[key].length > 0);
    if (writing && !deleting && (profile.disabled === true || !organization || !profile.role || profile.role === "user")) throw new Error("forbidden");
  } else {
    const parent = await db.doc(`authorityMlsV2/${binding.conversationId}`).get();
    if (!(await db.doc(`authorityHaRoutesById/${binding.conversationId}`).get()).exists) throw new Error("forbidden");
    await authorizeGroup(uid, groupBinding(parent.data()));
  }
  return binding;
}

function mediaGateway(region: string) {
  return onRequest({ region, invoker: "public", timeoutSeconds: 180, memory: "512MiB", concurrency: 8, maxInstances: 10 }, async (req, res) => {
    res.set("Cache-Control", "no-store");
    try {
      const authorization = req.header("Authorization"), appCheck = req.header("X-Firebase-AppCheck");
      if (!authorization?.startsWith("Bearer ") || !appCheck) { res.status(401).end(); return; }
      let uid: string;
      try { const [auth] = await Promise.all([getAuth().verifyIdToken(authorization.slice(7)), getAppCheck().verifyToken(appCheck)]); uid = auth.uid; }
      catch { res.status(401).end(); return; }
      if (uid === "8blrCEoszTWBVWb3lA1TnbSn7aJ3") { res.status(403).end(); return; }
      if (process.env.GROUP_PROFILE_HA_ENABLED !== "true") {
        const gate = await haFirestore().doc("applicationRuntime/canaries").get();
        if (!(Number(gate.get("expiresAtMs")) > Date.now() && (gate.get("uids") ?? []).includes(uid))) { res.status(503).end(); return; }
      }
      const path = typeof req.query.path === "string" ? req.query.path : "";
      if (!["PUT", "GET", "DELETE"].includes(req.method)) { res.status(405).end(); return; }
      const binding = await authorizeMedia(uid, path, req.method !== "GET", req.method === "DELETE");
      const file = getStorage().bucket(RESILIENT_MEDIA_BUCKET).file(path);
      if (req.method === "PUT") {
        const bytes = req.rawBody;
        if (!bytes?.length || bytes.length > binding.maxBytes) { res.status(413).end(); return; }
        const digest = createHash("sha256").update(bytes).digest("hex");
        let metadata;
        try {
          await file.save(bytes, { resumable: false, preconditionOpts: { ifGenerationMatch: 0 },
            metadata: { contentType: binding.kind === "avatar" ? "image/jpeg" : "application/octet-stream",
              cacheControl: "private, max-age=300", metadata: { "cc-sha256": digest, firebaseStorageDownloadTokens: randomUUID() } } });
          [metadata] = await file.getMetadata();
        } catch (error) {
          if (Number((error as { code?: unknown }).code) !== 412) throw error;
          [metadata] = await file.getMetadata();
          if (metadata.metadata?.["cc-sha256"] !== digest || Number(metadata.size) !== bytes.length) { res.status(409).end(); return; }
        }
        const token = metadata.metadata?.firebaseStorageDownloadTokens;
        const downloadUrl = `https://firebasestorage.googleapis.com/v0/b/${RESILIENT_MEDIA_BUCKET}/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
        res.json({ ok: true, ...(binding.kind === "avatar" ? { downloadUrl } : {}) });
      } else if (req.method === "GET") {
        const [metadata] = await file.getMetadata();
        if (Number(metadata.size) > binding.maxBytes) { res.status(413).end(); return; }
        const [bytes] = await file.download();
        res.type(binding.kind === "avatar" ? "image/jpeg" : "application/octet-stream").send(bytes);
      } else {
        await file.delete({ ignoreNotFound: true });
        res.json({ ok: true });
      }
    } catch (error) {
      const code = (error as { code?: unknown }).code;
      const message = (error as Error).message;
      res.status(code === 404 ? 404 : message === "invalid-path" ? 400 :
        message === "forbidden" || code === "permission-denied" || code === "invalid-argument" ? 403 : 503).end();
    }
  });
}
export const applicationMediaEurope = mediaGateway("europe-west4");
export const applicationMediaUS = mediaGateway("us-central1");

/** Existing clients keep their original bucket. Only a current avatar generation is mirrored. */
export async function mirrorLegacyProfileMedia(path: string, generation: string): Promise<boolean> {
  const match = /^users\/([A-Za-z0-9_-]{1,128})\/(.+)$/.exec(path);
  if (!match) return false;
  const source = getStorage().bucket(LEGACY_MEDIA_BUCKET).file(path);
  let metadata;
  try { [metadata] = await source.getMetadata(); } catch (error) {
    if (Number((error as { code?: unknown }).code) === 404) return false;
    throw error;
  }
  if (String(metadata.generation) !== generation) return false;
  const target = getStorage().bucket(RESILIENT_MEDIA_BUCKET).file(path);
  let previous;
  try { [previous] = await target.getMetadata(); } catch (error) {
    if (Number((error as { code?: unknown }).code) !== 404) throw error;
  }
  if (previous && BigInt(String(previous.metadata?.["cc-source-generation"] ?? "0")) >= BigInt(generation)) return false;
  await getStorage().bucket(LEGACY_MEDIA_BUCKET).file(path, { generation }).copy(target, { preconditionOpts: { ifGenerationMatch: previous?.generation ?? 0 },
    contentType: metadata.contentType, cacheControl: metadata.cacheControl,
    metadata: { ...metadata.metadata, "cc-source-generation": generation } });
  const user = haFirestore().doc(`users/${match[1]}`);
  await haFirestore().runTransaction(async tx => {
    const profile = await tx.get(user), url = profile.get("photoURL");
    if (typeof url !== "string") return;
    const prefix = `https://firebasestorage.googleapis.com/v0/b/${LEGACY_MEDIA_BUCKET}/o/${encodeURIComponent(path)}?`;
    if (url.startsWith(prefix)) tx.update(user, { photoURL: url.replace(`/b/${LEGACY_MEDIA_BUCKET}/`, `/b/${RESILIENT_MEDIA_BUCKET}/`) });
  });
  return true;
}
export const onLegacyProfileMediaFinalized = onObjectFinalized({ bucket: LEGACY_MEDIA_BUCKET, region: "us-central1", retry: true }, async event => {
  if (process.env.PROFILE_HA_ENABLED === "true") await mirrorLegacyProfileMedia(event.data.name, String(event.data.generation));
});
export const onLegacyProfileMediaDeleted = onObjectDeleted({ bucket: LEGACY_MEDIA_BUCKET, region: "us-central1", retry: true }, async event => {
  if (process.env.PROFILE_HA_ENABLED !== "true" || !/^users\/[A-Za-z0-9_-]{1,128}\/.+/.test(event.data.name)) return;
  const source = getStorage().bucket(LEGACY_MEDIA_BUCKET).file(event.data.name);
  if ((await source.exists())[0]) return; // A replacement may already have been committed.
  const target = getStorage().bucket(RESILIENT_MEDIA_BUCKET).file(event.data.name);
  try {
    const [meta] = await target.getMetadata();
    if (BigInt(String(meta.metadata?.["cc-source-generation"] ?? "0")) <= BigInt(event.data.generation))
      await target.delete({ ifGenerationMatch: meta.generation });
  } catch (error) { if (![404, 412].includes(Number((error as { code?: unknown }).code))) throw error; }
});
