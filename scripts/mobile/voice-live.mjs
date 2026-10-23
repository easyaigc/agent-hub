import assert from 'node:assert/strict';
import { execFile, execFileSync, fork } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile, mkdir, rename, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { networkInterfaces, homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { promisify } from 'node:util';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// Reuse an explicit original binding and owner. Acoustic audio is recorded by the real native App, then survives both process restarts.
const priorPath = resolve(process.env.VOICE_LIVE_PRIOR_REPORT ?? '');
const reopenPath = resolve(process.env.VOICE_LIVE_REOPEN_REPORT ?? '');
for (const path of [priorPath, reopenPath])
  assert(path.includes('/.local/') || path.includes('/.codex/acceptance-archives/'));
const prior = JSON.parse(await readFile(priorPath, 'utf8'));
const reopened = JSON.parse(await readFile(reopenPath, 'utf8'));
const serial = process.env.ANDROID_SERIAL;
assert(serial && (serial.startsWith('emulator-') || process.env.ANDROID_ALLOW_PHYSICAL === '1'));
assert.equal(reopened.serial, serial);
assert.equal(reopened.status, 'passed');
assert.equal(reopened.hostId, prior.hostId);
const phone = reopened.appProcesses.at(-1);
assert(phone.namespace && phone.tokenSha256);
const state = join(dirname(priorPath), 'host');
assert(state.includes('/UM04/live-'));
const executable = process.env.CODEX_EXECUTABLE;
assert(executable);
const caseId = randomUUID();
const root = resolve(
  process.env.VOICE_LIVE_OUTPUT_ROOT ??
    join(homedir(), '.codex/acceptance-archives/agent-hub/UM05'),
  'voice-live-' + caseId,
);
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const config = join(homedir(), '.codex/config.toml');
const configBefore = sha(await readFile(config));
const credentialBefore = sha(await readFile(join(state, 'private-connections-v1.json')));
const sdk = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk;
const adb = join(sdk, 'platform-tools/adb');
assert(
  !execFileSync('/bin/ps', ['-axo', 'args='], { encoding: 'utf8' })
    .split('\n')
    .some((line) => line.trimStart().startsWith(adb + ' -s ' + serial + ' shell am instrument ')),
  'An existing device test owns this phone',
);
const execute = promisify(execFile);
const call = async (...args) =>
  (await execute(adb, ['-s', serial, ...args], { timeout: 380000, maxBuffer: 16 * 1024 * 1024 }))
    .stdout;
const phrase = 'Green mountain, blue river, sixteen bright stars.';
const audio = join(root, 'acoustic-calibration.aiff');
await execute('say', ['-v', 'Samantha', '-o', audio, phrase]);
const report = {
  step: 'UM05',
  caseId,
  serial,
  hostId: prior.hostId,
  threadId: prior.source.threadId,
  namespace: phone.namespace,
  status: 'running',
  actualAcousticCapture: true,
  fixtureAudio: false,
  fixtureTranscript: false,
  newClaims: 0,
  newChatInputs: 0,
  hosts: [],
  http: [],
  native: [],
  priorReport: priorPath,
  priorReopenReport: reopenPath,
  audioPlaybackSha256: sha(await readFile(audio)),
  normalConfigBefore: configBefore,
  credentialIssuerBefore: credentialBefore,
};
let saving = Promise.resolve();
const save = () => {
  const snapshot = JSON.stringify(report, null, 2);
  saving = saving.then(async () => {
    const temporary = join(root, 'report-' + randomUUID() + '.tmp');
    await writeFile(temporary, snapshot, { mode: 0o600, flag: 'wx' });
    await rename(temporary, join(root, 'report.json'));
  });
  return saving;
};
await save();
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  prior.hostId,
  'UM05 original-owner voice witness',
);
let host,
  control,
  phase = 'capture',
  played = false,
  controlPort;
const address = Object.entries(networkInterfaces())
  .filter(([name]) => name.startsWith('en'))
  .flatMap(([, entries]) => entries ?? [])
  .find((entry) => entry.family === 'IPv4' && !entry.internal)?.address;
