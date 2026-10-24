import assert from 'node:assert/strict';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { channel } from 'node:diagnostics_channel';
import { join } from 'node:path';
import test from 'node:test';
import { preparedTraceFixture } from './fixtures/windows-prepared-trace.mjs';

function rewriteWithChangedMetadata(file, text) {
  const before = fs.statSync(file);
  fs.writeFileSync(file, text);
  // A same-size write within one filesystem clock tick may retain both times.
  // These cases exercise changed-metadata fences; same-stat rewrites have their
  // own full-body commit regression below.
  fs.utimesSync(file, before.atime, new Date(before.mtimeMs + 2000));
  assert.notEqual(fs.statSync(file).mtimeMs, before.mtimeMs);
}

function sameBundleHistory(f, count) {
  const older = Array.from({ length: count - 1 }, () =>
    f.fixture.addRequest({ recovery: true, idOverride: { predictionTurnId: randomUUID() } }),
  );
  if (older.length) {
    f.fixture.events.splice(-older.length);
    f.fixture.events.splice(2, 0, ...older.map((request) => request.event));
  }
  for (const [index, event] of f.fixture.events.entries()) {
    event.seq = index + 1;
    event.wall_time_unix_ms = event.codex_turn_id === f.fixture.predictionTurnId ? 2 : 1;
  }
  f.fixture.saveEvents();
}

function countEventDecodes(read) {
  const parse = JSON.parse;
  let events = 0;
  JSON.parse = function (...args) {
    const value = Reflect.apply(parse, this, args);
    if (value?.schema_version === 1 && Number.isSafeInteger(value.seq)) events++;
    return value;
  };
  try {
    return { value: read(), events };
  } finally {
    JSON.parse = parse;
  }
}

test('prepared parser diagnostics are opt-in, private, and add no filesystem/native observations', () =>
  preparedTraceFixture(
    ({ TraceSnapshot, reader, create, prepare, calls, canonical, catalogs, reset }) => {
      const f = create();
      f.fixture.events[0].diagnosticSecret = 'PRIVATE_PARSER_EVENT_BODY';
      f.fixture.saveEvents();
      const previous = process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
      delete process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
      const summary = channel('agent-hub.windows-trace.summary');
      const events = [];
      const receive = (event) => {
        if (Object.hasOwn(event, 'parserReadSnapshotCalls')) events.push(event);
      };
      const release = TraceSnapshot.prototype.releaseBodies;
      const snapshots = [];
      TraceSnapshot.prototype.releaseBodies = function () {
        snapshots.push(this);
        return release.call(this);
      };
      try {
        reset();
        const off = prepare(reader, f.fixture);
        assert(off);
        assert.equal(snapshots[0].diagnostic, undefined);
        const expected = off.commit();
        const observations = structuredClone({ calls, canonical, catalogs });
        reset();
        summary.subscribe(receive);
        const on = prepare(reader, f.fixture);
        assert(on);
        const guards = snapshots.at(-1).guards.length;
        assert.equal(events.length, 1, 'Prepare reports once, not inside a log/file loop');
        assert.equal(events[0].reason, 'ok');
        assert.equal(events[0].operation, 'parser');
        assert.equal(events[0].stage, 'prepared');
        assert.equal(events[0].parserReadSnapshotCalls, 2);
        assert.equal(events[0].parserCatalogWalks, catalogs.length);
        assert.equal(events[0].parserGuardCount, guards);
        assert(events[0].phases.parse.calls > 0);
        assert.deepEqual(on.commit(), expected);
        assert.deepEqual({ calls, canonical, catalogs }, observations);
        assert.equal(events.length, 2);
        assert.equal(events[1].operation, 'parser');
        assert.equal(events[1].stage, 'committed');
        for (const name of ['verify_inputs', 'verify', 'verify_inputs_final']) {
          assert.equal(events[1].phases[name].calls, 1);
          assert(Number.isFinite(events[1].phases[name].elapsedMs));
        }
        assert.equal(events[1].parserCatalogWalks, catalogs.length);
        assert.equal(events[1].parserGuardCount, guards);
        assert(!JSON.stringify(events).includes('PRIVATE_'));
        assert(!JSON.stringify(events).includes(f.fixture.directory));
        assert(!JSON.stringify(events).includes(f.fixture.predictionThreadId));
        assert.equal(on.commit(), null);
        on.dispose();
        assert.equal(events.length, 2, 'Consumed receipts cannot emit another result');
      } finally {
        summary.unsubscribe(receive);
        TraceSnapshot.prototype.releaseBodies = release;
        if (previous === undefined) delete process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
        else process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS = previous;
      }
    },
  ));

test('parse timing contains only decoding/index work and preserves thrown object identity', () =>
  preparedTraceFixture(
    ({ TraceSnapshot, reader, create, prepare, calls, canonical, catalogs, change, stat }) => {
      const f = create();
      const summary = channel('agent-hub.windows-trace.summary');
      const receive = () => {};
      summary.subscribe(receive);
      const decode = TraceSnapshot.prototype.decode;
      let depth = 0;
      TraceSnapshot.prototype.decode = function (body) {
        const counts = [calls.length, canonical.length, catalogs.length];
        depth++;
        try {
          return decode.call(this, body);
        } finally {
          depth--;
          assert.deepEqual([calls.length, canonical.length, catalogs.length], counts);
        }
      };
      change(() => assert.equal(depth, 0, 'Native I/O must not be charged to parse'));
      stat((_path, value) => {
        assert.equal(depth, 0, 'Filesystem I/O must not be charged to parse');
        return value;
      });
      try {
        const pending = prepare(reader, f.fixture);
        assert(pending);
        assert.equal(pending.commit()?.length, 1);
        const snapshot = new TraceSnapshot(true);
        const value = { secret: 'PRIVATE_RESULT' };
        assert.equal(
          snapshot.decode(() => value),
          value,
        );
        const failure = new Error('PRIVATE_ORIGINAL_ERROR');
        assert.throws(
          () =>
            snapshot.decode(() => {
              throw failure;
            }),
          (error) => error === failure,
        );
        snapshot.dispose();
      } finally {
        summary.unsubscribe(receive);
        TraceSnapshot.prototype.decode = decode;
      }
    },
  ));

test('prepared parser failures, disposal, and nonprepared reads retain their original behavior with diagnostics', () =>
  preparedTraceFixture(({ reader, root, create, prepare, data, calls }) => {
    const f = create();
    const summary = channel('agent-hub.windows-trace.summary');
    const events = [];
    const receive = (event) => {
      if (Object.hasOwn(event, 'parserReadSnapshotCalls')) events.push(event);
    };
    summary.subscribe(receive);
    try {
      assert.equal(
        reader.readPrediction(f.fixture.predictionThreadId, f.fixture.predictionTurnId)?.text,
        'Synthetic follow-up suggestion.',
      );
      assert.equal(events.length, 0, 'Nonprepared reads never create parser diagnostics');
      const pending = prepare(reader, f.fixture);
      assert(pending);
      fs.appendFileSync(f.request.requestPath, ' ');
      assert.equal(pending.commit(), null);
      assert.equal(events.at(-1).reason, 'native_rejected');
      const count = calls.length;
      assert.equal(pending.commit(), null);
      assert.equal(calls.length, count);
      const invalid = create();
      data.writeJson(invalid.request.requestPath, {});
      assert.equal(prepare(reader, invalid.fixture), null);
      assert.equal(events.at(-1).reason, 'native_rejected');
      const discarded = create();
      const receipt = prepare(reader, discarded.fixture);
      assert(receipt);
      receipt.dispose();
      assert.equal(events.at(-1).reason, 'aborted');
      const reports = events.length;
      receipt.dispose();
      assert.equal(receipt.commit(), null);
      assert.equal(events.length, reports);
      assert(
        events.every((event) =>
          Object.keys(event).every((key) => !['text', 'path', 'error', 'target'].includes(key)),
        ),
      );
      assert(!JSON.stringify(events).includes(root));
    } finally {
      summary.unsubscribe(receive);
    }
  }));

