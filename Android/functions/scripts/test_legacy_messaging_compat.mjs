import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
const require = createRequire(import.meta.url);

test("legacy Android/iOS/VoIP keep delivery when a new installation on the same account saves data", { skip: !process.env.FIRESTORE_EMULATOR_HOST }, async () => {
  initializeApp({ projectId: "demo-crisis-traffic" });
  const db = getFirestore();
  const { onMessageCreated } = require("../lib/messaging/onMessageCreated.js");
  const { registerPushToken } = require("../lib/messaging/pushTokens.js");
  const apns = require("../lib/messaging/apnsVoip.js");
  const pushes = [], rings = [];
  getMessaging().sendEachForMulticast = async message => {
    pushes.push(message);
    return { successCount: message.tokens.length, failureCount: 0, responses: message.tokens.map(() => ({ success: true, messageId: "mock" })) };
  };
  apns.sendVoipPush = async token => { rings.push(token); return { shouldPrune: false }; };
  const uid = "legacy-compat-recipient";
  const registration = async (token, platform, modern) => registerPushToken.run({ auth: { uid, token: {} }, data: {
    token, platform, ...(modern ? { installationId: "modern-install", trafficPolicyVersion: 1 } : {})
  }});
  for (const platform of ["android", "ios", "ios-voip"]) {
    await registration(`legacy-${platform}`, platform, false);
    await registration(`modern-${platform}`, platform, true);
  }
  await db.doc(`messagingTraffic/${uid}/installations/modern-install`).set({ savingUntil: Date.now() + 300_000 });
  const deliver = async (id, ttlMs, extra = {}) => {
    pushes.length = rings.length = 0;
    const ref = db.doc(`messages/${id}`);
    await ref.set({ v: 1, messageId: id, recipientUid: uid, senderUid: "sender", conversationId: "conversation",
      ttlMs, priority: "normal", ciphertext: "YWJj", createdAtMs: Date.now(), ...extra });
    await onMessageCreated.run({ data: await ref.get(), params: { messageId: id } });
    return pushes.flatMap(p => p.tokens).sort();
  };
  assert.deepEqual(await deliver("compat-typing", 15_000), ["legacy-android", "legacy-ios"]);
  await deliver("compat-call", 60_000, { callRing: true, priority: "high" });
  assert.deepEqual(rings, ["legacy-ios-voip"]);
  assert.deepEqual(await deliver("compat-media.43", 86_400_000), ["legacy-android", "legacy-ios"]);
  assert.deepEqual(await deliver("compat-text", 86_400_000), ["legacy-android", "legacy-ios", "modern-android", "modern-ios"]);
  assert.equal(pushes.find(p => p.tokens.includes("legacy-android")).android.priority, "high");
  assert.equal(pushes.find(p => p.tokens.includes("modern-android")).android.priority, "normal");
  for (const push of pushes) assert.equal(push.data.installationId, undefined, "legacy wire envelope is unchanged");
  assert.deepEqual(await deliver("compat-signal", 86_400_000, { v: 2, ctype: "signal" }),
    ["legacy-android", "legacy-ios", "modern-android", "modern-ios"]);
  for (const push of pushes) { assert.equal(push.data.v, "2"); assert.equal(push.data.ctype, "signal"); }
  assert.equal(pushes.find(p => p.tokens.includes("legacy-ios")).apns.headers["apns-push-type"], "alert");
  assert.deepEqual(await deliver("compat-large-legacy", 86_400_000, { ciphertext: "a".repeat(7_000) }),
    ["legacy-android", "legacy-ios"]);
  for (const push of pushes) assert.equal(push.data.fetch, "1", "legacy oversized-message wake remains supported");
  assert.deepEqual(await deliver("compat-sos", 86_400_000, { priority: "high", ciphertext: "a".repeat(7_000) }),
    ["legacy-android", "legacy-ios", "modern-android", "modern-ios"]);
  await registration("modern-android", "android", false); // Downgrading clears the opt-in marker.
  assert.deepEqual(await deliver("compat-downgrade", 15_000), ["legacy-android", "legacy-ios", "modern-android"]);
  await db.doc(`messagingTraffic/${uid}/installations/modern-install`).set({ savingUntil: Date.now() - 1 });
  assert.deepEqual(await deliver("compat-recovered", 15_000), ["legacy-android", "legacy-ios", "modern-android", "modern-ios"]);
  await db.recursiveDelete(db.doc(`messagingTokens/${uid}`));
  await db.recursiveDelete(db.doc(`messagingTraffic/${uid}`));
  const messages = await db.collection("messages").where("recipientUid", "==", uid).get();
  await Promise.all(messages.docs.map(d => d.ref.delete()));
});

test("delivery status never recreates an acknowledged ciphertext document", { skip: !process.env.FIRESTORE_EMULATOR_HOST }, async () => {
 const db=getFirestore();const {updateDeliveryState}=require("../lib/messaging/onMessageCreated.js");
 const ref=db.doc("messages/ack-race-test");await ref.set({recipientUid:"ack-race-recipient"});await ref.delete();
 await updateDeliveryState(ref,{delivered:true,deliveredCount:1});assert.equal((await ref.get()).exists,false);
});
