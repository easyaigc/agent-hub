import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isMainThread } from 'node:worker_threads';
import { verifyHostRuntime } from './verify-host-runtime.mjs';
import { safeWorkerFailure } from './host-worker-supervisor.mjs';

// Actual modules, synthetic exclusive state only. No Host/configuration/credentials,
// IPC, model input, relay, network listener or existing notification state is opened.
const repository = fileURLToPath(new URL('../', import.meta.url));
const report = {
  kind: 'windows-notification-state-preflight',
  passed: false,
  mainThread: isMainThread,
  formalHostStarted: false,
  existingStateRead: false,
  networkStarted: false,
};
let platform, publisher, directory, identity;
try {
  if (process.platform !== 'win32' || !isMainThread)
    throw Object.assign(new Error(), { code: 'WINDOWS_REQUIRED' });
  if (
    process.argv.length !== 6 ||
    process.argv[2] !== '--bundle' ||
    process.argv[4] !== '--directory'
  )
    throw Object.assign(new Error(), { code: 'INVALID_PREFLIGHT_INPUT' });
  const closure = await verifyHostRuntime({
    entry: join(repository, 'apps/host-agent/dist/runtime-entry.js'),
    bundle: resolve(process.argv[3]),
  });
  if (!closure.ok) throw Object.assign(new Error(), { code: 'RUNTIME_CLOSURE_MISMATCH' });
  report.runtimeFingerprints = Object.fromEntries(
    closure.packages.map((v) => [v.component, v.fingerprint]),
  );
  const adapter = closure.packages.find((v) => v.name === '@codex-manage/codex-adapter');
  const notification = closure.packages.find(
    (v) => v.name === '@codex-manage/notification-runtime',
  );
  if (!adapter || !notification)
    throw Object.assign(new Error(), { code: 'RUNTIME_CLOSURE_MISMATCH' });
  platform = await import(pathToFileURL(join(adapter.root, 'dist/desktop-platform.js')).href);
  const { NotificationPublisher } = await import(
    pathToFileURL(join(notification.root, 'dist/index.js')).href
  );
  directory = resolve(process.argv[5]);
  if (existsSync(directory))
    throw Object.assign(new Error(), { code: 'PREFLIGHT_DIRECTORY_EXISTS' });
  platform.makePrivateDirectorySync(directory);
  identity = lstatSync(directory, { bigint: true });
  const statePath = join(directory, 'notifications.json');
  const options = {
    statePath,
    target: {
      agentId: 'codex',
      hostId: 'notification-fixture',
      workspaceId: 'notification-fixture',
      runtimeId: 'desktop-runtime',
    },
    makePrivateDirectory: platform.makePrivateDirectorySync,
    createPrivateFile: (path) => platform.openPrivateFileSync(path, 'wx'),
    protectFile: platform.protectWindowsStateFile,
  };
  publisher = new NotificationPublisher(options);
  platform.assertPrivateFile(statePath);
  platform.assertPrivateFile(statePath + '.lock');
  const previous = readFileSync(statePath);
  publisher.close();
  publisher = undefined;
  assert(!existsSync(statePath + '.lock'));
  publisher = new NotificationPublisher(options);
  assert.deepEqual(readFileSync(statePath), previous);
  publisher.close();
  publisher = undefined;
  assert(!existsSync(statePath + '.lock'));
  report.freshLockAndSnapshotPrivate = true;
  report.reopenPreservesFixtureState = true;
  report.lockReleased = true;
  // Reproduce default-owner creation only inside our own synthetic directory.
  // No owner/ACL is set or changed. A User-owner token may legitimately accept.
  const defaultPath = join(directory, 'default-created.json');
  writeFileSync(defaultPath, 'fixture-only', { flag: 'wx', mode: 0o600 });
  try {
    platform.assertPrivateFile(defaultPath);
    report.defaultCreatedCheck = 'accepted-current-user';
    report.ownerRefusalEvidence = 'not-exercised';
  } catch (error) {
    report.defaultCreatedFailure = safeWorkerFailure(error);
    if (
      error?.code !== 'UNSAFE_WINDOWS_PRIVATE_STATE' ||
      error?.nativeRejection !== 'WRONG_FILE_OWNER'
    )
      throw error;
    const owner = error.nativeOwner;
    assert.equal(owner?.version, 2);
    assert.equal(owner.checkedAction, 'check');
    assert.equal(owner.targetKind, 'file');
    assert.equal(owner.transportPidMatches, true);
    assert.match(owner.helperUserHash ?? '', /^[a-f0-9]{64}$/);
    assert.match(owner.fileOwnerHash ?? '', /^[a-f0-9]{64}$/);
    assert.notEqual(owner.fileOwnerHash, owner.helperUserHash);
    const stat = lstatSync(defaultPath, { bigint: true });
    const key =
      (stat.dev & 0xffffffffn).toString(16).padStart(8, '0') +
      ':' +
      stat.ino.toString(16).padStart(16, '0');
    assert.equal(owner.handleIdentityHash, createHash('sha256').update(key).digest('hex'));
    report.defaultCreatedCheck = 'wrong-owner-rejected';
    report.ownerRefusalEvidence = 'same-handle-and-actual-child-validated';
  }
  report.passed = true;
} catch (error) {
  report.failure = safeWorkerFailure(error);
  process.exitCode = 1;
} finally {
  report.backendClosed = true;
  for (const close of [
    () => publisher?.close(),
    () => platform?.closeWindowsPrivateStateBackend(),
  ]) {
    try {
      close();
    } catch (error) {
      report.cleanupFailure = safeWorkerFailure(error);
      report.backendClosed = false;
      report.passed = false;
      process.exitCode = 1;
    }
  }
  try {
    if (identity) {
      const current = lstatSync(directory, { bigint: true });
      if (
        current.dev === identity.dev &&
        current.ino === identity.ino &&
        !current.isSymbolicLink() &&
        readdirSync(directory).every((n) =>
          ['notifications.json', 'notifications.json.lock', 'default-created.json'].includes(n),
        )
      ) {
        rmSync(directory, { recursive: true });
        report.fixtureRemoved = true;
      } else {
        report.fixtureRemoved = false;
        report.passed = false;
        process.exitCode = 1;
      }
    }
  } catch (error) {
    report.cleanupFailure = safeWorkerFailure(error);
    report.fixtureRemoved = false;
    report.passed = false;
    process.exitCode = 1;
  }
}
console.log(JSON.stringify(report));
