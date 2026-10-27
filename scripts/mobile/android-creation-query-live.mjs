import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath, readdir, lstat } from 'node:fs/promises';
import { join, resolve, isAbsolute, relative } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// This examiner cannot accept, continue, detach, open or otherwise write any remote operation.
assert.equal(process.platform, 'darwin');
const preflightOnly = process.env.ANDROID_CREATION_QUERY_PREFLIGHT_ONLY === '1';
const serial = process.env.ANDROID_SERIAL,
  pkg = 'com.easyai.agenthub.android.acceptance';
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const output = process.env.MOBILE_CREATION_QUERY_OUTPUT,
  originalReport = process.env.ANDROID_CREATION_QUERY_ORIGINAL_REPORT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
assert(
  originalReport &&
    isAbsolute(originalReport) &&
    !resolve(originalReport).startsWith(process.cwd() + '/'),
);
const read = async (p) => JSON.parse(await readFile(p, 'utf8')),
  sha = (x) => createHash('sha256').update(x).digest('hex');
const old = await read(originalReport),
  record = old.finalNativeRecord,
  intent = JSON.parse(record.intent),
  hostId = old.hostId,
  threadId = old.threadIds[0];
assert.equal(old.phase, 'P4-4g.3');
assert.equal(old.status, 'passed');
assert.equal(old.newRealModelInputsConfirmed, 1);
assert.equal(old.serial, serial);
assert(record.detached && record.phase === 'confirmed' && record.kind === 'creation');
assert.equal(record.intentSha256, sha(record.intent));
assert.equal(record.id, intent.clientOperationId);
assert.equal(intent.hostId, hostId);
assert.equal(intent.projectId, old.projectId);
assert.equal(JSON.parse(record.result).threadId, threadId);
assert.equal(preparedAdapterDigest(), old.adapterSha256);
const state = await realpath(join(originalReport, '..', 'host')),
  projectRoot = await realpath(old.projectRoot);
