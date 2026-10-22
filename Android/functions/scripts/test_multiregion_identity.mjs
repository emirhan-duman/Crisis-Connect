import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
const require = createRequire(import.meta.url);
const { messagingGatewayEurope: europe, messagingGatewayUS: us } = require('../lib/messaging/regionalGateway.js');
const { publishSignalPreKeys: legacyPublish, fetchSignalPreKeyBundle: legacyFetch } = require('../lib/messaging/signalPreKeys.js');
const request = (uid, data) => ({ auth: { uid, token: { firebase: { sign_in_provider: 'anonymous' } } }, data });
const invoke = (region, uid, operation, payload) => region.run(request(uid, { operation, payload }));
const key = keyId => ({ keyId, publicKey: 'YWJj', signature: 'YWJj' });

test('new regional key operations preserve QR-only restrictions before touching a directory', async () => {
  for (const region of [europe, us]) {
    await assert.rejects(invoke(region, 'anonymous', 'findContactsOnCrisisConnect', { phones: ['+905551234567'] }), e => e.code === 'permission-denied');
    await assert.rejects(invoke(region, 'anonymous', 'lookupIdentityKey', { phone: '+905551234567' }), e => e.code === 'permission-denied');
    await assert.rejects(invoke(region, 'anonymous', 'publishSignalPreKeys', { requestUid: 'another-user' }), e => e.code === 'unauthenticated');
  }
});

test('both regions and old prekey callables share one authoritative pool after database migration', { skip: !process.env.FIRESTORE_EMULATOR_HOST }, async () => {
  initializeApp({ projectId: 'demo-crisis-multiregion-identity' });
  const previous = process.env.MESSAGING_DATABASE_ID;
  process.env.MESSAGING_DATABASE_ID = 'messaging-live-eur3';
  const canonical = getFirestore('messaging-live-eur3');
  const original = getFirestore();
  const target = 'migrationPrekeyRecipient';
  try {
    // Represents the SAME old callable URL deployed with the verified new database target.
    await legacyPublish.run(request(target, { registrationId: 7, identityKey: 'YWJj',
      signedPreKey: key(90), lastResortKyberPreKey: key(99),
      preKeys: [key(1), key(2), key(3)], kyberPreKeys: [key(11), key(12), key(13)] }));
    const results = await Promise.all([
      invoke(europe, 'migration-eu-sender', 'fetchSignalPreKeyBundle', { targetUid: target }),
      invoke(us, 'migration-us-sender', 'fetchSignalPreKeyBundle', { targetUid: target }),
      legacyFetch.run(request('migration-old-sender', { targetUid: target })),
    ]);
    assert.equal(new Set(results.map(r => r.preKey.keyId)).size, 3, 'a one-time EC key cannot cross regions twice');
    assert.equal(new Set(results.map(r => r.kyberPreKey.keyId)).size, 3, 'a one-time Kyber key cannot cross regions twice');
    assert.ok(results.every(r => r.kyberPreKey.lastResort === false));
    const generation = (await canonical.doc(`signalPreKeys/${target}`).get()).get('poolGeneration');
    assert.equal((await canonical.collection(`signalPreKeys/${target}/generations/${generation}/ecPreKeys`).get()).size, 0);
    assert.equal((await canonical.collection(`signalPreKeys/${target}/generations/${generation}/kyberPreKeys`).get()).size, 0);
    assert.equal((await original.doc(`signalPreKeys/${target}`).get()).exists, false, 'no shadow pool in default database');
    const inventory = await invoke(us, target, 'checkSignalPreKeys', {});
    assert.equal(inventory.ecCount, 0);
    await invoke(europe, target, 'publishIdentityKey', { publicKey: 'YWJj', discoverable: false });
    const identity = await invoke(us, 'migration-reader', 'lookupIdentityKey', { uid: target });
    assert.equal(identity.publicKey, 'YWJj');
    assert.equal((await original.doc(`messagingKeys/${target}`).get()).exists, false);
  } finally {
    for (const collection of ['signalPreKeys', 'signalBundleThrottle', 'messagingKeys']) {
      const rows = await canonical.collection(collection).get();
      for (const row of rows.docs) await canonical.recursiveDelete(row.ref);
    }
    if (previous === undefined) delete process.env.MESSAGING_DATABASE_ID;
    else process.env.MESSAGING_DATABASE_ID = previous;
  }
});
