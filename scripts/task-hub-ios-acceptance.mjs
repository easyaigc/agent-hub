import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';
import { taskHubAcceptanceOutput } from './task-hub-acceptance-state.mjs';
import {
  taskHubNativeSourceFiles,
  taskHubNativeFingerprint,
  freezeTaskHubNativeInputs,
} from './task-hub-native-inputs.mjs';
import {
  probe,
  sameIdentity,
  verifyBundle,
  verifyRegistration,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

// Drives actual native UI against an installed, isolated Task Hub deployment.
// It neither creates a backend nor replaces an existing Host or phone installation.
const args = process.argv.slice(2);
const option = (name) => {
  const index = args.indexOf('--' + name);
  return index < 0 ? undefined : args[index + 1];
};
const state = option('state');
const device = option('device');
if (!state || !device)
  throw Error('Use --state <installed DEV state> --device <UDID> [--physical]');
const physical = args.includes('--physical');
const scenario = option('scenario') ?? 'workflow';
if (!['workflow', 'network', 'resume-development'].includes(scenario))
  throw Error('Unknown native scenario');
const testCase =
  scenario === 'network'
    ? 'TaskHubNetworkUITests/testTaskHubRealUnknownAndEndWaitingDEV'
    : scenario === 'resume-development'
      ? 'TaskHubUITests/testTaskHubResumeOriginalDevelopmentRoundDEV'
      : 'TaskHubUITests/testTaskHubLANSharedWorkflowDEV';
const resume =
  scenario === 'resume-development'
    ? {
        nonce: option('nonce'),
        taskId: option('task-id'),
        revision: option('revision'),
        round: '2',
      }
    : null;
if (
  resume &&
  (!/^[a-f0-9]{8}$/.test(resume.nonce ?? '') ||
    !/^[a-f0-9-]{36}$/i.test(resume.taskId ?? '') ||
    !/^[1-9][0-9]*$/.test(resume.revision ?? ''))
)
  throw Error('Resume requires the independently verified original nonce, task UUID and revision');
const root = await taskHubAcceptanceOutput(process.cwd(), option('output-root'), 'acceptance');
const privateJSON = async (path) => {
  const metadata = await lstat(path);
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.mode & 0o077)
    throw Error('Private deployment file permissions are invalid');
  return JSON.parse(await readFile(path, 'utf8'));
};
const env = await privateJSON(join(resolve(state), 'env.json'));
await privateJSON(join(resolve(state), 'ready.json'));
const configuration = await privateJSON(join(resolve(state), 'runtime.json'));
const trust = await privateJSON(join(resolve(state), 'trust.json'));
const registry = verifyRegistration(
  JSON.parse(await readFile(join(resolve(state), 'deployment.json'), 'utf8')),
  trust.registryKeySha256,
);
const manifest = await verifyBundle(configuration.bundle);
const identity = await probe(registry);
if (
  !sameIdentity(configuration, registry) ||
  !identity.identityVerified ||
  !identity.backendReady ||
  manifest.payloadSha256 !== configuration.payloadSha256
)
  throw Error('The selected DEV backend or bundle differs from its preserved registration');
if (!configuration.deploymentId?.includes('dev'))
  throw Error('Native acceptance requires a dedicated DEV deployment');
const endpoint = option('endpoint') ?? env.WEB_ORIGIN;
if (!endpoint?.startsWith('https://')) throw Error('HTTPS Task Hub endpoint required');
const selectedEndpoint = new URL(endpoint);
if (
  selectedEndpoint.hostname !== registry.domain ||
  selectedEndpoint.port !== (scenario === 'network' ? '19546' : String(configuration.tlsPort))
)
  throw Error(
    'Native testing must use its registered primary or exact same-certificate fault proxy',
  );
const networkEnvironment =
  scenario === 'network'
    ? {
        TEST_RUNNER_TASK_HUB_NETWORK_RUN_ID: process.env.TASK_HUB_NETWORK_RUN_ID,
        TEST_RUNNER_TASK_HUB_NETWORK_CERT_SHA256: process.env.TASK_HUB_NETWORK_CERT_SHA256,
        TEST_RUNNER_TASK_HUB_NETWORK_SCENARIO: process.env.TASK_HUB_NETWORK_SCENARIO,
      }
    : {};
if (
  scenario === 'network' &&
  (!/^[a-f0-9-]{36}$/i.test(networkEnvironment.TEST_RUNNER_TASK_HUB_NETWORK_RUN_ID ?? '') ||
    networkEnvironment.TEST_RUNNER_TASK_HUB_NETWORK_CERT_SHA256 !== registry.certificateSha256 ||
    networkEnvironment.TEST_RUNNER_TASK_HUB_NETWORK_SCENARIO !== '1')
)
  throw Error('Exact network harness scope and certificate required');
