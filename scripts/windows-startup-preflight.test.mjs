import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import fs from 'node:fs';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mock, test } from 'node:test';
import { safeWorkerFailure } from './host-worker-supervisor.mjs';
import { validateWindowsHostEnvironment } from './windows-host-environment.mjs';

test('rejection CLI succeeds with shared-allow refusal without inventing a protection-bit check', async () => {
  // Synthetic OS/Native boundaries only. The real check contract accepts a
  // current-owner DACL containing only trusted subjects, including inheritance.
  // This executes the actual CLI; it is not Windows ACL or compilation proof.
  const root = mkdtempSync(join(tmpdir(), 'rejection-contract-'));
  const adapter = join(root, 'adapter');
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
  mkdirSync(join(adapter, 'dist'), { recursive: true });
  mkdirSync(join(adapter, 'windows'));
  for (const name of resources) writeFileSync(join(adapter, name), 'export {};');
  const platformURL = pathToFileURL(join(adapter, 'dist/desktop-platform.js')).href;
  const nativeURL = pathToFileURL(join(adapter, 'dist/desktop-private-state-native.js')).href;
  const gateURL = pathToFileURL(join(adapter, 'dist/windows-process.js')).href;
  const bundle = join(root, 'bundle.json');
  const argv = process.argv,
    cwd = process.cwd(),
    exitCode = process.exitCode;
  const systemRoot = process.env.SystemRoot;
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
  const replies = [],
    commands = [],
    mocks = [];
  let shared = false,
    closed = false;
  try {
    mocks.push(
      mock.module('./verify-host-runtime.mjs', {
        namedExports: {
          async verifyHostRuntime(value) {
            assert.equal(value.bundle, bundle);
            return {
              ok: true,
              packages: [
                {
                  name: '@codex-manage/codex-adapter',
                  component: 'packages/codex-adapter',
                  root: adapter,
                  fingerprint: 'synthetic',
                },
              ],
            };
          },
        },
      }),
    );
    mocks.push(
      mock.module(platformURL, {
        namedExports: {
          makePrivateDirectorySync(path) {
            mkdirSync(path, { mode: 0o700 });
          },
          writePrivateFileSync(path, value, options) {
            writeFileSync(path, value, options);
          },
          assertPrivateFile() {
            if (shared)
              throw Object.assign(new Error('synthetic shared allow'), {
                code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
                nativeType: 'UnauthorizedAccessException',
                nativeRejection: 'SHARED_PRIVATE_FILE',
                nativeStep: 'dacl-allow-subject-check',
              });
            // No independent protection-bit refusal in the existing check policy.
          },
          protectWindowsStateFile() {
            shared = false;
          },
          closeWindowsPrivateStateBackend() {
            closed = true;
          },
          windowsPowerShellEnvironment() {
            return {};
          },
        },
      }),
    );
    mocks.push(
      mock.module(nativeURL, {
        namedExports: {
          privateStateNativeDiagnostics: {
            rejections: ['SHARED_PRIVATE_FILE', 'UNPROTECTED_PRIVATE_DACL'],
            nativeTypes: ['UnauthorizedAccessException'],
            steps: ['dacl-allow-subject-check', 'dacl-protection-check'],
            stages: [],
            failures: [],
            codes: [],
          },
        },
      }),
    );
    mocks.push(
      mock.module(gateURL, {
        namedExports: {
          withWindowsProcessCreation(budget, create) {
            assert.equal(budget, 5000);
            return create(budget);
          },
        },
      }),
    );
    const exec = mock.method(childProcess, 'execFileSync', (file, args, options) => {
      assert.equal(options.timeout, 5000);
      if (file.endsWith('icacls.exe')) {
        assert.deepEqual(args.slice(1), ['/grant', '*S-1-1-0:(R)']);
        shared = true;
      } else {
        // Model the old fixture's unprotection as trusted inherited subjects.
        // It remains accepted by check, so the removed assert.throws fails.
        assert(file.endsWith('powershell.exe'));
        assert(args.join(' ').includes('SetAccessRuleProtection($false,$true)'));
      }
      commands.push(file);
      return '';
    });
    mocks.push({ restore: () => exec.mock.restore() });
    const log = mock.method(console, 'log', (value) => replies.push(JSON.parse(value)));
    mocks.push({ restore: () => log.mock.restore() });
    syncBuiltinESMExports();
    Object.defineProperty(process, 'platform', { value: 'win32' });
    process.env.SystemRoot = 'C:/synthetic-windows';
    process.chdir(root);
    process.argv = [process.execPath, 'synthetic-entry', '--bundle', bundle];
    await import('./windows-private-state-rejection-preflight.mjs?contract-regression');
    assert.equal(replies.length, 1);
    const report = replies[0];
    assert.equal(report.passed, true);
    assert.equal(report.sharedAclRejected, true);
    assert.equal(report.restoredPrivateFileAccepted, true);
    assert.equal(report.unprotectedAclRejected, undefined);
    assert.equal(report.backendClosed, true);
    assert.equal(report.fixtureRemoved, true);
    assert.equal(report.callerBootstrapUnchanged, true);
    assert.equal(report.checks.length, 1);
    assert.equal(report.checks[0].nativeRejection, 'SHARED_PRIVATE_FILE');
    assert.equal(commands.length, 1);
    assert(closed);
  } finally {
    process.argv = argv;
    process.chdir(cwd);
    process.exitCode = exitCode;
    if (systemRoot === undefined) delete process.env.SystemRoot;
    else process.env.SystemRoot = systemRoot;
    Object.defineProperty(process, 'platform', descriptor);
    for (const m of mocks.reverse()) m.restore();
    syncBuiltinESMExports();
    rmSync(root, { recursive: true });
  }
});

