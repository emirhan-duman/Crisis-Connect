import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import { createRequire } from 'node:module';
import { initializeApp } from 'firebase-admin/app';
const require = createRequire(import.meta.url);
const { publishHaPrekeys, checkHaPrekeys, fetchHaPrekeys } = require('../lib/messaging/highAvailabilityPrekeys.js');
const { haFirestore } = require('../lib/messaging/highAvailabilityStore.js');
const enabled = !!process.env.FIRESTORE_EMULATOR_HOST;
if (enabled) initializeApp({ projectId: 'demo-crisis-ha-prekeys' });
const key = id => ({ keyId: id, publicKey: 'YWJj', signature: 'YWJj' });
const payload = () => ({ registrationId: 7, identityKey: 'YWJj', signedPreKey: key(90),
  lastResortKyberPreKey: key(99), preKeys: [key(1), key(2), key(3)], kyberPreKeys: [key(11), key(12), key(13)] });

test('malformed and overlarge publications fail before database access', async () => {
  await assert.rejects(publishHaPrekeys('haUser', { ...payload(), registrationId: 0 }), e => e.code === 'invalid-argument');
  await assert.rejects(publishHaPrekeys('haUser', { ...payload(), preKeys: [key(1), key(1)] }), e => e.code === 'invalid-argument');
  await assert.rejects(publishHaPrekeys('haUser', { ...payload(), preKeys: Array.from({ length: 201 }, (_, i) => key(i)) }), e => e.code === 'invalid-argument');
});

test('a lost publication response cannot resurrect consumed keys', { skip: !enabled }, async () => {
  const body = payload(), target = 'haReplay';
  await publishHaPrekeys(target, body);
  const consumed = await fetchHaPrekeys('haSender', target);
  assert.ok(consumed.preKey && !consumed.kyberPreKey.lastResort);
  const replay = await publishHaPrekeys(target, body);
  assert.equal(replay.duplicate, true);
  assert.equal(replay.ecCount, 2);
  // Even changing unrelated metadata cannot put an already used key back.
  await publishHaPrekeys(target, { ...body, signedPreKey: key(91) });
  assert.equal((await checkHaPrekeys(target)).ecCount, 2);
  const next = await fetchHaPrekeys('haSender2', target);
  assert.notEqual(next.preKey.keyId, consumed.preKey.keyId);
  assert.notEqual(next.kyberPreKey.keyId, consumed.kyberPreKey.keyId);
});

test('concurrent regions consume each one-time key at most once', { skip: !enabled }, async () => {
  await publishHaPrekeys('haConcurrent', payload());
  const bundles = await Promise.all(Array.from({ length: 6 }, (_, i) => fetchHaPrekeys('haClient' + i, 'haConcurrent')));
  const ec = bundles.flatMap(b => b.preKey ? [b.preKey.keyId] : []);
  const kyber = bundles.filter(b => !b.kyberPreKey.lastResort).map(b => b.kyberPreKey.keyId);
  assert.equal(ec.length, 3); assert.equal(new Set(ec).size, 3);
  assert.equal(kyber.length, 3); assert.equal(new Set(kyber).size, 3);
  assert.equal((await checkHaPrekeys('haConcurrent')).ecCount, 0);
});

test('same-generation IDs cannot be rebound and rotation never imports old pools', { skip: !enabled }, async () => {
  await publishHaPrekeys('haRotation', payload());
  await assert.rejects(publishHaPrekeys('haRotation', { ...payload(), preKeys: [{ ...key(1), publicKey: 'ZGVm' }] }),
    e => e.code === 'failed-precondition');
  await publishHaPrekeys('haRotation', { ...payload(), identityKey: 'ZGVm', preKeys: [key(8)], kyberPreKeys: [key(18)] });
  const result = await fetchHaPrekeys('haRotationCaller', 'haRotation');
  assert.equal(result.preKey.keyId, 8);
  assert.equal(result.kyberPreKey.keyId, 18);
});

test('daily fetch cap is checked in the same transaction as key consumption', { skip: !enabled }, async () => {
  await publishHaPrekeys('haQuotaTarget', payload());
  await haFirestore().doc('signalBundleThrottle/haQuotaCaller').set({ dayKey: new Date().toISOString().slice(0, 10), count: 200 });
  await assert.rejects(fetchHaPrekeys('haQuotaCaller', 'haQuotaTarget'), e => e.code === 'resource-exhausted');
  assert.equal((await checkHaPrekeys('haQuotaTarget')).ecCount, 3);
  await assert.rejects(fetchHaPrekeys('haQuotaTarget', 'haQuotaTarget'), e => e.code === 'invalid-argument');
});

after(async () => {
  if (!enabled) return;
  const db = haFirestore();
  for (const collection of ['signalPreKeys', 'signalBundleThrottle']) {
    const snapshot = await db.collection(collection).get();
    for (const doc of snapshot.docs) await db.recursiveDelete(doc.ref);
  }
  await db.terminate();
});
