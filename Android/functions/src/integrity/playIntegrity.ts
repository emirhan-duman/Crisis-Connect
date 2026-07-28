import { GoogleAuth } from "google-auth-library";

const DECODE_SCOPE = "https://www.googleapis.com/auth/playintegrity";

export class IntegrityVerificationError extends Error {
  readonly code: string;
  readonly detail?: string;

  constructor(code: string, message: string, detail?: string) {
    super(message);
    this.name = "IntegrityVerificationError";
    this.code = code;
    this.detail = detail;
  }
}

export type DeviceRecognitionVerdict =
  | "MEETS_BASIC_INTEGRITY"
  | "MEETS_DEVICE_INTEGRITY"
  | "MEETS_STRONG_INTEGRITY"
  | "MEETS_VIRTUAL_INTEGRITY";

export type AppRecognitionVerdict =
  | "PLAY_RECOGNIZED"
  | "UNRECOGNIZED_VERSION"
  | "UNEVALUATED";

export type LicensingVerdict =
  | "LICENSED"
  | "UNLICENSED"
  | "UNEVALUATED"
  | "USER_PAID_BEFORE";

export interface DecodedIntegrity {
  packageName: string;
  appVersion?: string;
  certificateSha256Digests: string[];
  nonceBase64: string;
  timestampMs: number;
  deviceRecognitionVerdict: DeviceRecognitionVerdict[];
  appRecognitionVerdict: AppRecognitionVerdict;
  licensingVerdict?: LicensingVerdict;
}

export interface IntegrityRequirements {
  packageName: string;
  acceptedDeviceVerdicts: DeviceRecognitionVerdict[];
  requirePlayRecognition: boolean;
  expectedNonceBase64: string;
  acceptedCertificateSha256Digests?: string[];
  maxAgeMs?: number;
}

interface RawIntegrityResponse {
  tokenPayloadExternal?: {
    requestDetails?: {
      requestPackageName?: string;
      nonce?: string;
      timestampMillis?: string;
    };
    appIntegrity?: {
      appRecognitionVerdict?: AppRecognitionVerdict;
      packageName?: string;
      certificateSha256Digest?: string[];
      versionCode?: string;
    };
    deviceIntegrity?: {
      deviceRecognitionVerdict?: DeviceRecognitionVerdict[];
    };
    accountDetails?: {
      appLicensingVerdict?: LicensingVerdict;
    };
  };
}

let cachedAuth: GoogleAuth | null = null;

function getAuth(): GoogleAuth {
  if (!cachedAuth) {
    cachedAuth = new GoogleAuth({ scopes: [DECODE_SCOPE] });
  }
  return cachedAuth;
}

async function callDecodeApi(
  packageName: string,
  integrityToken: string
): Promise<RawIntegrityResponse> {
  const auth = getAuth();
  const client = await auth.getClient();
  const url = `https://playintegrity.googleapis.com/v1/${encodeURIComponent(packageName)}:decodeIntegrityToken`;
  const tokenResponse = await client.getAccessToken();
  const accessToken = typeof tokenResponse === "string" ? tokenResponse : tokenResponse?.token;
  if (!accessToken) {
    throw new IntegrityVerificationError(
      "credentials-missing",
      "Could not acquire Google API access token for Play Integrity."
    );
  }
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({ integrityToken }),
  });
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new IntegrityVerificationError(
      "decode-api-error",
      `Play Integrity decode API returned HTTP ${response.status}.`,
      text.slice(0, 500)
    );
  }
  return (await response.json()) as RawIntegrityResponse;
}

function normalizeDecoded(raw: RawIntegrityResponse): DecodedIntegrity {
  const payload = raw.tokenPayloadExternal;
  if (!payload) {
    throw new IntegrityVerificationError(
      "decode-payload-missing",
      "Play Integrity decode response missing tokenPayloadExternal."
    );
  }
  const req = payload.requestDetails ?? {};
  const app = payload.appIntegrity ?? {};
  const device = payload.deviceIntegrity ?? {};
  const account = payload.accountDetails ?? {};
  return {
    packageName: app.packageName ?? req.requestPackageName ?? "",
    appVersion: app.versionCode,
    certificateSha256Digests: app.certificateSha256Digest ?? [],
    nonceBase64: req.nonce ?? "",
    timestampMs: Number.parseInt(req.timestampMillis ?? "0", 10) || 0,
    deviceRecognitionVerdict: device.deviceRecognitionVerdict ?? [],
    appRecognitionVerdict: app.appRecognitionVerdict ?? "UNEVALUATED",
    licensingVerdict: account.appLicensingVerdict,
  };
}

