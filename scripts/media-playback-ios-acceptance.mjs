import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import {
  access,
  chmod,
  cp,
  mkdir,
  mkdtemp,
  open,
  readFile,
  rm,
  stat,
  statfs,
  writeFile,
} from 'node:fs/promises';
import { spawn, execFileSync } from 'node:child_process';
import { dirname, join, relative, resolve } from 'node:path';

const root = resolve('.'),
  base = resolve('.local/media-streaming-20261121/mv2');
const marker = join(base, 'source-ready.json');
const markerHash = process.argv[2];
assert(
  /^[a-f0-9]{64}$/.test(markerHash ?? ''),
  'Pass the SHA-256 of the explicitly frozen source-ready.json after receiving source-ready notification',
);
const env = {
  ...process.env,
  DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
  PATH: dirname(process.execPath) + ':' + process.env.PATH,
  NODE_BINARY: process.execPath,
};
const exists = (p) =>
  access(p).then(
    () => true,
    () => false,
  );
const free = async () => {
  const s = await statfs(root);
  return s.bavail * s.bsize;
};
const requireSpace = async () =>
  assert((await free()) >= 6 * 1024 ** 3, 'Less than 6 GiB free; refusing build');
const run = (bin, args, opts = {}) =>
  execFileSync(bin, args, { env, encoding: 'utf8', ...opts }).trim();
