import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { join, basename, relative } from 'node:path';
import { pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { DesktopSuggestionWorkerClient } from '../packages/codex-adapter/dist/desktop-suggestion-worker-client.js';
import { DesktopSuggestionObserver } from '../packages/codex-adapter/dist/desktop-suggestion-observer.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsPowerShellEnvironment,
  windowsTraceSnapshot,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { DesktopPredictionTraceReader } from '../packages/codex-adapter/dist/desktop-prediction-trace.js';
import { withWindowsProcessCreation } from '../packages/codex-adapter/dist/windows-process.js';
import { recoverableFixture } from './fixtures/desktop-trace.mjs';
import { batchSnapshotFixture, batchTransportFixture } from './fixtures/windows-trace-batch.mjs';
import {
  encodeTraceSnapshotBatch,
  decodeTraceSnapshotBatch,
  parseTraceSnapshotBatchJSON,
  traceSnapshotBatchFits,
  createTraceSnapshotBatchSender,
} from '../packages/codex-adapter/dist/desktop-trace-native.js';

// Source/target fencing and worker lifecycle also run on macOS; NTFS cases stay native.
const windows = { skip: process.platform !== 'win32' };
const target = {
  hostId: 'synthetic-host',
  workspaceId: 'synthetic-workspace',
  runtimeId: 'desktop-runtime',
  agentId: 'codex',
};
function root() {
  const value = fs.mkdtempSync(join(import.meta.dirname, '../.local/windows-worker-'));
  protectWindowsStateDirectory(value);
  return fs.realpathSync(value);
}
async function ready(client) {
  const end = Date.now() + 15000;
  while (!client.available && Date.now() < end)
    await new Promise((resolve) => setTimeout(resolve, 25));
  assert(client.available, 'Worker did not become ready');
}

test('async Host adapter fences response source and a target change while awaiting the worker', async () => {
  const threadId = randomUUID(),
    sourceTurnId = randomUUID();
  let current = target;
  const observer = new DesktopSuggestionObserver({
    enabled: true,
    root: join(import.meta.dirname, '../.local/metadata-fixture'),
    directory: join(import.meta.dirname, '../.local/metadata-fixture'),
    target: () => current,
  });
  const response = {
    schemaVersion: 1,
    target,
    threadId,
    sourceTurnId,
    readOnly: true,
    source: 'native-trace',
    suggestion: null,
  };
  observer.workerClient = {
    available: true,
    read: async () => ({ ...response, sourceTurnId: randomUUID() }),
    close() {},
  };
  await assert.rejects(
    observer.readBoundSourceAsync(target, threadId, sourceTurnId),
    (e) => e.status === 409,
  );
  let finish;
  observer.workerClient.read = () =>
    new Promise((resolve) => {
      finish = resolve;
    });
  const pending = observer.readBoundSourceAsync(target, threadId, sourceTurnId);
  current = { ...target, hostId: 'changed-synthetic-host' };
  finish(response);
  await assert.rejects(pending, (e) => e.status === 409);
  observer.close();
});

test('private trace root remains user-owned with elevated descendant owners', windows, () => {
  fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
  const directory = root();
  try {
    withWindowsProcessCreation(30000, (timeout) =>
      execFileSync(
        join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          "$p=$env:TRACE_TEST_PATH;$a=Get-Acl -LiteralPath $p;$a.SetOwner([System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'));Set-Acl -LiteralPath $p -AclObject $a",
        ],
        {
          stdio: 'pipe',
          windowsHide: true,
          timeout,
          env: { ...windowsPowerShellEnvironment(), TRACE_TEST_PATH: directory },
        },
      ),
    );
    assert.throws(() => windowsTraceSnapshot(directory, directory));
  } finally {
    protectWindowsStateDirectory(directory);
  }
});

test(
  'async Windows reader revalidates native evidence on every request and rejects incomplete latest output',
  windows,
  async () => {
    fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
    const directory = root(),
      { fixture: f, request } = recoverableFixture(directory);
    const client = new DesktopSuggestionWorkerClient(
      { directory, root: directory, traceRoot: directory, target },
      pathToFileURL(join(import.meta.dirname, 'windows-trace-performance-reader-fixture.mjs')),
    );
    client.start();
    try {
      await ready(client);
      const results = [];
      client.worker.on('message', (message) => {
        if (message.kind === 'result') results.push(message);
      });
      const first = await client.read(f.sourceThreadId, f.sourceTurnId);
      assert.equal(
        results.at(-1).available,
        true,
        'The native reader delivers its fresh final guard',
      );
      assert(first.suggestion);
      const staged = [];
      client.worker.on('message', (message) => {
        if (message.kind === 'prepared') staged.push(message);
      });
      const prepared = await client.prepare(
        f.sourceThreadId,
        f.sourceTurnId,
        performance.now() + 8000,
      );
      try {
        assert.equal(staged.length, 1);
        assert(
          !Object.hasOwn(staged[0], 'value'),
          'Actual Windows prepared phase cannot carry text',
        );
        assert.equal((await prepared.commit()).suggestion.id, first.suggestion.id);
      } finally {
        prepared.dispose();
      }
      assert.equal(first.suggestion.validity, 'native-version');
      assert(Date.parse(first.suggestion.expiresAt) <= Date.now() + 6000);
      const saved = [...f.events];
      f.events.splice(3);
      f.saveEvents();
      assert.equal((await client.read(f.sourceThreadId, f.sourceTurnId)).suggestion, null);
      assert.equal(
        results.at(-1).available,
        true,
        'Empty prediction evidence is distinct from readiness',
      );
      f.events.splice(0, f.events.length, ...saved);
      f.saveEvents();
      assert.equal(
        (await client.read(f.sourceThreadId, f.sourceTurnId)).suggestion.id,
        first.suggestion.id,
      );
      withWindowsProcessCreation(30000, (timeout) =>
        execFileSync(
          join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
          [
            '-NoProfile',
            '-NonInteractive',
            '-Command',
            "$p=$env:TRACE_TEST_PATH;$a=Get-Acl -LiteralPath $p;$a.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new([System.Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'Read','Allow'));Set-Acl -LiteralPath $p -AclObject $a",
          ],
          {
            stdio: 'pipe',
            windowsHide: true,
            timeout,
            env: { ...windowsPowerShellEnvironment(), TRACE_TEST_PATH: request.requestPath },
          },
        ),
      );
      assert.equal((await client.read(f.sourceThreadId, f.sourceTurnId)).suggestion, null);
      protectWindowsStateFile(request.requestPath);
      assert.equal(
        (await client.read(f.sourceThreadId, f.sourceTurnId)).suggestion.id,
        first.suggestion.id,
      );
      assert.equal((await client.read(randomUUID(), f.sourceTurnId)).suggestion, null);
    } finally {
      client.close();
    }
  },
);

test('async reader has one in-flight request, a finite deadline, recovery and immediate close', async () => {
  fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
  const directory = root();
  const client = new DesktopSuggestionWorkerClient(
    { directory, root: directory, traceRoot: directory, target },
    pathToFileURL(join(import.meta.dirname, 'fixtures/windows-trace-worker-faults.mjs')),
  );
  client.start();
  try {
    await ready(client);
    const at = performance.now();
    const first = client.read(randomUUID(), randomUUID());
    const rejected = assert.rejects(first, (e) => e.status === 503);
    await assert.rejects(client.read(randomUUID(), randomUUID()), (e) => e.status === 503);
    await rejected;
    assert(performance.now() - at < 3500, 'Read deadline was not bounded');
    await ready(client);
    assert.equal((await client.read(randomUUID(), randomUUID())).suggestion, null);
    const closeAt = performance.now();
    client.close();
    assert(performance.now() - closeAt < 100, 'Close blocked its caller');
    await assert.rejects(client.read(randomUUID(), randomUUID()), (e) => e.status === 503);
  } finally {
    client.close();
  }
});

