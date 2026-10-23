import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { homedir } from 'node:os';
import { resolve, join } from 'node:path';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// This read gate never opens a desktop page or submits input. Only this task's exact owner is used.
const serial = process.env.ANDROID_SERIAL,
  threadId = process.env.ANDROID_HISTORY_THREAD;
assert(
  /^emulator-\d+$/.test(serial ?? '') && threadId,
  'Select a dedicated emulator and an authorized exact task',
);
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb'),
  root = resolve('.local/mobile-acceptance/A4/history-live-' + serial + '-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const android = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' }).trim();
const sha = (b) => createHash('sha256').update(b).digest('hex');
const files = execFileSync(
  'git',
  [
    'ls-files',
    '-co',
    '--exclude-standard',
    'apps/android',
    'packages/mobile-core',
    'packages/protocol',
    'apps/host-agent',
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
    'Actual current-task context compaction history via an isolated Host and GET-only native gate; no new model execution or failed-task receipt proof',
  serial,
  api: Number(android('shell', 'getprop', 'ro.build.version.sdk')),
  fontScale: Number(android('shell', 'settings', 'get', 'system', 'font_scale')),
  sourceSha256: await fingerprint(),
  toolSha256: sha(await readFile('scripts/mobile/android-history-live.mjs')),
  adapterSha256: preparedAdapterDigest(),
  freshModelExecution: false,
  remoteWriteRequests: 0,
  requests: [],
};
const save = () =>
  writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let host, gate, port;
try {
  await ipc.connect();
  const before = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId));
  const turns = desktopTurns(before),
    turn = turns.at(-1),
    compaction = turns
      .flatMap((t) => t.items ?? [])
      .findLast((i) => i.type === 'contextCompaction' && i.completed === true);
  assert(compaction, 'An actual completed original-owner compaction is required');
  report.originalOwnerBefore = true;
  report.originalTurnId = turn.turnId;
  report.compactionIdSha256 = sha(compaction.id);
  report.originalCompactionCompleted = true;
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
  host = await startRuntimeServer();
  const endpoint = 'http://127.0.0.1:' + host.server.address().port,
    token = (await readFile(root + '/host/access-token', 'utf8')).trim();
  const actual = await host.session.open(threadId);
  assert(
    actual.messages.some(
      (m) => m.id === compaction.id && m.kind === 'contextCompaction' && m.status === 'completed',
    ),
  );
  report.actualHistoryMessages = actual.messages.length;
  gate = createServer(async (req, res) => {
    const url = new URL(req.url, endpoint);
    report.requests.push({ method: req.method, path: url.pathname });
    if (req.headers.authorization !== 'Bearer ' + token) {
      res.writeHead(401).end();
      return;
    }
    if (req.method !== 'GET') {
      report.remoteWriteRequests++;
      res.writeHead(405).end();
      return;
    }
    if (
      !['/v1/workspace', '/v1/history', '/v1/events', '/v1/models', '/v1/receipts'].includes(
        url.pathname,
      ) ||
      (['history', 'events'].includes(url.pathname.slice(4)) &&
        url.searchParams.get('threadId') !== threadId)
    ) {
      res.writeHead(403).end();
      return;
    }
    try {
      const upstream = await fetch(url, {
        headers: { authorization: 'Bearer ' + token },
        redirect: 'error',
        signal: AbortSignal.timeout(60000),
      });
      res.writeHead(upstream.status, { 'content-type': 'application/json' });
      res.end(Buffer.from(await upstream.arrayBuffer()));
    } catch {
      res.writeHead(502).end();
    }
  });
  await new Promise((done) => gate.listen(0, '127.0.0.1', done));
  port = gate.address().port;
  android('reverse', 'tcp:' + port, 'tcp:' + port);
  const p = '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/history-config.json';
  await writeFile(
    root + '/native-config.json',
    JSON.stringify({
      endpoint: 'http://127.0.0.1:' + port,
      token,
      threadId,
      compactionId: compaction.id,
      turnId: turn.turnId,
      minimumMessages: actual.messages.length,
    }),
    { mode: 0o600 },
  );
  const app = '.build/native/android/outputs/apk/debug/android-debug.apk',
    test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
  report.apkSha256 = sha(await readFile(app));
  report.testApkSha256 = sha(await readFile(test));
  await mkdir(root + '/artifacts');
  await copyFile(app, root + '/artifacts/android-debug.apk');
  await copyFile(test, root + '/artifacts/android-debug-androidTest.apk');
  android('install', '-r', app);
  android('install', '-r', test);
  android('shell', 'pm', 'clear', 'com.easyai.agenthub.android.debug');
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-history',
  );
  android('push', root + '/native-config.json', p);
  await save();
  const { stdout: log } = await promisify(execFile)(
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
      'com.agenthub.android.MobileLiveHistoryTest',
      '-e',
      'historyConfig',
      p,
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 300000, maxBuffer: 2 * 1024 * 1024 },
  );
  await writeFile(root + '/instrumentation.log', log, { mode: 0o600 });
  assert(
    log.includes('OK (1 test)') && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
  );
  await mkdir(root + '/screenshots', { recursive: true });
  android(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-history/.',
    root + '/screenshots',
  );
  report.nativeCache = JSON.parse(await readFile(root + '/screenshots/native-proof.json', 'utf8'));
  assert(report.nativeCache.fullSnapshotPersisted);
  assert.equal(report.remoteWriteRequests, 0);
  assert(report.requests.some((r) => r.path === '/v1/history'));
  const after = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId));
  assert.equal(desktopTurns(after).at(-1).turnId, turn.turnId);
  assert(
    desktopTurns(after)
      .flatMap((t) => t.items ?? [])
      .some((i) => i.id === compaction.id && i.completed === true),
  );
  report.originalOwnerAfter = true;
  report.sameOriginalTurn = true;
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
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-history/.',
      root + '/screenshots',
    );
    const { readdir } = await import('node:fs/promises');
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
  if (report.status === 'passed' && report.screenshots?.length !== 1) {
    report.status = 'failed';
    report.error = 'The actual compaction capture is required';
    process.exitCode = 1;
  }
  ipc.close();
  if (gate) await new Promise((done) => gate.close(done));
  if (port) android('reverse', '--remove', 'tcp:' + port);
  if (host) await host.close();
  report.completedAt = new Date().toISOString();
  await save();
  console.log('Actual native compaction:', report.status, 'Evidence:', root);
}
