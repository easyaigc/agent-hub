import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';

const simulator = process.env.IOS_SIMULATOR_ID;
assert(simulator);
const root = '.local/mobile-acceptance/UM05/sharing-' + Date.now();
await mkdir(root, { recursive: true });
const endpoint = 'http://127.0.0.1:8818',
  hostId = 'sharing-ios-fixture',
  threadId = 'sharing-ios-thread',
  token = randomBytes(32).toString('base64url');
const posts = [],
  errors = [];
let voiceReads = 0;
const thread = {
  id: threadId,
  hostId,
  projectId: null,
  title: 'Sharing native fixture',
  state: 'idle',
};
const json = (res, value, status = 200) =>
  res
    .writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    .end(JSON.stringify(value));
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, endpoint);
    if (url.pathname === '/')
      return json(res, {
        endpoint,
        token,
        hostId,
        threadId,
        nonce: 'sharing-native',
        secondEndpoint: endpoint,
        secondToken: token,
        secondHostId: hostId,
        delayedEndpoint: endpoint,
      });
    if (url.pathname === '/results') return json(res, { posts, voiceReads });
    if (req.headers.authorization !== 'Bearer ' + token)
      return json(res, { error: 'Synthetic auth required' }, 401);
    if (url.pathname === '/v1/workspace')
      return json(res, {
        hostId,
        name: 'Sharing native fixture',
        connectionMode: 'managed-runtime',
        clientType: { id: 'codex', name: 'Codex' },
        capabilities: ['reply', 'stop', 'history:read'],
        projects: [],
        threads: [thread],
      });
    if (url.pathname === '/v1/history')
      return json(res, {
        hostId,
        threadId,
        thread,
        messages: [
          {
            id: 'sharing-answer',
            role: 'assistant',
            text: 'Sharing fixture ready',
            status: 'completed',
            files: [],
          },
        ],
        nextCursor: null,
        runtime: {
          state: 'idle',
          turnId: null,
          canControl: true,
          plan: [],
          approvals: [],
          receipts: [],
          error: null,
        },
      });
    if (url.pathname.startsWith('/v1/voice/')) {
      voiceReads++;
      return json(res, { error: 'Consent must not start voice' }, 500);
    }
    if (req.method === 'POST' && url.pathname === '/v1/replies') {
      let text = '';
      for await (const b of req) {
        text += b;
        if (text.length > 100000) throw Error('Oversized synthetic input');
      }
      const body = JSON.parse(text);
      assert.equal(body.hostId, hostId);
      assert.equal(body.threadId, threadId);
      posts.push(body);
      return json(res, {
        ...body,
        state: 'submitted',
        turnId: 'sharing-fixture-turn',
        error: null,
      });
    }
    return json(res, { error: 'Unknown synthetic route' }, 404);
  } catch (error) {
    errors.push(error.message);
    json(res, { error: error.message }, 500);
  }
});
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(8818, '127.0.0.1', done);
});
const log = createWriteStream(root + '/native.log');
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + simulator,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/mobile/UM05/sharing-ios',
    '-resultBundlePath',
    root + '/native.xcresult',
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ONLY_ACTIVE_ARCH=YES',
    '-only-testing:CodexManageUITests/CodexManageUITests/testAISharingNativePermissionDoesNotReplayAndRevocationPersists',
    'test',
  ],
  { stdio: ['ignore', 'pipe', 'pipe'] },
);
child.stdout.pipe(log, { end: false });
child.stderr.pipe(log, { end: false });
try {
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  assert.equal(code, 0);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].text, '原许可草稿 😀');
  assert.equal(voiceReads, 0);
  assert.deepEqual(errors, []);
  await writeFile(
    root + '/report.json',
    JSON.stringify(
      {
        scope: 'Native iOS privacy UI/Keychain/HTTP synthetic Host, no actual model',
        passed: true,
        posts,
        voiceReads,
        errors,
      },
      null,
      2,
    ),
  );
  console.log(JSON.stringify({ passed: 1, root }));
} catch (error) {
  await writeFile(
    root + '/report.json',
    JSON.stringify({ passed: false, error: error.message, posts, voiceReads, errors }, null, 2),
  );
  throw error;
} finally {
  server.close();
  server.closeAllConnections();
  log.end();
}
