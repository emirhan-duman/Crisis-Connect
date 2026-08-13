import * as http2 from "http2";
import * as crypto from "crypto";
import { defineSecret } from "firebase-functions/params";
import { logger } from "firebase-functions/v2";

/**
 * Direct APNs (HTTP/2) VoIP-push sender. PushKit VoIP pushes CANNOT go through FCM/Firebase
 * Admin — they must be delivered straight to Apple with `apns-push-type: voip`, priority 10, to
 * the app's dedicated PushKit token (topic `<bundle>.voip`). This is the only path that wakes a
 * force-quit iOS app so CallKit can ring an incoming internet call.
 *
 * Auth is a provider JWT (ES256 over the downloaded .p8 key), cached ~50 min. The three inputs
 * are Firebase secrets the project owner sets once:
 *   APNS_AUTH_KEY_P8  — full contents of the AuthKey_XXXX.p8 (BEGIN/END PRIVATE KEY block)
 *   APNS_KEY_ID       — the 10-char Key ID of that key
 *   APNS_TEAM_ID      — the 10-char Apple Developer Team ID
 */

export const apnsAuthKeyP8 = defineSecret("APNS_AUTH_KEY_P8");
export const apnsKeyId = defineSecret("APNS_KEY_ID");
export const apnsTeamId = defineSecret("APNS_TEAM_ID");

// Production APNs. The token-based (.p8) path serves both the sandbox and production APNs
// environments for the SAME token; api.push.apple.com works for TestFlight/App Store builds.
// Development builds (Xcode-installed, aps-environment=development) need api.sandbox.push.apple.com.
const APNS_HOST = "https://api.push.apple.com";
const APNS_SANDBOX_HOST = "https://api.sandbox.push.apple.com";

// Sticky APNs environment: set to whichever host actually accepted a token, so later rings skip the
// wasted failed attempt against the other one (see sendVoipPush).
let preferredApnsHost: string | null = null;
const VOIP_TOPIC_SUFFIX = ".voip";

interface CachedToken {
  jwt: string;
  issuedAtSec: number;
}
let cachedToken: CachedToken | null = null;

/** Build (or reuse) the ES256 provider JWT. APNs rejects tokens older than 60 min; refresh at 50. */
function providerToken(): string {
  const nowSec = Math.floor(Date.now() / 1000);
  if (cachedToken && nowSec - cachedToken.issuedAtSec < 50 * 60) {
    return cachedToken.jwt;
  }
  const keyId = apnsKeyId.value().trim();
  const teamId = apnsTeamId.value().trim();
  const p8 = apnsAuthKeyP8.value();

  const header = { alg: "ES256", kid: keyId };
  const payload = { iss: teamId, iat: nowSec };
  const encode = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj)).toString("base64url");
  const signingInput = `${encode(header)}.${encode(payload)}`;
  const signer = crypto.createSign("SHA256");
  signer.update(signingInput);
  // ES256 signatures must be JOSE (raw r||s), which Node emits with dsaEncoding "ieee-p1363".
  const signature = signer.sign(
    { key: p8, dsaEncoding: "ieee-p1363" },
    "base64url"
  );
  const jwt = `${signingInput}.${signature}`;
  cachedToken = { jwt, issuedAtSec: nowSec };
  return jwt;
}

export interface VoipPushPayload {
  callId: string;
  conversationId: string;
  senderUid: string;
  callerName: string;
  hasVideo: boolean;
  /** Defaults to the citizen-call wake contract. */
  type?: "call" | "authority_call_v2";
  authorityScopeType?: "agency" | "hierarchy";
  authorityChannelId?: string;
  authoritySignalId?: string;
}

/**
 * Delivers one VoIP push to [deviceToken]. Returns the APNs status: 200 = accepted; 410 (or a
 * "BadDeviceToken"/"Unregistered" reason) means the token is dead and the caller should prune it.
 * Tries production APNs first, then falls back to sandbox for development-signed builds.
 */
export async function sendVoipPush(
  deviceToken: string,
  bundleId: string,
  payload: VoipPushPayload
): Promise<{ status: number; shouldPrune: boolean }> {
  const body = JSON.stringify({
    aps: {},
    type: payload.type ?? "call",
    callId: payload.callId,
    conversationId: payload.conversationId,
    senderUid: payload.senderUid,
    callerName: payload.callerName,
    hasVideo: payload.hasVideo ? "1" : "0",
    ...(payload.type === "authority_call_v2" ? {
      authorityScopeType: payload.authorityScopeType,
      authorityChannelId: payload.authorityChannelId,
      authoritySignalId: payload.authoritySignalId,
    } : {}),
  });
  const jwt = providerToken();

  const attempt = (host: string) =>
    new Promise<{ status: number; reason: string }>((resolve, reject) => {
      const client = http2.connect(host);
      client.on("error", reject);
      const req = client.request({
        ":method": "POST",
        ":path": `/3/device/${deviceToken}`,
        authorization: `bearer ${jwt}`,
        "apns-topic": `${bundleId}${VOIP_TOPIC_SUFFIX}`,
        "apns-push-type": "voip",
        "apns-priority": "10",
        "apns-expiration": String(Math.floor(Date.now() / 1000) + 60),
        "content-type": "application/json",
      });
      let status = 0;
      let data = "";
      req.on("response", (headers) => {
        status = Number(headers[":status"] ?? 0);
      });
      req.setEncoding("utf8");
      req.on("data", (chunk) => (data += chunk));
      req.on("end", () => {
        client.close();
        let reason = "";
        try {
          reason = data ? (JSON.parse(data).reason ?? "") : "";
        } catch {
          reason = data;
        }
        resolve({ status, reason });
      });
      req.on("error", reject);
      req.write(body);
      req.end();
    });

  // Start with whichever environment last worked. A development-signed build (Xcode/devicectl
  // install) holds a SANDBOX token, so without this every ring burned a full failed production
  // round-trip first — pure added latency before the callee's phone rings. The reserved warm
  // instance keeps this across calls.
  const startedAt = Date.now();
  const firstHost = preferredApnsHost ?? APNS_HOST;
  const otherHost = firstHost === APNS_HOST ? APNS_SANDBOX_HOST : APNS_HOST;
  let usedHost = firstHost;
  let result: { status: number; reason: string };
  try {
    result = await attempt(firstHost);
    // A development-signed build's token is unknown to production APNs → retry the other one.
    if (result.status === 400 && result.reason === "BadDeviceToken") {
      usedHost = otherHost;
      result = await attempt(otherHost);
    }
    if (result.status === 200) preferredApnsHost = usedHost;
  } catch (err) {
    logger.warn("APNs VoIP push transport error", err);
    return { status: 0, shouldPrune: false };
  }
  logger.info(
    `APNs VoIP push: status=${result.status} host=${usedHost} ms=${Date.now() - startedAt}`
  );

  const shouldPrune =
    result.status === 410 ||
    result.reason === "Unregistered" ||
    result.reason === "BadDeviceToken";
  if (result.status !== 200) {
    logger.warn(
      `APNs VoIP push non-200: status=${result.status} reason=${result.reason}`
    );
  }
  return { status: result.status, shouldPrune };
}
