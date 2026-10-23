import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DesktopLiveWindow } from '../packages/codex-adapter/dist/desktop-live-window.js';

const path = ['turnHistory', 'history', 'entitiesByKey', 'tail:0:local:opaque', 'items'];
const item = (id, text) => ({ id, type: 'agentMessage', text });
const batch = (baseRevision, patches) => ({
  type: 'patches',
  baseRevision,
  revision: baseRevision + 1,
  patches,
});
test('a missing stream base cannot fabricate an item or a turn; exact native IDs bind a live window', () => {
  const window = new DesktopLiveWindow();
  window.apply(
    batch(70, [{ op: 'replace', path: [...path, 901, 'text'], value: 'unknown ancestor' }]),
  );
  assert.deepEqual(window.observation().items, []);
  window.apply(batch(71, [{ op: 'add', path: [...path, 902], value: item('live-a', 'start') }]));
  assert.deepEqual(window.observation().items, [], 'Opaque entity key is not a turn ID');
  window.bind([{ id: 'different', runId: 'current-turn' }]);
  assert.deepEqual(window.observation().items, []);
  window.bind([{ id: 'live-a', runId: 'current-turn' }]);
  window.apply(
    batch(72, [{ op: 'replace', path: [...path, 902, 'text'], value: 'streamed update' }]),
  );
  assert.equal(window.observation().items[0].turnId, 'current-turn');
  assert.equal(window.observation().items[0].item.text, 'streamed update');
  window.apply(
    batch(72, [{ op: 'replace', path: [...path, 902, 'text'], value: 'late duplicate' }]),
  );
  assert.equal(window.observation().items[0].item.text, 'streamed update');
  window.apply(batch(75, [{ op: 'replace', path: [...path, 902, 'text'], value: 'gap' }]));
  assert.deepEqual(
    window.observation().items,
    [],
    'A lost patch invalidates all partial ancestors',
  );
});
test('sparse array edits preserve exact item identity and reject ambiguous historical bindings', () => {
  const window = new DesktopLiveWindow();
  window.apply(batch(1, [{ op: 'add', path: [...path, 1000], value: item('a', 'A') }]));
  window.bind([{ id: 'a', runId: 'turn-a' }]);
  window.apply(batch(2, [{ op: 'add', path: [...path, 2], value: item('b', 'B') }]));
  window.apply(batch(3, [{ op: 'replace', path: [...path, 1001, 'text'], value: 'A2' }]));
  assert.deepEqual(
    window.observation().items.map((v) => v.item.text),
    ['B', 'A2'],
  );
  window.apply(batch(4, [{ op: 'remove', path: [...path, 2] }]));
  window.apply(batch(5, [{ op: 'replace', path: [...path, 1000, 'text'], value: 'A3' }]));
  assert.deepEqual(
    window.observation().items.map((v) => v.item.text),
    ['A3'],
  );
  window.bind([{ id: 'a', runId: 'different-turn' }]);
  assert.deepEqual(
    window.observation()?.items ?? [],
    [],
    'Conflicting exact IDs revoke the projection',
  );
});
test('oversized items, prototype paths, and unbounded history cannot enter the live window', () => {
  const window = new DesktopLiveWindow();
  window.apply(
    batch(1, [
      { op: 'add', path, value: Array.from({ length: 200 }, (_, n) => item('id-' + n, 'text')) },
    ]),
  );
  window.bind([{ id: 'id-199', runId: 'turn' }]);
  assert.equal(window.observation().items.length, 30);
  window.apply(
    batch(2, [{ op: 'replace', path: [...path, 199], value: item('huge', 'x'.repeat(300000)) }]),
  );
  assert.equal(window.observation().items.length, 29);
  window.apply(
    batch(3, [{ op: 'add', path: [...path, 198, '__proto__', 'polluted'], value: true }]),
  );
  assert.equal({}.polluted, undefined);
  assert.equal(window.observation().items.length, 28);
});

test('giant tool bodies are rejected before another JSON body is materialized', () => {
  const window = new DesktopLiveWindow();
  const original = JSON.stringify;
  try {
    JSON.stringify = (value, ...args) => {
      assert.notEqual(
        value?.id,
        'giant-tool',
        'Large tool must fail the size budget before serialization',
      );
      return original(value, ...args);
    };
    window.apply(
      batch(1, [
        {
          op: 'add',
          path: [...path, 0],
          value: {
            id: 'giant-tool',
            type: 'commandExecution',
            aggregatedOutput: 'x'.repeat(1024 * 1024),
          },
        },
      ]),
    );
    window.bind([{ id: 'giant-tool', runId: 'turn' }]);
    assert.deepEqual(window.observation().items, []);
  } finally {
    JSON.stringify = original;
  }
});