test('a 2700ms Worker read keeps workspace-like capability hints and permits the next fresh read', async (t) => {
  fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
  const directory = root();
  const client = new DesktopSuggestionWorkerClient(
    { directory, root: directory, traceRoot: directory, target },
    pathToFileURL(join(import.meta.dirname, 'fixtures/windows-trace-worker-availability.mjs')),
  );
  client.start();
  const worker = client.worker; // Actual fixture Worker, retained only to await its exit.
  const observer = new DesktopSuggestionObserver({
    enabled: false,
    root: directory,
    directory,
    target: () => target,
  });
  observer.workerClient = client;
  let monitor;
  const samples = [];
  try {
    await ready(client);
    await new Promise((done) => setTimeout(done, 950));
    monitor = setInterval(() => {
      samples.push({ at: performance.now(), capabilities: observer.capabilities });
    }, 25);
    const started = performance.now();
    const work = client.read(randomUUID(), randomUUID());
    const busyAt = performance.now();
    await assert.rejects(client.read(randomUUID(), randomUUID()), (e) => e.status === 503);
    assert(performance.now() - busyAt < 100, 'Busy is rejected without a queue');
    const first = await work;
    const elapsed = performance.now() - started;
    assert.equal(first.suggestion, null, 'Readiness is not inferred from suggestion text');
    assert(elapsed >= 2700 && elapsed < 3250, 'The actual Worker crosses the old budget');
    assert(samples.length >= 50, 'Main continues to serve capability observations');
    assert(samples.every((s) => s.capabilities.includes('desktop:suggestions:native-version:v1')));
    assert(
      samples.slice(1).every((s, i) => s.at - samples[i].at < 500),
      'The synchronous scan must not block Main',
    );
    const events = fs
      .readFileSync(join(directory, 'availability-timeline.jsonl'), 'utf8')
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    assert(!events.some((event) => event.event === 'following-state'));
    t.diagnostic(
      JSON.stringify({
        firstReadMs: elapsed,
        heartbeatAgeMs: Date.now() - events[0].at,
        availableAtResult: client.available,
      }),
    );
    assert.equal((await client.read(randomUUID(), randomUUID())).suggestion, null);
  } finally {
    clearInterval(monitor);
    const exited = once(worker, 'exit', { signal: AbortSignal.timeout(3500) });
    client.close();
    await exited;
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

async function availabilityFixture(mode, run) {
  fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
  const directory = root();
  fs.writeFileSync(join(directory, 'availability-mode.json'), JSON.stringify({ mode }), {
    mode: 0o600,
  });
  const client = new DesktopSuggestionWorkerClient(
    { directory, root: directory, traceRoot: directory, target },
    pathToFileURL(join(import.meta.dirname, 'fixtures/windows-trace-worker-availability.mjs')),
  );
  client.start();
  const worker = client.worker;
  try {
    await ready(client);
    await run({ client, worker, directory });
  } finally {
    await closeAvailabilityFixture(client, worker, directory);
  }
}

async function closeAvailabilityFixture(client, worker, directory) {
  const workers = [...new Set([worker, client.worker])].filter(
    (value) => value && value.threadId !== -1,
  );
  const exits = workers.map((value) => once(value, 'exit', { signal: AbortSignal.timeout(3500) }));
  client.close();
  try {
    await Promise.all(exits);
  } catch (error) {
    // These are actual owned synthetic Workers, with no child processes.
    await Promise.all(workers.map((value) => value.terminate()));
    throw error;
  }
  fs.rmSync(directory, { recursive: true, force: true });
}

test('only an explicit final boolean on a complete matching result can change readiness', async (t) => {
  for (const mode of [
    'false',
    'false-text',
    'error',
    'malformed',
    'wrong-target',
    'empty',
    'missing',
    'truthy',
    'stale',
    'old-final-sample',
  ])
    await t.test(mode, () =>
      availabilityFixture(mode, async ({ client, worker }) => {
        const observed = [];
        worker.on('message', (message) => {
          if (message.kind === 'result')
            observed.push({ available: client.available, id: message.id });
        });
        const work = client.read(randomUUID(), randomUUID());
        assert.equal(
          client.available,
          true,
          'Start with an already live state, not an expired one',
        );
        if (
          [
            'false-text',
            'error',
            'malformed',
            'wrong-target',
            'empty',
            'missing',
            'truthy',
            'old-final-sample',
          ].includes(mode)
        )
          await assert.rejects(work, (error) => error.status === 503);
        else assert.equal((await work).suggestion, null);
        assert.equal(
          client.available,
          false,
          'A failed or explicitly false result must revoke readiness',
        );
        assert(observed.length > 0);
        assert(
          observed.every((value) => !value.available),
          'Stale IDs must not briefly promote readiness',
        );
        await assert.rejects(
          client.read(randomUUID(), randomUUID()),
          (error) => error.status === 503,
        );
      }),
    );
});

test('malformed top-level messages and nonboolean states fail the actual worker without escaping Main', async (t) => {
  for (const mode of [
    'top-null',
    'top-undefined',
    'top-string',
    'top-number',
    'top-array',
    'state-missing',
    'state-truthy',
    'state-null',
    'state-no-time',
    'state-future-time',
  ])
    await t.test(mode, () =>
      availabilityFixture(mode, async ({ client, worker }) => {
        const observed = [];
        let acknowledge;
        const delivered = new Promise((done) => {
          acknowledge = done;
        });
        worker.on('message', (message) => {
          if (message?.kind === 'fixture-malformed-sent') {
            observed.push({ available: client.available, retired: client.worker !== worker });
            acknowledge();
          }
        });
        const work = client.read(randomUUID(), randomUUID());
        assert.equal(client.available, true, 'Malformed input arrives after a live state');
        await assert.rejects(work, (error) => error.status === 503);
        // The acknowledgement follows the malformed message on the actual port.
        // Retirement must happen there, not only at the later RPC deadline.
        await delivered;
        assert.deepEqual(observed, [{ available: false, retired: true }]);
        assert.equal(client.available, false);
        await assert.rejects(
          client.read(randomUUID(), randomUUID()),
          (error) => error.status === 503,
        );
      }),
    );
});

test('an explicit error field always rejects and only fixed failure status codes survive the worker boundary', async (t) => {
  for (const mode of [
    'error-false',
    'error-zero',
    'error-null',
    'error-undefined',
    'error-empty-string',
    'error-true',
    'error-string',
    'error-array',
    'error-empty-object',
    'status-null',
    'status-zero',
    'status-success',
    'status-string',
    'status-fractional',
    'status-nan',
    'status-infinity',
    'status-overflow',
    'status-other-failure',
    'status-400',
    'status-409',
    'status-503',
  ])
    await t.test(mode, () =>
      availabilityFixture(mode, async ({ client, worker }) => {
        const observed = [];
        worker.on('message', (message) => {
          if (message.kind === 'result') observed.push(client.available);
        });
        const work = client.read(randomUUID(), randomUUID());
        assert.equal(client.available, true, 'Error input must revoke an existing live state');
        const expected = mode === 'status-400' ? 400 : mode === 'status-409' ? 409 : 503;
        await assert.rejects(work, (error) => {
          assert(error instanceof Error);
          assert.equal(error.status, expected);
          return true;
        });
        assert.deepEqual(
          observed,
          [false],
          'A mixed success/error envelope cannot renew readiness',
        );
        assert.equal(client.available, false);
        await assert.rejects(
          client.read(randomUUID(), randomUUID()),
          (error) => error.status === 503,
        );
      }),
    );
});

test('a late result and heartbeat from a replaced worker cannot revive the new unavailable worker', () =>
  availabilityFixture('late', async ({ client, worker, directory }) => {
    const retired = once(worker, 'exit');
    const late = new Promise((resolve) =>
      worker.on('message', (message) => {
        if (message.kind === 'result') resolve();
      }),
    );
    const started = performance.now();
    await assert.rejects(client.read(randomUUID(), randomUUID()), (error) => error.status === 503);
    assert(performance.now() - started < 4250, 'The 3250ms RPC deadline remains finite');
    assert.equal(JSON.parse(fs.readFileSync(join(directory, 'availability-generation.json'))), 1);
    await late;
    await retired;
    await new Promise((done) => setImmediate(done));
    assert.equal(client.available, false, 'The retired Reader cannot renew readiness');
    const deadline = Date.now() + 1500;
    while (
      JSON.parse(fs.readFileSync(join(directory, 'availability-generation.json'))) < 2 &&
      Date.now() < deadline
    )
      await new Promise((done) => setTimeout(done, 20));
    assert.equal(JSON.parse(fs.readFileSync(join(directory, 'availability-generation.json'))), 2);
    assert.notEqual(client.worker, worker, 'A real second Worker generation must exist');
    await new Promise((done) => setImmediate(done));
    assert.equal(client.available, false);
    await assert.rejects(client.read(randomUUID(), randomUUID()), (error) => error.status === 503);
  }));

test('a deferred deadline callback cannot accept a queued result or state after a Main stall', async (t) =>
  availabilityFixture('deadline-stall', async ({ client, worker }) => {
    // Hold only Main's timer dispatch. The actual Worker and monotonic clock
    // keep running, so correctness cannot depend on timer/message phase order.
    t.mock.timers.enable({ apis: ['setTimeout'] });
    try {
      const entered = once(worker, 'message');
      const work = client.read(randomUUID(), randomUUID());
      const rejected = assert.rejects(work, (e) => e.status === 503);
      assert.equal((await entered)[0].kind, 'fixture-read-start');
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 3400);
      await rejected;
      await new Promise((done) => setImmediate(done));
      assert.equal(
        client.available,
        false,
        'Queued true state cannot revive the expired generation',
      );
      assert.notEqual(client.worker, worker, 'Absolute deadline retires the real old Worker');
    } finally {
      t.mock.timers.reset();
    }
  }));

test('a queued heartbeat keeps its sample time instead of gaining a fresh TTL on receipt', () =>
  availabilityFixture('queued-state', async ({ client, worker }) => {
    const posted = once(worker, 'message');
    const gate = new SharedArrayBuffer(4);
    const phase = new Int32Array(gate);
    worker.postMessage({ kind: 'fixture-queue-state', gate });
    Atomics.wait(phase, 0, 0, 2000);
    assert.equal(Atomics.load(phase, 0), 1, 'The Worker sampled before Main starts its stall');
    Atomics.store(phase, 0, 2);
    Atomics.notify(phase, 0);
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 5200);
    await posted;
    assert.equal(client.available, false, 'An old queued state is not fresh availability');
    await assert.rejects(client.read(randomUUID(), randomUUID()), (e) => e.status === 503);
  }));

test('close immediately rejects a real Worker still blocked in its 2700ms read', () =>
  availabilityFixture('slow', async ({ client, worker }) => {
    const entered = once(worker, 'message');
    const work = client.read(randomUUID(), randomUUID());
    const rejected = assert.rejects(work, (e) => e.status === 503);
    assert.equal((await entered)[0].kind, 'fixture-read-start');
    const started = performance.now();
    const exited = once(worker, 'exit', { signal: AbortSignal.timeout(3500) });
    client.close();
    await rejected;
    assert(performance.now() - started < 100, 'Close never waits for the synchronous read');
    assert.equal(client.available, false);
    await assert.rejects(client.read(randomUUID(), randomUUID()), (e) => e.status === 503);
    await exited;
    assert.equal(client.available, false, 'The retired Worker never restores readiness');
  }));

test("close rejects the pending read and ignores the actual worker's final result and heartbeat", () =>
  availabilityFixture('closed', async ({ client, worker }) => {
    const entered = once(worker, 'message');
    const observed = [];
    worker.on('message', (message) => {
      if (message.kind === 'result') observed.push(client.available);
    });
    const work = client.read(randomUUID(), randomUUID());
    const rejected = assert.rejects(work, (error) => error.status === 503);
    await entered;
    const exited = once(worker, 'exit', { signal: AbortSignal.timeout(3500) });
    client.close();
    await rejected;
    await exited;
    assert.deepEqual(observed, [false]);
    assert.equal(client.available, false);
    await assert.rejects(client.read(randomUUID(), randomUUID()), (error) => error.status === 503);
  }));

test(
  'ordinary Windows creation retains a trusted native owner inside a private user root',
  windows,
  () => {
    fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
    const directory = root();
    fs.writeFileSync(join(directory, 'ordinary.txt'), 'synthetic native ownership');
    // No SetOwner on the ordinary file. Native inherited private DACL is preserved.
    assert.equal(
      windowsTraceSnapshot(directory, join(directory, 'ordinary.txt'), 100).bytes.toString(),
      'synthetic native ownership',
    );
    const longDirectory = join(directory, ...Array(4).fill('long-' + 'x'.repeat(70)));
    fs.mkdirSync(longDirectory, { recursive: true });
    fs.writeFileSync(join(longDirectory, 'ordinary.txt'), 'long synthetic path');
    assert.equal(
      windowsTraceSnapshot(directory, join(longDirectory, 'ordinary.txt'), 100).bytes.toString(),
      'long synthetic path',
    );
    const staging = root(),
      { fixture: f, configured } = recoverableFixture(staging);
    const ordinaryCopy = (from, to) => {
      fs.mkdirSync(to);
      for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
        if (entry.isDirectory()) ordinaryCopy(join(from, entry.name), join(to, entry.name));
        else fs.writeFileSync(join(to, entry.name), fs.readFileSync(join(from, entry.name)));
      }
    };
    ordinaryCopy(f.directory, join(directory, basename(f.directory)));
    // The copied descendants are freshly created by the normal current token;
    // no descendant SetOwner/protection calls are made in this destination.
    const configuredPath = join(directory, relative(staging, configured.path));
    configured.value.cwd = directory;
    fs.writeFileSync(configuredPath, JSON.stringify(configured.value));
    const result = new DesktopPredictionTraceReader(directory).readSourcePredictions(
      f.sourceThreadId,
      f.sourceTurnId,
      1,
      true,
    );
    assert.equal(result.length, 1);
    assert(result[0].text);
  },
);

