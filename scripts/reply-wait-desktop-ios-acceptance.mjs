import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, cp, readdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Real Electron owner + native App + two real model inputs. A local transport
// proxy holds acknowledgements, leaving actual execution and IDs unchanged.
assert(process.env.IOS_SIMULATOR_ID && process.env.CODEX_EXECUTABLE);
const recovery = process.env.REPLY_WAIT_RESUME_REPORT;
const emptyRecovery = process.env.REPLY_WAIT_RESUME_EMPTY_REPORT;
const automatic = process.env.REPLY_WAIT_AUTOMATIC === '1';
assert(!automatic || !recovery, 'Automatic recovery uses its own fresh two-input DEV task');
assert(!recovery || !emptyRecovery);
const priorDirectory = recovery ?? emptyRecovery;
const priorBytes = priorDirectory ? await readFile(join(priorDirectory, 'report.json')) : undefined;
const prior = priorBytes ? JSON.parse(priorBytes) : undefined;
if (emptyRecovery) {
  assert.equal(prior.modelInputsSubmittedThisRun, 0);
  assert.equal(prior.sends.length, 0, 'Never replay a prior input while repairing test navigation');
}
const nonce = prior?.nonce ?? randomUUID().slice(0, 8);
const directory = priorDirectory
  ? join(resolve(priorDirectory), 'recovery-' + randomUUID().slice(0, 8))
  : resolve('.local/reply-wait-live/' + nonce);
