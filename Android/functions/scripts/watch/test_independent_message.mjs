import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,randomUUID,sign} from 'node:crypto';
import {initializeApp} from 'firebase-admin/app';
import {getFirestore} from 'firebase-admin/firestore';
import protocol from '../../lib/watch/deviceProtocol.js';
import registry from '../../lib/watch/deviceRegistry.js';
import gateway from '../../lib/watch/gateway.js';
import contacts from '../../lib/watch/phoneContacts.js';
test('device-addressed ciphertext commits once, delivery survives duplicate ack, unapproved peer rejected',async()=>{
 assert.match(process.env.FIRESTORE_EMULATOR_HOST??'',/^127\.0\.0\.1:/);initializeApp({projectId:'demo-crisis-independent-watch'});
 process.env.WATCH_INDEPENDENT_PROVISIONING='true';const db=getFirestore();
 async function device(uid){
  const id=randomUUID(),{privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),pub=publicKey.export({format:'der',type:'spki'}).toString('base64');
  const proof=p=>sign('sha256',Buffer.from(protocol.WATCH_DOMAIN+p),privateKey).toString('base64');
  await registry.enrollIndependentWatch.run({auth:{uid,token:{}},data:{deviceID:id,publicKey:pub,confirmed:true,signature:proof(protocol.enrollmentPayload(uid,id,pub))}});
  await db.doc(`watchInstallations/${id}/signal/binding`).set({deviceId:2,identityKey:uid});
  return{id,uid,make(action,body,commandID=randomUUID()){const now=Date.now();const payload=Buffer.from(JSON.stringify({version:1,deviceID:id,id:commandID,issuedAt:now,expiresAt:now+30000,action,body})).toString('base64');return{payload,signature:proof(payload)}}};
 }
 const a=await device('watchMessageAlice'),b=await device('watchMessageBob'),send=x=>gateway.executeWatchRequest(x,async()=>true);
 const messageID=randomUUID(),body={messageID,recipientInstallation:b.id,recipientUid:b.uid,recipientDevice:2,ctype:'prekey',ciphertext:Buffer.from('opaque-fixture-not-crypto-test').toString('base64')};
 await assert.rejects(send(a.make('shortMessage',body,messageID)),e=>e.code==='permission-denied');
 await db.doc(`watchInstallations/${a.id}/contacts/${b.id}`).set({enabled:true,identityKey:b.uid});await db.doc(`watchInstallations/${b.id}/contacts/${a.id}`).set({enabled:true,identityKey:a.uid});
 const request=a.make('shortMessage',body,messageID);await Promise.all([send(request),send(request),send(request)]);
 assert.equal((await db.collection(`watchInstallations/${b.id}/inbox`).get()).size,1);
 const inbox=await send(b.make('status',{}));assert.equal(inbox.inbox.length,1);assert.equal(inbox.inbox[0].ciphertext,body.ciphertext);
 await assert.rejects(send(a.make('ackMessage',{messageID})),e=>e.code==='permission-denied');
 const ack=b.make('ackMessage',{messageID});await Promise.all([send(ack),send(ack)]);
 assert.equal((await send(a.make('status',{commandIDs:[messageID]}))).receipts[0].outcome,'delivered');
 assert.equal((await send(b.make('status',{}))).inbox.length,0);
 // An unacknowledged first page must not hide later messages indefinitely.
 const pending=[];
 for(let i=0;i<5;i++){const id=randomUUID();pending.push(id);await send(a.make('shortMessage',{...body,messageID:id},id));}
 const page1=await send(b.make('status',{})),page2=await send(b.make('status',{inboxAfter:page1.inbox.at(-1).id})),page3=await send(b.make('status',{inboxAfter:page2.inbox.at(-1).id}));
 assert.deepEqual([...page1.inbox,...page2.inbox,...page3.inbox].map(m=>m.id),pending.sort());
 assert.equal(page1.inbox.length,2);assert.equal(page2.inbox.length,2);assert.equal(page3.inbox.length,1);
 const ownerReceipts=await contacts.readIndependentWatchReceipts.run({auth:{uid:a.uid,token:{}},data:{deviceID:a.id,ids:[messageID]}});
 assert.equal(ownerReceipts.receipts[0].outcome,'delivered');
 await assert.rejects(contacts.readIndependentWatchReceipts.run({auth:{uid:b.uid,token:{}},data:{deviceID:a.id,ids:[messageID]}}));
 await contacts.revokeIndependentWatchContact.run({auth:{uid:a.uid,token:{}},data:{deviceID:a.id,peerInstallation:b.id}});
 const nextID=randomUUID();await assert.rejects(send(a.make('shortMessage',{...body,messageID:nextID},nextID)),e=>e.code==='permission-denied');

 await send(request);assert.equal((await send(a.make('status',{commandIDs:[messageID]}))).receipts[0].outcome,'delivered');
 await db.recursiveDelete(db.doc(`watchInstallations/${a.id}`));await db.recursiveDelete(db.doc(`watchInstallations/${b.id}`));
 await db.doc(`watchAccounts/${a.uid}`).delete();await db.doc(`watchAccounts/${b.uid}`).delete();
});