const hash = async (p) => {
  const h = createHash('sha256');
  for await (const b of createReadStream(p)) h.update(b);
  return h.digest('hex');
};
const verifySeekEvidence = (rows) => {
  const seeks = rows.filter((row) => row.event === 'seek');
  assert(seeks.length, 'Missing seek event');
  const seek = seeks.at(-1);
  const landing = rows.find(
    (row) =>
      row.event === 'time' &&
      row.elapsed > seek.elapsed &&
      row.phase === 'playing' &&
      row.duration > 0 &&
      row.position / row.duration >= 0.75,
  );
  assert(landing, 'No playing time after seek near target');
  assert(
    landing.position / landing.duration <= 0.88,
    'First playing position after seek exceeds 88%',
  );
  assert(
    rows.some(
      (row) =>
        row.event === 'time' &&
        row.elapsed >= landing.elapsed + 2 &&
        row.phase === 'playing' &&
        row.position > landing.position,
    ),
    'Playback did not advance after seek',
  );
  return { position: landing.position, duration: landing.duration, elapsed: landing.elapsed };
};
const atoms = async (path) => {
  const f = await open(path, 'r');
  try {
    const size = (await f.stat()).size,
      result = [];
    let offset = 0;
    while (offset + 8 <= size) {
      const b = Buffer.alloc(16);
      await f.read(b, 0, 16, offset);
      let length = b.readUInt32BE(0);
      const type = b.toString('ascii', 4, 8);
      if (length === 1) length = Number(b.readBigUInt64BE(8));
      if (length === 0) length = size - offset;
      assert(length >= 8 && offset + length <= size, 'Invalid MP4 atom ' + type);
      result.push({ type, offset, length });
      offset += length;
    }
    assert.equal(offset, size);
    return result;
  } finally {
    await f.close();
  }
};
const write = async (p, obj) => writeFile(p, JSON.stringify(obj, null, 2) + '\n', { mode: 0o600 });
const copy = async (from, to) => {
  await mkdir(resolve(to, '..'), { recursive: true, mode: 0o700 });
  await cp(from, to, {
    recursive: true,
    filter: (p) =>
      !relative(root, resolve(p))
        .split('/')
        .some((part) =>
          /^(?:AppStore|videos|screenshots|xcuserdata|\.git|build|\.build|\.gradle|\.kotlin|node_modules)$/i.test(
            part,
          ),
        ),
  });
};
const execLog = (bin, args, log, extra = {}) =>
  new Promise((ok, reject) => {
    const out = createWriteStream(log, { mode: 0o600 });
    const child = spawn(bin, args, {
      env: { ...env, ...extra },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.pipe(out);
    child.stderr.pipe(out, { end: false });
    let monitor = setInterval(async () => {
      if ((await free()) < 6 * 1024 ** 3) child.kill('SIGTERM');
    }, 3000);
    child.on('error', reject);
    child.on('close', (code) => {
      clearInterval(monitor);
      out.end(() => ok(code));
    });
  });
await mkdir(base, { recursive: true, mode: 0o700 });
const dir = await mkdtemp(join(base, 'run-'));
await chmod(dir, 0o700);
const snapshot = join(dir, 'snapshot'),
  build = join(dir, 'derived'),
  video = join(dir, 'video.mp4');
const report = {
  status: 'waiting-source',
  createdAt: new Date().toISOString(),
  scope: { realDevice: false, microphone: false, remoteContact: false, realModelInputs: 0 },
  execution: {
    requestedModel: 'gpt-6.1-terra',
    requestedReasoning: 'medium',
    actualSettings: 'pending verification by coordinating session',
    speed: 'interface unavailable',
  },
  node: process.version,
  xcode: run('xcodebuild', ['-version']),
  system: run('sw_vers', []),
  freeBefore: await free(),
};
await write(join(dir, 'report.json'), report);
console.log('Run directory: ' + dir);
let host,
  device,
  active = false;
try {
  assert.equal(
    await hash(marker),
    markerHash,
    'Frozen source-ready marker does not match the explicitly supplied hash',
  );
  report.sourceReady = JSON.parse(await readFile(marker, 'utf8'));
  for (const [path, expected] of Object.entries(report.sourceReady.hostSourceFiles ?? {}))
    assert.equal(
      await hash(path),
      expected,
      'Frozen Host source differs from source-ready: ' + path,
    );
  report.status = 'preparing';
  await write(join(dir, 'report.json'), report);
  await requireSpace();
  for (const path of [
    'apps/ios',
    'packages/mobile-core',
    'packages/protocol/schemas',
    'packages/protocol/fixtures',
    'apps/android',
    'gradle',
    'gradlew',
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'scripts/mobile/gradle.mjs',
    'scripts/mobile/build-ios-core.sh',
    '.local/mobile-toolchain.json',
  ])
    if (await exists(path)) await copy(path, join(snapshot, path));
  report.sources = {};
  for (const path of [
    'apps/ios/Sources/MediaPlayback.swift',
    'apps/ios/Sources/AgentClient.swift',
    'apps/ios/Sources/RootView.swift',
    'apps/ios/Sources/MessageLinks.swift',
    'apps/ios/Sources/FilePreview.swift',
    'apps/ios/CodexManage.xcodeproj/project.pbxproj',
    'apps/host-agent/src/runtime-server.ts',
    'apps/host-agent/src/attachment-reads.ts',
    'packages/protocol/src/attachment-reads.ts',
    'packages/codex-adapter/src/runtime-session.ts',
    'apps/host-agent/dist/runtime-server.js',
    'apps/host-agent/dist/attachment-reads.js',
    'packages/protocol/dist/attachment-reads.js',
    'packages/codex-adapter/dist/runtime-session.js',
    'scripts/media-playback-ios-acceptance.mjs',
    'scripts/fixtures/media-playback-host.mjs',
    'scripts/fixtures/media-playback-ios-tests.swift',
  ])
    if (await exists(path)) report.sources[path] = await hash(path);
  for (const [path, expected] of Object.entries(report.sourceReady.files ?? {}))
    assert.equal(
      await hash(join(snapshot, path)),
      expected,
      'Frozen source differs from source-ready: ' + path,
    );
  const test = join(snapshot, 'apps/ios/Tests/CodexManageUITests.swift'),
    original = await readFile(test, 'utf8'),
    anchor = '  @MainActor func testOriginalDesktopPinnedSidebarDEV()';
  assert(original.includes(anchor));
  let modified = original.replace(
    anchor,
    (await readFile('scripts/fixtures/media-playback-ios-tests.swift', 'utf8')) + '\n' + anchor,
  );
  if (!modified.includes('import Security')) modified = 'import Security\n' + modified;
  const setup =
    '    app.launchArguments = name.contains("testReplyWaitReceiptReadOnlyRecoveryDEV")';
  assert(modified.includes(setup));
  modified = modified.replace(
    setup,
    '    if name.contains("testMediaPlayback") { app.launchArguments = ["-ui-testing", "-restore-testing", "-media-streaming-acceptance"]; if ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_TRACE"] == "0" { app.launchArguments.append("-media-streaming-no-trace") }; app.launchChineseTest(); XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20)); return }\n' +
      setup,
  );
  await writeFile(test, modified, { mode: 0o600 });
  // Kotlin builds inside the task snapshot; the existing Gradle and Kotlin caches are inputs, not copied.
  await requireSpace();
  const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
  const sdkName =
    'iphonesimulator' + run('xcrun', ['--sdk', 'iphonesimulator', '--show-sdk-version']);
  const kenv = {
    KOTLIN_FRAMEWORK_BUILD_TYPE: 'debug',
    SDK_NAME: 'iphonesimulator',
    CONFIGURATION: 'Debug',
    ARCHS: 'arm64',
    PLATFORM_NAME: 'iphonesimulator',
    EFFECTIVE_PLATFORM_NAME: '-iphonesimulator',
    TARGET_BUILD_DIR: join(snapshot, '.build/native/mobile-core/xcode-frameworks/Debug', sdkName),
    FRAMEWORKS_FOLDER_PATH: '',
    CODE_SIGNING_ALLOWED: 'NO',
    JAVA_HOME: local.javaHome,
    KOTLIN_NATIVE_HOME: local.kotlinNativeHome,
    ANDROID_HOME: local.androidSdk,
    GRADLE_USER_HOME: resolve('.local/gradle-home'),
  };
  await mkdir(kenv.TARGET_BUILD_DIR, { recursive: true, mode: 0o700 });
  let code = await execLog(
    process.execPath,
    [
      join(snapshot, 'scripts/mobile/gradle.mjs'),
      ':mobile-core:linkDebugFrameworkIosSimulatorArm64',
      '--no-daemon',
      '--offline',
    ],
    join(dir, 'gradle.log'),
    kenv,
  );
  assert.equal(code, 0, 'KMP framework build failed; see gradle.log');
  const builtFramework = join(
    snapshot,
    '.build/native/mobile-core/bin/iosSimulatorArm64/debugFramework/MobileCore.framework',
  );
  assert(await exists(builtFramework), 'KMP framework missing after build');
  await cp(builtFramework, join(kenv.TARGET_BUILD_DIR, 'MobileCore.framework'), {
    recursive: true,
  });
  const framework = join(kenv.TARGET_BUILD_DIR, 'MobileCore.framework/MobileCore');
  report.frameworkSha256 = await hash(framework);
  await writeFile(
    join(snapshot, 'scripts/mobile/build-ios-core.sh'),
    '#!/bin/sh\nset -eu\ntest -f "$SRCROOT/../../.build/native/mobile-core/xcode-frameworks/$CONFIGURATION/$SDK_NAME/MobileCore.framework/MobileCore"\n',
    { mode: 0o755 },
  );
  await requireSpace();
  code = await execLog(
    'ffmpeg',
    [
      '-hide_banner',
      '-loglevel',
      'error',
      '-y',
      '-f',
      'lavfi',
      '-i',
      'testsrc2=size=960x540:rate=24:duration=240',
      '-f',
      'lavfi',
      '-i',
      'sine=frequency=440:sample_rate=48000:duration=240',
      '-c:v',
      'libx264',
      '-preset',
      'ultrafast',
      '-b:v',
      '5M',
      '-minrate',
      '5M',
      '-maxrate',
      '5M',
      '-bufsize',
      '10M',
      '-x264-params',
      'nal-hrd=cbr:force-cfr=1',
      '-pix_fmt',
      'yuv420p',
      '-c:a',
      'aac',
      '-b:a',
      '128k',
      '-movflags',
      '+faststart',
      '-shortest',
      video,
    ],
    join(dir, 'ffmpeg.log'),
  );
  assert.equal(code, 0, 'video generation failed');
  assert((await stat(video)).size >= 100 * 1024 ** 2, 'Encoded sample must be at least 100 MiB');
  const tailVideo = join(dir, 'video-tail-moov.mp4');
  code = await execLog(
    'ffmpeg',
    ['-hide_banner', '-loglevel', 'error', '-y', '-i', video, '-map', '0', '-c', 'copy', tailVideo],
    join(dir, 'ffmpeg-tail.log'),
  );
  assert.equal(code, 0, 'tail-moov remux failed');
  const probe = JSON.parse(
    run('ffprobe', [
      '-v',
      'error',
      '-show_entries',
      'stream=codec_name,codec_type',
      '-of',
      'json',
      video,
    ]),
  );
  assert(
    probe.streams.some((s) => s.codec_type === 'video' && s.codec_name === 'h264') &&
      probe.streams.some((s) => s.codec_type === 'audio' && s.codec_name === 'aac'),
    'Expected H264 and AAC streams',
  );
  const fastAtoms = await atoms(video),
    tailAtoms = await atoms(tailVideo);
  assert(
    fastAtoms.findIndex((a) => a.type === 'moov') < fastAtoms.findIndex((a) => a.type === 'mdat'),
  );
  assert(
    tailAtoms.findIndex((a) => a.type === 'moov') > tailAtoms.findIndex((a) => a.type === 'mdat'),
  );
  report.samples = {
    fast: {
      size: (await stat(video)).size,
      sha256: await hash(video),
      atoms: fastAtoms,
      streams: probe.streams,
    },
    tail: {
      size: (await stat(tailVideo)).size,
      sha256: await hash(tailVideo),
      atoms: tailAtoms,
      streams: probe.streams,
      nativeStatus: 'not tested',
    },
  };
  const { startPlaybackHost } = await import('./fixtures/media-playback-host.mjs');
  host = await startPlaybackHost(dir, process.argv[3] === '--only-tail' ? tailVideo : video);
  assert(host.size > 8 * 1024 ** 2, 'video is too small for first-frame bound');
  report.media = {
    sha256: host.sha256,
    size: host.size,
    ffmpeg: run('ffmpeg', ['-version']).split('\n')[0],
  };
  await mkdir(join(build, 'SourcePackages'), { recursive: true });
  await cp('.build/ios/SourcePackages', join(build, 'SourcePackages'), { recursive: true });
  device = run('xcrun', [
    'simctl',
    'create',
    'Media Playback ' + Date.now(),
    'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro',
    'com.apple.CoreSimulator.SimRuntime.iOS-26-0',
  ]);
  report.simulator = device;
  await write(join(dir, 'report.json'), report);
  run('xcrun', ['simctl', 'boot', device]);
  run('xcrun', ['simctl', 'bootstatus', device, '-b']);
  await requireSpace();
  if (['--only-lease', '--focused-ui'].includes(process.argv[3])) {
    const focused = process.argv[3] === '--focused-ui';
    const caseKey = focused ? 'focusedCases' : 'leaseCases';
    report.status = focused ? 'testing-focused-ui' : 'testing-lease';
    report[caseKey] = {};
    await write(join(dir, 'report.json'), report);
    const portraitVideo = join(dir, 'portrait.mp4');
    if (focused) {
      await requireSpace();
      const portraitCode = await execLog(
        'ffmpeg',
        [
          '-hide_banner',
          '-loglevel',
          'error',
          '-y',
          '-f',
          'lavfi',
          '-i',
          'testsrc2=size=540x960:rate=24:duration=30',
          '-f',
          'lavfi',
          '-i',
          'sine=frequency=440:sample_rate=48000:duration=30',
          '-c:v',
          'libx264',
          '-preset',
          'ultrafast',
          '-b:v',
          '2M',
          '-pix_fmt',
          'yuv420p',
          '-c:a',
          'aac',
          '-b:a',
          '128k',
          '-movflags',
          '+faststart',
          '-shortest',
          portraitVideo,
        ],
        join(dir, 'portrait-ffmpeg.log'),
      );
      assert.equal(portraitCode, 0, 'Portrait video generation failed');
      const portraitProbe = JSON.parse(
        run('ffprobe', [
          '-v',
          'error',
          '-show_entries',
          'stream=codec_name,codec_type,width,height',
          '-of',
          'json',
          portraitVideo,
        ]),
      );
      assert(
        portraitProbe.streams.some(
          (s) => s.codec_name === 'h264' && s.width === 540 && s.height === 960,
        ),
      );
      assert(portraitProbe.streams.some((s) => s.codec_name === 'aac' && s.codec_type === 'audio'));
      report.samples.portrait = {
        sha256: await hash(portraitVideo),
        size: (await stat(portraitVideo)).size,
        streams: portraitProbe.streams,
      };
      await rm(tailVideo, { force: true });
    }
    const leaseTests = focused
      ? [
          ['core', 'testMediaPlaybackStreamingNative'],
          ['attachment-octet', 'testMediaPlaybackAttachmentEntryNative'],
          ['portrait', 'testMediaPlaybackPortraitNative'],
          ['short', 'testMediaPlaybackLeaseNative'],
          ['renew-forbidden', 'testMediaPlaybackLeaseForbiddenNative'],
          ['renew-version', 'testMediaPlaybackLeaseVersionNative'],
        ]
      : [
          ['short', 'testMediaPlaybackLeaseNative'],
          ['renew-forbidden', 'testMediaPlaybackLeaseForbiddenNative'],
          ['renew-version', 'testMediaPlaybackLeaseVersionNative'],
          ['attachment-octet', 'testMediaPlaybackAttachmentEntryNative'],
        ];
    for (const [name, testName] of leaseTests) {
      if (host) {
        await host.close();
        host = undefined;
        for (const path of [
          join(dir, 'host-state'),
          join(dir, 'transfers'),
          join(dir, 'document.md'),
        ])
          await rm(path, { recursive: true, force: true });
      }
      const hostDir = join(dir, `case-host-lease-${name}`);
      await mkdir(hostDir, { mode: 0o700 });
      host = await startPlaybackHost(
        hostDir,
        focused && name === 'portrait' ? portraitVideo : video,
      );
      const resultPath = join(dir, `lease-${name}.xcresult`);
      active = true;
      const exitCode = await execLog(
        'xcodebuild',
        [
          '-project',
          join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
          '-scheme',
          'CodexManage',
          '-destination',
          'platform=iOS Simulator,id=' + device,
          '-derivedDataPath',
          build,
          '-resultBundlePath',
          resultPath,
          '-parallel-testing-enabled',
          'NO',
          '-skipPackageUpdates',
          '-disableAutomaticPackageResolution',
          '-collect-test-diagnostics',
          'never',
          'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
          'CODE_SIGNING_ALLOWED=YES',
          'CODE_SIGN_IDENTITY=-',
          'ONLY_ACTIVE_ARCH=YES',
          `-only-testing:CodexManageUITests/CodexManageUITests/${testName}`,
          'test',
        ],
        join(dir, `lease-${name}.log`),
        { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
      );
      active = false;
      let summary;
      try {
        summary = JSON.parse(
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
      } catch (e) {
        summary = { error: e.message };
      }
      try {
        run('xcrun', [
          'xcresulttool',
          'export',
          'attachments',
          '--path',
          resultPath,
          '--output-path',
          join(dir, `lease-${name}-screenshots`),
        ]);
      } catch (e) {
        summary.screenshotError = e.message;
      }
      try {
        const container = run('xcrun', [
          'simctl',
          'get_app_container',
          device,
          'com.easyai.agenthub.acceptance.mediaplayback',
          'data',
        ]);
        await cp(
          join(container, 'Library/Caches/media-playback-evidence.json'),
          join(dir, `lease-${name}-playback-evidence.json`),
        );
        await chmod(join(dir, `lease-${name}-playback-evidence.json`), 0o600);
      } catch (e) {
        summary.evidenceError = e.message;
      }
      const binary = join(
        build,
        'Build/Products/Debug-iphonesimulator/CodexManage.app/CodexManage',
      );
      if (await exists(binary)) report.binarySha256 = await hash(binary);
      if (focused && name === 'core') {
        try {
          const rows = JSON.parse(
            await readFile(join(dir, `lease-${name}-playback-evidence.json`), 'utf8'),
          );
          const ids = [...new Set(rows.map((row) => row.id))];
          assert.equal(ids.length, 1, 'Core must preserve one session');
          for (const event of ['first-frame', 'seek', 'closed'])
            assert(rows.some((row) => row.event === event));
          summary.sessionId = ids[0];
          summary.seekLanding = verifySeekEvidence(rows);
        } catch (e) {
          summary.evidenceError = e.message;
        }
      }
      report[caseKey][name] = {
        exitCode,
        summary,
        counts: host.counts,
        requests: host.requests,
        passed:
          exitCode === 0 &&
          summary.passedTests === 1 &&
          !(focused && name === 'core' && summary.evidenceError),
      };
      await write(join(dir, 'report.json'), report);
      await host.close();
      host = undefined;
      await rm(hostDir, { recursive: true, force: true });
    }
    report.status = Object.values(report[caseKey]).every((item) => item.passed)
      ? 'passed'
      : 'failed';
    throw new Error('LEASE_ONLY_COMPLETED');
  }
  if (['context-only', '--only-attachment'].includes(process.argv[3])) {
    const singleName = process.argv[3] === 'context-only' ? 'context' : 'attachment-octet';
    const singleTest =
      singleName === 'context'
        ? 'testMediaPlaybackContextExitNative'
        : 'testMediaPlaybackAttachmentEntryNative';
    report.status = `testing-${singleName}`;
    await write(join(dir, 'report.json'), report);
    active = true;
    code = await execLog(
      'xcodebuild',
      [
        '-project',
        join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
        '-scheme',
        'CodexManage',
        '-destination',
        'platform=iOS Simulator,id=' + device,
        '-derivedDataPath',
        build,
        '-resultBundlePath',
        join(dir, `${singleName}.xcresult`),
        '-parallel-testing-enabled',
        'NO',
        '-skipPackageUpdates',
        '-disableAutomaticPackageResolution',
        '-collect-test-diagnostics',
        'never',
        'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
        'CODE_SIGNING_ALLOWED=YES',
        'CODE_SIGN_IDENTITY=-',
        'ONLY_ACTIVE_ARCH=YES',
        `-only-testing:CodexManageUITests/CodexManageUITests/${singleTest}`,
        'test',
      ],
      join(dir, `${singleName}.log`),
      { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
    );
    active = false;
    report.contextExitCode = code;
    try {
      report.contextSummary = JSON.parse(
        run('xcrun', [
          'xcresulttool',
          'get',
          'test-results',
          'summary',
          '--path',
          join(dir, `${singleName}.xcresult`),
          '--format',
          'json',
        ]),
      );
    } catch (e) {
      report.contextSummaryError = e.message;
    }
    try {
      run('xcrun', [
        'xcresulttool',
        'export',
        'attachments',
        '--path',
        join(dir, `${singleName}.xcresult`),
        '--output-path',
        join(dir, `${singleName}-screenshots`),
      ]);
    } catch (e) {
      report.contextScreenshotError = e.message;
    }
    const binary = join(build, 'Build/Products/Debug-iphonesimulator/CodexManage.app/CodexManage');
    if (await exists(binary)) report.binarySha256 = await hash(binary);
    try {
      const container = run('xcrun', [
        'simctl',
        'get_app_container',
        device,
        'com.easyai.agenthub.acceptance.mediaplayback',
        'data',
      ]);
      await cp(
        join(container, 'Library/Caches/media-playback-evidence.json'),
        join(dir, `${singleName}-playback-evidence.json`),
      );
      await chmod(join(dir, `${singleName}-playback-evidence.json`), 0o600);
    } catch (e) {
      report.contextEvidenceError = e.message;
    }
    report.status = code === 0 && report.contextSummary?.passedTests === 1 ? 'passed' : 'failed';
    report.singleCase = singleName;
    throw new Error('CONTEXT_ONLY_COMPLETED');
  }
  report.status = 'testing';
  await write(join(dir, 'report.json'), report);
  active = true;
  code = await execLog(
    'xcodebuild',
    [
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
      '-skipPackageUpdates',
      '-disableAutomaticPackageResolution',
      '-collect-test-diagnostics',
      'never',
      'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'ONLY_ACTIVE_ARCH=YES',
      '-only-testing:CodexManageUITests/CodexManageUITests/testMediaPlaybackStreamingNative',
      'test',
    ],
    join(dir, 'xcodebuild.log'),
    { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
  );
  active = false;
  report.exitCode = code;
  const binary = join(build, 'Build/Products/Debug-iphonesimulator/CodexManage.app/CodexManage');
  if (await exists(binary)) report.binarySha256 = await hash(binary);
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
  } catch (e) {
    report.summaryError = e.message;
  }
  try {
    const container = run('xcrun', [
      'simctl',
      'get_app_container',
      device,
      'com.easyai.agenthub.acceptance.mediaplayback',
      'data',
    ]);
    const file = join(container, 'Library/Caches/media-playback-evidence.json');
    if (await exists(file)) {
      await cp(file, join(dir, 'playback-evidence.json'));
      await chmod(join(dir, 'playback-evidence.json'), 0o600);
    }
  } catch (e) {
    report.evidenceError = e.message;
  }
  try {
    const evidence = JSON.parse(await readFile(join(dir, 'playback-evidence.json'), 'utf8'));
    const ids = [...new Set(evidence.map((row) => row.id))];
    assert.equal(
      ids.length,
      1,
      'Playback, seek, fullscreen and close must retain one session UUID',
    );
    for (const event of ['first-frame', 'seek', 'closed'])
      assert(
        evidence.some((row) => row.event === event),
        'Missing player evidence event: ' + event,
      );
    const seekLanding = verifySeekEvidence(evidence);
    report.sessionEvidence = {
      sessionId: ids[0],
      events: evidence.length,
      singleSession: true,
      seekLanding,
    };
  } catch (e) {
    report.evidenceError = e.message;
  }
  try {
    run('xcrun', [
      'xcresulttool',
      'export',
      'attachments',
      '--path',
      join(dir, 'native.xcresult'),
      '--output-path',
      join(dir, 'screenshots'),
    ]);
  } catch (e) {
    report.screenshotError = e.message;
  }
  report.counts = host.counts;
  report.requests = host.requests;
  report.status =
    code === 0 && report.summary?.passedTests === 1 && report.sessionEvidence?.singleSession
      ? 'streaming-passed-legacy-unverified'
      : 'failed';
  report.streamingStatus = report.status;
  if (report.streamingStatus !== 'streaming-passed-legacy-unverified')
    throw Error('Streaming build or test failed; legacy test was not started');
  if (['--only-streaming', '--only-tail'].includes(process.argv[3])) {
    report.status = 'passed';
    report.scope.streamingOnly = true;
    report.scope.mediaLayout = process.argv[3] === '--only-tail' ? 'tail-moov' : 'faststart';
    if (process.argv[3] === '--only-tail') report.samples.tail.nativeStatus = 'passed';
    throw new Error('STREAMING_ONLY_COMPLETED');
  }
  // The legacy case is deliberately a distinct invocation and result bundle.
  code = await execLog(
    'xcodebuild',
    [
      '-project',
      join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      build,
      '-resultBundlePath',
      join(dir, 'legacy.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-skipPackageUpdates',
      '-disableAutomaticPackageResolution',
      '-collect-test-diagnostics',
      'never',
      'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'ONLY_ACTIVE_ARCH=YES',
      '-only-testing:CodexManageUITests/CodexManageUITests/testMediaPlaybackLegacyNative',
      'test',
    ],
    join(dir, 'legacy.log'),
    { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
  );
  report.legacyExitCode = code;
  try {
    report.legacySummary = JSON.parse(
      run('xcrun', [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(dir, 'legacy.xcresult'),
        '--format',
        'json',
      ]),
    );
  } catch (e) {
    report.legacySummaryError = e.message;
  }
  report.counts = host.counts;
  report.requests = host.requests;
  report.status =
    report.streamingStatus === 'streaming-passed-legacy-unverified' &&
    code === 0 &&
    report.legacySummary?.passedTests === 1
      ? 'passed'
      : 'failed';
  try {
    const container = run('xcrun', [
      'simctl',
      'get_app_container',
      device,
      'com.easyai.agenthub.acceptance.mediaplayback',
      'data',
    ]);
    const file = join(container, 'Library/Caches/media-playback-evidence.json');
    if (await exists(file)) {
      await cp(file, join(dir, 'legacy-playback-evidence.json'));
      await chmod(join(dir, 'legacy-playback-evidence.json'), 0o600);
    }
  } catch (e) {
    report.legacyEvidenceError = e.message;
  }
  try {
    run('xcrun', [
      'xcresulttool',
      'export',
      'attachments',
      '--path',
      join(dir, 'legacy.xcresult'),
      '--output-path',
      join(dir, 'legacy-screenshots'),
    ]);
  } catch (e) {
    report.legacyScreenshotError = e.message;
  }
  if (report.status === 'passed') {
    const switchHost = async (label) => {
      if (host) await host.close();
      const hostDir = join(dir, `case-host-${label}`);
      await mkdir(hostDir, { mode: 0o700 });
      host = await startPlaybackHost(hostDir, video);
    };
    report.fastHost = { counts: host.counts, requests: host.requests };
    await write(join(dir, 'report.json'), report);
    await host.close();
    host = undefined;
    for (const path of [join(dir, 'host-state'), join(dir, 'transfers'), join(dir, 'document.md')])
      await rm(path, { recursive: true, force: true });
    await switchHost('forbidden');
    code = await execLog(
      'xcodebuild',
      [
        '-project',
        join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
        '-scheme',
        'CodexManage',
        '-destination',
        'platform=iOS Simulator,id=' + device,
        '-derivedDataPath',
        build,
        '-resultBundlePath',
        join(dir, 'forbidden.xcresult'),
        '-parallel-testing-enabled',
        'NO',
        '-skipPackageUpdates',
        '-disableAutomaticPackageResolution',
        '-collect-test-diagnostics',
        'never',
        'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
        'CODE_SIGNING_ALLOWED=YES',
        'CODE_SIGN_IDENTITY=-',
        'ONLY_ACTIVE_ARCH=YES',
        '-only-testing:CodexManageUITests/CodexManageUITests/testMediaPlaybackForbiddenRetryNative',
        'test',
      ],
      join(dir, 'forbidden.log'),
      { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
    );
    report.forbiddenExitCode = code;
    try {
      report.forbiddenSummary = JSON.parse(
        run('xcrun', [
          'xcresulttool',
          'get',
          'test-results',
          'summary',
          '--path',
          join(dir, 'forbidden.xcresult'),
          '--format',
          'json',
        ]),
      );
    } catch (e) {
      report.forbiddenSummaryError = e.message;
    }
    try {
      run('xcrun', [
        'xcresulttool',
        'export',
        'attachments',
        '--path',
        join(dir, 'forbidden.xcresult'),
        '--output-path',
        join(dir, 'forbidden-screenshots'),
      ]);
    } catch (e) {
      report.forbiddenScreenshotError = e.message;
    }
    report.forbiddenPassed = code === 0 && report.forbiddenSummary?.passedTests === 1;
    report.forbiddenCounts = host.counts;
    report.forbiddenRequests = host.requests;
    await write(join(dir, 'report.json'), report);
    await host.close();
    host = undefined;
    await rm(join(dir, 'case-host-forbidden'), { recursive: true, force: true });
    report.entryCases = {};
    for (const [name, testName] of [
      ['link', 'testMediaPlaybackLinkEntryNative'],
      ['attachment', 'testMediaPlaybackAttachmentEntryNative'],
      ['document', 'testMediaPlaybackDocumentEntryNative'],
      ['context', 'testMediaPlaybackContextExitNative'],
      ['share', 'testMediaPlaybackFullDownloadShareNative'],
    ]) {
      await switchHost(name);
      const resultPath = join(dir, `entry-${name}.xcresult`);
      const exitCode = await execLog(
        'xcodebuild',
        [
          '-project',
          join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
          '-scheme',
          'CodexManage',
          '-destination',
          'platform=iOS Simulator,id=' + device,
          '-derivedDataPath',
          build,
          '-resultBundlePath',
          resultPath,
          '-parallel-testing-enabled',
          'NO',
          '-skipPackageUpdates',
          '-disableAutomaticPackageResolution',
          '-collect-test-diagnostics',
          'never',
          'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
          'CODE_SIGNING_ALLOWED=YES',
          'CODE_SIGN_IDENTITY=-',
          'ONLY_ACTIVE_ARCH=YES',
          `-only-testing:CodexManageUITests/CodexManageUITests/${testName}`,
          'test',
        ],
        join(dir, `entry-${name}.log`),
        { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
      );
      let summary;
      try {
        summary = JSON.parse(
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
      } catch (e) {
        summary = { error: e.message };
      }
      try {
        run('xcrun', [
          'xcresulttool',
          'export',
          'attachments',
          '--path',
          resultPath,
          '--output-path',
          join(dir, `entry-${name}-screenshots`),
        ]);
      } catch (e) {
        summary.screenshotError = e.message;
      }
      report.entryCases[name] = {
        exitCode,
        summary,
        counts: host.counts,
        requests: host.requests,
        passed: exitCode === 0 && summary.passedTests === 1,
      };
      await write(join(dir, 'report.json'), report);
      await host.close();
      host = undefined;
      await rm(join(dir, `case-host-${name}`), { recursive: true, force: true });
    }
    const tailDir = join(dir, 'tail-host');
    await mkdir(tailDir, { mode: 0o700 });
    host = await startPlaybackHost(tailDir, tailVideo);
    const tailCode = await execLog(
      'xcodebuild',
      [
        '-project',
        join(snapshot, 'apps/ios/CodexManage.xcodeproj'),
        '-scheme',
        'CodexManage',
        '-destination',
        'platform=iOS Simulator,id=' + device,
        '-derivedDataPath',
        build,
        '-resultBundlePath',
        join(dir, 'tail.xcresult'),
        '-parallel-testing-enabled',
        'NO',
        '-skipPackageUpdates',
        '-disableAutomaticPackageResolution',
        '-collect-test-diagnostics',
        'never',
        'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.acceptance.mediaplayback',
        'CODE_SIGNING_ALLOWED=YES',
        'CODE_SIGN_IDENTITY=-',
        'ONLY_ACTIVE_ARCH=YES',
        '-only-testing:CodexManageUITests/CodexManageUITests/testMediaPlaybackStreamingNative',
        'test',
      ],
      join(dir, 'tail.log'),
      { TEST_RUNNER_MEDIA_PLAYBACK_FIXTURE_JSON: JSON.stringify(host.fixture) },
    );
    let tailSummary;
    try {
      tailSummary = JSON.parse(
        run('xcrun', [
          'xcresulttool',
          'get',
          'test-results',
          'summary',
          '--path',
          join(dir, 'tail.xcresult'),
          '--format',
          'json',
        ]),
      );
    } catch (e) {
      tailSummary = { error: e.message };
    }
    try {
      run('xcrun', [
        'xcresulttool',
        'export',
        'attachments',
        '--path',
        join(dir, 'tail.xcresult'),
        '--output-path',
        join(dir, 'tail-screenshots'),
      ]);
    } catch (e) {
      tailSummary.screenshotError = e.message;
    }
    let tailSession;
    try {
      const container = run('xcrun', [
        'simctl',
        'get_app_container',
        device,
        'com.easyai.agenthub.acceptance.mediaplayback',
        'data',
      ]);
      await cp(
        join(container, 'Library/Caches/media-playback-evidence.json'),
        join(dir, 'tail-playback-evidence.json'),
      );
      await chmod(join(dir, 'tail-playback-evidence.json'), 0o600);
      const rows = JSON.parse(await readFile(join(dir, 'tail-playback-evidence.json'), 'utf8'));
      const ids = [...new Set(rows.map((row) => row.id))];
      assert.equal(ids.length, 1);
      for (const event of ['first-frame', 'seek', 'closed'])
        assert(rows.some((row) => row.event === event));
      tailSummary.seekLanding = verifySeekEvidence(rows);
      tailSession = ids[0];
    } catch (e) {
      tailSummary.evidenceError = e.message;
    }
    report.tail = {
      exitCode: tailCode,
      summary: tailSummary,
      sessionId: tailSession,
      passed: tailCode === 0 && tailSummary.passedTests === 1 && !!tailSession,
      counts: host.counts,
      requests: host.requests,
    };
    report.samples.tail.nativeStatus = report.tail.passed ? 'passed' : 'failed';
    report.status =
      report.forbiddenPassed &&
      Object.values(report.entryCases).every((c) => c.passed) &&
      report.tail.passed
        ? 'passed'
        : 'failed';
  }
} catch (e) {
  if (
    !['CONTEXT_ONLY_COMPLETED', 'STREAMING_ONLY_COMPLETED', 'LEASE_ONLY_COMPLETED'].includes(
      e.message,
    )
  ) {
    report.status = 'failed';
    report.error = e.stack ?? e.message;
  }
} finally {
  if (host) {
    report.counts = host.counts;
    report.requests = host.requests;
    try {
      await host.close();
      report.hostClosed = true;
    } catch (e) {
      report.hostCloseError = e.message;
    }
  }
  if (device && !active) {
    try {
      run('xcrun', ['simctl', 'shutdown', device]);
      run('xcrun', ['simctl', 'delete', device]);
      report.simulatorDeleted = true;
    } catch (e) {
      report.simulatorCleanupError = e.message;
    }
  }
  // These paths are created exclusively by this run, and no process remains dependent on them.
  for (const path of [
    snapshot,
    build,
    video,
    join(dir, 'video-tail-moov.mp4'),
    join(dir, 'portrait.mp4'),
    join(dir, 'document.md'),
    join(dir, 'host-state'),
    join(dir, 'transfers'),
    join(dir, 'tail-host'),
    ...['short', 'renew-forbidden', 'renew-version', 'attachment-octet', 'core', 'portrait'].map(
      (n) => join(dir, `case-host-lease-${n}`),
    ),
    ...['forbidden', 'link', 'attachment', 'document', 'context', 'share'].map((n) =>
      join(dir, `case-host-${n}`),
    ),
  ])
    try {
      await rm(path, { recursive: true, force: true });
    } catch (e) {
      (report.cleanupErrors ??= []).push({ path, error: e.message });
    }
  for (const name of [
    'screenshots',
    'legacy-screenshots',
    'forbidden-screenshots',
    'tail-screenshots',
    'context-screenshots',
    'attachment-octet-screenshots',
    ...['short', 'renew-forbidden', 'renew-version', 'attachment-octet', 'core', 'portrait'].map(
      (n) => `lease-${n}-screenshots`,
    ),
    ...['link', 'attachment', 'document', 'context', 'share'].map((n) => `entry-${n}-screenshots`),
  ]) {
    const folder = join(dir, name);
    if (await exists(folder)) {
      await chmod(folder, 0o700);
      for (const entry of await (await import('node:fs/promises')).readdir(folder)) {
        const path = join(folder, entry);
        if (entry.endsWith('.png') || entry.endsWith('.txt') || entry === 'manifest.json')
          await chmod(path, 0o600);
        else await rm(path, { recursive: true, force: true });
      }
    }
  }
  for (const name of [
    'native.xcresult',
    'legacy.xcresult',
    'forbidden.xcresult',
    'tail.xcresult',
    'context.xcresult',
    'attachment-octet.xcresult',
    ...['short', 'renew-forbidden', 'renew-version', 'attachment-octet', 'core', 'portrait'].map(
      (n) => `lease-${n}.xcresult`,
    ),
    ...['link', 'attachment', 'document', 'context', 'share'].map((n) => `entry-${n}.xcresult`),
  ])
    await rm(join(dir, name), { recursive: true, force: true });
  report.freeAfter = await free();
  report.finishedAt = new Date().toISOString();
  await write(join(dir, 'report.json'), report);
  console.log(
    JSON.stringify(
      {
        dir,
        status: report.status,
        error: report.error,
        counts: report.counts,
        summary: report.summary,
        legacySummary: report.legacySummary,
        cleanupErrors: report.cleanupErrors,
      },
      null,
      2,
    ),
  );
}
if (report.status !== 'passed') process.exitCode = 1;
