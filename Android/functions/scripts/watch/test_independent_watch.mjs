import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import protocol from '../../lib/watch/deviceProtocol.js';
import registry from '../../lib/watch/deviceRegistry.js';
import journal from '../../lib/watch/commandJournal.js';
const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicDER = publicKey.export({ type:'spki',format:'der' }).toString('base64');
const deviceID = randomUUID(), ownerUid = 'watchTestOwner';
const signature = payload => sign('sha256', Buffer.from(protocol.WATCH_DOMAIN + payload), privateKey).toString('base64');
const command = (action='shortMessage', body={}, id=randomUUID(), now=Date.now()) => {
 const request = { version:1,deviceID,id,issuedAt:now,expiresAt:now+30_000,action,body };
 const encoded = Buffer.from(JSON.stringify(request)).toString('base64');
 return { request,encoded,signature:signature(encoded) };
};
test('P256 proof binds exact bytes, owner and device', () => {
 assert.equal(protocol.watchPublicKey(publicDER), publicDER);
 const payload = protocol.enrollmentPayload(ownerUid,deviceID,publicDER);
 protocol.verifyWatchSignature(publicDER,payload,signature(payload));
 assert.throws(() => protocol.verifyWatchSignature(publicDER,protocol.enrollmentPayload('other',deviceID,publicDER),signature(payload)));
 const otherKey = generateKeyPairSync('ec',{ namedCurve:'secp384r1' }).publicKey.export({type:'spki',format:'der'}).toString('base64');
 assert.throws(() => protocol.watchPublicKey(otherKey));
 assert.throws(() => protocol.base64('YWJj=',20));
});
test('expired/future/malformed commands cannot enter the journal', () => {
 const c=command(); assert.equal(protocol.parseWatchRequest(c.encoded,Date.now()).request.id,c.request.id);
 for (const patch of [{issuedAt:Date.now()+20_000},{expiresAt:1},{expiresAt:Date.now()+90_000},{version:2},{deviceID:'../foreign'},{body:[]},{action:'publishKey'}]) {
  const encoded=Buffer.from(JSON.stringify({...c.request,...patch})).toString('base64');
  assert.throws(()=>protocol.parseWatchRequest(encoded,Date.now()));
 }
});
test('revoked, expired and scope-restricted watches fail closed', () => {
 const c=command(); const registration={ownerUid,deviceID,publicKey:publicDER,scopes:['shortMessage','sos'],createdAt:Date.now(),expiresAt:Date.now()+60_000,revokedAt:null};
 protocol.authorizeWatch(registration,c.request,Date.now());
 for(const patch of [{revokedAt:Date.now()},{expiresAt:1},{scopes:['sos']},{deviceID:randomUUID()}])
  assert.throws(()=>protocol.authorizeWatch({...registration,...patch},c.request,Date.now()));
});
test('Firestore: provisioning, key isolation, concurrent dedup, rollback, revocation', {skip:!process.env.FIRESTORE_EMULATOR_HOST}, async () => {
 assert.ok(process.env.FIRESTORE_EMULATOR_HOST.startsWith('127.0.0.1:'),'Local emulator only');
 initializeApp({projectId:'demo-crisis-independent-watch'});
 const db=getFirestore();
 const keyRef=db.doc(`messagingKeys/${ownerUid}`),signalRef=db.doc(`signalPreKeys/${ownerUid}`);
 await keyRef.set({publicKey:'phone-key-unchanged'});await signalRef.set({identityKey:'phone-signal-unchanged',deviceId:1});
 const proof=protocol.enrollmentPayload(ownerUid,deviceID,publicDER);
 const enrollment={deviceID,publicKey:publicDER,signature:signature(proof),confirmed:true};
 const auth=uid=>({uid,token:{}});
 await assert.rejects(registry.enrollIndependentWatch.run({auth:auth(ownerUid),data:enrollment}),e=>e.code==='failed-precondition');
 process.env.WATCH_INDEPENDENT_PROVISIONING='true';
 await assert.rejects(registry.enrollIndependentWatch.run({data:enrollment}),e=>e.code==='unauthenticated');
 await assert.rejects(registry.enrollIndependentWatch.run({auth:auth(ownerUid),data:{...enrollment,confirmed:false}}));
 await registry.enrollIndependentWatch.run({auth:auth(ownerUid),data:enrollment});
 assert.equal((await keyRef.get()).get('publicKey'),'phone-key-unchanged');
 assert.equal((await signalRef.get()).get('identityKey'),'phone-signal-unchanged');
 const effects=db.collection('watchTestEffects');
 const prepare=async(tx,registration,request)=>({outcome:'accepted',write(t){t.create(effects.doc(request.id),{action:request.action,subject:request.body.sessionID??'contact',ownerUid:registration.ownerUid});}});
 const c=command();
 const outcomes=await Promise.all(Array.from({length:8},()=>journal.commitWatchCommand(db,c.encoded,c.signature,prepare)));
 assert.ok(outcomes.every(row=>row.id===c.request.id));assert.equal((await effects.get()).size,1);
 const edited=command('shortMessage',{changed:true},c.request.id);
 await assert.rejects(journal.commitWatchCommand(db,edited.encoded,edited.signature,prepare),e=>e.code==='already-exists');
 const start=command('startSOS',{sessionID:'saved-session'}),stop=command('stopSOS',{sessionID:'saved-session'});
 for(const op of [start,stop,stop,start])await journal.commitWatchCommand(db,op.encoded,op.signature,prepare);
 assert.equal((await effects.get()).size,3,'replay cannot create a new effect');
 const failed=command();
 await assert.rejects(journal.commitWatchCommand(db,failed.encoded,failed.signature,async()=>{throw Error('adapter failure');}));
 assert.equal((await db.doc(`watchInstallations/${deviceID}/commands/${failed.request.id}`).get()).exists,false);
 await assert.rejects(registry.revokeIndependentWatch.run({auth:auth('foreign'),data:{deviceID}}),e=>e.code==='permission-denied');
 await registry.revokeIndependentWatch.run({auth:auth(ownerUid),data:{deviceID}});
 await assert.rejects(journal.commitWatchCommand(db,c.encoded,c.signature,prepare),e=>e.code==='permission-denied');
 await assert.rejects(registry.enrollIndependentWatch.run({auth:auth(ownerUid),data:enrollment}),e=>e.code==='permission-denied');
 await db.recursiveDelete(db.doc(`watchInstallations/${deviceID}`));await db.doc(`watchAccounts/${ownerUid}`).delete();
 await db.recursiveDelete(effects);await keyRef.delete();await signalRef.delete();delete process.env.WATCH_INDEPENDENT_PROVISIONING;
});
