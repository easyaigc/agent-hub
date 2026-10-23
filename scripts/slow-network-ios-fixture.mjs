import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

// Synthetic HTTP impairment, not a claim of cellular/Windows device coverage.
// Actual bytes are paced to 128 kbit/s after 600 ms RTT. Catalog pages add
// 1.2 seconds of server delay; one read can stall beyond URLSession's deadline.
const endpoint = 'http://127.0.0.1:8897';
const hostId = 'slow-network-host',
  threadId = 'slow-network-thread';
const token = randomBytes(32).toString('base64url');
let mode, requests, stallNext, dropNext, historyInFlight, maxHistoryInFlight;
let firstWorkspace, firstHistory, stalls, drops;
let offline = false;
const completedRuns = [];
const reset = (value) => {
  if (requests?.length) completedRuns.push({ mode, ...stats() });
  mode = value;
  requests = [];
  stallNext = dropNext = false;
  historyInFlight = maxHistoryInFlight = stalls = drops = 0;
  firstWorkspace = firstHistory = undefined;
  offline = false;
};
const thread = { id: threadId, hostId, projectId: 'project', title: 'Slow network', state: 'idle' };
const snapshot = () => ({
  hostId,
  threadId,
  thread,
  nextCursor: null,
  messages: [{ id: 'answer', role: 'assistant', text: 'SLOW_NETWORK_HISTORY', files: [] }],
  runtime: {
    state: 'idle',
    turnId: null,
    plan: [],
    approvals: [],
    receipts: [],
    canControl: false,
  },
});
const stats = () => ({
  requests,
  firstHistoryDelayMS: firstHistory - firstWorkspace,
  maxHistoryInFlight,
  stalls,
  drops,
  historyInFlight,
});
reset('pagination');
const immediate = (res, value) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(value));
};
const paced = async (res, value) => {
  await delay(600);
  if (res.destroyed) return;
  const bytes = Buffer.from(JSON.stringify(value));
  res.writeHead(200, { 'content-type': 'application/json', 'content-length': bytes.length });
  for (let i = 0; i < bytes.length && !res.destroyed; i += 512) {
    res.write(bytes.subarray(i, i + 512));
    await delay(32);
  }
  if (!res.destroyed) res.end();
};
const server = createServer(async (req, res) => {
  const url = new URL(req.url, endpoint);
  if (url.pathname === '/reset') {
    reset(url.searchParams.get('mode') ?? 'pagination');
    return immediate(res, {
      endpoint,
      token,
      hostId,
      threadId,
      nonce: 'slow-network',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  }
  if (url.pathname === '/stats') return immediate(res, stats());
  if (url.pathname === '/stall') {
    stallNext = true;
    return immediate(res, {});
  }
  if (url.pathname === '/drop') {
    dropNext = true;
    return immediate(res, {});
  }
  if (url.pathname === '/recover') {
    stallNext = false;
    offline = false;
    return immediate(res, {});
  }
  if (url.pathname === '/offline') {
    offline = true;
    return immediate(res, {});
  }
  assert.equal(req.method, 'GET', 'Read-only recovery must never submit or replay a command');
  if (req.headers.authorization !== 'Bearer ' + token) {
    res.writeHead(401).end();
    return;
  }
  requests.push({
    path: url.pathname,
    cursor: url.searchParams.get('threadCursor'),
    at: Date.now(),
  });
  if (offline) {
    await delay(600);
    res
      .writeHead(503, { 'content-type': 'application/json' })
      .end('{"error":"Synthetic network unavailable"}');
    return;
  }
  if (url.pathname === '/v1/workspace') {
    firstWorkspace ??= Date.now();
    const page = Number(url.searchParams.get('threadCursor') ?? 0);
    if (mode === 'pagination') await delay(1200);
    return paced(res, {
      hostId,
      name: 'Slow Windows fixture',
      connectionMode: 'desktop-runtime',
      capabilities: ['projects:read', 'threads:read', 'history:read', 'events'],
      projects: [{ id: 'project', hostId, name: 'Slow project', roots: [] }],
      threads: page === 0 ? [thread] : [{ ...thread, id: 'older-' + page, title: 'Older ' + page }],
      nextProjectCursor: null,
      nextThreadCursor: mode === 'pagination' && page < 9 ? String(page + 1) : null,
    });
  }
  assert.equal(url.searchParams.get('hostId'), hostId);
  if (url.pathname === '/v1/history') {
    assert.equal(url.searchParams.get('threadId'), threadId);
    firstHistory ??= Date.now();
    historyInFlight++;
    maxHistoryInFlight = Math.max(maxHistoryInFlight, historyInFlight);
    res.once('close', () => historyInFlight--);
    if (stallNext) {
      stallNext = false;
      stalls++;
      // Headers and body both stall: exercise a genuine native request timeout.
      const timer = setTimeout(() => void paced(res, snapshot()), 42000);
      res.once('close', () => clearTimeout(timer));
      return;
    }
    if (dropNext) {
      dropNext = false;
      drops++;
      await delay(600);
      res.destroy();
      return;
    }
    return paced(res, snapshot());
  }
  if (url.pathname === '/v1/events') {
    assert.equal(url.searchParams.get('threadId'), threadId);
    await delay(800);
    // Independent liveness remains healthy while a history read stalls.
    return paced(res, {
      hostId,
      epoch: 'slow-network',
      cursor: 1,
      reset: false,
      ready: true,
      capabilities: ['projects:read', 'threads:read', 'history:read', 'events'],
      events: [],
    });
  }
  res.writeHead(404).end();
});
await new Promise((done) => server.listen(8897, '127.0.0.1', done));
if (process.argv.includes('--serve')) {
  console.log('Synthetic slow-network fixture listening on loopback:8897');
} else {
  assert(process.env.IOS_SIMULATOR_ID, 'Use a dedicated slow-network simulator');
  const directory = process.env.SLOW_NETWORK_OUTPUT ?? '.local/slow-network';
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const tests = (
    process.env.IOS_TESTS ??
    'testSlowNetworkFirstConversationBeforeCatalog,testSlowNetworkHistoryTimeoutKeepsHealthyConnection'
  ).split(',');
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
      '.build/ios',
      '-resultBundlePath',
      directory + '/result-' + Date.now() + '.xcresult',
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'ONLY_ACTIVE_ARCH=YES',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      ...tests.map((test) => '-only-testing:CodexManageUITests/CodexManageUITests/' + test),
      'test',
    ],
    { stdio: 'inherit', env: process.env },
  );
  try {
    const code = await new Promise((done, reject) => {
      child.once('exit', done);
      child.once('error', reject);
    });
    await writeFile(
      directory + '/report.json',
      JSON.stringify(
        {
          status: code === 0 ? 'passed' : 'failed',
          evidence: 'synthetic-native-iOS; not physical iPhone, Windows or cellular',
          profile: { kbps: 128, rttMS: 600, catalogServerDelayMS: 1200 },
          tests,
          runs: [...completedRuns, { mode, ...stats() }],
        },
        null,
        2,
      ) + '\n',
      { mode: 0o600 },
    );
    assert.equal(code, 0, 'Native slow-network assertions must pass');
  } finally {
    child.kill('SIGTERM');
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
}