test('verify batch protocol has fixed limits, exact fields and complete ordered responses', () => {
  const request = {
    schemaVersion: 1,
    kind: 'snapshot-many',
    id: 1,
    items: [
      { index: 0, path: 'C:\\fixture\\traces', maximum: 0 },
      { index: 1, path: 'C:\\fixture\\traces\\one.txt', maximum: 4 },
    ],
  };
  assert(encodeTraceSnapshotBatch(request).startsWith('B\t'));
  const changes = [
    (value) => {
      value.items = [];
    },
    (value) => {
      value.items.push(value.items[0]);
    },
    (value) => {
      value.schemaVersion = 2;
    },
    (value) => {
      value.kind = 'exec';
    },
    (value) => {
      value.id = 0;
    },
    (value) => {
      value.id = '1';
    },
    (value) => {
      value.id = 2147483648;
    },
    (value) => {
      value.root = 'C:\\other';
    },
    (value) => {
      value.items[1].index = 0;
    },
    (value) => {
      value.items[1].trusted = true;
    },
    (value) => {
      value.items[1].maximum = 16385;
    },
    (value) => {
      value.items[1].maximum = -1;
    },
    (value) => {
      value.items[1].maximum = 1.5;
    },
    (value) => {
      value.items[1].path = '\\\\?\\C:\\alias';
    },
    (value) => {
      value.items[1].path = '\\\\server\\share';
    },
    (value) => {
      value.items[1].path = 'C:\\fixture\\file:ads';
    },
    (value) => {
      value.items[1].path = 'C:\\fixture\\..\\alias';
    },
    (value) => {
      value.items[1].path += '.';
    },
    (value) => {
      value.items[1].path += '\0';
    },
  ];
  for (const change of changes) {
    const value = structuredClone(request);
    change(value);
    assert.throws(() => encodeTraceSnapshotBatch(value));
  }
  assert.throws(() => encodeTraceSnapshotBatch(null));
  assert.throws(() => encodeTraceSnapshotBatch([]));
  assert.equal(
    traceSnapshotBatchFits([
      { path: 'C:\\' + 'a'.repeat(30000), maximum: 1 },
      { path: 'C:\\' + 'b'.repeat(30000), maximum: 1 },
    ]),
    false,
    'Oversized frames choose singles before dispatch',
  );
  assert.equal(
    traceSnapshotBatchFits([{ path: 'C:\\' + 'a\\'.repeat(3000) + 'file', maximum: 1 }]),
    false,
    'Deep-chain response estimate has its own fixed bound',
  );
  assert.throws(() =>
    decodeTraceSnapshotBatch(
      { schemaVersion: 1, kind: 'snapshot-many', id: 1, items: [] },
      request,
    ),
  );
  assert.throws(() => parseTraceSnapshotBatchJSON('{"id":1,"id":1}'));
  assert.throws(() => parseTraceSnapshotBatchJSON('{"items":[{"index":0,"\\u0069ndex":0}]}'));
});

test('batched final guards preserve duplicates and reduce only deliveries, never native snapshots', async () =>
  batchSnapshotFixture(({ TraceSnapshot, root, local, counts, reset }) => {
    fs.writeFileSync(local(root + '\\one.txt'), 'AAAA');
    const snapshot = new TraceSnapshot();
    snapshot.root(root);
    snapshot.file(root + '\\one.txt', 4);
    snapshot.file(root + '\\one.txt', 4);
    assert.equal(
      counts.snapshots,
      2,
      'Initial reading still uses single snapshots and its logical body cache',
    );
    reset();
    snapshot.verify();
    assert.equal(
      counts.snapshots,
      3,
      'Every root/file/duplicate guard still executes a full snapshot',
    );
    assert.equal(counts.batch, 1);
    assert.equal(counts.single, 1);
    reset();
    snapshot.verify();
    assert.equal(counts.snapshots, 3, 'A later verify never reuses native permission results');
  }));

