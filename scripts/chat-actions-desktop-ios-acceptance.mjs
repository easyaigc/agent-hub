import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { execFileSync, spawn } from 'node:child_process';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';
import { desktopPinnedSection } from '../packages/codex-adapter/dist/desktop-chat-actions.js';

const device = process.env.IOS_SIMULATOR_ID;
const allowPartial = process.env.CHAT_ACTIONS_ALLOW_PARTIAL === '1';
const activePin = process.env.CHAT_ACTIONS_ACTIVE_PIN === '1';
const activeLifecycle = process.env.CHAT_ACTIONS_ACTIVE_LIFECYCLE === '1';
assert(
  !activeLifecycle || (activePin && allowPartial),
  'Active lifecycle checks require the partial active-owner run',
);
assert(device, 'Choose a dedicated simulator');
const reuseRun = process.env.CHAT_ACTIONS_REUSE_RUN;
if (reuseRun)
  assert(/^[a-f0-9]{8}$/.test(reuseRun), 'Reuse only an exact dedicated acceptance run');
const nonce = reuseRun ?? randomUUID().slice(0, 8);
const directory = resolve('.local/chat-actions-dev', nonce);
const outputDirectory = reuseRun ? join(directory, 'reuses', randomUUID().slice(0, 8)) : directory;
await mkdir(outputDirectory, { recursive: true, mode: 0o700 });
let hostId = randomUUID(),
  reusedThreadId = '';
if (reuseRun) {
  const previous = JSON.parse(await readFile(join(directory, 'report.json'), 'utf8'));
  const state = JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8'));
  const bridge = JSON.parse(await readFile(join(directory, 'bridge.json'), 'utf8'));
  const creations = Object.values(
    JSON.parse(await readFile(join(directory, 'desktop-creations/creations.json'), 'utf8')),
  ).filter((r) => r.text.startsWith('CHAT_ACTIONS_' + nonce + ':'));
  assert.equal(creations.length, 1);
  const original = creations[0];
  assert.equal(original.phase, 'confirmed');
  assert(original.threadId);
  assert.equal(previous.threadId, original.threadId);
  assert(previous.scenarios.some((r) => r.phase === 'model' && r.passed));
  hostId = original.hostId;
  reusedThreadId = original.threadId;
  assert.equal(state.hostId, hostId);
  assert.equal(bridge.hostId, hostId);
  for (const name of await readdir(join(directory, 'chat-actions'))) {
    if (!name.endsWith('.json')) continue;
    const receipt = JSON.parse(await readFile(join(directory, 'chat-actions', name), 'utf8'));
    assert(
      !['submitted', 'outcome-unknown'].includes(receipt.phase),
      'Query the original unknown operation; do not restart acceptance',
    );
  }
} else {
  await writeFile(
    join(directory, 'runtime.json'),
    JSON.stringify({
      hostId,
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
    }),
    { mode: 0o600 },
  );
  await writeFile(join(directory, 'bridge.json'), JSON.stringify({ hostId }), { mode: 0o600 });
}
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(directory, 'bridge.json'),
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Chat Actions DEV ' + nonce,
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
  HOST_AGENT_DISCOVERY: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const service = await startRuntimeServer();
const endpoint = 'http://127.0.0.1:' + service.server.address().port;
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const reader = new CodexHistoryReader(
  hostId,
  'Independent chat witness',
  process.env.CODEX_EXECUTABLE,
  join(homedir(), '.codex/.codex-global-state.json'),
);
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const report = {
  kind: 'real-electron-native-chat-actions',
  nonce,
  hostId,
  device,
  reusedModelInput: !!reuseRun,
  status: 'running',
  desktopVersion: execFileSync(
    '/usr/libexec/PlistBuddy',
    ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
    { encoding: 'utf8' },
  ).trim(),
  scenarios: [],
  source: {},
};
report.gitHead = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const sourceFiles = execFileSync(
  'git',
  [
    'ls-files',
    '-z',
    'apps/host-agent/src',
    'apps/ios/Sources',
    'apps/ios/Tests',
    'apps/ios/Resources',
    'packages/codex-adapter/src',
    'packages/protocol/src',
    'scripts/chat-actions-desktop-ios-acceptance.mjs',
    'package.json',
    'pnpm-lock.yaml',
  ],
  { encoding: 'utf8' },
)
  .split('\0')
  .filter(Boolean);
for (const file of sourceFiles)
  report.source[file] = createHash('sha256')
    .update(await readFile(file))
    .digest('hex');
