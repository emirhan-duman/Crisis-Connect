import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue, Transaction } from "firebase-admin/firestore";
import { requireUid } from "../certificates/callerRole";

/**
 * Victim-side SOS internet uplink.
 *
 * While the on-device SOS broadcast is BLE-first and fully offline, a victim with any usable
 * connectivity self-reports the same signal to the agency dashboard. The document id is the
 * device's stable rescue id (cc-<24 hex>), which is also what BLE relays (Crisis Link) use, so a
 * self-report and rescuer sightings of the same device merge into one dashboard marker.
 *
 * Routing: the signal lands in agencyPanels/{panel}/signals where the panel is derived from the
 * caller-supplied ISO country hint (TR→afad, US→fema, IN→ndma, JP→jma, else international). The
 * first report pins the panel in sosSignalRouting/{signalId} so heartbeats and the final resolve
 * always hit the same document even if country detection flaps mid-incident.
 *
 * Abuse controls: App Check enforced, per-uid write throttle, and the first internet reporter
 * becomes the signal owner (victimUid) — other uids cannot update or resolve it.
 */

const SIGNAL_ID_PATTERN = /^cc-[0-9a-f]{24}$/;
const MIN_REPORT_INTERVAL_MS = 15_000;
const SIGNAL_SCHEMA_VERSION = 2;
const PANEL_SCHEMA_VERSION = 3;
const AGENCY_PANELS_COLLECTION = "agencyPanels";
const ROUTING_COLLECTION = "sosSignalRouting";
const THROTTLE_COLLECTION = "sosReportThrottle";

interface AgencyRoute {
  /** Display name of the national disaster-management agency. */
  agency: string;
  /** Dashboard panel document id. Unique across all countries (acronyms collide: four
   * countries run a "NEMA", two run an "NDMA" — those get country-suffixed slugs). */
  panelId: string;
}

/**
 * ISO 3166-1 alpha-2 → primary national disaster/emergency management agency. Countries without
 * a confident mapping fall back to the shared International panel; adding a row here is all it
 * takes to give a country its own panel (the function creates the panel doc on first report).
 */