for (const mode of [
  'append',
  'rewrite',
  'truncate',
  'ordinary-change',
  'ancestor-change',
  'second-failure',
])
  test(`batch interleaving: ${mode}`, async () =>
    batchSnapshotFixture(({ TraceSnapshot, root, local, counts, reset, change }) => {
      const file = root + '\\one.txt';
      fs.writeFileSync(local(file), 'AAAA');
      const snapshot = new TraceSnapshot();
      snapshot.root(root);
      snapshot.file(file, 4, ['append', 'rewrite', 'truncate'].includes(mode));
      reset();
      let injected = false;
      change((when, index) => {
        if (mode === 'append' && when === 'before' && index === 0) {
          fs.appendFileSync(local(file), 'APPEND');
          injected = true;
        }
        if (mode === 'rewrite' && when === 'before' && index === 1) {
          fs.writeFileSync(local(file), 'BBBB');
          injected = true;
        }
        if (mode === 'truncate' && when === 'before' && index === 1) {
          fs.truncateSync(local(file), 2);
          injected = true;
        }
        if (mode === 'ordinary-change' && when === 'after' && index === 1) {
          fs.appendFileSync(local(file), 'late');
          injected = true;
        }
        if (mode === 'ancestor-change' && when === 'after' && index === 0) {
          const ancestor = local('C:\\fixture');
          const retained = ancestor + '-retained';
          const before = fs.lstatSync(ancestor, { bigint: true });
          const rootBefore = fs.lstatSync(local(root), { bigint: true });
          const fileBefore = fs.lstatSync(local(file), { bigint: true });
          fs.renameSync(ancestor, retained);
          fs.mkdirSync(ancestor, { mode: 0o700 });
          fs.renameSync(join(retained, 'traces'), local(root));
          assert.notEqual(fs.lstatSync(ancestor, { bigint: true }).ino, before.ino);
          assert.equal(fs.lstatSync(retained, { bigint: true }).ino, before.ino);
          assert.equal(fs.lstatSync(local(root), { bigint: true }).ino, rootBefore.ino);
          assert.equal(fs.lstatSync(local(file), { bigint: true }).ino, fileBefore.ino);
          injected = true;
        }
        if (mode === 'second-failure' && when === 'before' && index === 1) {
          injected = true;
          throw Error('synthetic second item rejected');
        }
      });
      if (mode === 'append') snapshot.verify();
      else assert.throws(() => snapshot.verify());
      assert.equal(injected, true, 'The intended interleaving must actually occur');
      assert.equal(counts.batch, 1);
      assert.equal(counts.single, 0, 'No failed batch may fall back to individual reads');
    }));

test('a large prefix is selected for the original single path before any batch call', async () =>
  batchSnapshotFixture(({ TraceSnapshot, root, local, counts, reset }) => {
    const file = root + '\\one.txt';
    fs.writeFileSync(local(file), 'A'.repeat(16385));
    const snapshot = new TraceSnapshot();
    snapshot.root(root);
    snapshot.file(file, 16385, true);
    reset();
    snapshot.verify();
    assert.equal(counts.batch, 0);
    assert.equal(counts.single, 2);
  }));

for (const mode of ['prefix-append-after', 'prefix-rewrite-after', 'ordinary-rewrite-after'])
  test(`an earlier batch item changes while a later one runs: ${mode}`, async () =>
    batchSnapshotFixture(({ TraceSnapshot, root, local, counts, reset, change }) => {
      const bundle = root + '\\bundle',
        first = bundle + '\\one.txt',
        second = bundle + '\\two.txt';
      fs.mkdirSync(local(bundle));
      fs.writeFileSync(local(first), 'AAAA');
      fs.writeFileSync(local(second), 'CCCC');
      // This interleaving exercises metadata drift after the native byte read.
      // Linux may coalesce immediate same-size rewrites into one timestamp tick;
      // an old, exactly representable mtime makes the real write observable.
      fs.utimesSync(local(first), 1_700_000_000, 1_700_000_000);
      const before = fs.statSync(local(first), { bigint: true });
      let after;
      const snapshot = new TraceSnapshot();
      snapshot.root(root);
      snapshot.directory(bundle);
      snapshot.file(first, 4, mode.startsWith('prefix'));
      snapshot.file(second, 4);
      reset();
      change((when, index, item) => {
        if (when === 'after' && index === 0 && item.path === first) {
          if (mode === 'prefix-append-after') fs.appendFileSync(local(first), 'APPEND');
          else fs.writeFileSync(local(first), 'BBBB');
          after = fs.statSync(local(first), { bigint: true });
        }
      });
      if (mode === 'prefix-append-after') snapshot.verify();
      else assert.throws(() => snapshot.verify(), { message: 'Unavailable prediction trace' });
      assert(after, 'The intended post-read mutation must actually occur');
      assert.equal(after.dev, before.dev);
      assert.equal(after.ino, before.ino);
      assert.notEqual(after.mtimeNs, before.mtimeNs, 'The write must produce metadata drift');
      assert.equal(after.size, mode === 'prefix-append-after' ? 10n : before.size);
      assert.equal(
        fs.readFileSync(local(first), 'utf8'),
        mode === 'prefix-append-after' ? 'AAAAAPPEND' : 'BBBB',
      );
      assert.equal(counts.batch, 2);
      assert.equal(counts.single, 0);
      assert.equal(counts.snapshots, 4);
    }));

test('an empty prefix retains the original max(1,length) append rejection', async () =>
  batchSnapshotFixture(({ TraceSnapshot, root, local, change }) => {
    const file = root + '\\one.txt';
    fs.writeFileSync(local(file), '');
    const snapshot = new TraceSnapshot();
    snapshot.root(root);
    snapshot.file(file, 4, true);
    change((when, index) => {
      if (when === 'before' && index === 0) fs.appendFileSync(local(file), 'A');
    });
    assert.throws(() => snapshot.verify());
  }));

for (const mode of [
  'normal',
  'wrong-id',
  'wrong-kind',
  'reordered',
  'short',
  'extra-item',
  'extra-field',
  'bad-base64',
  'wrong-chain',
  'extra-snapshot-field',
  'huge-bytes',
  'duplicate-key',
  'two-frames',
  'oversized',
  'second-failure',
  'batch-timeout',
  'budget',
  'closed',
  'owner-close',
  'duplicate-late',
  'stale-while-next',
  'clock-forward',
  'clock-backward',
])
  test(`actual Worker batch transport: ${mode}`, async (t) => {
    const result = await batchTransportFixture(mode);
    assert(result.allRetired, 'The actual owner/native Worker and its child must retire');
    if (mode === 'owner-close') assert.equal(result.answer, undefined);
    else assert.equal(result.answer.passed, mode === 'normal' || mode === 'clock-forward');
    assert.equal(
      result.requests,
      mode === 'closed' || mode === 'clock-backward' ? 0 : mode === 'stale-while-next' ? 2 : 1,
      'Unknown failures are never replayed',
    );
    if (mode === 'duplicate-late' || mode === 'stale-while-next')
      assert.equal(result.answer.firstPassed, true);
    if (mode === 'batch-timeout') {
      let observation = JSON.stringify({
        elapsed: result.answer.elapsed,
        owner: result.answer.timing,
        native: result.nativeTimers,
        incomplete: result.diagnosticIncomplete,
      });
      if (Buffer.byteLength(observation) > 1900)
        observation = JSON.stringify({
          elapsed: result.answer.elapsed,
          incomplete: true,
          detail: 'Original fixture result contains oversized diagnostics',
        });
      t.diagnostic('batch-timeout observation: ' + observation);
      assert(result.partialExecuted);
      assert(
        result.answer.elapsed < 1000,
        '750ms is for the entire batch, not each item; ' + observation,
      );
    }
    if (mode === 'budget')
      assert(result.answer.elapsed < 2200, 'Batch consumes the existing whole-read deadline');
  });

