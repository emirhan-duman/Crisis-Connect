import { decode as cborDecode } from "cbor-x";

/**
 * Apple App Attest attestation object schema (server-side).
 *
 * The attestation object is a CBOR-encoded blob with three top-level keys:
 *   - `fmt`: always "apple-appattest"
 *   - `attStmt`: { x5c: [DER, ...], receipt: DER }
 *   - `authData`: opaque bytes (RP ID hash, flags, counter, AAGUID, credId len,
 *                  credId, etc.)
 *
 * Reference:
 *   https://developer.apple.com/documentation/devicecheck/validating_apps_that_connect_to_your_server
 */

export interface ParsedAppleAttestation {
  fmt: string;
  x5cDer: Buffer[];
  receiptDer: Buffer;
  authData: Buffer;
  rpIdHash: Buffer;
  flags: number;
  counter: number;
  aaguid: Buffer;
  credentialId: Buffer;
  attestedCredPublicKeyCoseBytes: Buffer;
}

export class AppleAttestationParseError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
    this.name = "AppleAttestationParseError";
  }
}

function toBuffer(value: unknown, field: string): Buffer {
  if (Buffer.isBuffer(value)) return value;
  if (value instanceof Uint8Array) return Buffer.from(value);
  if (value instanceof ArrayBuffer) return Buffer.from(new Uint8Array(value));
  throw new AppleAttestationParseError(
    "attestation-malformed",
    `Expected ${field} to be bytes, got ${typeof value}.`
  );
}

function readUInt32BE(buf: Buffer, offset: number): number {
  if (offset + 4 > buf.length) {
    throw new AppleAttestationParseError(
      "attestation-malformed",
      "authData truncated while reading counter."
    );
  }
  return buf.readUInt32BE(offset);
}

function parseAuthData(authData: Buffer): {
  rpIdHash: Buffer;
  flags: number;
  counter: number;
  aaguid: Buffer;
  credentialId: Buffer;
  credPublicKeyCoseBytes: Buffer;
} {
  // Layout: rpIdHash(32) | flags(1) | counter(4) | aaguid(16) | credIdLen(2) | credId(n) | credPublicKey(cose)
  if (authData.length < 32 + 1 + 4 + 16 + 2) {
    throw new AppleAttestationParseError(
      "attestation-malformed",
      `authData too short (got ${authData.length} bytes).`
    );
  }
  let offset = 0;
  const rpIdHash = authData.subarray(offset, offset + 32);
  offset += 32;
  const flags = authData.readUInt8(offset);
  offset += 1;
  const counter = readUInt32BE(authData, offset);
  offset += 4;
  const aaguid = authData.subarray(offset, offset + 16);
  offset += 16;
  const credIdLen = authData.readUInt16BE(offset);
  offset += 2;
  if (offset + credIdLen > authData.length) {
    throw new AppleAttestationParseError(
      "attestation-malformed",
      "authData truncated reading credentialId."
    );
  }
  const credentialId = authData.subarray(offset, offset + credIdLen);
  offset += credIdLen;
  const credPublicKeyCoseBytes = authData.subarray(offset);
  return {
    rpIdHash,
    flags,
    counter,
    aaguid,
    credentialId,
    credPublicKeyCoseBytes,
  };
}

export function parseAppleAttestation(
  attestationObjectBytes: Buffer
): ParsedAppleAttestation {
  let decoded: unknown;
  try {
    decoded = cborDecode(attestationObjectBytes);
  } catch (error) {
    throw new AppleAttestationParseError(
      "attestation-cbor-malformed",
      `CBOR decode failed: ${(error as Error).message}`
    );
  }
  if (!decoded || typeof decoded !== "object") {
    throw new AppleAttestationParseError(
      "attestation-not-object",
      "Attestation object did not decode to a map."
    );
  }
  const root = decoded as Record<string, unknown>;
  const fmt = root.fmt;
  if (typeof fmt !== "string") {
    throw new AppleAttestationParseError(
      "attestation-fmt-missing",
      "Attestation object missing 'fmt' field."
    );
  }
  if (fmt !== "apple-appattest") {
    throw new AppleAttestationParseError(
      "attestation-fmt-unsupported",
      `Expected fmt='apple-appattest', got '${fmt}'.`
    );
  }
  const attStmt = root.attStmt as Record<string, unknown> | undefined;
  if (!attStmt || typeof attStmt !== "object") {
    throw new AppleAttestationParseError(
      "attestation-attStmt-missing",
      "Attestation statement missing."
    );
  }
  const x5cRaw = attStmt.x5c;
  if (!Array.isArray(x5cRaw) || x5cRaw.length === 0) {
    throw new AppleAttestationParseError(
      "attestation-x5c-missing",
      "x5c chain missing in attestation statement."
    );
  }
  const x5cDer = x5cRaw.map((entry, idx) => toBuffer(entry, `x5c[${idx}]`));
  const receiptDer = attStmt.receipt
    ? toBuffer(attStmt.receipt, "receipt")
    : Buffer.alloc(0);
  const authData = toBuffer(root.authData, "authData");
  const parsedAuth = parseAuthData(authData);

  return {
    fmt,
    x5cDer,
    receiptDer,
    authData,
    rpIdHash: parsedAuth.rpIdHash,
    flags: parsedAuth.flags,
    counter: parsedAuth.counter,
    aaguid: parsedAuth.aaguid,
    credentialId: parsedAuth.credentialId,
    attestedCredPublicKeyCoseBytes: parsedAuth.credPublicKeyCoseBytes,
  };
}
