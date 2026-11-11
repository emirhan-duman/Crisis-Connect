import { createHash } from "node:crypto";
import { getMessagingFirestore as getFirestore, currentMessagingDatabaseId } from "./databaseTarget";

export interface TrafficRuntimePolicy {
  version: number;
  emergencyFirst: boolean;
  retryAfterMs: number;
}
/** Stable installation cohorts; a rollback never disables the baseline conservation policy. */
export function resolveTrafficPolicy(data: Record<string, unknown>, installation: string): TrafficRuntimePolicy {
  const percent = typeof data.emergencyFirstPercent === "number" && Number.isFinite(data.emergencyFirstPercent)
    ? Math.max(0, Math.min(100, data.emergencyFirstPercent)) : 100;
  const cohort = createHash("sha256").update(installation).digest().readUInt32BE(0) % 10_000;
  return {
    version: 2,
    emergencyFirst: data.enabled !== false && cohort < percent * 100,
    retryAfterMs: typeof data.retryAfterMs === "number" && Number.isFinite(data.retryAfterMs)
      ? Math.max(30_000, Math.min(300_000, Math.floor(data.retryAfterMs))) : 30_000,
  };
}
const policies = new Map<string, { expires: number; data: Record<string, unknown> }>();
/** Server-only settings piggyback on inbox sync; no extra mobile socket or config polling. */
export async function trafficPolicy(installation: string): Promise<TrafficRuntimePolicy> {
  const database = currentMessagingDatabaseId();
  let cached = policies.get(database);
  if (!cached || cached.expires < Date.now()) {
    try {
      const snapshot = await getFirestore().doc("messagingRuntime/traffic").get();
      cached = { expires: Date.now() + 60_000, data: snapshot.data() ?? {} };
    } catch {
      // A configuration outage cannot break inbox delivery or reset an existing rollback.
      cached = { expires: Date.now() + 15_000, data: cached?.data ?? {} };
    }
    policies.set(database, cached);
  }
  return resolveTrafficPolicy(cached.data, installation);
}
