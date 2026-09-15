import fs from "node:fs";
import path from "node:path";
import test, { after, before, beforeEach } from "node:test";
import { fileURLToPath } from "node:url";

import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  setDoc,
  Timestamp,
  updateDoc,
  where,
} from "firebase/firestore";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const rulesPath = process.env.FIRESTORE_RULES_PATH
  ? path.resolve(process.env.FIRESTORE_RULES_PATH)
  : path.resolve(scriptDirectory, "../../firestore.rules");
const projectId = "demo-crisis-connect";
const panelId = "ankara-afad";
const otherPanelId = "izmir-afad";
const signalId = "cc-0123456789abcdef01234567";

let testEnvironment;

function userDatabase(uid, token = {}) {
  return testEnvironment.authenticatedContext(uid, token).firestore();
}

function anonymousDatabase() {
  return testEnvironment.unauthenticatedContext().firestore();
}

async function seedDocuments(documents) {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const database = context.firestore();
    await Promise.all(
      Object.entries(documents).map(([documentPath, data]) =>
        setDoc(doc(database, documentPath), data),
      ),
    );
  });
}

function dashboardUser(overrides = {}) {
  return {
    role: "authority",
    verified: true,
    dashboardAccess: true,
    agency: "Ankara AFAD",
    agencySlug: panelId,
    agencyKey: panelId,
    ...overrides,
  };
}

function validV1Message(overrides = {}) {
  return {
    v: 1,
    messageId: "message-1",
    conversationId: "conversation-1",
    senderUid: "alice",
    recipientUid: "bob",
    priority: "normal",
    createdAtMs: 1_700_000_000_000,
    ttlMs: 86_400_000,
    alg: "P256-HKDF-SHA256-A256GCM",
    ephemeralPubKey: "QUJDRA==",
    nonce: "QUJDRA==",
    ciphertext: "QUJDRA==",
    expireAt: Timestamp.fromMillis(1_700_086_400_000),
    ...overrides,
  };
}

function validV2Message(overrides = {}) {
  const {
    alg: _alg,
    ephemeralPubKey: _ephemeralPubKey,
    nonce: _nonce,
    ...common
  } = validV1Message();
  return {
    ...common,
    v: 2,
    ctype: "prekey",
    ...overrides,
  };
}

function validSignal(overrides = {}) {
  return {
    signalId,
    schemaVersion: 3,
    status: "active",
    source: "ble",
    firstSeenMillis: 1_700_000_000_000,
    lastSeenMillis: 1_700_000_001_000,
    lastReporterUid: "rescuer",
    ...overrides,
  };
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId,
    firestore: {
      rules: fs.readFileSync(rulesPath, "utf8"),
    },
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
});

after(async () => {
  await testEnvironment.cleanup();
});

test("users can read only their own profile", async () => {
  await seedDocuments({
    "users/alice": { role: "user", verified: false },
  });

  await assertSucceeds(getDoc(doc(userDatabase("alice"), "users/alice")));
  await assertFails(getDoc(doc(userDatabase("bob"), "users/alice")));
  await assertFails(getDoc(doc(anonymousDatabase(), "users/alice")));
});

test("self-service profiles cannot bootstrap privileged roles", async () => {
  await assertSucceeds(
    setDoc(doc(userDatabase("alice"), "users/alice"), {
      id: "alice",
      role: "user",
      verified: false,
      displayName: "Alice",
    }),
  );
  await assertFails(
    setDoc(doc(userDatabase("mallory"), "users/mallory"), {
      id: "mallory",
      role: "admin",
      dashboardAccess: true,
    }),
  );
});

test("profile updates cannot change authorization fields", async () => {
  await seedDocuments({
    "users/alice": { role: "user", verified: false, displayName: "Alice" },
  });
  const profile = doc(userDatabase("alice"), "users/alice");

  await assertSucceeds(updateDoc(profile, { displayName: "Alice A." }));
  await assertFails(updateDoc(profile, { role: "admin", dashboardAccess: true }));
  await assertFails(updateDoc(profile, { agencySlug: panelId }));
});

test("dashboard access follows role, flag, and org-role revocation", async () => {
  await seedDocuments({
    "users/authority": dashboardUser(),
    "users/revoked": dashboardUser({ dashboardAccess: false }),
    "users/field": dashboardUser({
      role: "fieldteam",
      orgRole: { permissions: { dashboard: false } },
    }),
    "dashboard/summary": { activeSignals: 2 },
  });

  await assertSucceeds(getDoc(doc(userDatabase("authority"), "dashboard/summary")));
  await assertFails(getDoc(doc(userDatabase("revoked"), "dashboard/summary")));
  await assertFails(getDoc(doc(userDatabase("field"), "dashboard/summary")));
});

test("agency panel reads are isolated from unrelated agencies", async () => {
  await seedDocuments({
    "users/authority": dashboardUser(),
    "users/outsider": dashboardUser({
      agency: "Izmir AFAD",
      agencySlug: otherPanelId,
      agencyKey: otherPanelId,
    }),
    [`agencyPanels/${panelId}`]: {
      agency: "Ankara AFAD",
      agencySlug: panelId,
      schemaVersion: 3,
    },
    [`agencyPanels/${panelId}/signals/${signalId}`]: validSignal(),
  });

  await assertSucceeds(
    getDoc(doc(userDatabase("authority"), `agencyPanels/${panelId}/signals/${signalId}`)),
  );
  await assertFails(
    getDoc(doc(userDatabase("outsider"), `agencyPanels/${panelId}/signals/${signalId}`)),
  );
});

