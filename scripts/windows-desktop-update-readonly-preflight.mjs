import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { verifyHostRuntime } from './verify-host-runtime.mjs';

// Normal Main entry. No HTTP listener, Host deployment, original desktop kill,
// permission changes, native argv override or model/creation/reply operation.
const args = process.argv.slice(2);
const callerState = () =>
  JSON.stringify([process.argv, process.execArgv, process.env.NODE_OPTIONS, process.cwd()]);
const callerBefore = callerState();
const report = {
  kind: 'windows-desktop-update-readonly-preflight',
  passed: false,
  scope:
    'Real current desktop/CLI plus owned auxiliary reader exit; not an actual installation update or deployment',
};
let session, view, platform, directory, targetBefore, file;
const children = new Set();
const stamp = (path) => {
  const s = lstatSync(path, { bigint: true });
  return [
    s.dev,
    s.ino,
    s.size,
    s.mtimeNs,
    s.ctimeNs,
    createHash('sha256').update(readFileSync(path)).digest('hex'),
  ].join(':');
};
const track = (child) => {
  if (child) children.add(child);
  return child;
};
const fixedIdentity = (v) =>
  JSON.stringify([v.pid, v.startedAtUnixMs, v.executableSha256, v.sameUser, v.sameSession]);
const until = async (predicate, milliseconds) => {
  const end = Date.now() + milliseconds;
  while (!predicate()) {
    if (Date.now() >= end)
      throw Object.assign(new Error('Read-only probe deadline'), {
        code: 'READONLY_PROBE_DEADLINE',
      });
    await delay(100);
  }
};
const bounded = async (work, milliseconds) => {
  let timer;
  try {
    return await Promise.race([
      work,
      new Promise((_, reject) => {
        timer = setTimeout(
          () =>
            reject(
              Object.assign(new Error('Read-only probe deadline'), {
                code: 'READONLY_PROBE_DEADLINE',
              }),
            ),
          milliseconds,
        );
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
};
try {
  if (args.length === 1 && args[0] === '--help') {
    console.log(
      'node scripts/windows-desktop-update-readonly-preflight.mjs --file <existing-windows-desktop.json> --missing-cli <absent-old-codex.exe> --bundle <same-bundle.json>',
    );
  } else {
    if (
      args.length !== 6 ||
      args[0] !== '--file' ||
      args[2] !== '--missing-cli' ||
      args[4] !== '--bundle'
    )
      throw Object.assign(new Error('Invalid read-only probe input'), {
        code: 'INVALID_READONLY_PROBE_INPUT',
      });
    if (process.platform !== 'win32')
      throw Object.assign(new Error('Windows required'), { code: 'WINDOWS_REQUIRED' });
    file = resolve(args[1]);
    const missingCli = resolve(args[3]);
    assert.equal(
      existsSync(missingCli),
      false,
      'Use the original absent CLI path; never remove a CLI for acceptance',
    );
    const repository = fileURLToPath(new URL('../', import.meta.url));
    const closure = await verifyHostRuntime({
      entry: join(repository, 'apps/host-agent/dist/runtime-entry.js'),
      bundle: resolve(args[5]),
    });
    assert.equal(closure.ok, true, 'Actual Host ESM closure must match this same bundle');
    report.runtimeFingerprints = Object.fromEntries(
      closure.packages.map((v) => [v.component, v.fingerprint]),
    );
    const adapter = closure.packages.find((v) => v.name === '@codex-manage/codex-adapter');
    const load = (name) => import(pathToFileURL(join(adapter.root, 'dist', name)).href);
    platform = await load('desktop-platform.js');
    const { RuntimeSession } = await load('runtime-session.js');
    const { DesktopWorkspaceView } = await load('desktop-workspace-view.js');
    platform.assertPrivateFile(file);
    targetBefore = stamp(file);
    const originalIdentity = fixedIdentity(platform.inspectWindowsPipe());
    directory = join(tmpdir(), 'codex-update-readonly-' + randomUUID());
    assert.equal(existsSync(directory), false, 'Do not precreate/protect the session root');
    const options = {
      mode: 'desktop-runtime',
      directory,
      root: directory,
      name: 'Readonly recovery acceptance',
      executable: missingCli,
      windowsDesktopConfig: file,
    };
    session = new RuntimeSession(options);
    await bounded(session.connect(), 90_000);
    const primary = track(session.runtime.reader.process);
    const ipc = session.runtime.ipc;
    const socket = ipc.socket;
    assert(primary && primary.exitCode === null && session.ready);
    assert(socket && !socket.destroyed);
    assert.notEqual(
      options.executable,
      missingCli,
      'Shared trusted discovery must resolve the absent startup hint',
    );
    assert.equal(existsSync(options.executable), true);
    view = new DesktopWorkspaceView(
      session,
      options.name,
      missingCli,
      join(process.env.CODEX_HOME ?? join(homedir(), '.codex'), '.codex-global-state.json'),
    );
    const first = await bounded(view.workspace(), 90_000);
    const auxiliary = track(view.reader.process);
    assert(
      auxiliary &&
        auxiliary !== primary &&
        auxiliary.pid !== primary.pid &&
        auxiliary.exitCode === null,
    );
    assert.deepEqual(auxiliary.spawnargs.slice(1), ['app-server', '--stdio']);
    const closed = view.reader.processClosed;
    assert.equal(auxiliary.kill('SIGTERM'), true, 'Only this probe-owned auxiliary is terminated');
    await bounded(closed, 15_000);
    assert(auxiliary.exitCode !== null || auxiliary.signalCode !== null);
    // Observe status only. The product background timer must recover without
    // another catalog request, manual reconnect, Host restart or desktop reopen.
    await until(() => view.status.state === 'ready' && view.reader.process !== auxiliary, 45_000);
    const replacement = track(view.reader.process);
    assert(replacement && replacement !== primary && replacement.exitCode === null);
    assert.strictEqual(session.runtime.reader.process, primary);
    assert.strictEqual(session.runtime.ipc, ipc);
    assert.strictEqual(ipc.socket, socket);
    assert(!socket.destroyed && session.ready && primary.exitCode === null);
    assert.equal(fixedIdentity(platform.inspectWindowsPipe()), originalIdentity);
    const second = await bounded(view.workspace(), 90_000);
    const ids = (v) =>
      JSON.stringify([v.projects.map((p) => p.id).sort(), v.threads.map((t) => t.id).sort()]);
    report.catalogIdentityUnchanged = ids(first) === ids(second);
    report.absentStartupCliRediscovered = true;
    report.auxiliaryExitConfirmed = true;
    report.backgroundRecoveryWithoutWorkspaceCall = true;
    report.primaryReaderIpcAndDesktopUnchanged = true;
    report.firstSessionRootInitiallyAbsent = true;
    report.passed = true;
  }
} catch (error) {
  const code = error?.code;
  report.failure = {
    code:
      typeof code === 'string' &&
      /^(?:CODEX_READER_|WINDOWS_|READONLY_PROBE_|INVALID_READONLY_PROBE_)[A-Z0-9_]+$/.test(code)
        ? code
        : 'READONLY_PROBE_FAILED',
  };
} finally {
  if (view) {
    track(view.reader.process);
    try {
      view.close();
    } catch {
      report.passed = false;
      report.closeFailed = true;
    }
    await bounded(view.reader.processClosed, 15_000).catch(() => {
      report.passed = false;
      report.closeFailed = true;
    });
  }
  if (session) {
    track(session.runtime.reader.process);
    try {
      session.close();
    } catch {
      report.passed = false;
      report.closeFailed = true;
    }
    await bounded(session.runtime.reader.processClosed, 15_000).catch(() => {
      report.passed = false;
      report.closeFailed = true;
    });
  }
  if (platform) {
    try {
      platform.closeWindowsPrivateStateBackend();
    } catch {
      report.passed = false;
      report.closeFailed = true;
    }
  }
  report.callerBootstrapUnchanged = callerState() === callerBefore;
  if (!report.callerBootstrapUnchanged) report.passed = false;
  report.ownedReaderExitConfirmed = [...children].every(
    (c) => c.exitCode !== null || c.signalCode !== null,
  );
  if (!report.ownedReaderExitConfirmed) report.passed = false;
  if (file && targetBefore !== undefined) {
    try {
      report.targetMetadataAndBytesUnchanged = stamp(file) === targetBefore;
    } catch {
      report.targetMetadataAndBytesUnchanged = false;
    }
    if (!report.targetMetadataAndBytesUnchanged) report.passed = false;
  }
  if (directory && report.ownedReaderExitConfirmed && !report.closeFailed) {
    rmSync(directory, { recursive: true, force: true });
    report.ownedStateRemoved = !existsSync(directory);
  }
}
if (!(args.length === 1 && args[0] === '--help')) {
  console.log(JSON.stringify(report));
  if (!report.passed) process.exitCode = 1;
}