for (const count of [1, 4, 16])
  test(`request-local index decodes each log event once for ${count} inferences, retaining every guard`, (t) =>
    preparedTraceFixture(({ TraceSnapshot, reader, root, create, prepare }) => {
      const f = create();
      sameBundleHistory(f, count);
      const lengths = [];
      const verify = TraceSnapshot.prototype.verify;
      TraceSnapshot.prototype.verify = function (...args) {
        lengths.push(this.guards.length);
        return Reflect.apply(verify, this, args);
      };
      const captures = [];
      const release = TraceSnapshot.prototype.releaseBodies;
      TraceSnapshot.prototype.releaseBodies = function () {
        captures.push(this);
        return release.call(this);
      };
      const legacy = countEventDecodes(() =>
        reader.readSourcePredictions(
          f.fixture.sourceThreadId,
          f.fixture.sourceTurnId,
          root,
          0,
          true,
        ),
      );
      const indexed = countEventDecodes(() => prepare(reader, f.fixture));
      assert(indexed.value);
      assert.deepEqual(indexed.value.commit(), legacy.value);
      const captured = captures[0];
      const lines = f.fixture.events.length;
      assert.equal(indexed.events, lines + 2, 'One full decode plus original discovery prefix');
      assert.equal(legacy.events, (count + 2) * lines + 2);
      assert.equal(lengths.length, 2);
      assert.equal(
        lengths[1],
        lengths[0],
        'Parsing reuse must not remove native proof obligations',
      );
      assert.equal(captured.preparedLogs.size, 0, 'No parsed private log survives body release');
      assert.equal(captured.nativeFiles.size, 0);
      t.diagnostic(
        JSON.stringify({
          inferences: count,
          lines,
          legacyEventDecodes: legacy.events,
          indexedEventDecodes: indexed.events,
          guards: lengths[1],
        }),
      );
    }));

test('a later same-thread bundle stays indexed and charged after each binding budget is released', () =>
  preparedTraceFixture(({ TraceSnapshot, reader, root, local, create, prepare, data }) => {
    const f = create();
    sameBundleHistory(f, 4);
    const other = create(
      data.execution({
        predictionThreadId: f.fixture.predictionThreadId,
        sourceThreadId: f.fixture.sourceThreadId,
        sourceTurnId: f.fixture.sourceTurnId,
      }),
    );
    other.fixture.events.splice(3);
    // Discovery cannot classify this oversized first line; binding still reads
    // the same-thread bundle under all original manifest/full-body receipts.
    other.fixture.events[0].padding = 'x'.repeat(20000);
    for (const event of other.fixture.events) event.wall_time_unix_ms = 1;
    other.fixture.saveEvents();
    const legacy = reader.readSourcePredictions(
      f.fixture.sourceThreadId,
      f.fixture.sourceTurnId,
      root,
      0,
      true,
    );
    assert.equal(legacy?.length, 1);
    const release = TraceSnapshot.prototype.releaseBodies;
    let retained;
    TraceSnapshot.prototype.releaseBodies = function () {
      retained ??= {
        logs: this.preparedLogs.size,
        bytes: this.bytes,
        paths: [...this.preparedLogs.keys()],
      };
      return release.call(this);
    };
    const indexed = countEventDecodes(() => prepare(reader, f.fixture));
    assert.deepEqual(indexed.value?.commit(), legacy);
    assert.equal(indexed.events, f.fixture.events.length + other.fixture.events.length + 2);
    assert.equal(retained.logs, 2);
    assert(
      retained.bytes >=
        retained.paths.reduce((sum, path) => sum + fs.statSync(local(path)).size, 0),
    );
  }));

test('binding accepts a single line BOM in a same-thread bundle beyond the discovery prefix', () =>
  preparedTraceFixture(({ reader, root, create, prepare, data }) => {
    const a = create();
    const b = create(
      data.execution({
        predictionThreadId: a.fixture.predictionThreadId,
        sourceThreadId: a.fixture.sourceThreadId,
        sourceTurnId: a.fixture.sourceTurnId,
      }),
    );
    b.fixture.events.splice(3);
    b.fixture.events[0].padding = 'x'.repeat(20000);
    b.fixture.saveEvents();
    fs.writeFileSync(b.fixture.logPath, '\uFEFF' + fs.readFileSync(b.fixture.logPath, 'utf8'));
    const firstLineBytes = fs.readFileSync(b.fixture.logPath).indexOf(10);
    assert(firstLineBytes > 16384 && firstLineBytes < 1024 * 1024);
    const legacy = reader.readSourcePredictions(
      a.fixture.sourceThreadId,
      a.fixture.sourceTurnId,
      root,
      0,
      true,
    );
    assert.equal(legacy?.length, 1, 'Legacy per-line binding decoder removes the single BOM');
    const pending = prepare(reader, a.fixture);
    assert(pending, 'Prepared binding must preserve the legacy accepted result');
    assert.deepEqual(pending.commit(), legacy);
  }));

test('a binding-cached BOM record still fails a later classified bundle full discovery', () =>
  preparedTraceFixture(({ reader, root, remote, create, prepare, data }) => {
    const first = create();
    const second = create(
      data.execution({
        predictionThreadId: first.fixture.predictionThreadId,
        sourceThreadId: first.fixture.sourceThreadId,
        sourceTurnId: first.fixture.sourceTurnId,
      }),
    );
    // Match the parser's directory-name ordering so A binds before B discovery.
    const [a, b] = [first, second].sort((left, right) =>
      left.fixture.directory < right.fixture.directory ? -1 : 1,
    );
    for (const event of a.fixture.events) event.wall_time_unix_ms = 2;
    a.fixture.saveEvents();
    b.fixture.events.splice(3);
    b.fixture.saveEvents();
    const lines = fs.readFileSync(b.fixture.logPath, 'utf8').split('\n');
    // The configured record at index 1 is classified first; the BOM is later.
    lines[2] = '\uFEFF' + lines[2];
    fs.writeFileSync(b.fixture.logPath, lines.join('\n'));
    assert(Buffer.byteLength(lines.slice(0, 2).join('\n') + '\n') < 16384);
    assert.equal(
      reader.readSourcePredictions(a.fixture.sourceThreadId, a.fixture.sourceTurnId, root, 0, true),
      null,
      'Legacy full discovery rejects the BOM even though binding can decode it',
    );
    const read = reader.readSnapshot;
    let boundA = false,
      cachedBom = false,
      discoveryReachedCachedBom = false;
    reader.readSnapshot = function (...args) {
      const result = Reflect.apply(read, this, args);
      if (!args[2] && args[1] === a.fixture.predictionTurnId) {
        boundA = !!result?.binding;
        const indexed = args[4]?.preparedLogs.get(remote(b.fixture.logPath))?.index;
        cachedBom = indexed?.decoded.has(2) === true;
        if (indexed) {
          const eventAt = indexed.eventAt;
          indexed.eventAt = function (index, binding = false) {
            if (index === 2 && !binding) discoveryReachedCachedBom = true;
            return eventAt.call(this, index, binding);
          };
        }
      }
      return result;
    };
    const pending = prepare(reader, a.fixture);
    try {
      assert.equal(pending, null, 'A cached binding must not widen discovery acceptance');
      assert(boundA, 'A binding must finish before the classified B discovery');
      assert(cachedBom, 'B BOM record must have been decoded and cached by A binding');
      assert(discoveryReachedCachedBom, 'B full discovery must reject its cached BOM record');
    } finally {
      pending?.dispose();
    }
  }));

