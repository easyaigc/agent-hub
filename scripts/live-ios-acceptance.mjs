import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Explicit live acceptance command. Uses installed Codex login and real model calls.
const directory = resolve(process.env.LIVE_IOS_STATE_DIR ?? '.local/live-ios');
await mkdir(join(directory, 'workspace/empty'), { recursive: true });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'managed-runtime',
  HOST_AGENT_PORT: '8790',
  HOST_AGENT_STATE_DIR: join(directory, 'runtime'),
  HOST_AGENT_ROOT: join(directory, 'workspace'),
});
const service = await startRuntimeServer();
const previousThreads = new Set((await service.session.workspace()).threads.map((t) => t.id));
const token = (await readFile(join(directory, 'runtime/access-token'), 'utf8')).trim();
const fixture = {
  endpoint: 'http://127.0.0.1:8790',
  token,
  hostId: service.session.hostId,
  threadId: '',
  nonce: randomUUID(),
  secondEndpoint: '',
  secondToken: '',
  secondHostId: '',
  delayedEndpoint: 'http://127.0.0.1:8793',
};
const fixtureServer = createServer((request, response) => {
  if (request.headers.origin || request.method !== 'GET') {
    response.writeHead(403);
    response.end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(fixture));
});
await new Promise((done, reject) => {
  fixtureServer.once('error', reject);
  fixtureServer.listen(8791, '127.0.0.1', done);
});
const history = spawn(process.execPath, ['apps/host-agent/dist/history-entry.js'], {
  env: {
    ...process.env,
    HOST_AGENT_PORT: '8788',
    HOST_AGENT_STATE_DIR: join(directory, 'history'),
  },
  stdio: 'ignore',
});
const run = (command, args) => execFileSync(command, args, { env: process.env, encoding: 'utf8' });
let child;
let secondService;
let previousSecondThreads = new Set();
let delayedReplies = 0;
// A real HTTP forwarder adds request latency for the composer/acknowledgement race test.
// It changes neither product code nor Codex replies.
const delayedServer = createServer(async (request, response) => {
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    if (
      request.method === 'POST' &&
      new URL(request.url, 'http://localhost').pathname === '/v1/replies'
    ) {
      delayedReplies++;
      await new Promise((done) => setTimeout(done, 15_000));
    }
    if (response.destroyed) return;
    const upstream = await fetch(fixture.endpoint + request.url, {
      method: request.method,
      headers: {
        Authorization: request.headers.authorization ?? '',
        'Content-Type': request.headers['content-type'] ?? 'application/json',
      },
      ...(chunks.length ? { body: Buffer.concat(chunks) } : {}),
    });
    response.writeHead(upstream.status, {
      'Content-Type': upstream.headers.get('content-type') ?? 'application/json',
      'Cache-Control': 'no-store',
    });
    response.end(Buffer.from(await upstream.arrayBuffer()));
  } catch {
    if (!response.destroyed) {
      response.writeHead(502);
      response.end();
    }
  }
});
try {
  await new Promise((done, reject) => {
    delayedServer.once('error', reject);
    delayedServer.listen(8793, '127.0.0.1', done);
  });
  const secondDirectory = join(directory, 'second-host');
  await mkdir(join(secondDirectory, 'workspace'), { recursive: true });
  Object.assign(process.env, {
    HOST_AGENT_PORT: '8792',
    HOST_AGENT_STATE_DIR: join(secondDirectory, 'runtime'),
    HOST_AGENT_ROOT: join(secondDirectory, 'workspace'),
  });
  secondService = await startRuntimeServer();
  previousSecondThreads = new Set(
    (await secondService.session.workspace()).threads.map((t) => t.id),
  );
  fixture.secondEndpoint = 'http://127.0.0.1:8792';
  fixture.secondToken = (
    await readFile(join(secondDirectory, 'runtime/access-token'), 'utf8')
  ).trim();
  fixture.secondHostId = secondService.session.hostId;
  if (fixture.secondHostId === fixture.hostId) throw Error('Host identities must be distinct');
  const devices = JSON.parse(run('xcrun', ['simctl', 'list', 'devices', 'available', '--json']));
  const device = Object.values(devices.devices)
    .flat()
    .find((v) =>
      process.env.IOS_SIMULATOR_ID
        ? v.udid === process.env.IOS_SIMULATOR_ID
        : v.name === 'iPhone 17 Pro',
    );
  if (!device) throw Error('Install an iPhone simulator or set IOS_SIMULATOR_ID');
  if (device.state !== 'Booted') run('xcrun', ['simctl', 'boot', device.udid]);
  run('xcrun', ['simctl', 'bootstatus', device.udid, '-b']);
  run('xcodebuild', [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-sdk',
    'iphonesimulator',
    '-destination',
    'generic/platform=iOS Simulator',
    '-derivedDataPath',
    '.build/ios',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'build',
  ]);
  run('xcrun', [
    'simctl',
    'install',
    device.udid,
    '.build/ios/Build/Products/Debug-iphonesimulator/CodexManage.app',
  ]);
  const container = run('xcrun', [
    'simctl',
    'get_app_container',
    device.udid,
    'com.codexmanage.ios',
    'data',
  ]).trim();
  await mkdir(join(container, 'Documents'), { recursive: true });
  await writeFile(
    join(container, 'Documents/native-input.txt'),
    '原生手机上传校验码：' + fixture.nonce,
  );
  const crc = (bytes) => {
    let value = 0xffffffff;
    for (const byte of bytes) {
      value ^= byte;
      for (let i = 0; i < 8; i++) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
    }
    return (value ^ 0xffffffff) >>> 0;
  };
  const chunk = (kind, bytes) => {
    const type = Buffer.from(kind);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(crc(Buffer.concat([type, bytes])));
    return Buffer.concat([length, type, bytes, checksum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(64);
  header.writeUInt32BE(64, 4);
  header[8] = 8;
  header[9] = 2;
  const rows = Buffer.alloc(64 * (1 + 64 * 3));
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) rows[y * 193 + 1 + x * 3] = 255;
  const image = join(directory, 'native-red.png');
  await writeFile(
    image,
    Buffer.concat([
      Buffer.from('89504e470d0a1a0a', 'hex'),
      chunk('IHDR', header),
      chunk('IDAT', deflateSync(rows)),
      chunk('IEND', Buffer.alloc(0)),
    ]),
  );
  run('xcrun', ['simctl', 'addmedia', device.udid, image]);
  for (let i = 0; i < 30; i++) {
    if (
      await fetch('http://127.0.0.1:8788/healthz')
        .then((r) => r.ok)
        .catch(() => false)
    )
      break;
    await new Promise((done) => setTimeout(done, 250));
  }
  const resultPath = join(directory, `result-${Date.now()}.xcresult`);
  const log = [];
  child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device.udid}`,
      '-derivedDataPath',
      '.build/ios',
      '-resultBundlePath',
      resultPath,
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      '-parallel-testing-enabled',
      'NO',
      '-skip-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopReplyProgressStopAndApproval',
      '-skip-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopConversationSwitchAndDraftIsolation',
      '-skip-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopPhotoFileUploadAndDownload',
      'test',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const redact = (line) => {
    for (const credential of [token, fixture.secondToken])
      for (let length = credential.length; length >= 6; length--)
        line = line.replaceAll(credential.slice(0, length), '[redacted]');
    return line;
  };
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = redact(line);
      log.push(safe);
      console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  await writeFile(join(directory, 'xcode.log'), log.join('\n'));
  if (code !== 0) throw Error('Live native tests failed; inspect .local/live-ios/xcode.log');
  const summary = JSON.parse(
    run('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      resultPath,
      '--format',
      'json',
    ]),
  );
  if (summary.failedTests !== 0 || summary.skippedTests !== 0 || summary.passedTests !== 16)
    throw Error('Incomplete live acceptance: inspect actual test counts');
  if (delayedReplies < 1) throw Error('Slow-send race was not exercised');
  const work = await service.session.workspace();
  const snapshots = await Promise.all(
    work.threads
      .filter((thread) => !previousThreads.has(thread.id))
      .map((thread) => service.session.open(thread.id)),
  );
  const mixed = snapshots.find((s) =>
    s.messages.some((m) => m.attachments.some((a) => a.name === 'native-report.txt')),
  );
  const formatted = snapshots.find((s) =>
    s.messages.some(
      (m) => m.role === 'assistant' && m.text.startsWith('IOS_NATIVE_' + fixture.nonce.slice(0, 8)),
    ),
  );
  if (
    !formatted ||
    formatted.runtime.plan.length < 2 ||
    !formatted.runtime.plan.every((s) => s.status === 'completed')
  )
    throw Error('Real native Markdown and completed plan evidence missing');
  if (!mixed) throw Error('Native report attachment missing');
  const report = mixed.messages
    .flatMap((m) => m.attachments)
    .find((a) => a.name === 'native-report.txt');
  const verified = (await service.session.download(mixed.threadId, report.id)).data.toString(
    'utf8',
  );
  if (!verified.includes(fixture.nonce))
    throw Error('Native uploaded TXT nonce missing from actual output');
  const control = snapshots.find((s) =>
    s.messages.some(
      (m) => m.role === 'assistant' && m.text === 'APPROVAL_IOS_' + fixture.nonce.slice(0, 8),
    ),
  );
  if (!control || !control.runtime.receipts.some((r) => r.state === 'interrupted'))
    throw Error('Real native interrupt and approval evidence missing');
  const secondWork = await secondService.session.workspace();
  const secondSnapshots = await Promise.all(
    secondWork.threads
      .filter((t) => !previousSecondThreads.has(t.id))
      .map((t) => secondService.session.open(t.id)),
  );
  const markerA = 'HOST_A_' + fixture.nonce.slice(0, 8),
    markerB = 'HOST_B_' + fixture.nonce.slice(0, 8);
  const hasAnswer = (items, marker) =>
    items.some((s) => s.messages.some((m) => m.role === 'assistant' && m.text === marker));
  const projectless = work.threads.find((t) =>
    t.title.includes('PROJECTLESS_' + fixture.nonce.slice(0, 8)),
  );
  const emptyProject = work.threads.find((t) =>
    t.title.includes('EMPTY_PROJECT_' + fixture.nonce.slice(0, 8)),
  );
  if (
    !projectless ||
    projectless.projectId !== null ||
    !hasAnswer(
      snapshots.filter((s) => s.threadId === projectless.id),
      'PROJECTLESS_' + fixture.nonce.slice(0, 8),
    )
  )
    throw Error('Real projectless creation and reply evidence missing');
  if (
    !emptyProject ||
    !emptyProject.projectId ||
    emptyProject.projectId === 'agent-workspace' ||
    !hasAnswer(
      snapshots.filter((s) => s.threadId === emptyProject.id),
      'EMPTY_PROJECT_' + fixture.nonce.slice(0, 8),
    )
  )
    throw Error('Real empty-project creation and reply evidence missing');
  if (
    !hasAnswer(snapshots, markerA) ||
    hasAnswer(snapshots, markerB) ||
    !hasAnswer(secondSnapshots, markerB) ||
    hasAnswer(secondSnapshots, markerA)
  )
    throw Error('Real host routing isolation failed');
  await writeFile(
    join(directory, 'summary.json'),
    JSON.stringify(
      {
        ...summary,
        liveMixedInputVerified: true,
        nativeStopApprovalVerified: true,
        twoLocalHostsVerified: true,
        nativeDraftRaceVerified: true,
        nativeMarkdownPlanVerified: true,
        nativeProjectlessVerified: true,
        nativeEmptyProjectVerified: true,
        resultPath,
      },
      null,
      2,
    ),
  );
  console.log(
    'Live native acceptance passed, including model reading the actual phone-uploaded nonce.',
  );
} finally {
  child?.kill('SIGTERM');
  history.kill('SIGTERM');
  fixtureServer.close();
  service.close();
  secondService?.close();
  delayedServer.close();
  delayedServer.closeAllConnections();
}
