import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { lstat, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { taskHubAcceptanceState } from '../task-hub-acceptance-state.mjs';
import { taskHubNativeSourceFiles, taskHubNativeFingerprint } from '../task-hub-native-inputs.mjs';
import {
  probe,
  sameIdentity,
  verifyBundle,
  verifyRegistration,
} from '../../skills/codex-host-connect/scripts/task-hub-core.mjs';

const args = process.argv.slice(2);
assert(args.length === 4 && args[0] === '--state' && args[2] === '--serial');
const state = await taskHubAcceptanceState(process.cwd(), args[1]);
const serial = args[3];
assert(/^emulator-[0-9]{4,5}$/.test(serial), 'Use a dedicated local emulator');
const read = async (name, privateFile = true) => {
  const path = resolve(state, name);
  const file = await lstat(path);
  assert(file.isFile() && !file.isSymbolicLink());
  assert.equal(file.mode & (privateFile ? 0o077 : 0o022), 0);
  if (typeof process.getuid === 'function') assert.equal(file.uid, process.getuid());
  return JSON.parse(await readFile(path, 'utf8'));
};
const configuration = await read('runtime.json');
const trust = await read('trust.json');
const registry = verifyRegistration(await read('deployment.json', false), trust.registryKeySha256);
assert(configuration.role === 'primary' && configuration.deploymentId.includes('dev'));
assert(
  Number.isInteger(configuration.tlsPort) &&
    configuration.tlsPort > 0 &&
    configuration.tlsPort < 65536,
);
assert(sameIdentity(configuration, registry));
const manifest = await verifyBundle(configuration.bundle);
assert.equal(manifest.payloadSha256, configuration.payloadSha256);
const live = await probe(registry);
assert(live.identityVerified && live.backendReady);
assert(live.capabilities.includes('mobile-device-sessions'));
const credentials = await read('env.json');
assert(credentials.BOOTSTRAP_PASSWORD);
const toolchain = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(toolchain.androidSdk, 'platform-tools/adb');
const pkg = 'com.easyai.agenthub.android.debug';
const name = 'task-hub-live-' + randomUUID() + '.json';
const fixture = Buffer.from(
  JSON.stringify({
    binding: {
      service: {
        version: 1,
        contractVersion: '1',
        deploymentId: registry.deploymentId,
        serverInstanceId: registry.serverInstanceId,
        primaryHostId: registry.primaryHostId,
        name: 'Registered local DEV Task Hub',
        capabilities: live.capabilities,
      },
      endpoint: 'https://' + registry.domain + ':' + configuration.tlsPort,
      fingerprint: registry.certificateSha256,
    },
    username: 'admin',
    password: credentials.BOOTSTRAP_PASSWORD,
  }),
);
const output = resolve('.local/task-hub-live-session-' + randomUUID());
await mkdir(output, { mode: 0o700 });
const sources = [
  ...new Set([
    ...taskHubNativeSourceFiles(process.cwd()),
    ...execFileSync('rg', ['--files', 'apps/android/src'], { encoding: 'utf8' }).trim().split('\n'),
  ]),
].sort();
const implementation = await taskHubNativeFingerprint(process.cwd(), sources);
const report = {
  kind: 'actual-local-dev-native-human-session',
  status: 'running',
  serial,
  deploymentId: registry.deploymentId,
  serverInstanceId: registry.serverInstanceId,
  primaryHostId: registry.primaryHostId,
  certificateSha256: registry.certificateSha256,
  payloadSha256: manifest.payloadSha256,
  implementation,
  startedAt: new Date().toISOString(),
};
const save = () =>
  writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
await save();
const port = 'tcp:' + configuration.tlsPort;
try {
  execFileSync(adb, ['-s', serial, 'reverse', port, port]);
  execFileSync(adb, ['-s', serial, 'shell', 'run-as', pkg, 'mkdir', '-p', 'files']);
  // The secret goes through stdin directly into this debug App's private sandbox,
  // never an adb argument, shared /data/local/tmp file or instrumentation log.
  execFileSync(adb, ['-s', serial, 'shell', 'run-as', pkg, 'tee', 'files/' + name], {
    input: fixture,
    stdio: ['pipe', 'ignore', 'pipe'],
  });
  execFileSync(adb, ['-s', serial, 'shell', 'run-as', pkg, 'chmod', '600', 'files/' + name]);
  const child = spawn(adb, [
    '-s',
    serial,
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    'com.agenthub.android.TaskHubLiveSessionTest',
    '-e',
    'taskHubPrivateFixture',
    name,
    pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
  ]);
  const chunks = [];
  child.stdout.on('data', (data) => chunks.push(data));
  child.stderr.on('data', (data) => chunks.push(data));
  const code = await new Promise((done, reject) => {
    child.once('close', done);
    child.once('error', reject);
  });
  const text = Buffer.concat(chunks)
    .toString('utf8')
    .replaceAll(credentials.BOOTSTRAP_PASSWORD, '[private]')
    .replace(/thm_[A-Za-z0-9_-]{43}/g, '[private-session]');
  await writeFile(output + '/native.log', text, { mode: 0o600 });
  assert.equal(code, 0);
  assert(text.includes('OK (1 test)') && !text.includes('FAILURES'));
  assert.equal(
    (await taskHubNativeFingerprint(process.cwd(), sources)).sha256,
    implementation.sha256,
    'Native inputs changed during session verification',
  );
  Object.assign(report, {
    status: 'passed',
    completedAt: new Date().toISOString(),
    originalServiceIdentityVerified: true,
    credentialRestorationAndRemoteLogout: true,
    businessTasksSubmitted: 0,
    agentInputsSubmitted: 0,
  });
  await save();
  console.log(output + '/report.json');
} catch (error) {
  Object.assign(report, {
    status: 'failed',
    completedAt: new Date().toISOString(),
    error: error.message.replaceAll(credentials.BOOTSTRAP_PASSWORD, '[private]'),
  });
  await save();
  throw error;
} finally {
  fixture.fill(0);
  execFileSync(adb, ['-s', serial, 'shell', 'run-as', pkg, 'rm', '-f', 'files/' + name]);
  execFileSync(adb, ['-s', serial, 'reverse', '--remove', port]);
}
