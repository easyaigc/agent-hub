import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile, cp, readdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { homedir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { gunzipSync } from 'node:zlib';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Dedicated Host, fresh empty desktop thread, dedicated simulator. No shared service changes.
const physical = process.env.RECEIVE_PHYSICAL === '1';
const device = process.env.RECEIVE_IOS_DEVICE_ID ?? process.env.IOS_SIMULATOR_ID;
const publicEndpoint = process.env.RECEIVE_PUBLIC_ENDPOINT;
const physicalBuildNumber = process.env.RECEIVE_IOS_BUILD_NUMBER;
if (physicalBuildNumber != null)
  assert(
    /^[1-9]\d*$/.test(physicalBuildNumber),
    'RECEIVE_IOS_BUILD_NUMBER must be a positive integer',
  );
if (physical)
  assert(publicEndpoint?.startsWith('https://'), 'Physical phone needs a reachable HTTPS proxy');
const fixtureSecret = randomBytes(32).toString('base64url');
const executable = process.env.CODEX_EXECUTABLE;
assert(device && executable, 'Select a dedicated simulator and the installed official CLI');
const nonce = randomUUID().slice(0, 8);
const composerSync = process.env.COMPOSER_SYNC_DEV === '1';
const largeSync = process.env.LARGE_SYNC_DEV === '1';
const liveWindow = process.env.LIVE_WINDOW_DEV === '1';
const liveEvidence = {
  droppedSnapshots: 0,
  originalOwnerPatches: 0,
  observedItems: 0,
  liveResponses: 0,
};
let dropLiveSnapshots = false;
let syncFailure = false;
let historyFallback = false,
  historyCheckedAt;
let syncArmed = false,
  releaseSync;
const syncState = {
  active: false,
  replies: 0,
  desktopOpens: 0,
  sends: [],
  historyReads: 0,
  historyEvents: 0,
};
const directory = resolve('.local/message-receive-desktop-ios', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostState = process.env.RECEIVE_HOST_STATE_DIR
  ? resolve(process.env.RECEIVE_HOST_STATE_DIR)
  : join(directory, 'host');
const native = process.env.RECEIVE_ACCEPTANCE_NATIVE_PATH
  ? resolve(process.env.RECEIVE_ACCEPTANCE_NATIVE_PATH)
  : join(directory, 'native');
const nativeInput = process.env.IOS_PROJECT_PATH
  ? dirname(resolve(process.env.IOS_PROJECT_PATH))
  : 'apps/ios';
if (!process.env.RECEIVE_ACCEPTANCE_NATIVE_PATH) await cp(nativeInput, native, { recursive: true });
const report = {
  kind: 'real-desktop-native-paced-http',
  appMode: 'ordinary',
  physical,
  nonce,
  device,
  kbps: Number(process.env.RECEIVE_KBPS ?? 512),
  rttMs: Number(process.env.RECEIVE_RTT_MS ?? 250),
  adapterSha256: preparedAdapterDigest(),
  responses: [],
  ui: {},
  owner: {},
  status: 'running',
  ...(composerSync ? { composerSync: syncState } : {}),
  ...(liveWindow ? { liveWindow: liveEvidence } : {}),
};
report.hostSourceSha256 = createHash('sha256')
  .update(await readFile('apps/host-agent/dist/read-json.js'))
  .update(await readFile('apps/host-agent/dist/runtime-server.js'))
  .digest('hex');
const nativeDigest = createHash('sha256');
for (const name of (await readdir(join(native, 'Sources')))
  .filter((name) => name.endsWith('.swift'))
  .sort()) {
  nativeDigest.update(name).update(await readFile(join(native, 'Sources', name)));
}
report.nativeSourceSha256 = nativeDigest
  .update(await readFile(join(native, 'CodexManage.xcodeproj/project.pbxproj')))
  .digest('hex');
report.nativeUITestSha256 = createHash('sha256')
  .update(await readFile(join(native, 'Tests/CodexManageUITests.swift')))
  .digest('hex');
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), {
    mode: 0o600,
  });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let service, proxy, fixtureServer, child, fixture;
