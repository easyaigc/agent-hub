import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, writeFile, readFile, rm, stat, utimes, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { spawnSync } from 'node:child_process';
import {
  safeDiagnostic,
  diagnosticEvent,
} from '../packages/codex-adapter/dist/diagnostic-events.js';
import {
  startRuntimeDiagnostics,
  appendRuntimeDiagnostic,
} from '../packages/codex-adapter/dist/runtime-diagnostics.js';
import { DesktopRuntime } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Resolve the Host's real ESM copy. A deployed bundle can contain a distinct
// node_modules Adapter alongside its nominal source/compiled tree.
const adapterResolution = spawnSync(
  process.execPath,
  [
    '--experimental-import-meta-resolve',
    '--input-type=module',
    '--eval',
    'process.stdout.write(import.meta.resolve("@codex-manage/codex-adapter",process.argv[1]));',
    new URL('../apps/host-agent/dist/runtime-server.js', import.meta.url).href,
  ],
  { encoding: 'utf8', timeout: 5000, windowsHide: true },
);
assert.equal(adapterResolution.status, 0, adapterResolution.stderr);
const { RuntimeSession, RuntimeError } = await import(
  new URL('./runtime-session.js', adapterResolution.stdout.trim()).href
);

async function until(read, predicate, ms = process.platform === 'win32' ? 35_000 : 5000) {
  const deadline = Date.now() + ms;
  for (;;) {
    let value;
    try {
      value = await read();
    } catch {
      /* Asynchronous writer may not have created its file yet. */
    }
    if (predicate(value)) return value;
    assert(Date.now() < deadline, 'Timed out waiting for actual asynchronous evidence');
    await delay(20);
  }
}
test('schema drops secrets, arbitrary routes, errors, query, stderr and stacks', () => {
  const secret = 'Bearer private-secret';
  const result = safeDiagnostic({
    scope: 'http',
    route: '/v1/private-secret',
    status: 503,
    requestId: randomUUID(),
    authorization: secret,
    query: secret,
    body: secret,
    stderr: secret,
    stack: secret,
    path: secret,
  });
  assert.equal(result.route, '/other');
  assert.equal(result.status, 503);
  assert(!JSON.stringify(result).includes('private-secret'));
});

test('fingerprint I/O failure marks diagnostics failed without throwing into Host startup', () => {
  const original = fs.readdirSync;
  fs.readdirSync = () => {
    throw Object.assign(new Error('fixture'), { code: 'EIO' });
  };
  syncBuiltinESMExports();
  try {
    const logger = startRuntimeDiagnostics('/unused', randomUUID());
    assert.equal(logger.status().failed, true);
    assert.equal(logger.fingerprint, 'unavailable');
  } finally {
    fs.readdirSync = original;
    syncBuiltinESMExports();
  }
});

test('multiple loaded diagnostic module copies retain one process dispatcher', async () => {
  const before = process.listenerCount('workerMessage');
  const module = new URL('../packages/codex-adapter/dist/diagnostic-events.js', import.meta.url);
  for (let i = 0; i < 20; i++) await import(module.href + '?copy=' + i);
  assert.equal(process.listenerCount('workerMessage'), before);
});

test('real eval caller starts a nonrecursive diagnostic writer', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'diagnostic-eval-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const module = new URL('../packages/codex-adapter/dist/runtime-diagnostics.js', import.meta.url)
    .href;
  const script = `import {startRuntimeDiagnostics} from ${JSON.stringify(module)};import {existsSync} from 'node:fs';const log=startRuntimeDiagnostics(${JSON.stringify(directory)},'11111111-1111-4111-8111-111111111111');const deadline=Date.now()+5000;while(!existsSync(${JSON.stringify(join(directory, 'diagnostics/errors.jsonl'))})){if(log.status().failed||Date.now()>deadline)throw Error('Diagnostic worker startup failed');await new Promise(resolve=>setTimeout(resolve,20));}await log.close();`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', script], {
    encoding: 'utf8',
    timeout: 8000,
  });
  assert.equal(result.status, 0, result.stderr);
});

