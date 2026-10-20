import { getFirestore, Firestore } from "firebase-admin/firestore";
import { AsyncLocalStorage } from "node:async_hooks";

/**
 * All messaging endpoints in a deployment must use one authoritative database.
 * A compute-region retry must never select a different/stale database, especially
 * for consumable Signal prekeys and delivery receipts.
 *
 * Existing deployments omit the variable and retain the released default path.
 * Changing it is a coordinated data migration, not an automatic fallback knob.
 */
export const LEGACY_MESSAGING_DATABASE = "(default)";
export const MULTIREGION_MESSAGING_DATABASE = "messaging-live-eur3";
const requestDatabase = new AsyncLocalStorage<string>();

export function messagingDatabaseId(configured: string | undefined): string {
  if (configured === undefined) return LEGACY_MESSAGING_DATABASE;
  if (configured === LEGACY_MESSAGING_DATABASE || configured === MULTIREGION_MESSAGING_DATABASE) {
    return configured;
  }
  // A typo must fail closed. Falling back would split authoritative key pools.
  throw new Error("Invalid MESSAGING_DATABASE_ID; refusing a split messaging database.");
}

export function getMessagingFirestore(): Firestore {
  const id = currentMessagingDatabaseId();
  return id === LEGACY_MESSAGING_DATABASE ? getFirestore() : getFirestore(id);
}

export function currentMessagingDatabaseId(): string {
  return requestDatabase.getStore() ?? messagingDatabaseId(process.env.MESSAGING_DATABASE_ID);
}

export function hasMessagingDatabaseContext(): boolean {
  return requestDatabase.getStore() !== undefined;
}

/** An authenticated server route chooses the database, never a client-supplied database ID. */
export async function withMessagingDatabase<T>(id: string, action: () => Promise<T>): Promise<T> {
  const validated = messagingDatabaseId(id);
  return requestDatabase.run(validated, action);
}
