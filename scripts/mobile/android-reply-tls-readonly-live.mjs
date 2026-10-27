import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID, X509Certificate } from 'node:crypto';
import { createServer as httpsServer } from 'node:https';
import { createServer as httpServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath, chmod } from 'node:fs/promises';
import { join, resolve, isAbsolute } from 'node:path';
import { DesktopRuntime } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// Owned TLS edge exposes only descriptor/workspace/models. It never forwards a remote write.
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL,
  pkg = 'com.easyai.agenthub.android.acceptance';
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const output = process.env.MOBILE_PAIRED_TLS_OUTPUT,
  projectId = process.env.ANDROID_PAIRED_TLS_PROJECT_ID;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_PAIRED_TLS_PROJECT_ROOT ?? '');
const root = join(output, 'paired-tls-readonly-' + Date.now()),
  state = join(root, 'host');
await mkdir(state, { recursive: true, mode: 0o700 });
const execute = promisify(execFile),
  sha = (x) => createHash('sha256').update(x).digest('hex'),
  read = async (p) => JSON.parse(await readFile(p, 'utf8'));
const toolchain = await read('.local/mobile-toolchain.json'),
  adb = resolve(toolchain.androidSdk, 'platform-tools/adb');
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
assert(Number(await android('shell', 'getprop', 'ro.build.version.sdk')) >= 29);
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
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
      'scripts/mobile/android-reply-tls-readonly-live.mjs',
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
  phase: 'P4-4h.1',
  status: 'running',
  scope:
    'Fresh isolated production Host and actual original desktop runtime version with an examiner-owned TLS edge. Physical native wrong-pin handshake rejection, production QR parser/pinned reads and unsent local draft/settings/file/consent only. Public descriptor is supplied by the owned examiner from actual Host target. No camera scan, production gateway provisioning, private device handshake, model/reply retry/negative-proof result or LAN/cellular/VPN proof.',
  serial,
  hostId,
  nonce,
  projectId,
  projectRoot,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  requests: [],
  posts: [],
  rejectedReads: [],
  tlsClientErrors: [],
  tlsConnections: [],
  ownedPorts: [],
  native: [],
  newRealModelInputsConfirmed: 0,
};
let service, proxy, control, token, endpoint, fixture, target, proxyPort, certFingerprint;
const active = new Set(),
  reverses = [];
