import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  closeSync,
  existsSync,
  fstatSync,
  lstatSync,
  openSync,
  readFileSync,
  readSync,
  renameSync,
  unlinkSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  assertPrivateFile,
  makePrivateDirectorySync,
  openPrivateFileSync,
  writePrivateFileSync,
  closeWindowsPrivateStateBackend,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { verifyRegularRuntime } from './windows-host-regular-runtime.mjs';
import { planRegularUpgrade } from './windows-host-regular-upgrade-plan.mjs';
import { regularPowerShellEnvironment } from './windows-host-regular-recovery.mjs';

const repository = fileURLToPath(new URL('..', import.meta.url));
const anchor = join(homedir(), '.config/agent-hub/windows-host-v1');
const pointer = join(anchor, 'current.json');
const operations = join(anchor, 'upgrades');
const lock = join(operations, 'active.json');
const hash = (v) => createHash('sha256').update(v).digest('hex');
const fail = (code) => {
  throw new Error(code);
};
const json = (value) => JSON.stringify(value) + '\n';
const sha = (path) => hash(readFileSync(path));
function readPrivate(path, maximum = 2 * 1024 * 1024) {
  assertPrivateFile(path);
  const before = lstatSync(path, { bigint: true });
  const fd = openSync(path, 'r');
  try {
    const stat = fstatSync(fd, { bigint: true });
    if (
      !stat.isFile() ||
      stat.nlink !== 1n ||
      stat.size > BigInt(maximum) ||
      stat.dev !== before.dev ||
      stat.ino !== before.ino
    )
      fail('HOST_UPGRADE_FILE_REJECTED');
    // Bound allocation and reads on the retained file, even while the running
    // Host publishes a new state. Never turn a growing file into an unbounded read.
    const bytes = Buffer.alloc(Number(stat.size));
    let offset = 0;
    while (offset < bytes.length) {
      const count = readSync(fd, bytes, offset, bytes.length - offset, offset);
      if (!count) fail('HOST_UPGRADE_CONCURRENT_CHANGE');
      offset += count;
    }
    if (readSync(fd, Buffer.alloc(1), 0, 1, offset)) fail('HOST_UPGRADE_CONCURRENT_CHANGE');
    const after = fstatSync(fd, { bigint: true });
    assertPrivateFile(path);
    const current = lstatSync(path, { bigint: true });
    if (
      after.size !== stat.size ||
      after.mtimeNs !== stat.mtimeNs ||
      after.ctimeNs !== stat.ctimeNs ||
      current.dev !== stat.dev ||
      current.ino !== stat.ino
    )
      fail('HOST_UPGRADE_CONCURRENT_CHANGE');
    return bytes;
  } finally {
    closeSync(fd);
  }
}
export function assertUpgradeRuntimeIdentity(file, hostId) {
  // Runtime state contains conversation/cache metadata and is legitimately much
  // larger than a configuration or receipt. Keep their 2 MiB limit unchanged.
  const state = JSON.parse(readPrivate(file, 64 * 1024 * 1024));
  if (!state || state.hostId !== hostId) fail('HOST_UPGRADE_HOST_CHANGED');
}
function ps(script, args, root = repository, environment = {}) {
  return execFileSync(
    join(process.env.SystemRoot ?? 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
    ['-NoProfile', '-NonInteractive', '-File', join(root, script), ...args],
    {
      encoding: 'utf8',
      windowsHide: true,
      timeout: args.includes('verify-runtime') ? 45000 : 60000,
      maxBuffer: 16384,
      env: { ...regularPowerShellEnvironment(process.env), ...environment },
    },
  );
}
function task(action, directory) {
  const planPath = join(directory, 'plan.json');
  const planBytes = readPrivate(planPath);
  const plan = JSON.parse(planBytes);
  const lockValue = JSON.parse(readPrivate(lock));
  if (lockValue.operationId !== plan.operationId || lockValue.planSha256 !== hash(planBytes))
    fail('HOST_UPGRADE_PLAN_CHANGED');
  const snapshot = join(directory, 'task.json');
  const expectedSnapshot =
    action === 'snapshot'
      ? undefined
      : JSON.parse(readPrivate(join(directory, 'snapshot.json'))).snapshotSha256;
  return JSON.parse(
    ps(
      'scripts/windows-host-regular-upgrade-task.ps1',
      [
        '-Action',
        action,
        '-PlanPath',
        planPath,
        '-PlanSha256',
        lockValue.planSha256,
        '-SnapshotPath',
        snapshot,
        ...(action === 'snapshot' ? [] : ['-SnapshotSha256', expectedSnapshot]),
      ],
      plan.newRoute.program,
      { CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'profile-v1' },
    ),
  );
}
function stop(action) {
  const root = dirname(dirname(action.worker));
  verifyRegularRuntime(root, root.split(/[\\/]/).at(-1));
  return JSON.parse(
    ps(
      'scripts/windows-host-task.ps1',
      [
        '-Action',
        'stop',
        '-NodePath',
        action.node,
        '-LauncherPath',
        action.launcher,
        '-WorkerPath',
        action.worker,
        '-ConfigPath',
        action.configuration,
      ],
      root,
    ),
  );
}
function receipt(directory, phase, extra = {}) {
  writePrivateFileSync(
    join(directory, phase + '.json'),
    json({ phase, at: new Date().toISOString(), ...extra }),
    { flag: 'wx', flush: true },
  );
}
function checkDigest(path, expected) {
  if (hash(readPrivate(path)) !== expected) fail('HOST_UPGRADE_CONCURRENT_CHANGE');
}
// File.Replace is atomic publication, with optimistic before/backup/after fences.
// It is NOT a kernel compare-and-swap against a noncooperating writer.
export function replaceUpgradeFile({ target, bytes, expected, directory, name }, run = ps) {
  checkDigest(target, expected);
  const prepared = join(directory, name + '.prepared');
  const backup = join(directory, name + '.backup');
  if (existsSync(prepared) || existsSync(backup)) fail('HOST_UPGRADE_REPLACEMENT_EXISTS');
  writePrivateFileSync(prepared, bytes, { flag: 'wx', flush: true });
  const result = JSON.parse(
    run('packages/codex-adapter/windows/response-config-replace.ps1', [
      '-Target',
      target,
      '-Prepared',
      prepared,
      '-Backup',
      backup,
      '-Expected',
      expected,
    ]),
  );
  if (result.replacedSha256 !== expected || sha(backup) !== expected || sha(target) !== hash(bytes))
    fail('HOST_UPGRADE_REPLACEMENT_UNCERTAIN');
  assertPrivateFile(target);
  assertPrivateFile(backup);
  return hash(bytes);
}
export function publishUpgradeRuntime(source, expected, destination, staging) {
  const manifest = verifyRegularRuntime(source, expected);
  if (existsSync(destination)) {
    verifyRegularRuntime(destination, expected);
    for (const name of [...Object.keys(manifest.files), 'runtime-manifest.json'])
      assertPrivateFile(join(destination, name));
    return manifest;
  }
  if (existsSync(staging)) fail('HOST_UPGRADE_STAGING_EXISTS');
  makePrivateDirectorySync(staging);
  const directories = new Set([staging]);
  for (const name of [...Object.keys(manifest.files), 'runtime-manifest.json']) {
    const target = join(staging, name);
    if (!directories.has(dirname(target))) {
      makePrivateDirectorySync(dirname(target));
      directories.add(dirname(target));
    }
    writePrivateFileSync(target, readFileSync(join(source, name)), { flag: 'wx' });
  }
  verifyRegularRuntime(staging, expected);
  // Both siblings have private, non-reparse parents created/checked natively.
  makePrivateDirectorySync(dirname(destination));
  renameSync(staging, destination);
  verifyRegularRuntime(destination, expected);
  return manifest;
}
function load(directory) {
  directory = resolve(directory);
  const name = directory.slice(operations.length + 1);
  if (
    dirname(directory).toLowerCase() !== operations.toLowerCase() ||
    !/^[a-f0-9-]{36}$/.test(name)
  )
    fail('HOST_UPGRADE_OPERATION_REJECTED');
  const planBytes = readPrivate(join(directory, 'plan.json'));
  const plan = JSON.parse(planBytes);
  const lockValue = JSON.parse(readPrivate(lock));
  if (
    plan.operationId !== name ||
    lockValue.operationId !== name ||
    lockValue.planSha256 !== hash(planBytes)
  )
    fail('HOST_UPGRADE_LOCK_CHANGED');
  // Reconstruct the immutable plan rather than trusting paths from a receipt.
  const checked = planRegularUpgrade({
    operationId: plan.operationId,
    profile: homedir(),
    current: plan.oldRoute,
    expectedCurrentSha256: plan.expectedCurrentSha256,
    desiredRuntimeSha256: plan.newRoute.runtimeSha256,
    desiredSourceCommit: plan.newRoute.sourceCommit,
    configuration: plan.oldConfiguration,
    launcherPath: plan.oldAction.launcher,
    launcherSha256: plan.oldAction.launcherSha256,
    responseConfigPath: plan.newConfiguration.environment.CODEX_DESKTOP_RESPONSES_CONFIG,
  });
  for (const key of Object.keys(checked))
    if (json(checked[key]) !== json(plan[key])) fail('HOST_UPGRADE_PLAN_CHANGED');
  checkDigest(plan.oldAction.launcher, plan.oldAction.launcherSha256);
  return { directory, plan };
}
function release(directory, plan) {
  if (JSON.parse(readPrivate(lock)).operationId !== plan.operationId)
    fail('HOST_UPGRADE_LOCK_CHANGED');
  unlinkSync(lock);
  return {
    operationId: plan.operationId,
    directory,
    hostId: plan.oldRoute.hostId,
    runtimeSha256: plan.newRoute.runtimeSha256,
  };
}
export function inspectUpgradePreparation(abandon = false) {
  if (!existsSync(lock)) return { active: false };
  const value = JSON.parse(readPrivate(lock));
  if (!/^[a-f0-9-]{36}$/.test(value.operationId ?? '')) fail('HOST_UPGRADE_LOCK_CHANGED');
  const directory = join(operations, value.operationId);
  const phases = [
    'plan',
    'task',
    'snapshot',
    'prepared',
    'stop-intent',
    'stopped',
    'task-replaced',
    'configuration-replaced',
    'pointer-replaced',
    'run-intent',
    'started',
    'healthy',
    'interrupted',
  ];
  const present = phases.filter((name) => existsSync(join(directory, name + '.json')));
  const result = {
    active: true,
    operationId: value.operationId,
    directory,
    present,
    pointerUnchanged: hash(readPrivate(pointer)) === value.pointerSha256,
    configurationUnchanged: hash(readPrivate(value.configuration)) === value.configurationSha256,
  };
  if (abandon) {
    // Preparation only publishes unused code and reads the Task. Once the stop
    // intent exists, reconcile/rollback is required; never replay an operation.
    if (
      present.includes('stop-intent') ||
      !result.pointerUnchanged ||
      !result.configurationUnchanged
    )
      fail('HOST_UPGRADE_ABANDON_REJECTED');
    makePrivateDirectorySync(directory);
    receipt(directory, 'abandoned-preparation', { operationId: value.operationId });
    if (JSON.parse(readPrivate(lock)).operationId !== value.operationId)
      fail('HOST_UPGRADE_LOCK_CHANGED');
    unlinkSync(lock);
    return { ...result, active: false, status: 'abandoned-preparation', preservedArtifacts: true };
  }
  return result;
}
export function prepareRegularUpgrade({
  source,
  runtimeSha256,
  launcherPath,
  launcherSha256,
  responseConfigPath,
}) {
  if (process.platform !== 'win32') fail('HOST_UPGRADE_WINDOWS_ONLY');
  // Official read-only route checks profile owner/DACL/source namespace before use.
  const route = JSON.parse(ps('scripts/windows-host-regular-route.ps1', []));
  const currentBytes = readPrivate(pointer);
  if (route.pointerSha256 !== hash(currentBytes)) fail('HOST_UPGRADE_ROUTE_CHANGED');
  const current = JSON.parse(currentBytes);
  const configurationBytes = readPrivate(current.configuration);
  const configuration = JSON.parse(configurationBytes);
  assertUpgradeRuntimeIdentity(join(current.state, 'runtime.json'), current.hostId);
  const manifest = verifyRegularRuntime(source, runtimeSha256);
  verifyRegularRuntime(current.program, current.runtimeSha256);
  // This entry deliberately reuses the installed native launcher. A changed
  // launcher implementation needs its own proven installer, not implicit repair.
  for (const name of [
    'host-launcher.cs',
    'private-state.cs',
    'private-state-compilation.cs',
    'private-state-recovery.cs',
  ]) {
    const relative = join('packages/codex-adapter/windows', name);
    if (sha(join(source, relative)) !== sha(join(current.program, relative)))
      fail('HOST_UPGRADE_LAUNCHER_CHANGE_UNSUPPORTED');
  }
  checkDigest(launcherPath, launcherSha256);
  if (responseConfigPath) readPrivate(responseConfigPath);
  const operationId = randomUUID();
  const plan = planRegularUpgrade({
    operationId,
    profile: homedir(),
    current,
    expectedCurrentSha256: hash(currentBytes),
    desiredRuntimeSha256: runtimeSha256,
    desiredSourceCommit: manifest.sourceCommit,
    configuration,
    launcherPath,
    launcherSha256,
    responseConfigPath,
  });
  plan.expectedConfigurationSha256 = hash(configurationBytes);
  plan.taskHelperSources = Object.fromEntries(
    ['private-state.cs', 'private-state-compilation.cs', 'private-state-recovery.cs'].map(
      (name) => [name, sha(join(source, 'packages/codex-adapter/windows', name))],
    ),
  );
  makePrivateDirectorySync(operations);
  const fd = openPrivateFileSync(lock, 'wx');
  try {
    writePrivateFileSync(
      fd,
      json({
        operationId,
        planSha256: hash(json(plan)),
        pointerSha256: hash(currentBytes),
        configuration: current.configuration,
        configurationSha256: hash(configurationBytes),
        pid: process.pid,
        at: new Date().toISOString(),
      }),
      { flush: true },
    );
  } finally {
    closeSync(fd);
  }
  const directory = join(operations, operationId);
  makePrivateDirectorySync(directory);
  writePrivateFileSync(join(directory, 'plan.json'), json(plan), { flag: 'wx', flush: true });
  writePrivateFileSync(join(directory, 'original-pointer.json'), currentBytes, { flag: 'wx' });
  writePrivateFileSync(join(directory, 'original-configuration.json'), configurationBytes, {
    flag: 'wx',
  });
  publishUpgradeRuntime(
    source,
    runtimeSha256,
    plan.newRoute.program,
    join(dirname(plan.newRoute.program), '.upgrade-' + operationId),
  );
  const snapshot = task('snapshot', directory);
  receipt(directory, 'snapshot', snapshot);
  receipt(directory, 'prepared', { runtimeSha256, sourceCommit: manifest.sourceCommit });
  return {
    directory,
    operationId,
    hostId: current.hostId,
    runtimeSha256,
    sourceCommit: manifest.sourceCommit,
  };
}
async function health(plan) {
  const config = plan.newConfiguration.environment;
  const token = readPrivate(join(plan.oldRoute.state, 'access-token')).toString().trim();
  const headers = { authorization: `Bearer ${token}` };
  const base = `http://127.0.0.1:${config.HOST_AGENT_PORT}`;
  const until = Date.now() + 45000;
  let last = 'HOST_UPGRADE_HEALTH_TIMEOUT';
  while (Date.now() < until) {
    try {
      const res = await fetch(base + '/v1/workspace', {
        headers,
        signal: AbortSignal.timeout(3000),
      });
      const value = await res.json();
      if (
        !res.ok ||
        value.hostId !== plan.oldRoute.hostId ||
        value.connectionMode !== 'desktop-runtime'
      )
        fail('HOST_UPGRADE_HEALTH_IDENTITY');
      if (config.CODEX_DESKTOP_RESPONSES_CONFIG) {
        const response = JSON.parse(readPrivate(config.CODEX_DESKTOP_RESPONSES_CONFIG));
        const ready = await fetch(
          `http://127.0.0.1:${response.port}/_agent_hub/${response.bindingId}/backend-api/health`,
          { signal: AbortSignal.timeout(2000) },
        );
        const body = await ready.json();
        if (
          !ready.ok ||
          !body.ready ||
          body.bindingId !== response.bindingId ||
          body.port !== response.port
        )
          fail('HOST_UPGRADE_CAPTURE_NOT_READY');
      }
      return { hostId: value.hostId, connectionMode: value.connectionMode };
    } catch (error) {
      last = error.message;
    }
    await new Promise((done) => setTimeout(done, 1000));
  }
  fail(last.startsWith('HOST_UPGRADE_') ? last : 'HOST_UPGRADE_HEALTH_TIMEOUT');
}
export async function applyRegularUpgrade(operation) {
  const { directory, plan } = load(operation);
  if (
    !existsSync(join(directory, 'prepared.json')) ||
    existsSync(join(directory, 'stop-intent.json'))
  )
    fail('HOST_UPGRADE_REPLAY_REJECTED');
  checkDigest(pointer, plan.expectedCurrentSha256);
  checkDigest(plan.oldRoute.configuration, plan.expectedConfigurationSha256);
  verifyRegularRuntime(plan.newRoute.program, plan.newRoute.runtimeSha256);
  receipt(directory, 'stop-intent');
  try {
    stop(plan.oldAction);
    receipt(directory, 'stopped');
    task('apply', directory);
    receipt(directory, 'task-replaced');
    replaceUpgradeFile({
      target: plan.oldRoute.configuration,
      bytes: json(plan.newConfiguration),
      expected: plan.expectedConfigurationSha256,
      directory,
      name: 'configuration',
    });
    receipt(directory, 'configuration-replaced');
    replaceUpgradeFile({
      target: pointer,
      bytes: json(plan.newRoute),
      expected: plan.expectedCurrentSha256,
      directory,
      name: 'pointer',
    });
    receipt(directory, 'pointer-replaced');
    receipt(directory, 'run-intent');
    const started = task('start', directory);
    receipt(directory, 'started', { task: started });
    const result = await health(plan);
    const runtime = task('verify-runtime', directory);
    receipt(directory, 'runtime-verified', runtime);
    receipt(directory, 'healthy', result);
    return { ...release(directory, plan), status: 'healthy', ...result };
  } catch (error) {
    receipt(directory, 'interrupted', { code: error.message.slice(0, 160) });
    // No automatic replay or blind rollback. Query exact Task/route and use the
    // explicit rollback entry only when every currently owned object matches.
    throw error;
  }
}
export async function rollbackRegularUpgrade(operation) {
  const { directory, plan } = load(operation);
  if (existsSync(join(directory, 'rollback-intent.json'))) fail('HOST_UPGRADE_REPLAY_REJECTED');
  const currentPointer = hash(readPrivate(pointer));
  const currentConfig = hash(readPrivate(plan.oldRoute.configuration));
  const newPointer = hash(json(plan.newRoute)),
    newConfig = hash(json(plan.newConfiguration));
  if (
    ![plan.expectedCurrentSha256, newPointer].includes(currentPointer) ||
    ![plan.expectedConfigurationSha256, newConfig].includes(currentConfig)
  )
    fail('HOST_UPGRADE_ROLLBACK_CONFLICT');
  const status = task('status', directory);
  if (!['old', 'new'].includes(status.route)) fail('HOST_UPGRADE_ROLLBACK_TASK_CONFLICT');
  receipt(directory, 'rollback-intent');
  stop(status.route === 'new' ? plan.newAction : plan.oldAction);
  if (currentPointer === newPointer)
    replaceUpgradeFile({
      target: pointer,
      bytes: readPrivate(join(directory, 'original-pointer.json')),
      expected: newPointer,
      directory,
      name: 'rollback-pointer',
    });
  if (currentConfig === newConfig)
    replaceUpgradeFile({
      target: plan.oldRoute.configuration,
      bytes: readPrivate(join(directory, 'original-configuration.json')),
      expected: newConfig,
      directory,
      name: 'rollback-configuration',
    });
  if (status.route === 'new') task('restore', directory);
  receipt(directory, 'rollback-run-intent');
  task('start-old', directory);
  const result = await health({ ...plan, newConfiguration: plan.oldConfiguration });
  receipt(directory, 'rolled-back', result);
  return { ...release(directory, plan), status: 'rolled-back' };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [action, ...args] = process.argv.slice(2);
    let result;
    if (action === 'prepare' && args.length >= 4 && args.length <= 5) {
      result = prepareRegularUpgrade({
        source: resolve(args[0]),
        runtimeSha256: args[1],
        launcherPath: args[2],
        launcherSha256: args[3],
        responseConfigPath: args[4],
      });
    } else if (action === 'inspect' && args.length === 0) result = inspectUpgradePreparation();
    else if (action === 'abandon-preparation' && args.length === 0)
      result = inspectUpgradePreparation(true);
    else if (action === 'apply' && args.length === 1) result = await applyRegularUpgrade(args[0]);
    else if (action === 'rollback' && args.length === 1)
      result = await rollbackRegularUpgrade(args[0]);
    else if (action === 'status' && args.length === 1) {
      const { directory, plan } = load(args[0]);
      result = {
        task: task('status', directory),
        pointerSha256: sha(pointer),
        configurationSha256: sha(plan.oldRoute.configuration),
      };
    } else fail('HOST_UPGRADE_ARGUMENTS_REJECTED');
    console.log(json(result));
  } finally {
    closeWindowsPrivateStateBackend();
  }
}
