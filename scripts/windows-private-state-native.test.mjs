import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, openSync, readFileSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { Agent, createServer, get, globalAgent } from 'node:http';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { Worker } from 'node:worker_threads';
import ts from 'typescript';
import { monitorPrivateStateWorkers } from './fixtures/windows-private-state-monitor.mjs';

const repository = fileURLToPath(new URL('../', import.meta.url));
const source = join(repository, 'packages/codex-adapter/src');
const childFixture = fileURLToPath(
  new URL('./fixtures/windows-private-state-child.mjs', import.meta.url),
);
const ownerFixture = fileURLToPath(
  new URL('./fixtures/windows-private-state-owner.mjs', import.meta.url),
);
const processFixture = new URL('./fixtures/windows-process-fixture.mjs', import.meta.url);
const run = promisify(execFile);
const unavailable = (error) => error.code === 'WINDOWS_PRIVATE_STATE_UNAVAILABLE';
const rejected = (error) => error.code === 'UNSAFE_WINDOWS_PRIVATE_STATE';
const environment = Object.fromEntries(
  Object.entries(process.env).filter(([key]) => !/^(HOST_|CODEX_|NODE_OPTIONS$)/i.test(key)),
);
const digest = (value) => createHash('sha256').update(value).digest('hex');
function asWindows(create) {
  const platform = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: 'win32' });
  try {
    return create();
  } finally {
    Object.defineProperty(process, 'platform', platform);
  }
}
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    throw error;
  }
};
async function until(check, message, ms = 3000) {
  const deadline = Date.now() + ms;
  while (!(await check())) {
    assert(Date.now() < deadline, message);
    await delay(15);
  }
}

async function fixture(t, mode = 'ok', executable) {
  await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
  const directory = await mkdtemp(join(repository, '.local/private-state-test-'));
  const log = join(directory, 'child.jsonl');
  const moduleURL = pathToFileURL(join(directory, 'desktop-private-state-native.js'));
  const entryURL = pathToFileURL(join(directory, 'desktop-private-state-native-worker.js'));
  await writeFile(join(directory, 'package.json'), '{"type":"module"}');
  // Freeze the real transport and gate together. No shared dist/build is overwritten.
  const names = [
    'desktop-private-state-native',
    'desktop-private-state-native-worker',
    'windows-process',
    'diagnostic-events',
  ];
  const inputs = await Promise.all(
    names.map((name) => readFile(join(source, name + '.ts'), 'utf8')),
  );
  for (const [index, name] of names.entries()) {
    const result = ts.transpileModule(inputs[index], {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    });
    const prefix = index === 1 ? "import './bootstrap.mjs';\n" : '';
    await writeFile(join(directory, name + '.js'), prefix + result.outputText);
    assert.equal(
      digest(await readFile(join(source, name + '.ts'))),
      digest(inputs[index]),
      'Production input changed while freezing this fixture',
    );
  }
  async function configureNative(executable) {
    await writeFile(
      join(directory, 'desktop-platform.js'),
      `export const windowsPrivateStateNativeExecutable=()=>${JSON.stringify(executable ?? process.execPath)};\n`,
    );
    await writeFile(
      join(directory, 'bootstrap.mjs'),
      `import assert from 'node:assert/strict';
import child from 'node:child_process';
import {appendFileSync,readFileSync} from 'node:fs';
import {syncBuiltinESMExports} from 'node:module';
import {threadId} from 'node:worker_threads';
import {getWindowsProcessGate} from './windows-process.js';
const configuration=JSON.parse(readFileSync(${JSON.stringify(join(directory, 'config.json'))},'utf8'));
if(configuration.forceWindows)Object.defineProperty(process,'platform',{value:'win32'});
if(configuration.forceWindows){
  const originalWait=Atomics.wait;
  let recorded=false;
  Atomics.wait=function(...args){
    if(!recorded&&args[0]?.buffer===getWindowsProcessGate()){
      recorded=true;
      appendFileSync(${JSON.stringify(log)},JSON.stringify({event:'gate-wait',threadId})+'\\n');
    }
    return Reflect.apply(originalWait,this,args);
  };
}
if(configuration.expectNodeFlags){
  assert.equal(Error.stackTraceLimit,17);
  assert.equal(process.sourceMapsEnabled,true);
  assert.throws(()=>({}).__proto__,{code:'ERR_PROTO_ACCESS'});
  assert.throws(()=>new Function('return 1'),EvalError);
  assert.equal(globalThis.fixturePreloads,undefined);
  appendFileSync(${JSON.stringify(log)},JSON.stringify({event:'flags',verified:true})+'\\n');
}
const original=child.spawn;
child.spawn=function(command,args,options){
  assert.equal(command,${JSON.stringify(executable ?? process.execPath)});
  assert.deepEqual(args,['serve']);
  if(process.platform==='win32')assert.equal(Atomics.load(new Int32Array(getWindowsProcessGate()),0),threadId+1,'Native spawn must remain inside the production gate');
  const spawned=Reflect.apply(original,this,[command,${executable ? 'args' : JSON.stringify([childFixture, join(directory, 'config.json'), 'serve'])},options]);
  appendFileSync(${JSON.stringify(log)},JSON.stringify({event:'spawn',pid:spawned.pid,threadId})+'\\n');
  spawned.once('exit',()=>setImmediate(()=>appendFileSync(${JSON.stringify(log)},JSON.stringify({event:'stdio-retired',pid:spawned.pid,destroyed:[spawned.stdin.destroyed,spawned.stdout.destroyed,spawned.stderr.destroyed]})+'\\n')));
  spawned.once('close',()=>appendFileSync(${JSON.stringify(log)},JSON.stringify({event:'child-close',pid:spawned.pid})+'\\n'));
  return spawned;
};
syncBuiltinESMExports();\n`,
    );
  }
  const {
    createWindowsPrivateStateBackend,
    privateStateProtocol: p,
    ...options
  } = await import(moduleURL.href);
  const gateURL = new URL('./windows-process.js', moduleURL);
  const gate = await import(gateURL.href);
  for (const [index, name] of names.entries())
    assert.equal(
      digest(await readFile(join(source, name + '.ts'))),
      digest(inputs[index]),
      'Production input changed after freezing this fixture',
    );
  const configuration = { mode, log, responseBytes: p.responseBytes };
  const saveConfiguration = () =>
    writeFile(join(directory, 'config.json'), JSON.stringify(configuration));
  await saveConfiguration();
  await configureNative(executable);
  const platforms = [],
    backends = [],
    actors = [];
  const monitor = monitorPrivateStateWorkers(entryURL, log);
  const events = () =>
    existsSync(log)
      ? readFileSync(log, 'utf8')
          .trim()
          .split('\n')
          .filter(Boolean)
          .map((line) => JSON.parse(line))
      : [];
  t.after(async () => {
    let failure;
    for (const worker of actors) await worker.terminate();
    for (const platform of platforms) platform.closeWindowsPrivateStateBackend();
    for (const backend of backends) {
      try {
        backend.close();
      } catch (error) {
        failure ??= error;
      }
    }
    await until(
      () =>
        events()
          .filter((v) => v.event === 'spawn' && v.pid)
          .every((v) => !alive(v.pid)),
      'Fixture native child did not exit',
    );
    await until(() => monitor.active.size === 0, 'Fixture transport Worker did not exit');
    monitor.restore();
    await rm(directory, { recursive: true, force: true });
    if (failure) throw failure;
  });
  return {
    directory,
    log,
    moduleURL,
    entryURL,
    p,
    options,
    actors,
    activeWorkers: monitor.active,
    gate,
    gateURL,
    events,
    useNative: configureNative,
    async platform() {
      const input = await readFile(join(source, 'desktop-platform.ts'), 'utf8');
      const output = ts.transpileModule(input, {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
      }).outputText;
      const path = join(directory, 'desktop-platform-actual.js');
      await writeFile(path, output);
      const platform = await import(pathToFileURL(path).href);
      platforms.push(platform);
      return platform;
    },
    async retired(pid) {
      const birth = events().find((event) => event.event === 'spawn' && event.pid === pid);
      assert(birth, 'Only a recorded fixture child can be inspected');
      await until(() => {
        const rows = events();
        return (
          rows.some((row) => row.event === 'child-close' && row.pid === pid) &&
          rows.some(
            (row) =>
              row.event === 'stdio-retired' && row.pid === pid && row.destroyed.every(Boolean),
          ) &&
          rows.some((row) => row.event === 'transport-exit' && row.id === birth.threadId)
        );
      }, 'Child exit must retire all stdio and the real transport Worker');
    },
    async forceWindows() {
      configuration.forceWindows = true;
      await saveConfiguration();
    },
    async expectNodeFlags() {
      configuration.expectNodeFlags = true;
      await saveConfiguration();
    },
    open() {
      const backend = createWindowsPrivateStateBackend(entryURL);
      backends.push(backend);
      return backend;
    },
  };
}

