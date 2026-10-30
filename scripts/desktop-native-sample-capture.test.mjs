import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { chmod, mkdtemp, readdir, readFile, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { DesktopNativeSampleCapture } from '../packages/codex-adapter/dist/desktop-native-sample-capture.js';
import { DesktopSuggestionStore } from '../packages/codex-adapter/dist/desktop-suggestion-store.js';
import { protectWindowsStateDirectory } from '../packages/codex-adapter/dist/desktop-platform.js';

const hash = (text) => createHash('sha256').update(text).digest('hex');
const timeout = 60_000;

// This is the store boundary, not a filesystem/Host/renderer integration fixture.
function setup({ save, read, onError } = {}) {
  let at = 1_000_000;
  const records = new Map();
  const writes = [];
  const errors = [];
  const producer = {
    epoch: randomUUID(),
    pid: 71,
    desktopPid: 72,
    desktopStartedAt: 900_000,
  };
  const store = {
    readRuntime(threadId) {
      return read ? read(threadId, records) : records.get(threadId);
    },
    saveRuntime(record) {
      assert.equal(Object.hasOwn(record, 'latestExtracted'), false);
      assert.equal(record.state === 'completed', record.result !== null);
      writes.push(structuredClone(record));
      if (save && save(record) !== true) return false;
      const previous = records.get(record.sourceThreadId);
      if (
        previous?.producer.epoch === record.producer.epoch &&
        previous.requestSequence > record.requestSequence
      )
        return false;
      const next = structuredClone(record);
      if (!next.result) {
        const kept = previous?.result
          ? { kind: 'native-sample', requestedAt: previous.requestedAt, result: previous.result }
          : previous?.latestExtracted;
        if (kept) next.latestExtracted = structuredClone(kept);
      }
      records.set(record.sourceThreadId, next);
      return true;
    },
  };
  const capture = new DesktopNativeSampleCapture({
    store,
    targetKey: 'codex:host-a:workspace-a:desktop-runtime',
    producer,
    now: () => at,
    onError(code) {
      errors.push(code);
      onError?.(code);
    },
  });
  return {
    capture,
    records,
    writes,
    errors,
    producer,
    advance: (milliseconds = 1) => (at += milliseconds),
    now: () => at,
    current: (request) => records.get(request.params.threadId.toLowerCase()),
    safety(request, suppressed = false, overrides = {}) {
      capture.observeSafetyDecision({
        sourceThreadId: request.params.threadId,
        sourceTurnId: request.params.expectedTurnId,
        suppressed,
        at,
        evidenceSha256: hash(`synthetic-safety:${request.id}:${at}`),
        ...overrides,
      });
    },
  };
}

function request(overrides = {}) {
  return {
    id: `thread/sample:${randomUUID()}`,
    method: 'thread/sample',
    params: {
      threadId: randomUUID(),
      expectedTurnId: randomUUID(),
      turnTrigger: 'composer_predictions',
      prompt: 'Synthetic native composer prediction request',
      model: 'desktop-selected-model',
      ...overrides,
    },
  };
}
const outbound = (fixture, value) => fixture.capture.observeOutbound(JSON.stringify(value));
function inbound(fixture, req, suggestion = 'Synthetic suggestion', result = {}) {
  fixture.capture.observeInbound(
    JSON.stringify({
      id: req.id,
      result: { text: JSON.stringify({ suggestion }), responseId: null, ...result },
    }),
  );
}
function complete(fixture, req, body = 'Synthetic suggestion') {
  outbound(fixture, req);
  fixture.advance();
  inbound(fixture, req, body);
  fixture.advance();
  fixture.safety(req);
}
function completedWrites(fixture) {
  return fixture.writes.filter((record) => record.state === 'completed');
}

test('native JSON frames remain passive; only a matched safety decision publishes the body', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const req = request({ realNativeOption: { arbitrary: true } });
  const original = JSON.stringify(req) + '\r\n';
  assert.equal(f.capture.observeOutbound(original), undefined);
  assert.equal(original, JSON.stringify(req) + '\r\n');
  assert.equal(f.current(req).state, 'pending');
  assert.equal(f.current(req).result, null);
  f.advance();
  const text = JSON.stringify({ suggestion: '  Synthetic body\n' });
  const response = JSON.stringify({
    id: req.id,
    result: { text, responseId: 'synthetic-response', nativeMetadata: true },
  });
  assert.equal(f.capture.observeInbound(response), undefined);
  assert.equal(f.writes.length, 1, 'An unscreened candidate never reaches the store');
  f.advance();
  f.safety(req);
  const saved = f.current(req);
  assert.deepEqual(
    Object.keys(saved).sort(),
    [
      'version',
      'kind',
      'targetKey',
      'sourceThreadId',
      'sourceTurnId',
      'requestId',
      'requestSequence',
      'requestedAt',
      'recordedAt',
      'producer',
      'state',
      'result',
    ].sort(),
  );
  assert.equal(saved.kind, 'native-sample');
  assert.equal(saved.version, 2);
  assert.equal(saved.state, 'completed');
  assert.equal(saved.result.text, 'Synthetic body');
  assert.equal(saved.result.contentSha256, hash('Synthetic body'));
  assert.equal(saved.result.resultSha256, hash(text), 'Digest binds the unmodified result.text');
  assert.equal(saved.result.requestId, req.id);
  assert.equal(saved.result.sourceTurnId, req.params.expectedTurnId);
  assert.equal(saved.result.responseId, 'synthetic-response');
  assert.equal(saved.result.completedAt, 1_000_001);
  assert.equal(saved.result.safety.decidedAt, 1_000_002);
  assert.deepEqual(saved.producer, f.producer);
  assert.equal(
    f.writes.some((record) => JSON.stringify(record).includes(req.params.prompt)),
    false,
  );
});

