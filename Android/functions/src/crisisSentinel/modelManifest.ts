import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { HttpsError, onCall } from "firebase-functions/v2/https";

type PlatformKey = "android" | "ios" | "mobile";

interface RawManifestInput {
  platform?: unknown;
}

interface RawDownloadUrlInput extends RawManifestInput {
  releaseId?: unknown;
}

interface ModelReleasePayload {
  platform: PlatformKey;
  id: string;
  displayName: string;
  fileName: string;
  downloadUrl: string | null;
  storageBucket: string;
  storagePath: string;
  expectedSha256: string | null;
  expectedBytes: number | null;
  minFreeBytes: number;
  updatedAtMs: number | null;
}

interface StorageLocation {
  bucket: string;
  objectPath: string;
}

const SAFE_TOKEN = /^[A-Za-z0-9._-]+$/;
const SHA256_HEX = /^[a-fA-F0-9]{64}$/;
const DEFAULT_MIN_FREE_BYTES = 2_500_000_000;
const SIGNED_DOWNLOAD_URL_TTL_MS = 10 * 60 * 1000;
const ALLOWED_MODEL_BUCKETS = new Set([
  "crisis-connect-1.firebasestorage.app",
  "crisis-connect-1.appspot.com",
]);
const MODEL_OBJECT_PREFIX = "crisis-sentinel/models/";

function resolvePlatform(raw: RawManifestInput): PlatformKey {
  const platform = typeof raw.platform === "string"
    ? raw.platform.trim().toLowerCase()
    : "mobile";
  if (platform === "android" || platform === "ios") {
    return platform;
  }
  return "mobile";
}

function stringField(
  data: Record<string, unknown>,
  key: string,
  fallbackKey?: string
): string | null {
  const primary = data[key];
  if (typeof primary === "string" && primary.trim() !== "") {
    return primary.trim();
  }
  if (fallbackKey) {
    const fallback = data[fallbackKey];
    if (typeof fallback === "string" && fallback.trim() !== "") {
      return fallback.trim();
    }
  }
  return null;
}

function numberField(
  data: Record<string, unknown>,
  key: string,
  fallbackKey?: string
): number | null {
  const raw = data[key] ?? (fallbackKey ? data[fallbackKey] : undefined);
  if (typeof raw !== "number" || !Number.isFinite(raw)) {
    return null;
  }
  if (!Number.isInteger(raw) || raw <= 0 || !Number.isSafeInteger(raw)) {
    throw new HttpsError(
      "failed-precondition",
      `Model manifest field '${key}' must be a positive safe integer.`
    );
  }
  return raw;
}

function updatedAtMillis(data: Record<string, unknown>): number | null {
  const updatedAt = data.updatedAt as { toMillis?: () => number } | undefined;
  if (updatedAt?.toMillis) {
    return updatedAt.toMillis();
  }
  const updatedAtMs = data.updatedAtMs;
  return typeof updatedAtMs === "number" && Number.isFinite(updatedAtMs)
    ? Math.floor(updatedAtMs)
    : null;
}

function assertSafeToken(value: string, field: string): void {
  if (!SAFE_TOKEN.test(value)) {
    throw new HttpsError(
      "failed-precondition",
      `Model manifest field '${field}' contains unsupported characters.`
    );
  }
}

function optionalSafeToken(data: Record<string, unknown>, key: string): string | null {
  const raw = data[key];
  if (raw === undefined || raw === null) {
    return null;
  }
  if (typeof raw !== "string") {
    throw new HttpsError(
      "invalid-argument",
      `Model download field '${key}' must be a string.`
    );
  }
  const value = raw.trim();
  if (!value) {
    return null;
  }
  assertSafeToken(value, key);
  return value;
}

function modelLocationFromDownloadUrl(value: string): StorageLocation {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest downloadUrl must be a valid URL."
    );
  }
  if (parsed.protocol !== "https:") {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest downloadUrl must use HTTPS."
    );
  }
  return modelLocationFromUrl(parsed);
}

function modelLocationFromUrl(parsed: URL): StorageLocation {
  if (parsed.hostname === "firebasestorage.googleapis.com") {
    const match = parsed.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
    const bucket = match?.[1] ? decodeURIComponent(match[1]) : "";
    const objectPath = match?.[2] ? decodeURIComponent(match[2]) : "";
    if (
      ALLOWED_MODEL_BUCKETS.has(bucket) &&
      objectPath.startsWith(MODEL_OBJECT_PREFIX) &&
      parsed.searchParams.get("alt") === "media"
    ) {
      return { bucket, objectPath };
    }
  }

  if (parsed.hostname === "storage.googleapis.com") {
    const [, bucket = "", ...objectParts] = parsed.pathname.split("/");
    const objectPath = decodeURIComponent(objectParts.join("/"));
    if (
      ALLOWED_MODEL_BUCKETS.has(bucket) &&
      objectPath.startsWith(MODEL_OBJECT_PREFIX)
    ) {
      return { bucket, objectPath };
    }
  }

  throw new HttpsError(
    "failed-precondition",
    "Model manifest downloadUrl must point to the Crisis Sentinel Firebase Storage model path."
  );
}

