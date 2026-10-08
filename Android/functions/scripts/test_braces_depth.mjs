import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import test from 'node:test';

const require = createRequire(import.meta.url);
const braces = require('braces');

const nested = (open, close, depth) => open.repeat(depth) + 'x' + close.repeat(depth);

const nestedAst = depth => {
  let node = { type: 'text', value: 'x' };
  for (let index = 0; index < depth; index++) {
    node = { type: 'brace', nodes: [node] };
  }
  return { type: 'root', nodes: [node] };
};

test('deep brace and parenthesis patterns fail before recursive walkers overflow', () => {
  for (const [open, close] of [['{', '}'], ['(', ')']]) {
    const allowed = nested(open, close, 100);
    const rejected = nested(open, close, 101);
    assert.doesNotThrow(() => braces(allowed));
    assert.throws(() => braces.parse(rejected), /Input depth \(101\), exceeds max depth \(100\)/);
  }

  const attack = nested('{', '}', 3500);
  for (const run of [
    () => braces(attack),
    () => braces(attack, { expand: true }),
    () => braces.stringify(attack)
  ]) {
    assert.throws(run, /Input depth \(101\), exceeds max depth \(100\)/);
  }
});

test('caller supplied ASTs have the same depth boundary', () => {
  for (const run of [braces.compile, braces.expand, braces.stringify]) {
    assert.throws(() => run(nestedAst(101)), /AST depth \(101\), exceeds max depth \(100\)/);
  }

  const cycle = { type: 'group', nodes: [{ type: 'text', value: 'x' }] };
  cycle.parent = cycle;
  assert.throws(() => braces.expand({ type: 'root', nodes: [cycle] }), /parent chain contains a cycle/);
});

test('ordinary glob expansion and escaping remain compatible', () => {
  assert.deepEqual(braces.expand('**/{functions,firestore}/*.rules'), [
    '**/functions/*.rules',
    '**/firestore/*.rules'
  ]);
  assert.equal(braces.stringify(braces.parse('{{a}}'), { escapeInvalid: true }), '{{a}}');
  assert.doesNotThrow(() => braces('\\{'.repeat(101) + 'x' + '\\}'.repeat(101)));
  assert.throws(() => braces.parse('{x}', { maxDepth: 0.5 }), /exceeds max depth/);
});

test('Firebase Tools keeps glob based file ignore behavior', async () => {
  const firebaseRequire = createRequire(require.resolve('firebase-tools/package.json'));
  const chokidar = firebaseRequire('chokidar');
  const watcher = new chokidar.FSWatcher({ ignored: ['**/*.local'] });
  try {
    assert.equal(watcher._isIgnored(path.join(process.cwd(), 'example.local')), true);
    assert.equal(watcher._isIgnored(path.join(process.cwd(), 'example.ts')), false);
  } finally {
    await watcher.close();
  }
});