test('real writer rotates, expires, suppresses floods, preserves privacy and private permissions', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'reconnect-diagnostics-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const root = join(directory, 'diagnostics');
  await mkdir(root, { mode: 0o700 });
  const file = join(root, 'errors.jsonl');
  await writeFile(file, Buffer.alloc(2 * 1024 * 1024, 32), { mode: 0o600 });
  await writeFile(file + '.2', 'expired', { mode: 0o600 });
  await utimes(file + '.2', new Date(0), new Date(0));
  const hostId = randomUUID();
  const logger = startRuntimeDiagnostics(directory, hostId);
  try {
    const requestId = randomUUID();
    appendRuntimeDiagnostic(
      directory,
      {
        scope: 'http',
        hostId,
        requestId,
        status: 503,
        code: 'HOST_STATE_SAVE_FAILED',
        route: '/v1/replies',
        ready: true,
        bridge: true,
        reader: true,
      },
      { message: 'SECRET_BODY', stderr: 'SECRET_STDERR', stack: 'SECRET_STACK' },
    );
    const log = await until(
      () => readFile(file, 'utf8'),
      (v) => v?.includes(requestId),
    );
    assert(!log.includes('SECRET'));
    assert.match(log, /HOST_STATE_SAVE_FAILED/);
    if (process.platform !== 'win32') {
      assert.equal((await stat(file)).mode & 0o077, 0);
      assert.equal((await stat(root)).mode & 0o077, 0);
    }
    assert.equal((await stat(file + '.1')).size, 2 * 1024 * 1024);
    await assert.rejects(stat(file + '.2'), { code: 'ENOENT' });
    const start = performance.now();
    for (let i = 0; i < 10000; i++)
      diagnosticEvent({ scope: 'flood', stage: 'bounded', body: 'SECRET' });
    assert(
      performance.now() - start < 1000,
      'Flood ingestion must not wait on disk/permission helper',
    );
    assert(logger.status().pending <= 64);
    assert(logger.status().dropped > 0);
    assert(logger.recent().length <= 64);
    assert((await stat(file)).size <= 2 * 1024 * 1024);
  } finally {
    await logger.close();
  }
});

test(
  'writer rejects a pre-existing public log without appending diagnostic bytes',
  {
    skip:
      process.platform === 'win32'
        ? 'POSIX mode fixture; Windows OWNER/DACL has native tests'
        : false,
  },
  async (t) => {
    const directory = await mkdtemp(join(tmpdir(), 'diagnostic-permissions-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    await mkdir(join(directory, 'diagnostics'), { mode: 0o700 });
    const file = join(directory, 'diagnostics/errors.jsonl');
    await writeFile(file, 'original-evidence', { mode: 0o644 });
    const logger = startRuntimeDiagnostics(directory, randomUUID());
    try {
      await until(
        () => logger.status(),
        (value) => value?.failed,
      );
      assert.equal(await readFile(file, 'utf8'), 'original-evidence');
    } finally {
      await logger.close();
    }
  },
);

test('writer refuses a symlink destination without modifying its target or blocking requests', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'diagnostic-refusal-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const target = join(directory, 'outside');
  await mkdir(target, { mode: 0o700 });
  try {
    await symlink(target, join(directory, 'diagnostics'));
  } catch (error) {
    if (process.platform === 'win32' && error.code === 'EPERM') {
      t.skip('Windows current user cannot create the symlink fixture; no privilege expansion');
      return;
    }
    throw error;
  }
  const logger = startRuntimeDiagnostics(directory, randomUUID());
  try {
    diagnosticEvent({ scope: 'http', status: 503 });
    await until(
      () => logger.status(),
      (v) => v?.dropped > 0,
    );
    await assert.rejects(stat(join(target, 'errors.jsonl')), { code: 'ENOENT' });
  } finally {
    await logger.close();
  }
});

test('automatic Duplex cancellation reproduces legacy ABORT_ERR and all four fixed close paths retire', () => {
  const module = new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url)
    .href;
  const legacy = `import {spawn} from 'node:child_process';import {Duplex} from 'node:stream';const child=spawn(process.execPath,['-e','process.stdin.resume()']);const stream=Duplex.from({readable:child.stdout,writable:child.stdin});stream.on('close',()=>child.kill());child.on('exit',()=>stream.destroy());child.on('error',()=>stream.destroy());stream.destroy();`;
  const before = spawnSync(process.execPath, ['--input-type=module', '-e', legacy], {
    encoding: 'utf8',
    timeout: 5000,
  });
  assert.notEqual(before.status, 0, 'Legacy early close must expose the real regression');
  assert.match(before.stderr, /ABORT_ERR/);
  for (const action of [
    'stream.destroy()',
    'child.stdin.destroy()',
    'child.stdout.destroy()',
    'child.kill()',
  ]) {
    const script = `import {spawn} from 'node:child_process';import {windowsDesktopBridgeStream} from ${JSON.stringify(module)};const child=spawn(process.execPath,['-e','process.stdin.resume()']);const stream=windowsDesktopBridgeStream(child);const closed=new Promise(resolve=>child.once('close',resolve));${action};await closed;`;
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', script], {
      encoding: 'utf8',
      timeout: 5000,
    });
    assert.equal(result.status, 0, action + ': ' + result.stderr);
  }
});

