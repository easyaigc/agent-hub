import assert from 'node:assert/strict';
import { execFileSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir, copyFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { homedir } from 'node:os';
import { createServer } from 'node:http';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
assert.equal(process.platform, 'darwin');
const threadId = process.env.ANDROID_PHYSICAL_THREAD;
assert(/^[a-f0-9-]{36}$/.test(threadId ?? ''));
assert(process.env.ANDROID_PHYSICAL_PROJECT_ROOT);
const projectRoot = resolve(process.env.ANDROID_PHYSICAL_PROJECT_ROOT);
assert(
  projectRoot.startsWith(resolve('.local/mobile-acceptance/A4') + '/'),
  'Only a dedicated DEV project may be used',
);
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')),
  adb = resolve(local.androidSdk, 'platform-tools/adb');
const android = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 60000 }).trim();
assert.equal(android('get-state'), 'device');
const root = resolve('.local/mobile-acceptance/A4/physical-model-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (b) => createHash('sha256').update(b).digest('hex');
const nonce = randomUUID().slice(0, 8);
const inputs = [];
const report = {
  stage: 'A4',
  status: 'running',
  scope:
    'Actual Xiaomi native reply to a dedicated original-desktop-owned chat, matching completed receipt, durable draft and independent original-owner proof; no failed-Turn claim',
  api: Number(android('shell', 'getprop', 'ro.build.version.sdk')),
  nonce,
  adapterSha256: preparedAdapterDigest(),
  allowsNextStage: false,
  actualFailedTurn: false,
  commands: [],
};
const save = () =>
  writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let host, gate, port;
let tearingDown = false;
try {
  await ipc.connect();
  const before = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId));
  assert.equal(before.cwd, projectRoot);
  const priorTurns = desktopTurns(before).length;
  assert(priorTurns > 0);
  assert.equal(desktopTurns(before).at(-1).status, 'completed');
  assert.equal(before.requests?.length ?? 0, 0);
  report.originalOwnerBefore = true;
  report.priorTurns = priorTurns;
  const id = randomUUID();
  await mkdir(root + '/host', { mode: 0o700 });
  await writeFile(
    root + '/host/runtime.json',
    JSON.stringify({
      hostId: id,
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
      hostId: id,
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
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: root + '/host/bridge.json',
  });
  for (const k of [
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[k];
  host = await startRuntimeServer();
  const endpoint = 'http://127.0.0.1:' + host.server.address().port,
    token = (await readFile(root + '/host/access-token', 'utf8')).trim();
  const workspace = await host.session.workspace();
  assert(workspace.threads.some((v) => v.id === threadId));
  gate = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, endpoint);
      if (req.headers.authorization !== 'Bearer ' + token) {
        res.writeHead(401).end();
        return;
      }
      const bytes = [];
      for await (const c of req) bytes.push(c);
      const body = Buffer.concat(bytes);
      if (req.method === 'POST') {
        assert.equal(url.pathname, '/v1/replies');
        const input = JSON.parse(body);
        assert.equal(input.threadId, threadId);
        assert(input.text.includes('PHONE_READY_' + nonce));
        assert.equal(input.mode, 'next-turn');
        inputs.push(input);
        assert.equal(inputs.length, 1, 'Native input must not be replayed');
        report.commands.push({ path: url.pathname, sha256: sha(body) });
        await save();
      } else {
        assert.equal(req.method, 'GET');
        assert(
          [
            '/v1/workspace',
            '/v1/models',
            '/v1/history',
            '/v1/events',
            '/v1/receipts',
            '/v1/desktop/creations',
            '/v1/desktop/creation-receipts',
          ].includes(url.pathname),
        );
        if (url.searchParams.get('threadId'))
          assert.equal(url.searchParams.get('threadId'), threadId);
      }
      const response = await fetch(url, {
        method: req.method,
        headers: {
          authorization: 'Bearer ' + token,
          ...(req.headers['content-type'] ? { 'content-type': req.headers['content-type'] } : {}),
        },
        ...(req.method === 'POST' ? { body } : {}),
        signal: AbortSignal.timeout(60000),
      });
      const output = Buffer.from(await response.arrayBuffer());
      res.writeHead(response.status, { 'content-type': 'application/json' });
      res.end(output);
    } catch (e) {
      if (tearingDown) report.teardownTransportNote = e.message;
      else report.gateError = e.message;
      await save();
      if (!res.headersSent) res.writeHead(502).end();
      else res.end();
    }
  });
  await new Promise((r) => gate.listen(0, '127.0.0.1', r));
  port = gate.address().port;
  android('reverse', 'tcp:' + port, 'tcp:' + port);
  report.installations = [];
  for (const [packageName, apk] of [
    [
      'com.easyai.agenthub.android.debug',
      '.build/native/android/outputs/apk/debug/android-debug.apk',
    ],
    [
      'com.easyai.agenthub.android.debug.test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
    ],
  ])
    report.installations.push(
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true }),
    );
  report.suiteSha256 = sha(
    await readFile(
      'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhysicalSmokeTest.kt',
    ),
  );
  report.toolSha256 = sha(await readFile('scripts/mobile/android-physical-model-live.mjs'));
  await copyFile(
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhysicalSmokeTest.kt',
    root + '/MobilePhysicalSmokeTest.kt',
  );
  await writeFile(
    root + '/native-config.json',
    JSON.stringify({ endpoint: 'http://127.0.0.1:' + port, token, threadId, nonce }),
    { mode: 0o600 },
  );
  const p =
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/physical-model-config.json';
  android('push', root + '/native-config.json', p);
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-physical-model',
  );
  await save();
  const result = await promisify(execFile)(
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
      'com.agenthub.android.MobilePhysicalSmokeTest#actualNativeModelTask',
      '-e',
      'physicalAcceptance',
      'true',
      '-e',
      'physicalModelConfig',
      p,
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 180000, maxBuffer: 2 * 1024 * 1024 },
  );
  await writeFile(root + '/instrumentation.log', result.stdout + result.stderr, { mode: 0o600 });
  await mkdir(root + '/screenshots');
  android(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-physical-model/.',
    root + '/screenshots',
  );
  assert(
    result.stdout.includes('OK (1 test)') &&
      !/FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED/.test(result.stdout),
  );
  const proof = JSON.parse(await readFile(root + '/screenshots/native-proof.json', 'utf8'));
  assert.equal(proof.threadId, threadId);
  assert.equal(inputs.length, 1);
  assert.equal(proof.operationId, inputs[0].clientMessageId);
  const after = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId));
  const turns = desktopTurns(after);
  assert.equal(turns.length, priorTurns + 1);
  assert.equal(turns.at(-1).turnId, proof.turnId);
  assert.equal(turns.at(-1).status, 'completed');
  const users = turns.flatMap((t) => t.items ?? []).filter((v) => v.type === 'userMessage');
  assert.equal(users.filter((v) => v.clientId === proof.operationId).length, 1);
  assert(
    turns.at(-1).items.some((v) => v.type === 'agentMessage' && v.text.trim() === proof.marker),
  );
  report.originalOwnerAfter = true;
  report.originalNewTurns = 1;
  report.nativeReplyCount = 1;
  report.noAutomaticReplay = true;
  report.realModelTaskCompleted = true;
  report.actualDraftRestored = proof.draftRestored;
  report.completedTests = 1;
  report.failures = 0;
  report.skips = 0;
  report.status = 'passed-functional-pending-visual-review';
} catch (e) {
  report.status = 'failed';
  report.error = e.message;
  if (e.stdout !== undefined)
    await writeFile(root + '/instrumentation.log', String(e.stdout) + String(e.stderr ?? ''), {
      mode: 0o600,
    });
  process.exitCode = 1;
} finally {
  tearingDown = true;
  try {
    await mkdir(root + '/screenshots', { recursive: true });
    android(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-physical-model/.',
      root + '/screenshots',
    );
    report.screenshots = await Promise.all(
      (await readdir(root + '/screenshots'))
        .filter((n) => n.endsWith('.png'))
        .sort()
        .map(async (name) => ({
          name,
          sha256: sha(await readFile(root + '/screenshots/' + name)),
          reviewed: false,
        })),
    );
  } catch (e) {
    report.captureError = e.message;
  }
  if (report.status.startsWith('passed') && report.screenshots.length !== 2) {
    report.status = 'failed';
    report.error = 'Both actual physical PNGs are required';
    process.exitCode = 1;
  }
  ipc.close();
  if (gate) await new Promise((r) => gate.close(r));
  if (port) android('reverse', '--remove', 'tcp:' + port);
  if (host) await host.close();
  report.completedAt = new Date().toISOString();
  await save();
  console.log(report.status, root);
}