test('real transport reuses one child, preserves exact path/cwd framing and retires on close', async (t) => {
  const f = await fixture(t, 'fragmented');
  const beforeExit = process.listenerCount('beforeExit'),
    exit = process.listenerCount('exit');
  const backend = f.open();
  const paths = ['空 格\\path\twith\ncontrol', 'x'.repeat(f.p.pathCharacters)];
  for (let index = 0; index < 24; index++)
    backend.call(index % 2 ? 'protect-file' : 'check', paths[index % 2]);
  const requests = f.events().filter((v) => v.event === 'request');
  assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
  assert.equal(requests.length, 24);
  assert.deepEqual(
    requests.map((v) => v.id),
    Array.from({ length: 24 }, (_, index) => index + 1),
  );
  assert(requests.every((v, index) => v.path === paths[index % 2] && v.cwd === process.cwd()));
  const pid = backend.status.pid;
  assert(pid > 0 && alive(pid));
  backend.close();
  backend.close();
  assert.equal(backend.status.closed, true);
  assert.equal(backend.status.gone, true);
  assert.equal(alive(pid), false);
  assert.equal(process.listenerCount('beforeExit'), beforeExit);
  assert.equal(process.listenerCount('exit'), exit);
  assert.throws(() => backend.call('check', 'after-close'), unavailable);
  assert.equal(f.events().filter((v) => v.event === 'request').length, 24);
});