assert.equal((await read(join(state, 'runtime.json'))).hostId, hostId);
assert.equal((await read(join(state, 'bridge.json'))).hostId, hostId);
const root = join(output, 'creation-query-live-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const execute = promisify(execFile),
  toolchain = await read('.local/mobile-toolchain.json'),
  adb = resolve(toolchain.androidSdk, 'platform-tools/adb');
const android = async (...args) =>
  (
    await execute(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 30000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout.trim();
assert.equal(await android('get-state'), 'device');
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
let token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
assert(token.length >= 32);
const clean = (x) => String(x).replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]');
const fingerprint = async () => {
  const files = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'apps/host-agent',
      'packages/codex-adapter',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile/android-creation-query-live.mjs',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const h = createHash('sha256');
  for (const f of new Set(files))
    h.update(f + '\0')
      .update(await readFile(f))
      .update('\0');
  return h.digest('hex');
};
const report = {
  phase: 'P4-4g.8b',
  status: 'running',
  preflightOnly,
  serial,
  nonce,
  hostId,
  projectId: old.projectId,
  threadId,
  originalOperationId: record.id,
  originalIntentSha256: record.intentSha256,
  originalReportSha256: sha(await readFile(originalReport)),
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  newRealModelInputsConfirmed: 0,
  reusedOriginalAcceptedInputs: 1,
  scope:
    'Current-source native read-only query of the actual original detached confirmed journal record and same owned Host state. Original model input is matching reused evidence. All remote writes forbidden. Controlled HTTP query failures test navigation ownership; no cellular/VPN, natural network failure, system picker, archived-owner, whole node or full visual claim.',
  queries: [],
  posts: [],
  rejectedReads: [],
  ownedPorts: [],
  native: [],
  audits: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original creation query read-only independent witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, endpoint, fixture;
const reverses = [],
  active = new Set(),
  claims = new Map();
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const wait = async (fn, label) => {
  const deadline = Date.now() + 25000;
  do {
    const result = await fn();
    if (result) return result;
    await new Promise((r) => setTimeout(r, 100));
  } while (Date.now() < deadline);
  throw Error(label + ' deadline');
};
const listen = async (s) => {
  await new Promise((r, j) => {
    s.once('error', j);
    s.listen(0, '127.0.0.1', r);
  });
  return s.address().port;
};
async function audit(label) {
  await witness.ipc.follow(threadId, 5000, true);
  const owner = witness.ipc.state(threadId);
  assert(
    owner && owner.id === threadId && owner.hostId === 'local' && witness.ipc.hasOwner(threadId),
  );
  assert.equal(await realpath(owner.cwd), projectRoot);
  assert.equal(owner.requests.length, 0);
  const turns = desktopTurns(owner);
  assert.equal(turns.length, 1);
  assert.equal(turns[0].status, 'completed');
  const users = turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage');
  assert.equal(users.length, 1);
  assert.equal(users[0].clientId, 'first-' + record.id);
  assert.deepEqual(
    users[0].content.filter((i) => i.type === 'text').map((i) => i.text),
    [intent.text],
  );
  const canonical = await witness.reader.read('thread/read', { threadId, includeTurns: true });
  assert.equal(canonical.thread.turns.length, 1);
  assert.equal(
    canonical.thread.turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage')
      .length,
    1,
  );
  const safe = label.replaceAll(/[^a-z0-9-]/g, '');
  await writeFile(join(root, safe + '-owner.json'), JSON.stringify(owner, null, 2), {
    mode: 0o600,
  });
  await writeFile(join(root, safe + '-canonical.json'), JSON.stringify(canonical, null, 2), {
    mode: 0o600,
  });
  report.audits.push({
    label,
    originalOwnerLoaded: true,
    ownerInputs: 1,
    canonicalInputs: 1,
    newInputs: 0,
  });
  await save();
}
async function snapshotOriginalState() {
  const occupied = await execute('/usr/sbin/lsof', ['+D', state], {
    encoding: 'utf8',
    timeout: 10000,
  })
    .then((r) => r.stdout)
    .catch((e) => {
      assert.equal(e.code, 1);
      return e.stdout ?? '';
    });
  assert.equal(occupied.trim(), '', 'Original state must be stopped before snapshot');
  const files = [];
  async function walk(dir) {
    for (const name of (await readdir(dir)).sort()) {
      const p = join(dir, name),
        st = await lstat(p);
      assert(!st.isSymbolicLink());
      if (st.isDirectory()) await walk(p);
      else {
        assert(st.isFile());
        files.push({ path: relative(state, p), sha256: sha(await readFile(p)) });
      }
    }
  }
  await walk(state);
  const archive = (
    await execute('/usr/bin/tar', ['-cf', '-', '-C', state, '.'], {
      encoding: 'buffer',
      timeout: 15000,
      maxBuffer: 64 * 1024 * 1024,
    })
  ).stdout;
  const file = join(root, 'original-host-state-before.tar');
  await writeFile(file, archive, { mode: 0o600 });
  assert.equal(sha(await readFile(file)), sha(archive));
  for (const f of files) {
    const bytes = (
      await execute('/usr/bin/tar', ['-xOf', file, './' + f.path], {
        encoding: 'buffer',
        timeout: 10000,
        maxBuffer: 32 * 1024 * 1024,
      })
    ).stdout;
    assert.equal(sha(bytes), f.sha256);
    assert.equal(sha(await readFile(join(state, f.path))), f.sha256);
  }
  await writeFile(
    join(root, 'original-host-state-before-manifest.json'),
    JSON.stringify({ files, archiveSha256: sha(archive), readbackVerified: true }, null, 2),
    { mode: 0o600 },
  );
  report.originalStateSnapshot = {
    files: files.length,
    archiveSha256: sha(archive),
    readbackVerified: true,
  };
}
async function startHost() {
  const env = {
    ...process.env,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
  };
  delete env.HOST_AGENT_TOKEN;
  delete env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete env.HOST_AGENT_DESKTOP_CONTROL_THREAD;
  const child = spawn(process.execPath, ['apps/host-agent/dist/runtime-entry.js'], {
    cwd: process.cwd(),
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  service = { child, logs: [] };
  const lines = createInterface({ input: child.stdout });
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Original owned Host ready deadline')), 30000);
    child.once('error', reject);
    child.once('exit', (c, s) => {
      clearTimeout(timer);
      reject(Error('Host early exit ' + c + '/' + s));
    });
    child.stderr.on('data', (b) => service.logs.push(clean(b.toString())));
    lines.on('line', (l) => {
      service.logs.push(clean(l));
      try {
        const v = JSON.parse(l);
        if (v.service === 'host-agent' && v.ready === true && v.mode === 'desktop-runtime') {
          clearTimeout(timer);
          done(v);
        }
      } catch {
        /* Non-JSON log lines do not select an endpoint. */
      }
    });
  });
  assert.equal(ready.address.address, '127.0.0.1');
  assert.equal(ready.tokenFile, join(state, 'access-token'));
  assert.equal((await readFile(ready.tokenFile, 'utf8')).trim(), token);
  endpoint = 'http://127.0.0.1:' + ready.address.port;
  report.ownedPorts.push({ label: 'host', port: ready.address.port, pid: child.pid });
}
const gets = new Set([
  '/healthz',
  '/v1/workspace',
  '/v1/models',
  '/v1/history',
  '/v1/events',
  '/v1/desktop/creations',
  '/v1/desktop/creation-status',
  '/v1/desktop/creation-receipts',
]);
const receiptPaths = new Set([
  '/v1/desktop/creations',
  '/v1/desktop/creation-status',
  '/v1/desktop/creation-receipts',
]);
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  assert.equal(witness.version, old.desktopVersion);
  await audit('before-host');
  await snapshotOriginalState();
  await startHost();
  const wres = await fetch(endpoint + '/v1/workspace?hostId=' + encodeURIComponent(hostId), {
    headers: { Authorization: 'Bearer ' + token },
    signal: AbortSignal.timeout(30000),
  });
  assert.equal(wres.status, 200);
  const workspace = await wres.json();
  assert.equal(workspace.hostId, hostId);
  // Codex workspace does not publish the optional Agent connection-state field.
  // Actual runtime readiness is verified through the live original owner before and after.
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  assert(workspace.capabilities.includes('commands:target-bound:v1'));
  assert(workspace.capabilities.includes('desktop:creations'));
  const project = workspace.projects.find((p) => p.id === old.projectId);
  assert(project);
  assert((await Promise.all(project.roots.map((p) => realpath(p)))).includes(projectRoot));
  report.workspaceCapabilities = workspace.capabilities;
  proxy = createServer((req, res) => {
    const entry = { controller: new AbortController() };
    active.add(entry);
    void (async () => {
      assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + token);
      const url = new URL(req.url, endpoint);
      if (req.method !== 'GET') {
        report.posts.push({ method: req.method, path: url.pathname });
        return json(res, { error: 'All remote writes forbidden' }, 403);
      }
      assert(gets.has(url.pathname), 'Unexpected read route ' + url.pathname);
      if (url.pathname === '/v1/workspace' || url.pathname === '/healthz') {
        assert([null, '', hostId].includes(url.searchParams.get('hostId')));
      } else assert.equal(url.searchParams.get('hostId'), hostId);
      if (['/v1/history', '/v1/events'].includes(url.pathname))
        assert.equal(
          url.searchParams.get('threadId'),
          threadId,
          'Only original owned task history permitted',
        );
      if (receiptPaths.has(url.pathname)) {
        assert.equal(url.searchParams.get('id'), record.id);
        const q = [...claims.values()].find((q) => !q.started);
        assert(q, 'No unclaimed or automatic receipt read');
        q.started = true;
        report.queries.push({
          index: q.index,
          mode: q.mode,
          path: url.pathname,
          operationId: record.id,
        });
        await save();
        if (q.mode === 'held-failure') {
          await wait(() => q.released, 'Query release');
          q.responded = true;
          return json(res, { error: 'Owned original query transport failure ' + nonce }, 503);
        }
        const response = await fetch(url, {
          headers: { Authorization: 'Bearer ' + token },
          redirect: 'error',
          signal: entry.controller.signal,
        });
        const bytes = Buffer.from(await response.arrayBuffer());
        assert.equal(response.status, 200);
        const receipt = JSON.parse(bytes);
        assert.equal(receipt.clientOperationId, record.id);
        assert.equal(receipt.hostId, hostId);
        assert.equal(receipt.phase, 'confirmed');
        assert.equal(receipt.threadId, threadId);
        await writeFile(join(root, 'actual-query-' + q.index + '.json'), bytes, { mode: 0o600 });
        q.responded = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(bytes);
        return;
      }
      const response = await fetch(url, {
        headers: { Authorization: 'Bearer ' + token },
        redirect: 'error',
        signal: entry.controller.signal,
      });
      res.writeHead(response.status, {
        'Content-Type': response.headers.get('content-type') ?? 'application/json',
      });
      if (url.pathname === '/v1/events') {
        const reader = response.body.getReader();
        res.on('close', () => entry.controller.abort());
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            res.write(value);
          }
        } finally {
          await reader.cancel().catch(() => {});
        }
        res.end();
      } else res.end(Buffer.from(await response.arrayBuffer()));
    })()
      .catch(async (e) => {
        if (!entry.controller.signal.aborted) {
          report.rejectedReads.push(clean(e.message));
          if (!res.headersSent) json(res, { error: clean(e.message) }, 500);
          else res.destroy();
        }
      })
      .finally(() => active.delete(entry));
  });
  const proxyPort = await listen(proxy);
  report.ownedPorts.push({ label: 'proxy', port: proxyPort, pid: process.pid });
  control = createServer((req, res) => {
    void (async () => {
      assert.equal(req.method, 'POST');
      assert.equal(req.headers.authorization, 'Bearer ' + controlKey);
      const chunks = [];
      let size = 0;
      for await (const b of req) {
        size += b.length;
        assert(size < 100000);
        chunks.push(b);
      }
      const v = JSON.parse(Buffer.concat(chunks));
      assert.equal(v.nonce, nonce);
      const route = new URL(req.url, 'http://127.0.0.1').pathname;
      if (route === '/claim-query') {
        assert.equal(v.index, claims.size);
        assert.equal(v.operationId, record.id);
        assert.equal(v.targetKey, record.sourceKey);
        assert.equal(
          v.mode,
          preflightOnly || v.index === 3 ? 'actual-original-receipt' : 'held-failure',
        );
        assert([...claims.values()].every((q) => q.completed));
        claims.set(v.index, { index: v.index, mode: v.mode });
      } else if (route === '/await-query-start') {
        await wait(() => claims.get(v.index)?.started, 'Native original GET');
      } else if (route === '/release-query') {
        const q = claims.get(v.index);
        assert(q?.started && q.mode === 'held-failure' && !q.released);
        q.released = true;
        await wait(() => q.responded, 'Controlled response');
      } else if (route === '/query-complete') {
        const q = claims.get(v.index);
        assert(q?.started && q.responded && !q.completed);
        q.completed = true;
        q.native = v;
      } else if (route === '/preflight-complete' || route === '/case-complete') {
        assert.equal(route, preflightOnly ? '/preflight-complete' : '/case-complete');
        assert.equal(v.newModelInputs, 0);
        assert.equal(v.intentSha256, record.intentSha256);
        assert.equal(v.originalId, record.id);
        assert.equal(v.targetKey, record.sourceKey);
        assert.equal(v.localText, fixture.independentDraft);
        assert.equal(v.localFile.sha256, sha(fixture.nextFileText));
        assert([...claims.values()].every((q) => q.completed));
        assert.equal(claims.size, preflightOnly ? 1 : 4);
        report.nativeCompletion = v;
        await audit('after-native');
      } else throw Error('Unsupported native control route');
      json(res, { ok: true });
    })().catch((e) => {
      report.controlFailure = clean(e.stack);
      json(res, { error: clean(e.message) }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts.push({ label: 'control', port: controlPort, pid: process.pid });
  fixture = {
    nonce,
    evidenceRun: randomUUID(),
    hostId,
    token,
    endpoint: 'http://127.0.0.1:' + proxyPort,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    projectId: old.projectId,
    preflightOnly,
    originalTarget: record.target,
    operationId: record.id,
    originalSourceKey: record.sourceKey,
    originalIntentSha256: record.intentSha256,
    independentDraft: 'Android original query independent draft ' + nonce,
    nextFileName: 'query-independent-' + nonce + '.txt',
    nextFileText: 'Android local query proof ' + nonce + '\n',
  };
  for (const port of [proxyPort, controlPort]) {
    await android('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    reverses.push(port);
  }
  for (const [name, source, packageName] of [
    ['app', '.build/native/android/outputs/apk/debug/android-debug.apk', pkg],
    [
      'test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      pkg + '.test',
    ],
  ]) {
    const apk = join(root, name + '.apk');
    await writeFile(apk, await readFile(source), { mode: 0o600 });
    report[name + 'Sha256'] = sha(await readFile(apk));
    await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true });
  }
  await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
  execFileSync(
    adb,
    [
      '-s',
      serial,
      'shell',
      `run-as ${pkg} sh -c 'umask 077; cat > files/mobile-creation-query-config.json'`,
    ],
    { input: JSON.stringify(fixture), encoding: 'utf8', timeout: 10000 },
  );
  let result;
  try {
    result = await execute(
      adb,
      [
        '-s',
        serial,
        'shell',
        'am',
        'instrument',
        '-w',
        '-r',
        '-e',
        'class',
        'com.agenthub.android.MobileCreationQueryModelLiveTest#originalDetachedCreationQueriesKeepTheirNavigationOwnerWithoutNewInput',
        pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
      ],
      { encoding: 'utf8', timeout: 650000, maxBuffer: 16 * 1024 * 1024 },
    );
  } catch (e) {
    await writeFile(
      join(root, 'instrumentation-command-failure.json'),
      JSON.stringify({ code: e.code, stdout: clean(e.stdout), stderr: clean(e.stderr) }, null, 2),
      { mode: 0o600 },
    );
    throw e;
  }
  const log = clean(result.stdout + result.stderr);
  await writeFile(join(root, 'instrumentation.log'), log, { mode: 0o600 });
  assert(
    /OK \(1 test\)/.test(log) &&
      !/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed/.test(log),
  );
  report.native.push({ status: 'passed', logSha256: sha(log) });
  assert(report.nativeCompletion);
  assert.equal(report.posts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  assert(!report.controlFailure);
  assert.equal(await fingerprint(), report.sourceSha256);
  await audit('final');
  report.claims = [...claims.values()];
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (fixture)
    try {
      const archive = (
        await execute(
          adb,
          [
            '-s',
            serial,
            'exec-out',
            'run-as',
            pkg,
            'tar',
            '-cf',
            '-',
            'files/acceptance/P4-creation-query/' + nonce + '/' + fixture.evidenceRun,
          ],
          { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
        )
      ).stdout;
      await writeFile(join(root, 'native-evidence.tar'), archive, { mode: 0o600 });
    } catch (e) {
      report.artifactError = clean(e.message);
    }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const port of reverses) await android('reverse', '--remove', 'tcp:' + port).catch(() => {});
  for (const e of active) e.controller.abort();
  for (const s of [proxy, control])
    if (s) {
      s.closeAllConnections();
      await new Promise((r) => s.close(r));
    }
  await witness.close();
  if (service) {
    const child = service.child;
    if (child.exitCode === null && child.signalCode === null) {
      await new Promise((done, reject) => {
        const t = setTimeout(() => reject(Error('Owned Host close deadline')), 15000);
        child.once('exit', () => {
          clearTimeout(t);
          done();
        });
        child.kill('SIGTERM');
      }).catch((e) => {
        report.cleanupError = clean(e.message);
        report.status = 'failed';
        process.exitCode = 1;
      });
    }
    report.hostProcessExit = { pid: child.pid, code: child.exitCode, signal: child.signalCode };
    await writeFile(join(root, 'host-process.log'), service.logs.join('\n'), { mode: 0o600 });
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      preflightOnly,
      nativeMethods: report.native.length,
      newModelInputs: 0,
      error: report.error,
    }),
  );
}
