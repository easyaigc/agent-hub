import assert from 'node:assert/strict';
import { execFileSync, execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID, createHash } from 'node:crypto';
import {
  readFile,
  writeFile,
  mkdir,
  copyFile,
  readdir,
  stat,
  rename,
  unlink,
} from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { CodexRuntime } from '../../packages/codex-adapter/dist/runtime.js';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from '../../scripts/mobile/android-install-if-needed.mjs';
assert.equal(
  process.env.A4_CONTROLLED_PROVIDER_APPROVED,
  '1',
  'Explicit approval for the prepared plan is required',
);
const serial = process.env.ANDROID_SERIAL;
assert(serial);
const physical = !serial.startsWith('emulator-');
assert(!physical || process.env.ANDROID_ALLOW_PHYSICAL === '1');
const planDir = resolve('.local/android-development/a4/runtime-failure-provider-plan');
await mkdir(planDir, { recursive: true, mode: 0o700 });
const examinerPath = fileURLToPath(import.meta.url),
  proxyPath = fileURLToPath(new URL('./runtime-failure-proxy.mjs', import.meta.url));
const root = resolve('.local/mobile-acceptance/A4/runtime-failure-' + serial + '-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
await writeFile(planDir + '/active-run.json', JSON.stringify({ root }), { mode: 0o600 });
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb');
const android = (...a) =>
  execFileSync(adb, ['-s', serial, ...a], { encoding: 'utf8', timeout: 60000 }).trim();
assert.equal(android('get-state'), 'device');
const sha = (b) => createHash('sha256').update(b).digest('hex');
const nonce = randomUUID().slice(0, 8),
  provider = 'a4_acceptance_' + nonce,
  configPath = join(homedir(), '.codex/config.toml');
const report = {
  stage: 'A4 / S10',
  status: 'running',
  serial,
  api: Number(android('shell', 'getprop', 'ro.build.version.sdk')),
  nonce,
  provider,
  adapterSha256: preparedAdapterDigest(),
  allowsNextStage: false,
  actualFailedTurn: false,
  commands: [],
  metadataMethods: [],
};
const save = () =>
  writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const inputs = [];
let threadId,
  host,
  gate,
  port,
  proxy,
  block,
  originalConfig,
  configMode,
  tearingDown = false;
const safeProcesses = () =>
  execFileSync('/bin/ps', ['-ax', '-o', 'pid=,comm='], { encoding: 'utf8' })
    .split('\n')
    .filter((s) =>
      /^\s*\d+\s+\/Applications\/ChatGPT\.app\/Contents\/MacOS\/ChatGPT(?:\s|$)/.test(s),
    )
    .map((s) => Number(s.trim().split(/\s+/)[0]));
const existingPids = safeProcesses();
assert(existingPids.length > 0, 'The original desktop main application is required');
async function metadata(fn) {
  const r = new CodexRuntime({
    mode: 'managed-runtime',
    executable:
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  });
  try {
    await r.connect();
    report.metadataMethods.push('initialize');
    const allowed = new Set([
      'account/read',
      'config/read',
      'project/create',
      'thread/start',
      'thread/name/set',
      'thread/read',
    ]);
    return await fn(async (method, params = {}) => {
      assert(allowed.has(method), 'Metadata-only whitelist');
      report.metadataMethods.push(method);
      return r.call(method, params);
    });
  } finally {
    const c = r.child;
    let timer;
    const exited = c
      ? new Promise((done) => c.once('close', (code) => done(code)))
      : Promise.resolve(0);
    c?.stdin.end();
    const code = await Promise.race([
      exited,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(Error('Metadata process exit unconfirmed')), 12000);
      }),
    ]).finally(() => clearTimeout(timer));
    assert.equal(code, 0, 'No desktop takeover before normal metadata exit');
    r.close();
    report.metadataExitedNormally = true;
    await save();
  }
}
async function changeConfig(current, next) {
  const live = await readFile(configPath);
  assert(live.equals(current), 'Configuration changed concurrently before write');
  const tmp = configPath + '.a4-' + nonce;
  await writeFile(tmp, next, { mode: configMode, flag: 'wx' });
  try {
    assert((await readFile(configPath)).equals(current), 'Preserve concurrent configuration edit');
    await rename(tmp, configPath);
  } finally {
    await unlink(tmp).catch(() => {});
  }
}
async function restoreConfig() {
  if (!block) return;
  const live = await readFile(configPath);
  const text = live.toString();
  assert.equal(text.split(block).length, 2, 'Exact added provider must be uniquely removable');
  const restored = Buffer.from(text.replace(block, ''));
  await changeConfig(live, restored);
  report.providerRemoved = true;
  report.configRestoredExactly = restored.equals(originalConfig);
  report.concurrentEditsPreserved = !report.configRestoredExactly;
  report.configSha256After = sha(restored);
  block = null;
  await save();
}
try {
  await ipc.connect();
  report.existingDesktopPids = existingPids;
  const parent = process.env.CODEX_THREAD_ID;
  assert(/^[a-f0-9-]{36}$/.test(parent));
  const ps = await ipc.follow(parent, 15000, true, true);
  assert(ipc.hasOwner(parent));
  report.unrelatedCurrentChatBefore = {
    threadId: parent,
    provider: ps.modelProvider,
    latestTurn: desktopTurns(ps).at(-1)?.turnId,
    turnCount: desktopTurns(ps).length,
  };
  const account = await metadata(async (call) => {
    const a = await call('account/read', { refreshToken: false });
    const c = await call('config/read', { includeLayers: false });
    assert.equal(c.config.model_provider ?? 'openai', 'openai');
    const url = new URL(c.config.chatgpt_base_url ?? 'https://chatgpt.com/backend-api/');
    assert.equal(url.origin, 'https://chatgpt.com');
    assert(['/backend-api', '/backend-api/'].includes(url.pathname));
    assert(!c.config.openai_base_url, 'Do not redirect an existing configured inference path');
    return a.account?.type;
  });
  assert.equal(account, 'chatgpt', 'Prepared fixed ChatGPT origin requires ChatGPT auth');
  await writeFile(root + '/mode', 'fault', { mode: 0o600 });
  await copyFile(proxyPath, root + '/proxy.mjs');
  await writeFile(
    root + '/proxy-plan.json',
    JSON.stringify({
      upstreamBaseUrl: 'https://chatgpt.com/backend-api/codex',
      modeFile: root + '/mode',
      privateCounterFile: root + '/proxy-counters.json',
      privatePortFile: root + '/proxy-port.json',
      nonce,
    }),
    { mode: 0o600 },
  );
  proxy = spawn(process.execPath, [root + '/proxy.mjs', root + '/proxy-plan.json'], {
    stdio: 'ignore',
    env: { ...process.env, NODE_USE_ENV_PROXY: '1' },
  });
  let proxyPort;
  for (let n = 0; n < 100; n++) {
    try {
      proxyPort = JSON.parse(await readFile(root + '/proxy-port.json', 'utf8')).port;
      break;
    } catch {
      assert(proxy.exitCode === null);
      await delay(100);
    }
  }
  assert(proxyPort);
  originalConfig = await readFile(configPath);
  configMode = (await stat(configPath)).mode & 0o777;
  report.configSha256Before = sha(originalConfig);
  assert(!originalConfig.toString().includes('[model_providers.' + provider + ']'));
  block =
    '\n# A4 controlled acceptance ' +
    nonce +
    ' begin\n[model_providers.' +
    provider +
    ']\nname = "A4 dedicated runtime failure"\nbase_url = "http://127.0.0.1:' +
    proxyPort +
    '/v1"\nwire_api = "responses"\nrequires_openai_auth = true\nrequest_max_retries = 0\nstream_max_retries = 0\nsupports_websockets = false\n# A4 controlled acceptance ' +
    nonce +
    ' end\n';
  await writeFile(root + '/added-provider-block.txt', block, { mode: 0o600 });
  await changeConfig(originalConfig, Buffer.concat([originalConfig, Buffer.from(block)]));
  report.providerRegistered = true;
  await save();
  await mkdir(root + '/project', { mode: 0o700 });
  await metadata(async (call) => {
    const c = await call('config/read', { includeLayers: false });
    assert.equal(c.config.model_provider ?? 'openai', 'openai');
    assert.equal(
      c.config.model_providers[provider].base_url,
      'http://127.0.0.1:' + proxyPort + '/v1',
    );
    const p = await call('project/create', {
      name: 'Android runtime failure DEV ' + nonce,
      roots: [{ path: root + '/project' }],
      idempotencyKey: randomUUID(),
    });
    report.projectId = p.project.id;
    const result = await call('thread/start', {
      cwd: root + '/project',
      projectId: p.project.id,
      modelProvider: provider,
      model: 'gpt-6.1-sol',
      approvalPolicy: 'never',
      sandbox: 'read-only',
      ephemeral: false,
      historyMode: 'legacy',
    });
    threadId = result.thread.id;
    assert(threadId);
    await call('thread/name/set', { threadId, name: 'A4 runtime failure ' + serial + ' ' + nonce });
    const v = await call('thread/read', { threadId, includeTurns: true });
    assert.equal(v.thread.turns.length, 0);
    assert.equal(v.thread.cwd, root + '/project');
    assert.equal(v.thread.modelProvider, provider);
    report.zeroTurnMetadataVerified = true;
    report.threadId = threadId;
  });
  await writeFile(planDir + '/ready.json', JSON.stringify({ root, threadId }), { mode: 0o600 });
  console.log('Original desktop must open prepared zero-Turn chat:', threadId);
  let before;
  for (let n = 0; n < 90; n++) {
    try {
      before = await ipc.follow(threadId, 1000, true, true);
      if (ipc.hasOwner(threadId)) break;
    } catch {
      // The prepared chat may still be waiting for the original desktop to open it.
    }
    await delay(1000);
  }
  assert(ipc.hasOwner(threadId), 'Original desktop owner is required');
  assert.equal(before.cwd, root + '/project');
  assert.equal(desktopTurns(before).length, 0);
  assert.equal(before.requests?.length ?? 0, 0);
  assert.equal(before.modelProvider, provider);
  report.originalOwnerBefore = true;
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
  await host.session.workspace();
  await host.session.open(threadId);
  assert((await host.session.workspace()).threads.some((t) => t.id === threadId));
  const endpoint = 'http://127.0.0.1:' + host.server.address().port,
    token = (await readFile(root + '/host/access-token', 'utf8')).trim();
  gate = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, endpoint);
      if (req.headers.authorization !== 'Bearer ' + token) {
        res.writeHead(401).end();
        return;
      }
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const body = Buffer.concat(chunks);
      if (req.method === 'POST') {
        assert.equal(url.pathname, '/v1/replies');
        const v = JSON.parse(body);
        assert.equal(v.threadId, threadId);
        assert.equal(v.mode, 'next-turn');
        assert(v.text.includes(nonce));
        inputs.push(v);
        assert(inputs.length <= 2, 'Only two explicit native inputs; never automatic replay');
        report.commands.push({ path: url.pathname, bodySha256: sha(body) });
        if (inputs.length === 2) {
          assert(v.text.includes('RECOVERED_' + nonce));
          const failed = await ipc.follow(threadId, 15000, true, true);
          assert(ipc.hasOwner(threadId));
          const t = desktopTurns(failed);
          assert.equal(t.length, 1);
          assert.equal(t[0].status, 'failed');
          assert.equal(
            t[0].items.filter(
              (i) => i.type === 'userMessage' && i.clientId === inputs[0].clientMessageId,
            ).length,
            1,
          );
          report.actualFailedTurn = true;
          report.failedTurn = t[0].turnId;
          await writeFile(root + '/mode', 'recover', { mode: 0o600 });
          report.proxyRecoveryEnabledAfterActualFailedTurn = true;
        } else assert(v.text.includes('FAULT_' + nonce));
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
      const r = await fetch(url, {
        method: req.method,
        headers: {
          authorization: 'Bearer ' + token,
          ...(req.headers['content-type'] ? { 'content-type': req.headers['content-type'] } : {}),
        },
        ...(req.method === 'POST' ? { body } : {}),
        signal: AbortSignal.timeout(60000),
      });
      const bytes = Buffer.from(await r.arrayBuffer());
      res.writeHead(r.status, { 'content-type': 'application/json' }).end(bytes);
    } catch (e) {
      if (!tearingDown) report.gateError = e.message;
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
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: physical }),
    );
  report.suiteSha256 = sha(
    await readFile(
      'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveRuntimeFailureTest.kt',
    ),
  );
  report.apkSha256 = sha(
    await readFile('.build/native/android/outputs/apk/debug/android-debug.apk'),
  );
  report.testApkSha256 = sha(
    await readFile(
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
    ),
  );
  await copyFile(
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveRuntimeFailureTest.kt',
    root + '/MobileLiveRuntimeFailureTest.kt',
  );
  await copyFile(examinerPath, root + '/run-live.mjs');
  report.examinerSha256 = sha(await readFile(examinerPath));
  report.proxySha256 = sha(await readFile(proxyPath));
  await writeFile(
    root + '/native-config.json',
    JSON.stringify({ endpoint: 'http://127.0.0.1:' + port, token, threadId, nonce }),
    { mode: 0o600 },
  );
  const p =
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/runtime-failure-config.json';
  android('push', root + '/native-config.json', p);
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-runtime-failure',
  );
  await save();
  report.nativeInstrumentationStarted = true;
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
      'com.agenthub.android.MobileLiveRuntimeFailureTest',
      '-e',
      'runtimeFailureConfig',
      p,
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 340000, maxBuffer: 2 * 1024 * 1024 },
  );
  await writeFile(root + '/instrumentation.log', result.stdout + result.stderr, { mode: 0o600 });
  await mkdir(root + '/screenshots');
  android(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-runtime-failure/.',
    root + '/screenshots',
  );
  assert(
    result.stdout.includes('OK (1 test)') &&
      !/FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED/.test(result.stdout),
  );
  const proof = JSON.parse(await readFile(root + '/screenshots/native-proof.json', 'utf8'));
  assert.equal(inputs.length, 2);
  assert.equal(proof.failedOperation, inputs[0].clientMessageId);
  assert.equal(proof.recoveredOperation, inputs[1].clientMessageId);
  const after = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId));
  const turns = desktopTurns(after);
  assert.equal(turns.length, 2);
  assert.equal(turns[0].status, 'failed');
  assert.equal(turns[1].status, 'completed');
  assert.equal(turns[0].turnId, proof.failedTurn);
  assert.equal(turns[1].turnId, proof.recoveredTurn);
  assert.equal(turns[0].error.message, proof.error);
  assert(
    turns[1].items.some(
      (i) => i.type === 'agentMessage' && i.text.trim() === proof.recoveredMarker,
    ),
  );
  for (const input of inputs)
    assert.equal(
      turns
        .flatMap((t) => t.items ?? [])
        .filter((i) => i.type === 'userMessage' && i.clientId === input.clientMessageId).length,
      1,
    );
  report.originalOwnerAfter = true;
  report.originalNewTurns = 2;
  report.nativeReplyCount = 2;
  report.actualFailedTurn = true;
  report.actualRecoveryCompleted = true;
  report.noAutomaticReplay = true;
  report.dismissedRestored = proof.dismissedRestored;
  report.draftRestored = proof.draftRestored;
  report.historicalFailurePreserved = proof.historicalFailurePreserved;
  report.originalTurnStates = turns.map((t) => t.status);
  report.completedTests = 1;
  report.failures = 0;
  report.skips = 0;
  report.status = 'passed-functional-pending-visual-review';
} catch (e) {
  report.status = 'failed';
  report.error = e.message;
  process.exitCode = 1;
  if (e.stdout !== undefined)
    await writeFile(root + '/instrumentation.log', String(e.stdout) + String(e.stderr ?? ''), {
      mode: 0o600,
    });
  if (threadId) {
    try {
      const s = await ipc.follow(threadId, 15000, true, true);
      report.failureAuditOriginalOwner = ipc.hasOwner(threadId);
      report.observedTurns = desktopTurns(s).map((t) => ({
        turnId: t.turnId,
        status: t.status,
        error: t.error?.message,
        userInputs: (t.items ?? []).filter((i) => i.type === 'userMessage').length,
      }));
    } catch {
      report.failureAuditUnavailable = true;
    }
  }
} finally {
  tearingDown = true;
  try {
    if (report.nativeInstrumentationStarted) {
      await mkdir(root + '/screenshots', { recursive: true });
      android(
        'pull',
        '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-runtime-failure/.',
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
      if (report.status.startsWith('passed')) assert.equal(report.screenshots.length, 4);
    }
  } catch (e) {
    report.captureError = e.message;
    report.status = 'failed';
    process.exitCode = 1;
  }
  try {
    await restoreConfig();
  } catch (e) {
    report.cleanupConfigError = e.message;
    report.status = 'failed';
    process.exitCode = 1;
  }
  if (proxy) {
    proxy.kill('SIGTERM');
    await Promise.race([new Promise((r) => proxy.once('close', r)), delay(3000)]);
    report.proxyStopped = proxy.exitCode !== null || proxy.signalCode !== null;
  }
  if (gate) await new Promise((r) => gate.close(r));
  if (port) android('reverse', '--remove', 'tcp:' + port);
  if (host) await host.close();
  try {
    const parent = report.unrelatedCurrentChatBefore?.threadId;
    if (parent) {
      const ps = await ipc.follow(parent, 15000, true, true);
      const before = report.unrelatedCurrentChatBefore;
      assert(ipc.hasOwner(parent));
      assert.equal(ps.modelProvider, before.provider);
      assert.equal(desktopTurns(ps).at(-1)?.turnId, before.latestTurn);
      assert.equal(desktopTurns(ps).length, before.turnCount);
      report.unrelatedCurrentChatUnchanged = true;
    }
  } catch (e) {
    report.unrelatedChatAuditError = e.message;
    report.status = 'failed';
    process.exitCode = 1;
  }
  ipc.close();
  try {
    report.proxyCounters = JSON.parse(await readFile(root + '/proxy-counters.json', 'utf8'));
    if (report.status.startsWith('passed')) {
      assert.equal(report.proxyCounters.droppedConnections, 1);
      assert.equal(report.proxyCounters.forwardedRequests, 1);
    }
  } catch (e) {
    report.proxyCounterError = e.message;
    if (report.status.startsWith('passed')) {
      report.status = 'failed';
      process.exitCode = 1;
    }
  }
  report.existingDesktopProcessesRemain = existingPids.every((pid) => {
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  });
  if (report.status.startsWith('passed')) {
    try {
      assert(report.providerRemoved && report.proxyStopped);
      assert(report.configRestoredExactly || report.concurrentEditsPreserved);
      assert(report.existingDesktopProcessesRemain && report.unrelatedCurrentChatUnchanged);
    } catch (e) {
      report.restorationGateError = e.message;
      report.status = 'failed';
      process.exitCode = 1;
    }
  }
  report.completedAt = new Date().toISOString();
  await save();
  console.log(report.status, root);
}