function base64UrlToStandardBase64(input: string): string {
  return input.replace(/-/g, "+").replace(/_/g, "/");
}

function compareBase64Loose(a: string, b: string): boolean {
  const normalize = (v: string): string =>
    base64UrlToStandardBase64(v).replace(/=+$/g, "");
  return normalize(a) === normalize(b);
}

export async function decodeAndVerifyIntegrity(
  integrityToken: string,
  requirements: IntegrityRequirements,
  now: number = Date.now()
): Promise<DecodedIntegrity> {
  if (!integrityToken || typeof integrityToken !== "string") {
    throw new IntegrityVerificationError(
      "token-missing",
      "Integrity token was not provided."
    );
  }

  const raw = await callDecodeApi(requirements.packageName, integrityToken);
  const decoded = normalizeDecoded(raw);

  if (decoded.packageName !== requirements.packageName) {
    throw new IntegrityVerificationError(
      "package-mismatch",
      `Token packageName=${decoded.packageName} expected=${requirements.packageName}`
    );
  }

  if (!compareBase64Loose(decoded.nonceBase64, requirements.expectedNonceBase64)) {
    throw new IntegrityVerificationError(
      "nonce-mismatch",
      "Integrity token nonce did not match the server-issued attestation challenge."
    );
  }

  const accepted = requirements.acceptedDeviceVerdicts;
  const intersection = decoded.deviceRecognitionVerdict.filter((v) =>
    accepted.includes(v)
  );
  if (intersection.length === 0) {
    throw new IntegrityVerificationError(
      "device-integrity-insufficient",
      `Token verdicts=[${decoded.deviceRecognitionVerdict.join(",")}] accepted=[${accepted.join(",")}]`
    );
  }

  if (
    requirements.requirePlayRecognition &&
    decoded.appRecognitionVerdict !== "PLAY_RECOGNIZED"
  ) {
    throw new IntegrityVerificationError(
      "app-not-play-recognized",
      `appRecognitionVerdict=${decoded.appRecognitionVerdict}`,
      `appRecognitionVerdict=${decoded.appRecognitionVerdict} ` +
        `appLicensingVerdict=${decoded.licensingVerdict ?? "n/a"} ` +
        `appPackage=${decoded.packageName} appVersion=${decoded.appVersion ?? "n/a"} ` +
        `certDigests=[${decoded.certificateSha256Digests.join(",")}] ` +
        `deviceVerdicts=[${decoded.deviceRecognitionVerdict.join(",")}]`
    );
  }

  if (
    requirements.acceptedCertificateSha256Digests &&
    requirements.acceptedCertificateSha256Digests.length > 0
  ) {
    const haystack = decoded.certificateSha256Digests.map((d) =>
      base64UrlToStandardBase64(d).replace(/=+$/g, "")
    );
    const needles = requirements.acceptedCertificateSha256Digests.map((d) =>
      base64UrlToStandardBase64(d).replace(/=+$/g, "")
    );
    const matched = haystack.some((h) => needles.includes(h));
    if (!matched) {
      throw new IntegrityVerificationError(
        "app-signing-mismatch",
        "Integrity token app signing cert is not in the accepted list."
      );
    }
  }

  if (requirements.maxAgeMs && decoded.timestampMs > 0) {
    const ageMs = now - decoded.timestampMs;
    if (ageMs > requirements.maxAgeMs) {
      throw new IntegrityVerificationError(
        "token-too-old",
        `Token age=${ageMs}ms exceeds max=${requirements.maxAgeMs}ms.`
      );
    }
  }

  return decoded;
}
