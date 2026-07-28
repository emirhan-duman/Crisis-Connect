import { onSchedule } from "firebase-functions/v2/scheduler";
import { getFirestore, Timestamp } from "firebase-admin/firestore";

/**
 * Safety-net cleanup for messages that were never acknowledged (recipient gone or never came
 * online). Delivered messages are removed immediately by acknowledgeMessage, so this daily job
 * normally finds little to do — it just guarantees nothing lingers past its expireAt.
 */
export const purgeExpiredMessages = onSchedule("every 24 hours", async () => {
  const db = getFirestore();
  const now = Timestamp.now();
  const BATCH = 400;

  const purge = async (build: () => FirebaseFirestore.Query) => {
    for (;;) {
      const snap = await build().limit(BATCH).get();
      if (snap.empty) break;
      const batch = db.batch();
      snap.docs.forEach((d) => batch.delete(d.ref));
      await batch.commit();
      if (snap.size < BATCH) break;
    }
  };

  await purge(() => db.collection("messages").where("expireAt", "<=", now));

  // Legacy sweep: early direct-write docs carry no expireAt (Firestore can't query a missing
  // field, so the expireAt query above never sees them). Anything older than the maximum
  // permitted TTL (30 days, enforced by the relay envelope validation) is garbage by definition.
  const maxTtlAgoMs = now.toMillis() - 30 * 24 * 60 * 60 * 1000;
  await purge(() => db.collection("messages").where("createdAtMs", "<=", maxTtlAgoMs));
});
