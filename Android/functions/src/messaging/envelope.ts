import { HttpsError } from "firebase-functions/v2/https";

/**
 * Server-visible OUTER envelope for an internet chat message.
 *
 * The actual message content lives inside {@link RelayEnvelope.ciphertext}, which is
 * end-to-end encrypted by the client (ECDH P-256 + HKDF + AES-256-GCM, mirroring the
 * app's `Crypto.kt`). The server NEVER decrypts it — it only validates the routing
 * metadata and fans the ciphertext out to the recipient. Template codes for common
 * crisis phrases live inside the ciphertext, so there is no server-side expansion and
 * therefore no plaintext exposure.
 */
// v1 = the interim authenticated-ECIES envelope (ephemeralPubKey/nonce/alg).
// v2 = the forward-secret Signal-protocol transport: opaque ciphertext + a ctype discriminator
//      ("prekey" = X3DH/PQXDH handshake message, "signal" = ratchet message). No ECDH fields.
export const MESSAGING_SCHEMA_VERSION = 1;
export const MESSAGING_SCHEMA_VERSION_SIGNAL = 2;
const SIGNAL_CTYPES = ["prekey", "signal"] as const;

// Size guards, expressed in base64 characters (~4 chars per 3 raw bytes).
const MAX_B64_CIPHERTEXT = 24_000; // ~18 KiB of plaintext+tag — comfortably inside FCM's 4 KB path once compact, generous for media-less text.
const MAX_B64_PUBKEY = 256; // P-256 raw point (65 B) or SPKI (~120 B) → well under.
const MAX_B64_NONCE = 32; // 12-byte GCM nonce → 16 b64 chars.
const MAX_ID_LEN = 128;

const B64_REGEX = /^[A-Za-z0-9+/]+=*$/;
const ID_REGEX = /^[A-Za-z0-9._:-]{1,128}$/;
// Firebase Auth UIDs are alphanumeric; keep the guard permissive but bounded.
const UID_REGEX = /^[A-Za-z0-9]{1,128}$/;

export type MessagePriority = "normal" | "high";

export interface RelayEnvelope {
  v: number;
  messageId: string;
  conversationId: string;
  senderUid: string;
  recipientUid: string;
  priority: MessagePriority;
  createdAtMs: number;
  ttlMs: number;
  ciphertext: string; // base64, opaque to the server
  // v1 (ECIES) fields:
  alg?: string;
  ephemeralPubKey?: string; // base64
  nonce?: string; // base64
  // v2 (Signal) field:
  ctype?: (typeof SIGNAL_CTYPES)[number];
}

function asObject(data: unknown): Record<string, unknown> {
  if (!data || typeof data !== "object") {
    throw new HttpsError("invalid-argument", "Request body must be an object.");
  }
  return data as Record<string, unknown>;
}

function requireId(obj: Record<string, unknown>, field: string): string {
  const value = obj[field];
  if (typeof value !== "string" || !ID_REGEX.test(value)) {
    throw new HttpsError(
      "invalid-argument",
      `${field} must be 1-${MAX_ID_LEN} chars of [A-Za-z0-9._:-].`
    );
  }
  return value;
}

function requireUid(obj: Record<string, unknown>, field: string): string {
  const value = obj[field];
  if (typeof value !== "string" || !UID_REGEX.test(value)) {
    throw new HttpsError("invalid-argument", `${field} must be a valid UID.`);
  }
  return value;
}

function requireBase64(
  obj: Record<string, unknown>,
  field: string,
  maxLen: number
): string {
  const value = obj[field];
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > maxLen ||
    !B64_REGEX.test(value)
  ) {
    throw new HttpsError(
      "invalid-argument",
      `${field} must be non-empty base64 no longer than ${maxLen} chars.`
    );
  }
  return value;
}

function requireFiniteNumber(
  obj: Record<string, unknown>,
  field: string
): number {
  const value = obj[field];
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new HttpsError("invalid-argument", `${field} must be a finite number.`);
  }
  return value;
}

/**
 * Validates a client-supplied envelope and pins {@link RelayEnvelope.senderUid} to the
 * authenticated caller — a client can never forge a message as someone else.
 */
export function parseRelayEnvelope(
  data: unknown,
  callerUid: string
): RelayEnvelope {
  const obj = asObject(data);

  const version = requireFiniteNumber(obj, "v");
  if (version !== MESSAGING_SCHEMA_VERSION && version !== MESSAGING_SCHEMA_VERSION_SIGNAL) {
    throw new HttpsError(
      "failed-precondition",
      `Unsupported schema version ${version}.`
    );
  }

  const recipientUid = requireUid(obj, "recipientUid");
  if (recipientUid === callerUid) {
    throw new HttpsError("invalid-argument", "recipientUid must differ from sender.");
  }

  const priorityRaw = obj.priority;
  const priority: MessagePriority = priorityRaw === "high" ? "high" : "normal";

  const createdAtMs = requireFiniteNumber(obj, "createdAtMs");
  const ttlMs = requireFiniteNumber(obj, "ttlMs");
  if (ttlMs <= 0 || ttlMs > 30 * 24 * 60 * 60 * 1000) {
    throw new HttpsError("invalid-argument", "ttlMs must be in (0, 30 days].");
  }

  const common = {
    messageId: requireId(obj, "messageId"),
    conversationId: requireId(obj, "conversationId"),
    senderUid: callerUid,
    recipientUid,
    priority,
    createdAtMs,
    ttlMs,
    ciphertext: requireBase64(obj, "ciphertext", MAX_B64_CIPHERTEXT),
  };

  if (version === MESSAGING_SCHEMA_VERSION_SIGNAL) {
    const ctype = obj.ctype;
    if (typeof ctype !== "string" || !SIGNAL_CTYPES.includes(ctype as (typeof SIGNAL_CTYPES)[number])) {
      throw new HttpsError("invalid-argument", `ctype must be one of ${SIGNAL_CTYPES.join(", ")}.`);
    }
    return { v: MESSAGING_SCHEMA_VERSION_SIGNAL, ...common, ctype: ctype as (typeof SIGNAL_CTYPES)[number] };
  }

  const alg = obj.alg;
  if (typeof alg !== "string" || alg.length === 0 || alg.length > 64) {
    throw new HttpsError("invalid-argument", "alg must be a short algorithm tag.");
  }
  return {
    v: MESSAGING_SCHEMA_VERSION,
    ...common,
    alg,
    ephemeralPubKey: requireBase64(obj, "ephemeralPubKey", MAX_B64_PUBKEY),
    nonce: requireBase64(obj, "nonce", MAX_B64_NONCE),
  };
}