test(
  'real normal Main CLI keeps original file mode and rejects unsupported platform safely',
  { skip: process.platform === 'win32' },
  () => {
    const entry = fileURLToPath(new URL('./windows-private-state-preflight.mjs', import.meta.url));
    for (const mode of ['--file', '--host-config', '--prepare-startup-probe']) {
      const args = [entry, mode, 'unopened-private-path', '--bundle', 'unopened-bundle'];
      if (mode === '--prepare-startup-probe')
        args.push(
          '--probe-directory',
          'unopened-probe',
          '--formal-worker-directory',
          'unopened-worker',
        );
      const child = childProcess.spawnSync(process.execPath, args, {
        encoding: 'utf8',
        timeout: 10000,
      });
      assert.equal(child.status, 1);
      const report = JSON.parse(child.stdout);
      assert.equal(report.passed, false);
      assert.equal(report.failure.code, 'WINDOWS_REQUIRED');
      assert(!child.stdout.includes('unopened-private-path'));
    }
  },
);

test(
  'normal Main rejection fixture refuses non-Windows before creating files',
  { skip: process.platform === 'win32' },
  () => {
    const entry = fileURLToPath(
      new URL('./windows-private-state-rejection-preflight.mjs', import.meta.url),
    );
    const result = childProcess.spawnSync(
      process.execPath,
      [entry, '--bundle', 'unopened-bundle'],
      { encoding: 'utf8' },
    );
    assert.equal(result.status, 1);
    const report = JSON.parse(result.stdout.trim());
    assert.equal(report.passed, false);
    assert.equal(report.callerBootstrapUnchanged, true);
    assert.equal(report.fixtureRemoved, undefined);
    assert(!result.stdout.includes('unopened-bundle'));
  },
);

