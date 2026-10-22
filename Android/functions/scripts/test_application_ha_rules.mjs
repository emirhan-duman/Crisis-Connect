import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import test,{before,after} from 'node:test';
const require=createRequire(process.env.RULES_TEST_PACKAGE_JSON??new URL('../package.json',import.meta.url));
const {initializeTestEnvironment,assertSucceeds,assertFails}=require('@firebase/rules-unit-testing');
const {doc,setDoc,getDoc,getDocs,collection,query,where,updateDoc,runTransaction,serverTimestamp}=require('firebase/firestore');
let env;
const id='am2_'+'a'.repeat(43),device='testDevice',credential='cc-mls:v1:alice:testDevice';
const binding={version:2,scopeType:'agency',channelId:'panel',participants:['alice','bob'],createdBy:'alice',creatorCredential:credential,
 nextControlSequence:0,lastControlId:'',nextApplicationSequence:0,lastMessageId:''};
before(async()=>{
 env=await initializeTestEnvironment({projectId:'demo-crisis-app-ha-rules',firestore:{host:'127.0.0.1',port:8787,rules:await readFile(new URL('../../firestore.application-ha.rules',import.meta.url),'utf8')}});
 await env.clearFirestore();
 await env.withSecurityRulesDisabled(async c=>{
  const db=c.firestore();
  for(const uid of ['alice','bob'])await setDoc(doc(db,'users/'+uid),{role:'authority',panelId:'panel',dashboardAccess:true,verified:true});
  await setDoc(doc(db,'users/outsider'),{role:'authority',panelId:'elsewhere',dashboardAccess:true});
  await setDoc(doc(db,'agencyPanels/panel'),{name:'Synthetic panel'});
  await setDoc(doc(db,'applicationRuntime/hierarchyAccess'),{panels:{},policies:{}});
  await setDoc(doc(db,'authorityHaRoutesById/'+id),{...binding,conversationId:id,generation:11});
  await setDoc(doc(db,'messages/private'),{recipientUid:'alice',senderUid:'bob',ciphertext:'opaque'});
 });
});
test('profile owner can read and edit personal fields; neither a peer nor an anonymous caller can read it',async()=>{
 const a=env.authenticatedContext('alice').firestore();
 await assertSucceeds(getDoc(doc(a,'users/alice')));await assertSucceeds(updateDoc(doc(a,'users/alice'),{username:'new name'}));
 await assertFails(getDoc(doc(env.authenticatedContext('bob').firestore(),'users/alice')));
 await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'users/alice')));
});
test('profile writes cannot forge roles, membership, phone verification or undelete a disabled account',async()=>{
 const db=env.authenticatedContext('alice').firestore();
 for(const delta of [{role:'admin'},{panelId:'elsewhere'},{platformAdmin:true},{disabled:false},{phoneVerifiedE164:'+905555555555'}])
  await assertFails(updateDoc(doc(db,'users/alice'),delta));
 await assertFails(setDoc(doc(env.authenticatedContext('newUser').firestore(),'users/newUser'),{role:'admin'}));
 await assertSucceeds(setDoc(doc(env.authenticatedContext('newUser').firestore(),'users/newUser'),{role:'user',username:'ordinary'}));
});
test('new group creation requires its server-issued immutable route',async()=>{
 const db=env.authenticatedContext('alice').firestore();
 await assertFails(setDoc(doc(db,'authorityMlsV2/am2_'+'b'.repeat(43)),{...binding,createdAt:serverTimestamp()}));
 await assertSucceeds(setDoc(doc(db,'authorityMlsV2/'+id),{...binding,createdAt:serverTimestamp()}));
 await assertFails(getDoc(doc(env.authenticatedContext('outsider').firestore(),'authorityMlsV2/'+id)));
});
test('native SDK group device and atomic ciphertext sequence obey existing MLS rules',async()=>{
 const db=env.authenticatedContext('alice').firestore();
 await assertSucceeds(setDoc(doc(db,`authorityMlsV2/${id}/devices/${device}`),{uid:'alice',deviceId:device,credential,signingPublicKey:'a'.repeat(43),label:'Synthetic',createdAt:serverTimestamp()}));
 const messageId='m_'+'a'.repeat(22),parent=doc(db,'authorityMlsV2/'+id),message=doc(db,`authorityMlsV2/${id}/messages/${messageId}`);
 await assertSucceeds(runTransaction(db,async tx=>{const s=await tx.get(parent);tx.set(message,{senderUid:'alice',senderDeviceId:device,senderCredential:credential,messageId,contentVersion:2,ciphertext:'YWJj',sequence:s.data().nextApplicationSequence,createdAt:serverTimestamp()});tx.update(parent,{nextApplicationSequence:1,lastMessageId:messageId});}));
 await assertSucceeds(getDocs(query(collection(env.authenticatedContext('bob').firestore(),`authorityMlsV2/${id}/messages`))));
 await assertFails(updateDoc(message,{ciphertext:'ZXZpbA'}));
});
test('receipt and typing metadata remain in the HA store with ownership and monotonic cursors',async()=>{
 const a=env.authenticatedContext('alice').firestore(),b=env.authenticatedContext('bob').firestore();
 const receipt=doc(a,'agencyPanels/panel/readReceipts/alice__bob');
 await assertSucceeds(setDoc(receipt,{viewerUid:'alice',partnerUid:'bob',deliveredAt:10,at:10,updatedAt:serverTimestamp()}));
 await assertSucceeds(getDoc(doc(b,'agencyPanels/panel/readReceipts/alice__bob')));
 await assertFails(updateDoc(receipt,{deliveredAt:9,updatedAt:serverTimestamp()}));
 await assertSucceeds(setDoc(doc(a,'agencyPanels/panel/typing/alice'),{uid:'alice',to:'bob',typing:true,at:serverTimestamp()}));
 await assertSucceeds(getDoc(doc(b,'agencyPanels/panel/typing/alice')));
 await assertFails(setDoc(doc(b,'agencyPanels/panel/typing/alice'),{uid:'alice',to:'bob',typing:true,at:serverTimestamp()}));
});
test('hierarchy ciphertext respects current policy even before its channel projection catches up',async()=>{
 const hid='am2_'+'h'.repeat(43),channel='hc_test';
 const parentData={...binding,scopeType:'hierarchy',channelId:channel,participants:['hierAlice','hierBob'],createdBy:'hierAlice'};
 await env.withSecurityRulesDisabled(async c=>{
  const db=c.firestore();
  await setDoc(doc(db,'agencyPanels/hParent'),{});
  await setDoc(doc(db,'agencyPanels/hChild'),{parentPanelId:'hParent'});
  await setDoc(doc(db,'applicationRuntime/hierarchyAccess'),{panels:{hChild:{parentPanelId:'hParent'},hParent:{}},policies:{}});
  await setDoc(doc(db,'users/hierAlice'),{role:'authority',panelId:'hChild',dashboardAccess:true});
  await setDoc(doc(db,'users/hierBob'),{role:'authority',panelId:'hParent',dashboardAccess:true});
  await setDoc(doc(db,'hierarchyChannels/'+channel),{panelIds:['hChild','hParent'],active:true});
  await setDoc(doc(db,'authorityHaRoutesById/'+hid),{...parentData,conversationId:hid,generation:11});
 });
 const db=env.authenticatedContext('hierAlice').firestore();
 await assertSucceeds(setDoc(doc(db,'authorityMlsV2/'+hid),{...parentData,createdAt:serverTimestamp()}));
 const cred='cc-mls:v1:aGllckFsaWNl:aGRldmljZQ';
 await assertSucceeds(setDoc(doc(db,`authorityMlsV2/${hid}/devices/hdevice`),{uid:'hierAlice',deviceId:'hdevice',credential:cred,signingPublicKey:'a'.repeat(43),label:'Test',createdAt:serverTimestamp()}));
 const mid='m_'+'b'.repeat(22),parent=doc(db,'authorityMlsV2/'+hid);
 await assertSucceeds(runTransaction(db,async tx=>{await tx.get(parent);tx.set(doc(db,`authorityMlsV2/${hid}/messages/${mid}`),{senderUid:'hierAlice',senderDeviceId:'hdevice',senderCredential:cred,messageId:mid,contentVersion:2,ciphertext:'YWJj',sequence:0,createdAt:serverTimestamp()});tx.update(parent,{nextApplicationSequence:1,lastMessageId:mid});}));
 await env.withSecurityRulesDisabled(async c=>{
  await updateDoc(doc(c.firestore(),'applicationRuntime/hierarchyAccess'),{
   'panels.hGrandchild':{parentPanelId:'hChild'},'panels.hOtherGrandchild':{parentPanelId:'hParent'}});
  await updateDoc(doc(c.firestore(),'users/hierAlice'),{panelId:'hGrandchild'});
  await updateDoc(doc(c.firestore(),'users/hierBob'),{panelId:'hOtherGrandchild'});
 });
 const second='m_'+'c'.repeat(22);
 await assertSucceeds(runTransaction(db,async tx=>{await tx.get(parent);tx.set(doc(db,`authorityMlsV2/${hid}/messages/${second}`),{senderUid:'hierAlice',senderDeviceId:'hdevice',senderCredential:cred,messageId:second,contentVersion:2,ciphertext:'YWJj',sequence:1,createdAt:serverTimestamp()});tx.update(parent,{nextApplicationSequence:2,lastMessageId:second});}));
 await env.withSecurityRulesDisabled(async c=>updateDoc(doc(c.firestore(),'applicationRuntime/hierarchyAccess'),{'policies.global':{up:false}}));
 await assertFails(getDoc(parent));
 await env.withSecurityRulesDisabled(async c=>{
  await updateDoc(doc(c.firestore(),'applicationRuntime/hierarchyAccess'),{'policies.global':{up:true}});
  await updateDoc(doc(c.firestore(),'hierarchyChannels/'+channel),{disabled:true});
 });
 await assertFails(getDoc(parent));
});
test('revoked authority cannot append group messages',async()=>{
 await env.withSecurityRulesDisabled(async c=>updateDoc(doc(c.firestore(),'users/alice'),{role:'user',dashboardAccess:false,verified:false}));
 await assertFails(getDoc(doc(env.authenticatedContext('alice').firestore(),'authorityMlsV2/'+id)));
});
test('private inbox remains recipient-only and server-write-only',async()=>{
 const db=env.authenticatedContext('alice').firestore();
 await assertSucceeds(getDocs(query(collection(db,'messages'),where('recipientUid','==','alice'))));
 await assertFails(updateDoc(doc(db,'messages/private'),{ciphertext:'forged'}));
 await assertFails(getDoc(doc(env.authenticatedContext('bob').firestore(),'messages/private')));
});
test('mirror ledgers, capabilities and routes remain server-owned',async()=>{
 const db=env.authenticatedContext('alice').firestore();
 for(const path of ['profileMirrorState/alice','authorityHaDevices/alice','authorityHaRoutes/fake','agencyPanels/panel','hierarchyChannels/fake','applicationRuntime/hierarchyAccess'])
  await assertFails(setDoc(doc(db,path),{forged:true}));
});
test('an unexpired token cannot read, mutate or recreate a profile after account erasure',async()=>{
 await env.withSecurityRulesDisabled(async c=>setDoc(doc(c.firestore(),'messagingAccounts/newUser'),{state:'deleted'}));
 const db=env.authenticatedContext('newUser').firestore();
 await assertFails(getDoc(doc(db,'users/newUser')));
 await assertFails(updateDoc(doc(db,'users/newUser'),{username:'restore'}));
 await assertFails(setDoc(doc(db,'users/newUser'),{role:'user',username:'restore'}));
});
after(async()=>env?.cleanup());
