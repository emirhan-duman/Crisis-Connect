import assert from 'node:assert/strict';
import test, { before, after } from 'node:test';
import { createRequire } from 'node:module';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
const require = createRequire(import.meta.url);
const { messagingGatewayHaEurope: eu, messagingGatewayHaUS: us } = require('../lib/messaging/highAvailabilityGateway.js');
const { relayMessage } = require('../lib/messaging/relay.js');
const { compactInbox } = require('../lib/messaging/compactInbox.js');
const { acknowledgeMessage } = require('../lib/messaging/messageAck.js');
const { publishIdentityKey, lookupIdentityKey } = require('../lib/messaging/identityKeys.js');
const { hashIdentifier } = require('../lib/messaging/identityKeys.js');
const { withMessagingDatabase } = require('../lib/messaging/databaseTarget.js');
const { publishSignalPreKeys, fetchSignalPreKeyBundle } = require('../lib/messaging/signalPreKeys.js');
const { onMessageCreated } = require('../lib/messaging/onMessageCreated.js');
const { deliverHaMessage, onLegacyMessageDeletedForHa, onHaMessageDeletedEurope, backfillLegacyInbox } = require('../lib/messaging/highAvailabilityDelivery.js');
const { haFirestore, deleteHighAvailabilityAccount } = require('../lib/messaging/highAvailabilityStore.js');
const enabled = !!process.env.FIRESTORE_EMULATOR_HOST;
const saved = { compatibility: process.env.HA_COMPATIBILITY_ENABLED, enrollment: process.env.HA_ENROLLMENT_ENABLED };
const old = 'interopLegacy', modern = 'interopModern', newer = 'interopNewer';
const req = (uid, data) => ({ auth: { uid, token: { firebase: { sign_in_provider: 'anonymous' } } }, data });
const ha = (gateway, uid, operation, payload) => gateway.run(req(uid, { operation, payload }));
const key = n => ({ keyId: n, publicKey: 'YWJj', signature: 'YWJj' });
const keys = n => ({ registrationId: 7, identityKey: 'YWJj', signedPreKey: key(90), lastResortKyberPreKey: key(99),
  preKeys: [key(n)], kyberPreKeys: [key(n + 10)] });
const wire = (id, recipientUid) => ({ v: 2, messageId: id, recipientUid, conversationId: 'interopConversation',
  createdAtMs: Date.now(), ttlMs: 60_000, ctype: 'signal', ciphertext: 'YWJj', priority: 'normal' });

before(async () => {
  if (!enabled) return;
  initializeApp({ projectId: 'demo-crisis-ha-interop' });
  process.env.HA_COMPATIBILITY_ENABLED = 'true';
  process.env.HA_ENROLLMENT_ENABLED = 'true';
  await publishIdentityKey.run(req(old, { publicKey: 'YWJj', discoverable: false }));
  await publishSignalPreKeys.run(req(old, keys(3)));
  for (const uid of [modern, newer]) {
    await ha(eu, uid, 'publishIdentityKey', { publicKey: 'YWJj', discoverable: false });
    await ha(us, uid, 'publishSignalPreKeys', keys(1));
  }
});

test('released identity/prekey callables resolve new accounts without copying old key pools', { skip: !enabled }, async () => {
  assert.equal((await lookupIdentityKey.run(req(old, { uid: modern }))).uid, modern);
  assert.equal((await fetchSignalPreKeyBundle.run(req(old, { targetUid: modern }))).preKey.keyId, 1);
  assert.equal((await ha(us, modern, 'lookupIdentityKey', { uid: old })).uid, old);
  assert.equal((await ha(eu, modern, 'fetchSignalPreKeyBundle', { targetUid: old })).preKey.keyId, 3);
  assert.equal((await getFirestore().doc('signalPreKeys/' + modern).get()).exists, false);
});

test('new sender reaches old inbox and old ACK removes the canonical copy', { skip: !enabled }, async () => {
  const data = wire('interopNewToOld', old);
  await ha(eu, modern, 'relayMessage', data);
  await deliverHaMessage(data.messageId);
  const oldSnapshot = await getFirestore().doc('messages/' + data.messageId).get();
  assert.equal(oldSnapshot.exists, true);
  const inbox = await compactInbox.run(req(old, { installationId: 'legacyInterop', saving: false }));
  assert.ok(inbox.messages.some(m => m.messageId === data.messageId));
  await acknowledgeMessage.run(req(old, { messageId: data.messageId }));
  await onLegacyMessageDeletedForHa.run({ data: oldSnapshot, params: { messageId: data.messageId } });
  assert.equal((await haFirestore().doc('messages/' + data.messageId).get()).exists, false);
  assert.equal((await ha(us, modern, 'relayMessage', data)).duplicate, true);
});

test('old sender reaches new inbox and retry after new ACK cannot resurrect it', { skip: !enabled }, async () => {
  const data = wire('interopOldToNew', modern);
  await relayMessage.run(req(old, data));
  assert.equal((await getFirestore().doc('messages/' + data.messageId).get()).exists, false);
  const inbox = await ha(us, modern, 'compactInbox', { installationId: 'modernInterop', saving: false });
  assert.ok(inbox.messages.some(m => m.messageId === data.messageId));
  await ha(eu, modern, 'acknowledgeMessage', { messageId: data.messageId });
  assert.equal((await relayMessage.run(req(old, data))).duplicate, true);
});

