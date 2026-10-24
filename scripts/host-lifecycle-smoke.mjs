import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { renameSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { channel } from 'node:diagnostics_channel';
import { setTimeout as delay } from 'node:timers/promises';
import { RuntimePersistence } from '../packages/codex-adapter/dist/runtime-persistence.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  assertPrivateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { superviseWorker, safeWorkerFailure } from './host-worker-supervisor.mjs';
import { createWindowsTraceDiagnostics } from '../packages/codex-adapter/dist/windows-trace-diagnostics.js';

// An independently created state and ephemeral localhost port only. On native
// Windows successful writes use the actual private ACL helper, never a stub.
const directory = await mkdtemp(join(tmpdir(), 'codex-host-lifecycle-'));
protectWindowsStateDirectory(directory);
const file = join(directory, 'runtime.json');
const reports = [];
let fault;
let protectedFiles = 0;
let store;
let supervisor;
const state = {
  hostId: 'isolated-fixture-host',
  threads: [],
  receipts: [{ id: 'stable-operation', state: 'uncertain' }],
};
const waitUntil = async (condition) => {
  for (let i = 0; i < 200; i++) {
    if (await condition()) return;
    await delay(25);
  }
  throw new Error('Isolated lifecycle condition timed out');
};
const server = createServer((_request, response) => {
  response.setHeader('Content-Type', 'application/json');
  response.end(JSON.stringify({ hostId: state.hostId, isolated: true }));
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
try {
  store = new RuntimePersistence(file, directory, {
    retryDelays: [10, 20, 30],
    cooldownMs: 1000,
    protect(path) {
      if (fault === 'protect')
        throw Object.assign(new Error('private fixture'), { code: 'ETIMEDOUT' });
      protectWindowsStateFile(path);
      assertPrivateFile(path);
      protectedFiles++;
    },
    rename(from, to) {
      if (fault === 'publish')
        throw Object.assign(new Error('private fixture'), { code: 'EACCES' });
      renameSync(from, to);
    },
    report(scope, error, attempt) {
      reports.push({ scope, stage: error.stage, attempt });
    },
  });
  store.save(state);
  const foreignTemporary = join(directory, 'runtime.json.unrelated.tmp');
  await writeFile(foreignTemporary, 'unrelated synthetic fixture');
  for (const stage of ['protect', 'publish']) {
    const original = await readFile(file);
    fault = stage;
    assert.throws(
      () => store.save(state),
      (error) => error.stage === stage,
    );
    assert.deepEqual(
      await readFile(file),
      original,
      'Failed publication preserves the previous state',
    );
    assert.deepEqual(
      (await readdir(directory)).filter((name) => name.endsWith('.tmp')),
      ['runtime.json.unrelated.tmp'],
    );
    const before = reports.length;
    for (let i = 0; i < 10; i++) store.schedule(() => state);
    await waitUntil(() => reports.length - before === 3);
    for (let i = 0; i < 10; i++) store.schedule(() => state);
    const responses = await Promise.all(
      Array.from({ length: 20 }, () => fetch(`http://127.0.0.1:${server.address().port}/healthz`)),
    );
    assert(responses.every((response) => response.status === 200));
    await delay(100);
    assert.equal(reports.length - before, 3, 'Repeated events cannot reset the retry budget');
    fault = undefined;
    state.receipts[0].state = stage === 'protect' ? 'failed' : 'completed';
    store.save(state); // Explicit recovery, still using the actual ACL helper.
    assert.equal(
      new RuntimePersistence(file, directory).load().receipts[0].state,
      state.receipts[0].state,
    );
  }

  // A poisoned cache must not starve another thread or spin through its retry budget.
  fault = undefined;
  const good = { id: 'good', messages: [{ text: 'synthetic history' }] };
  const bad = {
    id: 'bad',
    messages: [
      {
        toJSON() {
          throw Error('private synthetic data');
        },
      },
    ],
  };
  state.threads = [bad, good];
  store.save(state);
  await waitUntil(() => reports.filter((entry) => entry.scope === 'history-save').length === 3);
  const historyFiles = await readdir(join(directory, 'runtime-history-v1'));
  assert.equal(historyFiles.length, 1, 'Healthy history commits even while another cache fails');
  fault = 'protect';
  store.schedule(() => state);
  assert.throws(() => store.close());
  const stopped = reports.length;
  store.schedule(() => state);
  store.dirty(good);
  await delay(100);
  assert.equal(reports.length, stopped, 'Close stops both background writers after a failed flush');
  store.close();

  // Fault clears with no subsequent schedule/dirty/save: cooldown must wake itself.
  for (const scope of ['background', 'history']) {
    const idleDirectory = join(directory, 'idle-' + scope);
    const idleFile = join(idleDirectory, 'runtime.json');
    let failing = false;
    const attempts = [];
    const idle = new RuntimePersistence(idleFile, idleDirectory, {
      retryDelays: [5, 10],
      cooldownMs: 100,
      protect(path) {
        const selected =
          scope === 'history'
            ? path.includes('runtime-history-v1')
            : !path.includes('runtime-history-v1');
        if (failing && selected)
          throw Object.assign(new Error('synthetic disk full'), { code: 'ENOSPC' });
        protectWindowsStateFile(path);
        assertPrivateFile(path);
      },
      report(_scope, _error, attempt) {
        attempts.push({ attempt, at: Date.now() });
      },
    });
    try {
      const idleState = {
        hostId: 'idle-fixture',
        threads: [],
        receipts: [{ id: 'unchanged-operation', state: 'uncertain' }],
      };
      idle.save(idleState);
      const original = await readFile(idleFile);
      failing = true;
      if (scope === 'background') idle.schedule(() => idleState);
      else idle.dirty({ id: 'idle-history', messages: [{ text: 'latest synthetic history' }] });
      await waitUntil(() => attempts.length >= 4);
      assert.deepEqual(
        attempts.slice(0, 4).map((v) => v.attempt),
        [1, 2, 1, 2],
      );
      assert(attempts[2].at - attempts[1].at >= 90, 'Cycles respect cooldown');
      assert.deepEqual(
        await readFile(idleFile),
        original,
        'Failed retries preserve durable uncertain receipt',
      );
      failing = false;
      await waitUntil(() =>
        scope === 'background'
          ? idle.status.backgroundSave === 'idle'
          : idle.status.historyPending === 0,
      );
      assert.equal(JSON.parse(await readFile(idleFile, 'utf8')).receipts[0].state, 'uncertain');
      if (scope === 'history')
        assert.equal((await readdir(join(idleDirectory, 'runtime-history-v1'))).length, 1);
      // Cancel while cooling and ensure no timer survives close, including a failed final flush.
      failing = true;
      const before = attempts.length;
      if (scope === 'background') idle.schedule(() => idleState);
      else idle.dirty({ id: 'stopped-history', messages: [{ text: 'synthetic stopped history' }] });
      await waitUntil(() => attempts.length >= before + 2);
      try {
        idle.close();
      } catch {
        /* Expected final history flush fault. */
      }
      const closedAttempts = attempts.length;
      await delay(250);
      assert.equal(attempts.length, closedAttempts, 'No cooldown writes after close');
    } finally {
      try {
        idle.close();
      } catch {
        /* Synthetic final flush. */
      }
    }
  }

  // Real Node children crash twice, then serve HTTP. No desktop/model is launched.
  const childFile = join(directory, 'isolated-child.mjs');
  const marker = join(directory, 'child-count');
  const ready = join(directory, 'child-port');
  await writeFile(
    childFile,
    `
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
const marker = ${JSON.stringify(marker)};
const count = existsSync(marker) ? Number(readFileSync(marker, 'utf8')) + 1 : 1;
writeFileSync(marker, String(count));
process.stderr.write('SECRET_FIXTURE_MUST_NOT_BE_LOGGED\\n');
if (count < 3) process.exit(1);
const server = createServer((_req, res) => res.end('recovered'));
server.listen(0, '127.0.0.1', () => writeFileSync(${JSON.stringify(ready)}, String(server.address().port)));
process.on('message', message => { if (message.command === 'host-worker-stop') server.close(() => process.exit(0)); });
process.once('disconnect', () => process.exit(1));
`,
  );
  const workerReports = [];
  supervisor = superviseWorker({
    executable: process.execPath,
    args: [childFile],
    report: (entry) => workerReports.push(entry),
    delays: [10, 20],
    healthyMs: 10_000,
  });
  await waitUntil(async () => (await readdir(directory)).includes('child-port'));
  const port = Number(await readFile(ready, 'utf8'));
  assert.equal(await (await fetch(`http://127.0.0.1:${port}`)).text(), 'recovered');
  assert.equal(workerReports.filter((entry) => entry.event === 'exited').length, 2);
  assert.deepEqual(
    workerReports.filter((entry) => entry.event === 'retry').map((entry) => entry.delayMs),
    [10, 20],
  );
  assert(!JSON.stringify(workerReports).includes('SECRET_FIXTURE'));
  assert.deepEqual(
    safeWorkerFailure({
      message: 'secret',
      code: 'EACCES',
      syscall: 'rename',
      stack:
        'Error: secret\n at x (C:\\private\\runtime-persistence.js:12:34)\n at secret-key.js:1:2',
    }),
    {
      code: 'EACCES',
      syscall: 'rename',
      stage: undefined,
      nativeStatus: undefined,
      nativeType: undefined,
      stack: ['runtime-persistence.js:12:34'],
    },
  );
  await supervisor.stop();
  await delay(100);
  assert.equal(await readFile(marker, 'utf8'), '3', 'Stopping cannot launch a fourth child');

  // Real factory records travel through real child stderr, including split lines.
  // Subscriber-only capture avoids emitting fixture records from this parent.
  const traceChannel = channel('agent-hub.windows-trace.summary');
  const samples = [];
  const capture = (value) => samples.push(value);
  const oldTraceFlag = process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
  const restoreTraceFlag = () => {
    if (oldTraceFlag === undefined) delete process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
    else process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS = oldTraceFlag;
  };
  delete process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
  traceChannel.subscribe(capture);
  try {
    for (const scope of ['reader', 'client', 'native', 'host']) {
      const diagnostic = createWindowsTraceDiagnostics(scope, 7);
      diagnostic.phase('prepare', () => {});
      diagnostic.phase('parse', () => {});
      diagnostic.phase('verify', () => {});
      for (const kind of ['P', 'S', 'T']) diagnostic.nativeCall(kind).end(true);
      diagnostic.nativeCall('B', 8); // Pending is evidence, not a zero-completed success.
      diagnostic.end('phase_deadline', {
        operation: scope === 'reader' ? 'parser' : 'read',
        stage: 'interrupted',
        phase: 'verify',
        request: 3,
        deadlineKind: 'phase',
        selectedDeadlineKind: 'shared_rpc',
        deadlineTriggered: true,
        deadlineMask: 1,
        phaseRemainingMs: -2,
        remainingMs: -1,
        parserReadSnapshotCalls: 2,
        parserCatalogWalks: 6,
        parserGuardCount: 8,
        readerExited: true,
        transportExited: false,
        childExited: false,
        stdioClosed: false,
      });
    }
  } finally {
    traceChannel.unsubscribe(capture);
    restoreTraceFlag();
  }
  assert.equal(samples.length, 4);
  const secret = 'SECRET_WINDOWS_TRACE_FIXTURE_MUST_NOT_LEAK';
  const wrappers = samples.map((sample) => ({
    childPid: -999,
    windowsTrace: {
      ...sample,
      childPid: -998,
      supervisorPid: -997,
      body: secret,
      path: secret,
      error: { message: secret },
      phases: {
        ...sample.phases,
        privatePhase: { body: secret },
        prepare: { ...sample.phases.prepare, path: secret },
      },
      native: { ...sample.native, X: { secret }, B: { ...sample.native.B, body: secret } },
    },
  }));
  const invalid = [
    { windowsTrace: { ...samples[0], scope: secret } },
    { windowsTrace: { ...samples[0], native: { B: { ...samples[0].native.B, pending: secret } } } },
    { windowsTrace: { schemaVersion: 2, body: secret } },
    { windowsTrace: { ...samples[0], phases: { prepare: { calls: secret, elapsedMs: 0 } } } },
  ];
  const firstLine = JSON.stringify(wrappers[0]) + '\n';
  const tail = wrappers
    .slice(1)
    .concat(invalid)
    .map((value) => JSON.stringify(value) + '\n')
    .join('');
  assert(
    Buffer.byteLength(firstLine + tail + secret) < 16_384,
    'Legitimate multiline fixture stays inside the unchanged buffer cap',
  );
  const traceChildFile = join(directory, 'trace-child.mjs');
  await writeFile(
    traceChildFile,
    `
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
const [mode, marker] = process.argv.slice(2);
const count = existsSync(marker) ? Number(readFileSync(marker, 'utf8')) + 1 : 1;
process.on('message', message => { if (message.command === 'host-worker-stop') process.exit(0); });
const write = text => new Promise((resolve, reject) => process.stderr.write(text, error => error ? reject(error) : resolve()));
if (mode === 'oversized') {
  await write(JSON.stringify({windowsTrace: ${JSON.stringify(samples[0])}, body: ${JSON.stringify(secret)}.repeat(600)}) + '\\n');
} else if (mode === 'report-fault') {
  await write(${JSON.stringify(firstLine)});
} else {
  await write(${JSON.stringify(secret + '\n')});
  const first = ${JSON.stringify(firstLine)};
  await write(first.slice(0, 31));
  await write(first.slice(31) + ${JSON.stringify(tail)});
}
writeFileSync(marker, String(count));
if (mode === 'report-fault' && count === 1) process.exit(7);
`,
  );
  let forwardedTraceRecords = 0;
  for (const [mode, flag] of [
    ['default-off', undefined],
    ['not-one', 'true'],
    ['enabled', '1'],
    ['oversized', '1'],
    ['report-fault', '1'],
  ]) {
    if (flag === undefined) delete process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
    else process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS = flag;
    const traceMarker = join(directory, 'trace-count-' + mode);
    const entries = [],
      warnings = [];
    const originalError = console.error;
    if (mode === 'report-fault') console.error = (value) => warnings.push(value);
    try {
      supervisor = superviseWorker({
        executable: process.execPath,
        args: [traceChildFile, mode, traceMarker],
        report(entry) {
          entries.push(entry);
          if (mode === 'report-fault' && entry.event === 'windows-trace-summary')
            throw new Error(secret);
        },
        delays: [10],
        healthyMs: 10_000,
      });
      const launches = mode === 'report-fault' ? 2 : 1;
      await waitUntil(async () => {
        try {
          return Number(await readFile(traceMarker, 'utf8')) === launches;
        } catch {
          return false;
        }
      });
      if (flag === '1' && mode !== 'oversized')
        await waitUntil(
          () =>
            entries.filter((entry) => entry.event === 'windows-trace-summary').length ===
            (mode === 'report-fault' ? 2 : 4),
        );
      await supervisor.stop(); // close guarantees all stderr consumption before assertions.
      const summaries = entries.filter((entry) => entry.event === 'windows-trace-summary');
      if (mode === 'enabled') {
        assert.equal(
          summaries.length,
          4,
          'All four real factory scopes survive split/multiline input',
        );
        for (let i = 0; i < summaries.length; i++) {
          assert.deepEqual(summaries[i].windowsTrace, samples[i]);
          assert.equal(
            summaries[i].childPid,
            entries.find((entry) => entry.event === 'started').childPid,
          );
          assert.notEqual(
            summaries[i].childPid,
            samples[i].pid,
            'Reader summary PID may differ from actual launched Host',
          );
        }
        forwardedTraceRecords = summaries.length;
      } else if (mode === 'report-fault') {
        assert.equal(
          entries.filter((entry) => entry.event === 'exited' && entry.exitCode === 7).length,
          1,
        );
        assert.deepEqual(
          entries.filter((entry) => entry.event === 'retry').map((entry) => entry.delayMs),
          [10],
        );
        assert.equal(entries.filter((entry) => entry.event === 'started').length, 2);
        assert.deepEqual(warnings, [
          'HOST_DIAGNOSTICS_UNAVAILABLE',
          'HOST_DIAGNOSTICS_UNAVAILABLE',
        ]);
        for (const entry of summaries) {
          assert(
            entries.some(
              (started) => started.event === 'started' && started.childPid === entry.childPid,
            ),
          );
          assert.deepEqual(entry.windowsTrace, samples[0]);
        }
      } else
        assert.equal(summaries.length, 0, 'Default/non-1 flag and oversized records never forward');
      assert(
        !JSON.stringify(entries).includes(secret),
        'Noise, invalid nested values and unknown secret fields are never logged',
      );
      await delay(50);
      assert.equal(
        Number(await readFile(traceMarker, 'utf8')),
        launches,
        'Stop/reporter failure never authorizes an extra generation',
      );
    } finally {
      await supervisor?.stop();
      console.error = originalError;
      restoreTraceFlag();
    }
  }
  console.log(
    JSON.stringify({
      result: 'passed',
      platform: process.platform,
      nativeWindowsAcl: process.platform === 'win32',
      protectedFiles,
      backgroundRetryLimit: 3,
      concurrentReadResponses: 40,
      recoveredChildLaunch: 3,
      stoppedWithoutRespawn: true,
      windowsTraceForwarded: forwardedTraceRecords,
      windowsTraceModes: 5,
      windowsTraceReporterFailureRecovered: true,
      idlePersistenceRecovery: true,
      cooldownStoppedWithoutWrites: true,
      evidence:
        'isolated persistence and real Node child processes; no production Host or model task',
    }),
  );
} finally {
  await supervisor?.stop();
  try {
    store?.close();
  } catch {
    /* Expected synthetic close failure already asserted. */
  }
  await new Promise((resolve) => server.close(resolve));
  server.closeAllConnections();
  await rm(directory, { recursive: true, force: true });
}
