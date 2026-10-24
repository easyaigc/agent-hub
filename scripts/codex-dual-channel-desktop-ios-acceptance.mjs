import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve } from 'node:path';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

assert(process.env.IOS_SIMULATOR_ID, 'Set the dedicated IOS_SIMULATOR_ID');
const directory = resolve(
  process.env.DUAL_ACCEPTANCE_DIRECTORY ?? '.local/dual-channel-20261022/real-' + randomUUID(),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const tailscale = '/Applications/Tailscale.app/Contents/MacOS/Tailscale';
const ts = (args) =>
  execFileSync(tailscale, args, {
    env: { ...env, TAILSCALE_BE_CLI: '1' },
    encoding: 'utf8',
    timeout: 30000,
  });
const before = JSON.parse(ts(['serve', 'status', '--json']));
const servePort = Number(process.env.DUAL_SERVE_PORT ?? 18443);
assert(!before.TCP?.[servePort], 'Acceptance Serve port is already occupied');
const dns = JSON.parse(ts(['status', '--json'])).Self.DNSName.replace(/\.$/, '');
const serveEndpoint = `https://${dns}:${servePort}`;
const executable =
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const seed = new RuntimeSession({
  mode: 'desktop-runtime',
  directory,
  root: process.cwd(),
  executable,
});
const hostId = seed.hostId;
seed.close();
const bridge = join(directory, 'bridge.json');
await writeFile(bridge, JSON.stringify({ hostId }), { mode: 0o600 });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE: executable,
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_NAME: 'Dual Channel DEV',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_DISCOVERY: '1',
  HOST_AGENT_DISCOVERY_PORT: '0',
  HOST_AGENT_HTTPS_ENDPOINTS: JSON.stringify([serveEndpoint]),
});
let host,
  reader,
  control,
  installedServe = false,
  failServeAfterWrite = false;
let observed = '',
  stage = 'initial';
const reads = [],
  writes = [];
