import assert from 'node:assert/strict';
import fs from 'node:fs';
import { win32 } from 'node:path';
import { test } from 'node:test';
import {
  createTraceSnapshotBatchPlanner,
  encodeTraceSnapshotBatch,
  traceSnapshotBatchFits,
} from '../packages/codex-adapter/dist/desktop-trace-native.js';
import { preparedTraceFixture } from './fixtures/windows-prepared-trace.mjs';

function wireFits(items) {
  const request = {
    schemaVersion: 1,
    kind: 'snapshot-many',
    id: 2147483647,
    items: items.map((item, index) => ({ index, ...item })),
  };
  const bytes = Buffer.byteLength(JSON.stringify(request));
  const response =
    256 +
    items.reduce((sum, item) => {
      let depth = 1;
      for (let at = item.path; at !== win32.parse(at).root; at = win32.dirname(at)) depth++;
      return sum + 256 + depth * 128 + 4 * Math.ceil(item.maximum / 3);
    }, 0);
  return (
    items.length > 0 &&
    items.length <= 64 &&
    bytes <= 48 * 1024 &&
    2 + 4 * Math.ceil(bytes / 3) <= 100000 &&
    response <= 256 * 1024
  );
}

test('incremental planning matches serialized wire bounds across indices, UTF-8, escaping and deep chains', () => {
  for (const shape of [
    (i) => ({ path: `C:\\fixture\\small-${i}`, maximum: 0 }),
    (i) => ({ path: `C:\\fixture\\中文😀-quote"-${i}`, maximum: 1024 }),
    (i) => ({ path: `C:\\fixture\\${'a'.repeat(1200)}-${i}`, maximum: 1 }),
    (i) => ({ path: `C:\\${'deep\\'.repeat(100)}file-${i}`, maximum: 16384 }),
  ]) {
    const planner = createTraceSnapshotBatchPlanner(),
      accepted = [];
    for (let i = 0; i < 65; i++) {
      const item = shape(i),
        expected = wireFits([...accepted, item]);
      assert.equal(planner.add(item), expected);
      assert.equal(traceSnapshotBatchFits([...accepted, item]), expected);
      if (expected) {
        accepted.push(item);
        const frame = encodeTraceSnapshotBatch({
          schemaVersion: 1,
          kind: 'snapshot-many',
          id: 2147483647,
          items: accepted.map((item, index) => ({ index, ...item })),
        });
        assert(frame.length <= 100000);
        assert(Buffer.from(frame.slice(2), 'base64').length <= 48 * 1024);
      }
    }
  }
});

test('a rejected planning append consumes neither a row nor its byte budget', () => {
  const planner = createTraceSnapshotBatchPlanner();
  for (const item of [
    { path: 'C:\\fixture\\..\\alias', maximum: 1 },
    { path: 'C:\\fixture\\file:ads', maximum: 1 },
    { path: 'C:\\fixture\\control\n', maximum: 1 },
    { path: 'C:\\fixture\\large', maximum: 16385 },
    { path: 'C:\\fixture\\fractional', maximum: 1.5 },
    { path: 'C:\\' + 'deep\\'.repeat(3000) + 'file', maximum: 1 },
  ])
    assert.equal(planner.add(item), false);
  for (let i = 0; i < 64; i++) assert(planner.add({ path: `C:\\fixture\\${i}`, maximum: 0 }));
  assert.equal(planner.add({ path: 'C:\\fixture\\65', maximum: 0 }), false);
});

test('actual file planning serializes a linear number of rows while retaining all native receipts', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls }) => {
    const paths = Array.from({ length: 64 }, (_, i) => root + `\\linear-${i}`);
    for (const path of paths) fs.writeFileSync(local(path), 'AAAA');
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    const stringify = JSON.stringify;
    let rows = 0;
    JSON.stringify = function (value, ...args) {
      if (value?.items) rows += value.items.length;
      else if (value?.path && Object.hasOwn(value, 'index')) rows++;
      return Reflect.apply(stringify, this, [value, ...args]);
    };
    try {
      const values = snapshot.files(paths.map((path) => ({ path, maximum: 4 })));
      assert(values.every((value) => value.toString() === 'AAAA'));
    } finally {
      JSON.stringify = stringify;
    }
    assert(
      rows <= paths.length * 2,
      'Planning must not reserialize the growing prefix for every row',
    );
    assert.equal(snapshot.guards.length, 65);
    assert.equal(snapshot.bytes, 256);
    const items = calls.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
    assert.deepEqual(
      items.filter((item) => item.maximum > 0).map((item) => item.path),
      paths,
    );
    snapshot.commit();
  }));

