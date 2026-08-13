import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { getStorage } from "firebase-admin/storage";
import { requireUid } from "../certificates/callerRole";

/**
 * Account + data deletion, the single authoritative erase path for every client.
 *
 * Why this MUST live on the server: the most identifying data a user has is in collections that
 * Firestore rules close to clients entirely — `messagingKeys`, `messagingDirectory`,
 * `messagingTokens` and `signalPreKeys` are all `allow read, write: if false`, and `users/{uid}`
 * is `allow delete: if false`. A client-side "delete my account" (calling user.delete() and
 * deleting a doc or two) therefore CANNOT erase the phone-number hash that makes someone
 * discoverable — it silently leaves it behind. Every platform calls this callable instead.
 *
 * Ordering: Firebase Auth is deleted LAST. If a Firestore step fails the caller still owns their
 * account and can retry; the reverse order would strand data with no authenticated owner able to
 * ask for it again.
 *
 * SOS signals are ANONYMISED, not deleted. A signal is a rescue record — a coordinate, a
 * timestamp, a battery level that a field team may still be acting on — and destroying it mid
 * incident blinds the people looking for that person. Deleting the account severs the link to the
 * human (`victimUid` → null, `victimUidRedacted` → true) and keeps the incident. Play's policy
 * permits retention for a legitimate purpose as long as the privacy policy discloses it; the
 * disclosure lives in the "Verilerinizi yönetmek" section of the privacy policy.
 *
 * Auth posture: App Check + an authenticated caller, and the UI type-to-confirms. There is
 * deliberately no `auth_time` freshness gate: phone-verified users on this project keep
 * `sign_in_provider === "anonymous"` (the Twilio OTP flow attaches the number through the Admin
 * SDK) so they hold no client-side credential to re-authenticate with — a recent-login check
 * would lock exactly the users most likely to want out.
 *
 * Every step is best-effort and counted. A single unreachable collection must not abort the erase
 * and leave the user half-deleted with no way to finish; the returned + persisted summary says
 * what actually happened.
 */

const BATCH = 300;

/**
 * How fresh the caller's sign-in must be. Firebase applies the same rule to user.delete() and the
 * other account-critical operations; moving the erase server-side would otherwise quietly drop that
 * protection, leaving a found-and-unlocked phone one tap from destroying the account. OWASP calls
 * for re-authentication before critical operations, and Apple explicitly permits a verification step
 * (it forbids only making deletion hard to reach) — so one step, and no more.
 *
 * Five minutes is enough to reauthenticate and retry, short enough that a stolen session goes stale.
 */
const MAX_AUTH_AGE_SECONDS = 5 * 60;

/** Marker the clients match on to know they should reauthenticate and retry. */
const REAUTH_REQUIRED = "requires-recent-login";

/**
 * Rejects a caller whose sign-in predates the freshness window.
 *
 * `auth_time` is the moment the user last actually proved who they are; refreshing an ID token does
 * not move it, which is precisely why it is the right signal here. Federated clients advance it with
 * reauthenticate(); phone accounts advance it by signing in again through the OTP flow, which mints
 * a new session from a custom token.
 */
function requireRecentAuth(request: CallableRequest<unknown>): void {
  const token = request.auth?.token as Record<string, unknown> | undefined;
  const authTime = typeof token?.auth_time === "number" ? token.auth_time : null;
  if (authTime === null) {
    // No auth_time at all: refuse rather than guess. A token without it is not one we issued through
    // a normal sign-in, and this is the one operation where the safe default is "no".
    throw new HttpsError("failed-precondition", REAUTH_REQUIRED);
  }
  const ageSeconds = Date.now() / 1000 - authTime;
  if (ageSeconds > MAX_AUTH_AGE_SECONDS) {
    throw new HttpsError("failed-precondition", REAUTH_REQUIRED);
  }
}

/** Non-PII record that the erase ran. Server-only in rules; proves compliance without re-storing
 *  anything about the person who left. */
const TOMBSTONE_COLLECTION = "accountDeletions";

interface DeletionSummary {
  deletedDocs: number;
  anonymizedSignals: number;
  deletedFiles: number;
  authDeleted: boolean;
  /** Steps that threw. Non-empty means a follow-up sweep is warranted, not that nothing happened. */
  failedSteps: string[];
}

/**
 * Every Storage prefix that belongs to one user. Both are keyed by uid, and uid comes from the
 * verified token rather than the request, so neither can be steered at someone else's objects.
 */
const STORAGE_PREFIXES = [
  // Profile photo.
  (uid: string) => `users/${uid}/`,
  // Files the user attached to agency/hierarchy channel messages. Encrypted at rest, but still
  // their content: Apple's rule names user-generated media explicitly, and "we kept your files, in
  // a form you can no longer read" is not deletion.
  (uid: string) => `messageAttachments/${uid}/`,
];