test('async native permission waiting keeps timers and real HTTP responsive without accepting a second intent', async (t) => {
  const f = await fixture(t, 'async-held'),
    backend = f.open();
  const started = performance.now();
  let phase = 'native-start',
    httpElapsedMs;
  let completed = false;
  // Observe a possible failure immediately, even while the HTTP assertion is
  // waiting. A failed probe must not leave a late unhandled rejection/socket.
  const operation = backend.callAsync('protect-file', 'owned-background-temporary').then(
    () => {
      completed = true;
      return { ok: true };
    },
    (error) => ({ ok: false, error }),
  );
  const server = createServer((_request, response) => response.end('healthy'));
  // This is the fixture's own loopback server. Global environment proxies must
  // not reroute this responsiveness assertion or receive its request.
  const agent = new Agent({ keepAlive: false, proxyEnv: {} });
  let request, timer;
  try {
    await until(
      () => f.events().some((v) => v.event === 'request'),
      'Async request never reached native',
    );
    phase = 'native-held';
    await assert.rejects(backend.callAsync('check', 'must-not-be-sent'), unavailable);
    assert.throws(() => backend.call('check', 'must-not-be-sent'), unavailable);
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    phase = 'local-http';
    const httpStarted = performance.now();
    const body = await new Promise((resolve, reject) => {
      request = get(`http://127.0.0.1:${server.address().port}`, { agent }, (response) => {
        let body = '';
        response.setEncoding('utf8');
        response.on('data', (chunk) => (body += chunk));
        response.on('error', reject);
        response.on('end', () => {
          clearTimeout(timer);
          resolve(body);
        });
      });
      request.on('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
      timer = setTimeout(
        () => request.destroy(new Error('Fixture loopback HTTP exceeded1500ms')),
        1500,
      );
    });
    httpElapsedMs = performance.now() - httpStarted;
    await delay(25);
    assert.equal(body, 'healthy');
    assert.equal(completed, false, 'HTTP and timers must complete while native is still held');
    phase = 'native-release';
    await writeFile(f.log + '.release', 'release-owned-fixture');
    const result = await operation;
    if (!result.ok) throw result.error;
    await backend.callAsync('check', 'next-independent-intent');
    assert.deepEqual(
      f
        .events()
        .filter((v) => v.event === 'request')
        .map((v) => [v.id, v.path]),
      [
        [1, 'owned-background-temporary'],
        [2, 'next-independent-intent'],
      ],
    );
    backend.close();
    assert.equal(backend.status.gone, true);
    phase = 'complete';
  } finally {
    clearTimeout(timer);
    request?.destroy();
    agent.destroy();
    server.closeAllConnections();
    if (server.listening) await new Promise((resolve) => server.close(resolve));
    await writeFile(f.log + '.release', 'release-owned-fixture');
    const result = await operation;
    t.diagnostic(
      JSON.stringify({
        phase,
        elapsedMs: performance.now() - started,
        httpElapsedMs,
        nativeFailed: !result.ok,
        nativeErrorCode: result.error?.code,
        envProxyEnabled:
          process.env.NODE_USE_ENV_PROXY === '1' || process.execArgv.includes('--use-env-proxy'),
        httpProxyPresent: Boolean(process.env.HTTP_PROXY || process.env.http_proxy),
        globalProxyConfigured: Boolean(globalAgent.options.proxyEnv),
      }),
    );
  }
});

test('async permission rejection remains correlated and preserves the same child for the next intent', async (t) => {
  const f = await fixture(t, 'reject-second'),
    backend = f.open();
  await backend.callAsync('check', 'same-path');
  const pid = backend.status.pid;
  await assert.rejects(backend.callAsync('check', 'same-path'), rejected);
  await backend.callAsync('protect-file', 'same-path');
  assert.equal(backend.status.pid, pid);
  assert.deepEqual(
    f
      .events()
      .filter((v) => v.event === 'request')
      .map((v) => v.id),
    [1, 2, 3],
  );
});

for (const mode of ['wrong-id', 'timeout'])
  test(`async permission ${mode} fails closed without replay`, async (t) => {
    const f = await fixture(t, mode),
      backend = f.open();
    await assert.rejects(backend.callAsync('protect-file', 'immutable-effect'), unavailable);
    await assert.rejects(backend.callAsync('check', 'new-intent'), unavailable);
    backend.close();
    assert.equal(f.events().filter((v) => v.event === 'request').length, 1);
  });

test('closing an in-flight async permission call rejects it and retires its exact child', async (t) => {
  const f = await fixture(t, 'async-held'),
    backend = f.open();
  const rejection = assert.rejects(
    backend.callAsync('protect-file', 'owned-temporary'),
    unavailable,
  );
  await until(() => f.events().some((v) => v.event === 'request'), 'Native request missing');
  const pid = backend.status.pid;
  backend.close();
  await rejection;
  await f.retired(pid);
  assert.equal(backend.status.gone, true);
});

test('platform shutdown rejects queued background permission intents without starting another helper', async (t) => {
  const f = await fixture(t, 'async-held'),
    platform = await f.platform();
  const first = assert.rejects(
    asWindows(() => platform.protectWindowsStateFileAsync('first-owned-temporary')),
    unavailable,
  );
  const queued = assert.rejects(
    asWindows(() => platform.protectWindowsStateFileAsync('queued-owned-temporary')),
    unavailable,
  );
  await until(
    () => f.events().some((v) => v.event === 'request'),
    'First permission request missing',
  );
  const pid = f.events().find((v) => v.event === 'spawn').pid;
  platform.closeWindowsPrivateStateBackend();
  await Promise.all([first, queued]);
  await f.retired(pid);
  assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
  assert.equal(f.events().filter((v) => v.event === 'request').length, 1);
});

test('invalid actions and non-roundtripping/oversized paths never reach native stdin', async (t) => {
  const f = await fixture(t),
    backend = f.open();
  for (const path of ['', null, {}, 'x\0y', '\ud800', 'x'.repeat(f.p.pathCharacters + 1)])
    assert.throws(() => backend.call('check', path), unavailable);
  for (const action of ['serve', 'QUIT', 'check\nprotect', '', null])
    assert.throws(() => backend.call(action, 'synthetic'), unavailable);
  backend.call('check', 'valid-after-validation-errors');
  assert.deepEqual(
    f
      .events()
      .filter((v) => v.event === 'request')
      .map((v) => v.path),
    ['valid-after-validation-errors'],
  );
});

test('fresh native rejection is correlated, does not poison the child or reuse a previous success', async (t) => {
  const f = await fixture(t, 'reject-second'),
    backend = f.open();
  backend.call('check', 'same-path');
  const pid = backend.status.pid;
  assert.throws(
    () => backend.call('check', 'same-path'),
    (error) =>
      rejected(error) &&
      error.nativeError === 'UNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException',
  );
  backend.call('protect-file', 'same-path');
  assert.equal(backend.status.pid, pid);
  assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
  assert.deepEqual(
    f
      .events()
      .filter((v) => v.event === 'request')
      .map((v) => v.action),
    ['check', 'check', 'protect-file'],
  );
});

for (const mode of [
  'bad-ready',
  'crlf-ready',
  'trailing-ready',
  'exit-ready',
  'wrong-id',
  'crlf-response',
  'non-ascii',
  'oversized',
  'malformed',
  'stderr',
  'exit-operation',
  'overlong-error',
])
  test(`real Worker fails closed for ${mode} without retry or CLI fallback`, async (t) => {
    const f = await fixture(t, mode),
      backend = f.open();
    assert.throws(() => backend.call('protect-file', 'immutable-effect'), unavailable);
    assert.throws(() => backend.call('check', 'new-intent'), unavailable);
    backend.close();
    assert(backend.status.gone);
    assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
    const requests = f.events().filter((v) => v.event === 'request');
    assert(requests.length <= 1);
    assert(requests.every((v) => v.path === 'immutable-effect'));
  });

test('the largest allowed native error remains a rejection rather than success or transport corruption', async (t) => {
  const f = await fixture(t, 'max-error'),
    backend = f.open();
  assert.throws(
    () => backend.call('check', 'synthetic'),
    (error) =>
      rejected(error) && error.nativeError.length === 'UNSAFE_WINDOWS_PRIVATE_STATE:'.length + 128,
  );
  assert.equal(backend.status.state, f.p.ready);
});

test('stale and duplicate replies cannot satisfy the next operation', async (t) => {
  for (const mode of ['stale-id', 'duplicate']) {
    const f = await fixture(t, mode),
      backend = f.open();
    try {
      backend.call('check', 'first');
    } catch (error) {
      assert(unavailable(error));
    }
    assert.throws(() => backend.call('protect-file', 'second'), unavailable);
    backend.close();
    assert(f.events().filter((v) => v.event === 'request' && v.path === 'second').length <= 1);
    assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
  }
});

for (const mode of ['timeout', 'late'])
  test(
    `an applied operation with ${mode} acknowledgement is unknown and is never replayed`,
    { timeout: 12000 },
    async (t) => {
      const f = await fixture(t, mode),
        backend = f.open();
      const started = performance.now();
      assert.throws(() => backend.call('protect-file', 'applied-once'), unavailable);
      assert(performance.now() - started < f.p.operationMs + f.p.closeMs + 2000);
      assert.throws(() => backend.call('protect-file', 'applied-once'), unavailable);
      backend.close();
      assert.deepEqual(
        f
          .events()
          .filter((v) => v.event === 'request')
          .map((v) => v.path),
        ['applied-once'],
      );
      assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
    },
  );

test(
  'a child that never becomes ready is retired at the real startup deadline',
  { timeout: 35000 },
  async (t) => {
    const f = await fixture(t, 'startup-timeout'),
      backend = f.open();
    const started = performance.now();
    assert.throws(() => backend.call('check', 'never-submitted'), unavailable);
    assert(performance.now() - started < f.p.startupMs + f.p.closeMs + 2000);
    backend.close();
    assert.equal(f.events().filter((v) => v.event === 'request').length, 0);
  },
);

async function verifyOwnerTermination(f, path) {
  const config = join(f.directory, 'owners.json');
  await writeFile(
    config,
    JSON.stringify({
      scenario: 'isolates',
      moduleURL: f.moduleURL.href,
      entryURL: f.entryURL.href,
      path,
    }),
  );
  let stdout;
  try {
    ({ stdout } = await run(process.execPath, [ownerFixture, config], {
      env: environment,
      timeout: 12000,
    }));
  } catch (error) {
    throw new Error(error.stdout || error.stderr || error.message, { cause: error });
  }
  assert.deepEqual(JSON.parse(stdout), { passed: true });
}

test('independent owning isolates get independent children and owner termination leaves no child', async (t) => {
  const f = await fixture(t);
  await verifyOwnerTermination(f);
  assert.equal(f.events().filter((v) => v.event === 'spawn').length, 2);
});

test('an ordinary process can exit naturally without explicitly closing its unrefed backend', async (t) => {
  const f = await fixture(t),
    config = join(f.directory, 'owner.json');
  await writeFile(
    config,
    JSON.stringify({ moduleURL: f.moduleURL.href, entryURL: f.entryURL.href }),
  );
  await run(process.execPath, [ownerFixture, config], { env: environment, timeout: 8000 });
  const spawns = f.events().filter((v) => v.event === 'spawn');
  assert.equal(spawns.length, 1);
  assert.equal(alive(spawns[0].pid), false);
  assert.equal(f.events().filter((v) => v.event === 'request').length, 1);
});

test('NODE_OPTIONS is roundtripped by real Node, preserving conditions/backslashes while removing preloads', async (t) => {
  const f = await fixture(t);
  const packageRoot = join(f.directory, 'node_modules/private-state-conditions');
  await mkdir(packageRoot, { recursive: true });
  const winCondition = String.raw`C:\Users\Space Name\agent`;
  const exports = {
    [winCondition]: './win.mjs',
    [winCondition.replaceAll('\\', '')]: './collapsed.mjs',
    'space value': './space.mjs',
    default: './default.mjs',
  };
  await writeFile(join(packageRoot, 'package.json'), JSON.stringify({ exports }));
  for (const name of ['win', 'collapsed', 'space', 'default'])
    await writeFile(join(packageRoot, name + '.mjs'), `export default ${JSON.stringify(name)};`);
  const esm = pathToFileURL(join(f.directory, 'preload with space.mjs')).href;
  const cjs = join(f.directory, 'preload with space.cjs');
  await writeFile(
    fileURLToPath(esm),
    'globalThis.fixturePreloads=(globalThis.fixturePreloads??0)+1;',
  );
  await writeFile(cjs, 'globalThis.fixturePreloads=(globalThis.fixturePreloads??0)+1;');
  const probe = join(f.directory, 'flags.mjs');
  await writeFile(
    probe,
    "import selected from 'private-state-conditions';import {getHeapStatistics} from 'node:v8';let proto,dynamic;try{({}).__proto__}catch(e){proto=e.code}try{new Function('return 1')}catch(e){dynamic=e.name}console.log(JSON.stringify({selected,stack:Error.stackTraceLimit,heap:getHeapStatistics().heap_size_limit,preloads:globalThis.fixturePreloads??0,sourceMaps:process.sourceMapsEnabled,proto,dynamic}));",
  );
  for (const condition of ['space value', winCondition, winCondition.replaceAll('\\', '\\\\')]) {
    const raw = `--stack-trace-limit=17 --max-old-space-size=192 --enable-source-maps --disable-proto=throw --disallow-code-generation-from-strings --conditions="${condition}" --import=${esm} --require ${JSON.stringify(cjs)}`;
    const launch = async (value) =>
      JSON.parse(
        (
          await run(process.execPath, [probe], {
            env: { ...environment, NODE_OPTIONS: value },
            timeout: 5000,
          })
        ).stdout,
      );
    const original = await launch(raw);
    const filtered = await launch(f.options.privateStateNodeOptions(raw));
    assert.equal(original.preloads, 2);
    assert.equal(original.stack, 17);
    assert.equal(original.sourceMaps, true);
    assert.equal(original.proto, 'ERR_PROTO_ACCESS');
    assert.equal(original.dynamic, 'EvalError');
    assert.equal(filtered.preloads, 0);
    assert.deepEqual({ ...filtered, preloads: 2 }, original);
    assert.notEqual(
      original.selected,
      'default',
      'The direct Node reference must actually select the quoted condition',
    );
  }
});

test('real caller CLI with two-argument test flags produces a valid Worker and does not preload recursively', async (t) => {
  const f = await fixture(t),
    config = join(f.directory, 'owner.json');
  const preload = join(f.directory, 'caller-preload.mjs');
  await writeFile(
    preload,
    "import {isMainThread} from 'node:worker_threads';if(!isMainThread)throw Error('Recursive preload');",
  );
  await writeFile(
    config,
    JSON.stringify({ moduleURL: f.moduleURL.href, entryURL: f.entryURL.href, explicitClose: true }),
  );
  await f.expectNodeFlags();
  await run(
    process.execPath,
    [
      '--enable-source-maps',
      '--stack-trace-limit=17',
      '--max-old-space-size=192',
      '--disable-proto=throw',
      '--disallow-code-generation-from-strings',
      '--conditions=space value',
      '--test-name-pattern',
      'owner scope',
      '--test-skip-pattern',
      'skip scope',
      '--test-timeout',
      '9000',
      '--import',
      pathToFileURL(preload).href,
      ownerFixture,
      config,
    ],
    { env: environment, timeout: 8000 },
  );
  assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
  assert.equal(f.events().filter((v) => v.event === 'request').length, 1);
  assert.deepEqual(
    f.events().filter((v) => v.event === 'flags'),
    [{ event: 'flags', verified: true }],
  );
});

test('removing recursive preloads preserves real Worker process-wide security constraints', async (t) => {
  const f = await fixture(t),
    config = join(f.directory, 'security-owner.json');
  const bootstrap = join(f.directory, 'bootstrap.mjs');
  const original = await readFile(bootstrap, 'utf8');
  await writeFile(
    bootstrap,
    `import {appendFileSync as recordSecurity} from 'node:fs';
let prototype=false,generation=false;
try { void ({}).__proto__; } catch { prototype=true; }
try { new Function('return 1'); } catch { generation=true; }
recordSecurity(${JSON.stringify(f.log)},JSON.stringify({event:'security',prototype,generation})+'\\n');
` + original,
  );
  const preload = join(f.directory, 'security-preload.mjs');
  await writeFile(
    preload,
    "import {isMainThread} from 'node:worker_threads';if(!isMainThread)throw Error('Recursive preload');",
  );
  await writeFile(
    config,
    JSON.stringify({ moduleURL: f.moduleURL.href, entryURL: f.entryURL.href, explicitClose: true }),
  );
  await run(
    process.execPath,
    [
      '--disable-proto=throw',
      '--disallow-code-generation-from-strings',
      '--import',
      pathToFileURL(preload).href,
      ownerFixture,
      config,
    ],
    { env: environment, timeout: 8000 },
  );
  assert.deepEqual(
    f.events().filter((v) => v.event === 'security'),
    [{ event: 'security', prototype: true, generation: true }],
  );
  assert.equal(f.events().filter((v) => v.event === 'spawn').length, 1);
});

async function gateActor(f, options) {
  const control = options.control ?? new SharedArrayBuffer(8);
  const worker = new Worker(processFixture, {
    execArgv: [],
    env: environment,
    workerData: {
      gateURL: f.gateURL.href,
      windowsProcessGate: f.gate.getWindowsProcessGate(),
      control,
      ...options,
    },
  });
  f.actors.push(worker);
  f.gate.registerWindowsProcessWorker(worker);
  const messages = [];
  let failure;
  worker.on('message', (message) => messages.push(message));
  worker.once('error', (error) => {
    failure = error;
  });
  const exited = new Promise((resolve) => worker.once('exit', resolve));
  await until(() => messages.length > 0 || failure, 'Gate actor did not start');
  if (failure) throw failure;
  return { worker, messages, control: new Int32Array(control), exited };
}

function acknowledge(actor) {
  Atomics.store(actor.control, 0, 1);
  Atomics.notify(actor.control, 0);
}

test('process gate serializes real synchronous Node children across three owning isolates', async (t) => {
  const f = await fixture(t),
    control = new SharedArrayBuffer(8);
  const actors = await Promise.all(
    Array.from({ length: 3 }, (_, index) =>
      gateActor(f, {
        mode: 'contend',
        control,
        marker: join(f.directory, `effect-${index}`),
      }),
    ),
  );
  acknowledge(actors[0]);
  assert.deepEqual(await Promise.all(actors.map((actor) => actor.exited)), [0, 0, 0]);
  for (const [index, actor] of actors.entries()) {
    assert(
      actor.messages.some(
        (message) => message.kind === 'done' && message.remaining > 0 && message.remaining <= 3000,
      ),
    );
    assert.equal(await readFile(join(f.directory, `effect-${index}`), 'utf8'), 'effect\n');
  }
  assert.equal(Atomics.load(new Int32Array(f.gate.getWindowsProcessGate()), 0), 0);
});

test('process gate wait consumes the original budget and exhaustion creates no child', async (t) => {
  const f = await fixture(t),
    holder = await gateActor(f, { mode: 'hold', holdMs: 300 });
  acknowledge(holder);
  let remainingBudget;
  const output = asWindows(() =>
    f.gate.withWindowsProcessCreation(1000, (remaining) => {
      remainingBudget = remaining;
      return execFileSync(
        process.execPath,
        [fileURLToPath(processFixture), 'short', join(f.directory, 'waited')],
        { encoding: 'utf8', stdio: 'pipe', timeout: remaining },
      );
    }),
  );
  assert.equal(output, 'CLI_OK\n');
  assert(
    remainingBudget > 0 && remainingBudget <= 800,
    'Gate waiting must reduce, never restart, the child timeout',
  );
  assert.equal(await holder.exited, 0);
  const blocked = await gateActor(f, { mode: 'hold', holdMs: 300 });
  acknowledge(blocked);
  let creations = 0;
  assert.throws(
    () =>
      asWindows(() =>
        f.gate.withWindowsProcessCreation(70, () => {
          creations++;
        }),
      ),
    (error) => error.code === 'ETIMEDOUT' && error.spawnStarted === false,
  );
  assert.equal(creations, 0);
  assert.equal(await blocked.exited, 0);
});

test('process gate releases async ChildProcess and Promise results immediately and preserves failure identity', async (t) => {
  const f = await fixture(t);
  let originalChild;
  const child = asWindows(() =>
    f.gate.withWindowsProcessCreation(5000, () => {
      originalChild = spawn(
        process.execPath,
        [fileURLToPath(processFixture), 'short', join(f.directory, 'async'), '500'],
        { stdio: 'pipe' },
      );
      return originalChild;
    }),
  );
  let stdout = '',
    stderr = '';
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });
  const closed = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  });
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill();
    await closed;
  });
  assert.equal(child, originalChild);
  assert.equal(child.exitCode, null);
  assert.equal(Atomics.load(new Int32Array(f.gate.getWindowsProcessGate()), 0), 0);
  const short = asWindows(() =>
    f.gate.withWindowsProcessCreation(5000, (remaining) =>
      execFileSync(
        process.execPath,
        [fileURLToPath(processFixture), 'short', join(f.directory, 'parallel')],
        { encoding: 'utf8', stdio: 'pipe', timeout: remaining },
      ),
    ),
  );
  assert.equal(short, 'CLI_OK\n');
  assert.equal(
    child.exitCode,
    null,
    'A second creation must finish while the first async child is alive',
  );
  assert.equal(await closed, 0);
  assert.equal(stdout, 'CLI_OK\n');
  assert.equal(stderr, 'CLI_STDERR\n');
  const pending = Promise.resolve({ unchanged: true });
  assert.equal(
    asWindows(() => f.gate.withWindowsProcessCreation(5000, () => pending)),
    pending,
  );
  const failure = new Error('synthetic creation failure');
  assert.throws(
    () =>
      asWindows(() =>
        f.gate.withWindowsProcessCreation(5000, () => {
          throw failure;
        }),
      ),
    (error) => error === failure,
  );
  assert.throws(
    () =>
      asWindows(() =>
        f.gate.withWindowsProcessCreation(5000, () =>
          f.gate.withWindowsProcessCreation(5000, () =>
            assert.fail('Recursive creation must never run'),
          ),
        ),
      ),
    (error) => error.code === 'WINDOWS_PROCESS_GATE_UNAVAILABLE',
  );
  assert.equal(Atomics.load(new Int32Array(f.gate.getWindowsProcessGate()), 0), 0);
});

