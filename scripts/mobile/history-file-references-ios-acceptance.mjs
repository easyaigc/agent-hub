import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmod, lstat, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

// Run from the approved frozen checkout only. All arguments, target data and
// evidence remain private; credentials enter XCTest only through its environment.
process.umask(0o077);
const root = await realpath(process.cwd());
const inputs = [
  'apps/ios',
  'apps/android',
  'packages/mobile-core',
  'packages/protocol',
  'scripts/mobile',
  'gradle',
  'gradlew',
  'gradlew.bat',
  'gradle.properties',
  'build.gradle.kts',
  'settings.gradle.kts',
];
const files = [
  ...new Set(
    execFileSync('git', ['ls-files', '-co', '--exclude-standard', '-z', '--', ...inputs], {
      cwd: root,
      encoding: 'utf8',
    })
      .split('\0')
      .filter(Boolean),
  ),
].sort();
assert(files.length > 0);
async function fingerprint(copyTo, from = root) {
  const hash = createHash('sha256');
  for (const file of files) {
    const path = join(from, file);
    const info = await lstat(path);
    assert(info.isFile() && !info.isSymbolicLink(), 'Build input must be a regular file');
    const bytes = await readFile(path);
    const mode = info.mode & 0o777;
    hash
      .update(file + '\0' + mode + '\0')
      .update(bytes)
      .update('\0');
    if (copyTo) {
      const dest = join(copyTo, file);
      await mkdir(dirname(dest), { recursive: true, mode: 0o700 });
      await writeFile(dest, bytes, { mode });
      await chmod(dest, mode);
    }
  }
  return hash.digest('hex');
}
// Refuse changed inputs before creating a build directory, and detect changes
// during copying before using the isolated snapshot.
if (process.argv.length === 3 && process.argv[2] === '--fingerprint-only') {
  console.log(await fingerprint());
  process.exit(0);
}
assert.equal(process.argv.length, 2, 'Use no arguments or --fingerprint-only');
const configPath = process.env.HISTORY_FILE_REFERENCES_CONFIG;
assert(configPath, 'Set HISTORY_FILE_REFERENCES_CONFIG to the private configuration path');
const configInfo = await lstat(configPath);
assert(
  configInfo.isFile() && !configInfo.isSymbolicLink() && (configInfo.mode & 0o077) === 0,
  'Private configuration must be a regular owner-only file',
);
const privateConfig = JSON.parse(await readFile(configPath, 'utf8'));
const {
  deviceId,
  bundleId,
  sourceSha256,
  expectedHostId,
  expectedThreadId,
  connection,
  lastReply,
  earlierText,
} = privateConfig;
assert(typeof deviceId === 'string' && deviceId.length > 0);
assert(/^com\.easyai\.agenthub\.historyreferences\.[a-f0-9]{8}$/.test(bundleId));
assert(/^[a-f0-9]{64}$/.test(sourceSha256));
assert(connection && typeof expectedHostId === 'string' && expectedHostId.trim().length > 0);
assert.equal(connection.hostId, expectedHostId, 'DEV Host must match the private expected target');
assert(
  typeof expectedThreadId === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(expectedThreadId),
  'Expected thread must be a valid UUID',
);
assert.equal(
  connection.threadId,
  expectedThreadId,
  'Thread must match the private expected target',
);
assert(typeof connection.threadTitle === 'string' && connection.threadTitle.length > 0);
assert(new URL(connection.endpoint).protocol === 'https:');
assert(
  typeof lastReply === 'string' &&
    lastReply.trim() &&
    typeof earlierText === 'string' &&
    earlierText.trim() &&
    lastReply !== earlierText,
);
assert(
  typeof connection.token === 'string' && connection.token.length > 0,
  'Private DEV credential is required',
);
assert(process.env.DEVELOPER_DIR === '/Applications/Xcode.app/Contents/Developer');
const testConnection = {
  endpoint: connection.endpoint,
  hostId: connection.hostId,
  threadId: connection.threadId,
  threadTitle: connection.threadTitle,
  historyThreadArchived: connection.historyThreadArchived ?? false,
  token: connection.token,
  nonce: '',
  secondEndpoint: '',
  secondToken: '',
  secondHostId: '',
  delayedEndpoint: '',
};