const hostState = join(priorDirectory ? resolve(priorDirectory) : directory, 'host');
await mkdir(directory, { recursive: true, mode: 0o700 });
await cp('apps/ios', join(directory, 'native'), { recursive: true });
const report = {
  nonce,
  status: 'running',
  device: process.env.IOS_SIMULATOR_ID,
  adapterSha256: preparedAdapterDigest(),
  sends: prior?.sends ?? [],
  queries: [],
  releases: [],
  modelInputsSubmittedThisRun: 0,
  ...(priorBytes
    ? {
        originalReportSha256: createHash('sha256').update(priorBytes).digest('hex'),
        receiptOnlyRecovery: Boolean(recovery),
        emptyThreadRecovery: Boolean(emptyRecovery),
      }
    : {}),
};
const digest = createHash('sha256');
for (const file of (await readdir(join(directory, 'native/Sources')))
  .filter((f) => f.endsWith('.swift'))
  .sort()) {
  digest.update(file).update(await readFile(join(directory, 'native/Sources', file)));
}
report.nativeSha256 = digest.digest('hex');
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const witness = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let service, proxy, child;
try {
  report.threadId =
    prior?.threadId ??
    (await new DesktopThreadPreparer(
      process.env.CODEX_EXECUTABLE,
      join(homedir(), '.codex'),
    ).prepare(
      { cwd: process.cwd(), projectId: null, title: '发送等待恢复 DEV ' + nonce },
      {
        starting() {},
        identified() {},
        materialized() {},
        exited(code) {
          report.preparerExit = code;
        },
      },
    ));
  if (!priorDirectory) assert.equal(report.preparerExit, 0);
  await openOriginalDesktopThread(report.threadId);
  await witness.connect();
  for (let i = 0; i < 60; i++) {
    if (await witness.follow(report.threadId, 1000, true)) break;
    await delay(500);
  }
  assert(witness.hasOwner(report.threadId));
  assert.equal(desktopTurns(witness.state(report.threadId)).length, recovery ? 2 : 0);
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: hostState,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: '发送等待恢复 DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  for (const key of [
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
    'HOST_AGENT_TOKEN',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  await service.session.workspace();
  const initial = await service.session.open(report.threadId);
  assert.equal(initial.runtime.canControl, true);
  const backend = 'http://127.0.0.1:' + service.server.address().port;
  const token = (await readFile(join(hostState, 'access-token'), 'utf8')).trim();
  const endpoint = 'http://127.0.0.1:8816';
  const held = new Map(),
    released = new Set(),
    confirmed = new Set();
  const json = (response, value, status = 200) => {
    if (!response.destroyed)
      response
        .writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
        .end(JSON.stringify(value));
  };
  const hideUnacknowledged = (snapshot) => {
    if (recovery) return;
    if (snapshot?.runtime?.receipts)
      snapshot.runtime.receipts = snapshot.runtime.receipts.filter(
        (r) =>
          !report.sends.some(
            (s, index) => s.clientMessageId === r.clientMessageId && !released.has(index),
          ),
      );
  };
  proxy = createServer(async (request, response) => {
    try {
      if (request.headers.origin || request.headers.host !== '127.0.0.1:8816')
        return json(response, { error: 'Local DEV only' }, 403);
      const url = new URL(request.url, endpoint);
      if (url.pathname === '/')
        return json(response, {
          endpoint,
          token,
          hostId: service.session.hostId,
          threadId: report.threadId,
          threadTitle: initial.thread.title,
          nonce,
          ...(recovery ? { originalReplyId: report.sends[0].clientMessageId } : {}),
          secondEndpoint: '',
          secondToken: '',
          secondHostId: '',
          delayedEndpoint: '',
        });
      assert.equal(request.headers.authorization, 'Bearer ' + token);
      if (url.pathname === '/wait/status') return json(response, report);
      if (url.pathname === '/wait/confirm') {
        confirmed.add(Number(url.searchParams.get('index')));
        return json(response, { ok: true });
      }
      if (url.pathname === '/wait/settled') {
        const index = Number(url.searchParams.get('index'));
        for (let i = 0; i < 90; i++) {
          const current = await service.session.open(report.threadId);
          if (
            current.runtime.receipts.some(
              (r) =>
                r.clientMessageId === report.sends[index]?.clientMessageId &&
                r.state === 'completed',
            )
          )
            return json(response, { ok: true });
          await delay(500);
        }
        throw Error('Original model task did not settle; no replay attempted');
      }
      if (url.pathname === '/wait/release') {
        const index = Number(url.searchParams.get('index'));
        assert(held.has(index));
        const entry = held.get(index);
        if (automatic && index === 0)
          json(entry.response, { error: 'Delayed transport timeout' }, 504);
        else json(entry.response, entry.receipt);
        held.delete(index);
        released.add(index);
        report.releases.push(index);
        await save();
        return json(response, { ok: true });
      }
      let body, sendIndex;
      if (request.method === 'POST') {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        body = Buffer.concat(chunks);
        assert(
          ['/v1/threads/read', ...(!recovery ? ['/v1/replies'] : [])].includes(url.pathname),
          'Only native read acknowledgement and the original two inputs are allowed',
        );
        if (url.pathname === '/v1/replies') {
          const reply = JSON.parse(body);
          assert.equal(reply.hostId, service.session.hostId);
          assert.equal(reply.threadId, report.threadId);
          assert.equal(reply.effort, 'xhigh');
          assert.equal(report.sends.length < 2, true);
          sendIndex = report.sends.length;
          report.sends.push(reply);
          report.modelInputsSubmittedThisRun++;
          await save();
        }
      }
      const upstream = await fetch(backend + request.url, {
        method: request.method,
        headers: {
          Authorization: 'Bearer ' + token,
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body,
      });
      const value = await upstream.json();
      if (automatic && url.pathname === '/v1/workspace' && upstream.ok) {
        // The native receipt test starts from an exact zero-Turn owner. Such a
        // thread has no desktop recency yet. Expose only this owned DEV row in
        // the proxy's list; this is not a desktop sidebar parity acceptance.
        assert(value.threads.some((thread) => thread.id === report.threadId));
        value.threads = value.threads.filter((thread) => thread.id === report.threadId);
        value.projects = [];
        value.nextProjectCursor = null;
        value.nextThreadCursor = null;
        delete value.sidebar;
        report.testWorkspacePresentation = 'exact-owned-zero-turn-row';
      }
      if (body && sendIndex !== undefined) {
        assert.equal(upstream.status, 200);
        held.set(sendIndex, { response, receipt: value });
        return;
      }
      if (url.pathname === '/v1/history') hideUnacknowledged(value);
      if (url.pathname === '/v1/events')
        for (const event of value.events ?? []) hideUnacknowledged(event.snapshot);
      if (url.pathname === '/v1/receipts') {
        const id = url.searchParams.get('id');
        report.queries.push(id);
        const index = report.sends.findIndex((reply) => reply.clientMessageId === id);
        if (!recovery && !released.has(index) && !confirmed.has(index)) value.state = 'uncertain';
      }
      return json(response, value, upstream.status);
    } catch (error) {
      report.proxyError = error.message;
      json(response, { error: error.message }, 500);
    }
  });
  await new Promise((done, reject) => {
    proxy.once('error', reject);
    proxy.listen(8816, '127.0.0.1', done);
  });
  await save();
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  child = spawn(
    'xcodebuild',
    [
      '-project',
      join(directory, 'native/CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + process.env.IOS_SIMULATOR_ID,
      '-derivedDataPath',
      join(directory, 'build'),
      '-resultBundlePath',
      join(directory, 'native.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'ONLY_ACTIVE_ARCH=YES',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      '-only-testing:CodexManageUITests/CodexManageUITests/' +
        (recovery
          ? 'testReplyWaitReceiptReadOnlyRecoveryDEV'
          : automatic
            ? 'testReplyWaitRecoversOmittedReceiptsAutomatically'
            : 'testReplyWaitDetachesWithoutBlockingOrReplaying'),
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  assert.equal(code, 0);
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(directory, 'native.xcresult'),
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  for (let i = 0; i < 90; i++) {
    if (
      (await service.session.open(report.threadId)).runtime.receipts.filter(
        (r) => r.state === 'completed',
      ).length === 2
    )
      break;
    await delay(500);
  }
  const owner = await witness.completeHistory(report.threadId);
  const turns = desktopTurns(owner);
  assert(witness.hasOwner(report.threadId));
  assert.equal(turns.length, 2);
  for (const [index, turn] of turns.entries()) {
    assert.equal(turn.status, 'completed');
    assert.equal(
      turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
      'xhigh',
    );
    assert.equal(turn.items.filter((i) => i.type === 'userMessage').length, 1);
    assert(
      turn.items.some(
        (i) =>
          i.type === 'agentMessage' &&
          i.text === 'REPLY_WAIT_' + nonce + (index === 0 ? '_FIRST' : '_SECOND'),
      ),
    );
  }
  assert.equal(report.sends.length, 2);
  assert.equal(report.proxyError, undefined);
  assert(report.queries.includes(report.sends[0].clientMessageId));
  if (automatic) {
    assert(confirmed.has(0) && confirmed.has(1));
    assert.deepEqual(report.releases, [0, 1]);
    assert(report.queries.includes(report.sends[1].clientMessageId));
    report.automaticRecovery = true;
  }
  Object.assign(report, {
    status: 'passed',
    realRuntimeVerified: true,
    nativePassed: summary.passedTests,
    ownerTurnIds: turns.map((t) => t.turnId ?? t.id),
    uniqueModelInputs: 2,
    replayedInputs: 0,
  });
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  child?.kill('SIGTERM');
  proxy?.closeAllConnections();
  if (proxy) await new Promise((done) => proxy.close(done));
  await service?.close();
  witness.close();
  await save();
  console.log(
    JSON.stringify({
      directory,
      status: report.status,
      realRuntimeVerified: report.realRuntimeVerified ?? false,
    }),
  );
}
