import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';

const device = process.env.IOS_SIMULATOR_ID;
const basePort = Number(process.env.UNREAD_FIXTURE_PORT ?? 8806);
assert(device, 'Select a dedicated DEV simulator');
const output =
  process.env.UNREAD_ACCEPTANCE_OUTPUT ?? '.local/unread-badge-dev-20261011/multi-host';
await mkdir(output, { recursive: true, mode: 0o700 });
const hosts = ['a', 'b'].map((id, index) => ({
  id: 'badge-host-' + id,
  port: basePort + index,
  token: randomBytes(32).toString('base64url'),
  revision: 1,
  desktopUnread: true,
  readers: new Map(),
  approval: false,
  prefs: new Map(),
  online: true,
}));
const target = (host) => ({
  agentId: 'codex',
  hostId: host.id,
  workspaceId: host.id,
  runtimeId: 'desktop-runtime',
  account: { authority: 'fixture', subject: 'account' },
});
const notificationTarget = (host) => {
  const value = target(host);
  delete value.account;
  return value;
};
const reply = (host) => ({
  id: 'same-reply',
  role: 'assistant',
  text: 'BADGE_REPLY_' + host.id + '_' + host.revision,
  status: 'completed',
  runId: 'run-' + host.revision,
  files: [],
});
const head = (host) => {
  const message = reply(host),
    hash = createHash('sha256');
  for (const text of [message.id, message.text, message.status])
    hash.update(Buffer.byteLength(text) + ':' + text);
  return { revision: host.revision, messageId: message.id, contentVersion: hash.digest('hex') };
};
const thread = (host, readerId) => ({
  id: 'same-thread',
  hostId: host.id,
  projectId: 'project',
  title: 'Badge target',
  state: host.approval ? 'waiting-for-approval' : 'idle',
  hasUnreadTurn: host.desktopUnread,
  readState: {
    version: 1,
    latest: head(host),
    readerId,
    ...(host.readers.has(readerId) ? { read: host.readers.get(readerId) } : {}),
  },
});
const anchor = (host) => ({
  id: 'anchor',
  hostId: host.id,
  projectId: 'project',
  title: 'Badge anchor',
  state: 'idle',
  hasUnreadTurn: false,
});
const history = (host, id, readerId) => ({
  hostId: host.id,
  threadId: id,
  readTarget: target(host),
  thread: id === 'anchor' ? anchor(host) : thread(host, readerId),
  messages:
    id === 'anchor'
      ? [
          {
            id: 'anchor-reply',
            role: 'assistant',
            text: 'BADGE_ANCHOR',
            status: 'completed',
            files: [],
          },
        ]
      : [reply(host)],
  nextCursor: null,
  epoch: 'badge',
  cursor: host.revision + (host.approval ? 1 : 0),
  runtime: {
    state: host.approval && id !== 'anchor' ? 'waiting-for-approval' : 'idle',
    turnId: host.approval && id !== 'anchor' ? 'pending-run' : null,
    plan: [],
    receipts: [],
    canControl: true,
    approvals:
      host.approval && id !== 'anchor'
        ? [
            {
              id: 'approval',
              notificationRequestId: 'approval',
              threadId: id,
              turnId: 'pending-run',
              kind: 'permission',
              title: '待审批事项',
              detail: '查看后仍然等待处理',
              decisions: ['accept', 'decline'],
              questions: [],
            },
          ]
        : [],
  },
});
const notice = (host) => ({
  version: 1,
  id: createHash('sha256')
    .update(host.id + ':approval')
    .digest('hex'),
  target: notificationTarget(host),
  readTarget: target(host),
  kind: 'approval',
  occurredAt: new Date().toISOString(),
  threadId: 'same-thread',
  runId: 'pending-run',
  requestId: 'approval',
});
let historyReads = 0,
  mutations = 0;