test(
  'Windows same-source C# batch keeps per-item DACL checks and agrees with original singles',
  windows,
  async () => {
    const { frozenBatchFixture } = await import('./fixtures/windows-trace-batch.mjs');
    const fixture = await frozenBatchFixture(),
      directory = root();
    const executable = join(fixture.directory, 'snapshot-test.exe'),
      entry = join(fixture.directory, 'entry.cs');
    fs.writeFileSync(join(directory, 'one.txt'), 'AAAA');
    fs.writeFileSync(
      entry,
      `using System;using System.IO;using System.Text;using System.Reflection;
class SnapshotFixture {
 static object Call(string name,params object[] args){return typeof(TraceState).GetMethod(name,BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,args);}
 static int Main(string[] args){
 AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
 AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
 Console.OutputEncoding=new UTF8Encoding(false);Console.Out.NewLine="\\n";
 if(args.Length==3&&args[0]=="raw"){try{Console.WriteLine(Call("SnapshotMany",args[1],args[2]));}catch(Exception error){Console.Error.WriteLine(error.GetBaseException().GetType().FullName);Console.WriteLine("{\\"error\\":\\"SYNTHETIC_REJECTED\\"}");}return 0;}
 if(args.Length!=5||args[0]!="serve")return 1;
 File.AppendAllText(Environment.GetEnvironmentVariable("TRACE_BATCH_LOG"),"{\\"event\\":\\"started\\",\\"pid\\":"+System.Diagnostics.Process.GetCurrentProcess().Id+"}\\n");
 Console.WriteLine("{\\"ready\\":true}");
 string line;while((line=Console.ReadLine())!=null&&line!="QUIT"){
 try {if(line.Length>100000)throw new Exception();var parts=line.Split('\\t');
 if(parts.Length!=2||parts[0]!="B")throw new Exception();Console.WriteLine(Call("SnapshotMany",args[1],parts[1]));
 }catch(Exception error){Console.Error.WriteLine(error.GetBaseException().GetType().FullName);Console.WriteLine("{\\"error\\":\\"SYNTHETIC_REJECTED\\"}");}Console.Out.Flush();}return 0;}}
`,
    );
    const compiler = ['Framework64', 'Framework']
      .map((value) => join(process.env.SystemRoot, 'Microsoft.NET', value, 'v4.0.30319/csc.exe'))
      .find((value) => fs.existsSync(value));
    assert(compiler, 'Framework compiler required');
    withWindowsProcessCreation(30000, (timeout) =>
      execFileSync(
        compiler,
        [
          '/nologo',
          '/target:exe',
          '/main:SnapshotFixture',
          '/r:System.Web.Extensions.dll',
          '/out:' + executable,
          entry,
          join(import.meta.dirname, '../packages/codex-adapter/windows/trace-state.cs'),
          join(import.meta.dirname, '../packages/codex-adapter/windows/trace-process.cs'),
        ],
        { timeout, windowsHide: true, stdio: 'pipe' },
      ),
    );
    const extra = { TRACE_BATCH_EXE: executable, TRACE_BATCH_ROOT: directory };
    const expected = [
      windowsTraceSnapshot(directory, directory).identity,
      windowsTraceSnapshot(directory, join(directory, 'one.txt'), 4).identity,
    ];
    assert.deepEqual((await batchTransportFixture('normal', extra)).answer.identities, expected);
    const request = {
      schemaVersion: 1,
      kind: 'snapshot-many',
      id: 1,
      items: [
        { index: 0, path: directory, maximum: 0 },
        { index: 1, path: join(directory, 'one.txt'), maximum: 4 },
      ],
    };
    const raw = (text) =>
      JSON.parse(
        withWindowsProcessCreation(5000, (timeout) =>
          execFileSync(executable, ['raw', directory, Buffer.from(text).toString('base64')], {
            timeout,
            windowsHide: true,
            encoding: 'utf8',
            stdio: 'pipe',
          }),
        ),
      );
    for (const change of [
      (value) => {
        value.items = [];
      },
      (value) => {
        value.items.push(value.items[0]);
      },
      (value) => {
        value.root = directory;
      },
      (value) => {
        value.items[1].index = 0;
      },
      (value) => {
        value.items[1].maximum = 16385;
      },
      (value) => {
        value.items[1].path = directory + '-outside';
      },
    ]) {
      const value = structuredClone(request);
      change(value);
      assert.deepEqual(
        raw(JSON.stringify(value)),
        { error: 'SYNTHETIC_REJECTED' },
        'Native failure publishes no partial items',
      );
    }
    assert.deepEqual(raw(JSON.stringify(request).replace('"id":1', '"id":2,"id":1')), {
      error: 'SYNTHETIC_REJECTED',
    });
    const eight = structuredClone(request);
    eight.items = Array.from({ length: 8 }, (_, index) => ({ ...request.items[index % 2], index }));
    const eightValues = decodeTraceSnapshotBatch(raw(JSON.stringify(eight)), eight);
    assert.deepEqual(
      eightValues.map((item) => item.identity),
      Array.from({ length: 8 }, (_, index) => expected[index % 2]),
    );
    const sixtyFour = structuredClone(eight);
    sixtyFour.items = Array.from({ length: 64 }, (_, index) => ({
      ...request.items[index % 2],
      index,
    }));
    assert.deepEqual(
      decodeTraceSnapshotBatch(raw(JSON.stringify(sixtyFour)), sixtyFour).map(
        (item) => item.identity,
      ),
      Array.from({ length: 64 }, (_, index) => expected[index % 2]),
    );
    const overflow = structuredClone(sixtyFour);
    overflow.items.push({ ...overflow.items[0], index: 64 });
    assert.deepEqual(raw(JSON.stringify(overflow)), { error: 'SYNTHETIC_REJECTED' });
    const junction = join(directory, 'alias');
    fs.symlinkSync(directory, junction, 'junction');
    try {
      const value = structuredClone(request);
      value.items[1].path = join(junction, 'one.txt');
      assert.deepEqual(raw(JSON.stringify(value)), { error: 'SYNTHETIC_REJECTED' });
    } finally {
      fs.unlinkSync(junction);
    }
    const longDirectory = join(directory, ...Array(4).fill('long-' + 'x'.repeat(70)));
    assert(longDirectory.length > 260, 'The fixture must exceed the legacy MAX_PATH boundary');
    fs.mkdirSync(longDirectory, { recursive: true });
    fs.writeFileSync(join(longDirectory, 'one.txt'), 'AAAA');
    const longExpected = windowsTraceSnapshot(
      directory,
      join(longDirectory, 'one.txt'),
      4,
    ).identity;
    const longRequest = structuredClone(request);
    longRequest.items[0].path = longDirectory;
    longRequest.items[1].path = join(longDirectory, 'one.txt');
    const longResult = decodeTraceSnapshotBatch(raw(JSON.stringify(longRequest)), longRequest);
    assert.equal(longResult[1].identity, longExpected);
    withWindowsProcessCreation(30000, (timeout) =>
      execFileSync(
        join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          "$p=$env:TRACE_TEST_PATH;$a=Get-Acl -LiteralPath $p;$a.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new([System.Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'Read','Allow'));Set-Acl -LiteralPath $p -AclObject $a",
        ],
        {
          timeout,
          windowsHide: true,
          stdio: 'pipe',
          env: { ...windowsPowerShellEnvironment(), TRACE_TEST_PATH: join(directory, 'one.txt') },
        },
      ),
    );
    try {
      assert.equal((await batchTransportFixture('native-denied', extra)).answer.passed, false);
    } finally {
      protectWindowsStateFile(join(directory, 'one.txt'));
    }
    assert.equal((await batchTransportFixture('normal', extra)).answer.passed, true);
    fixture.unchanged();
  },
);

async function preparedFixture(mode, run) {
  fs.mkdirSync(join(import.meta.dirname, '../.local'), { recursive: true });
  const directory = root();
  fs.writeFileSync(join(directory, 'prepared-mode.json'), JSON.stringify({ mode }), {
    mode: 0o600,
  });
  const client = new DesktopSuggestionWorkerClient(
    { directory, root: directory, traceRoot: directory, target },
    pathToFileURL(join(import.meta.dirname, 'fixtures/windows-trace-worker-prepared.mjs')),
  );
  client.start();
  const worker = client.worker;
  try {
    await ready(client);
    await run({ client, worker, directory });
  } finally {
    await closeAvailabilityFixture(client, worker, directory);
  }
}
const preparedEvents = (directory) =>
  fs
    .readFileSync(join(directory, 'prepared-events.jsonl'), 'utf8')
    .trim()
    .split('\n')
    .map((line) => JSON.parse(line).event);

// Retirement may enqueue one typed cleanup after the single commit. The Worker
// can log it before or after Main observes its late packet; neither timing may
// add another phase, duplicate input, or accept the retired result.
function assertRetiredPreparedEvents(directory, expected) {
  const events = preparedEvents(directory);
  const cleanup = expected.includes('commit') && events.at(-1) === 'abort';
  assert.deepEqual(events, cleanup ? [...expected, 'abort'] : expected);
}