const passwords = [env.BOOTSTRAP_PASSWORD];
if (!passwords[0]) throw Error('A private DEV login is required');
const sourceFiles = taskHubNativeSourceFiles(process.cwd());
const fingerprint = () => taskHubNativeFingerprint(process.cwd(), sourceFiles);
const implementation = await fingerprint();
const frozenRoot = join(root, 'frozen-source');
await freezeTaskHubNativeInputs(process.cwd(), frozenRoot, implementation);
const frozenIOS = join(frozenRoot, 'apps/ios');
let toolchain = {};
try {
  toolchain = JSON.parse(await readFile(resolve('.local/mobile-toolchain.json'), 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const buildEnvironment = { ...process.env };
for (const [key, localKey] of [
  ['JAVA_HOME', 'javaHome'],
  ['ANDROID_HOME', 'androidSdk'],
  ['KOTLIN_NATIVE_HOME', 'kotlinNativeHome'],
])
  if (!buildEnvironment[key] && typeof toolchain[localKey] === 'string')
    buildEnvironment[key] = toolchain[localKey];
buildEnvironment.GRADLE_USER_HOME ??= join(root, 'gradle-home');
const nonce = resume?.nonce ?? randomUUID().slice(0, 8);
const resultPath = join(root, 'native.xcresult');
const report = {
  taskId: 'agent-hub-ios-task-management-20261014',
  kind: physical ? 'physical-native-task-workflow' : 'simulator-native-task-workflow',
  scenario,
  resume,
  device,
  nonce,
  implementation,
  deploymentId: configuration.deploymentId,
  serverInstanceId: configuration.serverInstanceId,
  primaryHostId: configuration.primaryHostId,
  payloadSha256: configuration.payloadSha256,
  sourceSha256: manifest.sourceSha256,
  certificateSha256: registry.certificateSha256,
  startedAt: new Date().toISOString(),
  phase: 'running',
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
await save();
const xcconfig = join(root, 'signing.xcconfig');
const entitlements = join(root, 'isolated.entitlements');
await writeFile(entitlements, '<?xml version="1.0"?><plist version="1.0"><dict/></plist>', {
  mode: 0o600,
});
await writeFile(
  xcconfig,
  `CODE_SIGN_ENTITLEMENTS = ${entitlements}\nCODE_SIGN_ENTITLEMENTS[sdk=iphoneos*] = ${entitlements}\n`,
  { mode: 0o600 },
);
const buildArgs = [
  '-project',
  join(frozenIOS, 'CodexManage.xcodeproj'),
  '-scheme',
  'CodexManage',
  '-destination',
  (physical ? 'platform=iOS,id=' : 'platform=iOS Simulator,id=') + device,
  '-destination-timeout',
  '30',
  '-derivedDataPath',
  join(root, 'build'),
  '-resultBundlePath',
  resultPath,
  '-parallel-testing-enabled',
  'NO',
  'ONLY_ACTIVE_ARCH=YES',
  '-collect-test-diagnostics',
  'never',
  '-only-testing:CodexManageUITests/' + testCase,
  'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.taskhubdev',
  ...(physical ? [] : ['CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-']),
  '-xcconfig',
  xcconfig,
  '-allowProvisioningUpdates',
  'test',
];
const logPath = join(root, 'native.log');
const log = createWriteStream(logPath, { mode: 0o600 });
const redact = (text) =>
  passwords
    .reduce((value, password) => value.replaceAll(password, '[private]'), text)
    .replace(/Bearer [A-Za-z0-9_.-]+/g, 'Bearer [private]')
    .replace(/thm_[A-Za-z0-9_-]{43}/g, '[private-session]');
const child = spawn('xcodebuild', buildArgs, {
  env: {
    ...buildEnvironment,
    DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
    TEST_RUNNER_TASK_HUB_ENDPOINT: endpoint,
    TEST_RUNNER_TASK_HUB_USERNAME: 'admin',
    TEST_RUNNER_TASK_HUB_PASSWORD: env.BOOTSTRAP_PASSWORD,
    TEST_RUNNER_TASK_HUB_NONCE: nonce,
    ...(resume
      ? {
          TEST_RUNNER_TASK_HUB_RESUME_WORKFLOW: '1',
          TEST_RUNNER_TASK_HUB_RESUME_TASK_ID: resume.taskId,
          TEST_RUNNER_TASK_HUB_RESUME_REVISION: resume.revision,
          TEST_RUNNER_TASK_HUB_RESUME_ROUND: resume.round,
        }
      : {}),
    ...networkEnvironment,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});
for (const stream of [child.stdout, child.stderr]) {
  const lines = createInterface({ input: stream });
  lines.on('line', (line) => log.write(redact(line) + '\n'));
}
const code = await new Promise((done, reject) => {
  child.once('error', reject);
  child.once('close', done);
});
await new Promise((done) => log.end(done));
report.exitCode = code;
report.finishedAt = new Date().toISOString();
report.laterWorkspaceChanges = (await fingerprint()).sha256 !== implementation.sha256;
report.sourceUnchanged = true;
for (const path of sourceFiles) {
  const bytes = await readFile(join(frozenRoot, path));
  if (createHash('sha256').update(bytes).digest('hex') !== implementation.files[path])
    report.sourceUnchanged = false;
}
try {
  report.nativeSummary = JSON.parse(
    execFileSync(
      'xcrun',
      ['xcresulttool', 'get', 'test-results', 'summary', '--path', resultPath, '--format', 'json'],
      {
        env: {
          ...process.env,
          DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
        },
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      },
    ),
  );
} catch {
  report.nativeSummary = null;
}
const summary = report.nativeSummary;
report.phase =
  code === 0 &&
  report.sourceUnchanged &&
  summary?.passedTests > 0 &&
  summary?.failedTests === 0 &&
  summary?.skippedTests === 0
    ? 'passed'
    : 'failed';
report.limitations = [
  'DEV signing excludes APNs capabilities; this run does not verify business push delivery.',
  'Original desktop model execution and independent computer QA require separate evidence.',
];
await save();
console.log(
  JSON.stringify({
    phase: report.phase,
    kind: report.kind,
    report: join(root, 'report.json'),
    log: logPath,
    xcresult: resultPath,
  }),
);
if (report.phase !== 'passed') process.exitCode = 1;