const COUNTRY_AGENCY_MAP: Record<string, AgencyRoute> = {
  // Europe
  TR: { agency: "AFAD", panelId: "afad" },
  GB: { agency: "Civil Contingencies Secretariat", panelId: "ccs" },
  IE: { agency: "Office of Emergency Planning", panelId: "oep" },
  FR: { agency: "Sécurité Civile", panelId: "securite-civile" },
  ES: { agency: "Protección Civil", panelId: "proteccion-civil" },
  PT: { agency: "ANEPC", panelId: "anepc" },
  IT: { agency: "Protezione Civile", panelId: "protezione-civile" },
  DE: { agency: "BBK", panelId: "bbk" },
  CH: { agency: "BABS", panelId: "babs" },
  AT: { agency: "SKKM", panelId: "skkm" },
  NL: { agency: "Nationaal CrisisCentrum", panelId: "ncc-nl" },
  BE: { agency: "NCCN", panelId: "nccn" },
  DK: { agency: "DEMA", panelId: "dema" },
  NO: { agency: "DSB", panelId: "dsb" },
  SE: { agency: "MSB", panelId: "msb" },
  FI: { agency: "Pelastustoimi", panelId: "pelastustoimi" },
  IS: { agency: "Almannavarnir", panelId: "almannavarnir" },
  PL: { agency: "RCB", panelId: "rcb" },
  CZ: { agency: "HZS ČR", panelId: "hzs" },
  HU: { agency: "OKF", panelId: "okf" },
  RO: { agency: "DSU", panelId: "dsu" },
  GR: { agency: "Civil Protection (GSCP)", panelId: "gscp" },
  CY: { agency: "Cyprus Civil Defence", panelId: "civil-defence-cy" },
  SI: { agency: "URSZR", panelId: "urszr" },
  HR: { agency: "Civilna zaštita", panelId: "civilna-zastita" },
  RS: { agency: "Sektor za vanredne situacije", panelId: "svs" },
  UA: { agency: "SESU", panelId: "sesu" },
  RU: { agency: "EMERCOM", panelId: "emercom" },
  AZ: { agency: "FHN", panelId: "fhn" },
  AM: { agency: "MES (Armenia)", panelId: "mes-am" },
  KZ: { agency: "MES (Kazakhstan)", panelId: "mes-kz" },
  UZ: { agency: "MES (Uzbekistan)", panelId: "mes-uz" },
  KG: { agency: "MES (Kyrgyzstan)", panelId: "mes-kg" },
  // Americas
  US: { agency: "FEMA", panelId: "fema" },
  CA: { agency: "Public Safety Canada", panelId: "psc" },
  MX: { agency: "CENAPRED", panelId: "cenapred" },
  GT: { agency: "CONRED", panelId: "conred" },
  HN: { agency: "COPECO", panelId: "copeco" },
  NI: { agency: "SINAPRED", panelId: "sinapred" },
  CR: { agency: "CNE", panelId: "cne" },
  PA: { agency: "SINAPROC", panelId: "sinaproc" },
  DO: { agency: "COE", panelId: "coe" },
  HT: { agency: "Protection Civile (Haïti)", panelId: "dgpc-ht" },
  CO: { agency: "UNGRD", panelId: "ungrd" },
  EC: { agency: "SNGRE", panelId: "sngre" },
  PE: { agency: "INDECI", panelId: "indeci" },
  BR: { agency: "Defesa Civil", panelId: "defesa-civil" },
  CL: { agency: "SENAPRED", panelId: "senapred" },
  AR: { agency: "SINAGIR", panelId: "sinagir" },
  PY: { agency: "SEN", panelId: "sen" },
  UY: { agency: "SINAE", panelId: "sinae" },
  BO: { agency: "VIDECI", panelId: "videci" },
  // Middle East & North Africa
  IL: { agency: "NEMA (Israel)", panelId: "nema-il" },
  SA: { agency: "Saudi Civil Defense", panelId: "saudi-civil-defense" },
  AE: { agency: "NCEMA", panelId: "ncema" },
  OM: { agency: "CDAA", panelId: "cdaa" },
  IR: { agency: "NDMO (Iran)", panelId: "ndmo-ir" },
  TN: { agency: "ONPC", panelId: "onpc" },
  DZ: { agency: "Protection Civile (Algérie)", panelId: "dgpc-dz" },
  MA: { agency: "Protection Civile (Maroc)", panelId: "dgpc-ma" },
  // Sub-Saharan Africa
  NG: { agency: "NEMA (Nigeria)", panelId: "nema-ng" },
  GH: { agency: "NADMO", panelId: "nadmo" },
  KE: { agency: "NDOC", panelId: "ndoc" },
  ET: { agency: "EDRMC", panelId: "edrmc" },
  RW: { agency: "MINEMA", panelId: "minema" },
  SO: { agency: "SODMA", panelId: "sodma" },
  MZ: { agency: "INGD", panelId: "ingd" },
  MW: { agency: "DODMA", panelId: "dodma" },
  ZM: { agency: "DMMU", panelId: "dmmu" },
  ZA: { agency: "NDMC", panelId: "ndmc" },
  MG: { agency: "BNGRC", panelId: "bngrc" },
  // Asia-Pacific
  IN: { agency: "NDMA", panelId: "ndma" },
  PK: { agency: "NDMA (Pakistan)", panelId: "ndma-pk" },
  BD: { agency: "DDM (Bangladesh)", panelId: "ddm-bd" },
  LK: { agency: "DMC", panelId: "dmc" },
  NP: { agency: "NDRRMA", panelId: "ndrrma" },
  MV: { agency: "NDMA (Maldives)", panelId: "ndma-mv" },
  AF: { agency: "ANDMA", panelId: "andma" },
  CN: { agency: "MEM", panelId: "mem" },
  MN: { agency: "NEMA (Mongolia)", panelId: "nema-mn" },
  JP: { agency: "JMA", panelId: "jma" },
  KR: { agency: "MOIS", panelId: "mois" },
  TW: { agency: "NFA", panelId: "nfa" },
  PH: { agency: "NDRRMC", panelId: "ndrrmc" },
  ID: { agency: "BNPB", panelId: "bnpb" },
  MY: { agency: "NADMA", panelId: "nadma" },
  SG: { agency: "SCDF", panelId: "scdf" },
  TH: { agency: "DDPM", panelId: "ddpm" },
  VN: { agency: "VNDMA", panelId: "vndma" },
  KH: { agency: "NCDM", panelId: "ncdm" },
  MM: { agency: "DDM (Myanmar)", panelId: "ddm-mm" },
  PG: { agency: "National Disaster Centre", panelId: "ndc-pg" },
  FJ: { agency: "NDMO (Fiji)", panelId: "ndmo-fj" },
  NZ: { agency: "NEMA (New Zealand)", panelId: "nema-nz" },
  AU: { agency: "NEMA (Australia)", panelId: "nema-au" },
};