test('process gate confirmed owner exit releases its lock without clearing a later owner', async (t) => {
  const f = await fixture(t),
    first = await gateActor(f, { mode: 'hold', holdMs: 25, keepAlive: true });
  acknowledge(first);
  await until(
    () => first.messages.some((message) => message.kind === 'released'),
    'First owner did not release',
  );
  const second = await gateActor(f, { mode: 'hold', holdMs: 2000 });
  acknowledge(second);
  const firstId = first.worker.threadId,
    secondId = second.worker.threadId;
  assert.equal(f.gate.getWindowsProcessWorker(firstId), first.worker);
  assert.equal(f.gate.getWindowsProcessWorker(secondId), second.worker);
  assert.equal(f.gate.getWindowsProcessWorker(0), undefined);
  const shared = new Int32Array(f.gate.getWindowsProcessGate()),
    token = second.worker.threadId + 1;
  assert.equal(Atomics.load(shared, 0), token);
  await first.worker.terminate();
  assert.equal(f.gate.getWindowsProcessWorker(firstId), undefined);
  assert.equal(f.gate.getWindowsProcessWorker(secondId), second.worker);
  assert.equal(
    Atomics.load(shared, 0),
    token,
    'A historical Worker exit must not clear the current owner',
  );
  await second.worker.terminate();
  assert.equal(f.gate.getWindowsProcessWorker(secondId), undefined);
  assert.equal(Atomics.load(shared, 0), 0);
  assert.equal(
    asWindows(() =>
      f.gate.withWindowsProcessCreation(1000, (remaining) =>
        execFileSync(
          process.execPath,
          [fileURLToPath(processFixture), 'short', join(f.directory, 'after-termination')],
          { encoding: 'utf8', stdio: 'pipe', timeout: remaining },
        ),
      ),
    ),
    'CLI_OK\n',
  );
});