test('a second prepared API read must decode its own log again', () =>
  preparedTraceFixture(({ reader, create, prepare }) => {
    const f = create();
    sameBundleHistory(f, 4);
    for (let i = 0; i < 2; i++) {
      const read = countEventDecodes(() => prepare(reader, f.fixture));
      assert.equal(read.events, f.fixture.events.length + 2);
      assert.equal(read.value.commit()?.length, 1);
    }
  }));

test(
  'macOS source recovery keeps its original decoder and POSIX guard path',
  { skip: process.platform !== 'darwin' },
  () =>
    preparedTraceFixture(({ reader, root, create, calls }) => {
      const f = create();
      sameBundleHistory(f, 4);
      const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
      Object.defineProperty(process, 'platform', { value: 'darwin' });
      try {
        const read = countEventDecodes(() =>
          reader.readSourcePredictions(
            f.fixture.sourceThreadId,
            f.fixture.sourceTurnId,
            root,
            0,
            true,
          ),
        );
        assert.equal(read.value?.length, 1);
        assert.equal(read.events, 6 * f.fixture.events.length + 2);
        assert.equal(calls.length, 0, 'No synthetic Windows Snapshot in the Mac path');
      } finally {
        Object.defineProperty(process, 'platform', descriptor);
      }
    }),
);

test('same-turn retries remain ambiguous even though their log is decoded once', () =>
  preparedTraceFixture(({ reader, create, prepare }) => {
    const f = create();
    for (let i = 0; i < 7; i++) f.fixture.addRequest({ recovery: true });
    const indexed = countEventDecodes(() => prepare(reader, f.fixture));
    assert.equal(indexed.value, null);
    assert.equal(indexed.events, f.fixture.events.length + 2);
  }));

for (const mutation of ['newer-incomplete', 'new-bundle', 'request-source', 'acl'])
  test(`index cannot hide ${mutation} after a binding was resolved`, () =>
    preparedTraceFixture(({ reader, root, local, create, prepare, data, changeAcl }) => {
      const f = create();
      sameBundleHistory(f, 4);
      const read = reader.readSnapshot;
      let changed = false;
      reader.readSnapshot = function (...args) {
        const result = Reflect.apply(read, this, args);
        if (!args[2] && !changed) {
          changed = true;
          if (mutation === 'newer-incomplete') {
            const request = f.fixture.addRequest({
              recovery: true,
              idOverride: { predictionTurnId: randomUUID() },
            });
            request.event.wall_time_unix_ms = 3;
            f.fixture.saveEvents();
          } else if (mutation === 'new-bundle')
            data.bundle(
              local(root),
              data.execution({ predictionThreadId: f.fixture.predictionThreadId }),
            );
          else if (mutation === 'request-source') {
            const metadata = JSON.parse(f.request.request.client_metadata['x-codex-turn-metadata']);
            metadata.prediction_source_turn_id = randomUUID();
            f.request.request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(metadata);
            data.writeJson(f.request.requestPath, f.request.request);
          } else changeAcl();
        }
        return result;
      };
      const pending = prepare(reader, f.fixture);
      assert.equal(pending?.commit() ?? null, null);
      assert(changed);
      if (mutation === 'newer-incomplete') assert.equal(prepare(reader, f.fixture), null);
    }));

test('same-stat log rewrite is caught by commit full-body guards even after indexed reuse', () =>
  preparedTraceFixture(({ reader, create, prepare, stat, remote }) => {
    const f = create();
    sameBundleHistory(f, 4);
    const before = fs.lstatSync(f.fixture.logPath, { bigint: true });
    stat((path, value) => (path === remote(f.fixture.logPath) ? before : value));
    const read = reader.readSnapshot;
    let changed = false;
    reader.readSnapshot = function (...args) {
      const result = Reflect.apply(read, this, args);
      if (!args[2] && !changed) {
        changed = true;
        const bytes = fs.readFileSync(f.fixture.logPath);
        const at = bytes.indexOf(Buffer.from('"rollout_started"'));
        assert(at >= 0);
        bytes[at + 1] = 'R'.charCodeAt(0);
        fs.writeFileSync(f.fixture.logPath, bytes);
      }
      return result;
    };
    const pending = prepare(reader, f.fixture);
    assert(pending);
    assert.equal(pending.commit(), null);
    assert(changed);
  }));

test('nonprepared reads retain their original decode path and do not retain an index', () =>
  preparedTraceFixture(({ TraceSnapshot, reader, root, create }) => {
    const f = create();
    sameBundleHistory(f, 4);
    const verify = TraceSnapshot.prototype.verify;
    let indices;
    TraceSnapshot.prototype.verify = function (...args) {
      indices = this.preparedLogs.size;
      return Reflect.apply(verify, this, args);
    };
    const result = countEventDecodes(() =>
      reader.readSourcePredictions(f.fixture.sourceThreadId, f.fixture.sourceTurnId, root, 0, true),
    );
    assert.equal(result.value?.length, 1);
    assert.equal(result.events, 6 * f.fixture.events.length + 2);
    assert.equal(indices, 0);
  }));

test(
  'Windows Snapshot reuses only scratch, retaining independent before/after native proofs',
  { skip: process.platform !== 'win32' },
  async () => {
    const { windowsTraceScratchFixture } =
      await import('./fixtures/windows-trace-snapshot-scratch.mjs');
    await windowsTraceScratchFixture();
  },
);

test('opaque prepare defers final verify, drops bodies, commits once without parsing another projection', () =>
  preparedTraceFixture(
    ({ TraceSnapshot, reader, root, create, prepare, remote, calls, canonical, reset }) => {
      const f = create();
      const expected = reader.readSourcePredictions(
        f.fixture.sourceThreadId,
        f.fixture.sourceTurnId,
        root,
        0,
        true,
      );
      const captures = [];
      const release = TraceSnapshot.prototype.releaseBodies;
      TraceSnapshot.prototype.releaseBodies = function () {
        release.call(this);
        captures.push(this);
      };
      reset();
      const pending = prepare(reader, f.fixture);
      const captured = captures[0];
      assert(pending);
      assert.deepEqual(Object.keys(pending).sort(), ['commit', 'dispose']);
      assert.equal(JSON.stringify(pending), '{}');
      assert.equal(captured.nativeFiles.size, 0);
      assert.equal(captured.bytes, 0);
      assert(captured.guards.some((guard) => guard.body));
      const request = remote(f.request.requestPath);
      assert.equal(
        canonical.filter((path) => path === request).length,
        0,
        'No final file verify during prepare',
      );
      reset();
      assert.deepEqual(pending.commit(), expected);
      const snapshots = calls.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
      const requestReads = snapshots.filter((item) => item.path === request);
      assert(requestReads.length > 0);
      assert(
        requestReads.every((item) => item.maximum === fs.statSync(f.request.requestPath).size),
      );
      assert.equal(canonical.filter((path) => path === request).length, requestReads.length * 3);
      assert.equal(captured.guards.length, 0);
      assert.equal(captured.catalogs.size, 0);
      const count = calls.length;
      assert.equal(pending.commit(), null);
      pending.dispose();
      pending.dispose();
      assert.equal(calls.length, count);
    },
  ));