test('the API has no generation, transport, executor, or Trace-cancellation input', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  for (const method of ['send', 'request', 'generate', 'start', 'observeTrace']) {
    assert.equal(f.capture[method], undefined);
  }
  for (const method of ['turn/start', 'thread/fork', 'thread/start', 'sample', 'trace/cancel']) {
    outbound(f, { ...request(), method });
  }
  outbound(f, request({ turnTrigger: 'user' }));
  assert.equal(f.writes.length, 0);
});

test('strict UUIDs, distinct source IDs, exact sample ID and bounded prompt are required', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const invalid = [
    request({ threadId: 'prefix-' + randomUUID() }),
    request({ threadId: randomUUID() + '\n' }),
    request({ expectedTurnId: randomUUID() + '-suffix' }),
    request({ expectedTurnId: randomUUID() + '\r\n' }),
    request({ prompt: '' }),
    request({ prompt: '  \n' }),
    request({ prompt: 'x'.repeat(65_537) }),
    request({ prompt: ['not a string'] }),
  ];
  const identical = request();
  identical.params.expectedTurnId = identical.params.threadId.toUpperCase();
  invalid.push(identical);
  for (const id of [
    1,
    randomUUID(),
    'thread/sample:' + randomUUID() + ':extra',
    'THREAD/SAMPLE:' + randomUUID(),
    'thread/sample:' + randomUUID() + '\n',
    null,
  ]) {
    invalid.push({ ...request(), id });
  }
  for (const req of invalid) {
    outbound(f, req);
    inbound(f, req);
    f.safety(req);
  }
  assert.equal(completedWrites(f).length, 0);
});

test('latest source request writes pending immediately and late old responses cannot win', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const first = request();
  complete(f, first, 'Previously extracted body');
  const second = request({ threadId: first.params.threadId });
  outbound(f, second);
  assert.equal(f.current(second).state, 'pending');
  assert.equal(f.current(second).result, null);
  assert.equal(f.current(second).latestExtracted.result.text, 'Previously extracted body');
  const third = request({ threadId: first.params.threadId });
  outbound(f, third);
  inbound(f, second, 'Late body');
  f.safety(second);
  assert.equal(f.current(third).state, 'pending');
  assert(f.current(third).requestSequence > f.writes[1].requestSequence);
  inbound(f, third, 'Newest body');
  f.safety(third);
  assert.equal(f.current(third).result.text, 'Newest body');
});

test('wire observation order wins even when all timestamps are identical', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const first = request();
  const second = request({ threadId: first.params.threadId });
  outbound(f, first);
  outbound(f, second);
  inbound(f, first);
  f.safety(first);
  inbound(f, second, 'Newer sequence');
  f.safety(second);
  assert.equal(f.current(second).requestSequence, 2);
  assert.equal(f.current(second).result.text, 'Newer sequence');
});