test("server-only collections reject direct client access", async () => {
  await seedDocuments({
    "users/admin": dashboardUser({ role: "admin", platformAdmin: true }),
    "rescueKeys/key-1": { secret: "sealed" },
    "system/config": { mode: "production" },
    "signalPreKeys/alice/devices/phone": { key: "sealed" },
  });
  const database = userDatabase("admin", { role: "admin" });

  await assertFails(getDoc(doc(database, "rescueKeys/key-1")));
  await assertFails(setDoc(doc(database, "system/config"), { mode: "debug" }));
  await assertFails(getDoc(doc(database, "signalPreKeys/alice/devices/phone")));
});

test("message creation binds sender and recipient", async () => {
  const alice = userDatabase("alice");

  await assertSucceeds(
    setDoc(doc(alice, "messages/message-1"), validV1Message()),
  );
  await assertFails(
    setDoc(
      doc(alice, "messages/forged"),
      validV1Message({ messageId: "forged", senderUid: "mallory" }),
    ),
  );
  await assertFails(
    setDoc(
      doc(alice, "messages/self"),
      validV1Message({ messageId: "self", recipientUid: "alice" }),
    ),
  );
  await assertSucceeds(
    setDoc(
      doc(alice, "messages/signal-prekey"),
      validV2Message({ messageId: "signal-prekey" }),
    ),
  );
  await assertSucceeds(
    setDoc(
      doc(alice, "messages/signal-ratchet"),
      validV2Message({ messageId: "signal-ratchet", ctype: "signal" }),
    ),
  );
});

test("only message participants can get or delete an envelope", async () => {
  await seedDocuments({ "messages/message-1": validV1Message() });

  await assertSucceeds(getDoc(doc(userDatabase("alice"), "messages/message-1")));
  await assertSucceeds(getDoc(doc(userDatabase("bob"), "messages/message-1")));
  await assertFails(getDoc(doc(userDatabase("mallory"), "messages/message-1")));
  await assertFails(deleteDoc(doc(userDatabase("mallory"), "messages/message-1")));
  await assertSucceeds(deleteDoc(doc(userDatabase("bob"), "messages/message-1")));

  await seedDocuments({ "messages/message-1": validV1Message() });
  await assertSucceeds(deleteDoc(doc(userDatabase("alice"), "messages/message-1")));
});

test("message list queries must be constrained to the recipient", async () => {
  await seedDocuments({ "messages/message-1": validV1Message() });
  const bobMessages = collection(userDatabase("bob"), "messages");

  await assertSucceeds(
    getDocs(query(bobMessages, where("recipientUid", "==", "bob"))),
  );
  await assertFails(getDocs(bobMessages));
  await assertFails(
    getDocs(query(bobMessages, where("senderUid", "==", "bob"))),
  );
});

test("persisted message envelopes are immutable to clients", async () => {
  await seedDocuments({ "messages/message-1": validV1Message() });
  const message = doc(userDatabase("bob"), "messages/message-1");

  await assertFails(updateDoc(message, { nonce: "Rk9SR0VE" }));
  await assertFails(updateDoc(message, { expireAt: Timestamp.fromMillis(4_102_444_800_000) }));
  await assertFails(updateDoc(message, { serverReceivedAt: Timestamp.now(), delivered: true }));
  await assertFails(updateDoc(message, { arbitrary: "data".repeat(1_000) }));
});

test("rescuers can write active BLE signals only to their own panel", async () => {
  await seedDocuments({
    "users/rescuer": dashboardUser({ role: "fieldteam", verified: true }),
  });
  const database = userDatabase("rescuer", { role: "fieldteam" });

  await assertSucceeds(
    setDoc(doc(database, `agencyPanels/${panelId}/signals/${signalId}`), validSignal()),
  );
  await assertFails(
    setDoc(doc(database, `agencyPanels/${otherPanelId}/signals/${signalId}`), validSignal()),
  );
  await assertFails(
    setDoc(
      doc(database, `agencyPanels/${panelId}/signals/invalid-id`),
      validSignal({ signalId: "invalid-id" }),
    ),
  );
});

test("rescue clients cannot write server-owned SOS fields", async () => {
  await seedDocuments({
    "users/rescuer": dashboardUser({ role: "fieldteam", verified: true }),
  });
  const database = userDatabase("rescuer", { role: "fieldteam" });
  const signal = doc(database, `agencyPanels/${panelId}/signals/${signalId}`);

  await assertFails(setDoc(signal, validSignal({ victimUid: "victim" })));

  await seedDocuments({
    [`agencyPanels/${panelId}/signals/${signalId}`]: validSignal({
      victimUid: "victim",
      panelPath: [panelId],
    }),
  });
  await assertSucceeds(updateDoc(signal, { lastSeenMillis: 1_700_000_002_000 }));
  await assertFails(updateDoc(signal, { victimUid: "someone-else" }));
  await assertFails(updateDoc(signal, { resolvedAt: Timestamp.now(), status: "resolved" }));
});
