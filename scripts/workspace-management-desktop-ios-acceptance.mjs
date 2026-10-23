import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { readMacDesktopVersion } from '../packages/codex-adapter/dist/desktop-platform.js';

// Real original desktop + native simulator. Only fresh, named fixture IDs are mutated.
const directory = resolve(
  process.env.BULK_ACCEPTANCE_DIRECTORY ?? join('.local/workspace-management-dev', randomUUID()),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const reportPath = join(directory, 'report.json');
let previous;
try {
  previous = JSON.parse(await readFile(reportPath, 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const label = previous?.label ?? 'BULK_DEV_' + randomUUID().slice(0, 8);
const nonce = label.slice('BULK_DEV_'.length);
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const report = previous ?? {
  kind: 'real-desktop-native-workspace-management',
  directory,
  label,
  projects: [],
  threads: [],
  gates: {},
  operations: [],
};
const save = () => writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const delay = (ms) => new Promise((done) => setTimeout(done, ms));
const wait = async (probe, description) => {
  const end = Date.now() + 180_000;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await delay(600);
  }
  throw new Error('Timed out: ' + description);
};
const home = process.env.CODEX_HOME ?? join(homedir(), '.codex');
// Fixture setup is metadata only; the native desktop remains the sole model executor.
const child = spawn(executable, ['app-server', '--stdio'], {
  env: { ...process.env, CODEX_HOME: home },
  stdio: 'pipe',
});
child.stderr.resume();
const exited = new Promise((done) => child.once('exit', done));
let counter = 0;
const pending = new Map();
const allowed = new Set([
  'initialize',
  'project/create',
  'project/list',
  'thread/read',
  'thread/list',
]);
const rpc = (method, params) => {
  assert(allowed.has(method));
  const id = ++counter;
  return new Promise((resolveResult, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('Metadata fixture timed out: ' + method));
    }, 20_000);
    pending.set(id, { resolve: resolveResult, reject, timer });
    child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  });
};
createInterface({ input: child.stdout }).on('line', (line) => {
  try {
    const value = JSON.parse(line),
      call = pending.get(value.id);
    if (!call) return;
    pending.delete(value.id);
    clearTimeout(call.timer);
    if (value.error) call.reject(new Error('Fixture RPC rejected: ' + JSON.stringify(value.error)));
    else call.resolve(value.result);
  } catch (error) {
    for (const call of pending.values()) call.reject(error);
  }
});
let host, fixtureServer, reader;
try {
  await rpc('initialize', {
    clientInfo: { name: 'workspace_management_fixture', version: '0.1.0' },
    capabilities: { experimentalApi: true },
  });
  child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
  for (let i = report.projects.length + 1; i <= 2; i++) {
    const root = join(directory, 'project-' + i);
    await mkdir(root);
    await writeFile(join(root, 'keep.txt'), label + '-file-' + i);
    const created = await rpc('project/create', {
      name: label + '-project-' + i,
      roots: [{ path: root }],
      idempotencyKey: randomUUID(),
    });
    assert.equal(typeof created.project?.id, 'string');
    report.projects.push({ id: created.project.id, root });
    await save();
  }
  const hostDirectory = join(directory, 'host'),
    bridge = join(directory, 'bridge.json');
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    HOST_AGENT_STATE_DIR: hostDirectory,
    HOST_AGENT_NAME: label,
    HOST_AGENT_BIND: '127.0.0.1',
    PORT: '8898',
    HOST_AGENT_PORT: '8898',
    HOST_AGENT_ROOT: directory,
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_TRANSFER_DIR: join(directory, 'received'),
  });
  host = await startRuntimeServer();
  await writeFile(
    bridge,
    JSON.stringify({
      hostId: host.session.hostId,
      desktopVersion: readMacDesktopVersion(),
      validationOnly: false,
    }),
    { mode: 0o600 },
  );
  await host.session.refreshDesktopCreationAvailability();
  await host.session.workspace();
  reader = new DesktopRuntime({ mode: 'desktop-runtime', executable }, host.session.hostId);
  await reader.connect();
  report.hostId = host.session.hostId;
  report.desktopVersion = reader.version;
  for (let i = 0; i < 2; i++) {
    if (process.env.BULK_UNLOADED_ACCEPTANCE === '1') {
      const proof = JSON.parse(
        await readFile(join(directory, 'original-owner-proof.json'), 'utf8'),
      ).proofs.find((proof) => proof.threadId === report.threads[i]?.id);
      assert(
        proof?.owner &&
          proof.completed &&
          proof.effort === 'xhigh' &&
          proof.turnCount === 1 &&
          proof.userInputs === 1,
      );
      report.ownerEvidence = 'reused-original-owner-proof-before-explicit-fixture-unload';
      report.archiveScope = 'unloaded-only';
      continue;
    }
    const existed = report.operations[i] !== undefined;
    const id = report.operations[i] ?? randomUUID(),
      marker = label + '_OK_' + i;
    if (!existed) report.operations.push(id);
    await save();
    if (!existed) {
      host.session.prepareDesktopCreation({
        hostId: host.session.hostId,
        clientOperationId: id,
        projectId: report.projects[i].id,
        text: '这是批量管理专用验收。仅原样回复 ' + marker + '，不要调用工具或修改文件。',
        attachments: [],
        effort: 'xhigh',
      });
      await host.session.submitDesktopCreation(id);
    }
    const result = await wait(async () => {
      const value = await host.session.queryDesktopCreation(id);
      if (value.phase === 'rejected') throw new Error('Creation not confirmed: ' + value.phase);
      return value.phase === 'confirmed' && value.threadId ? value : undefined;
    }, 'fixture desktop creation');
    if (!report.threads.some((thread) => thread.id === result.threadId))
      report.threads.push({ id: result.threadId, marker });
    await save();
    await reader.ipc.follow(result.threadId);
    const turn = await wait(async () => {
      await reader.ipc.follow(result.threadId, 3000, true);
      return desktopTurns(reader.ipc.state(result.threadId) ?? {}).find(
        (turn) =>
          turn.status === 'completed' &&
          turn.items?.some((item) => item.type === 'agentMessage' && item.text.includes(marker)),
      );
    }, 'original model completion');
    assert.equal(turn.items.filter((item) => item.type === 'userMessage').length, 1);
    assert.equal(desktopTurns(reader.ipc.state(result.threadId)).length, 1);
    report.gates['original-owner-' + i] = true;
  }
  // Close setup before mutations so a fresh independent reader verifies persisted results.
  child.stdin.end();
  await exited;
  const token = (await readFile(join(hostDirectory, 'access-token'), 'utf8')).trim();
  const actualPort = host.server.address().port;
  const fixture = {
    connection: {
      endpoint: 'http://127.0.0.1:' + actualPort,
      token,
      hostId: host.session.hostId,
      threadId: report.threads[0].id,
      threadTitle: '',
      nonce,
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    },
    projectIds: report.projects.map((project) => project.id),
    threadIds: report.threads.map((thread) => thread.id),
    search: label,
    projectsRemoved: process.env.BULK_UNLOADED_ACCEPTANCE === '1',
  };
  // The official titles derive from first input and contain the unique fixture label.
  const catalog = await reader.reader.desktopWorkspace();
  for (const thread of report.threads)
    assert(catalog.threads.find((row) => row.id === thread.id)?.title.includes(label));
  fixtureServer = createServer((request, response) => {
    if (request.method !== 'GET' || request.url !== '/' || request.headers.origin) {
      response.writeHead(403);
      response.end();
      return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(fixture));
  });
  await new Promise((done) => fixtureServer.listen(8899, '127.0.0.1', done));
  console.log(
    report.ownerEvidence
      ? 'Unloaded fixture ready; saved original-owner proof reused. Running native simulator tests.'
      : 'Real fixture ready; original owners confirmed. Running native simulator tests.',
  );
  const device = process.env.IOS_SIMULATOR_ID;
  assert(device, 'Set a dedicated simulator ID');
  const output = join(directory, 'native.log');
  const args = [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + device,
    '-derivedDataPath',
    '.build/workspace-management-ios',
    '-resultBundlePath',
    join(directory, 'native-' + Date.now() + '.xcresult'),
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ONLY_ACTIVE_ARCH=YES',
    '-parallel-testing-enabled',
    'NO',
    '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopWorkspaceManagementDEV',
    '-only-testing:CodexManageUITests/CodexManageUITests/testWorkspaceManagementBatchDemo',
    'test',
  ];
  const native = spawn('xcodebuild', args, {
    env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const liveLog = createWriteStream(output, { mode: 0o600 });
  native.stdout.on('data', (data) => {
    liveLog.write(data);
  });
  native.stderr.on('data', (data) => {
    liveLog.write(data);
  });
  const code = await new Promise((done) => native.once('exit', done));
  await new Promise((done) => liveLog.end(done));
  assert.equal(code, 0, 'Native test failed; inspect ' + output);
  report.gates.native = true;
  report.gates['native-demo-batch-cancel-persistence'] = true;
  const final = await reader.reader.desktopWorkspace();
  assert(report.projects.every((project) => !final.projects.some((row) => row.id === project.id)));
  assert(report.threads.every((thread) => !final.threads.some((row) => row.id === thread.id)));
  const archived = await reader.reader.read('thread/list', {
    archived: true,
    limit: 100,
    useStateDbOnly: true,
    modelProviders: [],
  });
  assert(report.threads.every((thread) => archived.data.some((row) => row.id === thread.id)));
  for (const project of report.projects)
    assert.equal(
      await readFile(join(project.root, 'keep.txt'), 'utf8'),
      label + '-file-' + (report.projects.indexOf(project) + 1),
    );
  for (const thread of report.threads) {
    const snapshot = await reader.reader.read('thread/read', {
      threadId: thread.id,
      includeTurns: true,
    });
    assert.equal(snapshot.thread.turns.length, 1);
    assert(
      snapshot.thread.turns[0].items.some(
        (item) => item.type === 'agentMessage' && item.text.includes(thread.marker),
      ),
    );
  }
  report.gates['files-and-original-history-preserved'] = true;
  const state = JSON.parse(await readFile(join(hostDirectory, 'runtime.json'), 'utf8'));
  const allOperations = Object.values(state.workspaceManagement ?? {});
  report.rejectedOperationsRetained = allOperations.filter((operation) =>
    operation.items.some((item) => item.state === 'rejected'),
  ).length;
  const operations = allOperations.filter((operation) =>
    operation.items.every((item) => item.state === 'completed'),
  );
  assert(operations.length >= 2);
  assert(
    operations.every(
      (operation) =>
        operation.items.length === 2 && operation.items.every((item) => item.state === 'completed'),
    ),
  );
  host.close();
  await delay(150);
  host = await startRuntimeServer();
  for (const operation of operations) {
    const restored = await host.session.workspaceManagementStatus(
      operation.clientOperationId,
      operation.target,
    );
    assert(restored.items.every((item) => item.state === 'completed'));
  }
  const restartedWorkspace = await host.session.workspace();
  assert(
    report.threads.every(
      (thread) => !restartedWorkspace.threads.some((row) => row.id === thread.id),
    ),
  );
  report.gates['host-restart-receipts-and-archive-filter'] = true;
  report.gates['restart-no-replay'] = true;
  report.sourceSha256 = Object.fromEntries(
    await Promise.all(
      [
        'apps/ios/Sources/Sheets.swift',
        'apps/ios/Sources/SessionStore.swift',
        'packages/codex-adapter/src/workspace-management.ts',
        'packages/codex-adapter/src/runtime-session.ts',
      ].map(async (file) => [
        file,
        createHash('sha256')
          .update(await readFile(file))
          .digest('hex'),
      ]),
    ),
  );
  delete report.error;
  report.fullRequestedScopeComplete = false;
  report.passed = true;
  await save();
  console.log(JSON.stringify({ passed: true, gates: report.gates, report: reportPath }));
} catch (error) {
  report.passed = false;
  report.error = error.message;
  await save();
  console.error(error.message);
  process.exitCode = 1;
} finally {
  child.stdin.end();
  if (child.exitCode === null) child.kill('SIGTERM');
  reader?.close();
  host?.close();
  fixtureServer?.closeAllConnections();
  await new Promise((done) => (fixtureServer ? fixtureServer.close(done) : done()));
}
