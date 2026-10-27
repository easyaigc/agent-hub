import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { createInterface } from 'node:readline';
import { createHash, randomUUID, randomBytes, X509Certificate } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir, lstat, realpath } from 'node:fs/promises';
import { join, resolve, relative, isAbsolute } from 'node:path';
import { createServer as httpsServer } from 'node:https';
import { createServer as httpServer } from 'node:http';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
assert.equal(process.platform, 'darwin');
const diagnosticOnly = process.env.ANDROID_REPLY_TLS_READ_DIAGNOSTIC === '1';
const nativeCase = process.env.ANDROID_REPLY_TLS_READ_CASE ?? 'reply-read';
assert(['reply-read', 'p3-sidebar-search'].includes(nativeCase));
assert(nativeCase === 'reply-read' || !diagnosticOnly);
const originalReport = process.env.ANDROID_REPLY_TLS_READ_ORIGINAL_REPORT,
  output = process.env.ANDROID_REPLY_TLS_READ_OUTPUT;
assert(
  originalReport &&
    output &&
    isAbsolute(originalReport) &&
    isAbsolute(output) &&
    !resolve(output).startsWith(process.cwd() + '/'),
);
const execute = promisify(execFile),
  read = async (p) => JSON.parse(await readFile(p, 'utf8')),
  sha = (b) => createHash('sha256').update(b).digest('hex');
const old = await read(originalReport),
  oldRoot = await realpath(join(originalReport, '..'));
assert.equal(old.phase, 'P4-4h.2');
assert.equal(old.status, 'failed');
assert(
  old.caseCompleted &&
    old.native.length === 2 &&
    old.newRealModelInputsConfirmed === 2 &&
    !old.unknownSubmittedInputIds.length,
);
assert.deepEqual(old.rejectedReads, ['Unknown read route', 'Unknown read route']);
// Revalidate an explicitly selected read-only repair against its own digest;
// the default remains the immutable original implementation fingerprint.
const expectedAdapterSha256 =
  process.env.ANDROID_REPLY_TLS_EXPECT_ADAPTER_SHA256 ?? old.adapterSha256;
assert.match(expectedAdapterSha256, /^[a-f0-9]{64}$/);
assert.equal(preparedAdapterDigest(), expectedAdapterSha256);
const expectedAppSha256 = process.env.ANDROID_REPLY_TLS_EXPECT_APP_SHA256 ?? old.appSha256;
assert.match(expectedAppSha256, /^[a-f0-9]{64}$/);
// A desktop restart/update needs a separately pinned acceptance environment.
// The default remains the original scene's exact version; never accept any build.
const expectedDesktopVersion =
  process.env.ANDROID_REPLY_TLS_EXPECT_DESKTOP_VERSION ?? old.desktopVersion;
assert.match(expectedDesktopVersion, /^\d+\.\d+\.\d+$/);
const state = await realpath(join(oldRoot, 'host')),
  hostId = old.hostId,
  threadId = old.threadId,
  projectRoot = await realpath(old.projectRoot),
  nonce = randomUUID().slice(0, 8);
const root = join(output, 'reply-tls-read-audit-' + Date.now() + '-' + nonce);
await mkdir(root, { recursive: true, mode: 0o700 });
const token = (await readFile(join(state, 'access-token'), 'utf8')).trim(),
  controlKey = randomBytes(32).toString('base64url');