test('dispose is idempotent; valid empty commits [], while failed/unsupported prepare is null', () =>
  preparedTraceFixture(({ reader, root, create, prepare, reset, calls }) => {
    const empty = reader.prepareSourcePredictions(randomUUID(), randomUUID(), root);
    assert.deepEqual(empty.commit(), []);
    const f = create(),
      pending = prepare(reader, f.fixture);
    reset();
    pending.dispose();
    pending.dispose();
    assert.equal(pending.commit(), null);
    assert.equal(calls.length, 0);
    assert.equal(reader.prepareSourcePredictions('invalid', randomUUID(), root), null);
    const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
    Object.defineProperty(process, 'platform', { value: 'darwin' });
    try {
      assert.equal(prepare(reader, f.fixture), null);
    } finally {
      Object.defineProperty(process, 'platform', descriptor);
    }
  }));

for (const field of ['request', 'response', 'manifest', 'configured', 'terminal', 'log'])
  test(`commit rejects changed full ${field}, without retry or old projection`, () =>
    preparedTraceFixture(({ reader, create, prepare, reset, calls }) => {
      const f = create();
      const pending = prepare(reader, f.fixture);
      assert(pending);
      const path = {
        request: f.request.requestPath,
        response: f.completion.responsePath,
        manifest: join(f.fixture.directory, 'manifest.json'),
        configured: f.configured.path,
        terminal: f.terminal.path,
        log: f.fixture.logPath,
      }[field];
      const bytes = fs.readFileSync(path);
      bytes[bytes.length - 2] ^= 1;
      fs.writeFileSync(path, bytes);
      reset();
      assert.equal(pending.commit(), null);
      const count = calls.length;
      assert.equal(pending.commit(), null);
      assert.equal(calls.length, count);
    }));

test('full SHA is compared against a fresh native body even when synthetic Node timestamps appear unchanged', () =>
  preparedTraceFixture(({ reader, create, prepare, stat, remote, reset, calls }) => {
    const f = create(),
      path = remote(f.request.requestPath),
      before = fs.lstatSync(f.request.requestPath, { bigint: true });
    const pending = prepare(reader, f.fixture);
    assert(pending);
    const bytes = fs.readFileSync(f.request.requestPath);
    bytes[bytes.length - 2] ^= 1;
    fs.writeFileSync(f.request.requestPath, bytes);
    stat((candidate, value) => (candidate === path ? before : value));
    reset();
    assert.equal(pending.commit(), null);
    assert(
      calls
        .flatMap((call) => (call.kind === 'B' ? call.items : [call]))
        .some((item) => item.path === path && item.maximum === bytes.length),
    );
  }));

test('full receipt requires fresh sameFile after native return, not only same inode', () =>
  preparedTraceFixture(({ reader, create, prepare, remote, change }) => {
    const f = create(),
      path = remote(f.completion.responsePath),
      pending = prepare(reader, f.fixture);
    assert(pending);
    let injected = false;
    change((when, candidate, maximum) => {
      if (when === 'after' && candidate === path && maximum > 1 && !injected) {
        const bytes = fs.readFileSync(f.completion.responsePath);
        bytes[bytes.length - 2] ^= 1;
        fs.writeFileSync(f.completion.responsePath, bytes);
        injected = true;
      }
    });
    assert.equal(pending.commit(), null);
    assert.equal(injected, true);
  }));

for (const mode of ['new-bundle', 'thread-filter-bad-name', 'replace-bundle'])
  test(`catalog/identity fence: ${mode}`, () =>
    preparedTraceFixture(({ reader, root, local, create, prepare, data }) => {
      const f = create(),
        pending = prepare(reader, f.fixture);
      assert(pending);
      if (mode === 'new-bundle') data.bundle(local(root));
      else if (mode === 'thread-filter-bad-name')
        fs.mkdirSync(join(local(root), 'nonsense-' + f.fixture.predictionThreadId), {
          mode: 0o700,
        });
      else {
        const before = fs.lstatSync(f.fixture.directory).ino;
        fs.renameSync(f.fixture.directory, f.fixture.directory + '-retained');
        fs.mkdirSync(f.fixture.directory, { mode: 0o700 });
        assert.notEqual(fs.lstatSync(f.fixture.directory).ino, before);
      }
      assert.equal(pending.commit(), null);
    }));

test('catalogs are also checked after all native confirms, not only before', () =>
  preparedTraceFixture(({ reader, root, local, create, prepare, remote, change, data }) => {
    const f = create(),
      pending = prepare(reader, f.fixture);
    assert(pending);
    let injected = false;
    change((when, path) => {
      if (when === 'after' && path === remote(f.terminal.path) && !injected) {
        data.bundle(local(root));
        injected = true;
      }
    });
    assert.equal(pending.commit(), null);
    assert.equal(injected, true);
  }));

for (const mode of ['short-grow', 'window-rewrite', 'closed-tail-append', 'full-body-rewrite'])
  test(`a later body confirm cannot hide an earlier input mutation: ${mode}`, () =>
    preparedTraceFixture(
      ({ reader, TraceSnapshot, root, local, remote, create, prepare, data, change }) => {
        const a = create(),
          b = data.bundle(local(root));
        if (mode === 'closed-tail-append')
          b.protocolEvent('session_configured', {
            type: 'session_configured',
            thread_source: 'vscode',
          });
        if (mode === 'window-rewrite') fs.appendFileSync(b.logPath, ' '.repeat(20000));
        const pending = prepare(reader, a.fixture);
        assert(pending);
        let prefixConfirmed = false,
          injected = false;
        const original = TraceSnapshot.prototype.nativeGuard;
        TraceSnapshot.prototype.nativeGuard = function (guard, ...args) {
          const result = Reflect.apply(original, this, [guard, ...args]);
          if (guard.path === remote(b.logPath)) prefixConfirmed = true;
          return result;
        };
        change((when, path) => {
          if (when !== 'after' || path !== remote(a.terminal.path) || !prefixConfirmed || injected)
            return;
          injected = true;
          if (mode === 'short-grow' || mode === 'closed-tail-append')
            fs.appendFileSync(b.logPath, 'NEW-TAIL');
          else {
            const file = mode === 'window-rewrite' ? b.logPath : a.request.requestPath;
            const bytes = fs.readFileSync(file);
            bytes[1] ^= 1;
            fs.writeFileSync(file, bytes);
          }
        });
        const result = pending.commit();
        assert.equal(prefixConfirmed, true);
        assert.equal(injected, true);
        if (mode === 'closed-tail-append') assert.equal(result?.length, 1);
        else assert.equal(result, null);
      },
    ));

test('configured cwd retarget outside authorized root is rejected with unchanged payload and leaf data', () =>
  preparedTraceFixture(({ reader, root, local, remote, create, prepare, data }) => {
    const f = create(),
      inside = join(local(root), 'inside'),
      alias = join(local(root), 'cwd-alias'),
      outside = local('C:\\fixture\\outside');
    fs.mkdirSync(inside);
    fs.mkdirSync(outside);
    fs.symlinkSync(inside, alias, 'junction');
    f.configured.value.cwd = remote(alias);
    data.writeJson(f.configured.path, f.configured.value);
    const pending = prepare(reader, f.fixture);
    assert(pending);
    const bytes = fs.readFileSync(f.configured.path);
    fs.unlinkSync(alias);
    fs.symlinkSync(outside, alias, 'junction');
    assert.deepEqual(fs.readFileSync(f.configured.path), bytes);
    assert.equal(pending.commit(), null);
  }));