assert.equal(await fingerprint(), sourceSha256, 'Frozen build-input fingerprint mismatch');
const directory = resolve(
  root,
  '.local/history-file-references-ios',
  `${Date.now()}-${process.pid}`,
);
const source = join(directory, 'source');
await mkdir(source, { recursive: true, mode: 0o700 });
assert.equal(await fingerprint(source), sourceSha256, 'Build inputs changed during snapshot');
assert.equal(
  await fingerprint(undefined, source),
  sourceSha256,
  'Copied build inputs differ from approved fingerprint',
);
assert.equal(await fingerprint(), sourceSha256, 'Build inputs changed after snapshot');
const currentFiles = [
  ...new Set(
    execFileSync('git', ['ls-files', '-co', '--exclude-standard', '-z', '--', ...inputs], {
      cwd: root,
      encoding: 'utf8',
    })
      .split('\0')
      .filter(Boolean),
  ),
].sort();
assert.deepEqual(currentFiles, files, 'Build input manifest changed during snapshot');
await mkdir(join(source, '.local'), { mode: 0o700 });
try {
  await writeFile(
    join(source, '.local/mobile-toolchain.json'),
    await readFile(join(root, '.local/mobile-toolchain.json')),
    { mode: 0o600 },
  );
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const project = join(source, 'apps/ios/CodexManage.xcodeproj');
const pbx = join(project, 'project.pbxproj');
const data = JSON.parse(
  execFileSync('plutil', ['-convert', 'json', '-o', '-', pbx], { encoding: 'utf8' }),
);
for (const object of Object.values(data.objects)) {
  for (const target of Object.values(object.attributes?.TargetAttributes ?? {}))
    target.SystemCapabilities = {};
  for (const key of Object.keys(object.buildSettings ?? {}))
    if (key.startsWith('CODE_SIGN_ENTITLEMENTS') || key.startsWith('PROVISIONING_PROFILE'))
      delete object.buildSettings[key];
}
await writeFile(pbx, JSON.stringify(data));
execFileSync('plutil', ['-convert', 'xml1', pbx]);
const resultPath = join(directory, 'result.xcresult');
const evidence = { status: 'running', sourceSha256, bundleId, resultPath };
const save = () =>
  writeFile(join(directory, 'run.json'), JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600 });
await save();
try {
  const deviceFile = join(directory, 'coredevice.json');
  execFileSync(
    'xcrun',
    ['devicectl', 'device', 'info', 'details', '--device', deviceId, '--json-output', deviceFile],
    { stdio: 'ignore' },
  );
  const device = JSON.parse(await readFile(deviceFile, 'utf8')).result;
  assert.equal(device.hardwareProperties.reality, 'physical');
  assert.equal(device.hardwareProperties.marketingName, 'iPhone 15 Pro Max');
  assert.equal(device.connectionProperties.pairingState, 'paired');
  assert.equal(device.connectionProperties.tunnelState, 'connected');
  evidence.transport = device.connectionProperties.transportType;
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-configuration',
      'Release',
      '-destination',
      `platform=iOS,id=${deviceId}`,
      '-derivedDataPath',
      join(directory, 'derived'),
      '-resultBundlePath',
      resultPath,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'SWIFT_OPTIMIZATION_LEVEL=-O',
      'SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG',
      `NOTIFICATION_APP_BUNDLE_ID=${bundleId}`,
      '-only-testing:CodexManageUITests/CodexManageUITests/testHistoryFileReferencesReadOnlyDEV',
      'test',
    ],
    {
      cwd: source,
      env: {
        ...process.env,
        GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? resolve(root, '.local/gradle-home'),
        TEST_RUNNER_HISTORY_FILE_REFERENCES_JSON: JSON.stringify({
          bundleId,
          connection: testConnection,
          lastReply,
          earlierText,
        }),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  // Discard raw output: XCTest can log individual secure-field keystrokes.
  child.stdout.resume();
  child.stderr.resume();
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      ['xcresulttool', 'get', 'test-results', 'summary', '--path', resultPath, '--format', 'json'],
      { encoding: 'utf8' },
    ),
  );
  evidence.verifiedDestinations = verifyIOSResultDevice(summary, deviceId);
  evidence.passedTests = summary.passedTests;
  evidence.failedTests = summary.failedTests;
  evidence.skippedTests = summary.skippedTests;
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  evidence.status = 'passed';
} catch (error) {
  evidence.status = 'failed';
  // Avoid including paths, identities, private input or child output in public output.
  evidence.failure = error.name;
  process.exitCode = 1;
} finally {
  // Raw XCTest results and derived build products may contain typed credentials.
  // They are task-owned paths; retain only the private summary and device record.
  await rm(resultPath, { recursive: true, force: true });
  await rm(join(directory, 'derived'), { recursive: true, force: true });
  await rm(source, { recursive: true, force: true });
  await save();
  console.log(`Private acceptance evidence: ${join(directory, 'run.json')} (${evidence.status})`);
}