for (const mode of ['slow', 'slow-commit'])
  test(`fenced ${mode} crosses3250ms with one reservation, no early body and healthy Main`, () =>
    preparedFixture(mode, async ({ client, worker, directory }) => {
      const messages = [];
      worker.on('message', (value) => messages.push(value));
      const started = performance.now();
      let ticks = 0;
      const monitor = setInterval(() => {
        ticks++;
        assert.equal(client.available, true);
      }, 20);
      try {
        const pending = client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
        await assert.rejects(client.read(randomUUID(), randomUUID()), (e) => e.status === 503);
        const prepared = await pending;
        const prepareElapsed = performance.now() - started;
        assert(prepareElapsed < 4000);
        if (mode === 'slow') assert(prepareElapsed > 3250, 'Actual3400ms fenced prepare');
        assert.equal(messages.filter((value) => value.kind === 'prepared').length, 1);
        assert(!JSON.stringify(messages).includes('committed synthetic fixture only'));
        assert(!messages.some((value) => Object.hasOwn(value, 'value')), 'Opaque before commit');
        const busyAt = performance.now();
        await assert.rejects(client.prepare(randomUUID(), randomUUID(), performance.now() + 8000));
        assert(performance.now() - busyAt < 100);
        await new Promise((done) => setTimeout(done, 100)); // Original middle owner await.
        const commitStarted = performance.now();
        const value = await prepared.commit();
        const commitElapsed = performance.now() - commitStarted;
        assert(commitElapsed < 4000);
        if (mode === 'slow-commit') assert(commitElapsed > 3250, 'Actual3400ms fenced commit');
        assert.equal(value.suggestion.text, 'committed synthetic fixture only');
        assert(performance.now() - started > 5500 && performance.now() - started < 7000);
        assert(ticks > 100, 'Synchronous Worker phases must not block Main');
        await assert.rejects(client.read(randomUUID(), randomUUID()), 'Final source owns the slot');
        prepared.dispose();
        await assert.rejects(prepared.commit(), 'Consumed continuation cannot commit twice');
        assert.equal(client.available, true, 'Fresh final sample is immediately available');
        assert.deepEqual(preparedEvents(directory), ['prepare', 'commit']);
      } finally {
        clearInterval(monitor);
      }
    }));

for (const mode of [
  'prepare-body',
  'prepare-false',
  'prepare-generation',
  'prepare-source',
  'prepare-target',
  'prepare-old-sample',
])
  test(`fenced prepare rejects ${mode} without releasing a projection`, () =>
    preparedFixture(mode, async ({ client, worker, directory }) => {
      await assert.rejects(
        client.prepare(randomUUID(), randomUUID(), performance.now() + 8000),
        (e) => e.status === 503,
      );
      assert.notEqual(client.worker, worker);
      assert(!preparedEvents(directory).includes('commit'));
    }));
for (const mode of [
  'commit-nonce',
  'commit-id',
  'commit-generation',
  'commit-source',
  'commit-target',
  'commit-false',
  'commit-old-sample',
  'commit-value-source',
])
  test(`fenced commit rejects ${mode} and retires the exact generation`, () =>
    preparedFixture(mode, async ({ client, worker }) => {
      const p = await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
      await assert.rejects(p.commit(), (e) => e.status === 503);
      assert.notEqual(client.worker, worker);
      assert.equal(client.available, false);
    }));
for (const [mode, ceiling] of [
  ['prepare-hang', 4000],
  ['commit-hang', 4000],
])
  test(`fenced ${mode} retains its ${ceiling}ms phase ceiling`, () =>
    preparedFixture(mode, async ({ client, worker }) => {
      const prepared =
        mode === 'commit-hang'
          ? await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000)
          : null;
      const started = performance.now();
      const operation = prepared
        ? prepared.commit()
        : client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
      await assert.rejects(operation, (e) => e.status === 503);
      // Timer-dispatch margin is test tolerance, never an acceptance budget.
      assert(performance.now() - started >= ceiling - 100);
      assert(performance.now() - started < ceiling + 1000);
      assert.notEqual(client.worker, worker);
    }));
async function nextFencedReply(worker, kind) {
  const signal = AbortSignal.timeout(6500);
  for (;;) {
    const [message] = await once(worker, 'message', { signal });
    if (message.kind === kind) return message;
  }
}

for (const [mode, kind] of [
  ['prepare-late', 'prepared'],
  ['commit-late', 'fenced-result'],
])
  test(`fenced ${mode} rejects a real4200ms positive reply from the retired generation`, () =>
    preparedFixture(mode, async ({ client, worker, directory }) => {
      const prepared =
        mode === 'commit-late'
          ? await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000)
          : null;
      const late = nextFencedReply(worker, kind);
      const operation = prepared
        ? prepared.commit()
        : client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
      await assert.rejects(operation, (error) => error.status === 503);
      assert.notEqual(client.worker, worker);
      const message = await late;
      assert.equal(message.available, true, 'Actual late positive packet must reach Main');
      assert.equal(Object.hasOwn(message, 'value'), mode === 'commit-late');
      assert.equal(client.available, false, 'Retired reply cannot restore readiness');
      assertRetiredPreparedEvents(
        directory,
        mode === 'commit-late' ? ['prepare', 'commit'] : ['prepare'],
      );
    }));

test('fenced prepare checks absolute4000ms after a queued sampled reply and Main stall', () =>
  preparedFixture('queued-prepare', async ({ client, worker, directory }) => {
    const barrier = new Int32Array(new SharedArrayBuffer(8));
    worker.postMessage({ kind: 'fixture-sync', shared: barrier.buffer });
    const pending = client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
    const rejected = assert.rejects(pending, (error) => error.status === 503);
    Atomics.wait(barrier, 0, 0, 1000);
    assert.equal(Atomics.load(barrier, 0), 1, 'Worker captured its prepared sample');
    Atomics.store(barrier, 1, 1);
    Atomics.notify(barrier, 1);
    // Less than5000ms hint TTL; it is the4000ms phase deadline that must reject.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 4200);
    await rejected;
    assert.notEqual(client.worker, worker);
    assert.equal(client.available, false);
    assert(!preparedEvents(directory).includes('commit'));
  }));

for (const phase of ['prepare', 'commit'])
  test(`fenced ${phase} is clamped by a short HTTP remainder instead of receiving4000ms`, () =>
    preparedFixture(
      phase === 'prepare' ? 'slow' : 'commit-late',
      async ({ client, worker, directory }) => {
        const started = performance.now();
        const preparing = client.prepare(randomUUID(), randomUUID(), performance.now() + 1000);
        const operation = phase === 'commit' ? (await preparing).commit() : preparing;
        await assert.rejects(operation, (error) => error.status === 503);
        assert(performance.now() - started < 2000, 'HTTP remainder cannot restart a phase');
        assert.notEqual(client.worker, worker);
        if (phase === 'prepare') assert(!preparedEvents(directory).includes('commit'));
      },
    ));

test('fenced3400ms capture plus middle await leaves only the original7000ms for commit', () =>
  preparedFixture('slow', async ({ client, worker, directory }) => {
    const started = performance.now();
    const prepared = await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
    await new Promise((done) => setTimeout(done, 1800));
    //3400+1800+2200 exceeds7000 although both actual phases are below4000.
    await assert.rejects(prepared.commit(), (error) => error.status === 503);
    assert(performance.now() - started < 8000);
    assert.notEqual(client.worker, worker);
    assert.equal(client.available, false);
    assert.deepEqual(preparedEvents(directory), ['prepare', 'commit']);
  }));