const tokenPath = join(directory, 'access-token');
const marker = 'DUAL_' + randomUUID().slice(0, 8);
const nonce = randomUUID().slice(0, 8);
let token;
function observe(server, route) {
  server.on('request', (req, res) => {
    if (req.headers.authorization !== 'Bearer ' + token) return;
    const path = new URL(req.url, 'http://localhost').pathname;
    if (req.method === 'POST') writes.push({ route, path, stage });
    res.once('finish', () => {
      if (
        res.statusCode === 200 &&
        req.method === 'GET' &&
        ['/v1/workspace', '/v1/history'].includes(path)
      ) {
        reads.push({ route, path, stage });
        observed = route;
      }
      if (
        route === 'serve' &&
        failServeAfterWrite &&
        req.method === 'POST' &&
        path === '/v1/replies' &&
        res.statusCode === 200
      ) {
        failServeAfterWrite = false;
        stage = 'serve-write-accepted-lan-recovery';
        observed = '';
        host.server.close();
        host.server.closeAllConnections();
      }
    });
  });
}
const stop = async (server) => {
  if (!server.listening) return;
  const closed = new Promise((done) => server.close(done));
  server.closeAllConnections();
  await closed;
};
const listen = async (server, port, bind) => {
  if (server.listening) return;
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(port, bind, done);
  });
};
try {
  host = await startRuntimeServer();
  assert(host.direct);
  token = (await readFile(tokenPath, 'utf8')).trim();
  const mainPort = host.server.address().port,
    directPort = host.direct.server.address().port;
  const fingerprint = host.direct.descriptor().fingerprint;
  observe(host.server, 'serve');
  observe(host.direct.server, 'lan');
  ts(['serve', '--bg', `--https=${servePort}`, `http://127.0.0.1:${mainPort}`]);
  installedServe = true;
  const configured = JSON.parse(ts(['serve', 'status', '--json']));
  assert(
    configured.Web?.[`${dns}:${servePort}`],
    'Actual Serve root must match the tested address',
  );
  const systemRead = await fetch(serveEndpoint + '/v1/workspace', {
    headers: { authorization: 'Bearer ' + token },
    redirect: 'error',
  });
  assert.equal(systemRead.status, 200);
  const workspace = await systemRead.json();
  assert.equal(workspace.hostId, hostId);
  const modelsResponse = await fetch(`http://127.0.0.1:${mainPort}/v1/models?hostId=${hostId}`, {
    headers: { authorization: 'Bearer ' + token },
  });
  assert.equal(modelsResponse.status, 200, 'Real model catalogue must be available');
  const models = await modelsResponse.json();
  const model = models.models.find((m) => m.isDefault) ?? models.models[0];
  const efforts = model.supportedEfforts;
  assert(efforts.includes('xhigh'));
  const fixture = {
    endpoint: serveEndpoint,
    token,
    hostId,
    threadId: '',
    nonce,
    marker,
    secondEndpoint: serveEndpoint,
    secondToken: token,
    secondHostId: hostId,
    delayedEndpoint: serveEndpoint,
    effortPosition: efforts.indexOf('xhigh') / (efforts.length - 1),
  };
  control = createServer(async (req, res) => {
    if (
      req.headers.origin ||
      !['127.0.0.1', '::ffff:127.0.0.1', '::1'].includes(req.socket.remoteAddress)
    ) {
      res.writeHead(403);
      res.end();
      return;
    }
    try {
      const action = new URL(req.url, 'http://localhost').pathname.slice(1);
      let data = {};
      if (req.method === 'GET' && action === '') data = fixture;
      else if (req.method === 'GET' && action === 'state') data = { observed, stage };
      else if (req.method === 'POST') {
        stage = action;
        observed = '';
        if (action === 'serve-off') await stop(host.server);
        else if (action === 'serve-on') await listen(host.server, mainPort, '127.0.0.1');
        else if (action === 'lan-off') await stop(host.direct.server);
        else if (action === 'lan-on') await listen(host.direct.server, directPort, '::');
        else if (action === 'serve-off-after-write') failServeAfterWrite = true;
        else if (action === 'restart') {
          await stop(host.server);
          await stop(host.direct.server);
          host.close();
          process.env.HOST_AGENT_PORT = String(mainPort);
          process.env.HOST_AGENT_DISCOVERY_PORT = String(directPort);
          host = await startRuntimeServer();
          assert.equal(host.direct.descriptor().fingerprint, fingerprint);
          observe(host.server, 'serve');
          observe(host.direct.server, 'lan');
        } else {
          res.writeHead(404);
          res.end();
          return;
        }
      } else {
        res.writeHead(405);
        res.end();
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(data));
    } catch {
      res.writeHead(500);
      res.end('{}');
    }
  });
  await new Promise((done, reject) => {
    control.once('error', reject);
    control.listen(8797, '127.0.0.1', done);
  });
  const resultBundle = join(directory, 'native.xcresult');
  const log = createWriteStream(join(directory, 'native.log'), { mode: 0o600 });
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
      process.env.IOS_DERIVED_DATA_PATH ?? join(directory, 'build'),
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCodexDualChannelRealDEV',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  assert.equal(
    await new Promise((done, reject) => {
      child.once('error', reject);
      child.once('exit', done);
    }),
    0,
    'Native acceptance failed; inspect the private native.log',
  );
  reader = new DesktopRuntime(
    { mode: 'desktop-runtime', directory, executable, root: process.cwd() },
    hostId,
    'Independent Dual Channel Verification',
  );
  await reader.connect();
  const operations = Object.values(
    JSON.parse(await readFile(join(directory, 'desktop-creations/creations.json'), 'utf8')),
  );
  const matches = operations.filter((row) => JSON.stringify(row).includes(marker));
  assert.equal(matches.length, 1);
  const operation = matches[0];
  await reader.ipc.follow(operation.threadId, 15000, true);
  assert(reader.ipc.hasOwner(operation.threadId));
  const turns = desktopTurns(reader.ipc.state(operation.threadId));
  for (const suffix of ['FIRST', 'SECOND']) {
    const matching = turns.filter((turn) => JSON.stringify(turn).includes(marker + '_' + suffix));
    assert.equal(matching.length, 1);
    assert.equal(
      matching[0].items
        .filter((item) => item.type === 'agentMessage')
        .map((item) => item.text ?? '')
        .join('\n')
        .trim(),
      marker + '_' + suffix,
    );
  }
  assert.equal(
    writes.filter((w) => w.path === '/v1/replies').length,
    1,
    'The follow-up must not be replayed across routes',
  );
  const container = execFileSync(
    'xcrun',
    ['simctl', 'get_app_container', process.env.IOS_SIMULATOR_ID, 'com.codexmanage.ios', 'data'],
    { env, encoding: 'utf8' },
  ).trim();
  const saved = JSON.parse(
    await readFile(
      join(container, 'Library/Application Support/Acceptance/session-state-v1.json'),
      'utf8',
    ),
  );
  await writeFile(join(directory, 'saved-state-private.json'), JSON.stringify(saved), {
    mode: 0o600,
  });
  const proof = {
    passed: true,
    hostId,
    threadId: operation.threadId,
    operationId: operation.clientOperationId,
    serveEndpoint,
    privateCertificatePreserved: true,
    modelTurns: 2,
    followupPostCount: 1,
    reads,
    writes,
    resultBundle,
    sourceSha256: createHash('sha256')
      .update(await readFile('apps/ios/Sources/CodexDiscovery.swift'))
      .update(await readFile('apps/ios/Sources/SessionStore.swift'))
      .digest('hex'),
    physicalPhone: 'not-tested',
    otherHost: 'separate-deployment-required',
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(proof, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ passed: true, report: join(directory, 'report.json') }));
} finally {
  reader?.close();
  control?.close();
  if (host) {
    await stop(host.server);
    if (host.direct) await stop(host.direct.server);
    host.close();
  }
  if (installedServe) {
    ts(['serve', `--https=${servePort}`, 'off']);
    assert.deepEqual(
      JSON.parse(ts(['serve', 'status', '--json'])),
      before,
      'Original Serve configuration must be preserved',
    );
  }
  await writeFile(
    join(directory, 'transport-observations.json'),
    JSON.stringify({ reads, writes, stage }, null, 2),
    { mode: 0o600 },
  );
}
