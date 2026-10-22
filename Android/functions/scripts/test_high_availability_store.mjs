import assert from 'node:assert/strict';
import test, { after, before } from 'node:test';
import { createRequire } from 'node:module';
import { initializeApp } from 'firebase-admin/app';
const require = createRequire(import.meta.url);
const { haFirestore, activateHighAvailability, usesHighAvailability, copyLegacyMessageToHa, acknowledgeHaMessage, messageDigest } = require('../lib/messaging/highAvailabilityStore.js');
const { regionalRelay } = require('../lib/messaging/regionalGateway.js');
const { withMessagingDatabase, MULTIREGION_MESSAGING_DATABASE } = require('../lib/messaging/databaseTarget.js');
const enabled = !!process.env.FIRESTORE_EMULATOR_HOST;
if (enabled) initializeApp({ projectId: 'demo-crisis-ha-store' });
const envelope = () => ({ senderUid: 'bridgeSender', recipientUid: 'bridgeRecipient', conversationId: 'bridgeConversation',
  v: 2, ctype: 'signal', ciphertext: 'YWJj', createdAtMs: Date.now(), ttlMs: 60_000 });

before(async () => {
  if (!enabled) return;
  // ACK parent documents intentionally do not exist; collection.get() cannot discover them.
  for (const uid of ['bridgeRecipient', 'otherRecipient']) {
    await haFirestore().recursiveDelete(haFirestore().doc('messagingBridgeAcks/' + uid));
  }
});

test('a bridge never accepts missing sender/recipient fields coerced to the string undefined', async () => {
  await assert.rejects(copyLegacyMessageToHa('bridgeInvalid', { conversationId: 'ok' }), e => e.code === 'invalid-argument');
  await assert.rejects(copyLegacyMessageToHa('bridgeInvalid', { senderUid: 123, recipientUid: 'valid', conversationId: 'ok' }), e => e.code === 'invalid-argument');
});

test('a late bridge event cannot resurrect an already acknowledged message', { skip: !enabled }, async () => {
  const data = envelope(), id = 'bridgeLate';
  await acknowledgeHaMessage(data.recipientUid, id);
  assert.equal(await copyLegacyMessageToHa(id, data), false);
  assert.equal((await haFirestore().doc('messages/' + id).get()).exists, false);
  assert.equal((await haFirestore().doc('messagingRegionalReceipts/' + messageDigest(id)).get()).exists, true);
});

test('simultaneous bridge deliveries produce one message and cannot rebind its route', { skip: !enabled }, async () => {
  const data = envelope(), id = 'bridgeConcurrent';
  assert.deepEqual((await Promise.all([copyLegacyMessageToHa(id, data), copyLegacyMessageToHa(id, data)])).sort(), [false, true]);
  await assert.rejects(copyLegacyMessageToHa(id, { ...data, recipientUid: 'otherRecipient' }), e => e.code === 'already-exists');
  await assert.rejects(acknowledgeHaMessage('otherRecipient', id), e => e.code === 'permission-denied');
  await acknowledgeHaMessage(data.recipientUid, id);
  assert.equal(await copyLegacyMessageToHa(id, data), false);
});

test('an absent ACK cannot reserve another recipient’s message ID', { skip: !enabled }, async () => {
  const data = envelope(), id = 'bridgeOtherAck';
  await acknowledgeHaMessage('otherRecipient', id);
  assert.equal(await copyLegacyMessageToHa(id, data), true);
  assert.equal((await haFirestore().doc('messages/' + id).get()).get('recipientUid'), data.recipientUid);
});

test('a recipient ACK before relay suppresses the late canonical send as well as the bridge', { skip: !enabled }, async () => {
  const data = envelope(), id = 'bridgeAckBeforeRelay';
  await acknowledgeHaMessage(data.recipientUid, id);
  const result = await withMessagingDatabase(MULTIREGION_MESSAGING_DATABASE,
    () => regionalRelay({ ...data, messageId: id, priority: 'normal' }, data.senderUid));
  assert.equal(result.duplicate, true);
  assert.equal((await haFirestore().doc('messages/' + id).get()).exists, false);
});

test('canonical relay cannot recreate an erased recipient inbox', { skip: !enabled }, async () => {
  const data = envelope();
  await haFirestore().doc('messagingAccounts/' + data.recipientUid).set({ state: 'deleted', protocol: 4 });
  await assert.rejects(withMessagingDatabase(MULTIREGION_MESSAGING_DATABASE,
    () => regionalRelay({ ...data, messageId: 'bridgeDeletedRecipient', priority: 'normal' }, data.senderUid)),
    e => e.code === 'not-found');
  await haFirestore().doc('messagingAccounts/' + data.recipientUid).delete();
});

test('adopting an existing copy adds its receipt before subsequent ACK', { skip: !enabled }, async () => {
  const data = envelope(), id = 'bridgeAdopt';
  await haFirestore().doc('messages/' + id).set(data);
  assert.equal(await copyLegacyMessageToHa(id, data), false);
  await acknowledgeHaMessage(data.recipientUid, id);
  assert.equal(await copyLegacyMessageToHa(id, data), false);
  assert.equal((await haFirestore().doc('messages/' + id).get()).exists, false);
});

test('expired and far-future envelopes cannot enter the new inbox', { skip: !enabled }, async () => {
  assert.equal(await copyLegacyMessageToHa('bridgeExpired', { ...envelope(), createdAtMs: Date.now() - 120_000 }), false);
  assert.equal(await copyLegacyMessageToHa('bridgeFuture', { ...envelope(), createdAtMs: Date.now() + 600_000 }), false);
});

test('enrollment requires a published identity and does not enroll unrelated accounts', { skip: !enabled }, async () => {
  await assert.rejects(activateHighAvailability('bridgeUnpublished'), e => e.code === 'failed-precondition');
  await haFirestore().doc('messagingKeys/bridgeRecipient').set({ publicKey: 'YWJj' });
  await activateHighAvailability('bridgeRecipient');
  assert.equal(await usesHighAvailability('bridgeRecipient'), true);
  assert.equal(await usesHighAvailability('bridgeOther'), false);
});

after(async () => {
  if (!enabled) return;
  const db = haFirestore();
  for (const collection of ['messages', 'messagingRegionalReceipts', 'messagingBridgeAcks', 'messagingKeys', 'messagingAccounts', 'messagingMigrationJobs']) {
    const snapshot = await db.collection(collection).get();
    for (const doc of snapshot.docs) await db.recursiveDelete(doc.ref);
  }
  for (const uid of ['bridgeRecipient', 'otherRecipient']) await db.recursiveDelete(db.doc('messagingBridgeAcks/' + uid));
  await db.terminate();
});
