import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createInterface } from 'node:readline';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { CodexRuntime } from '../packages/codex-adapter/dist/runtime.js';
import { prepareNativeMedia } from './prepare-native-media.mjs';

// Dedicated local native examiner. It never restarts the official desktop or a shared Host.
const executable = process.env.CODEX_EXECUTABLE;
const stateDirectory = resolve(process.env.HOST_AGENT_STATE_DIR);
const device = process.env.IOS_SIMULATOR_ID;
assert.ok(executable && device);
const { hostId } = JSON.parse(await readFile(join(stateDirectory, 'runtime.json'), 'utf8'));
const nonce = randomUUID().slice(0, 8);
const directory = resolve('.local/desktop-prepared-ios-acceptance', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const report = {
  kind: 'real-desktop-prepared-native',
  nonce,
  hostId,
  adapterSha256: preparedAdapterDigest(),
  scenarios: [],
  hostPids: [],
  gates: {},
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'Native independent witness',
);
const tests = (
  process.env.DESKTOP_IOS_TESTS ??
  'testOriginalDesktopReplyProgressStopAndApproval,testOriginalDesktopConversationSwitchAndDraftIsolation,testOriginalDesktopProjectCreation,testOriginalDesktopEmptyAndProjectlessCreation,testOriginalDesktopCreationRestartRecovery,testOriginalDesktopUnloadedOpenAndReply,testOriginalDesktopHostRestartRecovery,testOriginalDesktopPhotoFileUploadAndDownload'
).split(',');
const wait = async (probe, description, timeout = 120000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await probe();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 250));
  }
  throw Error('Timed out: ' + description);
};
let child, endpoint, token, fixture, server;
const request = async (route) => {
  const result = await fetch(endpoint + route, {
    headers: { Authorization: 'Bearer ' + token },
    signal: AbortSignal.timeout(35000),
  });
  if (!result.ok) throw Error('Dedicated Host HTTP ' + result.status);
  return result.json();
};
const records = async () =>
  Object.values(
    JSON.parse(await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8')),
  );
const launch = async () => {
  child = spawn(process.execPath, [resolve('apps/host-agent/dist/index.js')], {
    env: {
      ...process.env,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(stateDirectory, 'bridge.json'),
      HOST_AGENT_STATE_DIR: stateDirectory,
      HOST_AGENT_ROOT: process.cwd(),
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: report.port ? String(report.port) : '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  report.hostPids.push(child.pid);
  child.stderr.resume();
  let address;
  createInterface({ input: child.stdout }).on('line', (line) => {
    try {
      const value = JSON.parse(line);
      if (value.service === 'host-agent') address = value.address;
    } catch {
      // Ignore ordinary service diagnostics; only the explicit ready record supplies a port.
    }
  });
  await wait(() => {
    if (child.exitCode !== null) throw Error('Dedicated Host failed');
    return address;
  }, 'Host startup');
  report.port = address.port;
  endpoint = 'http://127.0.0.1:' + report.port;
  token = (await readFile(join(stateDirectory, 'access-token'), 'utf8')).trim();
  await save();
};
const terminate = async (signal = 'SIGTERM') => {
  if (!child || child.exitCode !== null) return;
  const target = child;
  child = undefined;
  await new Promise((done) => {
    target.once('close', done);
    target.kill(signal);
  });
};
const processIds = () => {
  const rows = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
    .split('\n')
    .map((v) => v.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean);
  const main = rows.filter((v) =>
    /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(v[3]),
  );
  return rows
    .filter(
      (v) =>
        main.includes(v) ||
        (main.some((m) => m[1] === v[2]) &&
          v[3].includes('/codex-cli/') &&
          v[3].includes('app-server')),
    )
    .map((v) => [Number(v[1]), Number(v[2])]);
};
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  report.beforeProcesses = processIds();
  const nativeFiles = (await readdir('apps/ios/Sources'))
    .filter((v) => v.endsWith('.swift'))
    .sort();
  const hash = createHash('sha256').update(
    await readFile('apps/ios/CodexManage.xcodeproj/project.pbxproj'),
  );
  for (const file of nativeFiles) hash.update(await readFile('apps/ios/Sources/' + file));
  report.nativeSourceSha256 = hash.digest('hex');
  report.nativeTestSha256 = createHash('sha256')
    .update(await readFile('apps/ios/Tests/CodexManageUITests.swift'))
    .digest('hex');
  await launch();
  const workspace = await request('/v1/workspace');
  const seed = JSON.parse(await readFile(join(stateDirectory, 'report.json'), 'utf8'));
  const threadId = seed.operations[0].threadId;
  const thread = workspace.threads.find((v) => v.id === threadId);
  assert.ok(thread);
  const managed = new CodexRuntime({ mode: 'managed-runtime', executable });
  let creation, empty;
  try {
    await managed.connect();
    for (const kind of ['project', 'empty']) {
      const cwd = join(directory, kind);
      await mkdir(cwd, { recursive: true });
      const value = await managed.call('project/create', {
        name: 'Native DEV ' + kind + ' ' + nonce,
        roots: [{ path: cwd }],
        idempotencyKey: randomUUID(),
      });
      if (kind === 'project') creation = value.project;
      else empty = value.project;
    }
  } finally {
    managed.close();
  }
  const catalog = await witness.reader.desktopWorkspace();
  const switches = catalog.threads
    .filter(
      (v) =>
        v.id !== threadId && /IOS_PROJECT_|IOS_RECOVERY_|IOS_EMPTY_|混合附件原生验收/.test(v.title),
    )
    .slice(0, 3);
  assert.equal(switches.length, 3);
  let unloaded;
  for (const item of catalog.threads.filter((v) =>
    /IOS_OPENED_|手机主动打开已有会话验收|^手机PROJECTLESS新建验收：/.test(v.title),
  )) {
    await witness.ipc.follow(item.id, 1000, true);
    if (!witness.ipc.hasOwner(item.id)) {
      unloaded = item;
      break;
    }
  }
  if (tests.includes('testOriginalDesktopUnloadedOpenAndReply'))
    assert.ok(unloaded, 'An actual unloaded acceptance history is required');
  fixture = {
    endpoint,
    token,
    hostId,
    threadId,
    threadTitle: thread.title,
    nonce,
    creationProjectId: creation.id,
    creationProjectName: creation.name,
    emptyProjectId: empty.id,
    unloadedThreadId: unloaded?.id,
    unloadedThreadTitle: unloaded?.title,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
    switchThreads: switches.map((v) => ({ id: v.id, title: v.title })),
    attachmentMarker: randomUUID().slice(0, 8),
  };
  report.emptyInitialThreadCount = catalog.threads.filter(
    (v) => v.projectId === empty.id || v.projectIds?.includes(empty.id),
  ).length;
  assert.equal(report.emptyInitialThreadCount, 0);
  report.unloadedBefore = Boolean(unloaded);
  server = createServer((req, res) => {
    void (async () => {
      try {
        if (
          req.method !== 'GET' ||
          req.headers.origin ||
          !['127.0.0.1:8795', 'localhost:8795'].includes(req.headers.host)
        ) {
          res.writeHead(403).end();
          return;
        }
        res.setHeader('content-type', 'application/json');
        res.setHeader('cache-control', 'no-store');
        if (req.url === '/') res.end(JSON.stringify(fixture));
        else if (
          req.url === '/creation-status' &&
          tests.includes('testOriginalDesktopCreationRestartRecovery')
        ) {
          const record = (await records()).find((v) => v.text.includes('IOS_RECOVERY_' + nonce));
          res.end(
            JSON.stringify({
              submitted: Boolean(
                record && ['submitted', 'outcome-unknown', 'confirmed'].includes(record.phase),
              ),
            }),
          );
        } else if (
          req.url === '/restart-host' &&
          tests.includes('testOriginalDesktopHostRestartRecovery')
        ) {
          await wait(async () => {
            const record = (await records()).find((v) =>
              v.text.includes('IOS_HOST_RECOVERY_' + nonce),
            );
            if (!record) return false;
            try {
              const entry = JSON.parse(
                await readFile(
                  join(
                    stateDirectory,
                    'desktop-prepared-bridge',
                    record.clientOperationId + '.json',
                  ),
                  'utf8',
                ),
              );
              return entry.phase === 'submitted' && entry.exitCode === 0;
            } catch {
              return false;
            }
          }, 'durable submitted input');
          await terminate('SIGKILL');
          await launch();
          res.end(JSON.stringify({ restarted: true }));
        } else res.writeHead(404).end();
      } catch (error) {
        res.writeHead(500).end(JSON.stringify({ error: error.message }));
      }
    })();
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8795, '127.0.0.1', done);
  });
  await prepareNativeMedia(device, directory, nonce);
  const resultBundle = join(directory, 'native.xcresult');
  report.resultBundle = resultBundle;
  report.testNames = tests;
  await save();
  const native = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      '.build/prepared-native-ios',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      ...tests.map((v) => '-only-testing:CodexManageUITests/CodexManageUITests/' + v),
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  for (const stream of [native.stdout, native.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    native.once('error', reject);
    native.once('exit', done);
  });
  await new Promise((done) => log.end(done));
  const result = JSON.parse(
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
  Object.assign(report, {
    passedTests: result.passedTests,
    failedTests: result.failedTests,
    skippedTests: result.skippedTests,
  });
  await save();
  assert.equal(code, 0);
  assert.equal(result.failedTests + result.skippedTests, 0);
  assert.equal(result.passedTests, tests.length);
  report.creations = [];
  for (const record of (await records()).filter(
    (v) => v.text.includes(nonce) || v.text.includes(fixture.attachmentMarker),
  )) {
    const receipt = await request(
      '/v1/desktop/creations?' + new URLSearchParams({ hostId, id: record.clientOperationId }),
    );
    await witness.ipc.follow(record.threadId, 15000, true);
    const state = witness.ipc.state(record.threadId);
    const turns = desktopTurns(state);
    const messages = turns
      .flatMap((v) => v.items ?? [])
      .filter(
        (v) => v.type === 'userMessage' && v.clientId === 'first-' + record.clientOperationId,
      );
    const entry = JSON.parse(
      await readFile(
        join(stateDirectory, 'desktop-prepared-bridge', record.clientOperationId + '.json'),
        'utf8',
      ),
    );
    const evidence = {
      operationId: record.clientOperationId,
      threadId: record.threadId,
      projectId: record.projectId,
      owner: witness.ipc.hasOwner(record.threadId),
      confirmed: receipt.phase === 'confirmed',
      firstNativeMessages: messages.length,
      preparationExited: entry.exitCode === 0,
      journalPhase: entry.phase,
      noInitializerTurn: turns.length === 1,
      cwd: state.cwd,
      workspaceKind: state.workspaceKind,
    };
    report.creations.push(evidence);
    assert.ok(
      evidence.owner &&
        evidence.confirmed &&
        evidence.preparationExited &&
        evidence.noInitializerTurn,
    );
    assert.equal(messages.length, 1);
    assert.equal(state.workspaceKind, record.projectId ? 'project' : 'projectless');
    if (record.directory) assert.equal(state.cwd, record.directory);
    assert.ok(messages[0].content.some((v) => v.type === 'text' && v.text.startsWith(record.text)));
    const metadata = (
      await witness.reader.read('thread/read', { threadId: record.threadId, includeTurns: false })
    ).thread;
    assert.equal(metadata.projectId ?? null, record.projectId);
  }
  if (tests.includes('testOriginalDesktopReplyProgressStopAndApproval')) {
    const history = await request('/v1/history?' + new URLSearchParams({ hostId, threadId }));
    report.ownerControls =
      history.runtime.canControl &&
      history.runtime.turnId === null &&
      history.runtime.approvals.length === 0 &&
      history.runtime.receipts.some((v) => v.state === 'interrupted') &&
      ['DESKTOP_IOS_' + nonce, 'DESKTOP_APPROVAL_' + nonce].every((marker) =>
        history.messages.some((v) => v.role === 'assistant' && v.text === marker),
      );
    assert.ok(report.ownerControls);
  }
  if (tests.includes('testOriginalDesktopUnloadedOpenAndReply')) {
    await witness.ipc.follow(unloaded.id, 15000, true);
    report.unloadedOpen =
      witness.ipc.hasOwner(unloaded.id) &&
      desktopTurns(witness.ipc.state(unloaded.id)).some((t) =>
        t.items.some((v) => v.type === 'agentMessage' && v.text === 'IOS_OPENED_' + nonce),
      );
    assert.ok(report.unloadedOpen);
  }
  report.afterProcesses = processIds();
  assert.deepEqual(report.afterProcesses, report.beforeProcesses);
  report.passed = true;
  report.verifiedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({ passed: true, tests: tests.length, report: join(directory, 'report.json') }),
  );
} catch (error) {
  report.passed = false;
  report.error = error.message;
  await save();
  console.log(
    JSON.stringify({ passed: false, error: error.message, report: join(directory, 'report.json') }),
  );
  process.exitCode = 1;
} finally {
  await terminate();
  if (server) {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
  witness.close();
}