const save = () =>
  writeFile(join(outputDirectory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
let test, metadata;
try {
  await save();
  await ipc.connect();
  await reader.connect();
  const reusedTitle = reusedThreadId
    ? (await reader.desktopWorkspace()).threads.find((thread) => thread.id === reusedThreadId)
        ?.title
    : '';
  assert(
    !reusedThreadId || reusedTitle,
    'Reuse only a thread still present in the official catalog',
  );
  const fixture = {
    endpoint,
    token,
    hostId,
    threadId: reusedThreadId,
    threadTitle: reusedTitle ?? '',
    chatActionsPartial: allowPartial,
    chatActionsActivePin: activePin,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  metadata = createServer(async (req, res) => {
    try {
      if (req.headers.origin || req.method !== 'GET') throw Error('Read only');
      const url = new URL(req.url, 'http://localhost');
      let result;
      if (url.pathname === '/') result = fixture;
      else if (url.pathname === '/witness') {
        const state = JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8'));
        const creations = Object.values(
          JSON.parse(await readFile(join(directory, 'desktop-creations/creations.json'), 'utf8')),
        ).filter((r) => r.text.startsWith('CHAT_ACTIONS_' + nonce + ':'));
        assert.equal(creations.length, 1, 'Only this immutable test creation may be examined');
        const id = creations[0].threadId;
        assert(id && state.threads.some((t) => t.id === id));
        const phase = url.searchParams.get('phase');
        if (['active-pin', 'active-unpin'].includes(phase)) {
          assert(activePin);
          const owner = await ipc.follow(id, 5000, true);
          assert(owner && owner.id === id);
          const turns = desktopTurns(owner);
          assert.equal(turns.length, 1);
          assert.equal(turns[0].status, 'inProgress');
          assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
          assert.equal(owner.latestReasoningEffort, 'xhigh');
          report.threadId = id;
          const turnId = turns[0].turnId ?? turns[0].id;
          assert.equal(typeof turnId, 'string');
          if (report.activeTurnId) assert.equal(turnId, report.activeTurnId);
          report.activeTurnId = turnId;
          await writeFile(join(outputDirectory, phase + '-owner.json'), JSON.stringify(owner), {
            mode: 0o600,
          });
        }
        if (phase === 'model') {
          const owner = await ipc.follow(id, 5000, true);
          assert(owner && owner.id === id && owner.hostId === 'local');
          const turns = desktopTurns(owner),
            text = JSON.stringify(turns);
          assert.equal(turns.length, 1);
          assert(text.includes('TOOL_CHAT_ACTIONS_' + nonce));
          const items = turns.flatMap((turn) => turn.items ?? []);
          const commands = items.filter((item) => item.type === 'commandExecution');
          assert.equal(commands.length, 1, 'Exactly one real command is required');
          assert.equal(commands[0].exitCode, 0);
          assert(commands[0].aggregatedOutput.includes('TOOL_CHAT_ACTIONS_' + nonce));
          assert.equal(items.filter((item) => item.type === 'userMessage').length, 1);
          report.toolId = commands[0].id;
          report.toolExitCode = commands[0].exitCode;
          report.userInputCount = 1;
          assert(turns.some((t) => t.status === 'completed'));
          assert.equal(owner.latestReasoningEffort, 'xhigh');
          report.threadId = id;
          report.model = owner.latestModel;
          report.effort = owner.latestReasoningEffort;
          await writeFile(join(outputDirectory, 'independent-owner.json'), JSON.stringify(owner), {
            mode: 0o600,
          });
        } else {
          assert.equal(id, report.threadId, 'The original dedicated chat must remain the target');
          const methodParams = { modelProviders: [], useStateDbOnly: true, limit: 100 };
          const all = async (params) => {
            let cursor,
              rows = [];
            do {
              const p = await reader.read('thread/list', {
                ...methodParams,
                ...params,
                ...(cursor ? { cursor } : {}),
              });
              rows.push(...p.data);
              cursor = p.nextCursor;
            } while (cursor);
            return rows;
          };
          const [active, archived] = await Promise.all([
            all({ archived: false }),
            all({ archived: true }),
          ]);
          const current = [...active, ...archived].find((t) => t.id === id);
          if (['pin', 'active-pin'].includes(phase))
            assert.equal(current.section?.id, desktopPinnedSection);
          if (['unpin', 'active-unpin'].includes(phase))
            assert.equal(current.section ?? null, null);
          if (phase === 'archive') {
            assert(archived.some((t) => t.id === id));
            assert(!active.some((t) => t.id === id));
          }
          if (phase === 'restore') {
            assert(active.some((t) => t.id === id));
            assert(!archived.some((t) => t.id === id));
          }
          if (phase === 'lifecycle-blocked') {
            assert(allowPartial);
            assert(active.some((t) => t.id === id));
            const receipts = [];
            for (const name of await readdir(join(directory, 'chat-actions'))) {
              if (!name.endsWith('.json')) continue;
              const receipt = JSON.parse(
                await readFile(join(directory, 'chat-actions', name), 'utf8'),
              );
              if (receipt.threadId === id && ['archive', 'delete'].includes(receipt.action))
                receipts.push(receipt);
            }
            for (const action of ['archive', 'delete'])
              assert(
                receipts.some(
                  (receipt) => receipt.action === action && receipt.phase === 'rejected',
                ),
              );
            report.lifecycleReceipts = receipts.map(({ action, phase, clientOperationId }) => ({
              action,
              phase,
              operationId: clientOperationId,
            }));
            report.blockers = [
              'Original desktop active writer; no named release/archive/delete IPC available',
            ];
          }
          if (phase === 'delete') {
            assert(!current);
          }
          assert(
            [
              'pin',
              'unpin',
              'archive',
              'restore',
              'delete',
              'lifecycle-blocked',
              'active-pin',
              'active-unpin',
            ].includes(phase),
          );
        }
        report.scenarios.push({ phase, threadId: id, passed: true });
        await save();
        result = { passed: true, threadId: id };
      } else throw Error('Unknown route');
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(result));
    } catch (error) {
      res.writeHead(409);
      res.end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done, reject) => {
    metadata.once('error', reject);
    metadata.listen(8819, '127.0.0.1', done);
  });
  const log = createWriteStream(join(outputDirectory, 'xcode.log'), { mode: 0o600 });
  test = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      '.build/ios',
      '-resultBundlePath',
      join(outputDirectory, 'native.xcresult'),
      '-collect-test-diagnostics',
      'never',
      '-parallel-testing-enabled',
      'NO',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopChatActions',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  test.stdout.pipe(log);
  test.stderr.pipe(log);
  const checkActiveLifecycle = async () => {
    if (!activeLifecycle) return;
    // Wait for both native pin actions to finish so management submissions do
    // not race each other. Only this run's proven, still-running owner is used.
    const deadline = Date.now() + 180_000;
    while (!report.scenarios.some((row) => row.phase === 'active-unpin')) {
      assert(test.exitCode === null && Date.now() < deadline, 'Active owner was not established');
      await new Promise((done) => setTimeout(done, 200));
    }
    const id = report.threadId;
    assert(id && report.activeTurnId);
    report.activeLifecycle = [];
    for (const action of ['archive', 'delete']) {
      const before = await ipc.follow(id, 5000, true);
      assert(before && before.id === id && before.hostId === 'local');
      const turn = desktopTurns(before).at(-1);
      assert.equal(turn.turnId ?? turn.id, report.activeTurnId);
      assert.equal(turn.status, 'inProgress');
      const request = {
        hostId,
        target: service.session.readingTarget(),
        threadId: id,
        clientOperationId: randomUUID(),
        action,
      };
      await writeFile(
        join(outputDirectory, 'active-' + action + '-intent.json'),
        JSON.stringify(request),
        { mode: 0o600 },
      );
      const response = await fetch(endpoint + '/v1/threads/actions', {
        method: 'POST',
        headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
        body: JSON.stringify(request),
        signal: AbortSignal.timeout(25_000),
      });
      const receipt = await response.json();
      await writeFile(
        join(outputDirectory, 'active-' + action + '-receipt.json'),
        JSON.stringify(receipt),
        { mode: 0o600 },
      );
      assert.equal(response.status, 200);
      assert.equal(receipt.clientOperationId, request.clientOperationId);
      assert.equal(
        receipt.phase,
        'rejected',
        'Record actual success or uncertainty; do not replay this operation',
      );
      assert.match(receipt.error, /电脑端拒绝/);
      const after = await ipc.follow(id, 5000, true);
      assert(after && after.id === id && after.hostId === 'local');
      const turns = desktopTurns(after);
      assert.equal(turns.length, 1);
      assert.equal(turns[0].turnId ?? turns[0].id, report.activeTurnId);
      assert.equal(turns[0].status, 'inProgress');
      assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
      assert.equal(after.latestReasoningEffort, 'xhigh');
      await writeFile(
        join(outputDirectory, 'active-' + action + '-owner.json'),
        JSON.stringify(after),
        { mode: 0o600 },
      );
      report.activeLifecycle.push({
        action,
        phase: receipt.phase,
        operationId: request.clientOperationId,
        originalTurnStillRunning: true,
      });
      await save();
    }
  };
  const [code] = await Promise.all([
    new Promise((done, reject) => {
      test.on('error', reject);
      test.on('exit', done);
    }),
    checkActiveLifecycle(),
  ]);
  assert.equal(code, 0, 'Native test failed; see private xcode.log');
  assert.equal(report.scenarios.length, (allowPartial ? 4 : 6) + (activePin ? 2 : 0));
  for (const file of sourceFiles)
    assert.equal(
      createHash('sha256')
        .update(await readFile(file))
        .digest('hex'),
      report.source[file],
      'Source changed during acceptance: ' + file,
    );
  report.status = allowPartial ? 'partial' : 'passed';
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      directory: outputDirectory,
      scenarios: report.scenarios,
    }),
  );
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  await save();
  throw error;
} finally {
  if (test?.exitCode === null) test.kill('SIGTERM');
  metadata?.closeAllConnections();
  metadata?.close();
  ipc.close();
  reader.close();
  await service.close();
}