test('negative short prefix growth into a newer incomplete prediction cannot revive old suggestion', () =>
  preparedTraceFixture(({ reader, root, local, create, prepare, data }) => {
    const a = create(),
      ids = data.execution({
        sourceThreadId: a.fixture.sourceThreadId,
        sourceTurnId: a.fixture.sourceTurnId,
      });
    const b = data.bundle(local(root), ids),
      pending = prepare(reader, a.fixture);
    assert(pending);
    b.protocolEvent('session_configured', {
      type: 'session_configured',
      thread_id: ids.predictionThreadId,
      thread_source: 'composer_predictions',
      forked_from_id: ids.sourceThreadId,
      cwd: root,
    });
    b.addRequest({ recovery: true });
    for (const event of b.events) event.wall_time_unix_ms += 20;
    b.saveEvents();
    assert.equal(pending.commit(), null);
    assert.equal(
      reader.readSourcePredictions(a.fixture.sourceThreadId, a.fixture.sourceTurnId, root, 0, true),
      null,
      'Original second projection also rejects new incomplete latest',
    );
  }));

for (const classified of [false, true])
  test(`unrelated ${classified ? 'closed configured' : 'full 16KiB window'} permits outside-window append`, () =>
    preparedTraceFixture(({ reader, root, local, create, prepare, data }) => {
      const a = create(),
        b = data.bundle(local(root));
      if (classified)
        b.protocolEvent('session_configured', {
          type: 'session_configured',
          thread_source: 'vscode',
        });
      else fs.appendFileSync(b.logPath, ' '.repeat(20000));
      const pending = prepare(reader, a.fixture);
      assert(pending);
      fs.appendFileSync(b.logPath, 'APPENDED-UNRELATED-TAIL');
      const result = pending.commit();
      assert.equal(result?.length, 1);
      assert.equal(result[0].binding.predictionThreadId, a.fixture.predictionThreadId);
    }));

test('a matching full log remains strict even after its discovery prefix was classified', () =>
  preparedTraceFixture(({ reader, create, prepare }) => {
    const f = create(),
      pending = prepare(reader, f.fixture);
    assert(pending);
    fs.appendFileSync(f.fixture.logPath, '\n');
    assert.equal(pending.commit(), null);
  }));

test('fresh native chain/ACL proof is not cached across prepare/commit', () =>
  preparedTraceFixture(({ reader, create, prepare, changeAcl }) => {
    const f = create(),
      pending = prepare(reader, f.fixture);
    assert(pending);
    changeAcl();
    assert.equal(pending.commit(), null);
  }));

test('same-leaf ancestor retarget after prefix handle closes is rejected by the final canonical guard', () =>
  preparedTraceFixture(
    ({ reader, TraceSnapshot, root, local, remote, create, prepare, data, change }) => {
      const a = create(),
        b = data.bundle(local(root));
      b.protocolEvent('session_configured', {
        type: 'session_configured',
        thread_source: 'vscode',
      });
      const pending = prepare(reader, a.fixture);
      assert(pending);
      let appended = false;
      change((when, path) => {
        if (when === 'after' && path === remote(b.logPath) && !appended) {
          fs.appendFileSync(b.logPath, 'APPEND');
          appended = true;
        }
      });
      const original = TraceSnapshot.prototype.prefixAfterBatch;
      let injected = false;
      TraceSnapshot.prototype.prefixAfterBatch = function (...args) {
        const value = Reflect.apply(original, this, args);
        if (!injected) {
          const anchor = local('C:\\fixture'),
            retained = anchor + '-retained';
          const before = fs.lstatSync(b.logPath, { bigint: true });
          fs.renameSync(anchor, retained);
          fs.symlinkSync(retained, anchor, 'junction');
          const after = fs.lstatSync(b.logPath, { bigint: true });
          assert.equal(after.ino, before.ino);
          assert.equal(after.dev, before.dev);
          injected = true;
        }
        return value;
      };
      assert.equal(pending.commit(), null);
      assert.equal(appended, true);
      assert.equal(injected, true);
    },
  ));

test('3 x 22MiB historical bodies leave receipts, not live caches, and use original single limit', () =>
  preparedTraceFixture(({ reader, create, prepare, data, calls, remote, reset }) => {
    const first = create();
    const history = [
      first,
      create(data.execution({ sourceThreadId: first.fixture.sourceThreadId })),
      create(data.execution({ sourceThreadId: first.fixture.sourceThreadId })),
    ];
    for (let i = 0; i < history.length; i++) {
      const f = history[i];
      f.request.request.padding = '';
      f.request.request.padding = 'x'.repeat(
        22 * 1024 * 1024 - Buffer.byteLength(JSON.stringify(f.request.request)),
      );
      data.writeJson(f.request.requestPath, f.request.request);
      for (const event of f.fixture.events) event.wall_time_unix_ms += i * 10;
      f.fixture.saveEvents();
    }
    const pending = prepare(reader, history.at(-1).fixture);
    assert(pending);
    reset();
    const result = pending.commit();
    assert.equal(result?.length, 1);
    for (const f of history)
      assert(
        calls.some(
          (call) =>
            call.kind === 'S' &&
            call.path === remote(f.request.requestPath) &&
            call.maximum === 22 * 1024 * 1024,
        ),
      );
    assert(
      calls
        .filter((call) => call.kind === 'B')
        .every((call) => call.items.every((item) => item.maximum <= 16384)),
    );
    const next = prepare(reader, history.at(-1).fixture);
    assert(next);
    fs.appendFileSync(history[0].request.requestPath, ' ');
    assert.equal(next.commit(), null);
  }));

test('prepared still rejects more than 64MiB of distinct request bytes within one binding', () =>
  preparedTraceFixture(({ reader, create, prepare, data }) => {
    const f = create();
    for (const request of [
      f.request,
      f.fixture.addRequest({ recovery: true }),
      f.fixture.addRequest({ recovery: true }),
    ]) {
      request.request.padding = '';
      request.request.padding = 'x'.repeat(
        22 * 1024 * 1024 - Buffer.byteLength(JSON.stringify(request.request)),
      );
      data.writeJson(request.requestPath, request.request);
    }
    assert.equal(prepare(reader, f.fixture), null);
  }));

for (const failure of ['failed', 'incomplete', 'empty'])
  test(`latest ${failure} preserves original no-fallback decision`, () =>
    preparedTraceFixture(({ reader, root, create, prepare, data }) => {
      const a = create(),
        b = create(
          data.execution({
            sourceThreadId: a.fixture.sourceThreadId,
            sourceTurnId: a.fixture.sourceTurnId,
          }),
        );
      for (const event of b.fixture.events) event.wall_time_unix_ms += 10;
      if (failure === 'failed')
        b.fixture.events.find((event) => event.payload.type === 'codex_turn_ended').payload.status =
          'failed';
      if (failure === 'incomplete')
        b.fixture.events.splice(
          b.fixture.events.findIndex((event) => event.payload.type === 'inference_completed'),
        );
      if (failure === 'empty') {
        b.completion.response.output_items[1].content[0].text = '{"suggestion":""}';
        b.terminal.value.last_agent_message = '{"suggestion":""}';
        data.writeJson(b.completion.responsePath, b.completion.response);
        data.writeJson(b.terminal.path, b.terminal.value);
      }
      b.fixture.saveEvents();
      const expected = reader.readSourcePredictions(
        a.fixture.sourceThreadId,
        a.fixture.sourceTurnId,
        root,
        0,
        true,
      );
      const pending = prepare(reader, a.fixture);
      assert.deepEqual(pending?.commit() ?? null, expected);
      if (expected)
        assert.notEqual(expected[0].binding.predictionThreadId, a.fixture.predictionThreadId);
    }));