test('safety events cannot cross thread, turn, response-time, or epoch boundaries', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const req = request();
  outbound(f, req);
  f.advance(10);
  inbound(f, req);
  f.safety(req, false, { sourceThreadId: randomUUID() });
  f.safety(req, false, { sourceTurnId: randomUUID() });
  f.safety(req, false, { at: 999_999 });
  assert.equal(completedWrites(f).length, 0);
  assert.equal(f.current(req).state, 'pending');
  f.safety(req);
  assert.equal(f.current(req).state, 'completed');

  const stale = request();
  outbound(f, stale);
  inbound(f, stale);
  f.records.set(stale.params.threadId, {
    ...f.current(stale),
    producer: { ...f.producer, epoch: randomUUID() },
  });
  f.safety(stale);
  assert.equal(completedWrites(f).length, 1);
});

test('a decision before its response, a future timestamp, and malformed safety proof fail closed', (t) => {
  for (const mode of ['before-response', 'earlier-than-response', 'future', 'hash', 'boolean']) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    if (mode === 'before-response') f.safety(req);
    f.advance(10);
    inbound(f, req);
    const changes = {
      'before-response': {},
      'earlier-than-response': { at: 1_000_005 },
      future: { at: f.now() + 1 },
      hash: { evidenceSha256: 'not-a-sha256' },
      boolean: { suppressed: 'false' },
    }[mode];
    f.safety(req, false, changes);
    assert.equal(completedWrites(f).length, 0, mode);
  }
});

test('independent sources may complete concurrently, even with an identical turn UUID', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const first = request();
  const second = request({ expectedTurnId: first.params.expectedTurnId });
  outbound(f, first);
  outbound(f, second);
  inbound(f, second, 'Source B');
  inbound(f, first, 'Source A');
  f.safety(first);
  f.safety(second);
  assert.equal(f.current(first).result.text, 'Source A');
  assert.equal(f.current(second).result.text, 'Source B');
});

test('same source/turn overlap and reuse cannot attach an old decision to a new body', (t) => {
  for (const previous of ['pending', 'candidate']) {
    const f = setup();
    t.after(() => f.capture.close());
    const first = request();
    outbound(f, first);
    if (previous !== 'pending') inbound(f, first, 'First body');
    if (previous === 'completed') f.safety(first);
    if (previous === 'cancelled')
      outbound(f, {
        method: 'thread/sample/cancel',
        params: { requestId: first.id },
      });
    const count = completedWrites(f).length;
    const second = request({
      threadId: first.params.threadId,
      expectedTurnId: first.params.expectedTurnId,
    });
    outbound(f, second);
    inbound(f, second, 'Ambiguous new body');
    f.advance();
    f.safety(first);
    f.safety(second);
    assert.equal(completedWrites(f).length, count, previous);
    assert.equal(f.current(second).state, 'unverified');
    assert.equal(f.current(second).result, null);
  }
});

test('only the observed native cancellation requestId cancels; Trace events are ignored', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const req = request();
  outbound(f, req);
  inbound(f, req);
  f.capture.observeInbound(
    JSON.stringify({ method: 'trace/cancel', params: { requestId: req.id } }),
  );
  assert.equal(f.current(req).state, 'pending');
  outbound(f, { id: 19, method: 'thread/sample/cancel', params: { requestId: req.id } });
  assert.equal(f.current(req).state, 'cancelled');
  f.safety(req);
  inbound(f, req);
  assert.equal(completedWrites(f).length, 0);
});

test('cancellation of a superseded request cannot revoke the newer source request', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const first = request();
  const second = request({ threadId: first.params.threadId });
  outbound(f, first);
  inbound(f, first);
  outbound(f, second);
  inbound(f, second, 'Current candidate');
  outbound(f, { method: 'thread/sample/cancel', params: { requestId: first.id } });
  f.safety(second);
  assert.equal(f.current(second).result.text, 'Current candidate');
});

