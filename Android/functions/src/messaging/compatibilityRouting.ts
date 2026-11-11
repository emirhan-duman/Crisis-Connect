import { onCall, CallableOptions, CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { requireUid } from "../certificates/callerRole";
import { currentMessagingDatabaseId, hasMessagingDatabaseContext, MULTIREGION_MESSAGING_DATABASE, LEGACY_MESSAGING_DATABASE, withMessagingDatabase } from "./databaseTarget";
import { haFirestore, usesHighAvailability } from "./highAvailabilityStore";

/** Existing names and wire contracts remain intact throughout the per-account transition. */
export function compatibleCallable<T>(operation: string, options: CallableOptions,
  handler: (request: CallableRequest<any>) => Promise<T>) {
  return onCall(options, request => runCompatibleOperation(operation, request, handler));
}

export async function runCompatibleOperation<T>(operation: string, request: CallableRequest<any>,
  handler: (request: CallableRequest<any>) => Promise<T>): Promise<T> {
  if (hasMessagingDatabaseContext() || currentMessagingDatabaseId() === MULTIREGION_MESSAGING_DATABASE || process.env.HA_COMPATIBILITY_ENABLED !== "true") {
    return handler(request);
  }
  const uid = requireUid(request), data = request.data ?? {};
  const target = operation === "relayMessage" ? data.recipientUid
    : operation === "fetchSignalPreKeyBundle" ? data.targetUid
    : operation === "lookupIdentityKey" ? data.uid : uid;
  const validTarget = typeof target === "string" && /^[A-Za-z0-9]{1,128}$/.test(target);
  const db = haFirestore();
  const [actor, peer] = await db.getAll(db.doc(`messagingAccounts/${uid}`),
    ...(validTarget && target !== uid ? [db.doc(`messagingAccounts/${target}`)] : []));
  if (actor.get("state") === "deleted") throw new HttpsError("permission-denied", "Messaging account is unavailable.");
  const run = (database: string) => withMessagingDatabase(database, () => handler(request));
  if (operation === "lookupIdentityKey" && typeof data.uid !== "string") {
    try { return await run(MULTIREGION_MESSAGING_DATABASE); }
    catch (error) { if ((error as { code?: string }).code !== "not-found") throw error; }
    const result = await run(LEGACY_MESSAGING_DATABASE);
    const target = (result as { uid?: string }).uid;
    // A revoked/moved HA directory entry must not reappear from an older legacy index.
    if (target && await usesHighAvailability(target)) throw new HttpsError("not-found", "No discoverable account matches.");
    return result;
  }
  if (operation === "findContactsOnCrisisConnect") {
    const current = await run(MULTIREGION_MESSAGING_DATABASE) as { matches: Array<Record<string, unknown>> };
    let old: { matches: Array<Record<string, unknown>> };
    try { old = await run(LEGACY_MESSAGING_DATABASE) as typeof old; }
    catch { return { ...current, partial: true } as T; }
    const candidates = [...new Set(old.matches.map(m => m.uid).filter((v): v is string => typeof v === "string"))];
    const active = new Set<string>();
    for (let i = 0; i < candidates.length; i += 200) {
      const snapshots = await haFirestore().getAll(...candidates.slice(i, i + 200).map(id => haFirestore().doc(`messagingAccounts/${id}`)));
      for (const snapshot of snapshots) if (snapshot.get("protocol") === 4 && snapshot.get("state") === "active") active.add(snapshot.id);
    }
    const matches = new Map(current.matches.map(m => [m.phone, m]));
    for (const match of old.matches) if (!active.has(String(match.uid)) && !matches.has(match.phone)) matches.set(match.phone, match);
    return { matches: [...matches.values()] } as T;
  }
  if (!validTarget) return run(LEGACY_MESSAGING_DATABASE);
  const account = target === uid ? actor : peer;
  if (account.get("state") === "deleted") throw new HttpsError("not-found", "Messaging account is unavailable.");
  return run(account.get("protocol") === 4 && account.get("state") === "active"
    ? MULTIREGION_MESSAGING_DATABASE : LEGACY_MESSAGING_DATABASE);
}
