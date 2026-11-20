import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { cp, mkdir, readFile, writeFile, symlink, statfs, rm, access } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve } from 'node:path';

// Synthetic read-only Host. It has no desktop or model connection.
const dir = resolve(process.env.MESSAGE_MEDIA_UI_DIR ?? '.local/multimedia-20261120/ui');
const snapshot = join(dir, 'snapshot'),
  build = join(dir, 'build');
await mkdir(dir, { recursive: true, mode: 0o700 });
for (const path of [snapshot, build, join(dir, 'native.xcresult')]) {
  const exists = await access(path).then(
    () => true,
    () => false,
  );
  assert(
    !exists,
    'Retain existing task data; choose a fresh result location before rerunning: ' + path,
  );
}
const free = async () => {
  const s = await statfs(dir);
  return s.bavail * s.bsize;
};
assert((await free()) >= 6 * 1024 ** 3, 'Less than 6 GiB free: do not start native build');
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
};
const run = (bin, args) => execFileSync(bin, args, { encoding: 'utf8', env }).trim();
const hostId = 'media-fixture-host',
  threadId = 'media-fixture-thread',
  messageId = 'media-fixture-answer';
const token = randomBytes(32).toString('base64url');
const target = { agentId: 'codex', hostId, workspaceId: hostId, runtimeId: 'desktop-runtime' };
const capabilities = ['history:read', 'events'];
const thread = {
  id: threadId,
  hostId,
  projectId: 'media-project',
  title: 'Media Fixture',
  state: 'idle',
};
const markdown =
  '![图片](/fixture/image.png)\n\n![视频](/fixture/video.mp4)\n\n```markdown\n![代码块图片](/fixture/code.png)\n```\n\n![缺失](/fixture/missing.png)';
