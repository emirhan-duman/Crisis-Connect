import { onCall, onRequest, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import * as crypto from "crypto";
import { normalizePhone, hashIdentifier } from "./identityKeys";
import {
  callingCodeOf,
  enforcePumpingRiskGate,
  enforceRateLimits,
  loadOtpConfig,
  recordOtpOutcome,
  spendLimitSpecs,
  HOUR_MS,
  LimitSpec,
} from "./otpGuard";

/**
 * Tier-3 phone verification fallback: Twilio Verify OTP + server-side account linking.
 *
 * Firebase Phone Auth's send path requires an app-verification token (Play Integrity /
 * reCAPTCHA) that some devices can never produce (old browsers, degraded GMS) and that
 * the backend sometimes rejects opaquely ("Error code:39" — see the 2026-07 incident).
 * This pair of callables is the device-independent escape hatch: Twilio delivers and
 * checks the OTP (no captcha, no attestation), then we bind the proven number to the
 * caller's Firebase account server-side with the Admin SDK.
 *
 * Anti-abuse: callers must be signed in (the app bootstraps anonymous auth) and sends
 * are metered per phone, per uid and per IP. Those three rotate cheaply, so the ceilings
 * that actually bound spend — destination-country caps, a global cap, the App Check
 * trust tier and the Lookup pumping-risk gate — live in otpGuard.ts. Codes only ever
 * exist inside Twilio.
 */

const twilioAccountSid = defineSecret("TWILIO_ACCOUNT_SID");
const twilioAuthToken = defineSecret("TWILIO_AUTH_TOKEN");
const twilioVerifyServiceSid = defineSecret("TWILIO_VERIFY_SERVICE_SID");

const PHONE_SENDS_PER_HOUR = 5;
const UID_SENDS_PER_HOUR = 8;
const IP_SENDS_PER_HOUR = 12;
const UID_CHECKS_PER_HOUR = 30;

/** Privacy-preserving throttle key for an IP (raw IPs are never stored). */
function ipThrottleKey(request: CallableRequest): string | null {
  const ip = request.rawRequest?.ip;
  if (!ip) return null;
  return "ip:" + crypto.createHash("sha256").update(`otp-ip:${ip}`).digest("base64url").slice(0, 24);
}

function requireUid(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign-in required.");
  }
  return uid;
}

interface TwilioVerifyResponse {
  status?: string;
  code?: number;
  [key: string]: unknown;
}

