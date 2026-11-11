import assert from 'node:assert/strict';
import test, { before, after } from 'node:test';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { setHaPushToken, mirrorHaPushToken } = require('../lib/messaging/highAvailabilityTokens.js');
const { haFirestore, deleteHighAvailabilityAccount } = require('../lib/messaging/highAvailabilityStore.js');
const enabled = !!process.env.FIRESTORE_EMULATOR_HOST;
before(() => { if (enabled) initializeApp({ projectId: 'demo-crisis-ha-tokens' }); });
const uid = 'tokenMirrorUser', token = 'isolated-push-token';
const job = async () => (await haFirestore().collection('messagingTokenBridge').where('uid', '==', uid).get()).docs[0];
test('normal registration and deletion preserve the old group/call token registry without replay resurrection', { skip: !enabled }, async () => {
  await setHaPushToken(uid, token, { token, platform: 'ios' });
  const registered = await job();
  await mirrorHaPushToken(registered.id);
  assert.equal((await getFirestore().collection(`messagingTokens/${uid}/tokens`).get()).size, 1);
  const completed = await registered.ref.get();
  await mirrorHaPushToken(registered.id);
  assert.equal((await registered.ref.get()).updateTime.isEqual(completed.updateTime), true, 'completion does not retrigger itself');
  await setHaPushToken(uid, token, null);
  await mirrorHaPushToken(registered.id);
  assert.equal((await getFirestore().collection(`messagingTokens/${uid}/tokens`).get()).size, 0);
  await mirrorHaPushToken(registered.id, registered.data());
  assert.equal((await getFirestore().collection(`messagingTokens/${uid}/tokens`).get()).size, 0);
});
test('a legacy outage leaves durable pending work but does not remove canonical registration', { skip: !enabled }, async () => {
  await setHaPushToken(uid, token, { token, platform: 'android' });
  const pending = await job(), legacy = getFirestore(), original = legacy.runTransaction;
  legacy.runTransaction = async () => { throw new Error('injected legacy outage'); };
  try { await assert.rejects(mirrorHaPushToken(pending.id), /injected legacy/); }
  finally { legacy.runTransaction = original; }
  assert.equal((await pending.ref.get()).get('pending'), true);
  assert.equal((await haFirestore().collection(`messagingTokens/${uid}/tokens`).get()).size, 1);
  await mirrorHaPushToken(pending.id);
  assert.equal((await pending.ref.get()).get('pending'), false);
});
test('erasure events and late retries cannot restore an erased account’s token', { skip: !enabled }, async () => {
  const previous = await job();
  await deleteHighAvailabilityAccount(uid);
  await mirrorHaPushToken(previous.id, previous.data());
  assert.equal((await getFirestore().collection(`messagingTokens/${uid}/tokens`).get()).size, 0);
  await mirrorHaPushToken(previous.id, previous.data());
  assert.equal((await getFirestore().collection(`messagingTokens/${uid}/tokens`).get()).size, 0);
});
after(async () => {
  if (!enabled) return;
  for (const db of [getFirestore(), haFirestore()]) {
    await db.recursiveDelete(db.doc('messagingTokens/' + uid));
    await db.recursiveDelete(db.doc('messagingAccounts/' + uid));
    for (const name of ['messagingTokenBridge', 'messagingTokenMirror']) {
      for (const doc of (await db.collection(name).get()).docs) await db.recursiveDelete(doc.ref);
    }
    await db.terminate();
  }
});
