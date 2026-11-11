import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";

const require = createRequire(import.meta.url);
const { parseCompactRequest, compactEnvelope, compactInbox } = require("../lib/messaging/compactInbox.js");
const { deferCompactDelivery } = require("../lib/messaging/compactDeliveryPolicy.js");

test("conservation pauses typing/calls/media, preserves text and oversized first-session SOS", () => {
  assert.equal(deferCompactDelivery(false, 15_000, true, false), false);
  assert.equal(deferCompactDelivery(true, 15_000, false), true);
  assert.equal(deferCompactDelivery(true, 60_000, true, true, true), true);
  assert.equal(deferCompactDelivery(true, 86_400_000, false, false, false), true);
  assert.equal(deferCompactDelivery(true, 86_400_000, false, true, false), false);
  assert.equal(deferCompactDelivery(true, 86_400_000, false, false, true), false);
});

test("bounded ACK input, safe document IDs, and account-independent validation", () => {
  assert.deepEqual(parseCompactRequest({ installationId: "install-test", acknowledgements: ["uuid~123", "uuid~123"] }).acknowledgements, ["uuid~123"]);
  assert.equal(parseCompactRequest({ installationId: "install-test", cursor: Buffer.from("odd legacy id").toString("base64url") }).cursor, "odd legacy id");
  for (const input of [null, [], { acknowledgements: ["../foreign"] }, { acknowledgements: Array(101).fill("a") }, { cursor: "bad/path" }, { saving: "false" }]) {
    assert.throws(() => parseCompactRequest(input && !Array.isArray(input) ? { installationId: "install-test", ...input } : input));
  }
});

test("PQXDH short text fits, attachment chunks and extra fields do not leak", () => {
  const envelope = compactEnvelope({ ciphertext: "a".repeat(7_000), v: 2, ctype: "prekey", secret: "never return", expireAt: {} }, "msg");
  assert.equal(envelope.ctype, "prekey");
  assert.equal(envelope.secret, undefined);
  assert.equal(envelope.expireAt, undefined);
  assert.equal(compactEnvelope({ ciphertext: "a", messageId: "forged" }, "actual").messageId, "actual");
  assert.equal(compactEnvelope({ ciphertext: "a" }, "bad legacy id"), null);
  assert.equal(compactEnvelope({ ciphertext: "a" }, "transfer~123.43"), null, "small final media chunk must also wait");
  assert.equal(compactEnvelope({ ciphertext: "a".repeat(16_000) }, "media"), null);
});

test("real Firestore: recipient-only reads/deletes, pagination, byte budget and replay", { skip: !process.env.FIRESTORE_EMULATOR_HOST }, async () => {
  initializeApp({ projectId: "demo-crisis-traffic" });
  const db = getFirestore();
  const uid = "traffic-recipient";
  const request = (authUid, data) => ({ auth: authUid ? { uid: authUid, token: {} } : undefined, data: { installationId: "install-test", ...data } });
  const batch = db.batch();
  for (let i = 0; i < 130; i++) {
    const id = `traffic_${String(i).padStart(3, "0")}`;
    batch.set(db.doc(`messages/${id}`), {
      messageId: id, v: 2, ctype: "signal", recipientUid: uid, senderUid: "sender",
      conversationId: "conversation", ciphertext: "a".repeat(i < 105 ? 16_000 : 2_000),
      expireAt: Timestamp.fromMillis(Date.now() + 60_000),
    });
  }
  batch.set(db.doc("messages/traffic_foreign"), { recipientUid: "someone-else", ciphertext: "a" });
  await batch.commit();
  await assert.rejects(compactInbox.run(request(null, {})), error => error.code === "unauthenticated");
  await assert.rejects(compactInbox.run(request(uid, { acknowledgements: ["traffic_129", "traffic_foreign"] })), error => error.code === "permission-denied");
  assert.equal((await db.doc("messages/traffic_129").get()).exists, true, "unauthorized batch cannot partly delete");
  let cursor;
  const seen = new Set();
  for (let i = 0; i < 8; i++) {
    const page = await compactInbox.run(request(uid, { ...(cursor ? { cursor } : {}), saving: true }));
    assert.ok(Buffer.byteLength(JSON.stringify(page)) <= 24_000);
    for (const message of page.messages) {
      assert.equal(message.recipientUid, uid);
      seen.add(message.messageId);
    }
    cursor = page.cursor;
    if (!cursor) break;
  }
  assert.equal(seen.size, 25, "large deferred media cannot starve later short messages");
  await compactInbox.run(request(uid, { acknowledgements: [...seen], saving: false }));
  assert.equal((await db.doc("messages/traffic_129").get()).exists, false);
  assert.equal((await db.doc("messages/traffic_000").get()).exists, true, "media remains for recovery");
  assert.equal((await db.doc("messages/traffic_foreign").get()).exists, true);
  assert.equal((await db.doc(`messagingTraffic/${uid}/installations/install-test`).get()).exists, false, "normal mode clears the leased hint");
  await compactInbox.run(request(uid, { acknowledgements: [...seen] })); // Missing ACKs are idempotent.
  const cleanup = db.batch();
  for (let i = 0; i < 130; i++) cleanup.delete(db.doc(`messages/traffic_${String(i).padStart(3, "0")}`));
  cleanup.delete(db.doc("messages/traffic_foreign"));
  await cleanup.commit();
  await db.recursiveDelete(db.doc(`messagingTraffic/${uid}`));
});

test("real Firestore: monotonic device hints, ACK-only draining, invalid ACK is atomic", { skip: !process.env.FIRESTORE_EMULATOR_HOST }, async () => {
 const db=getFirestore(); const uid="revision-recipient"; const installationId="revision-install";
 const request=data=>({auth:{uid,token:{}},data:{installationId,receive:false,...data}});
 const profile=db.doc(`messagingTraffic/${uid}/installations/${installationId}`);
 await compactInbox.run(request({saving:true,profileRevision:10}));
 await compactInbox.run(request({saving:false,profileRevision:12}));
 await compactInbox.run(request({saving:true,profileRevision:11}));
 assert.equal((await profile.get()).get("savingUntil"),0,"late conservation cannot override normal recovery");
 await compactInbox.run(request({saving:true,profileRevision:12}));
 assert.equal((await profile.get()).get("savingUntil"),0,"equal revision retry cannot change the state");
 const own=db.doc("messages/revision-own"),foreign=db.doc("messages/revision-foreign");
 await own.set({recipientUid:uid,ciphertext:"a"});await foreign.set({recipientUid:"other",ciphertext:"a"});
 await assert.rejects(compactInbox.run(request({saving:true,profileRevision:13,acknowledgements:[own.id,foreign.id]})),e=>e.code==="permission-denied");
 assert.equal((await own.get()).exists,true);assert.equal((await profile.get()).get("revision"),12);
 const result=await compactInbox.run(request({saving:false,profileRevision:14,acknowledgements:[own.id]}));
 assert.deepEqual(result,{messages:[],cursor:null});assert.equal((await own.get()).exists,false);
 await foreign.delete();await db.recursiveDelete(db.doc(`messagingTraffic/${uid}`));
});