test(
  'runtime coalesces concurrent recovery and explicit close isolates late reader completion',
  {
    skip:
      process.platform === 'win32'
        ? 'This fixture uses Mac IPC setup; Windows native recovery remains separately unverified'
        : false,
  },
  async () => {
    const runtime = new DesktopRuntime({ mode: 'desktop-runtime' }, randomUUID(), 'fixture');
    let release, started;
    const readerStarted = new Promise((resolve) => {
      started = resolve;
    });
    let calls = 0,
      ipc = 0;
    runtime.reader.connect = () => {
      calls++;
      started();
      return new Promise((resolve) => {
        release = resolve;
      });
    };
    runtime.ipc.connect = async () => {
      ipc++;
    };
    const first = runtime.connect(),
      second = runtime.connect();
    assert.equal(first, second);
    // Discovery is now shared asynchronously. Keep the late-reader race causal,
    // rather than requiring reader startup before connect() returns its Promise.
    await readerStarted;
    assert.equal(calls, 1);
    runtime.close();
    release();
    await assert.rejects(first, /closed/);
    assert.equal(ipc, 0);
    assert.equal(runtime.ready, false);
    await assert.rejects(runtime.connect(), /closed/);
  },
);

test('real HTTP distinguishes persistence, disconnected and owner 503; live 200 reports runtime false', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'diagnostic-http-'));
  const token = 'private-http-test-token-never-logged-0123456789';
  const saved = { ...process.env };
  const original = RuntimeSession.prototype.connect;
  RuntimeSession.prototype.connect = async () => {};
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: directory,
    HOST_AGENT_ROOT: directory,
    HOST_AGENT_TOKEN: token,
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  let service;
  try {
    service = await startRuntimeServer();
    const endpoint = `http://127.0.0.1:${service.server.address().port}`;
    const request = (path) =>
      fetch(endpoint + path, { headers: { Authorization: 'Bearer ' + token } });
    const unauthorized = await fetch(endpoint + '/healthz?secret=NEVER_LOG_QUERY');
    assert.equal(unauthorized.status, 401);
    const rejected = await unauthorized.json();
    const authLog = await until(
      () => readFile(join(directory, 'diagnostics/errors.jsonl'), 'utf8'),
      (value) => value?.includes(rejected.requestId),
    );
    const authRow = authLog
      .trim()
      .split('\n')
      .map(JSON.parse)
      .find((row) => row.requestId === rejected.requestId);
    assert.equal(authRow.route, '/healthz');
    assert(!authLog.includes('NEVER_LOG_QUERY'));
    const health = await request('/healthz');
    assert.equal(health.status, 200);
    const state = await health.json();
    assert.equal(state.runtime.ready, false);
    assert.equal(state.runtime.bridge, false);
    assert.equal(state.runtime.reader, false);
    const cases = [
      ['HOST_STATE_SAVE_FAILED', 'Host 状态保存失败'],
      ['CODEX_RUNTIME_DISCONNECTED', 'Codex 运行时未连接'],
      ['DESKTOP_OWNER_CHANGED', '原 owner 已变化'],
      ['SUGGESTION_READ_BUSY', '原桌面建议读取忙碌'],
    ];
    for (const [code, message] of cases) {
      service.session.refreshDesktopCreationAvailability = async () => {
        throw new RuntimeError(503, message, code);
      };
      const response = await request('/healthz?secret=NEVER_LOG_QUERY');
      const body = await response.json();
      assert.equal(response.status, 503);
      assert.equal(body.code, code);
      assert.equal(body.error, message);
      assert.equal(body.requestId, response.headers.get('x-agent-hub-diagnostic-id'));
      const content = await until(
        () => readFile(join(directory, 'diagnostics/errors.jsonl'), 'utf8'),
        (v) => v?.includes(body.requestId),
      );
      const row = content
        .trim()
        .split('\n')
        .map(JSON.parse)
        .find((row) => row.requestId === body.requestId);
      assert.equal(row.code, code);
      assert.equal(row.ready, false);
      assert.equal(row.route, '/healthz');
      assert(!content.includes(token) && !content.includes('NEVER_LOG_QUERY'));
    }
  } finally {
    service?.close();
    RuntimeSession.prototype.connect = original;
    for (const key of Object.keys(process.env)) if (!(key in saved)) delete process.env[key];
    Object.assign(process.env, saved);
    await service?.diagnostics.close();
    await rm(directory, { recursive: true, force: true });
  }
});