export const deleteAccountAndData = onCall(
  // The cascade walks every agency panel and runs a collection-group sweep, so the 60 s default is
  // not enough headroom for a long-lived account on a cold start. Timing out mid-cascade is not
  // destructive (every step is idempotent and the caller can retry), but it would tell someone
  // their erase failed while half of it had already happened.
  { enforceAppCheck: true, timeoutSeconds: 300, memory: "512MiB" },
  async (request: CallableRequest<unknown>): Promise<DeletionSummary> => {
    const uid = requireUid(request);
    requireRecentAuth(request);
    const db = getFirestore();

    const summary: DeletionSummary = {
      deletedDocs: 0,
      anonymizedSignals: 0,
      deletedFiles: 0,
      authDeleted: false,
      failedSteps: [],
    };

    /** Runs a step, recording rather than propagating failure. */
    const step = async (name: string, run: () => Promise<void>) => {
      try {
        await run();
      } catch (error) {
        console.error(`deleteAccountAndData: step "${name}" failed for uid=${uid}`, error);
        summary.failedSteps.push(name);
      }
    };

    /** Deletes a query's full result set, page by page. */
    const purgeQuery = async (build: () => FirebaseFirestore.Query) => {
      for (;;) {
        const snap = await build().limit(BATCH).get();
        if (snap.empty) break;
        const batch = db.batch();
        snap.docs.forEach((d) => batch.delete(d.ref));
        await batch.commit();
        summary.deletedDocs += snap.size;
        if (snap.size < BATCH) break;
      }
    };

    /**
     * Deletes a document and everything beneath it.
     *
     * Deliberately NOT gated on `exists`: a Firestore parent can be missing while its
     * subcollections are full. `registerPushToken` only ever writes
     * `messagingTokens/{uid}/tokens/{hash}`, so `messagingTokens/{uid}` does not exist as a
     * document — an exists() check here would skip the recursiveDelete and leave every push token
     * the user ever registered on our servers, still receiving notifications for a deleted account.
     */
    const purgeTree = async (path: string) => {
      const ref = db.doc(path);
      const existed = (await ref.get()).exists;
      await db.recursiveDelete(ref);
      if (existed) summary.deletedDocs += 1;
    };

    // ── 1. Discovery directory ────────────────────────────────────────────────
    // First, because it is the entry that makes the user findable by phone number or username to
    // anyone who knows it. messagingKeys/{uid}.directoryHashes is the authoritative list of the
    // hashes this identity claimed (publishIdentityKey keeps it in sync), so no query is needed.
    await step("messagingDirectory", async () => {
      const keySnap = await db.doc(`messagingKeys/${uid}`).get();
      const hashes = keySnap.data()?.directoryHashes;
      if (!Array.isArray(hashes) || hashes.length === 0) return;
      const batch = db.batch();
      for (const hash of hashes) {
        if (typeof hash === "string" && hash.length > 0) {
          batch.delete(db.doc(`messagingDirectory/${hash}`));
        }
      }
      await batch.commit();
      summary.deletedDocs += hashes.length;
    });

    // ── 2. Identity, keys and push routing ───────────────────────────────────
    // signalPreKeys and messagingTokens carry subcollections (one-time prekey pools, per-device
    // tokens), so they need recursiveDelete rather than a doc delete.
    await step("messagingKeys", () => purgeTree(`messagingKeys/${uid}`));
    await step("signalPreKeys", () => purgeTree(`signalPreKeys/${uid}`));
    await step("messagingTokens", () => purgeTree(`messagingTokens/${uid}`));

    // ── 3. Presence and rate-limit bookkeeping ───────────────────────────────
    await step("presence", () => purgeTree(`presence/${uid}`));
    await step("presenceSettings", () => purgeTree(`presenceSettings/${uid}`));
    await step("messagingScanThrottle", () => purgeTree(`messagingScanThrottle/${uid}`));
    await step("sosReportThrottle", () => purgeTree(`sosReportThrottle/${uid}`));

    // ── 4. Undelivered message envelopes ─────────────────────────────────────
    // Delivered messages are removed on ack; what remains is ciphertext queued for or from this
    // user. Two queries because Firestore has no OR across different fields at this scale.
    await step("messages:sent", () =>
      purgeQuery(() => db.collection("messages").where("senderUid", "==", uid))
    );
    await step("messages:received", () =>
      purgeQuery(() => db.collection("messages").where("recipientUid", "==", uid))
    );

    // ── 5. Role certificates and rescue device records ───────────────────────
    await step("certificates", () =>
      purgeQuery(() => db.collection("certificates").where("ownerUid", "==", uid))
    );
    await step("rescueDevices", () =>
      purgeQuery(() => db.collection("rescueDevices").where("uid", "==", uid))
    );

    // ── 6. Per-panel data: Sentinel chats (plaintext) + SOS anonymisation ────
    // agencyPanels is bounded by the number of national agencies with panels, so iterating it and
    // running an indexed equality query per panel avoids needing a collection-group index.
    await step("agencyPanels", async () => {
      const panels = await db.collection("agencyPanels").select().get();
      for (const panel of panels.docs) {
        // Cloud AI chats are stored as plaintext (not E2E, not ephemeral) → hard delete, including
        // the messages subcollection under each chat.
        const chats = await panel.ref.collection("chats").where("userId", "==", uid).get();
        for (const chat of chats.docs) {
          await db.recursiveDelete(chat.ref);
          summary.deletedDocs += 1;
        }

        // SOS: sever the person, keep the incident (see header).
        const signals = await panel.ref.collection("signals").where("victimUid", "==", uid).get();
        for (const signal of signals.docs) {
          await signal.ref.set(
            {
              victimUid: null,
              victimUidRedacted: true,
              redactedAt: FieldValue.serverTimestamp(),
              ...(signal.data().lastReporterUid === uid ? { lastReporterUid: null } : {}),
            },
            { merge: true }
          );
          summary.anonymizedSignals += 1;
        }
      }
    });

    // Routing pins carry the same owner uid and must be redacted in step with the signals.
    await step("sosSignalRouting", async () => {
      const routes = await db
        .collection("sosSignalRouting")
        .where("victimUid", "==", uid)
        .get();
      for (const route of routes.docs) {
        await route.ref.set(
          { victimUid: null, victimUidRedacted: true, redactedAt: FieldValue.serverTimestamp() },
          { merge: true }
        );
      }
    });

    // ── 7. Field-team sighting records ───────────────────────────────────────
    // A rescuer's own reports live in signals/{id}/reporters/{deviceId} across every panel. Needs a
    // COLLECTION_GROUP index on reporters.uid (firestore.indexes.json); if that index is missing the
    // query throws and the step is recorded instead of aborting the erase.
    await step("reporters", () =>
      purgeQuery(() => db.collectionGroup("reporters").where("uid", "==", uid))
    );

    // ── 8. Profile document ──────────────────────────────────────────────────
    // After the panel sweep: resolvePanelId-style lookups elsewhere read users/{uid}, so keeping it
    // until the panel work is done avoids a half-erased user being unresolvable mid-run.
    await step("users", () => purgeTree(`users/${uid}`));

    // ── 9. Storage: avatar and any other per-user object ─────────────────────
    await step("storage", async () => {
      const bucket = getStorage().bucket();
      for (const prefixFor of STORAGE_PREFIXES) {
        const [files] = await bucket.getFiles({ prefix: prefixFor(uid) });
        await Promise.all(files.map((file) => file.delete({ ignoreNotFound: true })));
        summary.deletedFiles += files.length;
      }
    });

    // ── 10. Tombstone ────────────────────────────────────────────────────────
    // Written before the Auth delete so the record exists even if that last call fails.
    await step("tombstone", async () => {
      await db.doc(`${TOMBSTONE_COLLECTION}/${uid}`).set({
        deletedAt: FieldValue.serverTimestamp(),
        deletedDocs: summary.deletedDocs,
        anonymizedSignals: summary.anonymizedSignals,
        deletedFiles: summary.deletedFiles,
        failedSteps: summary.failedSteps,
        source: "callable",
      });
    });

    // ── 11. Firebase Auth ────────────────────────────────────────────────────
    try {
      await getAuth().deleteUser(uid);
      summary.authDeleted = true;
    } catch (error) {
      const code = (error as { code?: string } | null)?.code;
      if (code === "auth/user-not-found") {
        // Already gone (double submit, or a client that deleted locally first) — not a failure.
        summary.authDeleted = true;
      } else {
        console.error(`deleteAccountAndData: auth delete failed for uid=${uid}`, error);
        // The data is gone but the login still works. Surfacing this lets the client tell the user
        // the truth and retry, rather than reporting a clean deletion that did not finish.
        throw new HttpsError(
          "internal",
          "Account data was deleted but the sign-in could not be removed. Please try again."
        );
      }
    }

    console.log(
      `deleteAccountAndData: uid=${uid} docs=${summary.deletedDocs} ` +
        `signals=${summary.anonymizedSignals} files=${summary.deletedFiles} ` +
        `failed=[${summary.failedSteps.join(",")}]`
    );

    return summary;
  }
);