test('owner diagnostics project only typed hashes and retain the policy rejection', () => {
  const nativeOwner = {
    version: 1,
    checkedAction: 'check',
    helperPid: 123,
    helperStartedFileTime: '179340245100000000',
    targetKind: 'file',
    helperUserHash: '1'.repeat(64),
    fileOwnerHash: '2'.repeat(64),
    requestedPathHash: '3'.repeat(64),
    handleIdentityHash: '4'.repeat(64),
    finalPathHash: '5'.repeat(64),
    transportPidMatches: true,
    privatePath: 'NEVER_LOG_OWNER_PATH',
    privateSid: 'NEVER_LOG_OWNER_SID',
  };
  const error = {
    code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
    nativeType: 'UnauthorizedAccessException',
    nativeRejection: 'WRONG_FILE_OWNER',
    nativeStep: 'owner-check',
    nativeOwner,
  };
  const value = safeWorkerFailure(error);
  assert.equal(value.nativeRejection, 'WRONG_FILE_OWNER');
  assert.equal(value.nativeStep, 'owner-check');
  assert.equal(value.nativeOwner.helperPid, 123);
  assert.equal(value.nativeOwner.transportPidMatches, true);
  assert(!JSON.stringify(value).includes('NEVER_LOG'));
  assert.equal(
    safeWorkerFailure({ ...error, nativeOwner: { ...nativeOwner, helperUserHash: 'raw SID' } })
      .nativeOwner,
    undefined,
  );
  assert.equal(safeWorkerFailure({ ...error, nativeType: 'IOException' }).nativeOwner, undefined);
});

test('worker failures retain bounded cause/status including zero and Windows DWORDs', () => {
  for (const reason of [
    'WRONG_FILE_OWNER',
    'NULL_PRIVATE_DACL',
    'UNKNOWN_PRIVATE_ACE',
    'SHARED_PRIVATE_FILE',
    'UNPROTECTED_PRIVATE_DACL',
  ]) {
    const value = safeWorkerFailure({
      code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
      nativeRejection: reason,
    });
    assert.equal(value.nativeRejection, reason);
  }
  assert.equal(safeWorkerFailure({ nativeRejection: 'private path' }).nativeRejection, undefined);
  const fileError = { code: 'EACCES', syscall: 'rename' };
  assert.equal(safeWorkerFailure(fileError).helperCauseCode, undefined);
  assert.equal(safeWorkerFailure({ cause: fileError }).helperCauseCode, undefined);
  const spawnError = {
    code: 'EACCES',
    syscall: 'spawnSync C:\\private\\powershell.exe',
    status: null,
  };
  assert.equal(safeWorkerFailure(spawnError).helperCauseCode, 'EACCES');
  assert(!JSON.stringify(safeWorkerFailure(spawnError)).includes('private'));
  for (const status of [0, 3221225781, 3221226505, 0xffffffff]) {
    const error = Object.assign(new Error('private body'), {
      code: 'WINDOWS_HELPER_FAILED',
      nativeType: 'UnauthorizedAccessException',
      cause: {
        code: 'ETIMEDOUT',
        status,
        signal: 'SIGTERM',
        killed: true,
        stderr: 'secret target path and token body\nWINDOWS_CLI_REJECTED\n',
      },
    });
    const value = safeWorkerFailure(error);
    assert.equal(value.helperStatus, status);
    assert.equal(value.helperCauseCode, 'ETIMEDOUT');
    assert.equal(value.helperSignal, 'SIGTERM');
    assert.equal(value.helperStderrMarker, 'WINDOWS_CLI_REJECTED');
    assert(!JSON.stringify(value).includes('secret'));
    assert(!JSON.stringify(value).includes('private body'));
  }
  for (const status of [undefined, null, -1, 0x100000000, NaN])
    assert.equal(safeWorkerFailure({ cause: { status } }).helperStatus, undefined);
  const cycle = { code: 'not-an-approved-helper-code', stderr: 'private text' };
  cycle.cause = cycle;
  const value = safeWorkerFailure(cycle);
  assert.equal(value.helperCauseCode, undefined);
  assert.equal(value.helperStderrMarker, 'unclassified');
  assert(!JSON.stringify(value).includes('private text'));
});