test('process gate applied child timeout stays unknown despite OK stdout and is never replayed', async (t) => {
  const f = await fixture(t),
    holder = await gateActor(f, { mode: 'hold', holdMs: 150 });
  acknowledge(holder);
  const marker = join(f.directory, 'unknown-effect');
  let attempts = 0;
  const started = performance.now();
  assert.throws(
    () =>
      asWindows(() =>
        f.gate.withWindowsProcessCreation(800, (remaining) => {
          attempts++;
          return execFileSync(
            process.execPath,
            [fileURLToPath(processFixture), 'timeout', marker],
            { encoding: 'utf8', stdio: 'pipe', timeout: remaining },
          );
        }),
      ),
    (error) => error.code === 'ETIMEDOUT' && String(error.stdout).includes('CLI_OK'),
  );
  assert(performance.now() - started < 1300, 'Gate wait and child execution share one budget');
  assert.equal(attempts, 1);
  assert.equal(await readFile(marker, 'utf8'), 'effect\n');
  assert.equal(await holder.exited, 0);
  asWindows(() =>
    f.gate.withWindowsProcessCreation(1000, (remaining) =>
      execFileSync(
        process.execPath,
        [fileURLToPath(processFixture), 'short', join(f.directory, 'new-intent')],
        { stdio: 'pipe', timeout: remaining },
      ),
    ),
  );
  assert.equal(await readFile(marker, 'utf8'), 'effect\n');
});