const history = {
  readTarget: target,
  hostId,
  threadId,
  thread,
  nextCursor: null,
  messages: [{ id: messageId, role: 'assistant', text: markdown, status: 'completed', files: [] }],
  runtime: {
    state: 'idle',
    turnId: null,
    plan: [],
    receipts: [],
    canControl: false,
    approvals: [],
  },
};
run(process.env.MESSAGE_MEDIA_FFMPEG ?? 'ffmpeg', [
  '-loglevel',
  'error',
  '-y',
  '-f',
  'lavfi',
  '-i',
  'color=c=blue:s=64x64:d=1:r=10',
  '-c:v',
  'libx264',
  '-pix_fmt',
  'yuv420p',
  '-movflags',
  '+faststart',
  join(dir, 'video.mp4'),
]);
run(process.env.MESSAGE_MEDIA_FFMPEG ?? 'ffmpeg', [
  '-loglevel',
  'error',
  '-y',
  '-f',
  'lavfi',
  '-i',
  'color=c=red:s=64x64',
  '-frames:v',
  '1',
  '-threads',
  '1',
  join(dir, 'image.png'),
]);
const files = {};
for (const name of ['image.png', 'video.mp4']) {
  const bytes = await readFile(join(dir, name));
  files[name] = { bytes, sha256: createHash('sha256').update(bytes).digest('hex') };
}
const counters = {
  resolve: 0,
  download: 0,
  imageResolve: 0,
  imageDownload: 0,
  videoResolve: 0,
  videoDownload: 0,
  missingResolve: 0,
};
const requests = [];
const json = (res, body, status = 200) =>
  res
    .writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    .end(JSON.stringify(body));
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.headers.authorization !== 'Bearer ' + token)
      return json(res, { error: 'Unauthorized' }, 401);
    if (url.pathname === '/counts') return json(res, counters);
    if (url.pathname === '/v1/workspace')
      return json(res, {
        hostId,
        name: 'Media fixture',
        connectionMode: 'desktop-runtime',
        readTarget: target,
        clientType: { id: 'codex', name: 'Codex' },
        capabilities,
        projects: [{ id: 'media-project', hostId, name: 'Fixture', roots: [] }],
        threads: [thread],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.pathname === '/v1/models') return json(res, { models: [] });
    if (url.pathname === '/v1/history' || url.pathname === '/v1/events') {
      assert.equal(url.searchParams.get('hostId'), hostId);
      assert.equal(url.searchParams.get('threadId'), threadId);
      if (url.pathname === '/v1/history') return json(res, history);
      await new Promise((r) => setTimeout(r, 200));
      return json(res, {
        hostId,
        epoch: 'media-fixture',
        cursor: 1,
        reset: false,
        ready: true,
        capabilities,
        events:
          Number(url.searchParams.get('after') ?? -1) < 1 ? [{ threadId, snapshot: history }] : [],
      });
    }
    if (url.pathname === '/v1/threads/read') return json(res, { ok: true });
    if (url.pathname === '/v1/attachments/resolve-link') {
      assert.equal(url.searchParams.get('hostId'), hostId);
      assert.equal(url.searchParams.get('threadId'), threadId);
      assert.equal(url.searchParams.get('messageId'), messageId);
      const reference = url.searchParams.get('reference');
      assert(
        ['/fixture/image.png', '/fixture/video.mp4', '/fixture/missing.png'].includes(reference),
      );
      const name = reference.split('/').at(-1);
      counters.resolve++;
      counters[
        name.startsWith('image')
          ? 'imageResolve'
          : name.startsWith('video')
            ? 'videoResolve'
            : 'missingResolve'
      ]++;
      requests.push({ route: 'resolve-link', hostId, threadId, messageId, reference });
      if (name === 'missing.png')
        return json(res, { error: 'fixture missing original error' }, 404);
      const f = files[name],
        attachment = {
          id: name,
          hostId,
          threadId,
          name,
          mediaType: name.endsWith('.png') ? 'image/png' : 'video/mp4',
          sizeBytes: f.bytes.length,
          sha256: f.sha256,
        };
      return json(res, { hostId, threadId, messageId, reference, readTarget: target, attachment });
    }
    if (url.pathname === '/v1/attachments/resolve') {
      assert.equal(url.searchParams.get('hostId'), hostId);
      assert.equal(url.searchParams.get('threadId'), threadId);
      return json(res, { error: 'legacy fixture' }, 404);
    }
    if (url.pathname === '/v1/attachments') {
      assert.equal(url.searchParams.get('hostId'), hostId);
      assert.equal(url.searchParams.get('threadId'), threadId);
      assert.deepEqual(JSON.parse(url.searchParams.get('target')), target);
      const name = url.searchParams.get('id'),
        f = files[name];
      assert(f);
      counters.download++;
      counters[name.startsWith('image') ? 'imageDownload' : 'videoDownload']++;
      requests.push({
        route: 'download',
        hostId,
        threadId,
        target,
        id: name,
        bytes: f.bytes.length,
        sha256: f.sha256,
      });
      return res
        .writeHead(200, {
          'content-type': 'application/octet-stream',
          'content-length': f.bytes.length,
        })
        .end(f.bytes);
    }
    requests.push({ route: url.pathname, method: req.method, unsupported: true });
    return json(res, { error: 'Unsupported fixture route' }, 404);
  } catch (e) {
    requests.push({ error: e.message, route: req.url });
    return json(res, { error: e.message }, 500);
  }
});
let device, child, report;
try {
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  const endpoint = 'http://127.0.0.1:' + server.address().port;
  const fixture = {
    endpoint,
    token,
    hostId,
    threadId,
    threadTitle: thread.title,
    nonce: 'synthetic',
    secondEndpoint: endpoint,
    secondToken: token,
    secondHostId: hostId,
    delayedEndpoint: endpoint,
  };
  await mkdir(join(snapshot, 'apps'), { recursive: true });
  await cp('apps/ios', join(snapshot, 'apps/ios'), { recursive: true });
  await mkdir(join(snapshot, '.build/native'), { recursive: true });
  await symlink(resolve('.build/native/mobile-core'), join(snapshot, '.build/native/mobile-core'));
  await mkdir(join(snapshot, 'scripts/mobile'), { recursive: true });
  await writeFile(
    join(snapshot, 'scripts/mobile/build-ios-core.sh'),
    '#!/bin/sh\nset -eu\ntest -d "$SRCROOT/../../.build/native/mobile-core/xcode-frameworks/$CONFIGURATION/$SDK_NAME/MobileCore.framework"\n',
    { mode: 0o755 },
  );
  await mkdir(build, { recursive: true });
  await cp('.build/ios/SourcePackages', join(build, 'SourcePackages'), { recursive: true });
  const testPath = join(snapshot, 'apps/ios/Tests/CodexManageUITests.swift');
  const original = await readFile(testPath, 'utf8'),
    anchor = '  @MainActor func testOriginalDesktopPinnedSidebarDEV()';
  assert(original.includes(anchor));
  await writeFile(
    testPath,
    original.replace(
      anchor,
      (await readFile('scripts/fixtures/message-media-native.swift', 'utf8')) + anchor,
    ),
  );
  const sources = {};
  for (const file of [
    'apps/ios/Sources/MessageLinks.swift',
    'apps/ios/Sources/RootView.swift',
    'scripts/fixtures/message-media-native.swift',
  ])
    sources[file] = createHash('sha256')
      .update(await readFile(file))
      .digest('hex');
  report = {
    status: 'running',
    sources,
    freeBefore: await free(),
    media: Object.fromEntries(
      Object.entries(files).map(([k, v]) => [k, { bytes: v.bytes.length, sha256: v.sha256 }]),
    ),
    realModelInputs: 0,
  };
  await writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2));
  device = run('xcrun', [
    'simctl',
    'create',
    'Message Media Fixture 20261120',
    'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro',
    'com.apple.CoreSimulator.SimRuntime.iOS-26-0',
  ]);
  report.simulator = device;
  await writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2));
  run('xcrun', ['simctl', 'boot', device]);
  run('xcrun', ['simctl', 'bootstatus', device, '-b']);
  assert((await free()) >= 6 * 1024 ** 3, 'Less than 6 GiB before compilation');
  const log = createWriteStream(join(dir, 'xcodebuild.log'));
  const args = [
    '-project',
    join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + device,
    '-derivedDataPath',
    build,
    '-resultBundlePath',
    join(dir, 'native.xcresult'),
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ONLY_ACTIVE_ARCH=YES',
    '-only-testing:CodexManageUITests/CodexManageUITests/testMessageMediaSyntheticNative',
    'test',
  ];
  child = spawn('xcodebuild', args, {
    env: { ...env, TEST_RUNNER_MESSAGE_MEDIA_FIXTURE_JSON: JSON.stringify(fixture) },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.pipe(log);
  child.stderr.pipe(log, { end: false });
  const monitor = setInterval(async () => {
    if ((await free()) < 6 * 1024 ** 3 && child?.exitCode === null) {
      report.diskStop = true;
      child.kill('SIGTERM');
    }
  }, 3000);
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  clearInterval(monitor);
  await new Promise((done) => log.end(done));
  child = undefined;
  report.exitCode = code;
  try {
    const binary = join(build, 'Build/Products/Debug-iphonesimulator/CodexManage.app/CodexManage');
    report.binarySha256 = createHash('sha256')
      .update(await readFile(binary))
      .digest('hex');
  } catch (error) {
    report.binaryHashError = error.message;
  }
  report.counters = counters;
  report.requests = requests;
  report.freeAfter = await free();
  try {
    report.summary = JSON.parse(
      run('xcrun', [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(dir, 'native.xcresult'),
        '--format',
        'json',
      ]),
    );
  } catch (error) {
    report.summaryError = error.message;
  }
  report.status = code === 0 && report.summary?.passedTests === 1 ? 'passed' : 'failed';
  await writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2));
  console.log(
    JSON.stringify({
      status: report.status,
      exitCode: code,
      counters,
      summary: report.summary,
      diskStop: report.diskStop,
    }),
  );
  assert.equal(report.status, 'passed');
} catch (error) {
  report ??= { realModelInputs: 0 };
  report.status = 'failed';
  report.error = error.message;
  report.counters = counters;
  report.requests = requests;
  report.freeAtFailure = await free();
  await writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2));
  throw error;
} finally {
  let buildStopped = true;
  if (child && child.exitCode === null) {
    const running = child;
    let timer;
    const closed = new Promise((done) => running.once('close', () => done(true)));
    running.kill('SIGTERM');
    buildStopped = await Promise.race([
      closed,
      new Promise((done) => {
        timer = setTimeout(() => done(false), 20000);
      }),
    ]);
    clearTimeout(timer);
  }
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  if (device) {
    try {
      run('xcrun', ['simctl', 'shutdown', device]);
    } catch (error) {
      report ??= {};
      report.shutdownError = error.message;
    }
    try {
      run('xcrun', ['simctl', 'delete', device]);
    } catch (error) {
      report ??= {};
      report.deviceCleanupError = error.message;
    }
  }
  // These exact directories were created by this run; artifacts remain outside them.
  if (buildStopped) {
    await rm(snapshot, { recursive: true, force: true });
    await rm(build, { recursive: true, force: true });
  } else {
    report ??= {};
    report.cleanupPending = {
      pid: child.pid,
      snapshot,
      build,
      reason: 'Build has not confirmed exit',
    };
  }
  if (report) {
    report.freeAfterCleanup = await free();
    await writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2));
  }
}
