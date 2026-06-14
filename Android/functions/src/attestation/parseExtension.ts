import { X509Certificate } from "@peculiar/x509";
import { AsnParser } from "@peculiar/asn1-schema";
import {
  KeyMintKeyDescription,
  KeyDescription,
  SecurityLevel,
  VerifiedBootState,
  RootOfTrust,
  AuthorizationList,
  id_ce_keyDescription,
} from "@peculiar/asn1-android";

export const ATTESTATION_EXTENSION_OID = id_ce_keyDescription;

export interface ParsedAttestation {
  attestationVersion: number;
  attestationSecurityLevel: SecurityLevel;
  keyMintVersion: number;
  keyMintSecurityLevel: SecurityLevel;
  attestationChallenge: Buffer;
  uniqueId: Buffer;
  softwareEnforced: AuthorizationList;
  hardwareEnforced: AuthorizationList;
  rootOfTrust: RootOfTrust | null;
  attestationApplicationIdBytes: Buffer | null;
}

function toBuffer(value: ArrayBuffer | Uint8Array | undefined): Buffer {
  if (!value) return Buffer.alloc(0);
  if (value instanceof Uint8Array) return Buffer.from(value);
  return Buffer.from(new Uint8Array(value));
}

function octetToBuffer(octet: { buffer: ArrayBuffer } | undefined): Buffer {
  if (!octet) return Buffer.alloc(0);
  return toBuffer(octet.buffer);
}

function isModernKeyMintVersion(version: number): boolean {
  return version >= 300;
}

function decodeWithFallback(
  bytes: ArrayBuffer
): { description: KeyMintKeyDescription; usedLegacy: boolean } {
  try {
    const modern = AsnParser.parse(bytes, KeyMintKeyDescription);
    if (isModernKeyMintVersion(Number(modern.attestationVersion))) {
      return { description: modern, usedLegacy: false };
    }
    return {
      description: KeyMintKeyDescription.fromLegacyKeyDescription(
        AsnParser.parse(bytes, KeyDescription)
      ),
      usedLegacy: true,
    };
  } catch (modernError) {
    try {
      const legacy = AsnParser.parse(bytes, KeyDescription);
      return {
        description: KeyMintKeyDescription.fromLegacyKeyDescription(legacy),
        usedLegacy: true,
      };
    } catch (legacyError) {
      throw new AttestationParseError(
        "attestation-extension-malformed",
        `Both KeyMint and legacy schemas failed: modern=${(modernError as Error).message}; legacy=${(legacyError as Error).message}`
      );
    }
  }
}

export class AttestationParseError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.code = code;
    this.name = "AttestationParseError";
  }
}

export function findAttestationCertificate(
  chain: X509Certificate[]
): X509Certificate {
  if (chain.length === 0) {
    throw new AttestationParseError("chain-empty", "Certificate chain is empty.");
  }
  // AOSP guidance: "Only the first occurrence of the extension in the chain
  // can be trusted." Iterate from root-end toward leaf so we stop at the
  // earliest (closest-to-root) cert that carries the attestation extension.
  for (let i = chain.length - 1; i >= 0; i--) {
    const cert = chain[i];
    if (cert.getExtension(ATTESTATION_EXTENSION_OID)) {
      return cert;
    }
  }
  throw new AttestationParseError(
    "attestation-extension-missing",
    "No certificate in the chain carries the key attestation extension."
  );
}

export function parseAttestationExtension(
  cert: X509Certificate
): ParsedAttestation {
  const extension = cert.getExtension(ATTESTATION_EXTENSION_OID);
  if (!extension) {
    throw new AttestationParseError(
      "attestation-extension-missing",
      "Certificate does not contain the key attestation extension."
    );
  }

  const { description } = decodeWithFallback(extension.value);

  return {
    attestationVersion: Number(description.attestationVersion),
    attestationSecurityLevel: description.attestationSecurityLevel,
    keyMintVersion: description.keyMintVersion,
    keyMintSecurityLevel: description.keyMintSecurityLevel,
    attestationChallenge: octetToBuffer(description.attestationChallenge),
    uniqueId: octetToBuffer(description.uniqueId),
    softwareEnforced: description.softwareEnforced,
    hardwareEnforced: description.hardwareEnforced,
    rootOfTrust:
      description.hardwareEnforced.rootOfTrust ??
      description.softwareEnforced.rootOfTrust ??
      null,
    attestationApplicationIdBytes:
      description.hardwareEnforced.attestationApplicationId
        ? octetToBuffer(description.hardwareEnforced.attestationApplicationId)
        : description.softwareEnforced.attestationApplicationId
          ? octetToBuffer(description.softwareEnforced.attestationApplicationId)
          : null,
  };
}

export interface SecurityRequirements {
  acceptedSecurityLevels: SecurityLevel[];
  requireDeviceLocked: boolean;
  requireVerifiedBoot: boolean;
  expectedChallenge: Buffer;
  expectedPublicKeyDer: Buffer | null;
  minOsPatchLevel?: number;
}

export interface SecurityCheckOutcome {
  ok: boolean;
  code?: string;
  detail?: string;
}

export function checkAttestationAgainstPolicy(
  attestation: ParsedAttestation,
  leafCertPublicKeyDer: Buffer,
  requirements: SecurityRequirements
): SecurityCheckOutcome {
  if (!requirements.acceptedSecurityLevels.includes(attestation.attestationSecurityLevel)) {
    return {
      ok: false,
      code: "security-level-rejected",
      detail: `Got level ${attestation.attestationSecurityLevel}, accepted ${requirements.acceptedSecurityLevels.join(",")}`,
    };
  }

  if (attestation.attestationChallenge.length === 0) {
    return { ok: false, code: "challenge-missing", detail: "Extension has no challenge." };
  }

  if (!attestation.attestationChallenge.equals(requirements.expectedChallenge)) {
    return {
      ok: false,
      code: "challenge-mismatch",
      detail: "Attestation challenge does not match server-issued nonce.",
    };
  }

  if (requirements.requireVerifiedBoot || requirements.requireDeviceLocked) {
    const rot = attestation.rootOfTrust;
    if (!rot) {
      return { ok: false, code: "root-of-trust-missing", detail: "RootOfTrust block absent." };
    }
    if (requirements.requireDeviceLocked && !rot.deviceLocked) {
      return { ok: false, code: "device-unlocked", detail: "Bootloader is unlocked." };
    }
    if (
      requirements.requireVerifiedBoot &&
      rot.verifiedBootState !== VerifiedBootState.verified
    ) {
      return {
        ok: false,
        code: "verified-boot-not-green",
        detail: `verifiedBootState=${rot.verifiedBootState}`,
      };
    }
  }

  if (requirements.expectedPublicKeyDer) {
    if (!requirements.expectedPublicKeyDer.equals(leafCertPublicKeyDer)) {
      return {
        ok: false,
        code: "public-key-mismatch",
        detail: "Attested public key does not match the key the client claims to bind.",
      };
    }
  }

  if (typeof requirements.minOsPatchLevel === "number") {
    const patch =
      attestation.hardwareEnforced.osPatchLevel ??
      attestation.softwareEnforced.osPatchLevel ??
      0;
    if (patch < requirements.minOsPatchLevel) {
      return {
        ok: false,
        code: "os-patch-too-old",
        detail: `osPatchLevel=${patch} < min=${requirements.minOsPatchLevel}`,
      };
    }
  }

  return { ok: true };
}

export { SecurityLevel, VerifiedBootState };
