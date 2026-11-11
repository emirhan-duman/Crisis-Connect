import assert from 'node:assert/strict';
import test from 'node:test';
import {createRequire} from 'node:module';
import {initializeApp} from 'firebase-admin/app';
import {getFirestore} from 'firebase-admin/firestore';
import {getAuth} from 'firebase-admin/auth';
import {getStorage} from 'firebase-admin/storage';
const require=createRequire(import.meta.url);
const {messagingGatewayEurope:eu,messagingGatewayUS:us,gateway}=require('../lib/messaging/regionalGateway.js');
const mumbai=gateway('asia-south1');
const {relayMessage}=require('../lib/messaging/relay.js');
const {compactInbox}=require('../lib/messaging/compactInbox.js');
const {acknowledgeMessage}=require('../lib/messaging/messageAck.js');
const invoke=(fn,uid,operation,payload)=>fn.run({auth:{uid,token:{}},data:{operation,payload}});
const envelope=id=>({v:2,messageId:id,recipientUid:'regionalRecipient',conversationId:'regionalPair',priority:'normal',createdAtMs:Date.now(),ttlMs:86400000,ctype:'signal',ciphertext:'YWJj',trafficPolicyVersion:2});

test('all three gateways require auth and reject unknown operations',async()=>{
 for(const fn of [eu,us,mumbai]){
  await assert.rejects(fn.run({data:{operation:'relayMessage',payload:{}}}),e=>e.code==='unauthenticated');
  await assert.rejects(invoke(fn,'regionalSender','unknownOperation',{}),e=>e.code==='invalid-argument');
  await assert.rejects(invoke(fn,'regionalSender','relayMessage',{requestUid:'changedAccount'}),e=>e.code==='unauthenticated');
 }
});
test('cross-region ambiguous success retries once, shares budgets, survives legacy ACK, and never rebinds IDs',{skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 initializeApp({projectId:'demo-crisis-traffic'});const db=getFirestore();const uid='regionalSender';const data=envelope('regional-concurrent');
 const results=await Promise.all([invoke(eu,uid,'relayMessage',data),invoke(us,uid,'relayMessage',data),invoke(mumbai,uid,'relayMessage',data)]);
 assert.equal(results.filter(r=>r.duplicate).length,2);
 assert.equal((await db.doc('messagingRelayBudgets/'+uid).get()).get('normal').tokens,11);
 const legacyPage=await compactInbox.run({auth:{uid:data.recipientUid,token:{}},data:{installationId:'legacy',saving:false}});
 assert.ok(legacyPage.messages.some(m=>m.messageId===data.messageId));
 await acknowledgeMessage.run({auth:{uid:data.recipientUid,token:{}},data:{messageId:data.messageId}});
 assert.equal((await invoke(mumbai,uid,'relayMessage',data)).duplicate,true);
 assert.equal((await db.doc('messages/'+data.messageId).get()).exists,false);
 await assert.rejects(invoke(eu,'otherSender','relayMessage',data),e=>e.code==='already-exists');
 await assert.rejects(invoke(eu,uid,'relayMessage',{...data,recipientUid:'differentRecipient'}),e=>e.code==='already-exists');
 const old=envelope('regional-old-sender');await relayMessage.run({auth:{uid,token:{}},data:old});
 const newPage=await invoke(eu,old.recipientUid,'compactInbox',{installationId:'modern',profileRevision:1,saving:true,policyVersion:2});
 assert.ok(newPage.messages.some(m=>m.messageId===old.messageId));
 await invoke(us,old.recipientUid,'acknowledgeMessage',{messageId:old.messageId});
 assert.equal((await db.doc('messages/'+old.messageId).get()).exists,false);
 for(const c of ['messagingRegionalReceipts','messages']){
  const rows=await db.collection(c).where('senderUid','==',uid).get();await Promise.all(rows.docs.map(r=>r.ref.delete()));
 }
 for(const id of [uid,data.recipientUid]){await db.doc('messagingRelayBudgets/'+id).delete();await db.recursiveDelete(db.doc('messagingTraffic/'+id));}
});
test('normal-mode call metadata survives, expired replay cannot return after TTL cleanup, and rollback reaches both regions',{skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 const db=getFirestore();const uid='regionalNormal';const data={...envelope('regional-call'),trafficPolicyVersion:1,ttlMs:60000,callRing:true};
 await invoke(eu,uid,'relayMessage',data);assert.equal((await db.doc('messages/'+data.messageId).get()).get('callRing'),true);
 assert.equal((await db.doc('messagingRelayBudgets/'+uid).get()).exists,false);
 await assert.rejects(invoke(eu,uid,'relayMessage',{...data,messageId:'regional-expired',createdAtMs:Date.now()-120000}),e=>e.code==='failed-precondition');
 await db.doc('messagingRuntime/regional').set({europeEnabled:false});
 for(const fn of [eu,us,mumbai])assert.equal((await invoke(fn,uid,'compactInbox',{installationId:'rollback',receive:false})).routing.preferredRegion,'us-central1');
 await db.doc('messagingRuntime/regional').delete();
 for(const c of ['messagingRegionalReceipts','messages']){const rows=await db.collection(c).where('senderUid','==',uid).get();await Promise.all(rows.docs.map(r=>r.ref.delete()));}
 await db.recursiveDelete(db.doc('messagingTraffic/'+uid));
});
test('account deletion removes sender and recipient regional receipts while preserving unrelated accounts',{skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 const db=getFirestore();const uid='regionalEraseUser';const auth=getAuth(),storage=getStorage();
 const deleteUser=auth.deleteUser,bucket=storage.bucket;let deletedUid;
 auth.deleteUser=async id=>{deletedUid=id};storage.bucket=()=>({getFiles:async()=>[[]]});
 try {
  await db.doc('messagingRegionalReceipts/erase-sent').set({senderUid:uid,recipientUid:'regionalOther'});
  await db.doc('messagingRegionalReceipts/erase-received').set({senderUid:'regionalOther',recipientUid:uid});
  await db.doc('messagingRegionalReceipts/erase-unrelated').set({senderUid:'untouchedSender',recipientUid:'untouchedRecipient'});
  const {deleteAccountAndData}=require('../lib/account/deleteAccount.js');
  const summary=await deleteAccountAndData.run({auth:{uid,token:{auth_time:Date.now()/1000}},data:{}});
  assert.equal(deletedUid,uid);assert.equal(summary.authDeleted,true);
  assert.equal(summary.failedSteps.some(s=>s.startsWith('messagingRegionalReceipts')),false);
  assert.equal((await db.doc('messagingRegionalReceipts/erase-sent').get()).exists,false);
  assert.equal((await db.doc('messagingRegionalReceipts/erase-received').get()).exists,false);
  assert.equal((await db.doc('messagingRegionalReceipts/erase-unrelated').get()).exists,true);
 } finally {
  auth.deleteUser=deleteUser;storage.bucket=bucket;
  await db.doc('messagingRegionalReceipts/erase-unrelated').delete();
  await db.doc('accountDeletionTombstones/'+uid).delete();
 }
});
test('regional metadata remains inside the complete 24 KB inbox response budget',{skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 const db=getFirestore(),uid='regionalByteRecipient';const {compactEnvelope}=require('../lib/messaging/compactInbox.js');
 const ids=[0,1,2].map(i=>'regional-byte-'+i);
 let bytes=7600,data;
 for(;bytes<=8000;bytes++) {
  data={v:2,recipientUid:uid,senderUid:'byteSender',conversationId:'bytePair',priority:'normal',ttlMs:86400000,createdAtMs:Date.now(),ctype:'signal',ciphertext:'a'.repeat(bytes)};
  const size=Buffer.byteLength(JSON.stringify({messages:ids.map(id=>compactEnvelope(data,id)),cursor:Buffer.from(ids[2]).toString('base64url')}));
  if(size>=23990 && size<=24000)break;
 }
 assert.ok(bytes<=8000,'near-limit fixture found');
 await Promise.all(ids.map(id=>db.doc('messages/'+id).set(data)));
 const result=await invoke(eu,uid,'compactInbox',{installationId:'byte-budget',saving:false});
 assert.ok(Buffer.byteLength(JSON.stringify(result))<=24000);
 assert.equal(result.messages.length,2);assert.ok(result.cursor);
 await Promise.all(ids.map(id=>db.doc('messages/'+id).delete()));await db.recursiveDelete(db.doc('messagingTraffic/'+uid));
});