test('shared environment validator retains strict production inputs', () => {
  const environment = {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG: 'private config',
    HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: 'private token path',
    AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS: '1',
  };
  assert.equal(validateWindowsHostEnvironment({ environment }), environment);
  for (const extra of [
    { NODE_OPTIONS: '--inspect' },
    { PATH: 'custom' },
    { AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS: '2' },
    { HOST_AGENT_PORT: 8794 },
  ])
    assert.throws(() =>
      validateWindowsHostEnvironment({ environment: { ...environment, ...extra } }),
    );
  assert.throws(() =>
    validateWindowsHostEnvironment({ environment: { CODEX_RUNTIME_MODE: 'other' } }),
  );
});

test('readonly orchestration preserves source metadata and refuses a Native failure even when comparison passes', async () => {
  // Synthetic OS/Native boundaries only. This does not count as Windows ACL,
  // scheduled-context, CLI inspection or formal Host startup acceptance.
  const root = mkdtempSync(join(tmpdir(), 'startup-preflight-'));
  const configDir = join(root, 'formal-state');
  const probeDir = join(root, 'own-probe');
  mkdirSync(configDir);
  mkdirSync(probeDir);
  const hostConfig = join(configDir, 'windows-host.json');
  const desktopConfig = join(configDir, 'windows-desktop.json');
  const token = join(configDir, 'notification-publisher-token');
  const reportPath = join(probeDir, 'startup-preflight-report.json');
  const environment = {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_WINDOWS_CONFIG: desktopConfig,
    CODEX_EXECUTABLE: join(root, 'codex.exe'),
    HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: token,
  };
  writeFileSync(hostConfig, JSON.stringify({ environment }));
  writeFileSync(desktopConfig, '{}');
  writeFileSync(token, 'PRIVATE_TOKEN_MUST_NOT_BE_READ');
  writeFileSync(reportPath, '{}');
  const stamp = (path) => {
    const s = fs.lstatSync(path, { bigint: true });
    return [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs, s.birthtimeNs].join(':');
  };
  const before = [hostConfig, desktopConfig, token].map(stamp);
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
  const savedEnv = { ...process.env };
  const savedCwd = process.cwd();
  const savedExitCode = process.exitCode;
  const actualReadFileSync = fs.readFileSync;
  const psCalls = [],
    privateChecks = [],
    runtimePrivateChecks = [],
    writes = [];
  let nativeRejectsToken = true,
    closed = 0,
    runtimeClosed = 0,
    cliInspections = 0;
  const adapter = join(root, 'actual-runtime-adapter');
  mkdirSync(join(adapter, 'dist'), { recursive: true });
  const runtimeEntry = join(adapter, 'dist/desktop-platform.js');
  writeFileSync(runtimeEntry, 'export {};');
  const platformMock = mock.module('../packages/codex-adapter/dist/desktop-platform.js', {
    namedExports: {
      assertPrivateFile(path) {
        privateChecks.push(path);
        assert.notEqual(path, token, 'publisher check must use actual runtime Adapter');
      },
      closeWindowsPrivateStateBackend() {
        closed++;
      },
      windowsPowerShellEnvironment() {
        return { SAFE_POWERSHELL_ENV: '1' };
      },
      inspectWindowsInstallation() {
        assert.fail('CLI inspection must use actual runtime Adapter');
      },
      writePrivateFileSync(path, value) {
        writes.push(path);
        writeFileSync(path, value);
      },
      makePrivateDirectorySync(path) {
        mkdirSync(path, { recursive: true });
      },
    },
  });
  const actualPlatformMock = mock.module(pathToFileURL(runtimeEntry).href, {
    namedExports: {
      assertPrivateFile(path) {
        runtimePrivateChecks.push(path);
        if (path === token && nativeRejectsToken)
          throw Object.assign(new Error('private native detail'), {
            code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
            nativeType: 'UnauthorizedAccessException',
            nativeRejection: 'WRONG_FILE_OWNER',
            nativeStep: 'owner-check',
            nativeOwner: {
              version: 1,
              checkedAction: 'check',
              helperPid: 123,
              helperUserHash: 'b'.repeat(64),
              fileOwnerHash: 'c'.repeat(64),
              requestedPathHash: 'd'.repeat(64),
              handleIdentityHash: 'e'.repeat(64),
              finalPathHash: 'f'.repeat(64),
              targetKind: 'file',
              transportPidMatches: true,
            },
          });
      },
      inspectWindowsInstallation() {
        cliInspections++;
        return {
          executable: environment.CODEX_EXECUTABLE,
          identity: { pid: 1234, sameUser: true, sameSession: true },
        };
      },
      closeWindowsPrivateStateBackend() {
        runtimeClosed++;
      },
    },
  });
  const gateMock = mock.module('../packages/codex-adapter/dist/windows-process.js', {
    namedExports: {
      withWindowsProcessCreation(budget, operation) {
        assert.equal(budget, 30000);
        return operation(budget);
      },
    },
  });
  const runtimeMock = mock.module('./verify-host-runtime.mjs', {
    namedExports: {
      async verifyHostRuntime() {
        return {
          ok: true,
          packages: [
            {
              name: '@codex-manage/codex-adapter',
              component: 'packages/codex-adapter',
              root: adapter,
              fingerprint: 'a'.repeat(64),
            },
          ],
        };
      },
    },
  });
  const readMock = mock.method(fs, 'readFileSync', (path, ...args) => {
    assert.notEqual(String(path), token, 'probe attempted token body read');
    return actualReadFileSync(path, ...args);
  });
  const execMock = mock.method(childProcess, 'execFileSync', (file, args, options) => {
    assert(file.endsWith('powershell.exe'));
    assert.equal(options.timeout, 30000);
    assert.deepEqual(options.env, { SAFE_POWERSHELL_ENV: '1' });
    const name = args[args.indexOf('-File') + 1];
    psCalls.push(name);
    if (name.endsWith('startup-probe.ps1'))
      return JSON.stringify({
        pid: process.pid,
        userHash: 'b'.repeat(64),
        elevated: true,
        privateRawSid: 'must-not-escape',
        packageFull: { state: 'none', rawName: 'must-not-escape' },
        file: {
          ownerMatchesNodeUser: true,
          ownerHash: 'c'.repeat(64),
          identityHash: 'e'.repeat(64),
          finalPathHash: 'f'.repeat(64),
          protected: true,
          privateRawAcl: 'must-not-escape',
        },
      });
    assert(name.endsWith('private-state.ps1'));
    assert.deepEqual(args.slice(-4), ['-Action', 'check', '-Path', token]);
    return 'PRIVATE_STATE_OK';
  });
  syncBuiltinESMExports();
  Object.defineProperty(process, 'platform', { value: 'win32' });
  try {
    const {
      runWindowsStartupPreflight,
      runWindowsStartupEnvelope,
      sanitizeStartupContext,
      prepareWindowsStartupProbe,
    } = await import('./windows-startup-preflight.mjs');
    assert.throws(() => sanitizeStartupContext({ pid: process.pid + 1 }, process.pid), {
      code: 'PREFLIGHT_CONTEXT_TARGET_CHANGED',
    });
    const result = await runWindowsStartupPreflight({
      hostConfig,
      bundle: join(root, 'bundle.json'),
      workingDirectory: probeDir,
    });
    assert.equal(result.passed, false);
    assert.equal(result.runtimeAdapterBinding, 'actual-host-esm-closure');
    assert.equal(result.workerBootstrapAndRuntimeShareModule, false);
    assert.equal(result.steps.find((v) => v.name === 'publisher-token-native-check').passed, false);
    assert.equal(result.publisherOwnerBinding.helperUserMatchesNode, true);
    assert.equal(result.publisherOwnerBinding.ownerMatchesIndependentMetadata, true);
    assert.equal(result.publisherOwnerBinding.handleMatchesIndependentMetadata, true);
    assert.equal(result.publisherOwnerBinding.finalPathMatchesIndependentMetadata, true);
    assert.equal(result.publisherOwnerBinding.transportPidMatches, true);
    assert.equal(
      result.steps.find((v) => v.name === 'publisher-token-same-source-powershell-check').passed,
      true,
    );
    assert(
      result.targetsMetadataUnchanged && result.callerBootstrapUnchanged && result.backendClosed,
    );
    assert.equal(cliInspections, 1);
    assert.equal(writes.length, 0);
    const json = JSON.stringify(result);
    for (const secret of ['PRIVATE_TOKEN', 'must-not-escape', hostConfig, token, desktopConfig])
      assert(!json.includes(secret));
    assert.equal(process.cwd(), savedCwd);
    assert.deepEqual([hostConfig, desktopConfig, token].map(stamp), before);
    const envelope = {
      format: 'windows-host-startup-preflight-v1',
      nonce: '00000000-0000-4000-8000-000000000000',
      hostConfig,
      bundle: join(root, 'bundle.json'),
      workingDirectory: probeDir,
      reportPath,
      reportStamp: stamp(reportPath),
    };
    await assert.rejects(
      runWindowsStartupEnvelope(
        { ...envelope, reportPath: token },
        join(probeDir, 'windows-host.json'),
      ),
      { code: 'PREFLIGHT_INVALID_ENVELOPE' },
    );
    assert.deepEqual(writes, []);
    nativeRejectsToken = false;
    await runWindowsStartupEnvelope(envelope, join(probeDir, 'windows-host.json'));
    assert.deepEqual(writes, [reportPath]);
    const scheduled = JSON.parse(readFileSync(reportPath, 'utf8'));
    assert.equal(scheduled.passed, true);
    assert.equal(scheduled.launch, 'official-worker-readonly-envelope');
    assert.equal(scheduled.hostStarted, false);
    assert.equal(scheduled.portsOpened, false);
    assert.equal(scheduled.tokenContentsRead, false);
    assert.equal(scheduled.configurationsWritten, false);
    assert.deepEqual([hostConfig, desktopConfig, token].map(stamp), before);
    assert(privateChecks.includes(hostConfig));
    assert(!privateChecks.includes(token));
    assert(runtimePrivateChecks.includes(token));
    assert(psCalls.length >= 4);
    assert(closed >= 3);
    assert(runtimeClosed >= 2);
    const prepared = await prepareWindowsStartupProbe({
      hostConfig,
      bundle: join(root, 'bundle.json'),
      directory: join(root, 'new-limited-probe'),
      workingDirectory: probeDir,
    });
    assert.equal(prepared.scheduledTaskCreated, false);
    assert.equal(prepared.formalHostStarted, false);
    assert.equal(prepared.launcherProtocol.version, 1);
    assert.deepEqual(prepared.launcherProtocol.arguments, [
      process.execPath,
      fileURLToPath(new URL('./windows-desktop-worker.mjs', import.meta.url)),
      prepared.configuration,
    ]);
    assert.equal(prepared.launcherProtocol.nodeSource, 'actual-preparing-process');
    assert.equal(prepared.launcherProtocol.formalNodeIdentityMustBeVerified, true);
    assert.equal(prepared.launcherProtocol.worker, prepared.launcherProtocol.arguments[1]);
    await assert.rejects(
      prepareWindowsStartupProbe({
        hostConfig,
        bundle: join(root, 'bundle.json'),
        directory: join(root, 'new-limited-probe'),
        workingDirectory: probeDir,
      }),
      { code: 'PREFLIGHT_PROBE_DIRECTORY_REJECTED' },
    );
    assert.deepEqual([hostConfig, desktopConfig, token].map(stamp), before);
  } finally {
    Object.defineProperty(process, 'platform', descriptor);
    for (const key of Object.keys(process.env)) if (!(key in savedEnv)) delete process.env[key];
    Object.assign(process.env, savedEnv);
    process.chdir(savedCwd);
    process.exitCode = savedExitCode;
    execMock.mock.restore();
    readMock.mock.restore();
    syncBuiltinESMExports();
    runtimeMock.restore();
    gateMock.restore();
    actualPlatformMock.restore();
    platformMock.restore();
    rmSync(root, { recursive: true });
  }
});
