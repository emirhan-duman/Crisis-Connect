import * as crypto from "crypto";
import { X509Certificate, cryptoProvider } from "@peculiar/x509";
import { webcrypto } from "crypto";
import { decode as cborDecode } from "cbor-x";
import { getAppleAppAttestationRoot } from "./rootStore";
import {
  AppleAttestationParseError,
  parseAppleAttestation,
  ParsedAppleAttestation,
} from "./parseAttestation";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
cryptoProvider.set(webcrypto as any);

/**
 * Apple App Attest credential certificate nonce extension OID.
 * RFC: Apple-defined extension carrying SHA-256(authData || clientDataHash).
 */
const APPLE_NONCE_EXTENSION_OID = "1.2.840.113635.100.8.2";
const AAGUID_APPATTEST_PROD = Buffer.from("appattest\0\0\0\0\0\0\0", "binary");
const AAGUID_APPATTEST_DEV = Buffer.from("appattestdevelop", "binary");

export class AppleAttestationVerificationError extends Error {
  readonly code: string;
  readonly detail?: string;
  constructor(code: string, message: string, detail?: string) {
    super(message);
    this.code = code;
    this.detail = detail;
    this.name = "AppleAttestationVerificationError";
  }
}

export interface AppleVerifyRequirements {
  expectedBundleId: string;
  expectedChallenge: Buffer;
  allowDevelopmentEnvironment: boolean;
}

export interface AppleVerifiedAttestation {
  parsed: ParsedAppleAttestation;
  attestedPublicKeyDer: Buffer;
  attestedPublicKeyBase64: Buffer;
  isProduction: boolean;
  credentialIdBase64: string;
}

function derFromUint8(value: Uint8Array | Buffer): Buffer {
  return Buffer.isBuffer(value) ? value : Buffer.from(value);
}

async function verifyChainAgainstAppleRoot(
  x5cDer: Buffer[]
): Promise<X509Certificate[]> {
  if (x5cDer.length === 0) {
    throw new AppleAttestationVerificationError(
      "chain-empty",
      "Attestation x5c chain is empty."
    );
  }
  const chain = x5cDer.map((der) => new X509Certificate(new Uint8Array(der)));
  // Each cert in the chain must be signed by the next.
  for (let i = 0; i < chain.length - 1; i++) {
    let ok = false;
    try {
      ok = await chain[i].verify({ publicKey: chain[i + 1].publicKey });
    } catch (error) {
      throw new AppleAttestationVerificationError(
        "chain-signature-error",
        `Link ${i} -> ${i + 1} signature verification failed.`,
        (error as Error).message
      );
    }
    if (!ok) {
      throw new AppleAttestationVerificationError(
        "chain-signature-invalid",
        `Link ${i} -> ${i + 1} signature did not validate.`
      );
    }
  }
  // The terminal cert must be signed by Apple's App Attestation Root CA.
  const root = getAppleAppAttestationRoot();
  const terminal = chain[chain.length - 1];
  let rootVerifies = false;
  try {
    rootVerifies = await terminal.verify({ publicKey: root.publicKey });
  } catch (error) {
    throw new AppleAttestationVerificationError(
      "chain-root-verify-error",
      "Chain root verification threw.",
      (error as Error).message
    );
  }
  if (!rootVerifies) {
    throw new AppleAttestationVerificationError(
      "chain-untrusted-root",
      "Chain does not anchor to the Apple App Attestation Root CA."
    );
  }
  const now = new Date();
  for (const cert of chain) {
    if (cert.notBefore > now) {
      throw new AppleAttestationVerificationError(
        "cert-not-yet-valid",
        `Certificate not yet valid: ${cert.subject}`
      );
    }
    if (cert.notAfter < now) {
      throw new AppleAttestationVerificationError(
        "cert-expired",
        `Certificate expired: ${cert.subject}`
      );
    }
  }
  return chain;
}

