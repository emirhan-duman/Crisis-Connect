import { getFirestore } from "firebase-admin/firestore";

const STATUS_URL = "https://android.googleapis.com/attestation/status";
const FIRESTORE_DOC = "system/attestation_status_cache";
const MIN_CACHE_MS = 30 * 60 * 1000;
const MAX_CACHE_MS = 24 * 60 * 60 * 1000;

export type RevocationStatus = "REVOKED" | "SUSPENDED";

export interface RevocationEntry {
  status: RevocationStatus;
  reason?: string;
  comment?: string;
  expires?: string;
}

interface StatusCacheEntry {
  entries: Record<string, RevocationEntry>;
  fetchedAtMs: number;
  expiresAtMs: number;
}

let memoryCache: StatusCacheEntry | null = null;

function parseCacheControl(headerValue: string | null): number | null {
  if (!headerValue) return null;
  const match = headerValue.match(/max-age=(\d+)/i);
  if (!match) return null;
  const seconds = Number.parseInt(match[1], 10);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  return seconds * 1000;
}

async function loadPersistentCache(): Promise<StatusCacheEntry | null> {
  try {
    const snap = await getFirestore().doc(FIRESTORE_DOC).get();
    if (!snap.exists) return null;
    const data = snap.data() as Partial<StatusCacheEntry> | undefined;
    if (!data?.entries || typeof data.expiresAtMs !== "number") return null;
    return {
      entries: data.entries,
      fetchedAtMs: data.fetchedAtMs ?? 0,
      expiresAtMs: data.expiresAtMs,
    };
  } catch (error) {
    console.warn("attestation/statusList: persistent cache read failed", error);
    return null;
  }
}

async function writePersistentCache(entry: StatusCacheEntry): Promise<void> {
  try {
    await getFirestore().doc(FIRESTORE_DOC).set(entry);
  } catch (error) {
    console.warn("attestation/statusList: persistent cache write failed", error);
  }
}

function normalizeEntries(payload: unknown): Record<string, RevocationEntry> {
  if (!payload || typeof payload !== "object") return {};
  const root = payload as Record<string, unknown>;
  const raw = (root.entries ?? root) as Record<string, unknown>;
  if (!raw || typeof raw !== "object") return {};
  const normalized: Record<string, RevocationEntry> = {};
  for (const [serial, value] of Object.entries(raw)) {
    if (!value || typeof value !== "object") continue;
    const entry = value as Record<string, unknown>;
    const status = typeof entry.status === "string"
      ? (entry.status.toUpperCase() as RevocationStatus)
      : null;
    if (status !== "REVOKED" && status !== "SUSPENDED") continue;
    normalized[serial.toLowerCase()] = {
      status,
      reason: typeof entry.reason === "string" ? entry.reason : undefined,
      comment: typeof entry.comment === "string" ? entry.comment : undefined,
      expires: typeof entry.expires === "string" ? entry.expires : undefined,
    };
  }
  return normalized;
}

async function fetchStatusFromGoogle(): Promise<StatusCacheEntry> {
  const response = await fetch(STATUS_URL, {
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`Attestation status fetch failed: HTTP ${response.status}`);
  }
  const ttlFromHeader = parseCacheControl(response.headers.get("cache-control"));
  const ttlMs = Math.min(
    MAX_CACHE_MS,
    Math.max(MIN_CACHE_MS, ttlFromHeader ?? 60 * 60 * 1000)
  );
  const payload = (await response.json()) as unknown;
  const now = Date.now();
  return {
    entries: normalizeEntries(payload),
    fetchedAtMs: now,
    expiresAtMs: now + ttlMs,
  };
}

export async function getRevocationEntry(
  serialHex: string
): Promise<RevocationEntry | null> {
  const lookup = serialHex.toLowerCase().replace(/^0+/, "") || "0";
  const now = Date.now();
  let cache: StatusCacheEntry | null = memoryCache;

  if (!cache || cache.expiresAtMs <= now) {
    const persisted = await loadPersistentCache();
    if (persisted && persisted.expiresAtMs > now) {
      cache = persisted;
      memoryCache = persisted;
    } else {
      try {
        const fresh = await fetchStatusFromGoogle();
        cache = fresh;
        memoryCache = fresh;
        void writePersistentCache(fresh);
      } catch (error) {
        if (persisted) {
          console.warn(
            "attestation/statusList: refresh failed, using stale cache",
            error
          );
          cache = persisted;
          memoryCache = persisted;
        } else {
          throw error;
        }
      }
    }
  }

  const entries = cache!.entries;
  return entries[lookup] ?? entries[serialHex.toLowerCase()] ?? null;
}

export function resetStatusCacheForTesting(): void {
  memoryCache = null;
}