test('direct writes from old SDKs are bridged; an HA ACK atomically fences later legacy forwarding', { skip: !enabled }, async () => {
  const data = { ...wire('interopDirectLegacy', modern), senderUid: old };
  const ref = getFirestore().doc('messages/' + data.messageId);
  await ref.set(data);
  const source = await ref.get();
  await onMessageCreated.run({ data: source, params: { messageId: data.messageId } });
  const canonical = await haFirestore().doc('messages/' + data.messageId).get();
  assert.equal(canonical.exists, true);
  await ha(us, modern, 'acknowledgeMessage', { messageId: data.messageId });
  await onHaMessageDeletedEurope.run({ data: canonical, params: { messageId: data.messageId } });
  assert.equal((await ref.get()).exists, false);
  // Replaying an earlier forwarding request observes the default receipt tombstone.
  const replay = await relayMessage.run(req(old, data));
  assert.equal(replay.duplicate, true);
});

test('new-to-new delivery remains functional while all default database access fails', { skip: !enabled }, async () => {
  const legacy = getFirestore(), originalDoc = legacy.doc, originalCollection = legacy.collection;
  legacy.doc = () => { throw new Error('Injected default region outage'); };
  legacy.collection = () => { throw new Error('Injected default region outage'); };
  try {
    const data = wire('interopDefaultOutage', newer);
    await ha(eu, modern, 'relayMessage', data);
    const inbox = await ha(us, newer, 'compactInbox', { installationId: 'outageInterop', saving: false });
    assert.ok(inbox.messages.some(m => m.messageId === data.messageId));
    await ha(us, newer, 'acknowledgeMessage', { messageId: data.messageId });
    assert.equal((await ha(eu, modern, 'relayMessage', data)).duplicate, true);
    assert.equal((await ha(eu, modern, 'lookupIdentityKey', { uid: newer })).uid, newer);
    assert.equal((await ha(us, modern, 'fetchSignalPreKeyBundle', { targetUid: newer })).preKey.keyId, 1);
  } finally { legacy.doc = originalDoc; legacy.collection = originalCollection; }
});

test('backfill preserves an unacknowledged message instead of interpreting migration as delivery', { skip: !enabled }, async () => {
  const data = { ...wire('interopBackfill', modern), senderUid: old };
  await getFirestore().doc('messages/' + data.messageId).set(data);
  await backfillLegacyInbox(modern);
  assert.equal((await getFirestore().doc('messages/' + data.messageId).get()).exists, true);
  assert.equal((await haFirestore().doc('messages/' + data.messageId).get()).exists, true);
});

test('mixed contact discovery excludes stale legacy opt-ins for an enrolled private account', { skip: !enabled }, async () => {
  const number = '+905551234567';
  const full = (uid, data) => ({ auth: { uid, token: { phone_number: number } }, data });
  await withMessagingDatabase('(default)', () => publishIdentityKey.run(full(modern, {
    publicKey: 'YWJj', discoverable: true, phone: number,
  })));
  await ha(eu, modern, 'publishIdentityKey', { publicKey: 'YWJj', discoverable: false });
  assert.equal((await getFirestore().doc('messagingDirectory/' + hashIdentifier('phone', number)).get()).exists, true);
  const found = await eu.run(full('interopFinder', { operation: 'findContactsOnCrisisConnect', payload: { phones: [number] } }));
  assert.equal(found.matches.some(m => m.uid === modern), false);
});

test('erasure removes parentless HA trees and stops a surviving auth session from republishing', { skip: !enabled }, async () => {
  await haFirestore().doc(`messagingTokens/${modern}/tokens/orphan`).set({ token: 'fixture' });
  await deleteHighAvailabilityAccount(modern);
  assert.equal((await haFirestore().collection(`messagingTokens/${modern}/tokens`).get()).size, 0);
  assert.equal((await haFirestore().doc('signalPreKeys/' + modern).get()).exists, false);
  await assert.rejects(ha(eu, modern, 'publishIdentityKey', { publicKey: 'YWJj' }), e => e.code === 'not-found');
  await assert.rejects(publishIdentityKey.run(req(modern, { publicKey: 'YWJj' })), e => e.code === 'permission-denied');
});

after(async () => {
  if (!enabled) return;
  for (const db of [getFirestore(), haFirestore()]) {
    for (const root of ['messages', 'messagingRegionalReceipts', 'messagingAccounts', 'messagingBridgeDeletes']) {
      const snapshot = await db.collection(root).get();
      for (const doc of snapshot.docs) await db.recursiveDelete(doc.ref);
    }
    for (const uid of [old, modern, newer]) for (const root of ['signalPreKeys', 'messagingKeys', 'signalBundleThrottle',
      'messagingTokens', 'messagingTraffic', 'messagingBridgeAcks', 'messagingMigrationJobs', 'users', 'messagingRelayBudgets']) {
      await db.recursiveDelete(db.doc(root + '/' + uid));
    }
    await db.terminate();
  }
  for (const [name, value] of [['HA_COMPATIBILITY_ENABLED', saved.compatibility], ['HA_ENROLLMENT_ENABLED', saved.enrollment]]) {
    if (value === undefined) delete process.env[name]; else process.env[name] = value;
  }
});
