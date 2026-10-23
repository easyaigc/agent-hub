import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile, copyFile, readdir, rename } from 'node:fs/promises';
import { createServer } from 'node:http';
import { homedir } from 'node:os';
import { resolve, join } from 'node:path';
import { CodexRuntime } from '../../packages/codex-adapter/dist/runtime.js';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Fresh official project metadata, zero-Turn preparation and the original desktop remain separate.
const serial = process.env.ANDROID_SERIAL;
assert(/^emulator-\d+$/.test(serial ?? ''), 'Dedicated emulator only; never a paused phone');
assert.equal(process.platform, 'darwin', 'This actual desktop examiner is macOS-only');
let threadId;
const priorIds = new Set();
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb');
const android = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' }).trim();
const root = resolve(
    '.local/mobile-acceptance/A4/history-identity-live-' + serial + '-' + Date.now(),
  ),
  nonce = randomUUID().slice(0, 8);
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (b) => createHash('sha256').update(b).digest('hex');
const files = execFileSync(
  'git',
  [
    'ls-files',
    '-co',
    '--exclude-standard',
    'apps/android',
    'apps/host-agent',
    'packages/mobile-core',
    'packages/protocol',
    'packages/codex-adapter',
  ],
  { encoding: 'utf8' },
)
  .trim()
  .split('\n')
  .filter(Boolean)
  .sort();
const fingerprint = async () => {
  const h = createHash('sha256');
  for (const f of new Set(files))
    h.update(f + '\0')
      .update(await readFile(f))
      .update('\0');
  return h.digest('hex');
};
const report = {
  phase: 'A4',
  status: 'running',
  scope:
    'Actual provisional history to original-owner identity adoption, retained unknown operation, local detach and explicit successful recovery; not a failed-Turn proof',
  serial,
  api: Number(android('shell', 'getprop', 'ro.build.version.sdk')),
  sourceSha256: await fingerprint(),
  toolSha256: sha(await readFile('scripts/mobile/android-history-identity-live.mjs')),
  adapterSha256: preparedAdapterDigest(),
  freshModelExecution: false,
  commands: [],
};
const save = () =>
  writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let host, gate, port, displacedDirectory, originalDirectory, directoryRestoreTimer;
