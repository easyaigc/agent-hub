import { createHash } from 'node:crypto';
import { readFile, lstat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isMainThread } from 'node:worker_threads';
import { verifyHostRuntime } from './verify-host-runtime.mjs';
import { safeWorkerFailure } from './host-worker-supervisor.mjs';

// Fixed, fresh Main process; no Host, reader, permission mutation of the target,
// arbitrary module entry, environment rewrite or business operation is started.
const resources = [
  'dist/desktop-platform.js',
  'dist/desktop-private-state-native.js',
  'dist/desktop-private-state-native-worker.js',
  'dist/windows-process.js',
  'dist/diagnostic-events.js',
  'windows/private-state.ps1',
  'windows/private-state.cs',
  'windows/private-state-compilation.cs',
];
const hash = (value) => createHash('sha256').update(value).digest('hex');
const callerState = () =>
  JSON.stringify([process.argv, process.execArgv, process.env.NODE_OPTIONS]);
const beforeCaller = callerState();
const report = {
  kind: 'windows-private-state-preflight',
  node: process.versions.node,
  mainThread: isMainThread,
  passed: false,
  scope: 'Actual Host ESM closure and private ACL check only; no reader or update recovery proof',
};
let platform;
let diagnostics;
let before;
let file;
let help = false;
const stamp = (value) => [value.dev, value.ino, value.size, value.mtimeNs, value.ctimeNs].join(':');
const safeFailure = (error) => {
  const result = { code: 'PREFLIGHT_FAILED' };
  const enums = {
    code: [
      'WINDOWS_PRIVATE_STATE_UNAVAILABLE',
      'UNSAFE_WINDOWS_PRIVATE_STATE',
      'WINDOWS_REQUIRED',
      'INVALID_PREFLIGHT_INPUT',
      'RUNTIME_CLOSURE_MISMATCH',
      'PREFLIGHT_RESOURCE_UNAVAILABLE',
      'PREFLIGHT_TARGET_CHANGED',
      'PREFLIGHT_CALLER_CHANGED',
      ...(diagnostics?.codes ?? []),
    ],
    stage: ['startup', 'startup-deadline', 'response', 'response-deadline'],
    nativeStage: diagnostics?.stages ?? [],
    nativeFailure: diagnostics?.failures ?? [],
    nativeCode: diagnostics?.codes ?? [],
    helperStage: diagnostics?.helperStages ?? [],
    nativeType: diagnostics?.nativeTypes ?? [],
    nativeRejection: diagnostics?.rejections ?? [],
    nativeStep: diagnostics?.steps ?? [],
    nativeCleanupStep: diagnostics?.steps ?? [],
  };
  for (const [key, values] of Object.entries(enums))
    if (values.includes(error?.[key])) result[key] = error[key];
  for (const key of ['nativeStatus', 'nativeWin32', 'nativeCleanupWin32', 'waitMs'])
    if (typeof error?.[key] === 'number' && Number.isFinite(error[key])) result[key] = error[key];
  const owner = safeWorkerFailure(error).nativeOwner;
  if (owner) result.nativeOwner = owner;
  return result;
};
const fail = (code) => {
  throw Object.assign(new Error(code), { code });
};
if (['--host-config', '--prepare-startup-probe'].includes(process.argv[2])) {
  let startupFailure;
  try {
    const startup = await import('./windows-startup-preflight.mjs');
    startupFailure = startup.startupFailure;
    await startup.windowsStartupPreflightMain(process.argv.slice(2));
  } catch (error) {
    console.log(
      JSON.stringify({
        kind: 'windows-host-startup-readonly-preflight',
        passed: false,
        failure: startupFailure ? startupFailure(error) : safeFailure(error),
      }),
    );
    process.exitCode = 1;
  }
} else {
  try {
    const args = process.argv.slice(2);
    if (args.length === 1 && args[0] === '--help') {
      help = true;
      console.log(
        'node scripts/windows-private-state-preflight.mjs --file <existing-private-config> [--bundle <same-skill/bundle.json>]',
      );
    } else {
      if (
        ![2, 4].includes(args.length) ||
        args[0] !== '--file' ||
        !args[1] ||
        (args.length === 4 && (args[2] !== '--bundle' || !args[3]))
      )
        fail('INVALID_PREFLIGHT_INPUT');
      if (process.platform !== 'win32' || !isMainThread) fail('WINDOWS_REQUIRED');
      file = resolve(args[1]);
      const target = await lstat(file, { bigint: true });
      if (!target.isFile() || target.isSymbolicLink()) fail('INVALID_PREFLIGHT_INPUT');
      before = stamp(target);
      const repository = fileURLToPath(new URL('../', import.meta.url));
      const bundle = args[3]
        ? resolve(args[3])
        : fileURLToPath(new URL('../../../bundle.json', import.meta.url));
      const closure = await verifyHostRuntime({
        entry: join(repository, 'apps/host-agent/dist/runtime-entry.js'),
        bundle,
      });
      if (!closure.ok) fail('RUNTIME_CLOSURE_MISMATCH');
      report.runtimeFingerprints = Object.fromEntries(
        closure.packages.map((v) => [v.component, v.fingerprint]),
      );
      const adapter = closure.packages.find((v) => v.name === '@codex-manage/codex-adapter');
      report.resources = {};
      for (const resource of resources) {
        try {
          report.resources[resource] = hash(await readFile(join(adapter.root, resource)));
        } catch {
          report.missingResource = resource;
          fail('PREFLIGHT_RESOURCE_UNAVAILABLE');
        }
      }
      diagnostics = (
        await import(pathToFileURL(join(adapter.root, 'dist/desktop-private-state-native.js')).href)
      ).privateStateNativeDiagnostics;
      platform = await import(pathToFileURL(join(adapter.root, 'dist/desktop-platform.js')).href);
      platform.assertPrivateFile(file);
      report.passed = true;
    }
  } catch (error) {
    report.failure = safeFailure(error);
  } finally {
    if (platform) {
      try {
        platform.closeWindowsPrivateStateBackend();
        report.backendClosed = true;
      } catch (error) {
        report.cleanupFailure = safeFailure(error);
        report.passed = false;
      }
    }
    if (before) {
      try {
        report.targetMetadataUnchanged = before === stamp(await lstat(file, { bigint: true }));
      } catch {
        report.targetMetadataUnchanged = false;
      }
      if (!report.targetMetadataUnchanged) {
        report.failure = { code: 'PREFLIGHT_TARGET_CHANGED' };
        report.passed = false;
      }
    }
    report.callerBootstrapUnchanged = beforeCaller === callerState();
    if (!report.callerBootstrapUnchanged) {
      report.failure = { code: 'PREFLIGHT_CALLER_CHANGED' };
      report.passed = false;
    }
  }
  if (!help) {
    console.log(JSON.stringify(report));
    if (!report.passed) process.exitCode = 1;
  }
}