function extractNonceFromCredCert(credCert: X509Certificate): Buffer {
  const extension = credCert.getExtension(APPLE_NONCE_EXTENSION_OID);
  if (!extension) {
    throw new AppleAttestationVerificationError(
      "nonce-extension-missing",
      `Credential certificate has no extension with OID ${APPLE_NONCE_EXTENSION_OID}.`
    );
  }
  // The extension value is itself a DER SEQUENCE { [1] OCTET STRING }. We can
  // CBOR-decoded simpler: it is wrapped as a tagged OCTET STRING. Practically,
  // Apple encodes it as: SEQUENCE { [1] EXPLICIT OCTET STRING <hash> }.
  // The OCTET STRING payload is the last 32 bytes of the extension value.
  const raw = Buffer.from(new Uint8Array(extension.value));
  if (raw.length < 32) {
    throw new AppleAttestationVerificationError(
      "nonce-extension-malformed",
      `Nonce extension too short (${raw.length} bytes).`
    );
  }
  // Find the final 32 bytes — the SHA-256 of authData || clientDataHash.
  return raw.subarray(raw.length - 32);
}

function buildExpectedNonce(authData: Buffer, clientDataHash: Buffer): Buffer {
  return crypto
    .createHash("sha256")
    .update(authData)
    .update(clientDataHash)
    .digest();
}

function decodeCoseEcP256PublicKeyToDer(coseBytes: Buffer): Buffer {
  // COSE_Key for ES256: {1: 2 (EC2), 3: -7 (ES256), -1: 1 (P-256), -2: x, -3: y}
  let decoded: unknown;
  try {
    decoded = cborDecode(coseBytes);
  } catch (error) {
    throw new AppleAttestationVerificationError(
      "cose-key-malformed",
      `Failed to CBOR-decode COSE key: ${(error as Error).message}`
    );
  }
  if (!decoded || typeof decoded !== "object") {
    throw new AppleAttestationVerificationError(
      "cose-key-not-map",
      "COSE key did not decode to a map."
    );
  }
  const map = decoded as Map<number, unknown> | Record<string, unknown>;
  const get = (key: number): unknown => {
    if (map instanceof Map) return map.get(key);
    return (map as Record<string, unknown>)[String(key)];
  };
  const kty = get(1);
  if (kty !== 2) {
    throw new AppleAttestationVerificationError(
      "cose-key-kty-invalid",
      `Expected COSE kty=2 (EC2), got ${String(kty)}.`
    );
  }
  const crv = get(-1);
  if (crv !== 1) {
    throw new AppleAttestationVerificationError(
      "cose-key-crv-invalid",
      `Expected COSE crv=1 (P-256), got ${String(crv)}.`
    );
  }
  const xRaw = get(-2);
  const yRaw = get(-3);
  if (!(xRaw instanceof Uint8Array) || !(yRaw instanceof Uint8Array)) {
    throw new AppleAttestationVerificationError(
      "cose-key-coords-missing",
      "COSE key missing x/y coordinates."
    );
  }
  const x = derFromUint8(xRaw);
  const y = derFromUint8(yRaw);
  if (x.length !== 32 || y.length !== 32) {
    throw new AppleAttestationVerificationError(
      "cose-key-coords-length",
      `Expected 32-byte x/y, got ${x.length}/${y.length}.`
    );
  }
  // Build SubjectPublicKeyInfo DER for EC P-256:
  // SEQUENCE {
  //   SEQUENCE { OID 1.2.840.10045.2.1 (ecPublicKey), OID 1.2.840.10045.3.1.7 (P-256) }
  //   BIT STRING (0x00 || 0x04 || x || y)
  // }
  const ecPublicKeyOid = Buffer.from("06072a8648ce3d0201", "hex");
  const p256Oid = Buffer.from("06082a8648ce3d030107", "hex");
  const algoSeq = Buffer.concat([
    Buffer.from([0x30, ecPublicKeyOid.length + p256Oid.length]),
    ecPublicKeyOid,
    p256Oid,
  ]);
  const uncompressedPoint = Buffer.concat([Buffer.from([0x04]), x, y]);
  // BIT STRING: length byte includes the leading "unused bits" byte (0x00).
  const bitString = Buffer.concat([
    Buffer.from([0x03, uncompressedPoint.length + 1, 0x00]),
    uncompressedPoint,
  ]);
  const inner = Buffer.concat([algoSeq, bitString]);
  return Buffer.concat([Buffer.from([0x30, inner.length]), inner]);
}

function isProductionAaguid(aaguid: Buffer): boolean {
  return aaguid.equals(AAGUID_APPATTEST_PROD);
}

