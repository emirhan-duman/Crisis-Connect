/** Normalize an agency membership value to the key-service document id shape. */
export function normalizeAgencyPanelId(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase().replace(/\//g, "-");
  return normalized.length > 0 ? normalized : null;
}

/**
 * Resolve the authoritative panel bound to a user document.
 *
 * `panelId` is the canonical membership field used by the web dashboard and
 * Firestore rules. Older production accounts may carry only agencySlug or
 * agencyKey, so those remain ordered compatibility fallbacks.
 */
export function resolveAgencyMessagingPanelId(userDoc: Record<string, unknown>): string | null {
  return normalizeAgencyPanelId(userDoc.panelId)
    ?? normalizeAgencyPanelId(userDoc.agencySlug)
    ?? normalizeAgencyPanelId(userDoc.agencyKey);
}