test(
  'real Windows C# single reads commit fresh full receipts without touching desktop services',
  { skip: process.platform !== 'win32' },
  async () => {
    const { DesktopPredictionTraceReader } =
      await import('../packages/codex-adapter/dist/desktop-prediction-trace.js');
    const { setup, recoverableFixture } = await import('./fixtures/desktop-trace.mjs');
    const { closeWindowsPrivateStateBackend } =
      await import('../packages/codex-adapter/dist/desktop-platform.js');
    try {
      setup((root) => {
        const f = recoverableFixture(root),
          reader = new DesktopPredictionTraceReader(root);
        const prepare = () =>
          reader.prepareSourcePredictions(
            f.fixture.sourceThreadId,
            f.fixture.sourceTurnId,
            root,
            0,
            true,
          );
        const pending = prepare();
        assert(pending);
        assert.equal(
          pending.commit()?.[0].binding.predictionThreadId,
          f.fixture.predictionThreadId,
        );
        const changed = prepare();
        assert(changed);
        fs.appendFileSync(f.request.requestPath, ' ');
        assert.equal(changed.commit(), null);
        assert.equal(changed.commit(), null);
      });
    } finally {
      closeWindowsPrivateStateBackend();
    }
  },
);

test('layered discovery keeps every native item/result while reducing ordered S round trips to bounded B8', () =>
  preparedTraceFixture(({ reader, root, local, remote, create, calls, batch, reset }) => {
    const values = Array.from({ length: 8 }, () => create());
    const target = values[0].fixture;
    const read = () =>
      reader.prepareSourcePredictions(target.sourceThreadId, target.sourceTurnId, root, 1, true);
    batch(false);
    reset();
    const scalar = read();
    assert(scalar);
    const scalarCalls = structuredClone(calls);
    const expected = scalar.commit();
    assert.equal(expected?.length, 1);
    batch(true);
    reset();
    const pending = read();
    assert(pending);
    const planned = structuredClone(calls);
    const items = planned.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
    assert(planned.some((call) => call.kind === 'B'));
    assert(
      planned.every(
        (call) =>
          call.kind !== 'B' ||
          (call.items.length >= 2 &&
            call.items.length <= 8 &&
            call.items.every((item) => item.maximum >= 0 && item.maximum <= 16_384)),
      ),
    );
    assert(planned.some((call) => call.kind === 'B' && call.items.length === 8));
    const payloadDirectories = values.map((value) =>
      remote(join(value.fixture.directory, 'payloads')),
    );
    for (const directory of payloadDirectories)
      assert(
        items.some((item) => item.maximum > 0 && item.path.startsWith(directory + '\\')),
        'Each discovery parent is covered by a fresh full-chain file read',
      );
    assert.equal(
      items.length,
      scalarCalls.length,
      'Scalar and batch retain identical native items',
    );
    // S carries its domain cap; B carries min(stat.size, cap), with max1 for an
    // empty file and max0 only for a directory. Compare effective read obligations
    // without mutating raw calls or discarding duplicate (path, maximum) tuples.
    const obligations = (values) =>
      values
        .map(({ path, maximum }) =>
          JSON.stringify([
            path,
            maximum === 0 ? 0 : Math.max(1, Math.min(maximum, fs.lstatSync(local(path)).size)),
          ]),
        )
        .sort();
    assert.deepEqual(obligations(items), obligations(scalarCalls));
    assert(planned.length < scalarCalls.length, 'Native transport round trips actually decrease');
    reset();
    assert.deepEqual(pending.commit(), expected);
    const committedItems = calls.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
    for (const directory of payloadDirectories)
      assert(
        committedItems.some((item) => item.path === directory && item.maximum === 0),
        'Every standalone directory is still freshly verified at commit',
      );
  }));

test('manifest dependency skips an old bundle prefix but retains its full manifest receipt', () =>
  preparedTraceFixture(({ reader, root, create, data, remote, calls }) => {
    const old = create(),
      current = create();
    const path = join(old.fixture.directory, 'manifest.json');
    const manifest = JSON.parse(fs.readFileSync(path, 'utf8'));
    data.writeJson(path, { ...manifest, started_at_unix_ms: 0 });
    const pending = reader.prepareSourcePredictions(
      current.fixture.sourceThreadId,
      current.fixture.sourceTurnId,
      root,
      1,
      true,
    );
    assert(pending);
    const nativeItems = calls.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
    assert(nativeItems.some((item) => item.path === remote(path)));
    assert(
      !nativeItems.some((item) => item.path === remote(join(old.fixture.directory, 'trace.jsonl'))),
    );
    data.writeJson(path, { ...manifest, started_at_unix_ms: 1 });
    assert.equal(pending.commit(), null, 'Old negative inputs still fence the whole snapshot');
  }));

test('a malformed configured reference cannot prefetch outside its validated payload path', () =>
  preparedTraceFixture(({ reader, root, create, calls }) => {
    const f = create();
    create();
    const event = f.fixture.events.find(
      (event) => event.payload.event_type === 'session_configured',
    );
    event.payload.event_payload.path = '../unrelated.json';
    f.fixture.saveEvents();
    assert.equal(
      reader.prepareSourcePredictions(
        f.fixture.sourceThreadId,
        f.fixture.sourceTurnId,
        root,
        1,
        true,
      ),
      null,
    );
    const items = calls.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
    assert(!items.some((item) => item.path.includes('..') || item.path.endsWith('unrelated.json')));
  }));

test('discovery B detects an earlier body rewrite during the second item; no cache/partial result or S replay', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset, change }) => {
    const a = root + '\\body-a.json',
      b = root + '\\body-b.json';
    fs.writeFileSync(local(a), 'first');
    fs.writeFileSync(local(b), 'second');
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    reset();
    let mutated = false;
    change((when, path) => {
      if (when === 'before' && path === b && !mutated) {
        mutated = true;
        rewriteWithChangedMetadata(local(a), 'other');
      }
    });
    assert.throws(() =>
      snapshot.files([
        { path: a, maximum: 100 },
        { path: b, maximum: 100 },
      ]),
    );
    assert.equal(mutated, true);
    assert.deepEqual(
      calls.map((call) => call.kind),
      ['B'],
    );
    assert.equal(snapshot.nativeFiles.size, 0);
    assert(!snapshot.guards.some((guard) => guard.path === a || guard.path === b));
  }));

for (const malformed of ['missing', 'extra', 'reordered'])
  test(`discovery B ${malformed} reply fails without partial cache or single retry`, () =>
    preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset, batchReply }) => {
      const a = root + '\\one.json',
        b = root + '\\two.json';
      fs.writeFileSync(local(a), 'one');
      fs.writeFileSync(local(b), 'two');
      const snapshot = new TraceSnapshot(true);
      snapshot.root(root);
      reset();
      batchReply((values) =>
        malformed === 'missing'
          ? values.slice(1)
          : malformed === 'extra'
            ? [...values, values[0]]
            : values.toReversed(),
      );
      assert.throws(() =>
        snapshot.files([
          { path: a, maximum: 100 },
          { path: b, maximum: 100 },
        ]),
      );
      assert.deepEqual(
        calls.map((call) => call.kind),
        ['B'],
      );
      assert.equal(snapshot.nativeFiles.size, 0);
    }));

