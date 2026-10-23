import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createInterface } from 'node:readline';
import { createWriteStream } from 'node:fs';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { prepareNativeMedia } from './prepare-native-media.mjs';

// Only an explicitly selected, already-open dedicated desktop test conversation is eligible.
const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
if (!threadId)
  throw Error(
    'Set DESKTOP_ACCEPTANCE_THREAD_ID to a dedicated test conversation already open in the original desktop',
  );
const directory = resolve('.local/desktop-acceptance');
await mkdir(directory, { recursive: true, mode: 0o700 });
const runStarted = new Date().toISOString();
const latestRun = { startedAt: runStarted, status: 'running', independentPassed: false };
const saveRun = () =>
  writeFile(join(directory, 'latest-run.json'), JSON.stringify(latestRun, null, 2), {
    mode: 0o600,
  });
await saveRun();
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_DESKTOP_VERSION: execFileSync(
    '/usr/libexec/PlistBuddy',
    ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
    { encoding: 'utf8' },
  ).trim(),
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Original Desktop Acceptance',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
const service = await startRuntimeServer();
const endpoint = `http://127.0.0.1:${service.server.address().port}`;
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const nonce = crypto.randomUUID().slice(0, 8);
const fixture = {
  endpoint,
  token,
  hostId: service.session.hostId,
  threadId,
  nonce,
  secondEndpoint: '',
  secondToken: '',
  secondHostId: '',
  delayedEndpoint: '',
  switchThreads: [],
};
const fixtureServer = createServer((request, response) => {
  if (
    request.headers.origin ||
    request.method !== 'GET' ||
    request.url !== '/' ||
    !['localhost:8795', '127.0.0.1:8795'].includes(request.headers.host)
  ) {
    response.writeHead(403);
    response.end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(fixture));
});
let child;
try {
  let projectCursor, threadCursor, workspace;
  do {
    const page = (workspace = await service.session.workspace(projectCursor, threadCursor));
    projectCursor = page.nextProjectCursor ?? undefined;
    threadCursor = page.nextThreadCursor ?? undefined;
  } while (projectCursor || threadCursor);
  const seenProjects = new Set();
  for (const thread of workspace.threads) {
    const project = thread.projectId ?? thread.projectIds?.[0];
    if (thread.id === threadId || !project || seenProjects.has(project)) continue;
    seenProjects.add(project);
    fixture.switchThreads.push({ id: thread.id, title: thread.title });
    if (fixture.switchThreads.length === 3) break;
  }
  if (fixture.switchThreads.length < 3)
    throw Error('Desktop switching acceptance requires three distinct project conversations');
  const initial = await service.session.open(threadId);
  if (!initial.runtime.canControl || initial.runtime.turnId)
    throw Error('The selected desktop conversation must have a verified owner and be idle');
  fixture.threadTitle = workspace.threads.find((v) => v.id === threadId)?.title;
  if (!fixture.threadTitle) throw Error('Desktop test conversation title missing');
  const initialReceiptIds = new Set(initial.runtime.receipts.map((v) => v.clientMessageId));
  await new Promise((resolve, reject) => {
    fixtureServer.once('error', reject);
    fixtureServer.listen(8795, '127.0.0.1', resolve);
  });
  const resultBundle = join(directory, `result-${Date.now()}.xcresult`);
  latestRun.resultBundle = resultBundle;
  latestRun.desktopBuild = process.env.CODEX_DESKTOP_VERSION;
  await saveRun();
  const destination = process.env.IOS_SIMULATOR_ID
    ? `platform=iOS Simulator,id=${process.env.IOS_SIMULATOR_ID}`
    : 'platform=iOS Simulator,name=iPhone 17 Pro';
  const devices = JSON.parse(
    execFileSync('xcrun', ['simctl', 'list', 'devices', 'available', '--json'], {
      encoding: 'utf8',
    }),
  );
  const device = Object.values(devices.devices)
    .flat()
    .find((v) =>
      process.env.IOS_SIMULATOR_ID
        ? v.udid === process.env.IOS_SIMULATOR_ID
        : v.name === 'iPhone 17 Pro',
    );
  if (!device) throw Error('iPhone simulator missing');
  await prepareNativeMedia(device.udid, directory, nonce);
  child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      destination,
      '-derivedDataPath',
      '.build/ios',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopReplyProgressStopAndApproval',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopConversationSwitchAndDraftIsolation',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopPhotoFileUploadAndDownload',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  let log = '';
  const currentLog = createWriteStream(join(directory, 'xcode-current.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log += safe + '\n';
      currentLog.write(safe + '\n');
    });
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', resolve);
  });
  await new Promise((done) => currentLog.end(done));
  log = log.replaceAll(token, '[REDACTED]').replaceAll(token.slice(0, 20), '[REDACTED]');
  await writeFile(join(directory, 'xcode.log'), log, { mode: 0o600 });
  const native = JSON.parse(
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
  Object.assign(latestRun, {
    nativePassed: native.passedTests,
    nativeFailed: native.failedTests,
    nativeSkipped: native.skippedTests,
  });
  await saveRun();
  if (
    code !== 0 ||
    native.failedTests !== 0 ||
    native.skippedTests !== 0 ||
    native.passedTests !== 3
  )
    throw Error(
      'Original desktop acceptance requires all three native tests to pass without skips',
    );
  const final = await service.session.open(threadId);
  const receipts = final.runtime.receipts;
  const summary = {
    verifiedAt: new Date().toISOString(),
    resultBundle,
    desktopBuild: process.env.CODEX_DESKTOP_VERSION,
    nativePassed: native.passedTests,
    nativeFailed: native.failedTests,
    nativeSkipped: native.skippedTests,
    mode: 'desktop-runtime',
    sameOwnerConnected: final.runtime.canControl,
    reply: final.messages.some((v) => v.role === 'assistant' && v.text === `DESKTOP_IOS_${nonce}`),
    stopped: receipts.some(
      (v) => v.state === 'interrupted' && !initialReceiptIds.has(v.clientMessageId),
    ),
    approval: final.messages.some(
      (v) => v.role === 'assistant' && v.text === `DESKTOP_APPROVAL_${nonce}`,
    ),
    idle: final.runtime.turnId === null,
    pendingApprovals: final.runtime.approvals.length,
  };
  const report = final.messages
    .flatMap((v) => v.attachments)
    .findLast((v) => v.name === 'desktop-native-report.txt');
  const image = final.messages
    .flatMap((v) => v.attachments)
    .findLast((v) => v.name === 'desktop-native-output.png');
  summary.phoneFileContent =
    !!report &&
    (await service.session.download(threadId, report.id)).data.toString('utf8').includes(nonce);
  summary.phoneImage =
    !!image &&
    (await service.session.download(threadId, image.id)).data
      .subarray(0, 8)
      .equals(Buffer.from('89504e470d0a1a0a', 'hex'));
  if (
    !summary.sameOwnerConnected ||
    !summary.reply ||
    !summary.stopped ||
    !summary.approval ||
    !summary.idle ||
    summary.pendingApprovals ||
    !summary.phoneFileContent ||
    !summary.phoneImage
  )
    throw Error('Native UI passed but independent desktop snapshot verification failed');
  await writeFile(join(directory, 'summary.json'), JSON.stringify(summary, null, 2), {
    mode: 0o600,
  });
  Object.assign(latestRun, {
    status: 'passed',
    independentPassed: true,
    finishedAt: summary.verifiedAt,
  });
  await saveRun();
  console.log(JSON.stringify(summary));
} catch (error) {
  Object.assign(latestRun, { status: 'failed', finishedAt: new Date().toISOString() });
  await saveRun();
  throw error;
} finally {
  child?.kill('SIGTERM');
  fixtureServer.close();
  service.close();
}