assert(token.length >= 32);
const clean = (x) => String(x).replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]');
const serial = process.env.ANDROID_SERIAL,
  pkg = 'com.easyai.agenthub.android.acceptance';
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const adb = resolve((await read('.local/mobile-toolchain.json')).androidSdk, 'platform-tools/adb');
const android = async (...a) =>
  (
    await execute(adb, ['-s', serial, ...a], {
      encoding: 'utf8',
      timeout: 30000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout.trim();
assert.equal(await android('get-state'), 'device');
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const occupiedReversePorts = new Set(
  [...(await android('reverse', '--list')).matchAll(/tcp:(\d+)/g)].map((v) => Number(v[1])),
);

const sourceFiles = execFileSync(
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
    'scripts/mobile/android-reply-tls-read-audit-live.mjs',
  ],
  { encoding: 'utf8' },
)
  .trim()
  .split('\n')
  .filter(Boolean)
  .sort();
const fingerprint = async () => {
  const h = createHash('sha256');
  for (const f of new Set(sourceFiles))
    h.update(f + '\0')
      .update(await readFile(f))
      .update('\0');
  return h.digest('hex');
};
const report = {
  phase: nativeCase === 'p3-sidebar-search' ? 'P3-6a' : 'P4-4h.3',
  nativeCase,
  originalAppSha256: old.appSha256,
  appUpgradeUnderReadonly: expectedAppSha256 !== old.appSha256,
  originalDesktopVersion: old.desktopVersion,
  expectedDesktopVersion,
  desktopUpgradeUnderReadonly: expectedDesktopVersion !== old.desktopVersion,
  status: 'running',
  diagnosticOnly,
  hostId,
  threadId,
  serial,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  originalAdapterSha256: old.adapterSha256,
  expectedAdapterSha256,
  adapterUpgradeUnderReadonly: expectedAdapterSha256 !== old.adapterSha256,
  originalReportSha256: sha(await readFile(originalReport)),
  requests: [],
  rejectedReads: [],
  posts: [],
  readCancellations: [],
  ownedPorts: [],
  audits: [],
  newRealModelInputsConfirmed: 0,
  reusedOriginalAcceptedInputs: 2,
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
let service, proxy, control, endpoint, fixture;
const active = new Set(),
  reverses = [];
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android accepted original read-only scope audit',
);
assert(process.env.CODEX_EXECUTABLE);
const json = (r, v, s = 200) => {
  r.writeHead(s, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  r.end(JSON.stringify(v));
};
const listen = async (s, port = 0) => {
  for (let attempt = 0; attempt < 20; attempt++) {
    await new Promise((r, j) => {
      s.once('error', j);
      s.listen(port, '127.0.0.1', r);
    });
    const selected = s.address().port;
    if (port !== 0 || !occupiedReversePorts.has(selected)) return selected;
    await new Promise((r) => s.close(r));
  }
  throw Error('No unoccupied owned reverse control port');
};

const machine = (v) =>
  Object.fromEntries(['agentId', 'hostId', 'workspaceId', 'runtimeId'].map((k) => [k, v[k]]));
async function audit(label) {
  await witness.ipc.follow(threadId, 5000, true);
  const owner = witness.ipc.state(threadId);
  assert(
    owner && owner.id === threadId && owner.hostId === 'local' && witness.ipc.hasOwner(threadId),
  );
  assert.equal(await realpath(owner.cwd), projectRoot);
  assert.equal(owner.requests.length, 0);
  const turns = desktopTurns(owner);
  assert.equal(turns.length, 2);
  assert(turns.every((t) => t.status === 'completed'));
  const users = turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage');
  assert.equal(users.length, 2);
  const originalCases = await read(join(oldRoot, 'immutable-cases.json')),
    seed = await read(join(oldRoot, 'seed-original-request.json')),
    reply = await read(join(oldRoot, 'reply-original-request.json'));
  assert.deepEqual(
    users.map((u) => u.clientId),
    ['first-' + seed.body.clientOperationId, reply.id],
  );
  for (let n = 0; n < 2; n++)
    assert.deepEqual(
      users[n].content.filter((i) => i.type === 'text').map((i) => i.text),
      [originalCases.cases[n].text],
    );
  const canonical = await witness.reader.read('thread/read', { threadId, includeTurns: true });
  assert.equal(
    canonical.thread.turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage')
      .length,
    2,
  );
  for (const [kind, value] of [
    ['owner', owner],
    ['canonical', canonical],
  ])
    await writeFile(join(root, label + '-' + kind + '.json'), JSON.stringify(value, null, 2), {
      mode: 0o600,
    });
  assert.equal(turns[1].turnId, canonical.thread.turns[1].id);
  assert.match(turns[1].turnId, /^[a-f0-9-]{36}$/);
  report.lastTurnId = turns[1].turnId;
  report.audits.push({ label, ownerInputs: 2, canonicalInputs: 2, newInputs: 0 });
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
  delete env.HOST_AGENT_REPLY_CHECKPOINT_FILE;
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
  '/v1/workspace',
  '/v1/connection-routes',
  '/v1/models',
  '/v1/history',
  '/v1/events',
  '/v1/receipts',
  '/v1/replies/status',
  '/v1/desktop/creation-receipts',
  '/v1/desktop/creations',
  ...(!diagnosticOnly ? ['/v1/threads/fork-options'] : []),
  ...(nativeCase === 'p3-sidebar-search' ? ['/v1/threads/search-history'] : []),
]);
try {
  await witness.connect();
  report.observedDesktopVersion = witness.version;
  assert.equal(witness.version, expectedDesktopVersion);
  await audit('before');
  await snapshotOriginalState();
  await startHost();
  const cert = await readFile(join(oldRoot, 'certificate.pem'));
  assert.equal(sha(new X509Certificate(cert).raw), old.certificateSha256);
  const tlsPort = old.ownedPorts.find((p) => p.label === 'tls-edge').port;
  proxy = httpsServer({ key: await readFile(join(oldRoot, 'key.pem')), cert }, (req, res) => {
    const entry = { controller: new AbortController() };
    active.add(entry);
    res.on('close', () => {
      if (!res.writableEnded) {
        entry.reason = 'native-reader-close';
        entry.controller.abort();
      }
    });
    void (async () => {
      const url = new URL(req.url, 'https://127.0.0.1');
      const attempt = {
        method: req.method,
        path: url.pathname,
        queryKeys: [...url.searchParams.keys()].sort(),
      };
      report.requests.push(attempt);
      assert(!req.headers.origin);
      assert.equal(req.headers.authorization, 'Bearer ' + token);
      assert.equal(req.method, 'GET', 'All product writes forbidden');
      assert([null, '', hostId].includes(url.searchParams.get('hostId')));
      if (url.searchParams.has('target'))
        assert.deepEqual(machine(JSON.parse(url.searchParams.get('target'))), machine(old.target));
      if (!gets.has(url.pathname)) {
        attempt.scopeRejected = true;
        report.rejectedReads.push({ path: url.pathname, queryKeys: attempt.queryKeys });
        await save();
        return json(res, { error: 'Owned diagnostic GET route not in original whitelist' }, 403);
      }
      if (
        [
          '/v1/history',
          '/v1/events',
          '/v1/receipts',
          '/v1/replies/status',
          '/v1/threads/fork-options',
          '/v1/threads/search-history',
        ].includes(url.pathname)
      )
        assert.equal(url.searchParams.get('threadId'), threadId);
      if (['/v1/receipts', '/v1/replies/status'].includes(url.pathname))
        assert.equal(url.searchParams.get('id'), old.caseCompleted.record.id);
      if (['/v1/desktop/creation-receipts', '/v1/desktop/creations'].includes(url.pathname))
        assert.equal(
          url.searchParams.get('id'),
          (await read(join(oldRoot, 'seed-original-request.json'))).body.clientOperationId,
        );
      if (url.pathname === '/v1/connection-routes') {
        assert.equal(url.searchParams.get('hostId'), hostId);
        assert.deepEqual([...url.searchParams.keys()], ['hostId']);
        assert.equal(url.searchParams.getAll('hostId').length, 1);
      }
      if (url.pathname === '/v1/threads/search-history') {
        assert.equal(url.searchParams.get('hostId'), hostId);
        assert.equal(url.searchParams.get('threadId'), threadId);
        for (const key of url.searchParams.keys()) {
          assert(['hostId', 'threadId', 'cursor'].includes(key));
          assert.equal(url.searchParams.getAll(key).length, 1);
        }
      }
      const r = await fetch(new URL(url.pathname + url.search, endpoint), {
          headers: { Authorization: 'Bearer ' + token },
          signal: entry.controller.signal,
          redirect: 'error',
        }),
        data = Buffer.from(await r.arrayBuffer());
      attempt.status = r.status;
      attempt.forwarded = true;
      assert([200, 404, 409].includes(r.status), 'Unexpected read status');
      if (url.pathname === '/v1/threads/search-history') {
        assert.equal(r.status, 200);
        const page = JSON.parse(data);
        assert.equal(page.hostId, hostId);
        assert.equal(page.threadId, threadId);
        assert.deepEqual(machine(page.readTarget), machine(old.target));
        report.completeSearchReads = (report.completeSearchReads ?? 0) + 1;
      }
      if (url.pathname === '/v1/threads/fork-options') {
        const options = JSON.parse(data);
        assert.equal(options.hostId, hostId);
        assert.equal(options.threadId, threadId);
        attempt.sameDirectoryAvailable = options.sameDirectoryAvailable;
      }
      await save();
      res.writeHead(r.status, { 'Content-Type': 'application/json' });
      res.end(data);
    })()
      .catch(async (e) => {
        if (entry.controller.signal.aborted)
          report.readCancellations.push({ kind: entry.reason ?? 'owned-close' });
        else {
          report.guardFailure = clean(e.stack);
          if (req.method !== 'GET')
            report.posts.push({
              method: req.method,
              path: new URL(req.url, 'https://127.0.0.1').pathname,
            });
        }
        if (!res.destroyed && !res.headersSent)
          json(res, { error: 'Owned read-only guard refused request' }, 403);
        await save();
      })
      .finally(() => active.delete(entry));
  });
  await listen(proxy, tlsPort);
  report.ownedPorts.push({ label: 'tls-edge', port: tlsPort, pid: process.pid });
  control = httpServer((req, res) => {
    void (async () => {
      assert.equal(req.method, 'POST');
      assert.equal(req.headers.authorization, 'Bearer ' + controlKey);
      const chunks = [];
      let n = 0;
      for await (const b of req) {
        n += b.length;
        assert(n < 200000);
        chunks.push(b);
      }
      const v = JSON.parse(Buffer.concat(chunks));
      assert.equal(v.nonce, nonce);
      const route = new URL(req.url, 'http://127.0.0.1').pathname;
      if (route === '/read-observed')
        return json(res, {
          observed: report.requests.some(
            (r) => r.path === '/v1/threads/fork-options' && (r.status === 200 || r.scopeRejected),
          ),
        });
      assert.equal(route, '/read-complete');
      assert.equal(v.localText, old.caseCompleted.localText);
      assert.deepEqual(v.localFile, old.caseCompleted.localFile);
      assert.equal(v.originalReply.id, old.caseCompleted.record.id);
      assert.equal(v.originalReply.intent, old.caseCompleted.record.intent);
      assert.equal(v.originalReply.intentSha256, old.caseCompleted.record.intentSha256);
      assert(v.originalReply.replyRecovery.consumed);
      assert.equal(v.fingerprint, old.certificateSha256);
      assert.equal(v.sourceKey, old.caseCompleted.sourceKey);
      assert.equal(v.threadId, threadId);
      report.completed = v;
      await audit('native-complete');
      json(res, { ok: true });
    })().catch((e) => {
      report.controlFailure = clean(e.stack);
      json(res, { error: 'Read audit control failed' }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts.push({ label: 'control', port: controlPort, pid: process.pid });
  fixture = {
    nonce,
    evidenceRun: randomUUID(),
    nativePhase: 0,
    hostId,
    threadId,
    sourceKey: old.caseCompleted.sourceKey,
    fingerprint: old.certificateSha256,
    model: old.model,
    projectId: old.projectId,
    searchNeedle: (await read(join(oldRoot, 'immutable-cases.json'))).cases[1].marker,
    localFileId: old.caseCompleted.localFile.id,
    localFileText: 'Local later reply bytes ' + old.nonce + '\n',
    independentDraft: old.caseCompleted.localText,
    originalReplyId: old.caseCompleted.record.id,
    originalReply: old.caseCompleted.record,
    lastTurnId: report.lastTurnId,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    diagnosticOnly,
  };
  for (const port of [tlsPort, controlPort]) {
    await android('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    reverses.push(port);
  }
  for (const [name, file, packageName] of [
    ['app', '.build/native/android/outputs/apk/debug/android-debug.apk', pkg],
    [
      'test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      pkg + '.test',
    ],
  ]) {
    assert(name !== 'app' || sha(await readFile(file)) === expectedAppSha256);
    const apk = join(root, name + '.apk');
    await writeFile(apk, await readFile(file), { mode: 0o600 });
    report[name + 'Sha256'] = sha(await readFile(apk));
    await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true });
  }
  await android('shell', 'am', 'force-stop', pkg);
  await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
  execFileSync(
    adb,
    [
      '-s',
      serial,
      'shell',
      `run-as ${pkg} sh -c 'umask 077; cat > files/mobile-reply-tls-read-audit-config.json'`,
    ],
    { input: JSON.stringify(fixture), encoding: 'utf8', timeout: 10000 },
  );
  const r = await execute(
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
      nativeCase === 'p3-sidebar-search'
        ? 'com.agenthub.android.MobileSidebarSearchReadLiveTest#sameOriginalSidebarSearchAndNativeCompleteHistoryLocatePreserveDraft'
        : 'com.agenthub.android.MobileReplyTlsReadAuditLiveTest#coldRestoreAndAuditAcceptedOriginalReadRoutes',
      pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 240000, maxBuffer: 16 * 1024 * 1024 },
  );
  await writeFile(join(root, 'instrumentation.log'), clean(r.stdout + r.stderr), { mode: 0o600 });
  assert(
    /OK \(1 test\)/.test(r.stdout) &&
      !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(r.stdout),
  );
  assert(
    report.completed && !report.guardFailure && !report.controlFailure && !report.posts.length,
  );
  assert.equal(await fingerprint(), report.sourceSha256);
  if (diagnosticOnly) {
    assert(report.rejectedReads.length >= 1);
    assert(report.rejectedReads.every((r) => r.path === '/v1/threads/fork-options'));
  } else {
    assert.equal(report.rejectedReads.length, 0);
    assert(
      report.requests.some(
        (r) =>
          r.path === '/v1/threads/fork-options' && r.status === 200 && r.sameDirectoryAvailable,
      ),
    );
    if (nativeCase === 'reply-read') assert(report.completed.sameDirectoryAvailable);
    else {
      assert(report.completed.sidebarAndSearchVerified);
      assert(report.completeSearchReads >= 1);
    }
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  try {
    await audit('final-readonly');
  } catch (e) {
    report.finalAuditError = clean(e.stack);
    report.status = 'failed';
    process.exitCode = 1;
  }
  if (fixture)
    try {
      const r = await execute(
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
          'files/acceptance/P4-reply-tls-read-audit/' + nonce,
        ],
        { encoding: 'buffer', timeout: 10000, maxBuffer: 64 * 1024 * 1024 },
      );
      await writeFile(join(root, 'native-evidence.tar'), r.stdout, { mode: 0o600 });
    } catch (e) {
      report.artifactError = clean(e.message);
    }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const p of reverses) await android('reverse', '--remove', 'tcp:' + p).catch(() => {});
  for (const e of active) {
    e.reason = 'owned-close';
    e.controller.abort();
  }
  for (const s of [proxy, control])
    if (s) {
      s.closeAllConnections();
      await new Promise((r) => s.close(r));
    }
  await witness.close();
  if (service) {
    const s = service;
    try {
      await new Promise((r, j) => {
        if (s.child.exitCode !== null) return r();
        const t = setTimeout(() => j(Error('Owned Host close timeout')), 15000);
        s.child.once('exit', () => {
          clearTimeout(t);
          r();
        });
        s.child.kill('SIGTERM');
      });
      assert.equal(s.child.exitCode, 0);
      report.hostExit = { pid: s.child.pid, code: s.child.exitCode };
      await writeFile(join(root, 'host.log'), s.logs.join('\n'), { mode: 0o600 });
    } catch (e) {
      report.cleanupError = clean(e.message);
      report.status = 'failed';
      process.exitCode = 1;
    }
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      diagnosticOnly,
      newModelInputs: 0,
      error: report.error,
    }),
  );
}
