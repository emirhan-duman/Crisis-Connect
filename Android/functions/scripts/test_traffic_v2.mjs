import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
const require = createRequire(import.meta.url);
const { resolveTrafficPolicy } = require("../lib/messaging/trafficRuntimePolicy.js");
const { reserveRelayBudget } = require("../lib/messaging/relayBudget.js");

test("runtime rollback is stable, bounded and independent of baseline conservation", () => {
  const key = "installation-one";
  assert.equal(resolveTrafficPolicy({ enabled:false },key).emergencyFirst,false);
  assert.equal(resolveTrafficPolicy({ emergencyFirstPercent:0 },key).emergencyFirst,false);
  assert.equal(resolveTrafficPolicy({ emergencyFirstPercent:100 },key).emergencyFirst,true);
  assert.deepEqual(resolveTrafficPolicy({ emergencyFirstPercent:5 },key),resolveTrafficPolicy({ emergencyFirstPercent:5 },key));
  assert.equal(resolveTrafficPolicy({ retryAfterMs:-1 },key).retryAfterMs,30_000);
  assert.equal(resolveTrafficPolicy({ retryAfterMs:Infinity },key).retryAfterMs,30_000);
  assert.equal(resolveTrafficPolicy({ retryAfterMs:900_000 },key).retryAfterMs,300_000);
});
test("separate emergency reserve survives normal reconnect flood and refills gradually", () => {
  let state = {};
  for (let i=0;i<12;i++) { const r=reserveRelayBudget(state,1_000,false);assert.equal(r.retryAfterMs,0);state=r.state; }
  assert.equal(reserveRelayBudget(state,1_000,false).retryAfterMs,5_000);
  assert.equal(reserveRelayBudget(state,6_000,false).retryAfterMs,0);
  for (let i=0;i<3;i++) { const r=reserveRelayBudget(state,1_000,true);assert.equal(r.retryAfterMs,0);state=r.state; }
  assert.equal(reserveRelayBudget(state,1_000,true).retryAfterMs,10_000);
});

test("real Firestore: urgent mail precedes backlog, ordinary mail is not starved, total byte cap includes policy", {skip:!process.env.FIRESTORE_EMULATOR_HOST},async () => {
  initializeApp({projectId:"demo-crisis-traffic"});
  const db=getFirestore(); const {compactInbox}=require("../lib/messaging/compactInbox.js");
  const uid="v2recipient"; const batch=db.batch();
  const base={v:2,ctype:"signal",recipientUid:uid,senderUid:"sender",conversationId:"test",ttlMs:86_400_000,
    ciphertext:"a".repeat(500),expireAt:Timestamp.fromMillis(Date.now()+60_000)};
  for(let i=0;i<24;i++) batch.set(db.doc(`messages/v2_a_${String(i).padStart(2,"0")}`),{...base,priority:"normal"});
  for(let i=0;i<10;i++) batch.set(db.doc(`messages/v2_z_${String(i).padStart(2,"0")}`),{...base,priority:"high"});
  batch.set(db.doc("messages/v2_z_control"),{...base,priority:"high",ttlMs:15_000});
  await batch.commit();
  const request=data=>({auth:{uid,token:{}},data:{installationId:"v2-test",policyVersion:2,...data}});
  const result=await compactInbox.run(request({}));
  assert.equal(result.policy.version,2);
  assert.equal(result.messages[0].priority,"high");
  assert.equal(result.messages.filter(m=>m.priority==="high").length,8);
  assert.equal(result.messages.filter(m=>m.priority==="normal").length,8);
  assert.equal(result.messages.some(m=>m.messageId==="v2_z_control"),false);
  assert.ok(Buffer.byteLength(JSON.stringify(result))<=24_000);
  const seen=new Set(result.messages.map(m=>m.messageId));
  let cursor=result.cursor;
  for(let i=0;i<10;i++) {
    const next=await compactInbox.run(request({cursor,acknowledgements:[...seen]}));
    next.messages.forEach(m=>seen.add(m.messageId));cursor=next.cursor;
    if(!cursor) break;
  }
  assert.ok([...seen].filter(id=>!id.endsWith("control")).length>=34,"urgent insertion cannot skip the ordinary cursor");
  await db.recursiveDelete(db.doc(`messagingTraffic/${uid}`));
  const remaining=await db.collection("messages").where("recipientUid","==",uid).get();
  await Promise.all(remaining.docs.map(d=>d.ref.delete()));
});

test("real Firestore: relay retries consume no tokens, pacing errors are actionable, legacy clients keep their contract", {skip:!process.env.FIRESTORE_EMULATOR_HOST},async () => {
  const db=getFirestore();const {relayMessage}=require("../lib/messaging/relay.js");
  const uid="v2sender";
  const data={v:2,ctype:"signal",recipientUid:"v2target",conversationId:"v2pair",ciphertext:"YWJj",priority:"normal",createdAtMs:Date.now(),ttlMs:86_400_000,trafficPolicyVersion:2};
  const request=(id,extra={})=>({auth:{uid,token:{}},data:{...data,messageId:id,...extra}});
  for(let i=0;i<12;i++) await relayMessage.run(request(`v2_budget_${i}`));
  assert.equal((await relayMessage.run(request("v2_budget_0"))).duplicate,true);
  await assert.rejects(relayMessage.run(request("v2_budget_over")),e=>e.code==="resource-exhausted" && e.details.retryAfterMs>=30_000);
  await relayMessage.run(request("v2_budget_urgent",{priority:"high"}));
  await relayMessage.run(request("v2_budget_legacy",{trafficPolicyVersion:undefined}));
  const messages=await db.collection("messages").where("senderUid","==",uid).get();
  await Promise.all(messages.docs.map(d=>d.ref.delete()));await db.doc(`messagingRelayBudgets/${uid}`).delete();
});
