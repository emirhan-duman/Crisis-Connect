import { X509Certificate, cryptoProvider } from "@peculiar/x509";
import { webcrypto } from "crypto";
import { getAttestationRoots } from "./rootStore";
import { getRevocationEntry } from "./statusList";
import {
  ParsedAttestation,
  SecurityRequirements,
  SecurityCheckOutcome,
  checkAttestationAgainstPolicy,
  findAttestationCertificate,
  parseAttestationExtension,
} from "./parseExtension";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
cryptoProvider.set(webcrypto as any);

export class AttestationVerificationError extends Error {
  readonly code: string;
  readonly detail?: string;

  constructor(code: string, message: string, detail?: string) {
    super(message);
    this.name = "AttestationVerificationError";
    this.code = code;
    this.detail = detail;
  }
}

export interface VerifiedAttestation {
  attestation: ParsedAttestation;
  leafCert: X509Certificate;
  rootFingerprint: string;
}

function toHex(buffer: ArrayBuffer | Uint8Array): string {
  const view = buffer instanceof Uint8Array
    ? buffer
    : new Uint8Array(buffer);
  let out = "";
  for (let i = 0; i < view.length; i++) {
    out += view[i].toString(16).padStart(2, "0");
  }
  return out;
}

async function thumbprintHex(cert: X509Certificate): Promise<string> {
  const raw = await cert.getThumbprint("SHA-256");
  return toHex(raw);
}

// SHA-256 over the certificate's SubjectPublicKeyInfo (the public key), as
// opposed to the whole certificate. The trusted Google attestation root must be
// matched by its public key, NOT by exact certificate bytes: devices ship their
// own copy of the Google root baked into the TEE/StrongBox at manufacture, and
// Google periodically re-issues the same root CA (same key + serial) with a new
// validity window. Those copies are not byte-identical, so a full-certificate
// thumbprint comparison falsely rejects genuine devices, while the public key is
// stable across re-issues.
async function publicKeyThumbprintHex(cert: X509Certificate): Promise<string> {
  const digest = await webcrypto.subtle.digest("SHA-256", cert.publicKey.rawData);
  return toHex(digest);
}

function pemListToChain(pemList: string[]): X509Certificate[] {
  if (!Array.isArray(pemList) || pemList.length === 0) {
    throw new AttestationVerificationError(
      "chain-empty",
      "Attestation chain is empty."
    );
  }
  try {
    return pemList.map((pem) => new X509Certificate(pem));
  } catch (error) {
    throw new AttestationVerificationError(
      "chain-malformed",
      "Attestation chain contains an unparseable certificate.",
      (error as Error).message
    );
  }
}

function normalizeSerial(serial: string): string {
  return serial.toLowerCase().replace(/^0+/, "") || "0";
}

async function verifySignatures(chain: X509Certificate[]): Promise<void> {
  for (let i = 0; i < chain.length - 1; i++) {
    let ok = false;
    try {
      ok = await chain[i].verify({ publicKey: chain[i + 1].publicKey });
    } catch (error) {
      throw new AttestationVerificationError(
        "chain-signature-error",
        `Chain link ${i} -> ${i + 1} failed signature verification.`,
        (error as Error).message
      );
    }
    if (!ok) {
      throw new AttestationVerificationError(
        "chain-signature-invalid",
        `Chain link ${i} -> ${i + 1} signature did not validate.`
      );
    }
  }
}

async function ensureChainEndsInGoogleRoot(
  chain: X509Certificate[]
): Promise<string> {
  const roots = await getAttestationRoots();
  if (roots.length === 0) {
    throw new AttestationVerificationError(
      "roots-unavailable",
      "Google attestation roots could not be loaded."
    );
  }
  const lastCert = chain[chain.length - 1];
  const lastThumb = await thumbprintHex(lastCert);
  const lastKeyThumb = await publicKeyThumbprintHex(lastCert);
  for (const root of roots) {
    const rootThumb = await thumbprintHex(root);
    // Primary anchor: the terminal cert's public key matches a trusted Google
    // root's public key. (Exact-thumbprint is kept as a fast path.) This is safe
    // because verifySignatures() has already proven the chain is signed up to
    // this key, so matching the key proves the chain is anchored to Google.
    const rootKeyThumb = await publicKeyThumbprintHex(root);
    if (rootThumb === lastThumb || rootKeyThumb === lastKeyThumb) {
      return rootThumb;
    }
  }
  const rootKeyThumbs = await Promise.all(roots.map((r) => publicKeyThumbprintHex(r)));
  const chainSubjects = chain.map((c) => c.subject).join(" || ");
  throw new AttestationVerificationError(
    "chain-untrusted-root",
    "Chain root is not in the trusted Google attestation root set.",
    `chainLen=${chain.length} lastSubject="${lastCert.subject}" lastIssuer="${lastCert.issuer}" ` +
      `lastKeyThumb=${lastKeyThumb} trustedRootCount=${roots.length} ` +
      `trustedKeyThumbs=[${rootKeyThumbs.join(",")}] chainSubjects=[${chainSubjects}]`
  );
}

async function checkRevocations(chain: X509Certificate[]): Promise<void> {
  for (const cert of chain) {
    const entry = await getRevocationEntry(normalizeSerial(cert.serialNumber));
    if (!entry) continue;
    throw new AttestationVerificationError(
      `cert-${entry.status.toLowerCase()}`,
      `Certificate ${cert.serialNumber} is ${entry.status} (${entry.reason ?? "UNSPECIFIED"}).`,
      entry.comment
    );
  }
}

function checkValidityDates(chain: X509Certificate[], now: Date): void {
  for (const cert of chain) {
    if (cert.notBefore > now) {
      throw new AttestationVerificationError(
        "cert-not-yet-valid",
        `Certificate ${cert.serialNumber} is not yet valid (notBefore=${cert.notBefore.toISOString()}).`
      );
    }
    if (cert.notAfter < now) {
      throw new AttestationVerificationError(
        "cert-expired",
        `Certificate ${cert.serialNumber} expired at ${cert.notAfter.toISOString()}.`
      );
    }
  }
}

export interface VerifyChainOptions {
  chainPem: string[];
  requirements: SecurityRequirements;
  now?: Date;
}

export async function verifyAttestationChain(
  options: VerifyChainOptions
): Promise<VerifiedAttestation> {
  const now = options.now ?? new Date();
  const chain = pemListToChain(options.chainPem);

  await verifySignatures(chain);
  const rootFingerprint = await ensureChainEndsInGoogleRoot(chain);
  await checkRevocations(chain);
  checkValidityDates(chain, now);

  const attestationCert = findAttestationCertificate(chain);
  const attestation = parseAttestationExtension(attestationCert);

  const leafCert = chain[0];
  const leafPublicKeyDer = Buffer.from(await leafCert.publicKey.rawData);

  const outcome: SecurityCheckOutcome = checkAttestationAgainstPolicy(
    attestation,
    leafPublicKeyDer,
    options.requirements
  );
  if (!outcome.ok) {
    throw new AttestationVerificationError(
      outcome.code ?? "policy-rejected",
      outcome.detail ?? "Attestation rejected by policy.",
      outcome.detail
    );
  }

  return {
    attestation,
    leafCert,
    rootFingerprint,
  };
}