const controlResponse = (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (
    ![
      '/',
      '/fixture',
      '/sync/arm',
      '/sync/status',
      '/sync/release',
      ...(largeSync ? ['/sync/fail', '/sync/recover', '/sync/history'] : []),
      ...(liveWindow ? ['/sync/live'] : []),
    ].includes(path)
  )
    return false;
  if (
    req.method !== 'GET' ||
    req.headers.origin ||
    (req.headers['x-sync-fixture-secret'] !== fixtureSecret &&
      (composerSync ||
        physical ||
        !['127.0.0.1:8795', 'localhost:8795'].includes(req.headers.host)))
  ) {
    res.writeHead(403).end();
    return true;
  }
  if (path === '/sync/arm') syncArmed = true;
  if (path === '/sync/live') {
    dropLiveSnapshots = true;
    const adapter = service.session.runtime.ipc;
    adapter.states.delete(report.threadId);
    adapter.onUnavailable(report.threadId);
  }
  if (path === '/sync/release') releaseSync?.();
  if (path === '/sync/fail') syncFailure = true;
  if (path === '/sync/recover') {
    syncFailure = false;
    historyFallback = false;
  }
  if (path === '/sync/history') {
    historyFallback = true;
    historyCheckedAt = undefined;
  }
  res.writeHead(200, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
  });
  res.end(JSON.stringify(path.startsWith('/sync/') ? syncState : fixture));
  return true;
};
try {
  const preparer = new DesktopThreadPreparer(executable, join(homedir(), '.codex'));
  report.threadId =
    process.env.RECEIVE_ACCEPTANCE_THREAD_ID ??
    (await preparer.prepare(
      {
        cwd: process.cwd(),
        projectId: null,
        title: (composerSync ? '慢网发送 DEV ' : '慢网接收 DEV ') + nonce,
      },
      {
        starting(pid) {
          report.preparationPid = pid;
        },
        identified(id) {
          report.preparedId = id;
        },
        materialized() {
          report.zeroTurnMetadata = true;
        },
        exited(code) {
          report.preparationExit = code;
        },
      },
    ));
  if (!process.env.RECEIVE_ACCEPTANCE_THREAD_ID) assert.equal(report.preparationExit, 0);
  await openOriginalDesktopThread(report.threadId);
  await ipc.connect();
  const ownerDeadline = Date.now() + 60000;
  while (Date.now() < ownerDeadline) {
    if (await ipc.follow(report.threadId, 1000, true)) break;
    await delay(500);
  }
  assert(ipc.hasOwner(report.threadId));
  const baselineTurns = desktopTurns(ipc.state(report.threadId));
  assert(!baselineTurns.some((turn) => turn.status === 'inProgress'));
  if (!process.env.RECEIVE_ACCEPTANCE_THREAD_ID) assert.equal(baselineTurns.length, 0);
  report.baselineTurnIds = baselineTurns.map((turn) => turn.turnId);
  ipc.onSnapshot = (id, state) => {
    if (id !== report.threadId) return;
    const turns = desktopTurns(state);
    const assistant = turns
      .flatMap((turn) => turn.items ?? [])
      .filter((item) => item.type === 'agentMessage')
      .map((item) => item.text ?? '')
      .join('\n');
    if (turns.length) report.owner.turnAt ??= Date.now();
    if (assistant.includes((composerSync ? 'SYNC_FIRST_' : 'RECEIVE_START_') + nonce))
      report.owner.firstAt ??= Date.now();
    if (assistant.includes((composerSync ? 'SYNC_DONE_' : 'RECEIVE_DONE_') + nonce))
      report.owner.finalAt ??= Date.now();
    if (turns.at(-1)?.status === 'completed') report.owner.completedAt ??= Date.now();
    if (
      physical &&
      turns.length === 1 &&
      turns[0].status === 'completed' &&
      !report.physicalSyncArmedAt
    ) {
      syncArmed = true;
      report.physicalSyncArmedAt = Date.now();
    }
  };
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: hostState,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: '慢网接收 DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = await startRuntimeServer();
  const hostPort = service.server.address().port;
  const token = (await readFile(join(hostState, 'access-token'), 'utf8')).trim();
  const initial = await service.session.open(report.threadId);
  assert.equal(initial.runtime.canControl, true);
  if (liveWindow) {
    // Fault injection is confined to this isolated Host receiver. The official
    // desktop, its stored history and the independent owner witness are untouched.
    const adapter = service.session.runtime.ipc;
    const receive = adapter.receive.bind(adapter);
    adapter.receive = (message) => {
      if (
        dropLiveSnapshots &&
        message.method === 'thread-stream-state-changed' &&
        message.params?.conversationId === report.threadId
      ) {
        if (message.params.change?.type === 'snapshot') {
          liveEvidence.droppedSnapshots++;
          return;
        }
        if (message.params.change?.type === 'patches') liveEvidence.originalOwnerPatches++;
      }
      receive(message);
    };
    const emit = service.session.emit.bind(service.session);
    service.session.emit = (id, method, ...args) => {
      if (id === report.threadId && method === 'desktop/observation') liveEvidence.observedItems++;
      return emit(id, method, ...args);
    };
  }
  if (largeSync && baselineTurns.length === 0) {
    // A zero-turn prepared owner may be absent from the desktop's persisted
    // sidebar. Materialize this dedicated QA chat with one real, harmless turn.
    const prime = await service.session.submit({
      hostId: service.session.hostId,
      threadId: report.threadId,
      clientMessageId: 'prime-' + nonce,
      text: '专用同步验收准备，只回复 SYNC_READY_' + nonce + '，不调用工具。',
      attachmentIds: [],
      mode: 'next-turn',
      effort: 'xhigh',
    });
    const deadline = Date.now() + 180000;
    while (Date.now() < deadline) {
      const turns = desktopTurns(ipc.state(report.threadId));
      if (turns[0]?.status === 'completed') {
        report.primingTurnId = turns[0].turnId;
        break;
      }
      assert.notEqual(prime.state, 'uncertain', 'Never replay an uncertain preparation input');
      await delay(500);
    }
    assert(report.primingTurnId, 'Dedicated QA preparation must complete before native testing');
  }
  proxy = createServer(async (req, res) => {
    if (controlResponse(req, res)) return;
    try {
      const input = [];
      for await (const chunk of req) input.push(chunk);
      const path = new URL(req.url, 'http://localhost').pathname;
      if (largeSync && historyFallback && ['/v1/history', '/v1/events'].includes(path)) {
        const cached = service.session.history(report.threadId, true);
        if (path === '/v1/history') {
          historyCheckedAt = new Date().toISOString();
          syncState.historyReads++;
        } else {
          syncState.historyEvents++;
          await delay(250);
        }
        cached.synchronization = {
          state: 'history',
          checkedAt: historyCheckedAt,
        };
        cached.runtime = {
          ...cached.runtime,
          canControl: false,
          state: 'unknown',
          turnId: null,
        };
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(
          JSON.stringify(
            path === '/v1/history'
              ? cached
              : {
                  hostId: service.session.hostId,
                  epoch: 'history-fallback-fixture',
                  cursor: 1,
                  reset: false,
                  ready: true,
                  events: [{ threadId: report.threadId, snapshot: cached }],
                },
          ),
        );
        return;
      }
      if (largeSync && syncFailure && ['/v1/history', '/v1/events'].includes(path)) {
        if (path === '/v1/history') {
          report.injectedHistoryFailures = (report.injectedHistoryFailures ?? 0) + 1;
          res
            .writeHead(503, { 'Content-Type': 'application/json' })
            .end(JSON.stringify({ error: '无法同步最新进度，当前显示缓存。' }));
        } else {
          const cached = service.session.history(report.threadId, true);
          cached.synchronization.state = 'unavailable';
          report.successfulStaleEvents = (report.successfulStaleEvents ?? 0) + 1;
          await delay(250);
          res.writeHead(200, { 'Content-Type': 'application/json' }).end(
            JSON.stringify({
              hostId: service.session.hostId,
              epoch: 'sync-failure-fixture',
              cursor: 1,
              reset: false,
              ready: true,
              events: [{ threadId: report.threadId, snapshot: cached }],
            }),
          );
        }
        return;
      }
      let submission;
      if (path === '/v1/replies' && req.method === 'POST') {
        report.submitAt = Date.now();
        submission = { at: Date.now(), whileSyncing: syncState.active };
        syncState.sends.push(submission);
      }
      if (path === '/v1/desktop/open' && req.method === 'POST') syncState.desktopOpens++;
      if (composerSync && path === '/v1/history' && syncArmed) {
        syncArmed = false;
        syncState.active = true;
        await new Promise((done) => {
          const timer = setTimeout(done, 30000);
          releaseSync = () => {
            clearTimeout(timer);
            done();
          };
          res.once('close', releaseSync);
        });
        syncState.active = false;
        releaseSync = undefined;
      }
      await delay(report.rttMs / 2);
      const started = Date.now();
      if (submission) submission.forwardedAt = started;
      const upstream = await new Promise((done, reject) => {
        const call = request(
          {
            host: '127.0.0.1',
            port: hostPort,
            path: req.url,
            method: req.method,
            headers: { ...req.headers, host: '127.0.0.1:' + hostPort },
          },
          (response) => {
            const chunks = [];
            response.on('data', (chunk) => chunks.push(chunk));
            response.on('end', () =>
              done({
                status: response.statusCode,
                headers: response.headers,
                bytes: Buffer.concat(chunks),
              }),
            );
            response.on('error', reject);
          },
        );
        res.once('close', () => {
          if (!res.writableFinished) call.destroy();
        });
        call.on('error', reject);
        call.end(Buffer.concat(input));
      });
      if (submission) {
        submission.acknowledgedAt = Date.now();
        submission.acceptedWhileSyncing = syncState.active;
        submission.status = upstream.status;
        syncState.replies++;
        if (physical && syncState.replies === 2) releaseSync?.();
      }
      await delay(report.rttMs / 2);
      if (res.destroyed) return;
      res.writeHead(upstream.status, upstream.headers);
      const sending = performance.now();
      const bytesPerSecond = report.kbps * 125;
      const size = Math.floor(bytesPerSecond * 0.02);
      for (let offset = 0; offset < upstream.bytes.length; offset += size) {
        if (res.destroyed) return;
        const end = Math.min(offset + size, upstream.bytes.length);
        await delay(Math.max(0, (end / bytesPerSecond) * 1000 - (performance.now() - sending)));
        res.write(upstream.bytes.subarray(offset, end));
      }
      res.end();
      if (['/v1/events', '/v1/history', '/v1/workspace'].includes(path)) {
        const data =
          upstream.headers['content-encoding'] === 'gzip'
            ? gunzipSync(upstream.bytes)
            : upstream.bytes;
        if (liveWindow && dropLiveSnapshots) {
          const value = JSON.parse(data);
          const snapshots =
            path === '/v1/history' ? [value] : (value.events ?? []).map((v) => v.snapshot);
          liveEvidence.liveResponses += snapshots.filter(
            (v) => v.synchronization?.state === 'live-window',
          ).length;
        }
        report.responses.push({
          path,
          at: Date.now(),
          wireBytes: upstream.bytes.length,
          decodedBytes: data.length,
          encoding: upstream.headers['content-encoding'] ?? 'identity',
          hostWaitAndEncodeMs:
            Date.now() - started - report.rttMs / 2 - (performance.now() - sending),
        });
      }
    } catch {
      if (!res.headersSent && !res.destroyed) res.writeHead(502).end();
      else res.destroy();
    }
  });
  await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
  fixture = {
    endpoint: physical ? publicEndpoint : 'http://127.0.0.1:' + proxy.address().port,
    token,
    hostId: service.session.hostId,
    threadId: report.threadId,
    threadTitle: initial.thread.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  if (!physical) {
    fixtureServer = createServer((req, res) => {
      if (!controlResponse(req, res)) res.writeHead(404).end();
    });
    await new Promise((done, reject) => {
      fixtureServer.once('error', reject);
      fixtureServer.listen(8795, '127.0.0.1', done);
    });
  }
  if (process.env.RECEIVE_TAILSCALE_HTTPS_PORT) {
    const { execFileSync } = await import('node:child_process');
    const beforeServe = JSON.parse(
      execFileSync(
        '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
        ['serve', 'status', '--json'],
        { encoding: 'utf8' },
      ),
    );
    assert(
      !beforeServe.TCP?.[process.env.RECEIVE_TAILSCALE_HTTPS_PORT],
      'Temporary HTTPS port must be unused',
    );
    execFileSync(
      '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
      [
        'serve',
        '--bg',
        '--https=' + process.env.RECEIVE_TAILSCALE_HTTPS_PORT,
        'http://127.0.0.1:' + proxy.address().port,
      ],
      { stdio: 'pipe' },
    );
    report.temporaryHttpsPort = process.env.RECEIVE_TAILSCALE_HTTPS_PORT;
    const ready = await fetch(publicEndpoint + '/v1/workspace?hostId=' + service.session.hostId, {
      headers: { Authorization: 'Bearer ' + token },
      signal: AbortSignal.timeout(15000),
    });
    assert.equal(ready.status, 200, 'Phone HTTPS route must authenticate before native acceptance');
    assert.equal((await ready.json()).hostId, service.session.hostId);
    report.httpsPreflight = 'authenticated-exact-host';
  }
  report.resultBundle = join(directory, 'result.xcresult');
  await save();
  console.log(JSON.stringify({ directory, device, threadId: report.threadId }));
  child = spawn(
    'xcodebuild',
    [
      '-project',
      // Keep workspace-relative KMP build phases and framework paths valid.
      resolve(nativeInput, 'CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      (physical ? 'platform=iOS,id=' : 'platform=iOS Simulator,id=') + device,
      '-derivedDataPath',
      process.env.IOS_DERIVED_DATA_PATH ?? join(directory, 'build'),
      '-resultBundlePath',
      report.resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/' +
        (composerSync
          ? physical
            ? 'testPhysicalSlowNetworkSendDEV'
            : 'testOriginalDesktopSendDuringSlowSync'
          : largeSync
            ? 'testOriginalDesktopLargeConversationSync'
            : liveWindow
              ? 'testOriginalDesktopLiveWindow'
              : 'testOriginalDesktopReceivePerformance'),
      'ONLY_ACTIVE_ARCH=YES',
      ...(physical
        ? [
            ...(physicalBuildNumber ? ['CURRENT_PROJECT_VERSION=' + physicalBuildNumber] : []),
            '-allowProvisioningUpdates',
          ]
        : ['CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-']),
      'test',
    ],
    {
      env: {
        ...process.env,
        TEST_RUNNER_SYNC_FIXTURE_URL: physical ? publicEndpoint : 'http://127.0.0.1:8795',
        TEST_RUNNER_SYNC_FIXTURE_SECRET: fixtureSecret,
        ...(process.env.RECEIVE_REUSE_CONNECTION === '1'
          ? { TEST_RUNNER_SYNC_REUSE_CONNECTION: '1' }
          : {}),
        ...(physical ? { TEST_RUNNER_SYNC_FIXTURE_JSON: JSON.stringify(fixture) } : {}),
        ...(process.env.RECEIVE_ORIGINAL_CLIENT_ID
          ? {
              TEST_RUNNER_SYNC_ORIGINAL_CLIENT_ID: process.env.RECEIVE_ORIGINAL_CLIENT_ID,
            }
          : {}),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(fixtureSecret, '[REDACTED]')
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log.write(safe + '\n');
      const stamp = safe.match(/RECEIVE_UI_(SEND|FIRST|FINAL)\s+(\d+(?:\.\d+)?)/);
      if (stamp) report.ui[stamp[1].toLowerCase() + 'At'] = Number(stamp[2]) * 1000;
      const physicalClick = safe.match(/SLOW_SYNC_PHYSICAL_CLICK_AT\s+([\d.]+)/);
      if (physicalClick) report.ui.clickedAt = Number(physicalClick[1]);
      const click = safe.match(/SLOW_SYNC_CLICK_TO_HOST_MS\s+(-?[\d.]+)/);
      if (click) report.ui.clickToProxyMs = Number(click[1]);
      if (/^Test Case|: error:|^\*\* TEST|RECEIVE_UI_|SLOW_SYNC_/.test(safe)) console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  assert.equal(code, 0, 'Native test failed; preserve the original evidence');
  if (liveWindow) {
    report.liveWindow = liveEvidence;
    assert(liveEvidence.droppedSnapshots > 0);
    assert(liveEvidence.originalOwnerPatches > 2);
    assert(liveEvidence.observedItems > 0);
    assert(liveEvidence.liveResponses > 0);
    assert.equal(syncState.sends.length, 1, 'Live recovery never replays a model input');
  }
  if (largeSync) {
    report.historyFallback = {
      historyReads: syncState.historyReads,
      cachedEvents: syncState.historyEvents,
    };
    assert(syncState.historyReads >= 2, 'Cached events must not starve actual history refreshes');
    assert(syncState.historyEvents >= 5);
  }
  const state = await ipc.follow(report.threadId, 15000, true, true);
  const turns = desktopTurns(state).filter(
    (turn) => turn.turnId !== report.primingTurnId && !report.baselineTurnIds.includes(turn.turnId),
  );
  assert.equal(
    turns.length,
    composerSync ? 2 : 1,
    'Each native send must produce exactly one original-owner turn',
  );
  for (const turn of turns) {
    assert.equal(turn.status, 'completed');
    assert.equal(
      turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
      'xhigh',
    );
    assert.equal((turn.items ?? []).filter((item) => item.type === 'userMessage').length, 1);
  }
  report.owner.turns = turns.map((turn) => ({
    id: turn.turnId,
    status: turn.status,
    model: turn.params.model,
    effort: turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
    userInputs: (turn.items ?? []).filter((item) => item.type === 'userMessage').length,
  }));
  if (composerSync) {
    report.composerSync = syncState;
    assert.equal(syncState.replies, 2);
    assert.equal(syncState.desktopOpens, 0);
    assert.equal(syncState.sends[1].whileSyncing, true);
    assert.equal(syncState.sends[1].acceptedWhileSyncing, true);
    assert.equal(syncState.sends[1].status, 200);
    assert(
      turns[1].items.some(
        (item) => item.type === 'agentMessage' && item.text.includes('SYNC_DONE_' + nonce),
      ),
    );
  }
  assert.equal(turns[0].status, 'completed');
  assert.equal(
    turns[0].params.effort ?? turns[0].params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  const items = turns[0].items ?? [];
  assert.equal(items.filter((item) => item.type === 'userMessage').length, 1);
  assert(
    items.some(
      (item) =>
        item.type === 'agentMessage' &&
        item.text.includes((composerSync ? 'SYNC_FIRST_' : 'RECEIVE_DONE_') + nonce),
    ),
  );
  assert(report.responses.some((row) => row.path === '/v1/events' && row.encoding === 'gzip'));
  if (largeSync) {
    assert(report.injectedHistoryFailures > 0);
    assert(report.successfulStaleEvents > 0);
    assert.equal(syncState.sends.length, 1, 'Failure/recovery must not submit another model input');
    report.nativeSyncFailureAndRecovery = true;
  }
  if (physical) report.ui.clickToProxyMs = syncState.sends[1].at - report.ui.clickedAt;
  // Build the canonical isolated workspace project; the private native copy is
  // immutable input evidence. Reject source drift instead of relocating KMP paths.
  const currentNative = createHash('sha256');
  for (const name of (await readdir(join(nativeInput, 'Sources')))
    .filter((name) => name.endsWith('.swift'))
    .sort()) {
    currentNative.update(name).update(await readFile(join(nativeInput, 'Sources', name)));
  }
  assert.equal(
    currentNative
      .update(await readFile(join(nativeInput, 'CodexManage.xcodeproj/project.pbxproj')))
      .digest('hex'),
    report.nativeSourceSha256,
  );
  assert.equal(
    createHash('sha256')
      .update(await readFile(join(nativeInput, 'Tests/CodexManageUITests.swift')))
      .digest('hex'),
    report.nativeUITestSha256,
  );
  report.nativeBuildScope =
    'Canonical isolated workspace project with unchanged source matching the private native snapshot; own derived data and result bundle';
  report.status = 'passed';
  report.actualModel = turns[0].params.model ?? turns[0].params.collaborationMode?.settings?.model;
  assert.equal(typeof report.actualModel, 'string');
  assert(report.actualModel.length > 0);
  report.actualEffort = 'xhigh';
  console.log(
    JSON.stringify({
      status: report.status,
      owner: report.owner,
      ui: report.ui,
      compressedEventResponses: report.responses.filter(
        (row) => row.path === '/v1/events' && row.encoding === 'gzip',
      ).length,
    }),
  );
} catch (error) {
  report.status = 'failed';
  report.failure = error.message;
  throw error;
} finally {
  releaseSync?.();
  await save();
  if (child && child.exitCode === null) child.kill('SIGTERM');
  for (const server of [fixtureServer, proxy])
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
  if (service) await service.close();
  ipc.close();
  if (report.temporaryHttpsPort) {
    const { execFileSync } = await import('node:child_process');
    execFileSync(
      '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
      ['serve', '--https=' + report.temporaryHttpsPort, 'off'],
      { stdio: 'pipe' },
    );
  }
}