const FALLBACK_ROUTE: AgencyRoute = { agency: "International", panelId: "international" };

/**
 * Dynamic routing overrides, editable without a redeploy. The Firestore doc `system/sosRouting`
 * holds `countries: { <ISO2>: { agency, panelId } }` plus an optional `fallback` route; entries
 * there win over the built-in table above, so new agencies are added straight from the console.
 * Cached in-instance for a few minutes to keep the per-report cost at ~zero.
 */
const ROUTING_CONFIG_DOC_PATH = "system/sosRouting";
const ROUTING_CONFIG_CACHE_TTL_MS = 5 * 60_000;
const PANEL_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;

interface RoutingConfig {
  countries: Record<string, AgencyRoute>;
  fallback: AgencyRoute;
}

let cachedRoutingConfig: RoutingConfig | null = null;
let routingConfigFetchedAtMs = 0;

async function loadRoutingConfig(db: FirebaseFirestore.Firestore): Promise<RoutingConfig> {
  const now = Date.now();
  if (cachedRoutingConfig && now - routingConfigFetchedAtMs < ROUTING_CONFIG_CACHE_TTL_MS) {
    return cachedRoutingConfig;
  }
  try {
    const snap = await db.doc(ROUTING_CONFIG_DOC_PATH).get();
    const data = (snap.data() ?? {}) as Record<string, unknown>;
    cachedRoutingConfig = {
      countries: sanitizeCountryRoutes(data.countries),
      fallback: sanitizeRoute(data.fallback) ?? FALLBACK_ROUTE,
    };
    routingConfigFetchedAtMs = now;
  } catch (error) {
    console.warn("Failed to load SOS routing config; using built-in table.", error);
    if (!cachedRoutingConfig) {
      cachedRoutingConfig = { countries: {}, fallback: FALLBACK_ROUTE };
    }
    routingConfigFetchedAtMs = now;
  }
  return cachedRoutingConfig;
}

function sanitizeCountryRoutes(raw: unknown): Record<string, AgencyRoute> {
  if (!raw || typeof raw !== "object") return {};
  const routes: Record<string, AgencyRoute> = {};
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    const iso = key.trim().toUpperCase();
    if (!/^[A-Z]{2}$/.test(iso)) continue;
    const route = sanitizeRoute(value);
    if (route) routes[iso] = route;
  }
  return routes;
}

function sanitizeRoute(raw: unknown): AgencyRoute | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const agency = typeof record.agency === "string" ? record.agency.trim().slice(0, 120) : "";
  const panelId = typeof record.panelId === "string" ? record.panelId.trim().toLowerCase() : "";
  if (!agency || !PANEL_ID_PATTERN.test(panelId)) return null;
  return { agency, panelId };
}

function resolveRoute(config: RoutingConfig, countryIso: string | null): AgencyRoute {
  if (!countryIso) return config.fallback;
  return config.countries[countryIso] ?? COUNTRY_AGENCY_MAP[countryIso] ?? config.fallback;
}

interface GpsPayload {
  lat: number;
  lon: number;
  provider: string;
  capturedAtMillis: number;
  accuracyMeters?: number;
}

interface ReportSosSignalResult {
  status: "active" | "resolved";
  panelId: string;
  agency: string;
}

