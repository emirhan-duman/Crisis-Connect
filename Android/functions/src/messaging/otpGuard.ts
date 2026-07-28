import { HttpsError } from "firebase-functions/v2/https";
import { getFirestore, FieldValue, Timestamp } from "firebase-admin/firestore";

/**
 * Cost-abuse defences for the Twilio OTP send path (see phoneOtp.ts).
 *
 * Background: on 2026-07-19 an SMS pumping (AIT) run drove ~240 OTP sends to premium
 * routes in TZ/ML/EG/MY in eight hours (~$113) and got the Twilio account suspended.
 * Every message was left to expire — the attacker earns a revenue share per delivered
 * SMS, so completion never happens. The old defences (per-phone/uid/IP/global hourly
 * counters) all passed: anonymous sign-up is open, so uid rotates freely, and the
 * global cap of 500/hour was worth ~$200/hour on those routes.
 *
 * The layers here, cheapest first:
 *   1. Kill switch      — `system/otpConfig.enabled=false` stops every send at once.
 *                         Flipped automatically by the Twilio spend webhook.
 *   2. Country caps     — per destination country hour/day ceilings. An attacker who
 *                         rotates uid, IP and phone still can't move volume anywhere.
 *   3. Trust tiers      — App Check attested callers get the normal ceiling; callers
 *                         without attestation get a fraction of it. Attestation is NOT
 *                         required: this path exists precisely for devices that cannot
 *                         attest, so it degrades their budget rather than locking out.
 *   4. Pumping risk     — Twilio Lookup scores the number BEFORE any SMS is paid for
 *                         ($0.025/lookup outside NAMER vs ~$0.47 for a premium-route
 *                         SMS, so the gate pays for itself at a ~5% catch rate).
 *
 * All thresholds live in Firestore (`system/otpConfig`, server-only in rules) so they
 * can be retuned during an incident without a deploy — same pattern as `system/sosRouting`.
 */

// ── Configuration ────────────────────────────────────────────────────────────

export interface CountryLimit {
  perHour: number;
  perDay: number;
}

export interface OtpConfig {
  /** Master switch. False pauses every OTP send (spend circuit breaker). */
  enabled: boolean;
  pausedReason: string;
  globalPerHour: number;
  globalPerDay: number;
  /** Applied to any destination country without an explicit override. */
  defaultCountry: CountryLimit;
  /** Keyed by E.164 calling code without "+" (e.g. "90", "255"). */
  countryOverrides: Record<string, CountryLimit>;
  /** Ceiling multiplier for callers that presented no valid App Check token. */
  unattestedFactor: number;
  /** Twilio Lookup SMS Pumping Risk gate. */
  riskGateEnabled: boolean;
  /** Scores at or above this block the send. Twilio: 75-90 suspicious, 90+ don't send. */
  riskBlockScore: number;
  /** When Lookup itself errors: true keeps sending (other layers still bound spend). */
  riskFailOpen: boolean;
}

const DEFAULT_CONFIG: OtpConfig = {
  enabled: true,
  pausedReason: "",
  // Real demand is ~2-3 verifications/day account-wide, so these stay far above
  // organic traffic while capping a worst-case day at roughly $50 instead of $200/hour.
  globalPerHour: 25,
  globalPerDay: 120,
  defaultCountry: { perHour: 8, perDay: 30 },
  countryOverrides: {},
  unattestedFactor: 0.4,
  riskGateEnabled: true,
  riskBlockScore: 75,
  riskFailOpen: true,
};

const CONFIG_DOC = "system/otpConfig";
const CONFIG_TTL_MS = 60_000;

let cachedConfig: { value: OtpConfig; expiresAt: number } | null = null;

function positiveInt(raw: unknown, fallback: number): number {
  return typeof raw === "number" && Number.isFinite(raw) && raw >= 0 ? Math.floor(raw) : fallback;
}