test('fenced abort or close during either long phase ignores its actual late positive reply', async (t) => {
  for (const phase of ['prepare', 'commit'])
    for (const mode of ['abort', 'close'])
      await t.test(`${phase}-${mode}`, () =>
        preparedFixture(
          phase === 'prepare' ? 'slow' : 'slow-commit',
          async ({ client, worker, directory }) => {
            const controller = new AbortController();
            const barrier = new Int32Array(new SharedArrayBuffer(8));
            // Commit setup finishes before its barrier is installed, so the signal
            // acknowledges the actual selected phase, not a prior prepare sample.
            const prepared =
              phase === 'commit'
                ? await client.prepare(
                    randomUUID(),
                    randomUUID(),
                    performance.now() + 8000,
                    controller.signal,
                  )
                : null;
            worker.postMessage({ kind: 'fixture-sync', shared: barrier.buffer });
            const late = nextFencedReply(
              worker,
              phase === 'prepare' ? 'prepared' : 'fenced-result',
            );
            const pending = prepared
              ? prepared.commit()
              : client.prepare(
                  randomUUID(),
                  randomUUID(),
                  performance.now() + 8000,
                  controller.signal,
                );
            let settled = false;
            pending.then(
              () => {
                settled = true;
              },
              () => {
                settled = true;
              },
            );
            const rejected = assert.rejects(pending, (error) => error.status === 503);
            await Atomics.waitAsync(barrier, 0, 0, 1000).value;
            assert.equal(Atomics.load(barrier, 0), 1, 'Actual Worker entered selected phase');
            await new Promise((done) => setTimeout(done, 3100));
            assert.equal(settled, false, 'Cancel/close must interrupt still-pending phase');
            const closedAt = performance.now();
            if (mode === 'abort') controller.abort();
            else client.close();
            await rejected;
            assert(performance.now() - closedAt < 100, 'Abort/close cannot await Worker phase');
            const message = await late;
            assert.equal(message.available, true);
            assert.equal(Object.hasOwn(message, 'value'), phase === 'commit');
            assert.notEqual(client.worker, worker);
            assert.equal(client.available, false, 'Retired generation cannot restore readiness');
            assertRetiredPreparedEvents(
              directory,
              phase === 'commit' ? ['prepare', 'commit'] : ['prepare'],
            );
          },
        ),
      );
});

test('fenced owner wait consumes the original 7000ms deadline and cannot start a late commit', () =>
  preparedFixture('normal', async ({ client, directory }) => {
    const p = await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
    await new Promise((done) => setTimeout(done, 7100));
    await assert.rejects(p.commit());
    assert(!preparedEvents(directory).includes('commit'));
  }));
test('fenced HTTP remainder, cancellation and close cannot dispatch a late owner success', async (t) => {
  for (const mode of ['http-remainder', 'cancel', 'close'])
    await t.test(mode, () =>
      preparedFixture('normal', async ({ client, directory }) => {
        const controller = new AbortController();
        const p = await client.prepare(
          randomUUID(),
          randomUUID(),
          performance.now() + (mode === 'http-remainder' ? 200 : 8000),
          controller.signal,
        );
        if (mode === 'http-remainder') await new Promise((done) => setTimeout(done, 250));
        else if (mode === 'cancel') controller.abort();
        else client.close();
        await assert.rejects(p.commit());
        assert(!preparedEvents(directory).includes('commit'));
        assert.equal(client.available, false);
      }),
    );
});
test('fenced Main stall cannot accept a queued commit result after the absolute phase deadline', () =>
  preparedFixture('queued-result', async ({ client, worker }) => {
    const p = await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
    const barrier = new Int32Array(new SharedArrayBuffer(8));
    worker.postMessage({ kind: 'fixture-sync', shared: barrier.buffer });
    const pending = p.commit();
    const rejected = assert.rejects(pending);
    Atomics.wait(barrier, 0, 0, 1000);
    assert.equal(Atomics.load(barrier, 0), 1, 'Commit sample exists before Main stalls');
    Atomics.store(barrier, 1, 1);
    Atomics.notify(barrier, 1);
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 4200);
    await rejected;
    assert.notEqual(client.worker, worker);
    assert.equal(client.available, false);
  }));

test('fenced adapter preserves transport status and disposes a handle after target changes', async () => {
  let current = target,
    disposed = 0;
  const observer = new DesktopSuggestionObserver({
    enabled: false,
    root: '.',
    directory: '.',
    target: () => current,
  });
  const thread = randomUUID(),
    turn = randomUUID();
  const client = {
    available: true,
    targetKey: JSON.stringify([
      target.agentId,
      target.hostId,
      target.workspaceId,
      target.runtimeId,
    ]),
    close() {},
    prepare: async () => {
      throw Object.assign(new Error('synthetic transport failure'), { status: 409 });
    },
  };
  observer.workerClient = client;
  try {
    await assert.rejects(
      observer.prepareBoundSourceAsync(target, thread, turn, performance.now() + 8000),
      (e) =>
        e.name === 'Error' &&
        e.constructor.name === 'SuggestionObservationError' &&
        e.status === 409,
    );
    let release;
    client.prepare = () =>
      new Promise((resolve) => {
        release = resolve;
      });
    const preparing = observer.prepareBoundSourceAsync(
      target,
      thread,
      turn,
      performance.now() + 8000,
    );
    current = { ...target, workspaceId: 'changed' };
    release({
      commit: async () => {
        throw new Error('Must never commit after target changes');
      },
      dispose() {
        disposed++;
      },
    });
    await assert.rejects(preparing, (e) => e.status === 409);
    assert.equal(disposed, 1);
  } finally {
    observer.close();
  }
});
test('fenced duplicate prepare and duplicate commit consume or dispose only one continuation', async (t) => {
  await t.test('duplicate-prepare', () =>
    preparedFixture('prepare-duplicate', async ({ client, worker }) => {
      const p = await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
      await new Promise((done) => setImmediate(done));
      await assert.rejects(p.commit());
      assert.notEqual(client.worker, worker);
    }),
  );
  await t.test('duplicate-commit', () =>
    preparedFixture('queued-result', async ({ client, directory }) => {
      const p = await client.prepare(randomUUID(), randomUUID(), performance.now() + 8000);
      const first = p.commit();
      const firstRejected = assert.rejects(first);
      await assert.rejects(p.commit());
      await firstRejected;
      assert(preparedEvents(directory).filter((x) => x === 'commit').length <= 1);
    }),
  );
});

test('Reader replacement waits for actual retirement instead of the old one-second retry', async () => {
  const directory = root();
  const client = new DesktopSuggestionWorkerClient(
    { directory, root: directory, traceRoot: directory, target },
    new URL('./fixtures/windows-trace-worker-retirement.mjs', import.meta.url),
  );
  client.start();
  const first = client.worker;
  const exited = once(first, 'exit');
  try {
    await ready(client);
    await assert.rejects(client.read(randomUUID(), randomUUID()), (error) => error.status === 503);
    await new Promise((resolve) => setTimeout(resolve, 1200));
    client.start();
    assert.notEqual(
      first.threadId,
      -1,
      'The old Reader is still within its original termination grace',
    );
    assert.equal(JSON.parse(fs.readFileSync(join(directory, 'retirement-generations.json'))), 1);
    assert.equal(client.available, false);
    await exited;
    await ready(client);
    assert.equal(JSON.parse(fs.readFileSync(join(directory, 'retirement-generations.json'))), 2);
  } finally {
    await closeAvailabilityFixture(client, first, directory);
  }
});

test('batch64 accepts small items while preserving byte bounds and rejecting the 65th item', () => {
  const request = {
    schemaVersion: 1,
    kind: 'snapshot-many',
    id: 1,
    items: Array.from({ length: 64 }, (_, index) => ({
      index,
      path: `C:\\fixture\\traces\\small-${index}`,
      maximum: 64,
    })),
  };
  assert(encodeTraceSnapshotBatch(request).startsWith('B\t'));
  const overflow = structuredClone(request);
  overflow.items.push({ ...overflow.items[0], index: 64 });
  assert.throws(() => encodeTraceSnapshotBatch(overflow));
  const large = structuredClone(request);
  large.items[63].maximum = 16 * 1024 * 1024;
  assert.throws(() => encodeTraceSnapshotBatch(large));
  const wideBodies = request.items.map((item) => ({ ...item, maximum: 16_384 }));
  assert.equal(traceSnapshotBatchFits(wideBodies), false, 'The 256 KiB response cap is unchanged');
  const deep = request.items.map((item) => ({
    ...item,
    path: 'C:\\' + 'a\\'.repeat(200) + `file-${item.index}`,
  }));
  assert.equal(
    traceSnapshotBatchFits(deep),
    false,
    'Response chain estimate still bounds a 64-item request',
  );
});

for (const mode of [
  'normal',
  'wrong-id',
  'short',
  'extra-item',
  'reordered',
  'second-failure',
  'batch-timeout',
  'closed',
  'owner-close',
  'duplicate-late',
  'stale-while-next',
])
  test(`actual Worker B8 retains one 750ms operation and exact retirement: ${mode}`, async () => {
    const result = await batchTransportFixture(mode, {}, 8);
    assert.equal(result.allRetired, true);
    if (mode === 'owner-close') assert.equal(result.answer, undefined);
    else assert.equal(result.answer.passed, mode === 'normal');
    assert.equal(result.requests, mode === 'closed' ? 0 : mode === 'stale-while-next' ? 2 : 1);
    if (mode === 'normal') assert.equal(result.answer.identities.length, 8);
    if (mode === 'batch-timeout') {
      assert.equal(result.partialExecuted, true);
      assert(result.answer.elapsed < 1000, 'Eight items share the original 750ms timer');
    }
    if (mode === 'duplicate-late' || mode === 'stale-while-next')
      assert.equal(result.answer.firstPassed, true);
  });

