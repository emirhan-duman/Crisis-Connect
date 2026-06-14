import { X509Certificate } from "@peculiar/x509";
import { getFirestore } from "firebase-admin/firestore";

const ROOTS_URL = "https://android.googleapis.com/attestation/root";
const FIRESTORE_DOC = "system/attestation_roots_cache";
const MIN_CACHE_MS = 60 * 60 * 1000;
const MAX_CACHE_MS = 7 * 24 * 60 * 60 * 1000;

interface RootsCacheEntry {
  pemList: string[];
  fetchedAtMs: number;
  expiresAtMs: number;
}

let memoryCache: RootsCacheEntry | null = null;

function parseCacheControl(headerValue: string | null): number | null {
  if (!headerValue) return null;
  const match = headerValue.match(/max-age=(\d+)/i);
  if (!match) return null;
  const seconds = Number.parseInt(match[1], 10);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  return seconds * 1000;
}

async function loadPersistentCache(): Promise<RootsCacheEntry | null> {
  try {
    const snap = await getFirestore().doc(FIRESTORE_DOC).get();
    if (!snap.exists) return null;
    const data = snap.data() as Partial<RootsCacheEntry> | undefined;
    if (!data?.pemList?.length || typeof data.expiresAtMs !== "number") return null;
    return {
      pemList: data.pemList,
      fetchedAtMs: data.fetchedAtMs ?? 0,
      expiresAtMs: data.expiresAtMs,
    };
  } catch (error) {
    console.warn("attestation/rootStore: persistent cache read failed", error);
    return null;
  }
}

async function writePersistentCache(entry: RootsCacheEntry): Promise<void> {
  try {
    await getFirestore().doc(FIRESTORE_DOC).set(entry);
  } catch (error) {
    console.warn("attestation/rootStore: persistent cache write failed", error);
  }
}

async function fetchRootsFromGoogle(): Promise<RootsCacheEntry> {
  const response = await fetch(ROOTS_URL, {
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`Attestation roots fetch failed: HTTP ${response.status}`);
  }
  const ttlFromHeader = parseCacheControl(response.headers.get("cache-control"));
  const ttlMs = Math.min(
    MAX_CACHE_MS,
    Math.max(MIN_CACHE_MS, ttlFromHeader ?? 24 * 60 * 60 * 1000)
  );
  const payload = (await response.json()) as unknown;
  const pemList = extractPemList(payload);
  if (pemList.length === 0) {
    throw new Error("Attestation roots payload was empty");
  }
  const now = Date.now();
  return {
    pemList,
    fetchedAtMs: now,
    expiresAtMs: now + ttlMs,
  };
}

function extractPemList(payload: unknown): string[] {
  if (!payload || typeof payload !== "object") return [];
  const result: string[] = [];
  const visit = (value: unknown): void => {
    if (typeof value === "string") {
      const trimmed = value.trim();
      if (trimmed.includes("-----BEGIN CERTIFICATE-----")) {
        result.push(trimmed);
      }
    } else if (Array.isArray(value)) {
      value.forEach(visit);
    } else if (value && typeof value === "object") {
      Object.values(value as Record<string, unknown>).forEach(visit);
    }
  };
  visit(payload);
  return result;
}

export async function getAttestationRoots(): Promise<X509Certificate[]> {
  const now = Date.now();
  if (memoryCache && memoryCache.expiresAtMs > now) {
    return memoryCache.pemList.map((pem) => new X509Certificate(pem));
  }

  const persisted = await loadPersistentCache();
  if (persisted && persisted.expiresAtMs > now) {
    memoryCache = persisted;
    return persisted.pemList.map((pem) => new X509Certificate(pem));
  }

  try {
    const fresh = await fetchRootsFromGoogle();
    memoryCache = fresh;
    void writePersistentCache(fresh);
    return fresh.pemList.map((pem) => new X509Certificate(pem));
  } catch (error) {
    if (persisted) {
      console.warn(
        "attestation/rootStore: refresh failed, falling back to stale cache",
        error
      );
      memoryCache = persisted;
      return persisted.pemList.map((pem) => new X509Certificate(pem));
    }
    throw error;
  }
}

export function resetRootsCacheForTesting(): void {
  memoryCache = null;
}