assert(address, 'Require the original private LAN interface');
const stopHost = async () => {
  if (!host || host.child.exitCode !== null || host.child.signalCode !== null) return;
  const child = host.child;
  await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Owned transcription Host did not exit')), 15000);
    child.once('exit', (code) => {
      clearTimeout(timer);
      assert.equal(code, 0);
      const row = report.hosts.find((r) => r.pid === child.pid);
      if (row) Object.assign(row, { exitCode: code, exitedAtUTC: new Date().toISOString() });
      done();
    });
    child.kill('SIGTERM');
  });
};
const launch = async () => {
  const hostPhase = phase;
  const child = fork(new URL('./voice-live-host.mjs', import.meta.url), [], {
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
    env: {
      ...process.env,
      VOICE_LIVE_EVIDENCE: root,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: executable,
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
      HOST_AGENT_STATE_DIR: state,
      HOST_AGENT_ROOT: prior.source.root,
      HOST_AGENT_NAME: 'UM05 original binding transcription',
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_DISCOVERY: '1',
      HOST_AGENT_DISCOVERY_BIND: '0.0.0.0',
      HOST_AGENT_DISCOVERY_PORT: '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
    },
  });
  host = { child };
  const log = createWriteStream(join(root, 'host-' + child.pid + '.log'), { mode: 0o600 });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  child.once('exit', () => log.end());
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Owned Host startup timeout')), 30000);
    child.once('error', reject);
    child.once('exit', (code) => {
      clearTimeout(timer);
      reject(Error('Host exited before ready: ' + code));
    });
    child.on('message', (message) => {
      if (message.type === 'ready') {
        clearTimeout(timer);
        done(message);
      }
      if (message.type === 'http') {
        report.http.push({ phase: hostPhase, ...message.record });
        void save();
      }
      if (message.type === 'http-completed') {
        const row = report.http.find((r) => r.id === message.record.id);
        if (row) Object.assign(row, message.record);
        void save();
      }
    });
  });
  assert.equal(ready.descriptor.target.hostId, prior.hostId);
  assert.equal(ready.descriptor.fingerprint, prior.hosts[0].fingerprint);
  host = { ...ready, child };
  report.hosts.push({
    phase,
    pid: ready.pid,
    port: ready.port,
    fingerprint: ready.descriptor.fingerprint,
  });
  await save();
};
const stats = () => ({
  claimPosts: report.http.filter((r) => r.method === 'POST' && r.path === '/connection/claim')
    .length,
  chatPosts: report.http.filter(
    (r) =>
      r.method === 'POST' &&
      ['/v1/replies', '/v1/desktop/creations', '/v1/threads'].includes(r.path),
  ).length,
  audioPosts: report.http.filter(
    (r) => r.method === 'POST' && r.path === '/v1/voice/transcriptions/audio',
  ).length,
});
const native = async (method) => {
  const output = await call(
    'shell',
    'am',
    'instrument',
    '-w',
    '-e',
    'voiceLiveControl',
    'http://127.0.0.1:' + controlPort,
    '-e',
    'voiceLiveCase',
    caseId,
    '-e',
    'class',
    'com.agenthub.android.MobileVoiceLiveTest#' + method,
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(join(root, phase + '-instrumentation.txt'), output, { mode: 0o600 });
  report.native.push({
    phase,
    method,
    passed: /OK \(1 test\)/.test(output) && !/FAILURES!!!/.test(output),
  });
  await save();
  await call(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM05-live/' + caseId,
    join(root, phase + '-native'),
  );
  assert(
    report.native.at(-1).passed,
    'Native ' + phase + ' did not pass; preserve this original recording and query its same ID',
  );
};
try {
  await witness.connect();
  const before = await witness.ipc.follow(prior.source.threadId, 15000, true);
  assert(
    before && witness.ipc.hasOwner(prior.source.threadId),
    'The current original owner is required; no model or claim will be started to replace it',
  );
  const beforeIds = desktopTurns(before).map((turn) => turn.turnId);
  assert(beforeIds.length > 0);
  report.turnIdsBefore = beforeIds;
  report.ownerBefore = witness.ipc.owners.get(prior.source.threadId);
  await writeFile(join(root, 'owner-before.json'), JSON.stringify(before), { mode: 0o600 });
  await launch();
  control = createServer(async (req, res) => {
    try {
      if (req.headers.origin || req.headers['x-voice-case'] !== caseId || req.method !== 'GET') {
        res.writeHead(403).end();
        return;
      }
      const path = new URL(req.url, 'http://localhost').pathname;
      let value;
      if (path === '/um05/fixture')
        value = {
          phase,
          hostId: prior.hostId,
          threadId: prior.source.threadId,
          namespace: phone.namespace,
          endpoint: `https://${address}:${host.port}`,
          expectedTokenSha256: phone.tokenSha256,
          expectedPhrase: phrase,
        };
      else if (path === '/um05/stats') value = stats();
      else if (path === '/um05/play' && phase === 'capture' && !played) {
        played = true;
        await execute('afplay', [audio]);
        value = { played: true };
      } else {
        res.writeHead(404).end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
    } catch (error) {
      res.writeHead(500).end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done) => control.listen(0, '127.0.0.1', done));
  controlPort = control.address().port;
  await call('reverse', 'tcp:' + controlPort, 'tcp:' + controlPort);
  const apk = '.build/native/android/outputs/apk/debug/android-debug.apk',
    testApk = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
  report.installations = [];
  report.installations.push(
    await installAndroidApkIfNeeded({
      adb,
      serial,
      apk: resolve(apk),
      packageName: 'com.easyai.agenthub.android.debug',
      allowPhysical: process.env.ANDROID_ALLOW_PHYSICAL === '1',
    }),
  );
  report.installations.push(
    await installAndroidApkIfNeeded({
      adb,
      serial,
      apk: resolve(testApk),
      packageName: 'com.easyai.agenthub.android.debug.test',
      allowPhysical: process.env.ANDROID_ALLOW_PHYSICAL === '1',
    }),
  );
  report.appSha256 = sha(await readFile(apk));
  report.testAppSha256 = sha(await readFile(testApk));
  await save();
  await native('recordAcousticOriginalBeforeProcessRestart');
  assert.deepEqual(stats(), { claimPosts: 0, chatPosts: 0, audioPosts: 0 });
  await call('shell', 'am', 'force-stop', 'com.easyai.agenthub.android.debug');
  await stopHost();
  phase = 'resume';
  await launch();
  await native('transcribeOriginalAfterProcessRestart');
  assert.deepEqual(stats(), { claimPosts: 0, chatPosts: 0, audioPosts: 1 });
  const proof = JSON.parse(await readFile(join(root, 'resume-native/actual-proof.json'), 'utf8'));
  const capture = JSON.parse(
    await readFile(join(root, 'capture-native/capture-proof.json'), 'utf8'),
  );
  assert.notEqual(proof.pid, capture.pid);
  assert.deepEqual(proof.originalManifest, capture.manifest);
  const receiptPath = join(state, 'voice-transcriptions', capture.manifest.operationId + '.json');
  const receipt = JSON.parse(await readFile(receiptPath, 'utf8'));
  for (const [key, value] of Object.entries(capture.manifest))
    assert.deepEqual(receipt[key], value);
  assert.equal(receipt.phase, 'completed');
  assert.equal(proof.transcribedDraft, proof.originalText + '\n' + receipt.text);
  await writeFile(join(root, 'actual-host-receipt-private.json'), await readFile(receiptPath), {
    mode: 0o600,
  });
  const wav = join(state, 'voice-transcriptions', capture.manifest.operationId + '.wav');
  const audioRequests = report.http.filter(
    (r) => r.method === 'POST' && r.path === '/v1/voice/transcriptions/audio',
  );
  assert.equal(audioRequests.length, 1);
  assert(audioRequests[0].completed && audioRequests[0].requestEnded);
  assert.equal(audioRequests[0].requestBytes, capture.manifest.sizeBytes);
  assert.equal(audioRequests[0].requestSha256, capture.manifest.sha256);
  // Completed audio is deliberately deleted by the Host; its immutable request hash proves the original bytes.
  await assert.rejects(stat(wav), (error) => error.code === 'ENOENT');
  report.completedHostAudioRemoved = true;
  report.actualAudioRequestSha256 = audioRequests[0].requestSha256;
  const after = await witness.ipc.follow(prior.source.threadId, 10000, true);
  assert(after && witness.ipc.hasOwner(prior.source.threadId));
  assert.deepEqual(
    desktopTurns(after).map((turn) => turn.turnId),
    beforeIds,
  );
  assert.equal(witness.ipc.owners.get(prior.source.threadId), report.ownerBefore);
  await writeFile(join(root, 'owner-after.json'), JSON.stringify(after), { mode: 0o600 });
  report.turnIdsAfter = beforeIds;
  report.credentialIssuerUnchanged =
    sha(await readFile(join(state, 'private-connections-v1.json'))) === credentialBefore;
  assert(report.credentialIssuerUnchanged);
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = { name: error.name, message: error.message };
  throw error;
} finally {
  try {
    const final = await witness.ipc.follow(prior.source.threadId, 10000, true);
    if (final && witness.ipc.hasOwner(prior.source.threadId)) {
      report.finalOwnerId = witness.ipc.owners.get(prior.source.threadId);
      report.finalTurnIds = desktopTurns(final).map((turn) => turn.turnId);
      await writeFile(join(root, 'owner-final.json'), JSON.stringify(final), { mode: 0o600 });
    }
  } catch (error) {
    report.finalWitnessError = error.message;
  }
  report.normalConfigAfter = sha(await readFile(config));
  report.normalConfigUnchangedDuringRun = report.normalConfigAfter === configBefore;
  if (controlPort) await call('reverse', '--remove', 'tcp:' + controlPort).catch(() => {});
  if (control) await new Promise((done) => control.close(done));
  await stopHost();
  witness.close();
  report.finishedAtUTC = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      report: join(root, 'report.json'),
      newChatInputs: 0,
      ...stats(),
    }),
  );
}