test('short configured prefixes retain the original projection and reduce discovery bytes', () =>
  preparedTraceFixture(({ TraceSnapshot, reader, create, prepare, calls, local, reset }) => {
    const sources = Array.from({ length: 80 }, () => create());
    for (const f of sources) {
      f.fixture.events.at(-1).padding = 'x'.repeat(20000);
      f.fixture.saveEvents();
    }
    const files = TraceSnapshot.prototype.files;
    TraceSnapshot.prototype.files = function (items) {
      return Reflect.apply(files, this, [
        items.map((item) =>
          item.prefix && item.maximum === 1024 ? { ...item, maximum: 16384 } : item,
        ),
      ]);
    };
    let legacy, legacyCalls;
    try {
      reset();
      legacy = prepare(reader, sources[0].fixture);
      assert(legacy);
      legacyCalls = structuredClone(calls);
    } finally {
      TraceSnapshot.prototype.files = files;
    }
    const expected = legacy.commit();
    assert.equal(expected?.length, 1);
    reset();
    const next = prepare(reader, sources[0].fixture);
    assert(next);
    const currentCalls = structuredClone(calls);
    assert.deepEqual(next.commit(), expected);
    const prefixBytes = (values) =>
      values
        .flatMap((call) => (call.kind === 'B' ? call.items : [call]))
        .filter((item) => item.path.endsWith('\\trace.jsonl') && item.maximum <= 16384)
        .reduce((sum, item) => sum + Math.min(item.maximum, fs.statSync(local(item.path)).size), 0);
    assert(prefixBytes(currentCalls) <= prefixBytes(legacyCalls) / 8);
    assert(currentCalls.length < legacyCalls.length);
  }));

test('a configured record beyond the short prefix extends once and retains both prefix fences', () =>
  preparedTraceFixture(({ reader, create, prepare, calls, remote }) => {
    const f = create();
    f.fixture.events[0].padding = 'x'.repeat(2500);
    f.fixture.saveEvents();
    const confirmed = prepare(reader, f.fixture);
    assert.equal(confirmed?.commit()?.length, 1);
    const pending = prepare(reader, f.fixture);
    assert(pending);
    const path = remote(f.fixture.logPath);
    const reads = calls
      .flatMap((call) => (call.kind === 'B' ? call.items : [call]))
      .filter((item) => item.path === path);
    assert(reads.some((item) => item.maximum === 1024));
    assert(reads.some((item) => item.maximum === 16384));
    const text = fs.readFileSync(f.fixture.logPath, 'utf8');
    fs.writeFileSync(f.fixture.logPath, text.replace('xxxx', 'yyyy'));
    assert.equal(pending.commit(), null);
  }));

test('a configured unrelated log may append after its native prefix handle closes', () =>
  preparedTraceFixture(({ reader, root, local, remote, create, prepare, data, change }) => {
    const source = create();
    const unrelated = data.bundle(local(root));
    unrelated.protocolEvent('session_configured', {
      type: 'session_configured',
      thread_source: 'vscode',
    });
    let appended = false;
    change((when, path, maximum) => {
      if (when !== 'after' || path !== remote(unrelated.logPath) || !maximum || appended) return;
      fs.appendFileSync(unrelated.logPath, 'UNRELATED-APPEND\n');
      appended = true;
    });
    const pending = prepare(reader, source.fixture);
    assert(appended);
    assert(pending, 'a stable configured prefix must tolerate unrelated tail progress');
    assert.equal(pending.commit()?.length, 1);
  }));

for (const mode of ['prefix-rewrite', 'unclassified-growth', 'outside-prefix-truncate'])
  test(`a prefix mutation during batch delivery still rejects ${mode}`, () =>
    preparedTraceFixture(({ reader, root, local, remote, create, prepare, data, change }) => {
      const source = create();
      const unrelated = data.bundle(local(root));
      if (mode !== 'unclassified-growth')
        unrelated.protocolEvent('session_configured', {
          type: 'session_configured',
          thread_source: 'vscode',
        });
      if (mode === 'outside-prefix-truncate')
        fs.appendFileSync(unrelated.logPath, ' '.repeat(20000));
      let mutated = false;
      change((when, path, maximum) => {
        if (when !== 'after' || path !== remote(unrelated.logPath) || !maximum || mutated) return;
        mutated = true;
        if (mode === 'outside-prefix-truncate') fs.truncateSync(unrelated.logPath, 2048);
        else if (mode === 'prefix-rewrite') {
          const bytes = fs.readFileSync(unrelated.logPath);
          bytes[0] = 91;
          fs.writeFileSync(unrelated.logPath, bytes);
        } else {
          unrelated.protocolEvent('session_configured', {
            type: 'session_configured',
            thread_source: 'composer_predictions',
            forked_from_id: source.fixture.sourceThreadId,
          });
        }
      });
      const pending = prepare(reader, source.fixture);
      assert(mutated);
      assert.equal(pending?.commit() ?? null, null);
    }));

for (const failedMaximum of [1024, 16384])
  test(`a failed ${failedMaximum}-byte prefix read is never retried or bypassed`, () =>
    preparedTraceFixture(({ reader, create, prepare, calls, remote, change }) => {
      const f = create();
      f.fixture.events[0].padding = 'x'.repeat(2500);
      f.fixture.saveEvents();
      const path = remote(f.fixture.logPath);
      change((when, candidate, maximum) => {
        if (when === 'before' && candidate === path && maximum === failedMaximum)
          throw Error('Synthetic native rejection');
      });
      assert.equal(prepare(reader, f.fixture), null);
      const reads = calls
        .flatMap((call) => (call.kind === 'B' ? call.items : [call]))
        .filter((item) => item.path === path);
      assert.equal(reads.filter((item) => item.maximum === failedMaximum).length, 1);
      if (failedMaximum === 1024) assert(!reads.some((item) => item.maximum > 1024));
    }));