function isDevelopmentAaguid(aaguid: Buffer): boolean {
  return aaguid.equals(AAGUID_APPATTEST_DEV);
}

export async function verifyAppleAttestation(args: {
  attestationObjectBytes: Buffer;
  keyIdBase64: string;
  challenge: Buffer;
  /**
   * Optional binding payload — when supplied, the clientDataHash is computed
   * as `SHA-256(challenge || boundData)` instead of `SHA-256(challenge)`.
   * This is how we bind the device's SecureEnclave public key (used as the
   * issued certificate's `publicKey` field) to the App Attest attestation:
   * the iOS client must call `attestKey(keyId, clientDataHash)` with the
   * same composite hash, otherwise the credential cert's nonce extension
   * will not match what we compute here.
   */
  boundData?: Buffer;
  expectedBundleId: string;
  allowDevelopmentEnvironment: boolean;
}): Promise<AppleVerifiedAttestation> {
  let parsed: ParsedAppleAttestation;
  try {
    parsed = parseAppleAttestation(args.attestationObjectBytes);
  } catch (error) {
    if (error instanceof AppleAttestationParseError) {
      throw new AppleAttestationVerificationError(error.code, error.message);
    }
    throw error;
  }

  // 1. Verify the x5c chain anchors in Apple's root.
  const chain = await verifyChainAgainstAppleRoot(parsed.x5cDer);
  const credCert = chain[0];

  // 2. Build expected nonce = SHA-256(authData || clientDataHash) and check
  //    against the nonce extension on the credential certificate.
  //    clientDataHash is SHA-256(challenge [|| boundData]).
  const clientDataDigest = crypto.createHash("sha256").update(args.challenge);
  if (args.boundData && args.boundData.length > 0) {
    clientDataDigest.update(args.boundData);
  }
  const clientDataHash = clientDataDigest.digest();
  const expectedNonce = buildExpectedNonce(parsed.authData, clientDataHash);
  const observedNonce = extractNonceFromCredCert(credCert);
  if (!expectedNonce.equals(observedNonce)) {
    throw new AppleAttestationVerificationError(
      "nonce-mismatch",
      "Nonce extension does not match SHA-256(authData || clientDataHash)."
    );
  }

  // 3. RP ID hash check.
  const expectedRpIdHash = crypto
    .createHash("sha256")
    .update(args.expectedBundleId)
    .digest();
  if (!expectedRpIdHash.equals(parsed.rpIdHash)) {
    throw new AppleAttestationVerificationError(
      "rp-id-hash-mismatch",
      `authData RP ID hash does not match SHA-256("${args.expectedBundleId}").`
    );
  }

  // 4. AAGUID must mark this as Apple App Attest (prod or, if allowed, dev).
  const isProd = isProductionAaguid(parsed.aaguid);
  const isDev = isDevelopmentAaguid(parsed.aaguid);
  if (!isProd && !(args.allowDevelopmentEnvironment && isDev)) {
    throw new AppleAttestationVerificationError(
      "aaguid-rejected",
      `AAGUID is not recognised as App Attest ${args.allowDevelopmentEnvironment ? "(prod or dev)" : "(prod)"}.`
    );
  }

  // 5. credentialId must equal the SHA-256 of the public key, which is also
  //    what the client used as its keyId base64. We verify by comparing the
  //    base64 of credentialId to the supplied keyId.
  const credentialIdBase64 = parsed.credentialId.toString("base64");
  if (credentialIdBase64 !== args.keyIdBase64.trim()) {
    throw new AppleAttestationVerificationError(
      "credential-id-mismatch",
      "Client-supplied keyId does not match credentialId from authData."
    );
  }

  // 6. Extract the attested public key (COSE EC2 P-256) and convert to
  //    SubjectPublicKeyInfo DER so the rest of the issuance flow (which
  //    expects X.509-encoded keys) can use it unchanged.
  const attestedPublicKeyDer = decodeCoseEcP256PublicKeyToDer(
    parsed.attestedCredPublicKeyCoseBytes
  );

  return {
    parsed,
    attestedPublicKeyDer,
    attestedPublicKeyBase64: Buffer.from(attestedPublicKeyDer.toString("base64")),
    isProduction: isProd,
    credentialIdBase64,
  };
}
