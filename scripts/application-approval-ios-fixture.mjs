import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { approvalRequest } from '../packages/codex-adapter/dist/approval-requests.js';

// Synthetic HTTP exercises the real native UI and transport. No desktop/model input.
assert(process.env.IOS_SIMULATOR_ID, 'Select an isolated iOS simulator explicitly');
const directory = resolve(process.env.APPROVAL_RESULT_DIRECTORY ?? '.local/application-approval');
await mkdir(directory, { recursive: true });
const hostId = 'application-approval-fixture';
const threadId = 'application-approval-thread';
const token = randomBytes(32).toString('base64url');
const readTarget = { agentId: 'codex', hostId, workspaceId: hostId, runtimeId: 'desktop-runtime' };
const capabilities = ['reply', 'stop', 'history:read', 'events', 'approvals'];
const cases = [
  ['computer-session', 'accept'],
  ['computer-always', 'acceptAlways'],
  ['computer-decline', 'decline'],
  ['computer-cancel', 'cancel'],
  ['permission-turn', 'accept'],
  ['permission-session', 'acceptForSession'],
  ['unsupported', 'decline'],
];
let scenario = 0,
  cursor = 1,
  resolved = false;
const submissions = [];
const projection = () =>
  approvalRequest(
    cases[scenario][0].startsWith('permission')
      ? 'item/permissions/requestApproval'
      : 'mcpServer/elicitation/request',
    cases[scenario][0].startsWith('permission')
      ? { reason: 'Fixture network access', permissions: { network: { enabled: true } } }
      : {
          mode: 'form',
          message: 'Allow Fixture App?',
          serverName: 'node_repl',
          requestedSchema:
            cases[scenario][0] === 'unsupported'
              ? { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] }
              : { type: 'object', properties: {} },
          _meta: {
            connector_id: 'computer-use',
            persist: ['session', 'always'],
            tool_params: { app: 'Fixture App' },
          },
        },
  );
const thread = () => ({
  id: threadId,
  hostId,
  projectId: 'approval-project',
  title: 'Application approval fixture',
  state: resolved ? 'idle' : 'waiting-for-approval',
});
const snapshot = () => {
  const { responses: _responses, desktopMethod: _method, ...approval } = projection();
  return {
    readTarget,
    hostId,
    threadId,
    thread: thread(),
    nextCursor: null,
    messages: [
      {
        id: 'fixture-answer',
        role: 'assistant',
        text: resolved ? 'APPROVAL_DONE_' + cases[scenario][0] : 'Native approval fixture',
        status: resolved ? 'completed' : 'inProgress',
        files: [],
      },
    ],
    runtime: {
      state: thread().state,
      turnId: resolved ? null : 'turn-' + scenario,
      plan: [],
      receipts: [],
      canControl: true,
      approvals: resolved
        ? []
        : [
            {
              ...approval,
              id: 'approval-' + scenario,
              threadId,
              turnId: 'turn-' + scenario,
              kind: 'approval',
              questions: [],
            },
          ],
    },
  };
};
const json = (response, value, status = 200) => {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    if (request.headers.authorization !== 'Bearer ' + token)
      return json(response, { error: 'Fixture auth required' }, 401);
    if (url.pathname === '/scenario') {
      assert(resolved, 'The previous original approval must finish first');
      assert.equal(Number(url.searchParams.get('value')), scenario + 1);
      scenario++;
      resolved = false;
      cursor++;
      return json(response, { ok: true });
    }
    if (url.pathname === '/v1/workspace')
      return json(response, {
        hostId,
        name: 'Approval fixture',
        connectionMode: 'desktop-runtime',
        readTarget,
        clientType: { id: 'codex', name: 'Codex' },
        capabilities,
        projects: [{ id: 'approval-project', hostId, name: 'Fixture', roots: [] }],
        threads: [thread()],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.pathname === '/v1/models') return json(response, { models: [] });
    if (url.pathname === '/v1/history' || url.pathname === '/v1/events') {
      assert.equal(url.searchParams.get('hostId'), hostId);
      assert.equal(url.searchParams.get('threadId'), threadId);
      assert.equal(request.headers['x-agent-hub-approvals'], 'mcp-v1');
      if (url.pathname === '/v1/history') return json(response, snapshot());
      await new Promise((done) => setTimeout(done, 200));
      return json(response, {
        hostId,
        epoch: 'approval-fixture',
        cursor,
        reset: false,
        ready: true,
        capabilities,
        events:
          cursor > Number(url.searchParams.get('after') ?? -1)
            ? [{ threadId, snapshot: snapshot() }]
            : [],
      });
    }
    if (url.pathname === '/v1/approvals' && request.method === 'POST') {
      let bytes = '';
      for await (const chunk of request) bytes += chunk;
      const body = JSON.parse(bytes);
      assert.equal(request.headers['x-agent-hub-approvals'], 'mcp-v1');
      assert(!resolved, 'No duplicate native response');
      assert.deepEqual(body, {
        hostId,
        threadId,
        approvalId: 'approval-' + scenario,
        turnId: 'turn-' + scenario,
        decision: cases[scenario][1],
        answers: {},
      });
      submissions.push({
        scenario: cases[scenario][0],
        decision: body.decision,
        response: projection().responses[body.decision],
      });
      resolved = true;
      cursor++;
      return json(response, { ok: true });
    }
    return json(response, { error: 'Unsupported fixture route' }, 404);
  } catch (error) {
    console.error(error);
    json(response, { error: error.message }, 500);
  }
});
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', done);
});
const endpoint = 'http://127.0.0.1:' + server.address().port;
const fixture = {
  endpoint,
  token,
  hostId,
  threadId,
  threadTitle: thread().title,
  nonce: 'simulator',
  secondEndpoint: endpoint,
  secondToken: token,
  secondHostId: hostId,
  delayedEndpoint: endpoint,
};
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
    resolve('.build/application-approval'),
    '-resultBundlePath',
    directory + '/native.xcresult',
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testApplicationApprovalsSimulator',
    'test',
  ],
  {
    stdio: 'inherit',
    env: {
      ...process.env,
      TEST_RUNNER_APPLICATION_APPROVAL_SIMULATOR_JSON: JSON.stringify(fixture),
    },
  },
);
try {
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  assert.equal(code, 0);
  assert.equal(submissions.length, cases.length);
  assert.deepEqual(submissions[0].response, {
    action: 'accept',
    content: {},
    _meta: { persist: 'session' },
  });
  assert.deepEqual(submissions[1].response, {
    action: 'accept',
    content: {},
    _meta: { persist: 'always' },
  });
  await writeFile(
    directory + '/report.json',
    JSON.stringify(
      {
        status: 'passed',
        nativeTests: 1,
        scenarios: cases.length,
        realModelInputs: 0,
        realRuntimeVerified: false,
        submissions,
      },
      null,
      2,
    ),
  );
  console.log('Application approvals: seven native simulator scenarios passed');
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
