import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { requireUid, resolveCallerRoleKey } from "../certificates/callerRole";

/**
 * Same-agency authority roster, gated to fellow authorities.
 *
 * Returns the minimal directory fields needed to open an MLS AuthorityChat with another active member
 * of the caller's agency. Phone numbers and cryptographic material are deliberately not returned.
 * Access is limited to an active member of the SAME agency (or a platform admin).
 *
 * Membership matching MIRRORS the web dashboard (app/api/admin/users listPanelUserDocs): a user's panel
 * is `resolvePanelId(userDoc)` (panelId, else the first agency-ish field, slugified via toPanelId), and
 * we scan the whole users collection and keep those whose resolved panel equals the caller's. A plain
 * `where('agencySlug','==',slug)` misses members whose agency is stored under panelId/agencyName or with
 * different casing — which is why the earlier version returned an empty roster. Admin-SDK only.
 */
const ELIGIBLE_ROLES = new Set(["admin", "authority", "fieldteam"]);

// Same agency-ish fields + order the web's resolvePanelId uses.
const AGENCY_FIELDS = [
  "agencySlug", "agencyKey", "agencyName", "agency", "authority", "institution", "organization",
] as const;

function readStr(value: unknown): string | null {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : null;
}

function readFlag(value: unknown): boolean {
  return value === true || value === 1
    || (typeof value === "string" && ["true", "1", "yes", "on", "enabled"].includes(value.trim().toLowerCase()));
}

function hasDashboardAccess(value: unknown): boolean {
  if (value === undefined || value === null) return true;
  return readFlag(value);
}

function normalizeRoleKey(value: unknown): string {
  return typeof value === "string" ? value.trim().toLowerCase().replace(/[-_]/g, "") : "";
}

/** Slugify a label into a panel id — byte-identical to the web's toPanelId. */
function toPanelId(input: string): string | null {
  const normalized = input
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return normalized || null;
}

/** The panel a user doc belongs to — panelId first, else the first agency-ish field, slugified. */
function resolvePanelId(data: Record<string, unknown>): string | null {
  const directPanelId = readStr(data.panelId);
  if (directPanelId) return toPanelId(directPanelId);
  for (const key of AGENCY_FIELDS) {
    const candidate = readStr(data[key]);
    if (candidate) {
      const panelId = toPanelId(candidate);
      if (panelId) return panelId;
    }
  }
  return null;
}

interface RosterMember {
  uid: string;
  name: string;
  role: string;
  phone: null;
  photoUrl: string | null;
  agencySlug: string;
  agencyName: string | null;
}

export const listAuthorityRoster = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>) => {
    try {
      const uid = requireUid(request);
      const role = await resolveCallerRoleKey(request, uid);
      if (!ELIGIBLE_ROLES.has(role)) {
        throw new HttpsError("permission-denied", "Only agency members may list the authority roster.");
      }

      const db = getFirestore();
      const callerDoc = (await db.doc(`users/${uid}`).get()).data() ?? {};
      if (callerDoc.disabled === true || !hasDashboardAccess(callerDoc.dashboardAccess)) {
        throw new HttpsError("permission-denied", "Inactive agency members cannot list the roster.");
      }
      const callerPanelId = resolvePanelId(callerDoc);
      const isPlatformAdmin = readFlag(callerDoc.platformAdmin)
        || readFlag(callerDoc.globalDashboardAccess)
        || readFlag(callerDoc.superAdmin);

      // The caller can only list their OWN panel; the client-sent agencySlug is advisory. Platform
      // admins may target any panel via the requested slug.
      const data = (request.data ?? {}) as Record<string, unknown>;
      const requestedPanelId = readStr(data.agencySlug) ? toPanelId(readStr(data.agencySlug) as string) : null;
      const targetPanelId = isPlatformAdmin ? (requestedPanelId ?? callerPanelId) : callerPanelId;
      if (!targetPanelId) {
        throw new HttpsError("failed-precondition", "Your account is not linked to an agency.");
      }

      // Scan all users and keep same-panel members (the web does the same in listPanelUserDocs).
      const usersSnap = await db.collection("users").get();
      const members: RosterMember[] = [];
      for (const doc of usersSnap.docs) {
        if (doc.id === uid) continue;
        const d = doc.data() as Record<string, unknown>;
        if (resolvePanelId(d) !== targetPanelId) continue;
        if (d.disabled === true || !hasDashboardAccess(d.dashboardAccess)) continue;
        const memberRole = normalizeRoleKey(d.role);
        if (!ELIGIBLE_ROLES.has(memberRole)) continue;
        members.push({
          uid: doc.id,
          name: readStr(d.username) ?? readStr(d.name) ?? readStr(d.displayName) ?? readStr(d.email) ?? doc.id,
          role: memberRole,
          phone: null,
          photoUrl: readStr(d.photoURL) ?? readStr(d.photoUrl),
          agencySlug: targetPanelId,
          agencyName: readStr(d.agencyName) ?? readStr(d.agency) ?? readStr(d.institution),
        });
      }

      members.sort((a, b) => a.name.localeCompare(b.name));
      return { members: members.slice(0, 2000) };
    } catch (err) {
      if (err instanceof HttpsError) throw err;
      const e = err as { name?: string; message?: string };
      throw new HttpsError("internal", `AUTHORITY_ROSTER_FAIL ${e.name ?? ""}: ${e.message ?? String(err)}`);
    }
  }
);