for (const mode of [
  'normal',
  'wrong-id',
  'short',
  'extra-item',
  'reordered',
  'second-failure',
  'batch-timeout',
  'duplicate-late',
  'stale-while-next',
])
  test(`actual Worker cold 64-item read retains each frame deadline and exact retirement: ${mode}`, async () => {
    const result = await batchTransportFixture(mode, {}, 64);
    assert.equal(result.allRetired, true);
    assert.equal(result.answer.passed, mode === 'normal');
    assert.equal(
      result.requests,
      ['normal', 'duplicate-late', 'stale-while-next'].includes(mode) ? 2 : 1,
      'Only a successful cold prefix may dispatch its remaining frame; a rejected frame is never replayed',
    );
    if (mode === 'normal') assert.equal(result.answer.identities.length, 64);
    if (mode === 'batch-timeout') {
      assert.equal(result.partialExecuted, true);
      assert(
        result.answer.elapsed < 1000,
        'The rejected cold prefix retains its original750ms timer',
      );
    }
    if (mode === 'duplicate-late') assert.equal(result.answer.firstPassed, true);
    if (mode === 'stale-while-next')
      assert.equal(
        result.answer.firstPassed,
        undefined,
        'A stale reply between subframes cannot publish the first logical read',
      );
  });

for (const mode of ['cold-second-timeout', 'cold-shared-budget'])
  test(`actual cold subframes retain Native750ms and the original aggregate phase budget: ${mode}`, async () => {
    const result = await batchTransportFixture(mode, {}, 64);
    assert.equal(result.allRetired, true);
    assert.equal(result.requests, 2);
    assert.equal(result.answer.passed, false);
    assert.equal(result.answer.firstPassed, undefined);
    assert.equal(result.answer.identities, undefined, 'No partial prefix is published');
    if (mode === 'cold-second-timeout') {
      assert.equal(result.partialExecuted, true);
      assert(result.answer.elapsed < 1100, 'The second frame still expires at750ms');
    } else {
      assert(
        result.answer.elapsed < 650,
        'The remaining frame receives only the original300ms phase remainder',
      );
    }
  });

function roundtripColdBatch(request) {
  const encoded = encodeTraceSnapshotBatch(request);
  const input = JSON.parse(Buffer.from(encoded.slice(2), 'base64').toString('utf8'));
  const acl = Buffer.alloc(32).toString('base64');
  const response = {
    schemaVersion: 1,
    kind: 'snapshot-many',
    id: input.id,
    items: input.items.map((item) => {
      const number = Number(item.path.match(/item-([0-9]+)$/)[1]);
      const chain = item.path
        .split('\\')
        .map((_part, level) => `1:0:${number * 10 + level}:0:0:${acl}`);
      return {
        index: item.index,
        snapshot: {
          identity: createHash('sha256').update(chain.join('|')).digest('base64'),
          chain,
          bytes: Buffer.from(String(number)).subarray(0, item.maximum).toString('base64'),
        },
      };
    }),
  };
  return decodeTraceSnapshotBatch(parseTraceSnapshotBatchJSON(JSON.stringify(response)), request);
}
const coldBatchItems = () =>
  Array.from({ length: 64 }, (_value, index) => ({
    path: `C:\\fixture\\traces\\item-${index}`,
    maximum: 16,
  }));

test('cold phase sends one eight-item wave then the remaining guards once, preserving wire order and IDs', () => {
  const frames = [];
  const sender = createTraceSnapshotBatchSender(
    'C:\\fixture\\traces',
    (request) => {
      frames.push(structuredClone(request));
      return roundtripColdBatch(request);
    },
    () => assert.fail('Valid replies must not retire the sender'),
  );
  const check = () =>
    assert.deepEqual(
      sender.read('C:\\fixture\\traces', coldBatchItems()).map((value) => value.bytes.toString()),
      Array.from({ length: 64 }, (_value, index) => String(index)),
    );
  check();
  check();
  sender.beginPhase();
  check();
  assert.deepEqual(
    frames.map((frame) => frame.items.length),
    [8, 56, 64, 8, 56],
  );
  assert.deepEqual(
    frames.map((frame) => frame.id),
    [1, 2, 3, 4, 5],
  );
  for (const frame of frames)
    assert.deepEqual(
      frame.items.map((item) => item.index),
      frame.items.map((_item, index) => index),
    );
});

test('a late malformed item rejects before any prefix frame and a single item does not warm parallel work', () => {
  const frames = [];
  const sender = createTraceSnapshotBatchSender(
    'C:\\fixture\\traces',
    (request) => {
      frames.push(request.items.length);
      return roundtripColdBatch(request);
    },
    () => assert.fail('Validation failure has not reached Native'),
  );
  const invalid = coldBatchItems();
  invalid[63].path = '\\\\server\\share\\item';
  assert.throws(() => sender.read('C:\\fixture\\traces', invalid));
  assert.throws(() => sender.read('C:\\other', coldBatchItems()));
  assert.deepEqual(frames, []);
  sender.read('C:\\fixture\\traces', coldBatchItems().slice(0, 1));
  sender.read('C:\\fixture\\traces', coldBatchItems());
  assert.deepEqual(frames, [1, 8, 56]);
});

for (const failureFrame of [1, 2])
  test(`cold frame ${failureFrame} rejection publishes no partial result and cannot be retried`, () => {
    let calls = 0,
      retired = 0;
    const sender = createTraceSnapshotBatchSender(
      'C:\\fixture\\traces',
      (request) => {
        if (++calls === failureFrame) throw Error('native-frame-rejected');
        return roundtripColdBatch(request);
      },
      () => retired++,
    );
    assert.throws(
      () => sender.read('C:\\fixture\\traces', coldBatchItems()),
      /native-frame-rejected/,
    );
    assert.equal(calls, failureFrame);
    assert.equal(retired, 1);
    sender.beginPhase();
    assert.throws(() => sender.read('C:\\fixture\\traces', coldBatchItems()));
    assert.equal(calls, failureFrame, 'A new phase cannot revive or resend a failed frame');
  });

test('validated caller items are frozen before the first wire frame', () => {
  const items = coldBatchItems();
  const frames = [];
  const sender = createTraceSnapshotBatchSender(
    'C:\\fixture\\traces',
    (request) => {
      frames.push(structuredClone(request));
      if (frames.length === 1) items[63].path = 'C:\\changed\\item-999';
      return roundtripColdBatch(request);
    },
    () => assert.fail('No native failure expected'),
  );
  const values = sender.read('C:\\fixture\\traces', items);
  assert.equal(values[63].bytes.toString(), '63');
  assert.equal(frames[1].items.at(-1).path, 'C:\\fixture\\traces\\item-63');
});

for (const mutate of [false, true])
  test(`cold subframes retain every filesystem guard and detect an earlier namespace swap: ${mutate}`, async () =>
    batchSnapshotFixture(({ TraceSnapshot, root, local, counts, reset, change }) => {
      const snapshot = new TraceSnapshot(true);
      snapshot.root(root);
      for (let index = 0; index < 64; index++) {
        const path = root + `\\item-${index}`;
        fs.mkdirSync(local(path));
        snapshot.directory(path);
      }
      reset();
      let injected = false;
      if (mutate)
        change((when, index) => {
          if (!injected && when === 'before' && counts.batch === 2 && index === 0) {
            fs.renameSync(local(root + '\\item-0'), local(root + '\\item-0-old'));
            fs.mkdirSync(local(root + '\\item-0'));
            injected = true;
          }
        });
      if (mutate) assert.throws(() => snapshot.verify());
      else snapshot.verify();
      assert.equal(injected, mutate);
      assert.equal(
        counts.snapshots,
        mutate ? 64 : 65,
        'All admitted frame guards execute; a known failure aborts the remaining singleton',
      );
      assert.equal(counts.batch, 2, 'Only delivery grouping changes');
      assert.equal(
        counts.single,
        mutate ? 0 : 1,
        'A failed frame never falls back to single reads',
      );
    }, createTraceSnapshotBatchSender));
