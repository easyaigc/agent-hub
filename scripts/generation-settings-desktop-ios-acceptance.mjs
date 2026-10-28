import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { currentSpeed } from '../packages/codex-adapter/dist/generation-settings.js';
import { verifyIOSResultDevice } from './mobile/ios-result-device.mjs';

// Fresh Host state and native simulator; inputs execute only in the original desktop.
// The metadata endpoint provides credentials, never simulated model responses.
const verifyDirectory = process.env.GENERATION_VERIFY_DIRECTORY;
const priorBytes = verifyDirectory
  ? await readFile(join(resolve(verifyDirectory), 'report.json'))
  : undefined;
const prior = priorBytes ? JSON.parse(priorBytes) : undefined;
if (prior) {
  assert.equal(prior.native?.passed, 1, 'Only verify a completed native test; never replay inputs');
  assert.equal(prior.native.failed, 0);
  assert.equal(prior.native.skipped, 0);
}
const device = prior?.device ?? process.env.IOS_SIMULATOR_ID;
const ultrafast = prior
  ? prior.modelId === 'gpt-6.1-sol'
  : process.env.GENERATION_ULTRAFAST === '1';
const modelId = ultrafast ? 'gpt-6.1-sol' : 'gpt-6-sol';
const expectedSpeeds = ultrafast
  ? ['ultrafast', 'ultrafast', 'fast', 'standard']
  : ['fast', 'fast', 'standard'];
const markers = ultrafast
  ? ['GEN_ULTRA_ONE_', 'GEN_ULTRA_TWO_', 'GEN_FAST_', 'GEN_STANDARD_']
  : ['GEN_FAST_ONE_', 'GEN_FAST_TWO_', 'GEN_STANDARD_'];