test('oversized discovery body selects original full S before dispatch; empty files use file maximum1', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset }) => {
    const a = root + '\\large.json',
      b = root + '\\small.json';
    fs.writeFileSync(local(a), Buffer.alloc(16_385, 65));
    fs.writeFileSync(local(b), 'ok');
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    reset();
    const values = snapshot.files([
      { path: a, maximum: 128 * 1024 },
      { path: b, maximum: 128 * 1024 },
    ]);
    assert.deepEqual(
      values.map((bytes) => bytes.length),
      [16_385, 2],
    );
    assert.deepEqual(
      calls.map((call) => call.kind),
      ['S', 'S'],
    );
    const c = root + '\\empty-a',
      d = root + '\\empty-b';
    fs.writeFileSync(local(c), '');
    fs.writeFileSync(local(d), '');
    reset();
    assert.deepEqual(
      snapshot
        .files([
          { path: c, maximum: 100 },
          { path: d, maximum: 100 },
        ])
        .map((v) => v.length),
      [0, 0],
    );
    assert.deepEqual(
      calls.map((call) => call.kind),
      ['B'],
    );
    assert(calls[0].items.every((item) => item.maximum === 1));
  }));

test('discovery bytes are charged once and released bodies retain commit receipts', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset }) => {
    const a = root + '\\cache-a',
      b = root + '\\cache-b';
    fs.writeFileSync(local(a), 'aaa');
    fs.writeFileSync(local(b), 'bbbbb');
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    reset();
    const items = [
      { path: a, maximum: 100 },
      { path: b, maximum: 100 },
    ];
    snapshot.files(items);
    assert.equal(snapshot.bytes, 8);
    const count = calls.length;
    assert.equal(snapshot.file(a, 100).toString(), 'aaa');
    assert.equal(snapshot.bytes, 8);
    assert.equal(calls.length, count);
    snapshot.releaseBodies();
    assert.equal(snapshot.bytes, 0);
    assert.equal(snapshot.nativeFiles.size, 0);
    assert(snapshot.guards.some((guard) => guard.path === a && guard.body.sha256));
    fs.writeFileSync(local(a), 'changed');
    assert.throws(() => snapshot.commit());
  }));

test('native batch failure midway stays unknown/fail-closed without single replay', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset, change }) => {
    const a = root + '\\timeout-a',
      b = root + '\\timeout-b';
    fs.writeFileSync(local(a), 'a');
    fs.writeFileSync(local(b), 'b');
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    reset();
    const timeout = Object.assign(new Error('synthetic batch timeout'), { code: 'ETIMEDOUT' });
    change((when, path) => {
      if (when === 'before' && path === b) throw timeout;
    });
    assert.throws(
      () =>
        snapshot.files([
          { path: a, maximum: 10 },
          { path: b, maximum: 10 },
        ]),
      (error) => error === timeout,
    );
    assert.deepEqual(
      calls.map((call) => call.kind),
      ['B'],
    );
    assert.equal(snapshot.nativeFiles.size, 0);
  }));

for (const count of [1, 2, 7, 8, 9, 16])
  test(`prepared final groups preserve ${count} independent ordered receipts`, () =>
    preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset }) => {
      const snapshot = new TraceSnapshot(true);
      snapshot.root(root);
      const paths = Array.from({ length: count }, (_, at) => root + `\\body-${at}`);
      for (const path of paths) {
        fs.writeFileSync(local(path), 'body');
        snapshot.file(path, 4);
      }
      const expected = snapshot.guards.map((guard) => ({
        path: guard.path,
        maximum: snapshot.nativeMaximum(guard),
      }));
      reset();
      snapshot.commit();
      assert.deepEqual(
        calls.flatMap((call) =>
          call.kind === 'B' ? call.items : [{ path: call.path, maximum: call.maximum }],
        ),
        expected,
      );
      assert(
        calls.every(
          (call) => call.kind !== 'B' || (call.items.length >= 2 && call.items.length <= 8),
        ),
      );
      assert.equal(calls.length, Math.ceil(expected.length / 8));
    }));

for (const at of [6, 7])
  for (const mutation of ['rewrite', 'truncate', 'ancestor', 'acl', 'later-failure'])
    test(`B8 item ${at + 1} ${mutation} keeps the whole prepared read closed`, () =>
      preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset, change, changeAcl }) => {
        const paths = Array.from({ length: 8 }, (_, index) => root + `\\fresh-${index}`);
        paths.forEach((path) => fs.writeFileSync(local(path), 'AAAA'));
        const snapshot = new TraceSnapshot(true);
        snapshot.root(root);
        reset();
        let injected = false;
        const failure = new Error('synthetic later B8 rejection');
        change((when, path) => {
          if (injected || when !== 'before' || path !== paths[at]) return;
          injected = true;
          if (mutation === 'rewrite') rewriteWithChangedMetadata(local(paths[0]), 'BBBB');
          else if (mutation === 'truncate') fs.truncateSync(local(paths[0]), 2);
          else if (mutation === 'acl') changeAcl();
          else if (mutation === 'later-failure') throw failure;
          else {
            const anchor = local('C:\\fixture');
            fs.renameSync(anchor, anchor + '-retained');
            fs.symlinkSync(anchor + '-retained', anchor, 'junction');
          }
        });
        assert.throws(() => snapshot.files(paths.map((path) => ({ path, maximum: 4 }))));
        assert.equal(injected, true);
        assert.deepEqual(
          calls.map((call) => call.kind),
          ['B'],
        );
        assert.equal(snapshot.nativeFiles.size, 0);
        assert.equal(snapshot.guards.length, 1);
      }));

for (const at of [6, 7])
  test(`B8 commit rejects a fresh native identity change at item ${at + 1}`, () =>
    preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset, change, changeAcl }) => {
      const paths = Array.from({ length: 8 }, (_, index) => root + `\\native-${index}`);
      paths.forEach((path) => fs.writeFileSync(local(path), 'AAAA'));
      const snapshot = new TraceSnapshot(true);
      snapshot.root(root);
      snapshot.files(paths.map((path) => ({ path, maximum: 4 })));
      reset();
      let injected = false;
      const changed = snapshot.guards[at].path;
      change((when, path) => {
        if (!injected && when === 'before' && path === changed) {
          injected = true;
          changeAcl();
        }
      });
      assert.throws(() => snapshot.commit());
      assert.equal(injected, true);
      assert.deepEqual(
        calls.map((call) => call.kind),
        ['B'],
      );
      assert.equal(calls[0].items.length, 8);
    }));

test('directory B8 retains unique guards, repeated path checks and no file-body reads', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset }) => {
    const paths = Array.from({ length: 8 }, (_, index) => root + `\\dir-${index}`);
    paths.forEach((path) => fs.mkdirSync(local(path)));
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    reset();
    snapshot.directoryGroup([...paths, paths[0]]);
    assert.equal(snapshot.guards.length, 9);
    assert.equal(snapshot.directories.size, 9);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].kind, 'B');
    assert.deepEqual(
      calls[0].items,
      paths.map((path) => ({ path, maximum: 0 })),
    );
  }));

