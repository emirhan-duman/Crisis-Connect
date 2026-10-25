import { compatibleCallable } from "./compatibilityRouting";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { getMessagingFirestore as getFirestore, currentMessagingDatabaseId, MULTIREGION_MESSAGING_DATABASE } from "./databaseTarget";
import { setHaPushToken } from "./highAvailabilityTokens";
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

export const registerPushToken = compatibleCallable("registerPushToken",
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const token = requireToken(data);
    const platform =
      typeof data.platform === "string" && data.platform.length <= 32
        ? data.platform
        : "android";
    const traffic = data.trafficPolicyVersion === 1 && typeof data.installationId === "string" &&
      /^[A-Za-z0-9_-]{1,128}$/.test(data.installationId)
      ? { installationId: data.installationId, trafficPolicyVersion: 1 } : {};

    if (currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE) {
      await setHaPushToken(uid, token, { token, platform, ...traffic, updatedAt: FieldValue.serverTimestamp() });
      return { ok: true };
    }
    await getFirestore()
      .doc(`messagingTokens/${uid}/tokens/${tokenDocId(token)}`)
      .set({
        token,
        platform,
        ...traffic,
        updatedAt: FieldValue.serverTimestamp(),
      });

    return { ok: true };
  }
);

export const unregisterPushToken = compatibleCallable("unregisterPushToken",
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;
    const token = requireToken(data);

    if (currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE) {
      await setHaPushToken(uid, token, null);
      return { ok: true };
    }

    await getFirestore()
      .doc(`messagingTokens/${uid}/tokens/${tokenDocId(token)}`)
      .delete();

    return { ok: true };
  }
);