test('suppression, failure, empty or invalid results retain only the store-owned extracted body', (t) => {
  for (const mode of ['suppressed', 'failed', 'empty', 'invalid']) {
    const f = setup();
    t.after(() => f.capture.close());
    const first = request();
    complete(f, first, 'Only preserved body');
    const next = request({ threadId: first.params.threadId });
    outbound(f, next);
    if (mode === 'failed') {
      f.capture.observeInbound(
        JSON.stringify({ id: next.id, error: { code: -1, message: 'synthetic' } }),
      );
    } else if (mode === 'invalid') {
      inbound(f, next, 'unused', { text: '{"suggestion":"x","extra":true}' });
    } else {
      inbound(f, next, mode === 'empty' ? null : 'Must stay private');
      f.safety(next, mode === 'suppressed');
    }
    const record = f.current(next);
    assert.equal(record.state, mode === 'invalid' ? 'unverified' : mode);
    assert.equal(record.result, null);
    assert.equal(record.latestExtracted.result.text, 'Only preserved body');
    assert.equal(JSON.stringify(record).split('Only preserved body').length - 1, 1);
    assert.equal(JSON.stringify(f.writes).includes('Must stay private'), false);
  }
});

test('exact result.text JSON rejects duplicate and escaped keys, extra keys and non-objects', (t) => {
  const texts = [
    '{"suggestion":"first","suggestion":"second"}',
    '{"suggestion":"first","\\u0073uggestion":"second"}',
    '{"suggestion":"x","responseId":null}',
    '{"suggestion":7}',
    '{"suggestion":true}',
    '{}',
    '[]',
    'null',
    '"body"',
    '{"suggestion":"x"} trailing',
    '{"suggestion":',
  ];
  for (const text of texts) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    inbound(f, req, 'unused', { text });
    f.safety(req);
    assert.equal(completedWrites(f).length, 0, text);
  }
});

test('null, whitespace, oversized bodies and response IDs never publish', (t) => {
  const values = [
    [null, {}],
    ['', {}],
    [' \n\t ', {}],
    ['x'.repeat(16_001), {}],
    ['界'.repeat(22_000), {}],
    ['body', { responseId: 'r'.repeat(513) }],
    ['body', { responseId: 10 }],
    ['body', { responseId: {} }],
  ];
  for (const [text, result] of values) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    inbound(f, req, text, result);
    f.safety(req);
    assert.equal(completedWrites(f).length, 0);
    assert.equal(f.current(req).result, null);
  }
});

test('absent, null and bounded string responseId are accepted without coercion', (t) => {
  for (const responseId of [undefined, null, 'r'.repeat(512)]) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    f.capture.observeInbound(
      JSON.stringify({
        id: req.id,
        result: { text: '{"suggestion":"body"}', responseId },
      }),
    );
    f.safety(req);
    assert.equal(f.current(req).result.responseId, responseId ?? null);
  }
});

test('escaped JSON strings and the exact 16000-character bound are accepted', (t) => {
  for (const text of ['Quoted "body", braces {} and a newline\ninside', 'x'.repeat(16_000)]) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    inbound(f, req, 'unused', { text: '{"\\u0073uggestion":' + JSON.stringify(text) + '}' });
    f.safety(req);
    assert.equal(f.current(req).result.text, text);
  }
});

test('repeated responses and source-conflicting IDs never reuse a candidate', (t) => {
  for (const mode of ['duplicate', 'collision']) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    inbound(f, req, 'Unsafely reusable candidate');
    if (mode === 'duplicate') inbound(f, req, 'A different response');
    if (mode === 'collision') outbound(f, { ...request(), id: req.id });
    f.safety(req);
    assert.equal(completedWrites(f).length, 0, mode);
  }
});

test('bad JSON, duplicate outer keys and oversized frames revoke unscreened candidates', (t) => {
  for (const direction of ['observeInbound', 'observeOutbound']) {
    for (const malformed of [
      '{"id":',
      '{"method":"ignored","method":"thread/sample"}',
      'x'.repeat(128 * 1024 + 1),
    ]) {
      const f = setup();
      t.after(() => f.capture.close());
      const req = request();
      outbound(f, req);
      inbound(f, req, 'Candidate');
      assert.doesNotThrow(() => f.capture[direction](malformed));
      f.safety(req);
      assert.equal(completedWrites(f).length, 0);
      assert.equal(f.current(req).state, 'unverified');
    }
  }
});

