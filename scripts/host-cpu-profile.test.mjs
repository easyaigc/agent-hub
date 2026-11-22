import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fork } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { chmod, lstat, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import {
  armCpuProfile,
  disarmCpuProfile,
  cpuProfileStatus,
  requestCpuProfile,
  stopCpuProfile,
} from '../apps/host-agent/dist/cpu-profile-control.js';
import { readPrivateJSON, profileDirectory } from '../apps/host-agent/dist/cpu-profile-files.js';
import { beginCpuStage, startCpuStages } from '../packages/codex-adapter/dist/cpu-stages.js';

const fixture = fileURLToPath(new URL('./fixtures/cpu-profile-host.mjs', import.meta.url));
async function until(fn, timeoutMs = 20_000) {
  const end = performance.now() + timeoutMs;
  let last;
  do {
    try {
      const value = await fn();
      if (value) return value;
    } catch (error) {
      last = error;
    }
    await sleep(50);
  } while (performance.now() < end);
  throw last ?? new Error('Fixture deadline exceeded');
}
async function withHost(body) {
  const state = await mkdtemp(join(tmpdir(), 'host-cpu-profile-'));
  await chmod(state, 0o700);
  const hostId = randomUUID();
  let child;
  try {
    armCpuProfile(state, hostId, fixture);
    child = fork(fixture, [state, hostId], { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
    let errors = '';
    child.stderr.on('data', (bytes) => {
      errors += bytes;
    });
    const ready = await until(() => {
      if (child.exitCode !== null) throw new Error(errors);
      const value = cpuProfileStatus(state);
      return value.state === 'idle' && value;
    });
    await body({ state, child, ready, hostId });
  } finally {
    if (child && child.exitCode === null) {
      child.send({ close: true });
      await until(() => child.exitCode !== null, 15_000);
    }
    await rm(state, { recursive: true });
  }
}

test('disabled stage path allocates no span and active metrics contain fixed labels only', () => {
  assert.equal(beginCpuStage('persistence.save'), undefined);
  const finish = startCpuStages();
  const end = beginCpuStage('persistence.serialize');
  end();
  const result = finish();
  assert.equal(result.stages['persistence.serialize'].calls, 1);
  assert.ok(result.processCpuUs.user >= 0);
  assert.equal(beginCpuStage('persistence.save'), undefined);
});

test('private control files reject symlinks and exposed mode', async () => {
  const state = await mkdtemp(join(tmpdir(), 'host-cpu-private-'));
  try {
    await chmod(state, 0o700);
    const file = join(state, 'real.json');
    await writeFile(file, '{}', { mode: 0o600 });
    await symlink(file, join(state, 'link.json'));
    assert.throws(() => readPrivateJSON(join(state, 'link.json')));
    await chmod(file, 0o644);
    assert.throws(() => readPrivateJSON(file), /PRIVATE_FILE/);
  } finally {
    await rm(state, { recursive: true });
  }
});

test(
  'only the original unconsumed arm operation can be withdrawn',
  { skip: process.platform !== 'darwin' },
  async () => {
    const state = await mkdtemp(join(tmpdir(), 'host-cpu-arm-'));
    try {
      await chmod(state, 0o700);
      const arm = armCpuProfile(state, randomUUID(), fixture);
      assert.throws(() => disarmCpuProfile(state, randomUUID()), /MISMATCH/);
      assert.equal(
        readPrivateJSON(join(profileDirectory(state), 'arm.json')).operationId,
        arm.operationId,
      );
      assert.deepEqual(disarmCpuProfile(state, arm.operationId), {
        operationId: arm.operationId,
        state: 'disarmed',
      });
    } finally {
      await rm(state, { recursive: true });
    }
  },
);

test(
  'actual Main CPU samples, no debug port, immutable operation and owned-session cleanup',
  { skip: process.platform !== 'darwin', timeout: 45_000 },
  async () => {
    await withHost(async ({ state, child, ready }) => {
      assert.equal(ready.pid, child.pid);
      assert.throws(() => requestCpuProfile(state, child.pid + 1, randomUUID(), 5000), /EXACT_PID/);
      assert.throws(
        () => requestCpuProfile(state, child.pid, randomUUID(), 60_001),
        /INVALID_REQUEST/,
      );
      const operationId = randomUUID();
      requestCpuProfile(state, child.pid, operationId, 5000);
      const receiptFile = join(profileDirectory(state), `receipt-${operationId}.json`);
      const receipt = await until(() => {
        const value = readPrivateJSON(receiptFile);
        return value.state === 'completed' && value;
      });
      const bytes = await readFile(join(profileDirectory(state), receipt.profile));
      assert.equal(createHash('sha256').update(bytes).digest('hex'), receipt.sha256);
      assert.equal(
        (await lstat(join(profileDirectory(state), receipt.profile))).mode & 0o777,
        0o600,
      );
      const profile = JSON.parse(bytes);
      assert.ok(profile.nodes.some((node) => node.callFrame.functionName === 'namedCpuWork'));
      assert.ok(receipt.actualProfileDurationUs >= 4_500_000);
      assert.ok(receipt.actualProfileDurationUs < 9_000_000);
      assert.ok(receipt.layerMetrics.stages['persistence.serialize'].calls > 0);
      assert.ok(receipt.layerMetrics.stages['persistence.sha256'].calls > 0);
      assert.ok(receipt.layerMetrics.mainThreadCpuUs.user > 0);
      await until(() => cpuProfileStatus(state).state === 'idle');
      assert.deepEqual(requestCpuProfile(state, child.pid, operationId, 5000), receipt);
      const checked = new Promise((resolve) => {
        const listener = (message) => {
          if (!message.check) return;
          child.off('message', listener);
          resolve(message);
        };
        child.on('message', listener);
      });
      child.send({ check: true });
      assert.deepEqual(
        { ...(await checked), tick: 0 },
        { check: true, error: false, isolate: true, inspectorUrl: null, tick: 0 },
      );
      stopCpuProfile(state, child.pid);
      await until(() => cpuProfileStatus(state).state === 'closed');
      // A consumed arm cannot enable a new collector on a later Host start.
      await assert.rejects(readFile(join(profileDirectory(state), 'arm.json')), { code: 'ENOENT' });
    });
  },
);

test(
  'Worker requests stop while Main is busy; no Host termination or claimed hard response bound',
  { skip: process.platform !== 'darwin', timeout: 45_000 },
  async () => {
    await withHost(async ({ state, child }) => {
      const operationId = randomUUID();
      requestCpuProfile(state, child.pid, operationId, 60_000);
      const file = join(profileDirectory(state), `receipt-${operationId}.json`);
      await until(() => readPrivateJSON(file).state === 'recording');
      child.send({ blockMs: 3000 });
      await sleep(250);
      const requestedNs = process.hrtime.bigint();
      stopCpuProfile(state, child.pid);
      const receipt = await until(() => {
        const value = readPrivateJSON(file);
        return value.state === 'completed' && value;
      });
      assert.ok(BigInt(receipt.stopRequestedNs) - requestedNs < 1_500_000_000n);
      assert.equal(child.exitCode, null);
      assert.ok(receipt.requestedStopDelayMs >= 0);
      await until(() => cpuProfileStatus(state).state === 'closed');
    });
  },
);
