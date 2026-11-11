import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import test, { before, after } from 'node:test';
// An external test-tool installation may be supplied without changing production dependencies.
const require = createRequire(process.env.RULES_TEST_PACKAGE_JSON ?? new URL('../package.json', import.meta.url));
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, getDocs, query, collection, where, updateDoc, deleteDoc, orderBy, documentId } = require('firebase/firestore');
let environment;
before(async () => {
  environment = await initializeTestEnvironment({ projectId: 'demo-crisis-ha-rules',
    firestore: { host: '127.0.0.1', port: 8787, rules: await readFile(new URL('../../firestore.messaging-ha.rules', import.meta.url), 'utf8') } });
  await environment.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, 'messages/recipientMessage'), { senderUid: 'sender', recipientUid: 'recipient', ciphertext: 'opaque' });
    await setDoc(doc(db, 'messages/otherMessage'), { senderUid: 'sender', recipientUid: 'someoneElse', ciphertext: 'opaque' });
    await setDoc(doc(db, 'messages/deletedMessage'), { senderUid: 'sender', recipientUid: 'deleted', ciphertext: 'opaque' });
    await setDoc(doc(db, 'messagingAccounts/deleted'), { state: 'deleted', protocol: 4 });
    for (const path of ['signalPreKeys/recipient', 'users/recipient', 'messagingKeys/recipient', 'messagingAccounts/recipient',
      'messagingBridgeAcks/recipient/ackedMessages/id', 'messagingTokens/recipient/tokens/token']) {
      await setDoc(doc(db, path), { private: true, ...(path === 'messagingAccounts/recipient' ? { state: 'active', protocol: 4 } : {}) });
    }
  });
});
test('recipient may read the exact native inbox query and own ciphertext', async () => {
  const db = environment.authenticatedContext('recipient').firestore();
  await assertSucceeds(getDoc(doc(db, 'messages/recipientMessage')));
  await assertSucceeds(getDocs(query(collection(db, 'messages'), where('recipientUid', '==', 'recipient'), orderBy(documentId()))));
});
test('public, sender, unrelated, claimed-admin and unbounded reads are denied', async () => {
  for (const context of [environment.unauthenticatedContext(), environment.authenticatedContext('sender'),
    environment.authenticatedContext('someoneElse'), environment.authenticatedContext('administrator', { role: 'admin' })]) {
    await assertFails(getDoc(doc(context.firestore(), 'messages/recipientMessage')));
  }
  await assertFails(getDocs(collection(environment.authenticatedContext('recipient').firestore(), 'messages')));
});
test('even the owner cannot create, alter, rebind, or delete an envelope through the SDK', async () => {
  const db = environment.authenticatedContext('recipient').firestore();
  await assertFails(setDoc(doc(db, 'messages/forged'), { recipientUid: 'recipient', senderUid: 'someoneElse', ciphertext: 'forged' }));
  await assertFails(updateDoc(doc(db, 'messages/recipientMessage'), { recipientUid: 'someoneElse' }));
  await assertFails(updateDoc(doc(db, 'messages/recipientMessage'), { ciphertext: 'x'.repeat(100_000) }));
  await assertFails(deleteDoc(doc(db, 'messages/recipientMessage')));
});
test('private profiles, keys, routing and orphaned subcollections stay server-only', async () => {
  const db = environment.authenticatedContext('recipient').firestore();
  for (const path of ['signalPreKeys/recipient', 'users/recipient', 'messagingKeys/recipient', 'messagingAccounts/recipient',
    'messagingBridgeAcks/recipient/ackedMessages/id', 'messagingTokens/recipient/tokens/token']) {
    await assertFails(getDoc(doc(db, path)));
    await assertFails(setDoc(doc(db, path), { role: 'admin' }));
  }
});
test('an account being erased cannot keep reading with a surviving token', async () => {
  await assertFails(getDoc(doc(environment.authenticatedContext('deleted').firestore(), 'messages/deletedMessage')));
});
after(async () => { await environment?.cleanup(); });
