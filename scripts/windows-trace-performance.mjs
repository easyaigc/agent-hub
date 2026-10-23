import fs from 'node:fs';
import { join, resolve } from 'node:path';
import { fork } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const [stage, runtimeDirectory, evidenceDirectory] = process.argv.slice(2);
if (!['before', 'after'].includes(stage))
  throw Error('Use before|after runtime-directory evidence-directory');
const runtime = resolve(runtimeDirectory),
  evidence = resolve(evidenceDirectory);
fs.mkdirSync(evidence, { recursive: true });
const child = fork(join(runtime, 'scripts/windows-trace-performance-host-fixture.mjs'), [], {
  execPath: process.execPath,
  execArgv: [],
  windowsHide: true,
  env: {
    ...process.env,
    TRACE_PERF_STAGE: stage,
    TRACE_PERF_FIXTURE_ROOT: join(evidence, stage + '-fixture-' + randomUUID()),
  },
  stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
});
let stderr = '';
child.stderr.on('data', (bytes) => {
  stderr = (stderr + bytes.toString()).slice(-4000);
});
try {
  const ready = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Fixture startup timed out')), 45000);
    child.on('message', (value) => {
      if (value.kind === 'ready') {
        clearTimeout(timer);
        resolve(value);
      }
    });
    child.once('exit', () => {
      clearTimeout(timer);
      reject(Error('Fixture exited: ' + stderr));
    });
  });
  const headers = { Authorization: 'Bearer ' + ready.token, Connection: 'close' };
  const query = new URLSearchParams({
    hostId: ready.hostId,
    target: JSON.stringify(ready.target),
    threadId: ready.threadId,
    sourceTurnId: ready.sourceTurnId,
  });
  const measure = async (path) => {
    const at = performance.now(),
      response = await fetch(ready.url + path, { headers });
    const value = await response.json();
    return {
      path: path.split('?')[0],
      ms: performance.now() - at,
      http: response.status,
      suggestion: !!value.suggestion,
      available: value.capabilities?.includes('desktop:suggestions:read:v1'),
    };
  };
  for (let i = 0; i < 200; i++) {
    if ((await measure('/v1/workspace')).available) break;
    if (i === 199) throw Error('Suggestion fixture never ready');
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  const samples = [];
  for (let i = 0; i < (stage === 'before' ? 1 : 5); i++) {
    const suggestion = measure('/v1/threads/next-turn-suggestion?' + query);
    await new Promise((resolve) => setTimeout(resolve, stage === 'before' ? 150 : 5));
    const concurrent = await Promise.all(
      [
        '/healthz',
        '/v1/workspace',
        '/v1/events?hostId=' + ready.hostId + '&epoch=fixture-reset',
      ].map(measure),
    );
    samples.push({ suggestion: await suggestion, concurrent });
  }
  const result = {
    stage,
    instanceAndRuntimeSource: 'controlled synthetic readiness and owner',
    filesystem: 'real Windows handles/DACLs/parser; initial fixtures normalize ownership',
    currentInstanceProbe:
      process.env.TRACE_PERF_NATIVE_PROBE === '1'
        ? 'actual signed original process before/after; only trace-root readiness is substituted'
        : 'synthetic instance readiness',
    samples,
  };
  if (
    samples.some(
      (v) =>
        v.suggestion.http !== 200 ||
        !v.suggestion.suggestion ||
        v.concurrent.some((r) => r.http !== 200),
    )
  )
    throw Error('Fixture HTTP validation failed');
  const name =
    stage + (process.env.TRACE_PERF_NATIVE_PROBE === '1' ? '-native-probe' : '') + '-http';
  fs.writeFileSync(
    join(evidence, name + '-' + Date.now() + '.json'),
    JSON.stringify(result, null, 2),
  );
  fs.writeFileSync(join(evidence, name + '.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} catch (error) {
  fs.writeFileSync(join(evidence, stage + '-fixture-error.txt'), stderr);
  console.error(stderr);
  throw error;
} finally {
  if (child.connected) child.send({ kind: 'close' });
  const timeout = setTimeout(() => child.kill(), 5000);
  timeout.unref();
  if (child.exitCode === null && child.signalCode === null)
    await new Promise((resolve) => child.once('exit', resolve));
  clearTimeout(timeout);
}