const restoreDirectory = async () => {
  if (displacedDirectory) {
    const displaced = displacedDirectory;
    displacedDirectory = null;
    try {
      await rename(displaced, originalDirectory);
      report.directoryRestored = true;
    } catch (error) {
      displacedDirectory = displaced;
      throw error;
    }
  }
};
const inputs = [];
try {
  await ipc.connect();
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE:
      process.env.CODEX_EXECUTABLE ??
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
    HOST_AGENT_STATE_DIR: root + '/host',
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  for (const k of [
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[k];
  await mkdir(root + '/host', { recursive: true, mode: 0o700 });
  const localHostId = randomUUID();
  await writeFile(
    root + '/host/runtime.json',
    JSON.stringify({
      hostId: localHostId,
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
    }),
    { mode: 0o600 },
  );
  await writeFile(
    root + '/host/bridge.json',
    JSON.stringify({
      hostId: localHostId,
      desktopVersion: execFileSync(
        '/usr/libexec/PlistBuddy',
        [
          '-c',
          'Print :CFBundleShortVersionString',
          '/Applications/ChatGPT.app/Contents/Info.plist',
        ],
        { encoding: 'utf8' },
      ).trim(),
    }),
    { mode: 0o600 },
  );
  process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG = root + '/host/bridge.json';
  host = await startRuntimeServer();
  const endpoint = 'http://127.0.0.1:' + host.server.address().port,
    token = (await readFile(root + '/host/access-token', 'utf8')).trim();
  await mkdir(root + '/project', { mode: 0o700 });
  const metadata = new CodexRuntime({
    mode: 'managed-runtime',
    executable: process.env.CODEX_EXECUTABLE,
  });
  let projectId;
  try {
    await metadata.connect();
    const v = await metadata.call('project/create', {
      name: 'Android history identity DEV ' + nonce,
      roots: [{ path: root + '/project' }],
      idempotencyKey: randomUUID(),
    });
    projectId = v.project.id;
  } finally {
    const child = metadata.child;
    const exited = child ? new Promise((r) => child.once('close', r)) : Promise.resolve();
    metadata.close();
    await Promise.race([
      exited,
      new Promise((_, reject) =>
        setTimeout(() => reject(Error('Metadata preparer exit required')), 5000),
      ),
    ]);
    report.metadataPreparerExited = true;
  }
  const workspace = await host.session.workspace();
  const project = workspace.projects.find((p) => p.id === projectId);
  assert(
    project && project.roots.includes(root + '/project'),
    'Exact fresh isolated project required',
  );
  gate = createServer(async (req, res) => {
    const url = new URL(req.url, endpoint);
    if (req.headers.authorization !== 'Bearer ' + token) {
      res.writeHead(401).end();
      return;
    }
    if (url.pathname === '/acceptance/history-identity-status' && req.method === 'GET') {
      res
        .writeHead(200, { 'content-type': 'application/json' })
        .end(JSON.stringify({ directoryRestored: report.directoryRestored === true }));
      return;
    }
    const allowed =
      req.method === 'GET'
        ? [
            '/v1/workspace',
            '/v1/history',
            '/v1/events',
            '/v1/models',
            '/v1/receipts',
            '/v1/desktop/creations',
            '/v1/desktop/creation-receipts',
          ].includes(url.pathname)
        : req.method === 'POST' &&
          [
            '/v1/attachments',
            '/v1/replies',
            '/v1/desktop/creations',
            '/v1/desktop/creations/submit',
          ].includes(url.pathname);
    if (!allowed) {
      res.writeHead(403).end();
      return;
    }
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);
    if (req.method === 'POST') {
      report.commands.push({ path: url.pathname, bodySha256: sha(body) });
      await save();
      if (url.pathname === '/v1/replies') {
        const input = JSON.parse(body);
        assert.equal(input.threadId, threadId);
        assert(input.text.includes(nonce));
        assert.equal(input.mode, 'next-turn');
        inputs.push(input);
      } else if (url.pathname === '/v1/desktop/creations') {
        const input = JSON.parse(body);
        assert.equal(input.projectId, projectId);
        assert(input.text.includes('BASELINE_' + nonce));
      } else if (url.pathname !== '/v1/desktop/creations/submit')
        assert.equal(url.searchParams.get('threadId'), threadId);
    }
    try {
      const headers = { authorization: 'Bearer ' + token };
      for (const key of ['content-type', 'x-content-sha256'])
        if (req.headers[key]) headers[key] = req.headers[key];
      if (url.pathname === '/v1/replies' && inputs.length === 1) {
        const before = await ipc.follow(threadId, 15000, true, true);
        assert(ipc.hasOwner(threadId));
        assert.equal(desktopTurns(before).at(-1).status, 'completed');
        assert.equal(before.requests?.length ?? 0, 0);
        assert(
          desktopTurns(before)
            .at(-1)
            .items.some((i) => i.type === 'agentMessage' && i.text.trim() === 'BASELINE_' + nonce),
        );
        for (const t of desktopTurns(before)) priorIds.add(t.turnId);
        originalDirectory = before.cwd;
        assert(project.roots.includes(originalDirectory));
        assert.equal(originalDirectory, root + '/project');
        report.ownerVerifiedBefore = true;
        report.freshModelExecution = true;
        displacedDirectory = originalDirectory + '.temporarily-unavailable';
        await rename(originalDirectory, displacedDirectory);
        report.directoryDisplaced = true;
        directoryRestoreTimer = setTimeout(() => {
          void restoreDirectory().catch((error) => {
            report.directoryProbeError = error.message;
          });
        }, 30000);
        void (async () => {
          try {
            const deadline = Date.now() + 30000;
            while (Date.now() < deadline && displacedDirectory) {
              const s = await ipc.follow(threadId, 5000, true, true);
              const last = desktopTurns(s).at(-1);
              if (
                last &&
                !priorIds.has(last.turnId) &&
                ['completed', 'failed', 'interrupted'].includes(last.status)
              ) {
                report.actualProbeTerminal = last.status;
                await restoreDirectory();
                return;
              }
              await new Promise((r) => setTimeout(r, 500));
            }
          } finally {
            await restoreDirectory();
          }
        })().catch(async (e) => {
          report.directoryProbeError = e.message;
          await restoreDirectory();
        });
      }
      const u = await fetch(url, {
        method: req.method,
        headers,
        ...(req.method === 'POST' ? { body } : {}),
        redirect: 'error',
        signal: AbortSignal.timeout(60000),
      });
      const raw = Buffer.from(await u.arrayBuffer());
      if (url.pathname.startsWith('/v1/desktop/creation') && u.ok) {
        const receipt = JSON.parse(raw);
        if (receipt.threadId) {
          threadId = receipt.threadId;
          report.ownedFreshThread = true;
        }
      }
      await save();
      res.writeHead(u.status, { 'content-type': 'application/json' });
      res.end(raw);
    } catch (e) {
      report.gateError = e.message;
      await save();
      if (!res.headersSent) res.writeHead(502).end();
      else res.end();
    }
  });
  await new Promise((done) => gate.listen(0, '127.0.0.1', done));
  port = gate.address().port;
  android('reverse', 'tcp:' + port, 'tcp:' + port);
  const p =
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/history-identity-config.json';
  await writeFile(
    root + '/native-config.json',
    JSON.stringify({ endpoint: 'http://127.0.0.1:' + port, token, projectId, nonce }),
    { mode: 0o600 },
  );
  const app = '.build/native/android/outputs/apk/debug/android-debug.apk',
    test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
  report.apkSha256 = sha(await readFile(app));
  report.testApkSha256 = sha(await readFile(test));
  await mkdir(root + '/artifacts');
  await copyFile(
    'scripts/mobile/android-history-identity-live.mjs',
    root + '/artifacts/android-history-identity-live.mjs',
  );
  await copyFile(
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveHistoryIdentityTest.kt',
    root + '/artifacts/MobileLiveHistoryIdentityTest.kt',
  );
  await copyFile(app, root + '/artifacts/android-debug.apk');
  await copyFile(test, root + '/artifacts/android-debug-androidTest.apk');
  android('install', '-r', app);
  android('install', '-r', test);
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-history-identity',
  );
  android('push', root + '/native-config.json', p);
  await save();
  let log;
  try {
    ({ stdout: log } = await promisify(execFile)(
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
        'com.agenthub.android.MobileLiveHistoryIdentityTest',
        '-e',
        'identityConfig',
        p,
        'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
      ],
      { encoding: 'utf8', timeout: 300000, maxBuffer: 2 * 1024 * 1024 },
    ));
  } catch (error) {
    await writeFile(
      root + '/instrumentation.log',
      String(error.stdout ?? '') + '\n' + String(error.stderr ?? ''),
      { mode: 0o600 },
    );
    report.instrumentationTimedOut = error.killed === true;
    throw error;
  }
  await writeFile(root + '/instrumentation.log', log, { mode: 0o600 });
  await mkdir(root + '/screenshots');
  android(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-history-identity/.',
    root + '/screenshots',
  );
  assert(
    log.includes('OK (1 test)') && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
  );
  const proof = JSON.parse(await readFile(root + '/screenshots/native-proof.json', 'utf8'));
  const after = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId));
  const turns = desktopTurns(after);
  assert.equal(
    turns.length,
    2,
    'Only baseline and explicit recovery execute; the unknown fault input is never replayed',
  );
  assert(turns.every((t) => t.status === 'completed'));
  assert.equal(inputs.length, 2);
  const text = (i) => (i.content ?? []).map((c) => c.text ?? '').join('\n');
  const users = turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage');
  assert.equal(users.length, 2);
  assert.equal(users.filter((i) => text(i).includes(proof.baselineMarker)).length, 1);
  assert.equal(users.filter((i) => i.clientId === proof.unknownOperation).length, 0);
  assert.equal(users.filter((i) => i.clientId === inputs[1].clientMessageId).length, 1);
  assert(
    turns[1].items.some(
      (i) => i.type === 'agentMessage' && i.text.trim() === proof.recoveredMarker,
    ),
  );
  report.ownerVerifiedAfter = true;
  report.freshModelExecution = true;
  report.nativeBaselineUsers = proof.nativeBaselineUsers;
  report.nativeBaselineBodies = proof.nativeBaselineBodies;
  report.restoredUnknown = proof.restoredUnknown;
  report.actualRecoveryCompleted = true;
  report.actualFailedTurn = false;
  report.originalUserInputs = users.length;
  report.nativeReplyCount = 2;
  report.noAutomaticReplay = true;
  assert(report.directoryRestored);
  assert.equal(await fingerprint(), report.sourceSha256);
  report.completedTests = 1;
  report.failures = 0;
  report.skips = 0;
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = e.message;
  process.exitCode = 1;
} finally {
  try {
    await mkdir(root + '/screenshots', { recursive: true });
    android(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-history-identity/.',
      root + '/screenshots',
    );
    report.screenshots = await Promise.all(
      (await readdir(root + '/screenshots'))
        .filter((n) => n.endsWith('.png'))
        .map(async (name) => ({
          name,
          sha256: sha(await readFile(root + '/screenshots/' + name)),
          reviewed: false,
        })),
    );
  } catch (e) {
    report.captureError = e.message;
  }
  if (report.status === 'passed' && report.screenshots?.length !== 2) {
    report.status = 'failed';
    report.error = 'Both actual history and explicit-recovery captures are required';
    process.exitCode = 1;
  }
  await restoreDirectory();
  clearTimeout(directoryRestoreTimer);
  ipc.close();
  if (gate) await new Promise((done) => gate.close(done));
  if (port) android('reverse', '--remove', 'tcp:' + port);
  if (host) await host.close();
  report.completedAt = new Date().toISOString();
  await save();
  console.log('Actual native history identity:', report.status, 'Evidence:', root);
}