test('a failed directory B8 publishes no partial directory guards or scalar replay', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset, change }) => {
    const paths = Array.from({ length: 8 }, (_, index) => root + `\\denied-${index}`);
    paths.forEach((path) => fs.mkdirSync(local(path)));
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    reset();
    let injected = false;
    change((when, path) => {
      if (when === 'before' && path === paths[7]) {
        injected = true;
        throw new Error('synthetic final directory rejection');
      }
    });
    assert.throws(() => snapshot.directoryGroup(paths));
    assert.equal(injected, true);
    assert.equal(snapshot.guards.length, 1);
    assert.equal(snapshot.directories.size, 1);
    assert.deepEqual(
      calls.map((call) => call.kind),
      ['B'],
    );
  }));

test('cache and duplicate path barriers preserve every body receipt without rereading cached bytes', () =>
  preparedTraceFixture(({ TraceSnapshot, root, local, calls, reset }) => {
    const paths = Array.from({ length: 7 }, (_, index) => root + `\\cached-${index}`);
    paths.forEach((path) => fs.writeFileSync(local(path), 'AAAA'));
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    snapshot.file(paths[2], 4);
    reset();
    const ordered = [
      paths[0],
      paths[1],
      paths[2],
      paths[3],
      paths[3],
      paths[4],
      paths[5],
      paths[6],
    ];
    assert.equal(snapshot.files(ordered.map((path) => ({ path, maximum: 4 }))).length, 8);
    assert.equal(snapshot.guards.length, 10);
    assert.equal(snapshot.bytes, 7 * 4);
    assert.equal(calls.flatMap((call) => (call.kind === 'B' ? call.items : [call])).length, 6);
    assert(
      !calls
        .flatMap((call) => (call.kind === 'B' ? call.items : [call]))
        .some((item) => item.path === paths[2]),
    );
  }));

for (const position of [7, 8])
  for (const mutation of ['append', 'rewrite', 'truncate', 'ancestor-retarget'])
    test(`B8 commit early prefix ${mutation} while item ${position} runs preserves all fences`, () =>
      preparedTraceFixture(({ TraceSnapshot, root, local, calls, canonical, reset, change }) => {
        const prefix = root + '\\early-prefix';
        const bodies = Array.from({ length: 6 }, (_, index) => root + `\\late-body-${index}`);
        fs.writeFileSync(local(prefix), 'AAAA');
        for (const path of bodies) fs.writeFileSync(local(path), 'body');
        const snapshot = new TraceSnapshot(true);
        snapshot.root(root);
        snapshot.file(prefix, 4, true);
        for (const path of bodies) snapshot.file(path, 4);
        const expected = snapshot.guards.map((guard) => ({
          path: guard.path,
          maximum: snapshot.nativeMaximum(guard),
        }));
        assert.equal(expected.length, 8, 'Root + early prefix + six bodies force one B8');
        assert.equal(expected[1].path, prefix);
        assert.equal(snapshot.guards[1].prefixMayGrow, true);
        const latePath = expected[position - 1].path;
        assert(bodies.includes(latePath));
        const originalTimes = fs.statSync(local(prefix));
        const prefixCanonicals = () => canonical.filter((path) => path === prefix).length;
        let nativePrefixReturned = false;
        let injected = false;
        let recheckEntered = false;
        let recheckReturned = false;
        let recheckFailure;
        let retargeted = false;
        const fenceEntered = [],
          fenceCompleted = [];
        const originalPrefix = TraceSnapshot.prototype.prefixAfterBatch;
        const originalInputs = TraceSnapshot.prototype.verifyInputs;
        TraceSnapshot.prototype.verifyInputs = function (afterGuards = false) {
          const label = afterGuards ? 'final' : 'initial';
          if (this === snapshot) fenceEntered.push(label);
          const value = Reflect.apply(originalInputs, this, [afterGuards]);
          if (this === snapshot) fenceCompleted.push(label);
          return value;
        };
        TraceSnapshot.prototype.prefixAfterBatch = function (guard, ...args) {
          const own = this === snapshot && guard.path === prefix;
          if (own) {
            assert.equal(
              prefixCanonicals(),
              2,
              'Initial and post-B canonical precede prefix repair',
            );
            recheckEntered = true;
          }
          let value;
          try {
            value = Reflect.apply(originalPrefix, this, [guard, ...args]);
          } catch (error) {
            if (own) recheckFailure = error;
            throw error;
          }
          if (own) {
            recheckReturned = true;
            if (mutation === 'ancestor-retarget') {
              const anchor = local('C:\\fixture');
              const before = fs.lstatSync(local(prefix), { bigint: true });
              fs.renameSync(anchor, anchor + '-retained');
              fs.symlinkSync(anchor + '-retained', anchor, 'junction');
              const after = fs.lstatSync(local(prefix), { bigint: true });
              assert.equal(after.ino, before.ino);
              assert.equal(after.dev, before.dev);
              retargeted = true; // The original prefix handle has already closed.
            }
          }
          return value;
        };
        reset();
        change((when, path) => {
          if (when === 'after' && path === prefix) nativePrefixReturned = true;
          if (injected || when !== 'before' || path !== latePath) return;
          assert(nativePrefixReturned, 'The early native prefix proof must predate the mutation');
          assert.equal(calls.length, 1);
          assert.equal(calls[0].kind, 'B');
          assert.equal(calls[0].items.length, 8);
          assert.equal(calls[0].items[position - 1].path, latePath);
          assert.equal(
            prefixCanonicals(),
            1,
            'Mutation is during B, before any post-B prefix check',
          );
          if (mutation === 'rewrite') {
            fs.writeFileSync(local(prefix), 'BBBB');
            // Ensure an actual metadata change even on a coarse timestamp filesystem.
            fs.utimesSync(
              local(prefix),
              originalTimes.atime,
              new Date(originalTimes.mtime.getTime() + 2000),
            );
            assert.notEqual(fs.statSync(local(prefix)).mtimeMs, originalTimes.mtimeMs);
          } else if (mutation === 'truncate') fs.truncateSync(local(prefix), 2);
          else fs.appendFileSync(local(prefix), '-APPEND');
          injected = true;
        });
        try {
          if (mutation === 'append') assert.doesNotThrow(() => snapshot.commit());
          else if (mutation === 'ancestor-retarget') assert.throws(() => snapshot.commit());
          else
            assert.throws(
              () => snapshot.commit(),
              (error) => error === recheckFailure,
            );
          assert.equal(injected, true);
          assert.equal(recheckEntered, true);
          assert.deepEqual(calls, [{ kind: 'B', items: expected }], 'No scalar replay or second B');
          if (mutation === 'append') {
            assert.equal(recheckReturned, true);
            assert.equal(prefixCanonicals(), 3);
            assert.deepEqual(fenceEntered, ['initial', 'final']);
            assert.deepEqual(fenceCompleted, ['initial', 'final']);
            assert.equal(
              snapshot.guards[1].verifiedStat.size,
              fs.lstatSync(local(prefix), { bigint: true }).size,
            );
          } else {
            assert.deepEqual(fenceEntered, ['initial']);
            assert.deepEqual(fenceCompleted, ['initial']);
            assert.equal(recheckReturned, mutation === 'ancestor-retarget');
            assert.equal(retargeted, mutation === 'ancestor-retarget');
            assert.equal(
              prefixCanonicals(),
              mutation === 'ancestor-retarget' ? 3 : 2,
              'Retarget must reach the third canonical after successful prefix-handle close',
            );
          }
        } finally {
          TraceSnapshot.prototype.prefixAfterBatch = originalPrefix;
          TraceSnapshot.prototype.verifyInputs = originalInputs;
          snapshot.dispose();
        }
      }));