test('requests time out without traffic and the timeout cannot publish a retained candidate', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = setup();
  t.after(() => f.capture.close());
  const req = request();
  outbound(f, req);
  inbound(f, req);
  f.advance(timeout);
  t.mock.timers.tick(timeout);
  assert.equal(f.current(req).state, 'unverified');
  f.safety(req);
  assert.equal(completedWrites(f).length, 0);
});

test('the bounded epoch rejects overflow, revokes pending records and never falls back to old bodies', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const requests = Array.from({ length: 65 }, () => request());
  for (const req of requests) outbound(f, req);
  for (const req of requests) {
    inbound(f, req, 'Over-capacity body');
    f.safety(req);
  }
  assert.equal(completedWrites(f).length, 0);
  assert(requests.every((req) => f.current(req)?.state !== 'pending'));
  assert(f.errors.some((code) => /limit/.test(code)));
});

test('a store false/throw or read failure reports a code and disables the producer epoch', (t) => {
  for (const mode of ['false', 'throw', 'read']) {
    let calls = 0;
    const f = setup({
      save() {
        calls++;
        if (mode === 'throw') throw new Error('secret-body-must-not-be-reported');
        return false;
      },
      read:
        mode === 'read'
          ? () => {
              throw new Error('secret-key');
            }
          : undefined,
      onError() {
        throw new Error('Error reporting cannot escape the observer');
      },
    });
    t.after(() => f.capture.close());
    const req = request();
    assert.doesNotThrow(() => outbound(f, req));
    const before = calls;
    assert.doesNotThrow(() => {
      inbound(f, req);
      f.safety(req);
      outbound(f, request());
      f.capture.close();
    });
    assert.equal(calls, before);
    assert.equal(f.errors.length, 1);
    assert(f.errors.every((code) => /^[a-z_]+$/.test(code)));
  }
});

test('failure during a completed save disables the epoch without retrying or replaying input', (t) => {
  let attemptedCompletion = 0;
  const f = setup({
    save(record) {
      if (record.state === 'completed') {
        attemptedCompletion++;
        return false;
      }
      return true;
    },
  });
  t.after(() => f.capture.close());
  const req = request();
  complete(f, req);
  f.safety(req);
  inbound(f, req);
  outbound(f, request());
  assert.equal(attemptedCompletion, 1);
  assert.equal(f.errors.length, 1);
  assert.equal(f.current(req).state, 'pending');
});

test('a read failure after candidate receipt never publishes or retries that body', (t) => {
  let failReads = false;
  const f = setup({
    read(threadId, records) {
      if (failReads) throw new Error('private-storage-detail');
      return records.get(threadId);
    },
  });
  t.after(() => f.capture.close());
  const req = request();
  outbound(f, req);
  inbound(f, req);
  failReads = true;
  f.safety(req);
  failReads = false;
  f.safety(req);
  assert.equal(completedWrites(f).length, 0);
  assert.deepEqual(f.errors, ['native_sample_store_read_failed']);
});

test('a current record replaced by another owner/sequence cannot be overwritten', (t) => {
  for (const change of [
    (record) => ({ ...record, requestSequence: record.requestSequence + 10 }),
    (record) => ({ ...record, producer: { ...record.producer, pid: 999 } }),
    (record) => ({ ...record, targetKey: 'another-machine' }),
    (record) => ({ ...record, state: 'cancelled' }),
  ]) {
    const f = setup();
    t.after(() => f.capture.close());
    const req = request();
    outbound(f, req);
    inbound(f, req);
    const changed = change(f.current(req));
    f.records.set(req.params.threadId, changed);
    f.safety(req);
    assert.deepEqual(f.current(req), changed);
    assert.equal(completedWrites(f).length, 0);
  }
});

test('sequence resumes above an existing same-producer record without using a stored body', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const first = request();
  complete(f, first, 'Prior single body');
  f.records.set(first.params.threadId, { ...f.current(first), requestSequence: 90 });
  const next = request({ threadId: first.params.threadId });
  outbound(f, next);
  assert.equal(f.current(next).requestSequence, 91);
  assert.equal(f.current(next).result, null);
});

