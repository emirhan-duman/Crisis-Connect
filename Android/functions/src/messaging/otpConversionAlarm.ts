import { onSchedule } from "firebase-functions/v2/scheduler";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

/**
 * Hourly completion-rate check on OTP sends.
 *
 * SMS pumping is invisible to volume alarms — the run on 2026-07-19 stayed under every
 * hourly ceiling we had — but it is loud in the completion rate: the attacker earns per
 * delivered SMS and never enters the code, so all ~240 verifications expired at 0/1.
 * Real traffic completes most of the time. A ratio this far below normal means either
 * an attack or a broken delivery route; both deserve a human within the hour.
 *
 * This alarm only reports. Auto-pausing on a ratio would let an attacker take
 * verification down for everyone by sending junk requests; the spend webhook in
 * phoneOtp.ts is the layer allowed to pause, because money is an unambiguous signal.
 */

const MIN_SENDS_FOR_SIGNAL = 8;
const COMPLETION_RATE_FLOOR = 0.25;

export const otpConversionAlarm = onSchedule(
  { schedule: "every 1 hours", region: "us-central1" },
  async () => {
    const db = getFirestore();
    // The bucket that just closed, e.g. "2026-07-20T09".
    const bucketId = new Date(Date.now() - 60 * 60 * 1000).toISOString().slice(0, 13);
    const snap = await db.doc(`otpStats/${bucketId}`).get();
    const data = snap.data();
    if (!data) return;

    const sent = (data.sent as number | undefined) ?? 0;
    const verified = (data.verified as number | undefined) ?? 0;
    if (sent < MIN_SENDS_FOR_SIGNAL) return;

    const rate = verified / sent;
    if (rate >= COMPLETION_RATE_FLOOR) return;

    // Name the worst destinations so the on-call has somewhere to start.
    const byCountry = (data.byCountry as Record<string, Record<string, number>> | undefined) ?? {};
    const suspects = Object.entries(byCountry)
      .map(([code, counts]) => ({ code, sent: counts.sent ?? 0, verified: counts.verified ?? 0 }))
      .filter((entry) => entry.sent > 0 && entry.verified === 0)
      .sort((a, b) => b.sent - a.sent)
      .slice(0, 5)
      .map((entry) => `+${entry.code}(${entry.sent})`)
      .join(", ");

    console.error(
      `otpConversionAlarm: completion rate ${(rate * 100).toFixed(0)}% in ${bucketId} ` +
        `(${verified}/${sent}) — possible SMS pumping. Unconverted destinations: ${suspects || "n/a"}`
    );

    await db.doc("system/otpAlerts").set(
      {
        lastAlertBucket: bucketId,
        lastAlertRate: rate,
        lastAlertSent: sent,
        lastAlertVerified: verified,
        lastAlertSuspects: suspects,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  }
);
