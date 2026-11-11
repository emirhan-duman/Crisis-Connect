export function reserveRelayBudget(previous: Record<string, unknown>, now: number, emergency: boolean): {
  state: Record<string, unknown>; retryAfterMs: number;
} {
  const bucket = emergency ? "emergency" : "normal";
  const capacity = emergency ? 3 : 12;
  const period = emergency ? 10_000 : 5_000;
  const saved = previous[bucket] as { tokens?: number; at?: number } | undefined;
  const tokens = Math.min(capacity,Math.max(0,Number(saved?.tokens ?? capacity)) +
    Math.max(0,now - Number(saved?.at ?? now)) / period);
  if (tokens < 1) return { state: previous, retryAfterMs: Math.ceil((1 - tokens) * period) };
  return { state: { ...previous,[bucket]:{ tokens: tokens - 1,at:now } },retryAfterMs:0 };
}
