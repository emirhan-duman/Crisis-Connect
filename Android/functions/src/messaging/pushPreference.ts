import { getFirestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";

/**
 * Reads the operator's push choice from Settings > Bildirimler
 * (`users/{uid}.notificationPreferences.push`, written by the web panel).
 *
 * Until this existed nothing read that field, so switching push off changed
 * nothing — every message still raised a banner.
 *
 * Two deliberate rules:
 *
 * 1. **Fail open.** A missing field, a document of the wrong shape, or a failed
 *    read all mean "allowed". This preference is the only reason we would ever
 *    drop a message push, and a silenced emergency message is far worse than an
 *    unwanted banner.
 * 2. **Never applies to call rings.** An incoming call is not a notification;
 *    honouring the toggle there would stop the callee's phone from ringing.
 *    Callers must check `isCallRing` before consulting this helper.
 */
async function readPushPreference(uid: string): Promise<boolean> {
  try {
    const snap = await getFirestore().doc(`users/${uid}`).get();
    const prefs = snap.get("notificationPreferences");
    if (!prefs || typeof prefs !== "object") return true;
    const push = (prefs as Record<string, unknown>).push;
    // Only an explicit `false` suppresses; anything else is treated as opted in.
    return push !== false;
  } catch (error) {
    logger.warn(`push preference lookup failed for ${uid}; delivering anyway`, error);
    return true;
  }
}

export async function isPushAllowed(uid: string): Promise<boolean> {
  return readPushPreference(uid);
}

/** Same rule, applied across a channel fan-out. Order is preserved. */
export async function filterUidsByPushPreference(uids: string[]): Promise<string[]> {
  const decisions = await Promise.all(uids.map((uid) => readPushPreference(uid)));
  return uids.filter((_, index) => decisions[index]);
}
