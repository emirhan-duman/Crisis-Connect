export type AttestationException = "standard" | "tester" | "demo";

export interface AttestationExceptionPolicy {
  readonly testerUids: ReadonlySet<string>;
  readonly demoUids: ReadonlySet<string>;
}

/**
 * Parses an explicitly configured comma-separated UID allowlist. Missing and
 * blank values deliberately resolve to an empty set so production deployments
 * fail closed when no exception has been configured.
 */
export function parseUidAllowlist(value: string | undefined): ReadonlySet<string> {
  return new Set(
    (value ?? "")
      .split(",")
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0)
  );
}

export function loadAttestationExceptionPolicy(
  environment: NodeJS.ProcessEnv = process.env
): AttestationExceptionPolicy {
  return {
    testerUids: parseUidAllowlist(environment.CC_TESTER_UIDS),
    demoUids: parseUidAllowlist(environment.CC_DEMO_UIDS),
  };
}

/**
 * Demo remains higher priority than tester to preserve the existing behavior
 * for explicitly configured UIDs that appear in both lists.
 */
export function resolveAttestationException(
  policy: AttestationExceptionPolicy,
  uid: string
): AttestationException {
  if (policy.demoUids.has(uid)) return "demo";
  if (policy.testerUids.has(uid)) return "tester";
  return "standard";
}