async function twilioVerifyCall(
  path: "Verifications" | "VerificationCheck",
  form: Record<string, string>
): Promise<TwilioVerifyResponse> {
  const sid = twilioAccountSid.value().trim();
  const token = twilioAuthToken.value().trim();
  const serviceSid = twilioVerifyServiceSid.value().trim();
  if (!sid || !token || !serviceSid) {
    console.error("Twilio Verify secrets are not configured.");
    throw new HttpsError("failed-precondition", "OTP service is not configured.");
  }
  const response = await fetch(
    `https://verify.twilio.com/v2/Services/${encodeURIComponent(serviceSid)}/${path}`,
    {
      method: "POST",
      headers: {
        Authorization: `Basic ${Buffer.from(`${sid}:${token}`).toString("base64")}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams(form).toString(),
    }
  );
  const body = (await response.json().catch(() => ({}))) as TwilioVerifyResponse;
  if (!response.ok) {
    // 60200 invalid number, 60203 rate-limit/max send attempts, 60202 max check attempts …
    console.error("Twilio Verify error", response.status, JSON.stringify(body).slice(0, 300));
    if (response.status === 429 || body.code === 60203 || body.code === 60202) {
      throw new HttpsError("resource-exhausted", "Too many attempts. Try again later.");
    }
    if (response.status === 400) {
      throw new HttpsError("invalid-argument", "The phone number was rejected.");
    }
    throw new HttpsError("unavailable", "OTP delivery failed. Try again.");
  }
  return body;
}

export const requestPhoneOtp = onCall(
  { region: "us-central1", secrets: [twilioAccountSid, twilioAuthToken, twilioVerifyServiceSid] },
  async (request) => {
    const uid = requireUid(request);
    const phone = normalizePhone(request.data?.phone);
    if (!phone) {
      throw new HttpsError("invalid-argument", "A valid E.164 phone number is required.");
    }
    // Optional BCP-47-ish locale ("tr", "pt-BR") localizes the SMS to the DEVICE language;
    // without it Twilio falls back to the phone number's country default.
    const rawLocale = typeof request.data?.locale === "string" ? request.data.locale.trim() : "";
    const locale = /^[a-z]{2}(-[A-Z]{2})?$/.test(rawLocale) ? rawLocale : undefined;

    const config = await loadOtpConfig();
    if (!config.enabled) {
      console.error(`requestPhoneOtp: sends are paused (${config.pausedReason || "no reason set"})`);
      throw new HttpsError("unavailable", "Verification is temporarily unavailable.");
    }

    const callingCode = callingCodeOf(phone);
    // App Check is deliberately not enforced on this path (it is the fallback for devices
    // that cannot attest), but a valid token still earns the full ceiling.
    const attested = request.app !== undefined;

    const specs: LimitSpec[] = [
      {
        key: `phone:${hashIdentifier("phone", phone)}`,
        cap: PHONE_SENDS_PER_HOUR,
        windowMs: HOUR_MS,
        label: "phone/hour",
      },
      { key: `uid:${uid}`, cap: UID_SENDS_PER_HOUR, windowMs: HOUR_MS, label: "uid/hour" },
      ...spendLimitSpecs(config, callingCode, attested),
    ];
    const ipKey = ipThrottleKey(request);
    if (ipKey) {
      specs.push({ key: ipKey, cap: IP_SENDS_PER_HOUR, windowMs: HOUR_MS, label: "ip/hour" });
    }
    await enforceRateLimits(specs);

    // Price the destination before paying for it: a Lookup costs $0.025 outside NAMER,
    // a premium-route SMS on the 2026-07-19 attack averaged ~$0.47.
    await enforcePumpingRiskGate(
      phone,
      callingCode,
      config,
      twilioAccountSid.value().trim(),
      twilioAuthToken.value().trim()
    );

    await twilioVerifyCall("Verifications", {
      To: phone,
      Channel: "sms",
      ...(locale ? { Locale: locale } : {}),
    });
    await recordOtpOutcome("sent", callingCode);
    return { sent: true };
  }
);

export const verifyPhoneOtp = onCall(
  { region: "us-central1", secrets: [twilioAccountSid, twilioAuthToken, twilioVerifyServiceSid] },
  async (request) => {
    const uid = requireUid(request);
    const phone = normalizePhone(request.data?.phone);
    const code = typeof request.data?.code === "string" ? request.data.code.trim() : "";
    if (!phone || !/^[0-9]{4,10}$/.test(code)) {
      throw new HttpsError("invalid-argument", "Phone and code are required.");
    }

    // Brute-force is primarily capped by Twilio (5 checks per verification, 10-min
    // expiry); this uid cap just keeps a scripted caller from burning API calls.
    await enforceRateLimits([
      { key: `check:${uid}`, cap: UID_CHECKS_PER_HOUR, windowMs: HOUR_MS, label: "check/hour" },
    ]);

    const check = await twilioVerifyCall("VerificationCheck", { To: phone, Code: code });
    if (check.status !== "approved") {
      throw new HttpsError("permission-denied", "The verification code is wrong or expired.");
    }
    await recordOtpOutcome("verified", callingCodeOf(phone));

    // Number proven. Bind it to the caller's account; if it already belongs to another
    // account, mint a custom token for THAT account (matches Firebase phone sign-in
    // semantics: proving possession of the number signs you into its account).
    const auth = getAuth();
    try {
      await auth.updateUser(uid, { phoneNumber: phone });
      // Mirror into the profile document. Android writes users/{uid}.phoneNumber client-side after
      // linking; iOS never did, so the dashboard roster and Android's remote-profile sync saw no
      // phone for iOS-verified accounts. Server-side is the one place that fixes every client at
      // once — and it cannot be spoofed, since we just proved possession of the number above.
      // Best-effort: the ACCOUNT link above already succeeded, and failing the whole
      // verification over a profile-mirror hiccup would strand a proven number.
      try {
        await getFirestore()
          .doc(`users/${uid}`)
          .set({ phoneNumber: phone, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      } catch (mirrorError) {
        console.error("verifyPhoneOtp profile mirror failed", mirrorError);
      }
      return { outcome: "linked", phone };
    } catch (error) {
      const errCode = (error as { code?: string }).code ?? "";
      if (errCode !== "auth/phone-number-already-exists") {
        console.error("verifyPhoneOtp updateUser failed", errCode);
        throw new HttpsError("internal", "Could not attach the number to the account.");
      }
    }
    const owner = await auth.getUserByPhoneNumber(phone);
    const customToken = await auth.createCustomToken(owner.uid);
    return { outcome: "signin", phone, customToken };
  }
);

// ── Spend circuit breaker ────────────────────────────────────────────────────

/**
 * Validates Twilio's `X-Twilio-Signature`: base64(HMAC-SHA1(authToken, url + sorted
 * key/value pairs)). https://www.twilio.com/docs/usage/security#validating-requests
 */
function isValidTwilioSignature(
  url: string,
  params: Record<string, unknown>,
  signature: string,
  authToken: string
): boolean {
  const payload = Object.keys(params)
    .sort()
    .reduce((acc, key) => acc + key + String(params[key] ?? ""), url);
  const expected = crypto.createHmac("sha1", authToken).update(Buffer.from(payload, "utf-8")).digest("base64");
  const a = Buffer.from(expected);
  const b = Buffer.from(signature);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/**
 * Twilio Usage Trigger webhook: pauses all OTP sends when daily spend crosses the
 * configured threshold. Usage triggers are evaluated about once a minute, which makes
 * this the backstop that bounds a pumping run regardless of which other layer failed.
 *
 * Configure in Twilio: Usage Triggers → trigger on `price`, daily recurrence, callback
 * to this function's URL. Re-enable afterwards by setting `system/otpConfig.enabled`
 * back to true in the Firestore console (deliberately manual — a spend alarm should be
 * looked at by a human before money can flow again).
 */
export const twilioSpendAlert = onRequest(
  { region: "us-central1", secrets: [twilioAuthToken] },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).send("Method Not Allowed");
      return;
    }
    const signature = req.get("X-Twilio-Signature") ?? "";
    const url = `https://${req.get("host")}${req.originalUrl}`;
    const params = (req.body ?? {}) as Record<string, unknown>;
    if (!signature || !isValidTwilioSignature(url, params, signature, twilioAuthToken.value().trim())) {
      console.error("twilioSpendAlert: signature validation failed");
      res.status(403).send("Forbidden");
      return;
    }

    const triggerName = String(params.UsageTrigger ?? params.TriggerName ?? "usage trigger");
    const currentValue = String(params.CurrentValue ?? "?");
    const reason = `Twilio spend alert (${triggerName} at ${currentValue}) — auto-paused`;
    console.error(`twilioSpendAlert: pausing OTP sends — ${reason}`);

    await getFirestore().doc("system/otpConfig").set(
      {
        enabled: false,
        pausedReason: reason.slice(0, 200),
        pausedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    res.status(204).send("");
  }
);
