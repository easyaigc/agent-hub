import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { resolve, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// Synthetic native regression: hold a real HTTP history response across one tap.
// Does not execute a model; the separate desktop acceptance covers that boundary.
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Select a dedicated simulator');
const baseline = process.env.COMPOSER_SYNC_BASELINE === '1';
const directory = resolve('.local/composer-sync-fixture/' + Date.now());
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostId = 'composer-slow-sync-host',
  threadId = 'composer-slow-sync-thread';
const token = randomBytes(32).toString('base64url');
const capabilities = [
  'reply',
  'events',
  'history:read',
  'runtime:control-required',
  'desktop:open',
];
let reset = false,
  held = false,
  release;
const report = { baseline, historyReads: 0, desktopOpens: 0, replies: 0, active: false, sends: [] };
const receipts = [];
const thread = {
  id: threadId,
  hostId,
  projectId: 'sync-project',
  title: 'Slow sync fixture',
  state: 'idle',
};
const snapshot = () => ({
  hostId,
  threadId,
  thread,
  nextCursor: null,
  messages: [
    { id: 'initial', role: 'assistant', text: 'SLOW_SYNC_READY', status: 'completed', files: [] },
    ...report.sends.flatMap((text, i) => [
      { id: 'user-' + i, role: 'user', text, files: [] },
      {
        id: 'assistant-' + i,
        role: 'assistant',
        text: 'SLOW_SYNC_DONE_' + (i + 1),
        status: 'completed',
        files: [],
      },
    ]),
  ],
  runtime: { state: 'idle', turnId: null, plan: [], approvals: [], receipts, canControl: true },
});
const json = (res, data) => {
  if (!res.destroyed)
    res
      .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      .end(JSON.stringify(data));
};
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/')
    return json(res, {
      endpoint: 'http://127.0.0.1:8897',
      token,
      hostId,
      threadId,
      threadTitle: thread.title,
      nonce: 'slow-sync',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  if (url.pathname === '/sync/status') return json(res, report);
  if (url.pathname === '/sync/arm') {
    held = true;
    reset = true;
    return json(res, report);
  }
  if (url.pathname === '/sync/release') {
    release?.();
    return json(res, report);
  }
  assert.equal(req.headers.authorization, 'Bearer ' + token);
  await delay(300); // Slow link request/response latency, in addition to held history.
  if (url.pathname === '/v1/workspace')
    return json(res, {
      hostId,
      name: 'Slow sync fixture',
      connectionMode: 'desktop-runtime',
      clientType: { id: 'codex', name: 'Codex' },
      capabilities,
      projects: [{ id: 'sync-project', hostId, name: 'Slow sync', roots: [] }],
      threads: [thread],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/history') {
    report.historyReads++;
    const captured = snapshot();
    if (held) {
      held = false;
      report.active = true;
      await new Promise((done) => {
        const timer = setTimeout(done, 20000);
        release = () => {
          clearTimeout(timer);
          done();
        };
        res.once('close', release);
      });
      report.active = false;
      release = undefined;
    }
    return json(res, captured);
  }
  if (url.pathname === '/v1/events') {
    await delay(500);
    const wasReset = reset;
    reset = false;
    return json(res, {
      hostId,
      epoch: 'slow-sync',
      cursor: report.replies,
      reset: wasReset,
      ready: true,
      capabilities,
      events: wasReset ? [] : [{ threadId, snapshot: snapshot() }],
    });
  }
  if (url.pathname === '/v1/replies') {
    let bytes = '';
    for await (const chunk of req) bytes += chunk;
    const body = JSON.parse(bytes);
    assert.equal(body.hostId, hostId);
    assert.equal(body.threadId, threadId);
    assert(
      !receipts.some((r) => r.clientMessageId === body.clientMessageId),
      'Single tap cannot submit twice',
    );
    report.replies++;
    report.sends.push(body.text);
    report.submittedWhileSyncing = report.active;
    const receipt = {
      hostId,
      threadId,
      clientMessageId: body.clientMessageId,
      state: 'completed',
      turnId: 'turn-' + report.replies,
    };
    receipts.push(receipt);
    return json(res, receipt);
  }
  if (url.pathname === '/v1/desktop/open') {
    report.desktopOpens++;
    let bytes = '';
    for await (const chunk of req) bytes += chunk;
    const body = JSON.parse(bytes);
    return json(res, { ...body, phase: 'opened' });
  }
  res.writeHead(404).end();
});
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(8897, '127.0.0.1', done);
});
const child = spawn(
  'xcodebuild',
  [
    '-project',
    process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + device,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? join(directory, 'build'),
    '-resultBundlePath',
    join(directory, 'result.xcresult'),
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/testSendDuringSlowBackgroundSync',
    'ONLY_ACTIVE_ARCH=YES',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'test',
  ],
  { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
);
const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
for (const stream of [child.stdout, child.stderr])
  createInterface({ input: stream }).on('line', (line) => {
    const safe = line.replaceAll(token, '[REDACTED]').replaceAll(token.slice(0, 16), '[REDACTED]');
    log.write(safe + '\n');
    if (/^Test Case|: error:|^\*\* TEST|SLOW_SYNC_/.test(safe)) console.log(safe);
  });
console.log(JSON.stringify({ directory, baseline, device }));
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0);
  assert.equal(report.replies, baseline ? 0 : 1);
  if (!baseline) {
    assert.equal(report.submittedWhileSyncing, true);
    assert.equal(report.desktopOpens, 0);
  }
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.failure = error.message;
  throw error;
} finally {
  release?.();
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  log.end();
}
