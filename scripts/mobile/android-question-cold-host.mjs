import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { readFile, realpath, stat, writeFile } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// A fixed examiner-owned Host process; no execution instance or production configuration is created.
if (process.env.ANDROID_QUESTION_COLD_CHILD === '1') {
  assert(process.send && process.connected);
  const root = await realpath(process.env.ANDROID_QUESTION_COLD_ROOT ?? '');
  assert.match(
    basename(root),
    /^(?:question-model-live|approval-invalidation-live)-\d+-[a-f0-9]+$/,
  );
  assert.equal((await stat(root)).mode & 0o077, 0);
  assert.equal(await realpath(process.env.HOST_AGENT_STATE_DIR ?? ''), join(root, 'host'));
  assert.equal(process.env.CODEX_RUNTIME_MODE, 'desktop-runtime');
  assert.equal(process.env.HOST_AGENT_BIND, '127.0.0.1');
  const nonce = process.env.ANDROID_QUESTION_COLD_NONCE;
  const manifest = JSON.parse(await readFile(join(root, 'immutable-cases.json'), 'utf8'));
  assert.match(nonce ?? '', /^[a-f0-9]{8}$/);
  assert.equal(manifest.nonce, nonce);
  const host = await startRuntimeServer();
  process.send({ type: 'ready', pid: process.pid, port: host.server.address().port });
  let closing = false;
  const close = () => {
    if (closing) return;
    closing = true;
    host.server.once('close', () => {
      if (process.connected) process.disconnect();
    });
    host.close();
  };
  process.on('message', (value) => {
    assert(value?.type === 'stop' && value.nonce === nonce);
    close();
  });
  process.once('disconnect', close);
  process.removeListener('SIGTERM', host.close);
  process.once('SIGTERM', close);
}

export async function startQuestionColdHost({ root, state, nonce, hostId, index, port = 0 }) {
  assert(Number.isSafeInteger(index) && index >= 0);
  const logs = [];
  const child = fork(fileURLToPath(import.meta.url), [], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      ANDROID_QUESTION_COLD_CHILD: '1',
      ANDROID_QUESTION_COLD_ROOT: root,
      ANDROID_QUESTION_COLD_NONCE: nonce,
      HOST_AGENT_PORT: String(port),
    },
    execArgv: [],
    silent: true,
  });
  await writeFile(
    join(root, 'cold-host-' + index + '-pid.json'),
    JSON.stringify({ pid: child.pid, index }),
    { mode: 0o600 },
  );
  child.stdout.on('data', (bytes) => logs.push(bytes.toString()));
  child.stderr.on('data', (bytes) => logs.push(bytes.toString()));
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Owned cold Host ready deadline')), 30000);
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      reject(Error('Owned cold Host exited before readiness: ' + code + '/' + signal));
    });
    child.once('message', (value) => {
      clearTimeout(timer);
      if (value?.type === 'ready' && value.pid === child.pid) done(value);
      else reject(Error('Owned cold Host readiness identity mismatch'));
    });
  }).catch(async (error) => {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM');
      if (child.connected) child.disconnect();
      await new Promise((done) => {
        const timer = setTimeout(done, 15000);
        child.once('exit', () => {
          clearTimeout(timer);
          done();
        });
      });
    }
    await writeFile(join(root, 'cold-host-' + index + '.log'), logs.join(''), { mode: 0o600 });
    throw error;
  });
  assert(Number.isSafeInteger(ready.port) && ready.port > 0);
  if (port) assert.equal(ready.port, port);
  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const endpoint = 'http://127.0.0.1:' + ready.port;
  const read = async (path, query = {}) => {
    assert(['/v1/workspace', '/v1/models', '/v1/history', '/v1/replies/status'].includes(path));
    const url = new URL(path, endpoint);
    for (const [key, value] of Object.entries({ hostId, ...query }))
      url.searchParams.set(key, value);
    const response = await fetch(url, {
      headers: { Authorization: 'Bearer ' + token },
      redirect: 'error',
      signal: AbortSignal.timeout(10000),
    });
    const value = await response.json();
    assert.equal(response.status, 200, 'Owned cold Host actual read: ' + path);
    if (value.hostId) assert.equal(value.hostId, hostId);
    return value;
  };
  let stopped = false;
  return {
    pid: child.pid,
    token,
    endpoint,
    read,
    server: { address: () => ({ port: ready.port }) },
    session: {
      workspace: () => read('/v1/workspace'),
      models: () => read('/v1/models'),
      open: (threadId) => read('/v1/history', { threadId, window: 'latest-v1' }),
    },
    async close() {
      if (stopped) return;
      stopped = true;
      const exited = new Promise((done, reject) => {
        const timer = setTimeout(() => reject(Error('Owned cold Host exit deadline')), 15000);
        child.once('exit', (code, signal) => {
          clearTimeout(timer);
          done({ code, signal });
        });
      });
      child.send({ type: 'stop', nonce });
      const exit = await exited;
      await writeFile(join(root, 'cold-host-' + index + '.log'), logs.join(''), { mode: 0o600 });
      assert.equal(exit.code, 0);
      return { pid: child.pid, port: ready.port, ...exit };
    },
  };
}
