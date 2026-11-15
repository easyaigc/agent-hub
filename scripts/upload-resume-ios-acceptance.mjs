import assert from 'node:assert/strict';
import { createHash, randomUUID, randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import {
  mkdir,
  readFile,
  readdir,
  rm,
  statfs,
  writeFile,
  copyFile,
  cp,
  rename,
} from 'node:fs/promises';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { join, resolve, relative } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { isolateMainUUID } from './mobile/isolated-ios-runner.mjs';
import { ResumableUploads } from '../apps/host-agent/dist/resumable-uploads.js';

const root = resolve('.local/upload-resume-20261115/native-testing');
const attemptId = randomUUID();
const run = join(root, `run-${attemptId}`);
const scenario = process.env.UPLOAD_ACCEPTANCE_SCENARIO || 'resume';
assert(['resume', 'cancel', 'verifying-cancel'].includes(scenario), 'Unknown acceptance scenario');
const cancelScenario = scenario === 'cancel';
const verifyingScenario = scenario === 'verifying-cancel';
const evidence = join(root, 'attempts', `${scenario}-${attemptId}`);
const report = join(evidence, 'report.json');
const hostId = 'native-upload-fixture';
const target = {
  agentId: 'codex',
  hostId,
  workspaceId: 'native-test-workspace',
  runtimeId: 'native-test-runtime',
};
const token = randomBytes(32).toString('hex');
const size = (verifyingScenario ? 4 : 64) * 1024 * 1024;
const hash = createHash('sha256');
for (let i = 0; i < size / (256 * 1024); i++) hash.update(Buffer.alloc(256 * 1024, 65));
const manifest = {
  version: 1,
  purpose: 'file-transfer',
  hostId,
  id: randomUUID(),
  name: verifyingScenario ? 'acceptance-verifying-4m.bin' : 'acceptance-64m.bin',
  mediaType: 'application/octet-stream',
  sizeBytes: size,
  sha256: hash.digest('hex'),
  threadId: null,
  creationId: null,
  attachmentId: null,
};
const config = {
  client: {
    id: hostId,
    name: 'Local upload acceptance',
    endpoint: '',
    isDemo: false,
    online: true,
    readingTarget: target,
  },
  manifest,
  token,
};
const state = {
  committed: 0,
  phase: 'none',
  disconnects: 0,
  timeouts: 0,
  timeoutInjected: false,
  publications: 0,
  hashOK: false,
  restartOffset: null,
  resumedAfterRestart: false,
  offsets: [],
  cancelDropped: false,
  cancelRequests: 0,
  cancelBusyInjected: 0,
  statusAfterRestart: 0,
  chunksAfterCancel: 0,
  cancelsAfterRestart: 0,
  publicationUncertain: false,
  recoveryCompleteBlocked: false,
  cancelConflict: 0,
  recoveryCompletes: 0,
  chunksAfterPublication: 0,
};
let simulator;
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const xcrun = (...args) => execFileSync('xcrun', args, { env, encoding: 'utf8' }).trim();
async function fingerprint() {
  const files = [
    'scripts/upload-resume-ios-acceptance.mjs',
    'scripts/mobile/isolated-ios-runner.mjs',
    'apps/ios/Tests/UploadResumeUITests.swift',
    'apps/host-agent/src/resumable-uploads.ts',
    'apps/host-agent/dist/resumable-uploads.js',
    'packages/protocol/src/uploads.ts',
  ];
  async function walk(dir) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) await walk(path);
      else if (entry.isFile() && path.endsWith('.js')) files.push(path);
    }
  }
  // These package entry points load their production JS modules; exclude unrelated tests.
  for (const dir of [
    'packages/protocol/dist',
    'packages/codex-adapter/dist',
    'packages/notification-runtime/dist',
  ])
    await walk(dir);
  const entries = [];
  for (const path of files.sort())
    entries.push({
      path,
      sha256: createHash('sha256')
        .update(await readFile(path))
        .digest('hex'),
    });
  return {
    files: entries,
    sha256: createHash('sha256').update(JSON.stringify(entries)).digest('hex'),
  };
}
async function verifyBuild(appSource) {
  const source = JSON.parse(await readFile('.local/upload-resume-20261115/build-ios-source.json'));
  const verification = JSON.parse(
    await readFile('.local/upload-resume-20261115/build-ios-verification.json'),
  );
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  assert(verification.sourceStable === true && verification.buildSucceeded === true);
  assert.equal(source.head, head);
  assert.equal(verification.head, head);
  assert.equal(source.fingerprint, verification.sourceFingerprint);
  assert(source.files.length > 60, 'Incomplete source manifest');
  assert.equal(
    new Set(source.files.map((item) => item.path)).size,
    source.files.length,
    'Duplicate source manifest paths',
  );
  for (const item of source.files)
    assert.equal(
      createHash('sha256')
        .update(await readFile(item.path))
        .digest('hex'),
      item.sha256,
      `Source mismatch: ${item.path}`,
    );
  const files = [];
  async function walk(dir) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) await walk(path);
      else if (entry.isFile())
        files.push({
          path: relative(appSource, path),
          sha256: createHash('sha256')
            .update(await readFile(path))
            .digest('hex'),
        });
      else throw new Error(`Unexpected non-file in App: ${path}`);
    }
  }
  await walk(appSource);
  const sort = (a) => a.sort((x, y) => x.path.localeCompare(y.path, 'en'));
  assert.deepEqual(
    sort(files),
    sort([...verification.appFiles]),
    'App file inventory or hash mismatch',
  );
  return {
    head,
    sourceFingerprint: source.fingerprint,
    appPackageFingerprint: verification.appPackageFingerprint,
    sourceFiles: source.files,
    appFiles: files,
  };
}
async function execute(command, args, log) {
  return new Promise((resolvePromise, reject) => {
    const out = createWriteStream(log);
    const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    const monitor = setInterval(async () => {
      try {
        const fs = await statfs(root);
        if (fs.bavail * fs.bsize < 350 * 1024 ** 2) {
          result.diskGuardTriggered = true;
          child.kill('SIGTERM');
        }
      } catch (error) {
        result.diskMonitorError = String(error);
      }
    }, 2000);
    child.stdout.pipe(out);
    child.stderr.pipe(out);
    child.on('error', (e) => {
      clearInterval(monitor);
      reject(e);
    });
    child.on('close', (code) => {
      clearInterval(monitor);
      out.end(() => resolvePromise(code));
    });
  });
}
const store = new ResumableUploads(hostId, join(run, 'store'), {
  async validate(m) {
    assert.equal(m.purpose, 'file-transfer');
  },
  async complete(m, path) {
    const destination = join(run, 'received.bin');
    if (!verifyingScenario || state.publications === 0) {
      await copyFile(path, destination);
      state.publications++;
    }
    const h = createHash('sha256');
    for await (const chunk of createReadStream(destination)) h.update(chunk);
    state.hashOK = h.digest('hex') === m.sha256;
    if (verifyingScenario && !state.publicationUncertain) {
      state.publicationUncertain = true;
      throw new Error('Fixture: publication persisted but result unavailable');
    }
    const {
      version: _version,
      purpose: _purpose,
      threadId: _threadId,
      creationId: _creationId,
      attachmentId: _attachmentId,
      ...file
    } = m;
    return {
      ...file,
      phase: 'completed',
      destinationPath: destination,
      createdAt: new Date().toISOString(),
    };
  },
});
function send(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(data));
}
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1:18763');
    if (url.pathname === '/control/config') return send(res, 200, config);
    if (url.pathname === '/control/state') return send(res, 200, state);
    if (url.pathname === '/control/restart') {
      state.restartOffset = state.committed;
      return send(res, 200, { ok: true });
    }
    if (req.headers.authorization !== `Bearer ${token}`)
      return send(res, 401, { error: 'unauthorized' });
    const actualTarget = JSON.parse(url.searchParams.get('target') || 'null');
    if (
      url.searchParams.get('hostId') !== hostId ||
      !actualTarget ||
      Object.keys(target).some((k) => actualTarget[k] !== target[k])
    )
      return send(res, 409, { error: 'target mismatch' });
    const allowed = ['hostId', 'target', 'id', 'offset'];
    if (
      [...url.searchParams.keys()].some(
        (k) => !allowed.includes(k) || url.searchParams.getAll(k).length !== 1,
      )
    )
      return send(res, 400, { error: 'invalid query' });
    let result;
    const id = url.searchParams.get('id');
    if (req.method === 'GET' && url.pathname === '/v1/uploads/capabilities')
      result = { version: 1, target, maxChunkBytes: 256 * 1024 };
    else if (req.method === 'GET' && url.pathname === '/v1/uploads') {
      if (cancelScenario && state.cancelDropped && state.restartOffset === null)
        return send(res, 503, { error: 'status unavailable until restart' });
      if ((cancelScenario || verifyingScenario) && state.restartOffset !== null)
        state.statusAfterRestart++;
      result = await store.status(id);
    } else if (req.method === 'POST' && url.pathname === '/v1/uploads') {
      const pieces = [];
      for await (const c of req) pieces.push(c);
      result = await store.prepare(JSON.parse(Buffer.concat(pieces)));
    } else if (req.method === 'POST' && url.pathname === '/v1/uploads/chunk') {
      if (cancelScenario && state.cancelDropped) state.chunksAfterCancel++;
      if (verifyingScenario && state.publicationUncertain) state.chunksAfterPublication++;
      assert.equal(req.headers['content-type'], 'application/octet-stream');
      assert.match(url.searchParams.get('offset') ?? '', /^(0|[1-9]\d*)$/);
      const offset = Number(url.searchParams.get('offset'));
      const pieces = [];
      for await (const c of req) pieces.push(c);
      if (
        !cancelScenario &&
        !verifyingScenario &&
        !state.timeoutInjected &&
        offset >= 16 * 1024 * 1024
      ) {
        state.timeoutInjected = true;
        await delay(32_000);
        if (res.destroyed) {
          state.timeouts++;
          return;
        }
      }
      await delay(180);
      result = await store.chunk(
        id,
        offset,
        req.headers['x-content-sha256'],
        Buffer.concat(pieces),
      );
      state.offsets.push(offset);
      if (state.restartOffset !== null && offset >= state.restartOffset)
        state.resumedAfterRestart = true;
      state.committed = result.committedBytes;
      state.phase = result.phase;
      // Simulate another in-flight completion while the last chunk response is withheld.
      // The production store persists verifying/publicationStarted before the callback fails.
      if (verifyingScenario && state.committed === size && !state.publicationUncertain) {
        try {
          await store.complete(id);
        } catch (error) {
          if (!state.publicationUncertain) throw error;
        }
        const receipt = await store.status(id);
        assert.equal(receipt.phase, 'verifying');
        assert.equal(receipt.publicationStarted, true);
        state.phase = receipt.phase;
        await new Promise((resolvePromise) => {
          if (res.destroyed) resolvePromise();
          else res.once('close', resolvePromise);
        });
        return;
      }
      if (
        !cancelScenario &&
        !verifyingScenario &&
        !state.disconnects &&
        state.committed >= 8 * 1024 * 1024
      ) {
        state.disconnects++;
        return res.destroy();
      }
    } else if (req.method === 'POST' && url.pathname === '/v1/uploads/complete') {
      if (verifyingScenario && state.publicationUncertain && state.restartOffset === null) {
        state.recoveryCompleteBlocked = true;
        await new Promise((resolvePromise) => {
          if (res.destroyed) resolvePromise();
          else res.once('close', resolvePromise);
        });
        return;
      }
      if (verifyingScenario && state.publicationUncertain) state.recoveryCompletes++;
      result = await store.complete(id);
    } else if (req.method === 'POST' && url.pathname === '/v1/uploads/cancel') {
      state.cancelRequests++;
      if (cancelScenario && !state.cancelDropped && state.cancelBusyInjected === 0) {
        state.cancelBusyInjected++;
        return send(res, 409, { error: 'Fixture: the in-flight chunk is finishing' });
      }
      if (cancelScenario && state.restartOffset !== null) state.cancelsAfterRestart++;
      try {
        result = await store.cancel(id);
      } catch (error) {
        if (verifyingScenario && error.status === 409) state.cancelConflict++;
        throw error;
      }
      if (cancelScenario && !state.cancelDropped) {
        state.cancelDropped = true;
        state.phase = result.phase;
        return res.destroy();
      }
    } else return send(res, 405, { error: 'unsupported' });
    if (result?.committedBytes !== undefined) {
      state.committed = result.committedBytes;
      state.phase = result.phase;
    }
    send(res, 200, result);
  } catch (error) {
    if (!res.destroyed) send(res, error.status ?? 500, { error: String(error.message) });
  }
});
await mkdir(join(root, 'attempts'), { recursive: true, mode: 0o700 });
await mkdir(evidence, { recursive: false, mode: 0o700 });
await mkdir(run, { recursive: false, mode: 0o700 });
let result = {
  startedAt: new Date().toISOString(),
  runtime:
    'loopback fixture; production ResumableUploads; hand-written HTTP routing/auth and fixed file-transfer completion; no actual runtime router, Electron or cellular',
  attemptId,
  scenario,
  node: process.version,
  modelSettings: 'not exposed to this execution entry point',
};
try {
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolvePromise);
  });
  const disk = await statfs(root);
  result.freeBytesBefore = disk.bavail * disk.bsize;
  config.client.endpoint = `http://127.0.0.1:${server.address().port}`;
  assert(
    result.freeBytesBefore >= 1500 * 1024 ** 2,
    'Require at least 1500 MiB free for simulator and fixture',
  );
  result.fingerprintBefore = await fingerprint();
  const runtimes = JSON.parse(xcrun('simctl', 'list', 'runtimes', '--json')).runtimes;
  const runtime = runtimes.find(
    (x) => x.isAvailable && x.identifier.startsWith('com.apple.CoreSimulator.SimRuntime.iOS-'),
  );
  assert(runtime, 'No available iOS simulator runtime');
  const appSource = resolve('.build/ios/Build/Products/Debug-iphonesimulator/CodexManage.app');
  const app = join(run, 'Acceptance.app');
  const runner = join(run, 'UploadRunner.app');
  const test = join(runner, 'PlugIns', 'UploadResumeUITests.xctest');
  const appBinary = join(app, 'CodexManage');
  result.buildBinding = await verifyBuild(appSource);
  await cp(appSource, app, { recursive: true });
  assert.deepEqual(
    await verifyBuild(app),
    result.buildBinding,
    'App copy or build binding changed during staging',
  );
  // This acceptance app does not exercise notification extensions; avoid an unrelated bundle identity collision.
  await rm(join(app, 'PlugIns'), { recursive: true, force: true });
  execFileSync(
    '/usr/libexec/PlistBuddy',
    [
      '-c',
      'Set :CFBundleIdentifier com.easyai.agenthub.uploadresume.acceptance',
      join(app, 'Info.plist'),
    ],
    { env },
  );
  execFileSync('lipo', [appBinary, '-thin', 'arm64', '-output', appBinary + '.thin'], { env });
  await copyFile(appBinary + '.thin', appBinary);
  await rm(appBinary + '.thin');
  const modified = isolateMainUUID(
    await readFile(appBinary),
    'com.easyai.agenthub.uploadresume.acceptance.xctrunner',
  );
  await writeFile(appBinary, modified.buffer);
  result.appUUIDs = modified.uuids;
  execFileSync('codesign', ['--force', '--sign', '-', app], { env });
  const xctRunner =
    '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/Library/Xcode/Agents/XCTRunner.app';
  await cp(xctRunner, runner, { recursive: true });
  const plistBuddy = (key, value) =>
    execFileSync(
      '/usr/libexec/PlistBuddy',
      ['-c', `Set :${key} ${value}`, join(runner, 'Info.plist')],
      { env },
    );
  plistBuddy('CFBundleIdentifier', 'com.easyai.agenthub.uploadresume.acceptance.uitests.xctrunner');
  plistBuddy('CFBundleExecutable', 'XCTRunner');
  plistBuddy('CFBundleName', 'UploadRunner');
  const runnerBinary = join(runner, 'XCTRunner');
  execFileSync('lipo', [runnerBinary, '-thin', 'arm64', '-output', runnerBinary + '.thin'], {
    env,
  });
  await copyFile(runnerBinary + '.thin', runnerBinary);
  await rm(runnerBinary + '.thin');
  await writeFile(
    runnerBinary,
    isolateMainUUID(
      await readFile(runnerBinary),
      'com.easyai.agenthub.uploadresume.acceptance.uitests.xctrunner',
    ).buffer,
  );
  const platformDeveloper =
    '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer';
  const frameworks = join(runner, 'Frameworks');
  await mkdir(frameworks, { recursive: true });
  for (const name of ['XCTest', 'XCUIAutomation', 'Testing', '_Testing_Foundation'])
    await cp(
      join(platformDeveloper, 'Library/Frameworks', `${name}.framework`),
      join(frameworks, `${name}.framework`),
      { recursive: true },
    );
  for (const name of ['XCTestCore', 'XCTestSupport', 'XCTAutomationSupport', 'ResultDataPublisher'])
    await cp(
      join(platformDeveloper, 'Library/PrivateFrameworks', `${name}.framework`),
      join(frameworks, `${name}.framework`),
      { recursive: true },
    );
  for (const name of ['lib_TestingInterop.dylib', 'libXCTestSwiftSupport.dylib'])
    await cp(join(platformDeveloper, 'usr/lib', name), join(frameworks, name));
  for (const name of await readdir(frameworks))
    execFileSync('codesign', ['--force', '--sign', '-', join(frameworks, name)], { env });
  await mkdir(test, { recursive: true });
  await writeFile(
    join(test, 'Info.plist'),
    JSON.stringify({
      CFBundleIdentifier: 'com.easyai.agenthub.uploadresume.acceptance.uitests',
      CFBundleExecutable: 'UploadResumeUITests',
      CFBundleName: 'UploadResumeUITests',
      CFBundlePackageType: 'BNDL',
      CFBundleVersion: '1',
    }),
  );
  execFileSync('plutil', ['-convert', 'xml1', join(test, 'Info.plist')], { env });
  const platform =
    '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer';
  const sdk = xcrun('--sdk', 'iphonesimulator', '--show-sdk-path');
  const compile = [
    '--sdk',
    'iphonesimulator',
    'swiftc',
    '-emit-library',
    '-module-name',
    'UploadResumeUITests',
    '-target',
    'arm64-apple-ios17.0-simulator',
    '-sdk',
    sdk,
    '-F',
    join(platform, 'Library/Frameworks'),
    '-I',
    join(platform, 'usr/lib'),
    '-L',
    join(platform, 'usr/lib'),
    '-lXCTestSwiftSupport',
    '-Xlinker',
    '-rpath',
    '-Xlinker',
    '@loader_path/Frameworks',
    'apps/ios/Tests/UploadResumeUITests.swift',
    '-o',
    join(test, 'UploadResumeUITests'),
  ];
  result.compileExitCode = await execute('xcrun', compile, join(run, 'compile.log'));
  if (result.compileExitCode !== 0)
    throw new Error(
      'XCTest compile failed: ' + (await readFile(join(run, 'compile.log'), 'utf8')).slice(-6000),
    );
  execFileSync('codesign', ['--force', '--sign', '-', test], { env });
  execFileSync('codesign', ['--force', '--sign', '-', runner], { env });
  const configuration = {
    UploadResumeUITests: {
      IsUITestBundle: true,
      IsXCTRunnerHostedTestBundle: true,
      DependentProductPaths: [app, runner, test],
      ProductModuleName: 'UploadResumeUITests',
      TestBundlePath: join(test),
      TestHostPath: runner,
      TestHostBundleIdentifier: 'com.easyai.agenthub.uploadresume.acceptance.uitests.xctrunner',
      UITargetAppPath: app,
      UITargetAppBundleIdentifier: 'com.easyai.agenthub.uploadresume.acceptance',
      EnvironmentVariables: { UPLOAD_FIXTURE_URL: config.client.endpoint },
      TestingEnvironmentVariables: { UPLOAD_FIXTURE_URL: config.client.endpoint },
      OnlyTestIdentifiers: [
        verifyingScenario
          ? 'UploadResumeUITests/testVerifyingCancellationRecovery'
          : cancelScenario
            ? 'UploadResumeUITests/testCancelResponseLostAndRestart'
            : 'UploadResumeUITests/testUploadPauseDisconnectAndRestart',
      ],
    },
    __xctestrun_metadata__: { FormatVersion: 1 },
  };
  const testRun = join(run, 'UploadResume.xctestrun');
  await writeFile(testRun, JSON.stringify(configuration));
  execFileSync('plutil', ['-convert', 'xml1', testRun], { env });
  simulator = xcrun(
    'simctl',
    'create',
    `Upload Resume Acceptance ${randomUUID()}`,
    'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro',
    runtime.identifier,
  );
  xcrun('simctl', 'boot', simulator);
  const bootExit = await execute(
    'xcrun',
    ['simctl', 'bootstatus', simulator, '-b'],
    join(run, 'boot.log'),
  );
  if (bootExit !== 0)
    throw new Error(
      result.diskGuardTriggered
        ? 'Simulator boot stopped: free space fell below 350 MiB'
        : `Simulator bootstatus failed: ${bootExit}`,
    );
  const args = [
    'test-without-building',
    '-xctestrun',
    testRun,
    '-destination',
    `platform=iOS Simulator,id=${simulator}`,
    '-resultBundlePath',
    join(run, 'result.xcresult'),
    '-parallel-testing-enabled',
    'NO',
  ];
  result.exitCode = await execute('xcodebuild', args, join(run, 'xcodebuild.log'));
  result.fingerprintAfter = await fingerprint();
  result.fingerprintStable = result.fingerprintBefore.sha256 === result.fingerprintAfter.sha256;
  try {
    result.buildBindingAfter = await verifyBuild(appSource);
    assert.deepEqual(
      result.buildBindingAfter,
      result.buildBinding,
      'Build binding changed during test',
    );
    result.buildBindingStable = true;
  } catch (error) {
    result.buildBindingStable = false;
    result.buildBindingError = String(error);
  }
  result.state = {
    ...state,
    offsets: undefined,
    firstOffset: state.offsets[0],
    lastOffset: state.offsets.at(-1),
    requestCount: state.offsets.length,
  };
  result.testPassed =
    result.exitCode === 0 &&
    result.fingerprintStable &&
    result.buildBindingStable &&
    (verifyingScenario
      ? state.publicationUncertain &&
        state.recoveryCompleteBlocked &&
        state.statusAfterRestart > 0 &&
        state.cancelConflict > 0 &&
        state.recoveryCompletes > 0 &&
        state.chunksAfterPublication === 0 &&
        state.publications === 1 &&
        state.hashOK &&
        state.phase === 'completed'
      : cancelScenario
        ? state.cancelDropped &&
          state.cancelBusyInjected === 1 &&
          state.cancelRequests >= 2 &&
          state.statusAfterRestart > 0 &&
          state.chunksAfterCancel === 0 &&
          state.cancelsAfterRestart === 0 &&
          state.publications === 0
        : state.phase === 'completed' &&
          state.publications === 1 &&
          state.hashOK &&
          state.resumedAfterRestart &&
          state.disconnects === 1 &&
          state.timeouts === 1);
  if (result.exitCode !== 0)
    result.logTail = (await readFile(join(run, 'xcodebuild.log'), 'utf8'))
      .replaceAll(token, '[REDACTED]')
      .slice(-12000);
  try {
    result.xcresultSummary = JSON.parse(
      execFileSync(
        'xcrun',
        [
          'xcresulttool',
          'get',
          'test-results',
          'summary',
          '--path',
          join(run, 'result.xcresult'),
          '--format',
          'json',
        ],
        { env, encoding: 'utf8' },
      ),
    );
    await writeFile(
      join(evidence, 'xcresult-summary.json'),
      JSON.stringify(result.xcresultSummary, null, 2),
      { mode: 0o600 },
    );
    const attachments = join(run, 'attachments');
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'export',
        'attachments',
        '--path',
        join(run, 'result.xcresult'),
        '--output-path',
        attachments,
      ],
      { env },
    );
    const exported = JSON.parse(await readFile(join(attachments, 'manifest.json'), 'utf8'));
    await writeFile(
      join(evidence, 'attachments-manifest.json'),
      JSON.stringify(exported, null, 2).replaceAll(token, '[REDACTED]'),
      { mode: 0o600 },
    );
    for (const item of exported
      .flatMap((test) => test.attachments || [])
      .filter((a) => a.suggestedHumanReadableName?.startsWith('upload-diagnostic-'))) {
      await copyFile(
        join(attachments, item.exportedFileName),
        join(evidence, item.exportedFileName),
      );
    }
    for (const name of scenario !== 'resume' || result.exitCode !== 0
      ? []
      : ['upload-in-progress', 'upload-completed']) {
      const matches = exported
        .flatMap((test) => test.attachments || [])
        .filter((a) => a.suggestedHumanReadableName?.includes(name));
      assert.equal(matches.length, 1, `Missing unique screenshot: ${name}`);
      await copyFile(join(attachments, matches[0].exportedFileName), join(evidence, `${name}.png`));
    }
    result.screenshotsSaved = scenario === 'resume' && result.exitCode === 0;
    result.evidenceExtracted = true;
  } catch (error) {
    result.evidenceError = String(error);
    result.testPassed = false;
  }
} catch (error) {
  result.error = String(error.stack || error);
} finally {
  server.closeAllConnections();
  await new Promise((resolvePromise) => server.close(resolvePromise));
  // Free reproducible, task-owned large files before simulator deletion or log writes.
  for (const name of ['Acceptance.app', 'UploadRunner.app', 'store', 'received.bin']) {
    try {
      await rm(join(run, name), { recursive: true, force: true });
    } catch (error) {
      result.cleanupDeferred = String(error);
    }
  }
  if (simulator) {
    try {
      xcrun('simctl', 'shutdown', simulator);
    } catch (error) {
      result.simulatorShutdownError = String(error);
    }
    try {
      xcrun('simctl', 'delete', simulator);
      result.simulatorDeleted = true;
    } catch (e) {
      result.simulatorDeleted = false;
      result.simulatorCleanupError = String(e);
    }
  }
  for (const name of ['compile.log', 'boot.log', 'xcodebuild.log']) {
    try {
      await writeFile(
        join(evidence, name),
        (await readFile(join(run, name), 'utf8')).replaceAll(token, '[REDACTED]'),
        { mode: 0o600 },
      );
    } catch (e) {
      if (e.code !== 'ENOENT') result.cleanupDeferred = `Could not preserve ${name}: ${String(e)}`;
    }
  }
  // Preserve an unextractable result for recovery rather than discarding the only evidence.
  if (result.exitCode !== undefined && !result.evidenceExtracted) {
    try {
      await rename(join(run, 'result.xcresult'), join(evidence, 'unextracted.xcresult'));
      result.resultRetained = true;
    } catch (error) {
      if (error.code !== 'ENOENT') {
        result.cleanupDeferred = String(error);
      }
    }
  }
  await writeFile(report, JSON.stringify(result, null, 2), { mode: 0o600 });
  if (!result.cleanupDeferred) await rm(run, { recursive: true, force: true });
  console.log(
    JSON.stringify({
      report,
      testPassed: result.testPassed ?? false,
      error: result.error,
      simulatorDeleted: result.simulatorDeleted,
    }),
  );
}
if (!result.testPassed) process.exitCode = 1;
