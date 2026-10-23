import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';

const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Use a dedicated local DEV simulator');
const output = process.env.UNREAD_ACCEPTANCE_OUTPUT ?? '.local/unread-native-' + Date.now();
await mkdir(output, { recursive: true, mode: 0o700 });
const token = randomBytes(32).toString('base64url');
const endpoint = 'http://127.0.0.1:8804',
  hostId = 'reading-fixture-host';
const target = {
  agentId: 'codex',
  hostId,
  workspaceId: hostId,
  runtimeId: 'desktop-runtime',
  account: { authority: 'fixture', subject: 'account-a' },
};
let revision = 1,
  bodyRevision = 1,
  compactHistory = false,
  legacy = false,
  sequence = 1,
  acknowledgements = 0,
  acknowledgementAttempts = 0,
  rejectAck = false,
  desktopUnread = true;
const readers = new Map();
const reply = (version = revision) => ({
  id: 'reading-reply',
  role: 'assistant',
  text: 'MOBILE_READ_' + version,
  status: 'completed',
  files: [],
});
const head = () => {
  const value = reply(),
    hash = createHash('sha256');
  for (const text of [value.id, value.text, value.status])
    hash.update(Buffer.byteLength(text) + ':' + text);
  return { revision, messageId: value.id, contentVersion: hash.digest('hex') };
};
const thread = (readerId) => ({
  id: 'reading-target',
  hostId,
  projectId: 'reading-project',
  title: 'Reading target',
  state: 'idle',
  hasUnreadTurn: desktopUnread,
  ...(!legacy
    ? {
        readState: {
          version: 1,
          latest: head(),
          readerId,
          ...(readers.has(readerId) ? { read: readers.get(readerId) } : {}),
        },
      }
    : {}),
});
const anchor = {
  id: 'reading-anchor',
  hostId,
  projectId: 'reading-project',
  title: 'Reading anchor',
  state: 'idle',
  hasUnreadTurn: false,
};
const history = (id, readerId) => ({
  hostId,
  threadId: id,
  readTarget: target,
  thread: id === anchor.id ? anchor : thread(readerId),
  nextCursor: null,
  messages:
    id === anchor.id
      ? [
          {
            id: 'anchor-reply',
            role: 'assistant',
            text: 'READING_ANCHOR',
            status: 'completed',
            files: [],
          },
        ]
      : [
          ...Array.from({ length: compactHistory ? 0 : 28 }, (_, index) => ({
            id: 'old-' + index,
            role: index % 2 ? 'assistant' : 'user',
            text: 'Earlier reading message ' + index + '\n' + 'Previous content. '.repeat(14),
            status: 'completed',
            files: [],
          })),
          reply(bodyRevision),
        ],
  runtime: {
    state: 'idle',
    turnId: null,
    plan: [],
    approvals: [],
    receipts: [],
    canControl: false,
  },
  epoch: 'reading-fixture',
  cursor: sequence,
});
const json = (response, status, value) => {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/reset') {
    revision = sequence = 1;
    bodyRevision = 1;
    compactHistory = url.searchParams.get('compact') === '1';
    legacy = url.searchParams.get('legacy') === '1';
    acknowledgements = 0;
    acknowledgementAttempts = 0;
    rejectAck = false;
    desktopUnread = true;
    target.account.subject = 'account-a';
    readers.clear();
    return json(response, 200, {
      endpoint,
      token,
      hostId,
      threadId: 'reading-target',
      threadTitle: 'Reading target',
      nonce: 'reading',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  }
  if (request.headers.authorization !== 'Bearer ' + token) return json(response, 401, {});
  if (url.pathname === '/next') {
    revision++;
    if (url.searchParams.get('hold') !== '1') bodyRevision = revision;
    sequence++;
    return json(response, 200, { revision });
  }
  if (url.pathname === '/release-body') {
    bodyRevision = revision;
    sequence++;
    return json(response, 200, { revision });
  }
  if (url.pathname === '/ack-failure') {
    rejectAck = url.searchParams.get('enabled') === '1';
    return json(response, 200, {});
  }
  if (url.pathname === '/desktop-read') {
    desktopUnread = url.searchParams.get('unread') === '1';
    sequence++;
    return json(response, 200, { desktopUnread });
  }
  if (url.pathname === '/account') {
    target.account.subject = url.searchParams.get('subject');
    assert(['account-a', 'account-b'].includes(target.account.subject));
    sequence++;
    return json(response, 200, { account: target.account.subject });
  }
  if (url.pathname === '/results')
    return json(response, 200, {
      acknowledgements,
      acknowledgementAttempts,
      desktopUnread,
      revision,
    });
  const readerId = url.searchParams.get('readerId');
  if (url.pathname === '/v1/workspace')
    return json(response, 200, {
      hostId,
      name: 'Reading DEV',
      connectionMode: 'desktop-runtime',
      clientType: { id: 'codex', name: 'Codex' },
      readTarget: target,
      capabilities: [
        'projects:read',
        'threads:read',
        'history:read',
        'events',
        ...(!legacy ? ['threads:read-state'] : []),
      ],
      projects: [{ id: 'reading-project', hostId, name: 'Reading project', roots: [] }],
      threads: [anchor, thread(readerId)],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/history')
    return json(response, 200, history(url.searchParams.get('threadId'), readerId));
  if (url.pathname === '/v1/events') {
    await new Promise((done) => setTimeout(done, 750));
    if (response.destroyed) return;
    const id = url.searchParams.get('threadId');
    return json(response, 200, {
      hostId,
      epoch: 'reading-fixture',
      cursor: sequence,
      reset: url.searchParams.get('epoch') !== 'reading-fixture',
      ready: true,
      events:
        Number(url.searchParams.get('after')) < sequence ||
        url.searchParams.get('epoch') !== 'reading-fixture'
          ? [{ seq: sequence, threadId: id, kind: 'resync', snapshot: history(id, readerId) }]
          : [],
    });
  }
  if (url.pathname === '/v1/threads/read') {
    let body = '';
    for await (const part of request) body += part;
    const value = JSON.parse(body);
    assert.equal(value.hostId, hostId);
    assert.equal(value.threadId, 'reading-target');
    assert.deepEqual(value.target, target);
    acknowledgementAttempts++;
    if (rejectAck) return json(response, 503, { error: 'DEV acknowledgement offline' });
    if (
      JSON.stringify(value.position) !== JSON.stringify(head()) &&
      (value.position.revision !== revision ||
        value.position.contentVersion !== head().contentVersion)
    )
      return json(response, 409, { error: 'new reply' });
    if (readers.get(value.readerId)?.revision !== revision) {
      acknowledgements++;
      readers.set(value.readerId, head());
    }
    return json(response, 200, {
      hostId,
      threadId: value.threadId,
      target,
      readerId: value.readerId,
      read: head(),
    });
  }
  return json(response, 404, { error: 'Unknown fixture operation' });
});
await new Promise((done) => server.listen(8804, '127.0.0.1', done));
const resultBundle = output + '/native.xcresult';
const tests = (
  process.env.UNREAD_IOS_TESTS ??
  'testMobileReadingClearsAndSurvivesRefresh,testMobileReadingWaitsForMatchingBodyAndShortChatLayout,testMobileReadingLegacyConnectionClearsDot'
).split(',');
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
    process.env.IOS_DERIVED_DATA_PATH ?? output + '/build',
    '-resultBundlePath',
    resultBundle,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    ...tests.map((test) => '-only-testing:CodexManageUITests/CodexManageUITests/' + test),
    'test',
  ],
  { stdio: 'inherit' },
);
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        resultBundle,
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  await writeFile(
    output + '/summary.json',
    JSON.stringify(
      {
        code,
        passedTests: summary.passedTests,
        failedTests: summary.failedTests,
        skippedTests: summary.skippedTests,
        acknowledgements,
        desktopUnread,
      },
      null,
      2,
    ),
  );
  assert.equal(code, 0);
  assert.equal(summary.passedTests, tests.length);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
} finally {
  server.closeAllConnections();
  server.close();
}