const clean = (x) =>
  String(x)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const json = (res, v, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(v));
};
const listen = async (s) => {
  await new Promise((r, j) => {
    s.once('error', j);
    s.listen(0, '127.0.0.1', r);
  });
  return s.address().port;
};
const machineTarget = (v) =>
  Object.fromEntries(['agentId', 'hostId', 'workspaceId', 'runtimeId'].map((k) => [k, v[k]]));
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android paired TLS zero-input independent runtime witness',
);
assert(process.env.CODEX_EXECUTABLE);
async function startHost() {
  const env = {
    ...process.env,
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android paired TLS readonly DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  };
  delete env.HOST_AGENT_TOKEN;
  delete env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete env.HOST_AGENT_REPLY_CHECKPOINT_FILE;
  const child = spawn(process.execPath, ['apps/host-agent/dist/runtime-entry.js'], {
    cwd: process.cwd(),
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  service = { child, logs: [] };
  const lines = createInterface({ input: child.stdout });
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Own Host readiness deadline')), 30000);
    child.once('error', reject);
    child.once('exit', (c, s) => {
      clearTimeout(timer);
      reject(Error('Own Host early exit ' + c + '/' + s));
    });
    child.stderr.on('data', (b) => service.logs.push(clean(b.toString())));
    lines.on('line', (line) => {
      service.logs.push(clean(line));
      try {
        const v = JSON.parse(line);
        if (v.service === 'host-agent' && v.ready === true && v.mode === 'desktop-runtime') {
          clearTimeout(timer);
          done(v);
        }
      } catch {
        /* Readiness requires fixed JSON. */
      }
    });
  });
  assert.equal(ready.address.address, '127.0.0.1');
  assert.equal(ready.tokenFile, join(state, 'access-token'));
  token = (await readFile(ready.tokenFile, 'utf8')).trim();
  assert(token.length >= 32);
  endpoint = 'http://127.0.0.1:' + ready.address.port;
  report.ownedPorts.push({ label: 'host', port: ready.address.port, pid: child.pid });
}
async function hostRead(path) {
  assert(['/v1/workspace', '/v1/models'].includes(path));
  const r = await fetch(endpoint + path + '?hostId=' + encodeURIComponent(hostId), {
    headers: { Authorization: 'Bearer ' + token },
    signal: AbortSignal.timeout(30000),
    redirect: 'error',
  });
  assert.equal(r.status, 200);
  return r.json();
}
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  await writeFile(
    join(state, 'bridge.json'),
    JSON.stringify({ hostId, desktopVersion: witness.version }),
    { mode: 0o600 },
  );
  await writeFile(
    join(state, 'runtime.json'),
    JSON.stringify({
      hostId,
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
    }),
    { mode: 0o600 },
  );
  await startHost();
  const workspace = await hostRead('/v1/workspace');
  target = workspace.readTarget;
  assert.equal(workspace.hostId, hostId);
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  assert.equal(target.hostId, hostId);
  assert(
    workspace.capabilities.includes('desktop:creations') &&
      workspace.capabilities.includes('commands:target-bound:v1'),
  );
  const project = workspace.projects.find((p) => p.id === projectId);
  assert(project, 'Exact human-added project must exist; no project creation');
  assert((await Promise.all(project.roots.map((p) => realpath(p)))).includes(projectRoot));
  const model = (await hostRead('/v1/models')).models.find((m) => m.isDefault);
  assert(
    model && model.supportedEfforts.includes('xhigh') && model.supportedSpeeds.includes('fast'),
  );
  report.model = model.id;
  report.target = target;
  const keyPath = join(root, 'key.pem'),
    certPath = join(root, 'certificate.pem');
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
      '-keyout',
      keyPath,
      '-out',
      certPath,
    ],
    { stdio: 'ignore' },
  );
  await chmod(keyPath, 0o600);
  await chmod(certPath, 0o600);
  const cert = await readFile(certPath);
  certFingerprint = sha(new X509Certificate(cert).raw);
  report.certificateSha256 = certFingerprint;
  const descriptor = (fingerprint) => ({
    version: 1,
    agentId: 'codex',
    name: 'Android paired TLS readonly DEV',
    target,
    fingerprint,
    endpoints: ['https://127.0.0.1:' + proxyPort],
    endpointHints: [{ endpoint: 'https://127.0.0.1:' + proxyPort, networkKind: 'lan' }],
  });
  proxy = httpsServer({ key: await readFile(keyPath), cert }, (req, res) => {
    const entry = { controller: new AbortController() };
    active.add(entry);
    void (async () => {
      assert(!req.headers.origin);
      const url = new URL(req.url, 'https://127.0.0.1');
      if (req.method !== 'GET') {
        report.posts.push({ method: req.method, path: url.pathname });
        return json(res, { error: 'All remote writes forbidden in zero-input TLS preflight' }, 403);
      }
      if (url.pathname === '/host') {
        assert(!req.headers.authorization);
        report.requests.push({ path: '/host', method: 'GET', publicDescriptor: true });
        return json(res, descriptor(certFingerprint));
      }
      assert(
        ['/v1/workspace', '/v1/models'].includes(url.pathname),
        'Unexpected read route ' + url.pathname,
      );
      assert.equal(req.headers.authorization, 'Bearer ' + token);
      assert([null, '', hostId].includes(url.searchParams.get('hostId')));
      if (url.searchParams.has('target'))
        assert.deepEqual(
          machineTarget(JSON.parse(url.searchParams.get('target'))),
          machineTarget(target),
        );
      const upstream = new URL(url.pathname + url.search, endpoint),
        response = await fetch(upstream, {
          headers: { Authorization: 'Bearer ' + token },
          signal: entry.controller.signal,
          redirect: 'error',
        });
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(response.status, 200);
      const value = JSON.parse(bytes);
      if (url.pathname === '/v1/workspace') assert.equal(value.hostId, hostId);
      else assert(Array.isArray(value.models) && value.models.some((m) => m.id === model.id));
      report.requests.push({ path: url.pathname, method: 'GET', forwarded: true });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(bytes);
    })()
      .catch((e) => {
        if (!entry.controller.signal.aborted) {
          report.rejectedReads.push(clean(e.message));
          if (!res.headersSent)
            json(res, { error: 'Owned readonly TLS preflight rejected request' }, 500);
          else res.destroy();
        }
      })
      .finally(() => active.delete(entry));
  });
  proxy.on('tlsClientError', (e) =>
    report.tlsClientErrors.push({ code: e.code ?? null, name: e.name }),
  );
  proxy.on('secureConnection', (s) => report.tlsConnections.push({ protocol: s.getProtocol() }));
  proxyPort = await listen(proxy);
  report.ownedPorts.push({ label: 'tls-edge', port: proxyPort, pid: process.pid });
  control = httpServer((req, res) => {
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
      if (route === '/certificate-rejected') {
        assert(v.noClientPublished && v.noCredentialSaved);
        assert(v.failureClasses.some((c) => c.includes('SSL') || c.includes('Certificate')));
        assert.equal(report.requests.length, 0);
        report.wrongPinRejected = v;
      } else if (route === '/preflight-complete') {
        assert(report.wrongPinRejected);
        assert.equal(v.hostId, hostId);
        assert.equal(v.fingerprint, certFingerprint);
        assert.deepEqual(machineTarget(v.pairedTarget), machineTarget(target));
        assert.equal(v.localText, fixture.independentDraft);
        assert.equal(v.localFile.sha256, sha(fixture.localFileText));
        assert.equal(v.model, model.id);
        assert.equal(v.effort, 'xhigh');
        assert.equal(v.speed, 'fast');
        assert.equal(v.permission, 'read-only');
        assert.equal(v.newModelInputs, 0);
        assert.equal(report.posts.length, 0);
        report.nativeCompletion = v;
      } else throw Error('Unsupported native control route');
      await save();
      json(res, { ok: true });
    })().catch((e) => {
      report.controlFailure = clean(e.stack);
      json(res, { error: 'Owned TLS control witness rejected result' }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts.push({ label: 'control', port: controlPort, pid: process.pid });
  const pairing = (fingerprint) =>
    'codex-host://pair?descriptor=' +
    Buffer.from(JSON.stringify({ ...descriptor(fingerprint), token })).toString('base64url');
  fixture = {
    nonce,
    evidenceRun: randomUUID(),
    hostId,
    target,
    fingerprint: certFingerprint,
    pairing: pairing(certFingerprint),
    badPairing: pairing('0'.repeat(64)),
    projectId,
    model: model.id,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    independentDraft: 'Android paired TLS unsent local draft ' + nonce,
    localFileName: 'tls-preflight-' + nonce + '.txt',
    localFileText: 'Android paired TLS local bytes ' + nonce + '\n',
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
      `run-as ${pkg} sh -c 'umask 077; cat > files/mobile-paired-tls-config.json'`,
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
        'com.agenthub.android.MobilePairedTlsReadLiveTest#realPinnedPairingRejectsWrongCertificateAndPreparesOnlyLocalDraft',
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
      !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED/.test(log),
  );
  report.native.push({ status: 'passed', logSha256: sha(log) });
  assert(report.nativeCompletion && report.wrongPinRejected);
  assert.equal(report.posts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  assert(!report.controlFailure);
  assert(report.tlsConnections.length > 0);
  assert(report.tlsClientErrors.length > 0);
  assert.equal(await fingerprint(), report.sourceSha256);
  const runtime = await read(join(state, 'runtime.json'));
  assert.equal(runtime.hostId, hostId);
  assert.equal(runtime.receipts.length, 0);
  const broker = await read(join(state, 'desktop-creations', 'creations.json')).catch((e) => {
    assert.equal(e.code, 'ENOENT');
    return {};
  });
  assert.equal(Object.keys(broker).length, 0);
  report.ownedHostReceiptCount = 0;
  report.ownedCreationCount = 0;
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (fixture)
    try {
      const bytes = (
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
            'files/acceptance/P4-paired-tls/' + nonce + '/' + fixture.evidenceRun,
          ],
          { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
        )
      ).stdout;
      await writeFile(join(root, 'native-evidence.tar'), bytes, { mode: 0o600 });
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
    if (child.exitCode === null && child.signalCode === null)
      await new Promise((done, reject) => {
        const t = setTimeout(() => reject(Error('Own Host close deadline')), 15000);
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
    report.hostProcessExit = { pid: child.pid, code: child.exitCode, signal: child.signalCode };
    await writeFile(join(root, 'host-process.log'), service.logs.join('\n'), { mode: 0o600 });
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      newModelInputs: 0,
      nativeMethods: report.native.length,
      error: report.error,
    }),
  );
}