test('cold and warm native Workers coexist with immediate sync CLI and fully retire one helper without harming another', async (t) => {
  const f = await fixture(t);
  await f.forceWindows();
  const backends = [];
  for (const phase of ['cold', 'warm']) {
    const backend = asWindows(() => f.open());
    backends.push(backend);
    // Intentionally no ready/check call between open and synchronous creation.
    const output = asWindows(() =>
      f.gate.withWindowsProcessCreation(5000, (remaining) =>
        execFileSync(
          process.execPath,
          [fileURLToPath(processFixture), 'short', join(f.directory, phase)],
          { encoding: 'utf8', stdio: 'pipe', timeout: remaining },
        ),
      ),
    );
    assert.equal(output, 'CLI_OK\n');
    backend.call('check', 'fresh-' + phase);
  }
  assert.notEqual(backends[0].status.pid, backends[1].status.pid);
  const firstPid = backends[0].status.pid;
  backends[0].close();
  await f.retired(firstPid);
  backends[1].call('check', 'still-available');
  const secondPid = backends[1].status.pid;
  backends[1].close();
  await f.retired(secondPid);
  assert.equal(f.events().filter((event) => event.event === 'spawn').length, 2);
});

for (const mode of ['close', 'terminate'])
  test(`startup waiting on another live gate holder cancels on owner ${mode} without late spawn`, async (t) => {
    const f = await fixture(t);
    await f.forceWindows();
    const holder = await gateActor(f, { mode: 'hold', holdMs: 8000 });
    acknowledge(holder);
    let backend, owner;
    if (mode === 'close') backend = asWindows(() => f.open());
    else {
      owner = new Worker(pathToFileURL(ownerFixture), {
        execArgv: [],
        env: environment,
        workerData: {
          moduleURL: f.moduleURL.href,
          entryURL: f.entryURL.href,
          windowsProcessGate: f.gate.getWindowsProcessGate(),
        },
      });
      f.actors.push(owner);
      f.gate.registerWindowsProcessWorker(owner);
    }
    await until(
      () => f.events().some((row) => row.event === 'gate-wait'),
      'Transport must actually be waiting inside the real gate',
    );
    const started = performance.now();
    if (backend) backend.close();
    else await owner.terminate();
    await until(
      () => f.activeWorkers.size === 0,
      'Cancelled transport must exit within the original close budget',
      Math.max(1, f.p.closeMs - (performance.now() - started)),
    );
    assert(
      performance.now() - started < f.p.closeMs,
      'Cancellation cannot wait for the 30s startup budget',
    );
    assert.equal(
      Atomics.load(new Int32Array(f.gate.getWindowsProcessGate()), 0),
      holder.worker.threadId + 1,
      'The unrelated live holder must retain its own lock',
    );
    assert.equal(f.events().filter((row) => row.event === 'spawn').length, 0);
    Atomics.store(holder.control, 1, 1);
    Atomics.notify(holder.control, 1);
    assert.equal(await holder.exited, 0);
    await delay(80);
    assert.equal(
      f.events().filter((row) => row.event === 'spawn' || row.event === 'request').length,
      0,
      'A cancelled startup must never replay when the gate becomes free',
    );
    const fresh = asWindows(() => f.open());
    fresh.call('check', 'independent-new-request');
    fresh.close();
    await f.retired(fresh.status.pid);
  });