assert(device, 'Select a dedicated iOS simulator');
const nonce = prior?.nonce ?? randomUUID().slice(0, 8);
const directory = verifyDirectory
  ? resolve(verifyDirectory)
  : resolve(
      process.env.GENERATION_ACCEPTANCE_DIRECTORY ?? '.local/generation-settings-desktop',
      nonce,
    );
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostId = prior?.hostId ?? randomUUID();
const desktopVersion = execFileSync(
  '/usr/libexec/PlistBuddy',
  ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
  { encoding: 'utf8' },
).trim();
const config = join(directory, 'bridge.json');
if (!prior) {
  await writeFile(config, JSON.stringify({ hostId, desktopVersion, validationOnly: true }), {
    mode: 0o600,
  });
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
}
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Generation DEV',
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_DISCOVERY: '0',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const service = await startRuntimeServer();
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const nativeFiles = [
  'Models.swift',
  'SessionStore.swift',
  'Sheets.swift',
  'RootView.swift',
  'HostService.swift',
];
const digest = createHash('sha256');
for (const name of nativeFiles) digest.update(await readFile(join('apps/ios/Sources', name)));
const processes = () => {
  const rows = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
    .split('\n')
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean);
  const main = rows.filter((row) =>
    /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(row[3]),
  );
  return [
    ...main,
    ...rows.filter(
      (row) =>
        main.some((p) => p[1] === row[2]) &&
        row[3].includes('/codex-cli/') &&
        row[3].includes('app-server'),
    ),
  ].map((row) => [row[1], row[2]]);
};
const evidence = {
  kind: 'real-desktop-generation-settings',
  nonce,
  hostId,
  desktopVersion,
  adapterSha256: preparedAdapterDigest(),
  nativeSha256: digest.digest('hex'),
  beforeProcesses: prior?.beforeProcesses ?? processes(),
  testSourceSha256: createHash('sha256')
    .update(await readFile('apps/ios/Tests/CodexManageUITests.swift'))
    .digest('hex'),
  status: 'running',
  scenarios: [],
  modelId,
  expectedSpeeds,
  device,
  sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  sourceDiffSha256: createHash('sha256')
    .update(
      execFileSync(
        'git',
        [
          'diff',
          '--binary',
          'HEAD',
          '--',
          'apps/ios',
          'packages/codex-adapter',
          'scripts/generation-settings-desktop-ios-acceptance.mjs',
        ],
        { maxBuffer: 16 * 1024 * 1024 },
      ),
    )
    .digest('hex'),
};
const reportPath = join(
  directory,
  prior ? 'verification-' + randomUUID().slice(0, 8) + '.json' : 'report.json',
);
const save = () => writeFile(reportPath, JSON.stringify(evidence, null, 2), { mode: 0o600 });
let metadata, child;
try {
  if (prior) {
    assert.equal(evidence.adapterSha256, prior.adapterSha256, 'Original Adapter must match');
    assert.equal(evidence.nativeSha256, prior.nativeSha256, 'Original native sources must match');
    assert.equal(
      evidence.testSourceSha256,
      prior.testSourceSha256,
      'Original native assertions must match',
    );
    evidence.originalReportSha256 = createHash('sha256').update(priorBytes).digest('hex');
    evidence.readOnlyVerification = true;
  }
  await ipc.connect();
  const workspace = await service.session.workspace();
  const project = workspace.projects.find((p) =>
    p.roots.includes(process.env.GENERATION_PROJECT_ROOT ?? process.cwd()),
  );
  assert(project, 'A real saved project for this checkout is required');
  assert(workspace.capabilities.includes('desktop:creations:testing'));
  assert(workspace.capabilities.includes('generation-speed'));
  const catalog = (await service.session.models()).models;
  assert(
    catalog
      .find((m) => m.id === modelId)
      .supportedSpeeds.includes(ultrafast ? 'ultrafast' : 'fast'),
  );
  evidence.catalog = catalog;
  const resultBundle = prior?.resultBundle ?? join(directory, 'native.xcresult');
  evidence.resultBundle = resultBundle;
  let code = 0;
  if (!prior) {
    metadata = createServer((req, res) => {
      if (
        req.method !== 'GET' ||
        req.url !== '/' ||
        req.headers.origin ||
        !['localhost:8805', '127.0.0.1:8805'].includes(req.headers.host)
      ) {
        res.writeHead(403);
        res.end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(
        JSON.stringify({
          endpoint: `http://127.0.0.1:${service.server.address().port}`,
          token,
          hostId,
          threadId: '',
          threadTitle: '',
          nonce,
          generationUltrafast: ultrafast,
          creationProjectId: project.id,
          creationProjectName: project.name,
          secondEndpoint: '',
          secondToken: '',
          secondHostId: '',
          delayedEndpoint: '',
        }),
      );
    });
    await new Promise((done, reject) => {
      metadata.once('error', reject);
      metadata.listen(8805, '127.0.0.1', done);
    });
    await save();
    child = spawn(
      'xcodebuild',
      [
        '-project',
        'apps/ios/CodexManage.xcodeproj',
        '-scheme',
        'CodexManage',
        '-destination',
        `platform=iOS Simulator,id=${device},arch=${process.arch === 'arm64' ? 'arm64' : 'x86_64'}`,
        '-derivedDataPath',
        '.build/ios-generation-desktop',
        '-resultBundlePath',
        resultBundle,
        '-parallel-testing-enabled',
        'NO',
        '-collect-test-diagnostics',
        'never',
        '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopGenerationPreferences',
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
        const safe = line
          .replaceAll(token, '[REDACTED]')
          .replaceAll(token.slice(0, 16), '[REDACTED]');
        log.write(safe + '\n');
        if (/^Test Case|: error:|^\s*Executed |^\*\* TEST (SUCCEEDED|FAILED)/.test(safe))
          console.log(safe);
      });
    code = await new Promise((done, reject) => {
      child.once('exit', done);
      child.once('error', reject);
    });
    await new Promise((done) => log.end(done));
  }
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
  evidence.native = {
    passed: summary.passedTests,
    failed: summary.failedTests,
    skipped: summary.skippedTests,
  };
  evidence.resultDevices = verifyIOSResultDevice(summary, device);
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  const state = JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8'));
  for (const [index, prefix] of markers.entries()) {
    const marker = prefix + nonce;
    const receipts = state.receipts.filter((r) => r.reply.text.includes(marker));
    assert.equal(receipts.length, 1, 'Exactly one submitted intent');
    const receipt = receipts[0];
    const snapshot = await ipc.follow(receipt.threadId, 5000, true);
    assert(ipc.hasOwner(receipt.threadId));
    assert.equal(snapshot.id, receipt.threadId);
    assert.equal(snapshot.hostId, 'local');
    const turns = desktopTurns(snapshot);
    const matching = turns.filter((t) =>
      (t.items ?? []).some(
        (item) => item.type === 'userMessage' && item.clientId === receipt.clientMessageId,
      ),
    );
    assert.equal(matching.length, 1);
    const turn = matching[0];
    assert.equal(turn.status, 'completed');
    assert(turn.items.some((item) => item.type === 'agentMessage' && item.text.trim() === marker));
    assert.equal(turn.params.model ?? turn.params.collaborationMode?.settings?.model, modelId);
    assert.equal(
      turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
      'low',
    );
    assert.equal(currentSpeed(turn.params.serviceTier), expectedSpeeds[index]);
    assert.equal(turn.params.approvalPolicy, 'never');
    assert.equal(turn.params.permissions, ':danger-full-access');
    assert.equal(
      state.threads.find((t) => t.id === receipt.threadId).projectId,
      index === 1 ? project.id : null,
    );
    const history = await service.session.open(receipt.threadId);
    assert.equal(history.runtime.generationSettings.speed, expectedSpeeds[index]);
    evidence.scenarios.push({
      marker,
      threadId: receipt.threadId,
      turnId: turn.turnId,
      outcome: 'passed',
      speed: expectedSpeeds[index],
      serviceTier: turn.params.serviceTier,
      originalOwner: true,
      exactInputCount: 1,
    });
  }
  evidence.afterProcesses = processes();
  assert.deepEqual(evidence.afterProcesses, evidence.beforeProcesses);
  evidence.status = 'passed';
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  process.exitCode = 1;
} finally {
  child?.kill('SIGTERM');
  metadata?.closeAllConnections();
  if (metadata) await new Promise((done) => metadata.close(done));
  ipc.close();
  service.close();
  await save();
  console.log(
    JSON.stringify({
      status: evidence.status,
      scenarios: evidence.scenarios.length,
      report: reportPath,
      error: evidence.error,
    }),
  );
}
