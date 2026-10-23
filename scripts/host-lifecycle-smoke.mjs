import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { renameSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { RuntimePersistence } from '../packages/codex-adapter/dist/runtime-persistence.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  assertPrivateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { superviseWorker, safeWorkerFailure } from './host-worker-supervisor.mjs';

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
