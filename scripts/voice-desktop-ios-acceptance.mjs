import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { openOriginalDesktopThread } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// A dedicated Host and signed DEV app; physical acoustic recording is kept distinct from fixtures.
const device = process.env.VOICE_IOS_DEVICE_ID;
const threadId = process.env.VOICE_WITNESS_THREAD_ID;
const audioPath = process.env.VOICE_ACOUSTIC_AUDIO_PATH;
const simulated = process.env.VOICE_SIMULATOR === '1';
const fileFixture = process.env.VOICE_FILE_FIXTURE === '1';
const publicPairing = process.env.VOICE_PUBLIC_PAIRING === '1';
const expectCaptureUnavailable = process.env.VOICE_EXPECT_CAPTURE_UNAVAILABLE === '1';
if (!device || !threadId || !audioPath)
  throw Error('Set VOICE_IOS_DEVICE_ID, VOICE_WITNESS_THREAD_ID and VOICE_ACOUSTIC_AUDIO_PATH');
const directory = resolve('.local/voice-native-dev/' + randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const reuseState = process.env.VOICE_REUSE_HOST_STATE;
if (reuseState) {
  assert(
    publicPairing && simulated,
    'Reuse is only for this isolated public-pairing simulator test',
  );
  assert(/^\.local\/voice-native-dev\/[0-9a-f-]{36}\/host$/.test(reuseState));
  assert.equal(
    (await readdir(join(reuseState, 'voice-transcriptions'))).filter((n) => n.endsWith('.json'))
      .length,
    0,
    'An earlier audio operation must be reviewed rather than repeated',
  );
}
const state = reuseState ? resolve(reuseState) : join(directory, 'host');
const executable = process.env.CODEX_EXECUTABLE;
if (!executable) throw Error('Set the actual installed CODEX_EXECUTABLE');
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  HOST_AGENT_STATE_DIR: state,
  HOST_AGENT_NAME: 'Codex Voice DEV',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_DISCOVERY: '1',
  HOST_AGENT_DISCOVERY_PORT: reuseState ? process.env.VOICE_REUSE_DISCOVERY_PORT : '0',
  HOST_AGENT_DISCOVERY_BONJOUR: publicPairing ? '1' : '0',
});
const service = await startRuntimeServer();
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  service.session.hostId,
  'Voice independent witness',
);
const endpoint = 'http://127.0.0.1:' + service.server.address().port;
const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
const report = {
  kind: fileFixture
    ? 'native-file-fixture-real-codex-transcription'
    : 'physical-recording-codex-host-transcription',
  startedAt: new Date().toISOString(),
  hostId: service.session.hostId,
  desktopThreadId: threadId,
  reuseOriginalIsolatedPairing: !!reuseState,
  native: null,
  audioPlaybackSha256: createHash('sha256')
    .update(await readFile(audioPath))
    .digest('hex'),
};
const fingerprintFiles = [
  'apps/host-agent/dist/runtime-server.js',
  'apps/host-agent/dist/voice-transcriptions.js',
  'packages/codex-adapter/dist/desktop-backend-auth.js',
  'packages/codex-adapter/dist/desktop-transcription.js',
  'packages/protocol/dist/voice-transcription.js',
  'apps/ios/Sources/AgentClient.swift',
  'apps/ios/Sources/VoiceInput.swift',
  'apps/ios/Sources/SessionStore.swift',
  'apps/ios/Sources/RootView.swift',
  'apps/ios/Tests/CodexManageUITests.swift',
  'scripts/voice-desktop-ios-acceptance.mjs',
];
report.fingerprints = Object.fromEntries(
  await Promise.all(
    fingerprintFiles.map(async (path) => [
      path,
      createHash('sha256')
        .update(await readFile(path))
        .digest('hex'),
    ]),
  ),
);
const turnIds = (snapshot) =>
  desktopTurns(snapshot).map((turn) => {
    assert.equal(typeof turn.turnId, 'string', 'Desktop witness requires actual turn IDs');
    return turn.turnId;
  });
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const host = async (path) => {
  const response = await fetch(endpoint + path, { headers: { Authorization: 'Bearer ' + token } });
  const value = await response.json();
  assert.equal(response.status, 200, value.error);
  return value;
};
let server, child, playback, nativeText;
const fixtureNonce = randomUUID();
try {
  assert.ok(service.direct, 'Dedicated HTTPS listener required');
  report.publicPairing = publicPairing;
  report.readiness = await host('/v1/voice/readiness?hostId=' + report.hostId);
  assert.equal(report.readiness.available, true);
  const workspace = await host('/v1/workspace?hostId=' + report.hostId);
  assert.ok(
    workspace.threads[0]?.id,
    'Select a current desktop conversation for native draft recovery',
  );
  const captureThread =
    workspace.threads.find((thread) => thread.id === threadId) ?? workspace.threads[0];
  report.captureThreadId = captureThread.id;
  report.captureThreadTitle = captureThread.title;
  await witness.connect();
  let before = await witness.ipc.follow(threadId, 3000, true);
  if (!before) {
    await openOriginalDesktopThread(threadId);
    before = await witness.ipc.follow(threadId, 15000, true);
  }
  assert.ok(before && witness.ipc.hasOwner(threadId));
  report.ownerId = witness.ipc.owners.get(threadId);
  report.desktopVersion = witness.version;
  report.turnsBefore = turnIds(before);
  await writeFile(join(directory, 'desktop-before.json'), JSON.stringify(before), { mode: 0o600 });
  const descriptor = { ...service.direct.descriptor(), token };
  const uri =
    'codex-host://pair?descriptor=' + Buffer.from(JSON.stringify(descriptor)).toString('base64url');
  server = createServer(async (request, response) => {
    if (request.headers.origin) {
      response.writeHead(403).end();
      return;
    }
    if (request.method === 'GET' && request.url === '/' && simulated) {
      response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(
        JSON.stringify(
          publicPairing
            ? { hostId: report.hostId, threadId, fileFixture: String(fileFixture) }
            : { uri, threadId, fileFixture: String(fileFixture) },
        ),
      );
      return;
    }
    if (request.method === 'GET' && request.url === '/audio' && fileFixture) {
      response.writeHead(200, { 'Content-Type': 'audio/wav', 'Cache-Control': 'no-store' });
      response.end(await readFile(audioPath));
      return;
    }
    if (
      request.method === 'POST' &&
      request.url === '/evidence' &&
      request.headers['x-voice-fixture-nonce'] === fixtureNonce
    ) {
      let data = '';
      for await (const chunk of request) {
        data += chunk;
        if (data.length > 100_000) {
          response.writeHead(413).end();
          return;
        }
      }
      nativeText = JSON.parse(data).text;
      response.writeHead(200).end('{}');
      return;
    }
    response.writeHead(404).end();
  });
  await new Promise((done) => server.listen(18165, simulated ? '127.0.0.1' : '0.0.0.0', done));
  const fixtureURL = process.env.VOICE_FIXTURE_URL;
  if (!fixtureURL) throw Error('Set phone-reachable VOICE_FIXTURE_URL (private DEV fixture only)');
  const voiceEntitlements = join(directory, 'voice-dev.entitlements');
  const voiceConfig = join(directory, 'voice-dev.xcconfig');
  if (process.env.VOICE_ISOLATE_SIGNING === '1')
    await writeFile(voiceEntitlements, '<?xml version="1.0"?><plist version="1.0"><dict/></plist>');
  if (process.env.VOICE_ISOLATE_SIGNING === '1')
    await writeFile(
      voiceConfig,
      `CODE_SIGN_ENTITLEMENTS = ${voiceEntitlements}\nCODE_SIGN_ENTITLEMENTS[sdk=iphoneos*] = ${voiceEntitlements}\n`,
    );
  const args = [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    (simulated ? 'platform=iOS Simulator,id=' : 'platform=iOS,id=') + device,
    '-destination-timeout',
    '30',
    '-derivedDataPath',
    process.env.VOICE_IOS_DERIVED_DATA_PATH ?? join(directory, 'build'),
    '-resultBundlePath',
    join(directory, 'native.xcresult'),
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/testCodexHostRecordedAudioTranscriptionDEV',
    'NOTIFICATION_APP_BUNDLE_ID=' +
      (process.env.VOICE_DEV_BUNDLE_ID ?? 'com.easyai.agenthub.voicedev'),
    ...(simulated ? ['CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-'] : []),
    ...(process.env.VOICE_ISOLATE_SIGNING === '1' ? ['-xcconfig', voiceConfig] : []),
    ...(process.env.VOICE_DEVELOPMENT_TEAM
      ? ['DEVELOPMENT_TEAM=' + process.env.VOICE_DEVELOPMENT_TEAM]
      : []),
    '-allowProvisioningUpdates',
    'test',
  ];
  const log = createWriteStream(join(directory, 'native.log'), { mode: 0o600 });
  child = spawn('xcodebuild', args, {
    env: {
      ...process.env,
      TEST_RUNNER_VOICE_FIXTURE_URL: fixtureURL,
      ...(publicPairing
        ? { TEST_RUNNER_VOICE_PUBLIC_HOST_ID: report.hostId }
        : { TEST_RUNNER_VOICE_PAIRING_URI: uri }),
      TEST_RUNNER_VOICE_FILE_FIXTURE: String(fileFixture),
      TEST_RUNNER_VOICE_FIXTURE_NONCE: fixtureNonce,
      TEST_RUNNER_VOICE_VERIFY_RECOVERY: process.env.VOICE_VERIFY_RECOVERY ?? '0',
      TEST_RUNNER_VOICE_RESTORE_ORIGINAL: reuseState ? '1' : '0',
      ...(process.env.VOICE_EXPECTED_PHRASE
        ? { TEST_RUNNER_VOICE_EXPECTED_PHRASE: process.env.VOICE_EXPECTED_PHRASE }
        : {}),
      TEST_RUNNER_VOICE_EXPECT_CAPTURE_UNAVAILABLE: expectCaptureUnavailable ? '1' : '0',
      TEST_RUNNER_VOICE_CAPTURE_THREAD_ID: report.captureThreadId,
      TEST_RUNNER_VOICE_CAPTURE_THREAD_TITLE: report.captureThreadTitle,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  createInterface({ input: child.stdout }).on('line', (line) => {
    if (line.trim() === 'VOICE_HOST_ACOUSTIC_READY' && !playback) {
      playback = spawn('/usr/bin/afplay', [audioPath], { stdio: 'ignore' });
    }
  });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  report.native = {
    exitCode: code,
    resultBundle: 'native.xcresult',
    playbackStarted: Boolean(playback),
    textReceived: Boolean(nativeText),
  };
  const build = process.env.VOICE_IOS_DERIVED_DATA_PATH ?? join(directory, 'build');
  const nativeExecutable = join(
    build,
    'Build/Products',
    simulated ? 'Debug-iphonesimulator' : 'Debug-iphoneos',
    'CodexManage.app/CodexManage',
  );
  report.native.binarySha256 = createHash('sha256')
    .update(await readFile(nativeExecutable))
    .digest('hex');
  report.native.debugBinarySha256 = createHash('sha256')
    .update(await readFile(nativeExecutable + '.debug.dylib'))
    .digest('hex');
  const after = await witness.ipc.follow(threadId, 15000, true);
  assert.ok(after && witness.ipc.hasOwner(threadId));
  report.turnsAfter = turnIds(after);
  await writeFile(join(directory, 'desktop-after.json'), JSON.stringify(after), { mode: 0o600 });
  assert.deepEqual(
    report.turnsAfter,
    report.turnsBefore,
    'Dictation must never submit a chat Turn',
  );
  assert.equal(witness.ipc.owners.get(threadId), report.ownerId);
  const receipts = (await readdir(join(state, 'voice-transcriptions'))).filter((name) =>
    name.endsWith('.json'),
  );
  report.receipts = [];
  for (const name of receipts) {
    const record = JSON.parse(await readFile(join(state, 'voice-transcriptions', name), 'utf8'));
    report.receipts.push({
      operationId: record.operationId,
      sha256: record.sha256,
      sizeBytes: record.sizeBytes,
      durationMs: record.durationMs,
      phase: record.phase,
      text: record.text,
    });
  }
  report.nativeText = nativeText;
  assert.equal(code, 0, 'Native recording/transcription test did not pass');
  if (expectCaptureUnavailable) {
    assert.equal(report.receipts.length, 0);
    assert.equal(nativeText, undefined);
    report.kind = 'actual-unavailable-microphone-responsive-timeout-original-draft';
    report.passed = true;
  } else {
    assert.equal(report.receipts.length, 1);
    assert.equal(report.receipts[0].phase, 'completed');
    assert.equal(nativeText, 'VOICE_ORIGINAL_DRAFT\n' + report.receipts[0].text);
    report.passed = true;
  }
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  await save();
  child?.kill();
  playback?.kill();
  server?.close();
  server?.closeAllConnections();
  witness.close();
  service.close();
  console.log(
    JSON.stringify({
      report: join(directory, 'report.json'),
      passed: report.passed ?? false,
      error: report.error,
      native: report.native,
    }),
  );
}