export const reportSosSignal = onCall(
  { enforceAppCheck: true },
  async (request: CallableRequest<unknown>): Promise<ReportSosSignalResult> => {
    const uid = requireUid(request);
    const data = (request.data ?? {}) as Record<string, unknown>;

    const signalId = normalizeSignalId(data.signalId);
    if (!signalId) {
      throw new HttpsError("invalid-argument", "signalId must match cc-<24 hex>.");
    }
    const status = data.status === "resolved" ? "resolved" : "active";
    const gps = status === "active" ? parseGps(data) : parseOptionalGps(data);
    if (status === "active" && !gps) {
      throw new HttpsError("invalid-argument", "An active SOS report requires a valid location.");
    }
    const batteryPercent = parseBatteryPercent(data.batteryPercent);
    const countryIso = normalizeCountryIso(data.countryIso);

    const db = getFirestore();

    // Lightweight per-uid throttle for active heartbeats (best-effort, same pattern as contact
    // discovery). Resolves are exempt so stopping and immediately restarting SOS is never blocked.
    const throttleRef = db.doc(`${THROTTLE_COLLECTION}/${uid}`);
    const now = Date.now();
    if (status === "active") {
      const lastAt = (await throttleRef.get()).data()?.lastReportAtMs as number | undefined;
      if (typeof lastAt === "number" && now - lastAt < MIN_REPORT_INTERVAL_MS) {
        throw new HttpsError("resource-exhausted", "Reporting too frequently; slow down.");
      }
      await throttleRef.set({ lastReportAtMs: now, updatedAt: FieldValue.serverTimestamp() });
    }

    const routingRef = db.doc(`${ROUTING_COLLECTION}/${signalId}`);
    const routingConfig = await loadRoutingConfig(db);

    const result = await db.runTransaction(async (tx: Transaction) => {
      const routingSnap = await tx.get(routingRef);
      const routing = routingSnap.data();

      const ownerUid = routing?.victimUid as string | undefined;
      if (ownerUid && ownerUid !== uid) {
        throw new HttpsError("permission-denied", "This SOS signal belongs to another account.");
      }

      const route = resolveRoute(routingConfig, countryIso);
      const agency = (routing?.agency as string | undefined) ?? route.agency;
      const panelId = (routing?.panelId as string | undefined) ?? route.panelId;

      const panelRef = db.collection(AGENCY_PANELS_COLLECTION).doc(panelId);
      const signalRef = panelRef.collection("signals").doc(signalId);
      const signalSnap = await tx.get(signalRef);
      const firstSeenMillis =
        (signalSnap.data()?.firstSeenMillis as number | undefined) ?? now;

      if (!routingSnap.exists) {
        tx.set(routingRef, {
          signalId,
          victimUid: uid,
          agency,
          panelId,
          createdAt: FieldValue.serverTimestamp(),
        });
      }

      tx.set(
        panelRef,
        {
          agency,
          agencySlug: panelId,
          schemaVersion: PANEL_SCHEMA_VERSION,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      const signalData: Record<string, unknown> = {
        signalId,
        schemaVersion: SIGNAL_SCHEMA_VERSION,
        status,
        source: "internet",
        locationSource: "victim_gps",
        firstSeenMillis,
        lastSeenMillis: now,
        lastSeenAt: FieldValue.serverTimestamp(),
        lastReporterUid: uid,
        victimUid: uid,
        reporterKind: "victim",
      };
      if (gps) {
        signalData.gps = gps;
        signalData.victimGps = gps;
      }
      if (typeof batteryPercent === "number") {
        signalData.victimBatteryPercent = batteryPercent;
      }
      if (countryIso) {
        signalData.countryIso = countryIso;
      }
      if (status === "resolved") {
        signalData.resolvedAt = FieldValue.serverTimestamp();
        signalData.resolvedMillis = now;
      }
      tx.set(signalRef, signalData, { merge: true });

      return { status, panelId, agency } as ReportSosSignalResult;
    });

    return result;
  }
);

function normalizeSignalId(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const normalized = raw.trim().toLowerCase();
  return SIGNAL_ID_PATTERN.test(normalized) ? normalized : null;
}

function parseGps(data: Record<string, unknown>): GpsPayload | null {
  const lat = asFiniteNumber(data.lat);
  const lon = asFiniteNumber(data.lon);
  if (lat === null || lon === null) return null;
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
  if (lat === 0 && lon === 0) return null;

  const capturedAtMillis = asFiniteNumber(data.capturedAtMillis) ?? Date.now();
  const provider =
    typeof data.provider === "string" && data.provider.trim().length > 0
      ? data.provider.trim().slice(0, 40)
      : "android";
  const gps: GpsPayload = { lat, lon, provider, capturedAtMillis };
  const accuracyMeters = asFiniteNumber(data.accuracyMeters);
  if (accuracyMeters !== null && accuracyMeters > 0) {
    gps.accuracyMeters = accuracyMeters;
  }
  return gps;
}

function parseOptionalGps(data: Record<string, unknown>): GpsPayload | null {
  if (data.lat === undefined && data.lon === undefined) return null;
  return parseGps(data);
}

function parseBatteryPercent(raw: unknown): number | null {
  const value = asFiniteNumber(raw);
  if (value === null) return null;
  return Math.min(100, Math.max(0, Math.round(value)));
}

function normalizeCountryIso(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const normalized = raw.trim().toUpperCase();
  return /^[A-Z]{2}$/.test(normalized) ? normalized : null;
}

function asFiniteNumber(raw: unknown): number | null {
  return typeof raw === "number" && Number.isFinite(raw) ? raw : null;
}
