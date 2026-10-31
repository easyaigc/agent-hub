import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, lstatSync, readFileSync, realpathSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isMainThread } from 'node:worker_threads';
import * as platform from '../packages/codex-adapter/dist/desktop-platform.js';
import { withWindowsProcessCreation } from '../packages/codex-adapter/dist/windows-process.js';
import { verifyHostRuntime } from './verify-host-runtime.mjs';
import { validateWindowsHostEnvironment } from './windows-host-environment.mjs';
import { safeWorkerFailure } from './host-worker-supervisor.mjs';

const repository = fileURLToPath(new URL('../', import.meta.url));
const hash = (value) => createHash('sha256').update(value).digest('hex');
const pathHash = (path) =>
  hash(
    path
      .replace(/^\\\\\?\\UNC\\/i, '\\\\')
      .replace(/^\\\\\?\\/, '')
      .replaceAll('/', '\\')
      .toLowerCase(),
  );
const failure = (code) => Object.assign(new Error('Windows startup preflight failed'), { code });
const stamp = (path) => {
  const s = lstatSync(path, { bigint: true });
  if (!s.isFile() || s.isSymbolicLink()) throw failure('PREFLIGHT_INVALID_FILE');
  return [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs, s.birthtimeNs].join(':');
};
const bootstrap = () => JSON.stringify([process.argv, process.execArgv, process.env.NODE_OPTIONS]);
const safeCode = (code) => (/^[A-Z][A-Z0-9_]{0,63}$/.test(code ?? '') ? code : 'PREFLIGHT_FAILED');
export function startupFailure(error) {
  const result = { ...safeWorkerFailure(error), code: safeCode(error?.code) };
  for (const key of ['nativeFailure', 'nativeStep', 'nativeCleanupStep'])
    if (/^[a-z][a-z0-9-]{0,39}$/.test(error?.[key] ?? '')) result[key] = error[key];
  const stderr = String(error?.stderr ?? error?.cause?.stderr ?? '');
  const rejected =
    /^WINDOWS_PRIVATE_STATE_REJECTED:(WRONG_FILE_OWNER|NULL_PRIVATE_DACL|UNKNOWN_PRIVATE_ACE|SHARED_PRIVATE_FILE|UNPROTECTED_PRIVATE_DACL)\s*$/m.exec(
      stderr,
    )?.[1];
  if (rejected) result.nativeRejection = rejected;
  for (const [key, prefix] of [
    ['nativeWin32', 'WINDOWS_PRIVATE_STATE_WIN32'],
    ['probeWin32', 'WINDOWS_STARTUP_PROBE_WIN32'],
  ]) {
    const value = new RegExp('^' + prefix + ':([0-9]{1,10})\\s*$', 'm').exec(stderr)?.[1];
    if (value !== undefined && Number(value) <= 0xffffffff) result[key] = Number(value);
  }
  return result;
}
function readConfiguration(path) {
  platform.assertPrivateFile(path);
  const s = lstatSync(path);
  if (s.size > 64 * 1024) throw failure('PREFLIGHT_CONFIG_TOO_LARGE');
  return JSON.parse(readFileSync(path, 'utf8'));
}
function runPowerShell(adapter, name, args, json = false) {
  const output = withWindowsProcessCreation(30_000, (timeout) =>
    execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      ['-NoProfile', '-NonInteractive', '-File', join(adapter, 'windows', name), ...args],
      {
        encoding: 'utf8',
        stdio: 'pipe',
        timeout,
        windowsHide: true,
        maxBuffer: 64 * 1024,
        env: platform.windowsPowerShellEnvironment(),
      },
    ),
  );
  if (Buffer.byteLength(output) > 16 * 1024) throw failure('PREFLIGHT_RESPONSE_TOO_LARGE');
  return json ? JSON.parse(output) : output.trim();
}
export function sanitizeStartupContext(value, pid) {
  if (value?.pid !== pid) throw failure('PREFLIGHT_CONTEXT_TARGET_CHANGED');
  const result = {
    pid: value.pid,
    subject: 'actual-preflight-node-process',
    formalHostContext: 'not-observed',
  };
  const digest = (v) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
  for (const key of ['userHash', 'ownerHash', 'imagePathHash', 'helperUserHash'])
    if (digest(value[key])) result[key] = value[key];
  for (const key of [
    'ownerMatchesUser',
    'ownerIsAdministrators',
    'elevated',
    'helperUserMatchesNode',
  ])
    if (typeof value[key] === 'boolean') result[key] = value[key];
  if (
    Number.isSafeInteger(value.sessionId) &&
    value.sessionId >= 0 &&
    value.sessionId <= 0xffffffff
  )
    result.sessionId = value.sessionId;
  if (/^[0-9]{1,20}$/.test(value.startedFileTime ?? ''))
    result.startedFileTime = value.startedFileTime;
  if (['untrusted', 'low', 'medium', 'high', 'system-or-higher'].includes(value.integrity))
    result.integrity = value.integrity;
  for (const key of ['packageFull', 'packageFamily']) {
    const p = value[key];
    if (p?.state === 'none') result[key] = { state: 'none' };
    else if (p?.state === 'present' && digest(p.hash))
      result[key] = { state: 'present', hash: p.hash };
    else if (p?.state === 'error' && Number.isSafeInteger(p.code))
      result[key] = { state: 'error', code: p.code };
  }
  if (value.file && typeof value.file === 'object') {
    result.file = {};
    for (const key of ['directory', 'reparse', 'ownerMatchesNodeUser', 'protected', 'daclPresent'])
      if (typeof value.file[key] === 'boolean') result.file[key] = value.file[key];
    for (const key of ['identityHash', 'finalPathHash', 'descriptorHash', 'ownerHash'])
      if (digest(value.file[key])) result.file[key] = value.file[key];
    for (const key of ['unknownAceCount', 'unsafeAllowCount'])
      if (Number.isSafeInteger(value.file[key]) && value.file[key] >= 0 && value.file[key] < 65536)
        result.file[key] = value.file[key];
  }
  return result;
}
function context(adapter, path) {
  const value = runPowerShell(
    adapter,
    'startup-probe.ps1',
    ['-NodePid', String(process.pid), ...(path ? ['-Path', path] : [])],
    true,
  );
  return sanitizeStartupContext(value, process.pid);
}
function envSummary() {
  return Object.fromEntries(
    ['SystemRoot', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP', 'CODEX_HOME'].map((name) => [
      name,
      process.env[name] === undefined
        ? { state: 'absent' }
        : { state: 'present', hash: pathHash(process.env[name]) },
    ]),
  );
}
export async function runWindowsStartupPreflight({ hostConfig, bundle, workingDirectory }) {
  let runtimePlatform;
  const beforeBootstrap = bootstrap(),
    cwd = process.cwd(),
    saved = new Map(),
    watched = new Map();
  const report = {
    kind: 'windows-host-startup-readonly-preflight',
    schemaVersion: 1,
    node: process.versions.node,
    pid: process.pid,
    parentPid: process.ppid,
    mainThread: isMainThread,
    steps: [],
    passed: false,
    hostStarted: false,
    portsOpened: false,
    tokenContentsRead: false,
    configurationsWritten: false,
    hostStatePrepared: false,
    nativeCachesMayBootstrap: true,
  };
  const step = async (name, operation, exposeValue = true) => {
    const started = performance.now();
    try {
      const value = await operation();
      report.steps.push({
        name,
        passed: true,
        elapsedMs: performance.now() - started,
        ...(!exposeValue || value === undefined ? {} : { value }),
      });
      return { passed: true, value };
    } catch (error) {
      report.steps.push({
        name,
        passed: false,
        elapsedMs: performance.now() - started,
        failure: startupFailure(error),
      });
      return { passed: false };
    }
  };
  const watch = (path) => {
    watched.set(path, stamp(path));
    return path;
  };
  try {
    if (process.platform !== 'win32' || !isMainThread) throw failure('WINDOWS_REQUIRED');
    if (workingDirectory) {
      const s = lstatSync(workingDirectory);
      if (!isAbsolute(workingDirectory) || !s.isDirectory() || s.isSymbolicLink())
        throw failure('PREFLIGHT_INVALID_CWD');
      process.chdir(workingDirectory);
    }
    report.cwdHash = pathHash(process.cwd());
    const closure = await verifyHostRuntime({
      entry: join(repository, 'apps/host-agent/dist/runtime-entry.js'),
      bundle: resolve(bundle),
    });
    report.runtimeFingerprints = Object.fromEntries(
      closure.packages.map((v) => [v.component, v.fingerprint]),
    );
    if (!closure.ok) throw failure('RUNTIME_CLOSURE_MISMATCH');
    const adapter = closure.packages.find((v) => v.name === '@codex-manage/codex-adapter')?.root;
    if (!adapter) throw failure('RUNTIME_CLOSURE_MISMATCH');
    runtimePlatform = await import(pathToFileURL(join(adapter, 'dist/desktop-platform.js')).href);
    report.runtimeAdapterBinding = 'actual-host-esm-closure';
    report.workerBootstrapAndRuntimeShareModule = runtimePlatform === platform;
    report.contextBeforeConfiguration = (
      await step('process-context-before-config', () => context(adapter))
    ).value;
    hostConfig = watch(resolve(hostConfig));
    const config = await step(
      'host-configuration-private-check',
      () => {
        const value = readConfiguration(hostConfig);
        validateWindowsHostEnvironment(value);
        return value;
      },
      false,
    );
    // Configuration values are private process inputs, never report fields.
    if (!config.passed) return report;
    const environment = validateWindowsHostEnvironment(config.value);
    for (const [key, value] of Object.entries(environment)) {
      saved.set(key, process.env[key]);
      process.env[key] = value;
    }
    report.environment = envSummary();
    report.contextAfterConfiguration = (
      await step('process-context-after-config', () => context(adapter))
    ).value;
    if (environment.CODEX_DESKTOP_WINDOWS_CONFIG) {
      const path = resolve(environment.CODEX_DESKTOP_WINDOWS_CONFIG);
      await step('desktop-configuration-private-check', () => {
        watch(path);
        runtimePlatform.assertPrivateFile(path);
      });
    }
    await step('desktop-cli-inspection', () => {
      const installed = runtimePlatform.inspectWindowsInstallation(environment.CODEX_EXECUTABLE);
      return {
        cliPathHash: pathHash(installed.executable),
        configuredCliMatchesDiscovered:
          environment.CODEX_EXECUTABLE !== undefined &&
          pathHash(resolve(environment.CODEX_EXECUTABLE)) === pathHash(installed.executable),
        desktopPid: installed.identity.pid,
        sameUser: installed.identity.sameUser,
        sameSession: installed.identity.sameSession,
      };
    });
    if (environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE) {
      const path = resolve(environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE);
      const metadata = await step('publisher-token-metadata', () => {
        watch(path);
        return context(adapter, path);
      });
      if (metadata.value?.file) {
        const s = lstatSync(path, { bigint: true });
        const identity =
          (s.dev & 0xffffffffn).toString(16).padStart(8, '0') +
          ':' +
          s.ino.toString(16).padStart(16, '0');
        metadata.value.file.nodeIdentityMatchesMetadataHandle =
          hash(identity) === metadata.value.file.identityHash;
        metadata.value.file.nodeRealpathMatchesMetadataHandle =
          pathHash(realpathSync.native(path)) === metadata.value.file.finalPathHash;
      }
      await step('publisher-token-native-check', () => runtimePlatform.assertPrivateFile(path));
      const owner = report.steps.at(-1)?.failure?.nativeOwner;
      if (owner) {
        const compare = (actual, expected) =>
          typeof actual === 'string' && typeof expected === 'string'
            ? actual === expected
            : 'unavailable';
        report.publisherOwnerBinding = {
          source: 'same-retained-handle-on-rejected-native-check',
          helperUserMatchesNode: compare(owner.helperUserHash, metadata.value?.userHash),
          ownerMatchesIndependentMetadata: compare(
            owner.fileOwnerHash,
            metadata.value?.file?.ownerHash,
          ),
          handleMatchesIndependentMetadata: compare(
            owner.handleIdentityHash,
            metadata.value?.file?.identityHash,
          ),
          finalPathMatchesIndependentMetadata: compare(
            owner.finalPathHash,
            metadata.value?.file?.finalPathHash,
          ),
          requestedPathMatchesConfiguration: owner.requestedPathHash === pathHash(path),
          transportPidMatches: owner.transportPidMatches ?? 'unavailable',
        };
      }
      await step('publisher-token-same-source-powershell-check', () => {
        const value = runPowerShell(adapter, 'private-state.ps1', [
          '-Action',
          'check',
          '-Path',
          path,
        ]);
        if (value !== 'PRIVATE_STATE_OK') throw failure('PREFLIGHT_INVALID_NATIVE_RESPONSE');
      });
    } else report.publisherToken = 'not-configured';
    report.passed = report.steps.every((v) => v.passed);
  } catch (error) {
    report.failure = startupFailure(error);
  } finally {
    report.backendClosed = true;
    for (const backend of new Set([runtimePlatform, platform].filter(Boolean))) {
      try {
        backend.closeWindowsPrivateStateBackend();
      } catch (error) {
        report.cleanupFailure ??= startupFailure(error);
        report.backendClosed = false;
        report.passed = false;
      }
    }
    report.targetsMetadataUnchanged = [...watched].every(([path, before]) => {
      try {
        return stamp(path) === before;
      } catch {
        return false;
      }
    });
    if (!report.targetsMetadataUnchanged) report.passed = false;
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    process.chdir(cwd);
    report.callerBootstrapUnchanged = beforeBootstrap === bootstrap();
    if (!report.callerBootstrapUnchanged) report.passed = false;
  }
  return report;
}
const contained = (parent, path) => {
  const value = relative(resolve(parent).toLowerCase(), resolve(path).toLowerCase());
  return value === '' || (!value.startsWith('..') && !isAbsolute(value));
};
export async function prepareWindowsStartupProbe({
  hostConfig,
  bundle,
  directory,
  workingDirectory,
}) {
  if (process.platform !== 'win32' || !isMainThread) throw failure('WINDOWS_REQUIRED');
  hostConfig = resolve(hostConfig);
  bundle = resolve(bundle);
  directory = resolve(directory);
  const configuration = readConfiguration(hostConfig),
    environment = validateWindowsHostEnvironment(configuration);
  const protectedDirectories = [
    dirname(hostConfig),
    environment.HOST_AGENT_STATE_DIR,
    environment.CODEX_DESKTOP_WINDOWS_CONFIG && dirname(environment.CODEX_DESKTOP_WINDOWS_CONFIG),
    environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE &&
      dirname(environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE),
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'CodexManage/native-helpers'),
  ].filter(Boolean);
  if (existsSync(directory) || protectedDirectories.some((path) => contained(path, directory)))
    throw failure('PREFLIGHT_PROBE_DIRECTORY_REJECTED');
  if (
    !workingDirectory ||
    !isAbsolute(workingDirectory) ||
    !lstatSync(workingDirectory).isDirectory()
  )
    throw failure('PREFLIGHT_INVALID_CWD');
  try {
    platform.makePrivateDirectorySync(directory);
    const reportPath = join(directory, 'startup-preflight-report.json');
    platform.writePrivateFileSync(reportPath, '{}', { flag: 'wx' });
    const value = {
      format: 'windows-host-startup-preflight-v1',
      nonce: randomUUID(),
      hostConfig,
      bundle,
      workingDirectory: resolve(workingDirectory),
      reportPath,
      reportStamp: stamp(reportPath),
    };
    const path = join(directory, 'windows-host.json');
    platform.writePrivateFileSync(path, JSON.stringify(value), { flag: 'wx' });
    const worker = join(repository, 'scripts/windows-desktop-worker.mjs');
    return {
      kind: 'windows-host-startup-probe-prepared',
      configuration: path,
      report: reportPath,
      nonce: value.nonce,
      launcherProtocol: {
        version: 1,
        arguments: [process.execPath, worker, path],
        nodeSource: 'actual-preparing-process',
        formalNodeIdentityMustBeVerified: true,
        worker,
        configuration: path,
      },
      scheduledTaskCreated: false,
      formalConfigurationWritten: false,
      formalHostStarted: false,
    };
  } finally {
    platform.closeWindowsPrivateStateBackend();
  }
}
export async function runWindowsStartupEnvelope(value, configurationPath) {
  try {
    const keys = [
      'format',
      'nonce',
      'hostConfig',
      'bundle',
      'workingDirectory',
      'reportPath',
      'reportStamp',
    ];
    if (
      !value ||
      Object.keys(value).some((key) => !keys.includes(key)) ||
      value.format !== 'windows-host-startup-preflight-v1' ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(
        value.nonce ?? '',
      ) ||
      !['hostConfig', 'bundle', 'workingDirectory', 'reportPath'].every(
        (key) => typeof value[key] === 'string' && isAbsolute(value[key]),
      )
    )
      throw failure('PREFLIGHT_INVALID_ENVELOPE');
    // The launcher supplies a private, dedicated configuration. A report target
    // cannot be redirected to an existing Host state or arbitrary private file.
    if (
      typeof configurationPath !== 'string' ||
      basename(configurationPath) !== 'windows-host.json' ||
      resolve(value.reportPath) !==
        join(dirname(resolve(configurationPath)), 'startup-preflight-report.json') ||
      lstatSync(value.reportPath).size !== 2
    )
      throw failure('PREFLIGHT_INVALID_ENVELOPE');
    const environment = validateWindowsHostEnvironment(readConfiguration(value.hostConfig));
    const protectedDirectories = [
      dirname(value.hostConfig),
      environment.HOST_AGENT_STATE_DIR,
      environment.CODEX_DESKTOP_WINDOWS_CONFIG && dirname(environment.CODEX_DESKTOP_WINDOWS_CONFIG),
      environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE &&
        dirname(environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE),
      process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'CodexManage/native-helpers'),
    ].filter(Boolean);
    if (protectedDirectories.some((path) => contained(path, dirname(value.reportPath))))
      throw failure('PREFLIGHT_PROBE_DIRECTORY_REJECTED');
    platform.assertPrivateFile(value.reportPath);
    if (stamp(value.reportPath) !== value.reportStamp) throw failure('PREFLIGHT_REPORT_CHANGED');
    const report = await runWindowsStartupPreflight(value);
    const json = JSON.stringify({
      ...report,
      nonce: value.nonce,
      launch: 'official-worker-readonly-envelope',
    });
    if (Buffer.byteLength(json) > 16 * 1024) throw failure('PREFLIGHT_RESPONSE_TOO_LARGE');
    platform.assertPrivateFile(value.reportPath);
    if (stamp(value.reportPath) !== value.reportStamp) throw failure('PREFLIGHT_REPORT_CHANGED');
    platform.writePrivateFileSync(value.reportPath, json);
    process.exitCode = report.passed ? 0 : 1;
  } finally {
    platform.closeWindowsPrivateStateBackend();
  }
}
export async function windowsStartupPreflightMain(args) {
  const prepare = args[0] === '--prepare-startup-probe';
  const names = prepare
    ? ['--prepare-startup-probe', '--bundle', '--probe-directory', '--formal-worker-directory']
    : ['--host-config', '--bundle'];
  if (
    args.length !== names.length * 2 ||
    !names.every((name, i) => args[i * 2] === name && args[i * 2 + 1])
  )
    throw failure('INVALID_PREFLIGHT_INPUT');
  try {
    const result = prepare
      ? await prepareWindowsStartupProbe({
          hostConfig: args[1],
          bundle: args[3],
          directory: args[5],
          workingDirectory: args[7],
        })
      : await runWindowsStartupPreflight({ hostConfig: args[1], bundle: args[3] });
    console.log(JSON.stringify(result));
    if (!prepare && !result.passed) process.exitCode = 1;
  } finally {
    platform.closeWindowsPrivateStateBackend();
  }
}