function parseCountryLimit(raw: unknown, fallback: CountryLimit): CountryLimit {
  const d = raw as Record<string, unknown> | undefined;
  if (!d) return fallback;
  return {
    perHour: positiveInt(d.perHour, fallback.perHour),
    perDay: positiveInt(d.perDay, fallback.perDay),
  };
}

/** Reads `system/otpConfig`, falling back to safe defaults. Cached for 60s per instance. */
export async function loadOtpConfig(): Promise<OtpConfig> {
  const now = Date.now();
  if (cachedConfig && cachedConfig.expiresAt > now) return cachedConfig.value;

  let value = DEFAULT_CONFIG;
  try {
    const snap = await getFirestore().doc(CONFIG_DOC).get();
    const d = snap.data();
    if (d) {
      const defaultCountry = parseCountryLimit(d.defaultCountry, DEFAULT_CONFIG.defaultCountry);
      const overrides: Record<string, CountryLimit> = {};
      const rawOverrides = d.countryOverrides as Record<string, unknown> | undefined;
      if (rawOverrides && typeof rawOverrides === "object") {
        for (const [code, limit] of Object.entries(rawOverrides)) {
          if (/^[0-9]{1,4}$/.test(code)) {
            overrides[code] = parseCountryLimit(limit, defaultCountry);
          }
        }
      }
      value = {
        // Only an explicit `false` pauses sends; a missing field must not take OTP down.
        enabled: d.enabled !== false,
        pausedReason: typeof d.pausedReason === "string" ? d.pausedReason.slice(0, 200) : "",
        globalPerHour: positiveInt(d.globalPerHour, DEFAULT_CONFIG.globalPerHour),
        globalPerDay: positiveInt(d.globalPerDay, DEFAULT_CONFIG.globalPerDay),
        defaultCountry,
        countryOverrides: overrides,
        unattestedFactor:
          typeof d.unattestedFactor === "number" && d.unattestedFactor > 0 && d.unattestedFactor <= 1
            ? d.unattestedFactor
            : DEFAULT_CONFIG.unattestedFactor,
        riskGateEnabled: d.riskGateEnabled !== false,
        riskBlockScore: positiveInt(d.riskBlockScore, DEFAULT_CONFIG.riskBlockScore),
        riskFailOpen: d.riskFailOpen !== false,
      };
    }
  } catch (error) {
    // A config read failure must not block emergency onboarding; defaults are already strict.
    console.error("otpGuard: config read failed, using defaults", (error as Error).message);
  }

  cachedConfig = { value, expiresAt: now + CONFIG_TTL_MS };
  return value;
}

/** Test seam: drops the per-instance config cache. */
export function resetOtpConfigCache(): void {
  cachedConfig = null;
}

// ── Destination country ──────────────────────────────────────────────────────

/**
 * E.164 calling codes, longest-match first. Only used to bucket spend by destination,
 * so an unknown prefix degrading to a 1-2 digit match is harmless — it still buckets.
 */
const CALLING_CODES = new Set([
  "1", "7", "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45",
  "46", "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63",
  "64", "65", "66", "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98",
  "211", "212", "213", "216", "218", "220", "221", "222", "223", "224", "225", "226", "227", "228",
  "229", "230", "231", "232", "233", "234", "235", "236", "237", "238", "239", "240", "241", "242",
  "243", "244", "245", "246", "248", "249", "250", "251", "252", "253", "254", "255", "256", "257",
  "258", "260", "261", "262", "263", "264", "265", "266", "267", "268", "269", "290", "291", "297",
  "298", "299", "350", "351", "352", "353", "354", "355", "356", "357", "358", "359", "370", "371",
  "372", "373", "374", "375", "376", "377", "378", "379", "380", "381", "382", "383", "385", "386",
  "387", "389", "420", "421", "423", "500", "501", "502", "503", "504", "505", "506", "507", "508",
  "509", "590", "591", "592", "593", "594", "595", "596", "597", "598", "599", "670", "672", "673",
  "674", "675", "676", "677", "678", "679", "680", "681", "682", "683", "685", "686", "687", "688",
  "689", "690", "691", "692", "850", "852", "853", "855", "856", "880", "886", "960", "961", "962",
  "963", "964", "965", "966", "967", "968", "970", "971", "972", "973", "974", "975", "976", "977",
  "992", "993", "994", "995", "996", "998",
]);

