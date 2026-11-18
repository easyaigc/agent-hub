import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { request } from 'node:https';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';

const hostId = process.env.IOS_ACCEPTANCE_HOST_ID;
const threadId = process.env.IOS_ACCEPTANCE_THREAD_ID;
assert.match(hostId ?? '', /^[a-f0-9-]{36}$/i, 'Explicit authorized Host ID required');
assert.match(threadId ?? '', /^[a-f0-9-]{36}$/i, 'Explicit authorized Thread ID required');
const device = process.env.IOS_SIMULATOR_ID;
assert.match(device ?? '', /^[A-F0-9-]{36}$/i, 'Explicit IOS_SIMULATOR_ID required');
assert(process.env.IOS_ACCEPTANCE_ROOT, 'Explicit task-owned IOS_ACCEPTANCE_ROOT required');
const root = resolve(process.env.IOS_ACCEPTANCE_ROOT);
assert(process.env.IOS_ACCEPTANCE_SIMULATOR_NAME, 'Explicit task-owned simulator name required');
assert(process.env.IOS_ACCEPTANCE_OUTPUT, 'Explicit IOS_ACCEPTANCE_OUTPUT required');
const output = resolve(process.env.IOS_ACCEPTANCE_OUTPUT);
assert(output.startsWith(root + '/'), 'Output must be an exclusive task directory');
await mkdir(output, { mode: 0o700 });
const simulatorEnv = {
  ...process.env,
  DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
};
const devices = JSON.parse(
  execFileSync('xcrun', ['simctl', 'list', 'devices', 'available', '--json'], {
    env: simulatorEnv,
    encoding: 'utf8',
  }),
);
const selected = Object.values(devices.devices)
  .flat()
  .find((item) => item.udid === device);
assert(
  selected?.name === process.env.IOS_ACCEPTANCE_SIMULATOR_NAME && selected.state === 'Booted',
  'Require the task-owned booted simulator',
);
const catalog = JSON.parse(await readFile(resolve(homedir(), '.config/agent-hub/hosts.json')));
const host = catalog.hosts.find((entry) => entry.hostId === hostId);
assert(host && new URL(host.endpoint).protocol === 'https:');
const pin = host.tlsPin.sha256.toLowerCase().replaceAll(':', '');
assert.match(pin, /^[0-9a-f]{64}$/);
const ca = await readFile(host.tlsPin.certificatePath.replace(/^~(?=\/)/, homedir()));
function pinnedGet(path, token = '') {
  return new Promise((resolveRequest, reject) => {
    const url = new URL(path, host.endpoint);
    const req = request(
      url,
      {
        ca,
        checkServerIdentity(_name, cert) {
          const actual = createHash('sha256').update(cert.raw).digest('hex');
          return actual === pin ? undefined : new Error('registered certificate pin mismatch');
        },
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        timeout: 15000,
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (part) => {
          data += part;
          if (data.length > 2_000_000) req.destroy();
        });
        res.on('end', () => {
          try {
            assert.equal(res.statusCode, 200);
            resolveRequest(JSON.parse(data));
          } catch (error) {
            reject(error);
          }
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('pinned request timed out')));
    req.on('error', reject);
    req.end();
  });
}
const descriptor = await pinnedGet('/host');
assert.equal(descriptor.target.hostId, hostId);
assert.equal(descriptor.fingerprint.toLowerCase(), pin);
const workspace = await pinnedGet('/v1/workspace', host.clientKey);
assert.equal(workspace.hostId, hostId);
assert.equal(workspace.connectionMode, 'desktop-runtime');
const project = workspace.projects.find((value) => value.name === 'easyai-desktop');
const targetThread = workspace.threads.find(
  (value) => value.id === threadId && value.projectId === project?.id,
);
assert(project && targetThread);
if (process.env.IOS_ACCEPTANCE_PREFLIGHT === '1') {
  console.log(
    JSON.stringify({
      threadId,
      title: targetThread.title,
      project: project.name,
      archived: targetThread.archived,
      sidebarContainsThread: JSON.stringify(workspace.sidebar ?? {}).includes(threadId),
      hasNextThreadPage: !!workspace.nextThreadCursor,
      threadCount: workspace.threads.length,
    }),
  );
  process.exit(0);
}
const fixture = {
  endpoint: host.endpoint,
  token: '',
  hostId,
  threadId,
  threadTitle: targetThread.title,
  nonce: 'readonly',
  secondEndpoint: '',
  secondToken: '',
  secondHostId: '',
  delayedEndpoint: '',
};
const server = createServer((req, res) => {
  if (
    req.method !== 'GET' ||
    req.url !== '/' ||
    req.headers.origin ||
    req.headers.host !== '127.0.0.1:18943'
  )
    return res.writeHead(403).end();
  res
    .writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    .end(JSON.stringify(fixture));
});
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(18943, '127.0.0.1', done);
});
const toolchain = JSON.parse(await readFile('.local/mobile-toolchain.json'));
const env = {
  ...process.env,
  DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
  JAVA_HOME: toolchain.javaHome,
};
const args = [
  '-project',
  'apps/ios/CodexManage.xcodeproj',
  '-scheme',
  'CodexManage',
  '-destination',
  `platform=iOS Simulator,id=${device}`,
  '-derivedDataPath',
  `${root}/DerivedData`,
  '-resultBundlePath',
  `${output}/result.xcresult`,
  '-parallel-testing-enabled',
  'NO',
  '-collect-test-diagnostics',
  'never',
  '-only-testing:CodexManageUITests/CodexManageUITests/testHistoryInstalledAppReadOnlyDEVMacBook128ConversationSyncReadOnly',
  'ARCHS=arm64',
  'ONLY_ACTIVE_ARCH=YES',
  'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.macbook128sync',
  'CODE_SIGNING_ALLOWED=YES',
  'CODE_SIGN_IDENTITY=-',
  'test',
];
try {
  console.log('Pinned certificate and target identity verified; starting read-only XCTest.');
  const child = spawn('xcodebuild', args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  for (const stream of [child.stdout, child.stderr])
    stream.on('data', (chunk) => {
      log += chunk.toString();
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  const fragments = [
    host.clientKey,
    host.clientKey.slice(0, 18),
    host.clientKey.slice(0, 10),
    host.clientKey.slice(0, 6),
  ];
  log = log
    .split('\n')
    .map((line) =>
      /Type .*SecureTextField/.test(line) || fragments.some((value) => line.includes(value))
        ? '[REDACTED credential activity]'
        : line,
    )
    .join('\n');
  await writeFile(`${output}/xcode.log`, log, { mode: 0o600 });
  console.log(
    log
      .split('\n')
      .filter((line) => /MACBOOK_SYNC_|Test Case|error:|\*\* TEST/.test(line))
      .join('\n'),
  );
  process.exitCode = code || 0;
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