function modelLocationFromFields(data: Record<string, unknown>): StorageLocation | null {
  const bucket = stringField(data, "storageBucket");
  const objectPath = stringField(data, "storagePath", "objectPath");
  if (!bucket && !objectPath) {
    return null;
  }
  if (!bucket || !objectPath) {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest storageBucket and storagePath must be provided together."
    );
  }
  if (!ALLOWED_MODEL_BUCKETS.has(bucket) || !objectPath.startsWith(MODEL_OBJECT_PREFIX)) {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest storagePath must point to the Crisis Sentinel Firebase Storage model path."
    );
  }
  return { bucket, objectPath };
}

function toReleasePayload(
  platform: PlatformKey,
  data: Record<string, unknown>
): ModelReleasePayload {
  const id = stringField(data, "id", "releaseId");
  const displayName = stringField(data, "displayName", "name");
  const fileName = stringField(data, "fileName");
  const downloadUrl = stringField(data, "downloadUrl", "url");
  const explicitLocation = modelLocationFromFields(data);
  const urlLocation = downloadUrl ? modelLocationFromDownloadUrl(downloadUrl) : null;
  const storageLocation = explicitLocation ?? urlLocation;
  const expectedSha256 = stringField(data, "expectedSha256", "sha256");
  const expectedBytes = numberField(data, "expectedBytes", "bytes");
  const minFreeBytes = numberField(data, "minFreeBytes") ?? DEFAULT_MIN_FREE_BYTES;

  if (!id || !displayName || !fileName || !storageLocation) {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest is missing id, displayName, fileName, or storageBucket/storagePath."
    );
  }
  if (
    explicitLocation &&
    urlLocation &&
    (explicitLocation.bucket !== urlLocation.bucket || explicitLocation.objectPath !== urlLocation.objectPath)
  ) {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest downloadUrl and storagePath must point to the same model object."
    );
  }

  assertSafeToken(id, "id");
  assertSafeToken(fileName, "fileName");

  if (expectedSha256 && !SHA256_HEX.test(expectedSha256)) {
    throw new HttpsError(
      "failed-precondition",
      "Model manifest expectedSha256 must be 64 hex characters."
    );
  }

  return {
    platform,
    id,
    displayName,
    fileName,
    downloadUrl: null,
    storageBucket: storageLocation.bucket,
    storagePath: storageLocation.objectPath,
    expectedSha256: expectedSha256?.toLowerCase() ?? null,
    expectedBytes,
    minFreeBytes,
    updatedAtMs: updatedAtMillis(data),
  };
}

async function loadManifest(platform: PlatformKey): Promise<ModelReleasePayload | null> {
  const candidates: PlatformKey[] = platform === "mobile"
    ? ["mobile"]
    : [platform, "mobile"];
  const db = getFirestore();

  for (const candidate of candidates) {
    const snap = await db.doc(`crisisSentinelModelReleases/${candidate}`).get();
    if (!snap.exists) {
      continue;
    }
    const data = snap.data() as Record<string, unknown>;
    if (data.enabled !== true) {
      return null;
    }
    return toReleasePayload(candidate, data);
  }

  return null;
}

export const getCrisisSentinelModelManifest = onCall(
  // Public users can fetch the manifest only from verified app clients.
  { enforceAppCheck: true },
  async (request) => {
    const platform = resolvePlatform((request.data ?? {}) as RawManifestInput);
    const release = await loadManifest(platform);
    if (!release) {
      return {
        available: false,
        reason: "No enabled Crisis Sentinel mobile model release is configured.",
      };
    }
    return {
      available: true,
      release,
    };
  }
);

export const getCrisisSentinelModelDownloadUrl = onCall(
  // Model artifacts are not readable through public Storage rules. Verified
  // app clients request short-lived signed URLs through this callable instead.
  { enforceAppCheck: true },
  async (request) => {
    const data = (request.data ?? {}) as RawDownloadUrlInput & Record<string, unknown>;
    const platform = resolvePlatform(data);
    const release = await loadManifest(platform);
    if (!release) {
      return {
        available: false,
        reason: "not configured",
      };
    }

    const requestedReleaseId = optionalSafeToken(data, "releaseId");
    if (requestedReleaseId && requestedReleaseId !== release.id) {
      throw new HttpsError(
        "failed-precondition",
        "Requested model release is no longer current."
      );
    }

    const bucket = getStorage().bucket(release.storageBucket);
    const file = bucket.file(release.storagePath);
    const [exists] = await file.exists();
    if (!exists) {
      throw new HttpsError(
        "not-found",
        "Crisis Sentinel model artifact is missing from Storage."
      );
    }

    const expiresAtMs = Date.now() + SIGNED_DOWNLOAD_URL_TTL_MS;
    const [downloadUrl] = await file.getSignedUrl({
      version: "v4",
      action: "read",
      expires: expiresAtMs,
    });

    return {
      available: true,
      releaseId: release.id,
      downloadUrl,
      expiresAtMs,
    };
  }
);