/** Extracts the E.164 calling code ("+255671424054" → "255"). Never returns PII. */
export function callingCodeOf(phoneE164: string): string {
  const digits = phoneE164.replace(/^\+/, "");
  for (const len of [3, 2, 1]) {
    const prefix = digits.slice(0, len);
    if (CALLING_CODES.has(prefix)) return prefix;
  }
  return digits.slice(0, 2) || "unknown";
}

// ── Rate limiting ────────────────────────────────────────────────────────────

export const HOUR_MS = 60 * 60 * 1000;
export const DAY_MS = 24 * HOUR_MS;

export interface LimitSpec {
  key: string;
  cap: number;
  windowMs: number;
  /** Non-PII label used in logs when this bucket is the one that trips. */
  label: string;
}

/**
 * Fixed-window counters in `otpThrottle/{key}`, all evaluated in ONE transaction so a
 * send costs a single round trip no matter how many ceilings apply. Throws
 * resource-exhausted (and writes nothing) as soon as any bucket is full.
 */
export async function enforceRateLimits(specs: LimitSpec[]): Promise<void> {
  if (specs.length === 0) return;
  const db = getFirestore();
  const refs = specs.map((spec) => db.collection("otpThrottle").doc(spec.key));

  await db.runTransaction(async (tx) => {
    const snaps = await tx.getAll(...refs);
    const now = Date.now();
    const pending: Array<{ index: number; fresh: boolean; windowStart: Timestamp }> = [];

    for (let i = 0; i < specs.length; i++) {
      const spec = specs[i];
      const data = snaps[i].data();
      const windowStart = data?.windowStart as Timestamp | undefined;
      const count = (data?.count as number | undefined) ?? 0;
      const fresh = !windowStart || now - windowStart.toMillis() >= spec.windowMs;
      if (!fresh && count >= spec.cap) {
        console.warn(`otpGuard: cap reached (${spec.label}, cap=${spec.cap})`);
        throw new HttpsError("resource-exhausted", "Too many attempts. Try again later.");
      }
      pending.push({
        index: i,
        fresh,
        windowStart: fresh ? Timestamp.fromMillis(now) : (windowStart as Timestamp),
      });
    }

    // Firestore requires every read before any write, hence the second pass.
    for (const entry of pending) {
      tx.set(
        refs[entry.index],
        {
          windowStart: entry.windowStart,
          count: entry.fresh ? 1 : FieldValue.increment(1),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    }
  });
}

/** Scales a ceiling down for callers that could not prove app authenticity. */
function tiered(cap: number, attested: boolean, factor: number): number {
  return attested ? cap : Math.max(1, Math.floor(cap * factor));
}

/**
 * Builds the global + destination-country ceilings for one send. Per-phone, per-uid and
 * per-IP buckets stay in phoneOtp.ts; these are the ones that survive uid/IP rotation.
 */
export function spendLimitSpecs(
  config: OtpConfig,
  callingCode: string,
  attested: boolean
): LimitSpec[] {
  const country = config.countryOverrides[callingCode] ?? config.defaultCountry;
  const factor = config.unattestedFactor;
  return [
    {
      key: "global:otp-sends",
      cap: config.globalPerHour,
      windowMs: HOUR_MS,
      label: "global/hour",
    },
    {
      key: "global:otp-sends-day",
      cap: config.globalPerDay,
      windowMs: DAY_MS,
      label: "global/day",
    },
    {
      key: `country:${callingCode}:h`,
      cap: tiered(country.perHour, attested, factor),
      windowMs: HOUR_MS,
      label: `country+${callingCode}/hour`,
    },
    {
      key: `country:${callingCode}:d`,
      cap: tiered(country.perDay, attested, factor),
      windowMs: DAY_MS,
      label: `country+${callingCode}/day`,
    },
  ];
}

// ── Twilio Lookup: SMS Pumping Risk ──────────────────────────────────────────

export interface PumpingRisk {
  score: number;
  blocked: boolean;
  category: string;
}

/**
 * Scores a number with Twilio Lookup before we pay for an SMS.
 * https://www.twilio.com/docs/lookup/v2-api/sms-pumping-risk
 *
 * Returns null when the signal is unavailable (network error, unsupported number) —
 * the caller decides whether that is fatal via `riskFailOpen`.
 */
export async function fetchPumpingRisk(
  phoneE164: string,
  accountSid: string,
  authToken: string
): Promise<PumpingRisk | null> {
  const url =
    `https://lookups.twilio.com/v2/PhoneNumbers/${encodeURIComponent(phoneE164)}` +
    "?Fields=sms_pumping_risk";
  const response = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Basic ${Buffer.from(`${accountSid}:${authToken}`).toString("base64")}`,
    },
  });
  if (!response.ok) {
    console.error("otpGuard: Lookup request failed", response.status);
    return null;
  }
  const body = (await response.json().catch(() => null)) as Record<string, unknown> | null;
  const risk = body?.sms_pumping_risk as Record<string, unknown> | undefined;
  if (!risk) return null;
  const score = risk.sms_pumping_risk_score;
  if (typeof score !== "number") return null;
  return {
    score,
    blocked: risk.number_blocked === true,
    category: typeof risk.carrier_risk_category === "string" ? risk.carrier_risk_category : "",
  };
}

/**
 * Applies the risk gate. Throws permission-denied for numbers Twilio scores as pumping
 * traffic (or that Fraud Guard has already blocked); returns the score for telemetry.
 */
export async function enforcePumpingRiskGate(
  phoneE164: string,
  callingCode: string,
  config: OtpConfig,
  accountSid: string,
  authToken: string
): Promise<PumpingRisk | null> {
  if (!config.riskGateEnabled) return null;

  let risk: PumpingRisk | null = null;
  try {
    risk = await fetchPumpingRisk(phoneE164, accountSid, authToken);
  } catch (error) {
    console.error("otpGuard: Lookup threw", (error as Error).message);
  }

  if (!risk) {
    if (config.riskFailOpen) return null;
    throw new HttpsError("unavailable", "Verification is temporarily unavailable.");
  }
  if (risk.blocked || risk.score >= config.riskBlockScore) {
    console.warn(
      `otpGuard: blocked by pumping risk (country=+${callingCode}, score=${risk.score}, ` +
        `category=${risk.category}, fraudGuardBlocked=${risk.blocked})`
    );
    throw new HttpsError("permission-denied", "This number cannot be verified right now.");
  }
  return risk;
}

// ── Conversion telemetry ─────────────────────────────────────────────────────

/**
 * Counts sends and completions per UTC hour in `otpStats/{yyyy-mm-ddThh}`.
 *
 * Completion rate is the sharpest AIT signal we have: in the 2026-07-19 run every one
 * of ~240 sends expired unused, so an hourly ratio alarm would have fired within the
 * first half hour. Counters are aggregate-only — no phone numbers, no uids.
 */
export async function recordOtpOutcome(
  kind: "sent" | "verified",
  callingCode: string
): Promise<void> {
  const bucketId = new Date().toISOString().slice(0, 13); // "2026-07-20T09"
  try {
    await getFirestore()
      .doc(`otpStats/${bucketId}`)
      .set(
        {
          [kind]: FieldValue.increment(1),
          [`byCountry.${callingCode}.${kind}`]: FieldValue.increment(1),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
  } catch (error) {
    // Telemetry must never fail a verification.
    console.error("otpGuard: stats write failed", (error as Error).message);
  }
}
