import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { cp, mkdir, readFile, readdir, writeFile, symlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';

// Dedicated native/Host snapshots. The optional baseline is the original compiled
// broker, captured before editing; no changes to the shared Host or desktop.
const device = process.env.IOS_SIMULATOR_ID;
const executable = process.env.CODEX_EXECUTABLE;
assert(device && executable, 'Select a dedicated simulator and the installed official CLI');
const nonce = randomUUID().slice(0, 8);
const directory = resolve('.local/attachment-filename-native', nonce);
const frozen = join(directory, 'frozen');
await mkdir(frozen, { recursive: true, mode: 0o700 });
await cp('packages', join(frozen, 'packages'), { recursive: true, verbatimSymlinks: true });
await cp('apps/host-agent', join(frozen, 'apps/host-agent'), {
  recursive: true,
  verbatimSymlinks: true,
});
await cp('apps/ios', join(directory, 'native'), { recursive: true });
await symlink(resolve('node_modules'), join(frozen, 'node_modules'), 'dir');
const brokerPath = join(frozen, 'packages/codex-adapter/dist/desktop-creation.js');
const fixedBroker = await readFile(brokerPath);
const report = {
  kind: 'real-desktop-native-attachment-filename',
  nonce,
  device,
  status: 'running',
  scenarios: [],
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
report.fixedBrokerSha256 = digest(fixedBroker);
const nativeHash = createHash('sha256');
for (const path of [
  'CodexManage.xcodeproj/project.pbxproj',
  'Tests/CodexManageUITests.swift',
  ...(await readdir(join(directory, 'native/Sources')))
    .filter((name) => name.endsWith('.swift'))
    .sort()
    .map((name) => 'Sources/' + name),
]) {
  nativeHash.update(path).update(await readFile(join(directory, 'native', path)));
}
report.nativeSourceSha256 = nativeHash.digest('hex');
const filename = 'native-long-input-' + '附件文件名长度复现'.repeat(9) + '.md';
report.filename = { characters: filename.length, utf8Bytes: Buffer.byteLength(filename) };
assert(filename.length <= 200 && Buffer.byteLength(filename) > 200);
const run = (command, args) => execFileSync(command, args, { encoding: 'utf8' });
const witness = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let host, metadata, activeToken;
const wait = async (probe, description) => {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const value = await probe();
    if (value) return value;
    await delay(250);
  }
  throw Error('Timed out: ' + description);
};
const stopHost = async () => {
  if (!host || host.exitCode !== null) return;
  const child = host;
  host = undefined;
  await new Promise((done) => {
    child.once('close', done);
    child.kill('SIGTERM');
  });
};
const native = async (action, phase) => {
  const log = createWriteStream(join(directory, phase + '.log'), { mode: 0o600 });
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      join(directory, 'native/CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      join(directory, 'build'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopLongFilenameCreation',
      ...(action === 'test-without-building'
        ? ['-resultBundlePath', join(directory, phase + '.xcresult')]
        : []),
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      action,
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = activeToken ? line.replaceAll(activeToken, '[REDACTED]') : line;
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  assert.equal(code, 0, phase + ' native exit');
  if (action === 'test-without-building') {
    const summary = JSON.parse(
      run('xcrun', [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(directory, phase + '.xcresult'),
        '--format',
        'json',
      ]),
    );
    assert.equal(summary.passedTests, 1);
    assert.equal(summary.failedTests + summary.skippedTests, 0);
  }
};
try {
  await save();
  const devices = JSON.parse(run('xcrun', ['simctl', 'list', 'devices', '--json']));
  const selected = Object.values(devices.devices)
    .flat()
    .find((item) => item.udid === device);
  assert(selected, 'Dedicated simulator must exist');
  if (selected.state !== 'Booted') run('xcrun', ['simctl', 'boot', device]);
  run('xcrun', ['simctl', 'bootstatus', device, '-b']);
  await native('build-for-testing', 'build');
  run('xcrun', [
    'simctl',
    'install',
    device,
    join(directory, 'build/Build/Products/Debug-iphonesimulator/CodexManage.app'),
  ]);
  const container = run('xcrun', [
    'simctl',
    'get_app_container',
    device,
    'com.codexmanage.ios',
    'data',
  ]).trim();
  await mkdir(join(container, 'Documents'), { recursive: true });
  const bytes = Buffer.from('LONG_FILENAME_' + nonce);
  await writeFile(join(container, 'Documents', filename), bytes);
  const phases = process.env.ATTACHMENT_FILENAME_BASELINE ? ['before', 'after'] : ['after'];
  for (const phase of phases) {
    const broker =
      phase === 'before'
        ? await readFile(resolve(process.env.ATTACHMENT_FILENAME_BASELINE))
        : fixedBroker;
    await writeFile(brokerPath, broker);
    const state = join(directory, phase + '-host');
    const hostId = randomUUID();
    await mkdir(state, { recursive: true, mode: 0o700 });
    await writeFile(
      join(state, 'runtime.json'),
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
    await writeFile(join(state, 'bridge.json'), JSON.stringify({ hostId }), { mode: 0o600 });
    const env = {
      ...process.env,
      CODEX_EXECUTABLE: executable,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
      HOST_AGENT_STATE_DIR: state,
      HOST_AGENT_ROOT: process.cwd(),
      HOST_AGENT_NAME: '附件文件名 DEV ' + phase,
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
    };
    for (const key of [
      'CODEX_DESKTOP_CONTROLLER_CONFIG',
      'HOST_AGENT_TOKEN',
      'HOST_NOTIFICATION_RELAY_URL',
      'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
    ])
      delete env[key];
    host = spawn(process.execPath, [join(frozen, 'apps/host-agent/dist/index.js')], {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let port;
    createInterface({ input: host.stdout }).on('line', (line) => {
      try {
        const value = JSON.parse(line);
        if (value.service === 'host-agent') port = value.address.port;
      } catch {
        /* ordinary diagnostics */
      }
    });
    host.stderr.pipe(createWriteStream(join(directory, phase + '-host.log'), { mode: 0o600 }));
    await wait(() => {
      assert.equal(host.exitCode, null);
      return port;
    }, 'isolated Host ready');
    const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
    activeToken = token;
    const endpoint = 'http://127.0.0.1:' + port;
    metadata = createServer((request, response) => {
      if (
        request.method !== 'GET' ||
        request.headers.origin ||
        !['127.0.0.1:8795', 'localhost:8795'].includes(request.headers.host)
      ) {
        response.writeHead(403).end();
        return;
      }
      response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(
        JSON.stringify({
          endpoint,
          token,
          hostId,
          threadId: '',
          threadTitle: '',
          nonce,
          secondEndpoint: endpoint,
          secondToken: token,
          secondHostId: hostId,
          delayedEndpoint: endpoint,
          attachmentMarker: phase === 'before' ? 'expect-rejected' : 'expect-success',
        }),
      );
    });
    await new Promise((done, reject) => {
      metadata.once('error', reject);
      metadata.listen(8795, '127.0.0.1', done);
    });
    await native('test-without-building', phase);
    const records = Object.values(
      JSON.parse(await readFile(join(state, 'desktop-creations/creations.json'), 'utf8')),
    );
    const scenario = {
      phase,
      status: 'passed',
      brokerSha256: digest(broker),
      creations: records.length,
    };
    if (phase === 'before') {
      assert.equal(records.length, 0, 'Invalid manifest must not create or execute a thread');
      scenario.error = '附件清单无效';
      scenario.modelInputs = 0;
    } else {
      assert.equal(records.length, 1);
      const record = records[0];
      assert.equal(record.phase, 'confirmed');
      assert.equal(record.attachments[0].name, filename);
      assert.equal(record.attachments[0].sha256, digest(bytes));
      assert.equal(record.effort, 'xhigh');
      await witness.connect();
      const snapshot = await witness.completeHistory(record.threadId);
      const turns = desktopTurns(snapshot);
      assert.equal(turns.length, 1);
      assert.equal(turns[0].status, 'completed');
      assert.equal(
        turns[0].params.effort ?? turns[0].params.collaborationMode?.settings?.reasoning_effort,
        'xhigh',
      );
      const messages = turns[0].items.filter((item) => item.type === 'userMessage');
      assert.equal(messages.length, 1);
      assert(!record.text.includes(nonce), 'The model must actually read the attachment');
      assert(
        turns[0].items.some(
          (item) => item.type === 'agentMessage' && item.text === bytes.toString(),
        ),
      );
      const preparation = JSON.parse(
        await readFile(
          join(state, 'desktop-prepared-bridge', record.clientOperationId + '.json'),
          'utf8',
        ),
      );
      assert.equal(preparation.exitCode, 0);
      const file = join(
        state,
        'desktop-creations',
        record.clientOperationId,
        record.attachments[0].id,
        'attachment.md',
      );
      assert.equal(digest(await readFile(file)), digest(bytes));
      Object.assign(scenario, {
        threadId: record.threadId,
        operationId: record.clientOperationId,
        modelInputs: messages.length,
        effort: 'xhigh',
        preparationExit: 0,
        attachmentSha256: digest(bytes),
        owner: witness.hasOwner(record.threadId),
      });
      assert(scenario.owner);
    }
    report.scenarios.push(scenario);
    await save();
    console.log(JSON.stringify(scenario));
    metadata.closeAllConnections();
    await new Promise((done) => metadata.close(done));
    metadata = undefined;
    await stopHost();
  }
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  witness.close();
  if (metadata) {
    metadata.closeAllConnections();
    await new Promise((done) => metadata.close(done));
  }
  await stopHost();
  await save();
  console.log(JSON.stringify({ directory, status: report.status }));
}
