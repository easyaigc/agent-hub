import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { lstatSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isMainThread } from 'node:worker_threads';
import { verifyHostRuntime } from './verify-host-runtime.mjs';

// Only our fresh synthetic file is changed. No Host/config/token is accepted as
// input, no owner is changed and no unsafe result is retried as a success.
const report = {
  kind: 'windows-private-state-rejection-preflight',
  scope: 'Synthetic shared-allow refusal and restored-file acceptance only',
  passed: false,
};
const caller = () => JSON.stringify([process.argv, process.execArgv, process.env.NODE_OPTIONS]);
const beforeCaller = caller();
let platform, diagnostics, root, identity;
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
const stamp = (path) => {
  const s = lstatSync(path, { bigint: true });
  assert(!s.isSymbolicLink());
  return [s.dev, s.ino].join(':');
};
try {
  assert.equal(process.platform, 'win32', 'WINDOWS_REQUIRED');
  assert(isMainThread, 'WINDOWS_REQUIRED');
  assert(process.argv.length === 4 && process.argv[2] === '--bundle', 'INVALID_PREFLIGHT_INPUT');
  const repository = fileURLToPath(new URL('../', import.meta.url));
  const closure = await verifyHostRuntime({
    entry: join(repository, 'apps/host-agent/dist/runtime-entry.js'),
    bundle: resolve(process.argv[3]),
  });
  assert(closure.ok, 'RUNTIME_CLOSURE_MISMATCH');
  report.runtimeFingerprints = Object.fromEntries(
    closure.packages.map((v) => [v.component, v.fingerprint]),
  );
  const adapter = closure.packages.find((v) => v.name === '@codex-manage/codex-adapter');
  report.resources = Object.fromEntries(
    resources.map((name) => [
      name,
      createHash('sha256')
        .update(readFileSync(join(adapter.root, name)))
        .digest('hex'),
    ]),
  );
  diagnostics = (
    await import(pathToFileURL(join(adapter.root, 'dist/desktop-private-state-native.js')).href)
  ).privateStateNativeDiagnostics;
  platform = await import(pathToFileURL(join(adapter.root, 'dist/desktop-platform.js')).href);
  const gate = await import(pathToFileURL(join(adapter.root, 'dist/windows-process.js')).href);
  root = join(process.cwd(), 'windows-private-state-rejection-' + randomUUID());
  assert(!existsSync(root), 'FIXTURE_ALREADY_EXISTS');
  platform.makePrivateDirectorySync(root);
  identity = stamp(root);
  const file = join(root, 'synthetic.json');
  platform.writePrivateFileSync(file, '{}', { flag: 'wx' });
  const fileIdentity = stamp(file);
  const same = () => {
    assert.equal(stamp(root), identity);
    assert.equal(stamp(file), fileIdentity);
  };
  const reject = (reason, step) => {
    same();
    assert.throws(
      () => platform.assertPrivateFile(file),
      (error) => {
        report.checks ??= [];
        const value = { expectedRejection: reason };
        for (const [key, values] of Object.entries({
          code: ['WINDOWS_PRIVATE_STATE_UNAVAILABLE', 'UNSAFE_WINDOWS_PRIVATE_STATE'],
          nativeRejection: diagnostics.rejections,
          nativeType: diagnostics.nativeTypes,
          nativeStep: diagnostics.steps,
        }))
          if (values.includes(error?.[key])) value[key] = error[key];
        report.checks.push(value);
        return (
          error.code === 'UNSAFE_WINDOWS_PRIVATE_STATE' &&
          error.nativeType === 'UnauthorizedAccessException' &&
          error.nativeRejection === reason &&
          error.nativeStep === step
        );
      },
    );
  };
  platform.assertPrivateFile(file);
  gate.withWindowsProcessCreation(5000, (timeout) =>
    execFileSync(
      join(process.env.SystemRoot, 'System32/icacls.exe'),
      [file, '/grant', '*S-1-1-0:(R)'],
      { timeout, stdio: 'pipe' },
    ),
  );
  reject('SHARED_PRIVATE_FILE', 'dacl-allow-subject-check');
  platform.protectWindowsStateFile(file);
  platform.assertPrivateFile(file);
  same();
  // check validates owner and allowed DACL subjects. Protection is verified
  // after protect/protect-file writes, not an independent check requirement.
  report.sharedAclRejected = report.restoredPrivateFileAccepted = true;
  report.passed = true;
} catch (error) {
  report.failure = {};
  for (const [key, values] of Object.entries({
    code: ['WINDOWS_PRIVATE_STATE_UNAVAILABLE', 'UNSAFE_WINDOWS_PRIVATE_STATE'],
    nativeRejection: diagnostics?.rejections ?? [],
    nativeType: diagnostics?.nativeTypes ?? [],
    nativeStep: diagnostics?.steps ?? [],
    nativeStage: diagnostics?.stages ?? [],
    nativeFailure: diagnostics?.failures ?? [],
    nativeCode: diagnostics?.codes ?? [],
  }))
    if (values.includes(error?.[key])) report.failure[key] = error[key];
  if (!Object.keys(report.failure).length) report.failure.code = 'PREFLIGHT_FAILED';
  report.passed = false;
} finally {
  if (platform) {
    try {
      platform.closeWindowsPrivateStateBackend();
      report.backendClosed = true;
    } catch {
      report.backendClosed = false;
      report.passed = false;
    }
  }
  if (identity && report.backendClosed) {
    try {
      assert.equal(stamp(root), identity);
      rmSync(root, { recursive: true });
      report.fixtureRemoved = !existsSync(root);
    } catch {
      report.fixtureRemoved = false;
      report.passed = false;
    }
  } else if (identity) report.fixtureRemoved = false;
  report.callerBootstrapUnchanged = caller() === beforeCaller;
  if (!report.callerBootstrapUnchanged) report.passed = false;
}
console.log(JSON.stringify(report));
if (!report.passed) process.exitCode = 1;