test('close revokes only pending leases, preserves extracted history and clears timers', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = setup();
  t.after(() => f.capture.close());
  const first = request();
  complete(f, first, 'Saved body survives close');
  const pending = request({ threadId: first.params.threadId });
  outbound(f, pending);
  inbound(f, pending, 'Unscreened body');
  f.capture.close();
  assert.equal(f.current(pending).state, 'unverified');
  assert.equal(f.current(pending).latestExtracted.result.text, 'Saved body survives close');
  const writes = f.writes.length;
  f.advance(timeout * 2);
  t.mock.timers.tick(timeout * 2);
  f.safety(pending);
  inbound(f, pending);
  outbound(f, request());
  f.capture.close();
  assert.equal(f.writes.length, writes);
});

test('the real store retains a single extracted body while suppressed and closed leases have no current body', async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'native-sample-store-')));
  let capture;
  t.after(async () => {
    capture?.close();
    await rm(root, { recursive: true, force: true });
  });
  await chmod(root, 0o700);
  protectWindowsStateDirectory(root);
  const store = new DesktopSuggestionStore(root, 'synthetic-native-target');
  let at = Date.now();
  const errors = [];
  capture = new DesktopNativeSampleCapture({
    store,
    targetKey: store.targetKey,
    producer: {
      epoch: randomUUID(),
      pid: process.pid,
      desktopPid: process.pid,
      desktopStartedAt: at - 100,
    },
    now: () => at,
    onError: (code) => errors.push(code),
  });
  const first = request();
  const observed = { capture };
  const decision = (req, suppressed) =>
    capture.observeSafetyDecision({
      sourceThreadId: req.params.threadId,
      sourceTurnId: req.params.expectedTurnId,
      at,
      suppressed,
      evidenceSha256: hash(`synthetic-evidence:${req.id}`),
    });
  outbound(observed, first);
  inbound(observed, first, 'Single synthetic extracted body');
  assert.equal(store.readRuntime(first.params.threadId).result, null);
  decision(first, false);
  assert.equal(store.readRuntime(first.params.threadId).state, 'completed');
  const second = request({ threadId: first.params.threadId });
  at++;
  outbound(observed, second);
  inbound(observed, second, 'Suppressed synthetic body');
  decision(second, true);
  assert.equal(store.readRuntime(first.params.threadId).state, 'suppressed');
  const third = request({ threadId: first.params.threadId });
  at++;
  outbound(observed, third);
  inbound(observed, third, 'Unscreened synthetic body');
  capture.close();
  const record = store.readRuntime(first.params.threadId);
  assert.equal(record.state, 'unverified');
  assert.equal(record.result, null);
  assert.equal(record.latestExtracted.result.text, 'Single synthetic extracted body');
  const files = await readdir(store.directory);
  assert.deepEqual(files, [first.params.threadId + '.json']);
  const persisted = await readFile(join(store.directory, files[0]), 'utf8');
  assert.equal(persisted.split('Single synthetic extracted body').length - 1, 1);
  assert.equal(persisted.includes('Suppressed synthetic body'), false);
  assert.equal(persisted.includes('Unscreened synthetic body'), false);
  assert.deepEqual(errors, []);
});

test('completed sequential requests keep working beyond the bounded metadata window', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const source = request();
  for (let n = 0; n < 260; n++) {
    f.advance(10);
    const req = request({
      threadId: source.params.threadId,
      expectedTurnId: source.params.expectedTurnId,
    });
    outbound(f, req);
    f.advance();
    inbound(f, req, `Body ${n}`);
    f.advance();
    f.safety(req);
    assert.equal(f.current(req).state, 'completed');
    assert.equal(f.current(req).result.text, `Body ${n}`);
  }
  assert.equal(f.capture.available, true);
  assert.equal(completedWrites(f).length, 260);
});

test('unknown responses and cancellations do not revoke an independently bound candidate', (t) => {
  const f = setup();
  t.after(() => f.capture.close());
  const req = request();
  outbound(f, req);
  inbound(f, req, 'Bound candidate');
  const unrelated = request();
  inbound(f, unrelated, 'Unrelated');
  outbound(f, { method: 'thread/sample/cancel', params: { requestId: unrelated.id } });
  f.safety(req);
  assert.equal(f.current(req).result.text, 'Bound candidate');
});
