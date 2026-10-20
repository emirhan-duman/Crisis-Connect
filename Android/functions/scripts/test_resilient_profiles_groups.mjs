import assert from 'node:assert/strict';
import test, { after, before } from 'node:test';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { createRequire } from 'node:module';
assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'A dedicated emulator is required; live tests are separate.');
initializeApp({projectId:'demo-crisis-profile-ha'});
const require=createRequire(import.meta.url);
const {mirrorProfile}=require('../lib/messaging/resilientProfiles.js');
const {resolveResilientGroup,groupConversationId,groupBinding,authorizeGroup,mirrorGroupMetadata,metadataEventTimestamp}=require('../lib/messaging/resilientGroups.js');
const {eraseApplicationAccount}=require('../lib/messaging/resilientErasure.js');
const {mediaBinding}=require('../lib/messaging/resilientMedia.js');
const legacy=getFirestore(),ha=getFirestore('messaging-live-eur3');
before(async()=>{for(const db of [legacy,ha])for(const collection of await db.listCollections())for(const row of (await collection.get()).docs)await db.recursiveDelete(row.ref);});
let serial=0;
const next=()=>`profileTest${++serial}`;
async function edit(db,uid,data){const r=db.doc('users/'+uid),before=(await r.get()).data();await r.set(data,{merge:true});const s=await r.get();return {uid,before,after:s.data(),time:s.updateTime,origin:db===legacy?'legacy':'ha'};}
async function mirror(event){return mirrorProfile(event,event.origin==='legacy'?legacy:ha,event.origin==='legacy'?ha:legacy);}
test('legacy profile bootstrap preserves all fields and causes no write-trigger echo',async()=>{
 const uid=next(),e=await edit(legacy,uid,{username:'first',role:'authority',panelId:'testPanel'});assert.equal(await mirror(e),true);
 const s=await ha.doc('users/'+uid).get();assert.equal(s.get('role'),'authority');
 assert.equal(await mirror({uid,after:s.data(),time:s.updateTime,origin:'ha'}),false);
});
test('concurrent disjoint profile edits merge without losing either field',async()=>{
 const uid=next();await mirror(await edit(legacy,uid,{username:'before',country:'TR'}));
 const a=await edit(legacy,uid,{username:'after'}),b=await edit(ha,uid,{country:'DE'});
 await mirror(b);await mirror(a);
 assert.equal((await ha.doc('users/'+uid).get()).get('username'),'after');
 assert.equal((await legacy.doc('users/'+uid).get()).get('country'),'DE');
});
test('reverting a native profile edit to its last mirrored value is still synchronized',async()=>{
 const uid=next();await mirror(await edit(legacy,uid,{username:'original'}));
 await mirror(await edit(ha,uid,{username:'changed'}));
 const revert=await edit(ha,uid,{username:'original'});
 assert.equal(await mirror(revert),true);
 assert.equal((await legacy.doc('users/'+uid).get()).get('username'),'original');
});
test('out-of-order updates cannot restore an older value',async()=>{
 const uid=next(),old=await edit(legacy,uid,{username:'old'}),fresh=await edit(legacy,uid,{username:'new'});
 await mirror(fresh);await mirror(old);assert.equal((await ha.doc('users/'+uid).get()).get('username'),'new');
});
test('a newer unprocessed native SDK write beats a stale mirror',async()=>{
 const uid=next();await mirror(await edit(legacy,uid,{username:'base'}));
 const stale=await edit(legacy,uid,{username:'stale'});await edit(ha,uid,{username:'latest'});
 assert.equal(await mirror(stale),false);assert.equal((await ha.doc('users/'+uid).get()).get('username'),'latest');
});
test('HA data cannot grant administrative authority or verified phone status',async()=>{
 const uid=next();await mirror(await edit(legacy,uid,{role:'user',verified:false,phoneNumber:'a'}));
 await mirror(await edit(ha,uid,{role:'admin',panelId:'intruder',phoneVerifiedE164:'b',username:'allowed'}));
 const r=await legacy.doc('users/'+uid).get();assert.equal(r.get('role'),'user');assert.equal(r.get('panelId'),undefined);
 assert.equal(r.get('phoneVerifiedE164'),undefined);assert.equal(r.get('username'),'allowed');
});
test('legacy authority revocation reaches HA without restoring stale roles',async()=>{
 const uid=next();const old=await edit(legacy,uid,{role:'authority',panelId:'testPanel'});await mirror(old);
 await mirror(await edit(legacy,uid,{role:'user',disabled:true}));await mirror(old);
 const r=await ha.doc('users/'+uid).get();assert.equal(r.get('role'),'user');assert.equal(r.get('disabled'),true);
});
test('deletion tombstone prevents delayed profile resurrection',async()=>{
 const uid=next();const old=await edit(legacy,uid,{username:'erase'});await mirror(old);
 await legacy.doc('users/'+uid).delete();await mirror({uid,before:old.after,time:Timestamp.now(),origin:'legacy'});
 await mirror(old);assert.equal((await ha.doc('users/'+uid).get()).exists,false);
});
test('account deletion blocks newer mirror events as well',async()=>{
 const uid=next();await ha.doc('messagingAccounts/'+uid).set({state:'deleted'});
 assert.equal(await mirror(await edit(legacy,uid,{username:'no'})),false);
});
test('a delayed HA delete event cannot recreate a mirror ledger after account erasure',async()=>{
 const uid=next();await ha.doc('messagingAccounts/'+uid).set({state:'deleted'});
 assert.equal(await mirror({uid,before:{username:'removed'},time:Timestamp.now(),origin:'ha'}),false);
 assert.equal((await legacy.doc('profileMirrorState/'+uid).get()).exists,false);
});
const b={scopeType:'agency',channelId:'testPanel',participants:['groupAlice','groupBob']};
async function seedGroup(){await ha.doc('agencyPanels/testPanel').set({name:'Synthetic panel'});for(const uid of b.participants)await ha.doc('users/'+uid).set({role:'authority',dashboardAccess:true,panelId:'testPanel'});}
test('group IDs are canonical, and the resilient epoch is distinct from every old ratchet',()=>{
 assert.equal(groupConversationId(b,true),groupConversationId({...b,participants:[...b.participants].reverse()},true));
 assert.notEqual(groupConversationId(b,true),groupConversationId(b,false));
 assert.throws(()=>groupBinding({...b,participants:['same','same']}));
});
test('group routing keeps an old participant or device on the released session',async()=>{
 await seedGroup();assert.equal((await resolveResilientGroup('groupAlice',b,'aliceDevice')).resilient,false);
 const old=groupConversationId(b,false);
 await legacy.doc(`authorityMlsV2/${old}/devices/oldBobDevice`).set({uid:'groupBob'});
 assert.equal((await resolveResilientGroup('groupBob',b,'newBobDevice')).resilient,false);
});
test('all upgraded devices allocate one new epoch and reuse it during a legacy outage',async()=>{
 const result=await resolveResilientGroup('groupBob',b,'oldBobDevice');assert.equal(result.resilient,true);
 const duringOutage=await resolveResilientGroup('groupAlice',b,'aliceDevice',()=>{throw new Error('legacy region unavailable');});
 assert.deepEqual(duringOutage,result);
});
test('an unavailable initial legacy directory cannot silently authorize migration',async()=>{
 const binding={...b,channelId:'unpreparedPanel'};
 await ha.doc('agencyPanels/unpreparedPanel').set({childPanelIds:['testPanel']});
 const unavailable=()=>{throw new Error('initial legacy directory unavailable');};
 await assert.rejects(resolveResilientGroup('groupAlice',binding,'aliceDevice',unavailable),/initial legacy directory unavailable/);
 assert.equal((await ha.doc(`authorityHaRoutes/${groupConversationId(binding,false)}`).get()).exists,false);
 assert.equal((await ha.doc(`authorityHaRoutesById/${groupConversationId(binding,true)}`).get()).exists,false);
});
test('explicit independent bootstrap opens a first group with no peer capability or legacy reads',async()=>{
 const binding={...b,channelId:'independentPanel',participants:['isolatedA','isolatedB']};
 await ha.doc('agencyPanels/independentPanel').set({});
 for(const uid of binding.participants)await ha.doc('users/'+uid).set({role:'authority',panelId:'independentPanel'});
 const unavailable=()=>{throw new Error('MUST NOT read default during independent bootstrap');};
 const result=await resolveResilientGroup('isolatedA',binding,'isolatedDeviceA',unavailable,true);
 assert.equal(result.resilient,true);assert.equal(result.legacyDelivery,false);assert.equal(result.compatibility,'separate-session');
 assert.equal((await ha.doc('authorityHaDevices/isolatedB').get()).exists,false);
 const route=await ha.doc(`authorityHaRoutesById/${result.conversationId}`).get();
 assert.equal(route.get('bootstrap'),'independent-v1');
 // Route creation is not message delivery or MLS membership enrollment.
 assert.equal((await ha.doc(`authorityMlsV2/${result.conversationId}`).get()).exists,false);
});
test('independent bootstrap is atomic and idempotent across concurrent regional retries',async()=>{
 const binding={...b,channelId:'independentPanel',participants:['isolatedA','isolatedB']};
 const results=await Promise.all(Array.from({length:8},(_,i)=>resolveResilientGroup(i%2?'isolatedA':'isolatedB',binding,'concurrent'+i,()=>{throw Error('outage');},true)));
 assert.equal(new Set(results.map(r=>r.conversationId)).size,1);
 const id=results[0].conversationId,old=results[0].legacyConversationId;
 assert.equal((await ha.doc(`authorityHaRoutes/${old}`).get()).get('conversationId'),id);
 assert.equal((await ha.doc(`authorityHaRoutesById/${id}`).get()).get('legacyConversationId'),old);
});
test('a legacy device can still register independently without joining or overwriting HA state',async()=>{
 const binding={...b,channelId:'independentPanel',participants:['isolatedA','isolatedB']};
 const old=groupConversationId(binding,false),id=groupConversationId(binding,true);
 await legacy.doc(`authorityMlsV2/${old}/devices/lateLegacy`).set({uid:'isolatedB',credential:'original-credential'});
 const result=await resolveResilientGroup('isolatedA',binding,'isolatedDeviceA',()=>{throw Error('outage');},true);
 assert.equal(result.conversationId,id);
 assert.equal((await legacy.doc(`authorityMlsV2/${old}/devices/lateLegacy`).get()).get('credential'),'original-credential');
 assert.equal((await ha.doc(`authorityMlsV2/${id}/devices/lateLegacy`).get()).exists,false);
});
test('independent bootstrap still rejects unauthorized participants and current revocation',async()=>{
 const binding={...b,channelId:'independentPanel',participants:['isolatedA','isolatedB']};
 await assert.rejects(resolveResilientGroup('intruder',binding,'intruderDevice',undefined,true),e=>e.code==='permission-denied');
 await ha.doc('users/isolatedB').update({disabled:true});
 await assert.rejects(resolveResilientGroup('isolatedA',binding,'isolatedDeviceA',undefined,true),e=>e.code==='permission-denied');
});
test('unrelated, revoked and cross-scope users cannot open a group route',async()=>{
 await assert.rejects(authorizeGroup('mallory',b),e=>e.code==='permission-denied');
 await ha.doc('users/groupBob').update({disabled:true});
 await assert.rejects(authorizeGroup('groupAlice',b),e=>e.code==='permission-denied');
 await ha.doc('users/groupBob').update({disabled:false,panelId:'otherPanel'});
 await assert.rejects(authorizeGroup('groupAlice',b),e=>e.code==='permission-denied');
});
test('out-of-order metadata cannot restore revoked hierarchy membership',async()=>{
 await mirrorGroupMetadata('hierarchyChannels','testHierarchy',{panelIds:['a','b']},new Timestamp(100,1000));
 await mirrorGroupMetadata('hierarchyChannels','testHierarchy',{panelIds:['a']},new Timestamp(100,3000));
 await mirrorGroupMetadata('hierarchyChannels','testHierarchy',{panelIds:['a','b']},new Timestamp(100,2000));
 assert.deepEqual((await ha.doc('hierarchyChannels/testHierarchy').get()).get('panelIds'),['a']);
});
test('metadata deletion event clocks preserve sub-millisecond precision',()=>{
 const time=metadataEventTimestamp('1970-01-01T00:01:41.000000003Z');
 assert.equal(time.seconds,101);assert.equal(time.nanoseconds,3);
 assert.throws(()=>metadataEventTimestamp('invalid'));
});
test('panel and policy access projections commit with their HA metadata and retain other entries',async()=>{
 await mirrorGroupMetadata('agencyPanels','childPanel',{parentPanelId:'parentPanel'},new Timestamp(101,1));
 await mirrorGroupMetadata('messagingPolicies','projectionPanel',{up:false},new Timestamp(101,2));
 let snapshot=await ha.doc('applicationRuntime/hierarchyAccess').get();
 assert.equal(snapshot.get('panels.childPanel.parentPanelId'),'parentPanel');assert.equal(snapshot.get('policies.projectionPanel.up'),false);
 await mirrorGroupMetadata('messagingPolicies','projectionPanel',{up:true},new Timestamp(101,1));
 assert.equal((await ha.doc('applicationRuntime/hierarchyAccess').get()).get('policies.projectionPanel.up'),false);
 await mirrorGroupMetadata('agencyPanels','childPanel',undefined,new Timestamp(101,3));
 snapshot=await ha.doc('applicationRuntime/hierarchyAccess').get();
 assert.equal(snapshot.get('panels.childPanel'),undefined);assert.equal(snapshot.get('policies.projectionPanel.up'),false);
});
test('hierarchy authorization rejects policy revocation even with stale active channel metadata',async()=>{
 await ha.doc('agencyPanels/parent').set({});await ha.doc('agencyPanels/child').set({parentPanelId:'parent'});
 await ha.doc('users/hierA').set({role:'authority',panelId:'parent'});
 await ha.doc('users/hierB').set({role:'authority',panelId:'child'});
 await ha.doc('hierarchyChannels/hierarchy').set({panelIds:['parent','child'],active:true});
 const binding={scopeType:'hierarchy',channelId:'hierarchy',participants:['hierA','hierB']};
 await authorizeGroup('hierA',binding);
 await ha.doc('messagingPolicies/global').set({up:false});
 await assert.rejects(authorizeGroup('hierA',binding),e=>e.code==='permission-denied');
});
test('media paths are restricted to immutable scoped uploads',()=>{
 const id=groupConversationId(b,true),uuid='b8937f60-71bc-49db-a9eb-65d9f88dbedf';
 assert.equal(mediaBinding(`users/groupAlice/avatars/${uuid}.jpg`).maxBytes,2*1024*1024);
 assert.equal(mediaBinding(`authorityMessageAttachments/${id}/groupAlice/${uuid}`).owner,'groupAlice');
 for(const path of ['users/groupAlice/../groupBob/avatar.jpg','users/groupAlice/avatar.jpg',`authorityMessageAttachments/no/groupAlice/${uuid}`])assert.throws(()=>mediaBinding(path));
});
test('application erasure follows the existing tombstone, removes scoped copies and is retryable',async()=>{
 const uid='erasedAccount',cid=groupConversationId({...b,participants:[uid,'groupBob']},true),old=groupConversationId({...b,participants:[uid,'groupBob']},false);
 await ha.doc(`users/${uid}`).set({username:'erase'});
 const prefixes=[];const remove=async prefix=>prefixes.push(prefix);
 await eraseApplicationAccount(uid,ha,remove,legacy);assert.equal((await ha.doc(`users/${uid}`).get()).exists,true);
 await ha.doc(`messagingAccounts/${uid}`).set({state:'deleted'});
 await ha.doc(`authorityHaRoutesById/${cid}`).set({participants:[uid,'groupBob'],legacyConversationId:old});
 await ha.doc(`authorityHaRoutes/${old}`).set({conversationId:cid});
 await ha.doc(`authorityMlsV2/${cid}/devices/device`).set({uid});
 for(const db of [legacy,ha])await db.doc(`profileMirrorState/${uid}/profileMirrorRevisions/revision`).set({value:'remove'});
 await eraseApplicationAccount(uid,ha,remove,legacy);
 assert.deepEqual(prefixes,[`users/${uid}/`,`authorityMessageAttachments/${cid}/`]);
 assert.equal((await ha.doc(`users/${uid}`).get()).exists,false);
 assert.equal((await ha.doc(`authorityHaRoutesById/${cid}`).get()).exists,false);
 assert.equal((await ha.doc(`authorityMlsV2/${cid}/devices/device`).get()).exists,false);
 assert.equal((await legacy.doc(`profileMirrorState/${uid}/profileMirrorRevisions/revision`).get()).exists,false);
 await eraseApplicationAccount(uid,ha,remove,legacy);
 assert.equal((await ha.doc(`messagingAccounts/${uid}`).get()).get('state'),'deleted');
});
after(async()=>{await legacy.terminate();await ha.terminate();});
