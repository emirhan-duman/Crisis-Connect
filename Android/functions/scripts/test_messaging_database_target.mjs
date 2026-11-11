import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { initializeApp } from 'firebase-admin/app';
const require = createRequire(import.meta.url);
const { messagingDatabaseId, getMessagingFirestore, currentMessagingDatabaseId, withMessagingDatabase } = require('../lib/messaging/databaseTarget.js');

test('released deployments keep their exact default database unless explicitly migrated', () => {
  assert.equal(messagingDatabaseId(undefined), '(default)');
  assert.equal(messagingDatabaseId('(default)'), '(default)');
  assert.equal(messagingDatabaseId('messaging-live-eur3'), 'messaging-live-eur3');
});

test('a restored snapshot, typo or empty config cannot silently become authoritative', () => {
  for (const value of ['', 'dr-rehearsal-eur3', 'messages-us', 'messaging-live-eur3 ', 'EUR3']) {
    assert.throws(() => messagingDatabaseId(value), /refusing a split/);
  }
});

test('overlapping legacy and new requests never leak their database into one another', async () => {
  let release;
  const barrier = new Promise(resolve => { release = resolve; });
  const migrated = withMessagingDatabase('messaging-live-eur3', async () => {
    assert.equal(currentMessagingDatabaseId(), 'messaging-live-eur3');
    await barrier;
    assert.equal(currentMessagingDatabaseId(), 'messaging-live-eur3');
    return 'new';
  });
  const legacy = withMessagingDatabase('(default)', async () => {
    assert.equal(currentMessagingDatabaseId(), '(default)');
    release();
    await Promise.resolve();
    assert.equal(currentMessagingDatabaseId(), '(default)');
    return 'old';
  });
  assert.deepEqual(await Promise.all([migrated, legacy]), ['new', 'old']);
  assert.equal(currentMessagingDatabaseId(), '(default)');
});

test('nested migration reads restore the caller database even after failure', async () => {
  await withMessagingDatabase('messaging-live-eur3', async () => {
    await assert.rejects(withMessagingDatabase('(default)', async () => {
      assert.equal(currentMessagingDatabaseId(), '(default)');
      throw new Error('synthetic unavailable');
    }));
    assert.equal(currentMessagingDatabaseId(), 'messaging-live-eur3');
  });
  assert.equal(currentMessagingDatabaseId(), '(default)');
});

test('both compute regions resolve the same SDK database instance; no network fallback', () => {
  initializeApp({ projectId: 'demo-crisis-database-target' });
  const original = process.env.MESSAGING_DATABASE_ID;
  try {
    delete process.env.MESSAGING_DATABASE_ID;
    const legacy = getMessagingFirestore();
    process.env.MESSAGING_DATABASE_ID = 'messaging-live-eur3';
    const canonical = getMessagingFirestore();
    assert.notEqual(canonical, legacy);
    assert.equal(getMessagingFirestore(), canonical);
    process.env.MESSAGING_DATABASE_ID = 'dr-rehearsal-eur3';
    assert.throws(getMessagingFirestore, /refusing a split/);
  } finally {
    if (original === undefined) delete process.env.MESSAGING_DATABASE_ID;
    else process.env.MESSAGING_DATABASE_ID = original;
  }
});
