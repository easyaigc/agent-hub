import assert from 'node:assert/strict';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import test from 'node:test';
import { preparedTraceFixture } from './fixtures/windows-prepared-trace.mjs';

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

test('layered discovery keeps every native item/result while reducing S round trips to two-item B', () =>
  preparedTraceFixture(({ reader, root, create, calls, batch, reset }) => {
    const values = Array.from({ length: 4 }, () => create());
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
          (call.items.length === 2 &&
            call.items.every((item) => item.maximum > 0 && item.maximum <= 16_384)),
      ),
    );
    assert.equal(items.length, scalarCalls.length, 'No per-item native proof was removed');
    assert.deepEqual(
      items.map((item) => item.path).sort(),
      scalarCalls.map((item) => item.path).sort(),
    );
    assert(planned.length < scalarCalls.length, 'Native transport round trips actually decrease');
    assert.deepEqual(pending.commit(), expected);
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
        fs.writeFileSync(local(a), 'other');
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