const json = (res, status, value) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const servers = hosts.map((host) =>
  createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1:' + host.port);
    if (host === hosts[0] && url.pathname === '/reset') {
      for (const value of hosts) {
        value.revision = 1;
        value.readers.clear();
        value.desktopUnread = true;
        value.approval = false;
        value.prefs.clear();
        value.online = true;
      }
      return json(res, 200, {
        endpoint: 'http://127.0.0.1:' + basePort,
        token: host.token,
        hostId: host.id,
        threadId: 'same-thread',
        threadTitle: 'Badge target',
        nonce: 'badge',
        secondEndpoint: 'http://127.0.0.1:' + (basePort + 1),
        secondToken: hosts[1].token,
        secondHostId: hosts[1].id,
        delayedEndpoint: '',
      });
    }
    if (req.headers.authorization !== 'Bearer ' + host.token) return json(res, 401, {});
    if (url.pathname === '/connection') {
      for (const value of hosts) value.online = url.searchParams.get('online') === '1';
      return json(res, 200, {});
    }
    if (!host.online && url.pathname.startsWith('/v1/')) {
      req.socket.destroy();
      return;
    }
    if (url.pathname === '/desktop-read') {
      for (const value of hosts) value.desktopUnread = false;
      return json(res, 200, {});
    }
    if (url.pathname === '/next') {
      host.revision++;
      return json(res, 200, {});
    }
    if (url.pathname === '/approval') {
      host.approval = true;
      return json(res, 200, {});
    }
    const readerId = url.searchParams.get('readerId');
    if (url.pathname === '/v1/workspace')
      return json(res, 200, {
        hostId: host.id,
        name: host.id,
        connectionMode: 'desktop-runtime',
        clientType: { id: 'codex', name: 'Codex' },
        readTarget: target(host),
        capabilities: [
          'projects:read',
          'threads:read',
          'history:read',
          'events',
          'threads:read-state',
          'approvals',
          'notifications',
        ],
        projects: [{ id: 'project', hostId: host.id, name: 'Badge project', roots: [] }],
        threads: url.searchParams.get('threadCursor') ? [thread(host, readerId)] : [anchor(host)],
        nextProjectCursor: null,
        nextThreadCursor: url.searchParams.get('threadCursor') ? null : 'more',
      });
    if (url.pathname === '/v1/history') {
      historyReads++;
      return json(res, 200, history(host, url.searchParams.get('threadId'), readerId));
    }
    if (url.pathname === '/v1/events') {
      await new Promise((done) => setTimeout(done, 600));
      if (res.destroyed) return;
      const value = history(host, url.searchParams.get('threadId'), readerId);
      return json(res, 200, {
        hostId: host.id,
        epoch: 'badge',
        cursor: value.cursor,
        ready: true,
        reset: url.searchParams.get('epoch') !== 'badge',
        events:
          Number(url.searchParams.get('after')) < value.cursor ||
          url.searchParams.get('epoch') !== 'badge'
            ? [{ threadId: value.threadId, snapshot: value }]
            : [],
      });
    }
    if (url.pathname === '/v1/notifications/events') {
      const event = notice(host);
      return json(res, 200, {
        events: host.approval && url.searchParams.get('after') !== event.id ? [event] : [],
        cursor: host.approval ? event.id : '',
        activeIds: host.approval ? [event.id] : [],
        status: {
          version: 1,
          target: notificationTarget(host),
          state: 'ready',
          delivery: 'foreground-only',
        },
      });
    }
    if (req.method === 'POST') {
      let body = '';
      for await (const part of req) body += part;
      const value = JSON.parse(body);
      if (url.pathname === '/v1/threads/read') {
        assert.deepEqual(value.target, target(host));
        assert.equal(value.position.contentVersion, head(host).contentVersion);
        host.readers.set(value.readerId, head(host));
        return json(res, 200, {
          hostId: host.id,
          threadId: value.threadId,
          readerId: value.readerId,
          target: target(host),
          read: head(host),
        });
      }
      if (url.pathname.startsWith('/v1/notifications/'))
        return json(res, 200, {
          version: 1,
          target: notificationTarget(host),
          state: 'ready',
          delivery: 'foreground-only',
        });
      mutations++;
    }
    return json(res, 404, {});
  }),
);
await Promise.all(
  servers.map(
    (server, i) => new Promise((done) => server.listen(hosts[i].port, '127.0.0.1', done)),
  ),
);
const resultBundle = output + '/native.xcresult';
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
    '-only-testing:CodexManageUITests/CodexManageUITests/testMobileUnreadBadgeAggregatesEveryComputer',
    '-only-testing:CodexManageUITests/CodexManageUITests/testConnectionMethodDropdownShowsOnlySelectedForm',
    'test',
  ],
  {
    stdio: 'inherit',
    env: { ...process.env, TEST_RUNNER_UNREAD_FIXTURE_URL: 'http://127.0.0.1:' + basePort },
  },
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
    output + '/report.json',
    JSON.stringify(
      {
        passed: summary.passedTests,
        failed: summary.failedTests,
        skipped: summary.skippedTests,
        exitCode: code,
        historyReads,
        mutations,
        approvalPreserved: hosts[0].approval,
        sameThreadAcrossHosts: true,
      },
      null,
      2,
    ),
  );
  assert.equal(code, 0);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.passedTests, 2);
  assert.equal(mutations, 0);
  assert(hosts[0].approval);
} finally {
  for (const server of servers) {
    server.closeAllConnections();
    server.close();
  }
}
