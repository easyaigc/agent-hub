import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import test from 'node:test';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

function fixture() {
  const snapshots = [];
  const value = {
    hostId: 'fixture-host',
    epoch: 'fixture-epoch',
    seq: 71,
    ready: true,
    capabilities: [],
    events: [{ seq: 71, threadId: 'selected', kind: 'changed' }],
    listeners: new Set(),
    runtime: {},
    state: { threads: [{ id: 'selected' }] },
    revision: 1,
    thread(id) {
      assert.equal(id, 'selected');
    },
    async refreshNativeQueue() {},
    history(id) {
      snapshots.push(id);
      return { threadId: id, revision: this.revision };
    },
  };
  return { value, snapshots };
}
function poll(value, after = 70, epoch = 'fixture-epoch') {
  return RuntimeSession.prototype.poll.call(
    value,
    after,
    epoch,
    new AbortController().signal,
    'selected',
  );
}

test('a filtered quiet thread advances the global cursor without an invented reset', async () => {
  const { value, snapshots } = fixture();
  value.events[0].threadId = 'other';
  const result = await poll(value);
  assert.equal(result.cursor, 71);
  assert.equal(result.reset, false);
  assert.deepEqual(result.events, []);
  assert.deepEqual(snapshots, []);
});

test('retention lost during coalescing returns an authoritative reset snapshot', async () => {
  const { value, snapshots } = fixture();
  const pending = poll(value);
  setImmediate(() => {
    // The originally selected change has left the retained host event window.
    value.events = [{ seq: 74, threadId: 'other', kind: 'changed' }];
    value.seq = 74;
    value.revision = 2;
  });
  const result = await pending;
  assert.equal(result.reset, true);
  assert.equal(result.cursor, 74);
  assert.deepEqual(snapshots, ['selected']);
  assert.deepEqual(
    result.events.map((event) => event.snapshot),
    [{ threadId: 'selected', revision: 2 }],
  );
});

test('an epoch changed during coalescing returns the new authoritative snapshot', async () => {
  const { value, snapshots } = fixture();
  const pending = poll(value);
  setImmediate(() => {
    value.epoch = 'new-epoch';
    value.events = [];
    value.seq = 1;
    value.revision = 2;
  });
  const result = await pending;
  assert.equal(result.reset, true);
  assert.equal(result.epoch, 'new-epoch');
  assert.deepEqual(snapshots, ['selected']);
  assert.equal(result.events[0].snapshot.revision, 2);
});

test('an initially expired cursor still returns only the requested thread snapshot', async () => {
  const { value, snapshots } = fixture();
  value.events = [{ seq: 74, threadId: 'other', kind: 'changed' }];
  value.seq = 74;
  const result = await poll(value);
  assert.equal(result.reset, true);
  assert.deepEqual(snapshots, ['selected']);
  assert.equal(result.events.length, 1);
  assert.equal(result.events[0].threadId, 'selected');
});
