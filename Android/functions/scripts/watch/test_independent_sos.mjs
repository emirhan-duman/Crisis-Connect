import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,randomUUID,sign} from 'node:crypto';
import {initializeApp} from 'firebase-admin/app';
import {getFirestore} from 'firebase-admin/firestore';
import protocol from '../../lib/watch/deviceProtocol.js';
import registry from '../../lib/watch/deviceRegistry.js';
import gateway from '../../lib/watch/gateway.js';
import report from '../../lib/sos/reportSignal.js';

test('real SOS projection: signed watch start/stop, lost receipt recovery and no resurrection', {skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 assert.match(process.env.FIRESTORE_EMULATOR_HOST,/^127\.0\.0\.1:/);
 initializeApp({projectId:'demo-crisis-independent-watch'});const db=getFirestore();
 const ownerUid='watchSOSOwner',deviceID=randomUUID(),sessionID=randomUUID();
 const {publicKey,privateKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const pub=publicKey.export({format:'der',type:'spki'}).toString('base64');
 const signature=payload=>sign('sha256',Buffer.from(protocol.WATCH_DOMAIN+payload),privateKey).toString('base64');
 process.env.WATCH_INDEPENDENT_PROVISIONING='true';
 await registry.enrollIndependentWatch.run({auth:{uid:ownerUid,token:{}},data:{deviceID,publicKey:pub,confirmed:true,signature:signature(protocol.enrollmentPayload(ownerUid,deviceID,pub))}});
 const make=(action,body,id=randomUUID())=>{const now=Date.now();const payload=Buffer.from(JSON.stringify({version:1,deviceID,id,issuedAt:now,expiresAt:now+30_000,action,body})).toString('base64');return{payload,signature:signature(payload)};};
 const send=raw=>gateway.executeWatchRequest(raw,async uid=>uid===ownerUid);
 const location={sessionID,lat:41.01,lon:28.97,accuracyMeters:15,capturedAtMillis:Date.now(),countryIso:'TR'};
 const start=make('startSOS',location);
 const receipts=await Promise.all([send(start),send(start),send(start)]);
 assert.equal(new Set(receipts.map(r=>r.id)).size,1);
 const started=receipts[0],signalID=started.details.signalId;
 const panel=db.doc(`agencyPanels/afad/signals/${signalID}`);
 assert.equal((await panel.get()).get('status'),'active');assert.equal((await panel.get()).get('victimUid'),ownerUid);
 assert.equal((await panel.get()).get('gps.provider'),'watch');
 // The client loses the initial HTTP receipt, then reconciles by ID without submitting start again.
 const recovered=await send(make('status',{commandIDs:[started.id]}));
 assert.equal(recovered.sos.sessionID,sessionID);assert.equal(recovered.receipts[0].id,started.id);
 await assert.rejects(send(make('startSOS',{...location,sessionID:randomUUID()})),e=>e.code==='failed-precondition');
 const stop=await send(make('stopSOS',{sessionID}));assert.equal(stop.details.status,'resolved');
 assert.equal((await panel.get()).get('status'),'resolved');
 await send(start);assert.equal((await panel.get()).get('status'),'resolved','replay returns original receipt without restarting SOS');
 await assert.rejects(send(make('startSOS',location)),e=>e.code==='failed-precondition');
 await assert.rejects(send(make('startSOS',{...location,sessionID:randomUUID(),capturedAtMillis:Date.now()-180_000})),e=>e.code==='invalid-argument');
 const reopened=await send(make('status',{commandIDs:[started.id,stop.id]}));
 assert.equal(reopened.sos.status,'resolved');assert.equal(reopened.receipts.length,2);
 assert.equal((await db.collection(`watchInstallations/${deviceID}/commands`).get()).size,2);
 await assert.rejects(gateway.executeWatchRequest(make('status',{}),async()=>false),e=>e.code==='permission-denied');
 // Existing phone callable still uses the same country routing/projection after extraction.
 const phoneSignal='cc-00112233445566778899aabb';
 await report.reportSosSignal.run({auth:{uid:'phoneSOSFixture',token:{}},data:{...location,signalId:phoneSignal}});
 assert.equal((await db.doc(`agencyPanels/afad/signals/${phoneSignal}`).get()).get('status'),'active');
 await report.reportSosSignal.run({auth:{uid:'phoneSOSFixture',token:{}},data:{signalId:phoneSignal,status:'resolved'}});
 assert.equal((await db.doc(`agencyPanels/afad/signals/${phoneSignal}`).get()).get('status'),'resolved');
 for(const id of [signalID,phoneSignal]){await db.doc(`agencyPanels/afad/signals/${id}`).delete();await db.recursiveDelete(db.doc(`sosSignalRouting/${id}`));}
 await db.recursiveDelete(db.doc(`watchInstallations/${deviceID}`));await db.recursiveDelete(db.doc(`watchSOS/${ownerUid}`));await db.doc(`watchAccounts/${ownerUid}`).delete();
 await db.doc('sosReportThrottle/phoneSOSFixture').delete();delete process.env.WATCH_INDEPENDENT_PROVISIONING;
});
