import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';

// Native regression with real HTTP responses deliberately held across two sends.
// Synthetic snapshots are auxiliary evidence, never a real desktop/model proof.
assert(process.env.IOS_SIMULATOR_ID, 'Select a dedicated simulator');
const directory = resolve(
  process.env.REPLY_WAIT_ACCEPTANCE_OUTPUT ?? '.local/reply-wait-fixture/' + randomUUID(),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostId = 'reply-wait-fixture',
  threadId = 'reply-wait-thread';
const token = randomBytes(32).toString('base64url');
const scheduling = process.env.REPLY_WAIT_SCHEDULING === '1';
const port = scheduling ? 18816 : 8816;
const endpoint = 'http://127.0.0.1:' + port;
const automatic = scheduling || process.env.REPLY_WAIT_AUTOMATIC === '1';
const stalledHistory = process.env.REPLY_WAIT_STALLED_HISTORY === '1';
const confirmed = new Set();
const heldHistory = new Set();
let historyReleased = false;
let boundary = '';
const capabilities = [
  'reply',
  'stop',
  'queue',
  'steer',
  ...(!scheduling ? ['events'] : []),
  'history:read',
];
const sends = [],
  receipts = [],
  held = new Map();
const cursor = 1;
let acceptanceStatePath;
const report = {
  realRuntimeVerified: false,
  queries: [],
  statusQueries: [],
  releases: [],
  sends,
  queriesWhileHistoryHeld: 0,
  boundaryQueries: {},
  eventRequests: 0,
};
const thread = {
  id: threadId,
  hostId,
  title: 'Reply wait fixture',
  projectId: 'wait-project',
  state: 'idle',
};
const snapshot = () => ({
  hostId,
  threadId,
  thread,
  nextCursor: null,
  messages: [
    { id: 'ready', role: 'assistant', text: 'REPLY_WAIT_READY', status: 'completed', files: [] },
  ],
  runtime: {
    state: 'idle',
    turnId: 'old-completed-turn',
    plan: [],
    approvals: [],
    receipts: [],
    canControl: true,
  },
});
const json = (response, value, status = 200) => {
  if (!response.destroyed)
    response
      .writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      .end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  try {
    if (request.headers.origin || request.headers.host !== '127.0.0.1:' + port)
      return json(response, { error: 'Local fixture only' }, 403);
    const url = new URL(request.url, endpoint);
    if (url.pathname === '/')
      return json(response, {
        endpoint,
        token,
        hostId,
        threadId,
        threadTitle: thread.title,
        nonce: stalledHistory ? 'fixture-stalled-history' : 'fixture',
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      });
    assert.equal(request.headers.authorization, 'Bearer ' + token);
    if (url.pathname === '/wait/status')
      return json(response, { ...report, heldHistory: heldHistory.size });
    if (url.pathname === '/wait/checkpoint' && scheduling) {
      boundary = url.searchParams.get('phase');
      assert(['cold', 'foreground', 'manual'].includes(boundary));
      historyReleased = false;
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/release-history' && scheduling) {
      historyReleased = true;
      for (const pending of heldHistory) json(pending, snapshot());
      heldHistory.clear();
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/settled') return json(response, { ok: true });
    if (url.pathname === '/wait/confirm') {
      confirmed.add(Number(url.searchParams.get('index')));
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/upgrade') {
      // The App is terminated first. Seed only this dedicated simulator's
      // Acceptance store with the exact old-version disposition, unchanged ID/body.
      const container = execFileSync(
        'xcrun',
        [
          'simctl',
          'get_app_container',
          process.env.IOS_SIMULATOR_ID,
          'com.codexmanage.ios',
          'data',
        ],
        { encoding: 'utf8' },
      ).trim();
      const path = join(container, 'Library/Application Support/Acceptance/session-state-v1.json');
      acceptanceStatePath = path;
      const saved = JSON.parse(await readFile(path, 'utf8'));
      const entry = Object.entries(saved.detachedReplies).find(([, rows]) =>
        rows.some((r) => r.original.clientMessageId === sends[0].clientMessageId),
      );
      assert(entry);
      const [key, rows] = entry;
      const index = rows.findIndex((r) => r.original.clientMessageId === sends[0].clientMessageId);
      assert(!saved.outbox[key]);
      saved.outbox[key] = rows.splice(index, 1)[0].original;
      saved.detachedReplyWaits = [sends[0].clientMessageId];
      await writeFile(path, JSON.stringify(saved), { mode: 0o600 });
      report.legacyMigrationSeeded = true;
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/release') {
      const index = Number(url.searchParams.get('index'));
      assert(held.has(index));
      if (automatic && !scheduling && index === 0)
        json(held.get(index), { error: 'Delayed transport timeout' }, 504);
      else json(held.get(index), receipts[index]);
      held.delete(index);
      report.releases.push(index);
      if (stalledHistory && index === 1) {
        historyReleased = true;
        for (const pending of heldHistory) json(pending, snapshot());
        heldHistory.clear();
      }
      return json(response, { ok: true });
    }
    if (url.pathname === '/v1/workspace')
      return json(response, {
        hostId,
        name: 'Reply wait fixture',
        connectionMode: 'managed-runtime',
        capabilities,
        clientType: { id: 'codex', name: 'Codex' },
        projects: [{ id: 'wait-project', hostId, name: 'Reply wait', roots: [] }],
        threads: [thread],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.pathname === '/v1/models') return json(response, { models: [] });
    if (url.pathname === '/v1/history') {
      if (((stalledHistory && sends.length > 0) || (scheduling && boundary)) && !historyReleased) {
        heldHistory.add(response);
        response.once('close', () => heldHistory.delete(response));
        return;
      }
      return json(response, snapshot());
    }
    if (url.pathname === '/v1/events') {
      report.eventRequests++;
      assert(!scheduling, 'Receipt scheduling must not require events');
      await new Promise((done) => setTimeout(done, 1000));
      return json(response, {
        hostId,
        epoch: 'wait',
        cursor,
        ready: true,
        reset: false,
        capabilities,
        events: [],
      });
    }
    if (url.pathname === '/v1/receipts') {
      const id = url.searchParams.get('id');
      report.queries.push(id);
      if (heldHistory.size) {
        report.queriesWhileHistoryHeld++;
        if (scheduling)
          report.boundaryQueries[boundary] = (report.boundaryQueries[boundary] ?? 0) + 1;
      }
      const index = receipts.findIndex((r) => r.clientMessageId === id);
      const receipt = receipts[index];
      if (held.has(index) && (!confirmed.has(index) || (scheduling && !heldHistory.size)))
        return json(response, { ...receipt, state: 'uncertain' });
      if (automatic && !scheduling && index === 0)
        return json(response, { error: 'Primary receipt missing' }, 404);
      if (automatic && !scheduling && index === 2)
        return json(response, { error: 'No receipt' }, 404);
      return json(response, receipt ?? { error: 'No receipt' }, receipt ? 200 : 404);
    }
    if (url.pathname === '/v1/replies/status' && automatic) {
      const id = url.searchParams.get('id');
      report.statusQueries.push(id);
      const index = receipts.findIndex((r) => r.clientMessageId === id);
      return json(response, {
        version: 2,
        hostId,
        threadId,
        clientMessageId: id,
        target: JSON.parse(url.searchParams.get('target')),
        status: index === 0 && confirmed.has(0) ? 'accepted' : 'unavailable',
        capturedTarget: JSON.parse(url.searchParams.get('target')),
        evidence: null,
        fingerprintVersion: null,
        fingerprint: null,
        observedAt: new Date().toISOString(),
        receipt: index === 0 && confirmed.has(0) ? receipts[index] : null,
      });
    }
    if (url.pathname === '/v1/replies' && request.method === 'POST') {
      let body = '';
      for await (const bytes of request) body += bytes;
      const reply = JSON.parse(body);
      assert.equal(reply.hostId, hostId);
      assert.equal(reply.threadId, threadId);
      assert.equal(reply.mode, 'next-turn');
      assert(!sends.some((s) => s.clientMessageId === reply.clientMessageId));
      const index = sends.length;
      sends.push(reply);
      receipts.push({
        ...reply,
        state: automatic ? 'completed' : index === 0 || index === 2 ? 'failed' : 'completed',
        turnId: null,
        error: !automatic && (index === 0 || index === 2) ? 'Submission failed fixture' : null,
      });
      if (index < 2 || scheduling) {
        held.set(index, response);
        return;
      }
      if (automatic && index === 2) return json(response, { error: 'Unknown submission' }, 504);
      return json(response, receipts[index]);
    }
    return json(response, { error: 'Unsupported fixture route' }, 404);
  } catch (error) {
    report.error = error.message;
    json(response, { error: error.message }, 500);
  }
});
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(port, '127.0.0.1', done);
});
const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + process.env.IOS_SIMULATOR_ID,
    '-derivedDataPath',
    resolve(process.env.REPLY_WAIT_BUILD_DIR ?? '.build/ios-reply-wait'),
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
      (scheduling
        ? 'testReplyWaitRecoverySchedulingWithoutEvents'
        : automatic
          ? 'testReplyWaitRecoversOmittedReceiptsAutomatically'
          : 'testReplyWaitDetachesWithoutBlockingOrReplaying'),
    'test',
  ],
  { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
);
for (const stream of [child.stdout, child.stderr])
  createInterface({ input: stream }).on('line', (line) => {
    const safe = line.replaceAll(token, '[REDACTED]');
    log.write(safe + '\n');
    if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
  });
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0);
  assert.equal(sends.length, 3);
  assert.equal(report.error, undefined);
  assert.deepEqual(report.releases, scheduling ? [0, 1, 2] : [0, 1]);
  assert(report.queries.includes(sends[0].clientMessageId));
  if (scheduling) {
    assert.equal(new Set(sends.map((reply) => reply.clientMessageId)).size, 3);
    assert.equal(report.eventRequests, 0);
    for (const phase of ['cold', 'foreground', 'manual'])
      assert(report.boundaryQueries[phase] > 0, phase);
    for (const [index, reply] of sends.entries()) {
      assert(confirmed.has(index));
      assert(report.queries.includes(reply.clientMessageId));
    }
    report.schedulingRecovery = true;
  } else if (!automatic) {
    assert.equal(report.legacyMigrationSeeded, true);
    assert(acceptanceStatePath);
    const restored = JSON.parse(await readFile(acceptanceStatePath, 'utf8'));
    const original = Object.values(restored.detachedReplies)
      .flat()
      .find((r) => r.original.clientMessageId === sends[0].clientMessageId)?.original;
    assert(original);
    for (const key of Object.keys(sends[0]))
      assert.deepEqual(
        original[key],
        sends[0][key],
        'The migrated immutable input changed: ' + key,
      );
    assert(
      !Object.values(restored.outbox).some((r) => r.clientMessageId === original.clientMessageId),
    );
    report.originalIntentPreserved = true;
    report.legacyMigrationRecovered = true;
  } else {
    assert(confirmed.has(0) && confirmed.has(1));
    assert(report.queries.includes(sends[1].clientMessageId));
    assert(report.queries.includes(sends[2].clientMessageId));
    assert(report.statusQueries.includes(sends[0].clientMessageId));
    report.automaticRecovery = true;
    if (stalledHistory) assert(report.queriesWhileHistoryHeld > 0);
  }
  report.status = 'passed';
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  await new Promise((done) => log.end(done));
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(
    JSON.stringify({ directory, status: report.status ?? 'failed', realRuntimeVerified: false }),
  );
}
