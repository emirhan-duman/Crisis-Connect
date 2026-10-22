import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { requireUid } from "../certificates/callerRole";
import { gateway } from "./regionalGateway";
import { runCompatibleOperation } from "./compatibilityRouting";
import { LEGACY_MESSAGING_DATABASE, MULTIREGION_MESSAGING_DATABASE, withMessagingDatabase } from "./databaseTarget";
import { activateHighAvailability, haFirestore, usesHighAvailability } from "./highAvailabilityStore";
import { resolveResilientGroup, listResilientHierarchy, activeGroupProfile, authorizeGroup, groupBinding } from "./resilientGroups";
import { listAuthorityRoster } from "./authorityRoster";
import { requestAuthorityMlsPreparation } from "./authorityMlsPreparation";
import { getFirestore } from "firebase-admin/firestore";

export function highAvailabilityGateway(region: string) {
  const delegate = gateway(region);
  return onCall({ region, enforceAppCheck: true, invoker: "public", timeoutSeconds: 45, maxInstances: 100 }, async request => {
    const uid = requireUid(request);
    const operation = request.data?.operation;
    const payload = request.data?.payload;
    if (typeof operation !== "string" || !payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new HttpsError("invalid-argument", "Operation and payload required.");
    }
    if (payload.requestUid !== undefined && payload.requestUid !== uid) throw new HttpsError("unauthenticated", "Messaging account changed.");
    if (["resolveResilientGroup", "openResilientGroup", "listResilientHierarchy", "listAuthorityRoster", "requestAuthorityMlsPreparation"].includes(operation)) {
      if (process.env.GROUP_PROFILE_HA_ENABLED !== "true" ||
          (operation === "openResilientGroup" && process.env.GROUP_INDEPENDENT_BOOTSTRAP_ENABLED !== "true")) {
        const gate = await haFirestore().doc("applicationRuntime/canaries").get();
        if (!(Number(gate.get("expiresAtMs")) > Date.now() && (gate.get("uids") ?? []).includes(uid)))
          throw new HttpsError("unavailable", "Group resilience is not enabled.");
      }
      let result: unknown;
      if (operation === "resolveResilientGroup" || operation === "openResilientGroup") {
        // A server-owned, expiring fault fixture can deny default-store access for disposable
        // accounts. No client-supplied flag can enable this or bypass authorization.
        const fault = await haFirestore().doc("applicationRuntime/bootstrapOutageCanaries").get();
        const denyLegacy = Number(fault.get("expiresAtMs")) > Date.now() && (fault.get("uids") ?? []).includes(uid);
        result = await resolveResilientGroup(uid, payload.binding, payload.deviceId, () => {
          if (denyLegacy) throw new HttpsError("unavailable", "Legacy directory outage simulation.");
          return getFirestore();
        }, operation === "openResilientGroup");
      }
      else if (operation === "listResilientHierarchy") result = await listResilientHierarchy(uid);
      else {
        if (operation === "requestAuthorityMlsPreparation" &&
            (typeof payload.conversationId !== "string" || !/^am2_[A-Za-z0-9_-]{43}$/.test(payload.conversationId))) {
          throw new HttpsError("invalid-argument", "Invalid conversation ID.");
        }
        const action = operation === "listAuthorityRoster" ? listAuthorityRoster : requestAuthorityMlsPreparation;
        const database = operation === "requestAuthorityMlsPreparation" &&
          !(await haFirestore().doc(`authorityHaRoutesById/${String(payload.conversationId)}`).get()).exists
          ? LEGACY_MESSAGING_DATABASE : MULTIREGION_MESSAGING_DATABASE;
        if (operation === "listAuthorityRoster" && !activeGroupProfile((await haFirestore().doc(`users/${uid}`).get()).data() ?? {}))
          throw new HttpsError("permission-denied", "Inactive group membership.");
        if (operation === "requestAuthorityMlsPreparation" && database === MULTIREGION_MESSAGING_DATABASE)
          await authorizeGroup(uid, groupBinding((await haFirestore().doc(`authorityMlsV2/${payload.conversationId}`).get()).data()));
        result = await withMessagingDatabase<unknown>(database, () => action.run({ ...request, data: payload }));
      }
      return { ...(result as object), routing: { databaseId: MULTIREGION_MESSAGING_DATABASE, protocol: 4, servingRegion: region } };
    }
    const enrolled = await usesHighAvailability(uid);
    const canaries = (process.env.HA_CANARY_UIDS ?? "").split(",").filter(Boolean);
    if (!enrolled && process.env.HA_ENROLLMENT_ENABLED !== "true" && !canaries.includes(uid)) {
      const canary = await haFirestore().doc("messagingRuntime/haCanaries").get();
      if (!(Number(canary.get("expiresAtMs")) > Date.now() && Array.isArray(canary.get("uids")) && canary.get("uids").includes(uid))) {
        throw new HttpsError("unavailable", "High availability enrollment is not enabled.");
      }
    }
    if (process.env.HA_COMPATIBILITY_ENABLED !== "true") {
      throw new HttpsError("unavailable", "Compatibility rollout is not ready.");
    }
    const dispatch = (database: string, body = payload) => withMessagingDatabase(database,
      () => delegate.run({ ...request, data: { operation, payload: body } }));
    let result: Record<string, unknown>;
    if (operation === "fetchSignalPreKeyBundle" || (operation === "lookupIdentityKey" && typeof payload.uid === "string")) {
      const target = operation === "fetchSignalPreKeyBundle" ? payload.targetUid : payload.uid;
      const database = typeof target === "string" && /^[A-Za-z0-9]{1,128}$/.test(target) && await usesHighAvailability(target)
        ? MULTIREGION_MESSAGING_DATABASE : LEGACY_MESSAGING_DATABASE;
      result = await dispatch(database);
    } else if (operation === "lookupIdentityKey") {
      try { result = await dispatch(MULTIREGION_MESSAGING_DATABASE); }
      catch (error) {
        if ((error as { code?: string }).code !== "not-found") throw error;
        result = await dispatch(LEGACY_MESSAGING_DATABASE);
        if (typeof result.uid === "string" && await usesHighAvailability(result.uid)) {
          throw new HttpsError("not-found", "No discoverable account matches.");
        }
      }
    } else if (operation === "findContactsOnCrisisConnect") {
      result = await runCompatibleOperation(operation, { ...request, data: payload } as CallableRequest<any>,
        () => delegate.run({ ...request, data: { operation, payload } }));
    } else {
      result = await dispatch(MULTIREGION_MESSAGING_DATABASE);
      if (operation === "publishIdentityKey") {
        await activateHighAvailability(uid);
      }
    }
    return { ...result, routing: { ...(result.routing as object ?? {}), databaseId: MULTIREGION_MESSAGING_DATABASE,
      protocol: 4, servingRegion: region } };
  });
}

export const messagingGatewayHaEurope = highAvailabilityGateway("europe-west4");
export const messagingGatewayHaUS = highAvailabilityGateway("us-central1");
