import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import * as crypto from "crypto";
import { requireUid } from "../certificates/callerRole";

/**
 * Per-user FCM/APNs push token registry: `messagingTokens/{uid}/tokens/{tokenHash}`.
 * The token is the OS push channel the message trigger fans out to — this is the whole
 * low-bandwidth downlink story (piggyback the single OS push connection, no extra socket).
 * Doc id is a hash of the token so re-registering the same token is idempotent.
 */

const MAX_TOKEN_LEN = 4096;

function requireToken(data: Record<string, unknown>): string {
  const token = data.token;
  if (typeof token !== "string" || token.length === 0 || token.length > MAX_TOKEN_LEN) {
    throw new HttpsError("invalid-argument", "token must be a non-empty push token.");
  }
  return token;
}

function tokenDocId(token: string): string {
  return crypto.createHash("sha256").update(token).digest("base64url");
}

export const registerPushToken = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const token = requireToken(data);
    const platform =
      typeof data.platform === "string" && data.platform.length <= 32
        ? data.platform
        : "android";

    await getFirestore()
      .doc(`messagingTokens/${uid}/tokens/${tokenDocId(token)}`)
      .set({
        token,
        platform,
        updatedAt: FieldValue.serverTimestamp(),
      });

    return { ok: true };
  }
);

export const unregisterPushToken = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const token = requireToken(data);

    await getFirestore()
      .doc(`messagingTokens/${uid}/tokens/${tokenDocId(token)}`)
      .delete();

    return { ok: true };
  }
);