test(
  'native Windows CLI and serve run the same C# source with equivalent decisions and relative cwd',
  { skip: process.platform !== 'win32' },
  async (t) => {
    const f = await fixture(t),
      executable = join(f.directory, 'private-state.exe');
    const system = process.env.SystemRoot;
    const compiler = ['Framework64', 'Framework']
      .map((name) => join(system, 'Microsoft.NET', name, 'v4.0.30319/csc.exe'))
      .find(existsSync);
    assert(compiler, 'Native .NET compiler required');
    f.gate.withWindowsProcessCreation(30000, (timeout) =>
      execFileSync(
        compiler,
        [
          '/nologo',
          '/target:exe',
          '/optimize+',
          '/out:' + executable,
          join(repository, 'packages/codex-adapter/windows/private-state.cs'),
        ],
        { stdio: 'pipe', timeout },
      ),
    );
    await f.useNative(executable);
    const native = f,
      backend = native.open();
    const cli = (action, path) => {
      const started = performance.now();
      let cause,
        name = null,
        code = null,
        status = 0,
        signal = null,
        killed = null,
        stdout,
        stderr = null;
      try {
        stdout = f.gate.withWindowsProcessCreation(5000, (timeout) =>
          execFileSync(executable, [action, path], {
            encoding: 'utf8',
            stdio: 'pipe',
            timeout,
          }),
        );
      } catch (error) {
        cause = error;
        name = error.name ?? null;
        code = error.code ?? null;
        status = error.status ?? null;
        signal = error.signal ?? null;
        killed = error.killed ?? null;
        stdout = error.stdout == null ? null : String(error.stdout);
        stderr = error.stderr == null ? null : String(error.stderr);
      }
      const unknown = cause && (code === 'ETIMEDOUT' || signal !== null || killed === true);
      const nativeError = stderr?.trim();
      // A timeout/kill can follow an already-applied operation, even with status
      // 0 or an OK in stdout. Only an unambiguous completed C# refusal is rejected.
      const completedRejection =
        cause &&
        !unknown &&
        status === 1 &&
        (code === null || code === 1) &&
        stdout?.trim() === '' &&
        typeof nativeError === 'string' &&
        /^UNSAFE_WINDOWS_PRIVATE_STATE:[A-Za-z][A-Za-z0-9]{0,127}$/.exec(nativeError ?? '')?.[0] ===
          nativeError;
      const outcome = cause
        ? unknown
          ? 'unknown'
          : completedRejection
            ? 'rejected'
            : 'failed'
        : stdout.trim() === 'PRIVATE_STATE_OK'
          ? 'accepted'
          : 'invalid-output';
      return {
        outcome,
        cause,
        name,
        code,
        status,
        signal,
        killed,
        elapsedMs: performance.now() - started,
        stdout,
        stderr,
      };
    };
    const compare = (action, path, accepted = true, candidate = backend) => {
      const expected = cli(action, path);
      const { cause, ...diagnostic } = expected;
      try {
        assert.equal(
          expected.outcome,
          accepted ? 'accepted' : 'rejected',
          `Unexpected native CLI decision: ${JSON.stringify({ action, ...diagnostic })}`,
        );
      } catch (error) {
        if (cause) Object.defineProperty(error, 'cause', { value: cause, configurable: true });
        throw error;
      }
      if (accepted) {
        assert.equal(expected.stdout.trim(), 'PRIVATE_STATE_OK');
        candidate.call(action, path);
      } else
        assert.throws(
          () => candidate.call(action, path),
          (error) => rejected(error) && error.nativeError === expected.stderr.trim(),
        );
    };
    compare('protect', native.directory);
    const file = join(native.directory, '同源码 file.json');
    await writeFile(file, '{}');
    compare('protect-file', file);
    compare('check', relative(process.cwd(), file));
    compare('protect', file, false);
    compare('check', join(native.directory, 'missing-file'), false);
    const target = join(native.directory, 'junction-target'),
      reparse = join(native.directory, 'junction');
    await mkdir(target);
    compare('protect', target);
    await symlink(target, reparse, 'junction');
    compare('check', reparse, false);
    compare('check', file);
    assert.equal(native.events().filter((v) => v.event === 'spawn').length, 1);
    for (const stdio of ['pipe', 'file']) {
      const peer = native.open();
      // Preserve open -> immediate CLI on every cold/warm channel. A ready
      // check is performed only after CLI has returned and cannot hide the race.
      if (stdio === 'pipe') compare('check', file, true, peer);
      else {
        const stdout = join(native.directory, 'cli.stdout'),
          stderr = join(native.directory, 'cli.stderr');
        const out = openSync(stdout, 'wx', 0o600);
        try {
          const err = openSync(stderr, 'wx', 0o600);
          try {
            f.gate.withWindowsProcessCreation(5000, (timeout) =>
              execFileSync(executable, ['check', file], { stdio: ['ignore', out, err], timeout }),
            );
          } finally {
            closeSync(err);
          }
        } finally {
          closeSync(out);
        }
        assert.equal(readFileSync(stdout, 'utf8').trim(), 'PRIVATE_STATE_OK');
        assert.equal(readFileSync(stderr, 'utf8'), '');
        peer.call('check', file);
      }
      const pid = peer.status.pid;
      backend.call('check', file);
      peer.close();
      await native.retired(pid);
      backend.call('check', file);
    }
    backend.close();
    assert(backend.status.gone);
    await native.retired(backend.status.pid);
    // Same owner-termination boundary, now against the real same-source C#
    // reader. Its EOF path must retire the process independently of QUIT.
    await verifyOwnerTermination(native, file);
    assert.equal(native.events().filter((v) => v.event === 'spawn').length, 5);
  },
);
