import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { cp, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { installWindowsPrivateStateFixture } from './fixtures/windows-private-state-boundary.mjs';

// Native calls are stubbed; this is policy/migration evidence, not Windows acceptance.
const directory = await realpath(await mkdtemp(join(tmpdir(), 'windows-control #%-')));
const permissions = installWindowsPrivateStateFixture(
  directory,
  new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url),
);
const executable = join(directory, 'installed', 'Codex.exe');
const archivePath = join(directory, 'installed/resources/app.asar');
const configPath = join(directory, 'windows.json');
const identity = {
  pid: 12345,
  startedAtUnixMs: Date.now() - 60_000,
  executable,
  executableSha256: 'a'.repeat(64),
  userSid: 'fixture-sid',
  sameUser: true,
  sameSession: true,
};
const version = '26.928.20755';
const archive = () => {
  const info = Buffer.from(JSON.stringify({ version }));
  const module = Buffer.from(
    'win32 codex-ipc ipc.sock {"thread-stream-state-changed":11,"thread-follower-start-turn":2,"thread-follower-interrupt-turn":4}',
  );
  const tree = {
    files: {
      'package.json': { offset: '0', size: info.length },
      '.vite': {
        files: {
          build: { files: { 'ipc.js': { offset: String(info.length), size: module.length } } },
        },
      },
    },
  };
  const json = Buffer.from(JSON.stringify(tree));
  const headerSize = 8 + Math.ceil(json.length / 4) * 4;
  const header = Buffer.alloc(8 + headerSize);
  header.writeUInt32LE(4, 0);
  header.writeUInt32LE(headerSize, 4);
  header.writeUInt32LE(headerSize - 4, 8);
  header.writeUInt32LE(json.length, 12);
  json.copy(header, 16);
  return Buffer.concat([header, info, module]);
};
const originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform');
const originalExec = childProcess.execFileSync;
const originalWarn = console.warn;
const previousLocalData = process.env.LOCALAPPDATA;
const previousSystemRoot = process.env.SystemRoot;
// Node's native bootstrap needs the real Windows environment before --import runs.
// Keep OS variables while excluding ambient Host configuration from these fixtures.
const nativeChildEnvironment = Object.fromEntries(
  Object.entries(process.env).filter(([key]) => !/^(HOST_|CODEX_|FIXTURE_)/i.test(key)),
);
const localData = join(directory, 'fixture-user-data');
const systemRoot = join(directory, 'fixture-windows');
let closePrivateState;
try {
  const { loadWindowsDesktop, windowsPowerShellEnvironment, closeWindowsPrivateStateBackend } =
    await import('../packages/codex-adapter/dist/desktop-platform.js');
  closePrivateState = closeWindowsPrivateStateBackend;
  await mkdir(join(systemRoot, 'Microsoft.NET/Framework64/v4.0.30319'), { recursive: true });
  await writeFile(join(systemRoot, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'), 'fixture');
  process.env.LOCALAPPDATA = localData;
  process.env.SystemRoot = systemRoot;
  await mkdir(join(directory, 'installed/resources'), { recursive: true });
  await writeFile(archivePath, archive());
  await writeFile(join(directory, 'installed/resources/codex.exe'), 'fixture');
  Object.defineProperty(process, 'platform', { value: 'win32' });
  childProcess.execFileSync = (file, args) => {
    if (file.endsWith('csc.exe')) {
      writeFileSync(args.find((arg) => arg.startsWith('/out:')).slice(5), 'fixture');
      return '';
    }
    if (args.includes('inspect')) return JSON.stringify(identity);
    throw Error('Unexpected native fixture call');
  };
  console.warn = () => {};
  syncBuiltinESMExports();
  for (const legacy of [
    {},
    { desktopVersion: 'old-build' },
    { desktopVersion: version, validationOnly: true },
    { desktopVersion: version, validationOnly: false },
  ]) {
    await writeFile(configPath, JSON.stringify({ executable, ...legacy }));
    const result = loadWindowsDesktop(configPath, 'host');
    assert.equal(result.verificationStatus, 'not-run');
    assert.equal(result.identity.pid, identity.pid);
  }
  const report = join(directory, 'report.json');
  await writeFile(report, '{}');
  await writeFile(
    configPath,
    JSON.stringify({
      executable,
      desktopVersion: version,
      validationOnly: true,
      verification: report,
    }),
  );
  assert.equal(loadWindowsDesktop(configPath, 'host').verificationStatus, 'stale');
  await rm(report);
  assert.equal(loadWindowsDesktop(configPath, 'host').verificationStatus, 'unavailable');
  identity.sameUser = false;
  assert.throws(() => loadWindowsDesktop(configPath, 'host'), /identity/);
  identity.sameUser = true;
  await writeFile(configPath, JSON.stringify({ executable: 'other.exe', desktopVersion: version }));
  assert.throws(() => loadWindowsDesktop(configPath, 'host'), /Wrong Windows desktop/);
  assert(permissions.calls.some((call) => call.action === 'check' && call.path === configPath));
  Object.defineProperty(process, 'platform', originalPlatform);
  childProcess.execFileSync = originalExec;
  console.warn = originalWarn;
  syncBuiltinESMExports();

  // Run the actual service script with no test Thread and then migrate an old install.
  await mkdir(join(directory, 'scripts'));
  await mkdir(join(directory, 'scripts/fixtures'));
  await mkdir(join(directory, 'packages/codex-adapter/dist'), { recursive: true });
  await mkdir(join(directory, 'packages/codex-adapter/windows'), { recursive: true });
  await writeFile(join(directory, 'package.json'), '{"type":"module"}');
  for (const [from, to] of [
    ['./windows-desktop-service.mjs', 'scripts/windows-desktop-service.mjs'],
    ['./windows-desktop-recovery.mjs', 'scripts/windows-desktop-recovery.mjs'],
    ['./windows-desktop-worker.mjs', 'scripts/windows-desktop-worker.mjs'],
    ['./host-worker-supervisor.mjs', 'scripts/host-worker-supervisor.mjs'],
    [
      './fixtures/windows-private-state-boundary.mjs',
      'scripts/fixtures/windows-private-state-boundary.mjs',
    ],
    [
      '../packages/codex-adapter/dist/diagnostic-events.js',
      'packages/codex-adapter/dist/diagnostic-events.js',
    ],
    [
      '../packages/codex-adapter/dist/desktop-platform.js',
      'packages/codex-adapter/dist/desktop-platform.js',
    ],
    [
      '../packages/codex-adapter/dist/windows-process.js',
      'packages/codex-adapter/dist/windows-process.js',
    ],
    [
      '../packages/codex-adapter/windows/private-state.cs',
      'packages/codex-adapter/windows/private-state.cs',
    ],
    [
      '../packages/codex-adapter/windows/host-launcher.cs',
      'packages/codex-adapter/windows/host-launcher.cs',
    ],
    [
      '../packages/codex-adapter/windows/private-state.ps1',
      'packages/codex-adapter/windows/private-state.ps1',
    ],
  ])
    await cp(new URL(from, import.meta.url), join(directory, to));
  // Exercise preload URL escaping as well as Windows drive-letter handling.
  const mock = join(directory, 'native fixture #%.mjs');
  const mockURL = pathToFileURL(mock).href;
  await writeFile(
    mock,
    `import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { writeFileSync } from 'node:fs';
import fs from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { syncBuiltinESMExports } from 'node:module';
import {installWindowsPrivateStateFixture} from './scripts/fixtures/windows-private-state-boundary.mjs';
import {getWindowsProcessGate} from './packages/codex-adapter/dist/windows-process.js';
const permissions=installWindowsPrivateStateFixture(${JSON.stringify(directory)},new URL('./packages/codex-adapter/dist/desktop-platform.js',import.meta.url));
process.once('exit',()=>permissions.restore());
assert.equal(process.env.SystemRoot, ${JSON.stringify(previousSystemRoot)}, 'Node must bootstrap with the native SystemRoot');
assert.equal(process.env.LOCALAPPDATA, ${JSON.stringify(previousLocalData)}, 'Node must bootstrap with the native LOCALAPPDATA');
// Apply synthetic paths only after Node and its native crypto initialization are ready.
process.env.LOCALAPPDATA = ${JSON.stringify(localData)};
process.env.SystemRoot = ${JSON.stringify(systemRoot)};
Object.defineProperty(process, 'platform', {value:'win32'});
const logicalRoot=join(process.env.LOCALAPPDATA,'CodexManage/native-helpers');
const physicalRoot=join(process.env.LOCALAPPDATA,'Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/CodexManage/native-helpers');
const native=fs.realpathSync.native;
if(process.env.FIXTURE_MSIX_REDIRECT==='1')fs.realpathSync.native=(path,...options)=>{
  if(typeof path==='string'&&path.startsWith(logicalRoot+sep)){
    const physical=join(physicalRoot,relative(logicalRoot,path));
    fs.mkdirSync(dirname(physical),{recursive:true});fs.copyFileSync(path,physical);
    return native(physical,...options);
  }
  return native(path,...options);
};
childProcess.execFileSync = (file, args, options) => {
  assert.equal(options.windowsHide, true, 'Native background helper allocated a console');
  if (file.endsWith('csc.exe')) {
    writeFileSync(args.find(arg => arg.startsWith('/out:')).slice(5), 'synthetic launcher');
    assert(args.includes('/target:winexe'));
    return '';
  }
  if (file.endsWith('private-state.exe')) return 'PRIVATE_STATE_OK';
  if (args.some(arg => arg.endsWith('private-state.ps1'))) {
    const action=args[args.indexOf('-Action')+1],path=args[args.indexOf('-Path')+1];
    assert(['protect','protect-file','check','bootstrap-native'].includes(action));
    if(action==='bootstrap-native')for(const flag of ['-BinaryPath','-PhysicalPath']){
      const binary=args[args.indexOf(flag)+1],inside=relative(${JSON.stringify(directory)},binary);
      assert(binary&&inside!=='..'&&!inside.startsWith('..'+sep),'Binary bootstrap escaped fixture root');
      assert(fs.statSync(binary).isFile());
    }
    const contained=relative(${JSON.stringify(directory)},path);
    assert(contained!=='..'&&!contained.startsWith('..'+sep),'Bootstrap escaped fixture root');
    return 'PRIVATE_STATE_OK';
  }
  if (args.includes('inspect')) return ${JSON.stringify(JSON.stringify(identity))};
  if (args.some(arg => arg.endsWith('desktop-cli.ps1'))) return JSON.stringify({sha256:'b'.repeat(64)});
  if (args.some(arg => arg.endsWith('windows-host-task.ps1'))) {
    const launcher=args[args.indexOf('-LauncherPath')+1];
    const roots=JSON.parse(Buffer.from(args[args.indexOf('-LauncherRootsBase64')+1],'base64').toString('utf8'));
    assert(roots.includes(dirname(dirname(launcher))));
    if(process.env.FIXTURE_MSIX_REDIRECT==='1')assert.equal(dirname(dirname(launcher)),physicalRoot,'Scheduler received a virtual logical path');
    const action = args[args.indexOf('-Action') + 1];
    const budget = Number(args[args.indexOf('-CommandBudgetMs') + 1]);
    const expected = ['stop', 'restart'].includes(action) ? 45000 : 20000;
    // The gate may consume time, but cannot extend the original task/child budget.
    if (budget !== expected || !Number.isSafeInteger(options.timeout) || options.timeout <= 0 || options.timeout > budget + 15000) throw Error('Mismatched service/task command budget');
    assert.equal(Atomics.load(new Int32Array(getWindowsProcessGate()),0),1,'PowerShell creation must hold the actual main process gate');
    if (process.env.FIXTURE_TASK_TIMEOUT === '1') throw Object.assign(Error('Synthetic task timeout'), {code:'ETIMEDOUT', signal:'SIGTERM'});
    return '{"fixtureTaskCalled":true}';
  }
  throw Error('Unexpected native fixture call');
};
syncBuiltinESMExports();
`,
  );
  const run = (action, environment) => {
    const result = childProcess.spawnSync(
      process.execPath,
      ['--import', mockURL, join(directory, 'scripts/windows-desktop-service.mjs'), action],
      {
        encoding: 'utf8',
        env: { ...nativeChildEnvironment, ...environment },
        cwd: directory,
        timeout: 10_000,
      },
    );
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).fixtureTaskCalled, true);
    return result;
  };
  for (const action of ['stop', 'restart']) {
    const rejected = childProcess.spawnSync(
      process.execPath,
      ['--import', mockURL, join(directory, 'scripts/windows-desktop-service.mjs'), action],
      {
        encoding: 'utf8',
        cwd: directory,
        env: {
          ...nativeChildEnvironment,
          HOST_AGENT_ROOT: directory,
          FIXTURE_TASK_TIMEOUT: '1',
        },
        timeout: 10_000,
      },
    );
    assert.equal(rejected.status, 1, rejected.stderr);
    assert.equal(
      rejected.stdout,
      '',
      'A native timeout must never become successful service output',
    );
    assert.match(rejected.stderr, /ETIMEDOUT/);
  }
  run('stop', {});
  run('status', {});
  run('status', { FIXTURE_MSIX_REDIRECT: '1' });
  run('start', {
    HOST_AGENT_ROOT: directory,
    HOST_AGENT_NAME: 'Preserved PC',
    HOST_AGENT_PORT: '8822',
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '1',
    CODEX_DESKTOP_SUGGESTION_TRACE_ROOT: join(directory, 'private-trace-fixture'),
  });
  const savedPath = join(directory, '.local/desktop-runtime/windows-desktop.json');
  const saved = JSON.parse(await readFile(savedPath, 'utf8'));
  assert.equal(saved.validationOnly, false);
  assert.equal(saved.validationThreadId, undefined);
  const jobPath = join(directory, '.local/desktop-runtime/windows-host.json');
  const before = JSON.parse(await readFile(jobPath, 'utf8'));
  await writeFile(
    savedPath,
    JSON.stringify({
      ...saved,
      validationOnly: true,
      desktopVersion: 'old',
      executable: 'old.exe',
      archive: 'old.asar',
      verification: report,
    }),
  );
  run('restart', {});
  const migrated = JSON.parse(await readFile(savedPath, 'utf8'));
  assert.equal(migrated.validationOnly, false);
  assert.equal(migrated.desktopVersion, 'old');
  assert.equal(migrated.executable, executable);
  assert.equal(migrated.archive, archivePath);
  assert.equal(migrated.verification, report);
  const after = JSON.parse(await readFile(jobPath, 'utf8'));
  assert.equal(after.environment.HOST_AGENT_ROOT, before.environment.HOST_AGENT_ROOT);
  assert.equal(after.environment.HOST_AGENT_NAME, 'Preserved PC');
  assert.equal(after.environment.HOST_AGENT_PORT, '8822');
  assert.equal(after.environment.CODEX_DESKTOP_SUGGESTION_OBSERVER, '1');
  assert.equal(
    after.environment.CODEX_DESKTOP_SUGGESTION_TRACE_ROOT,
    join(directory, 'private-trace-fixture'),
  );
  run('restart', {
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
    CODEX_DESKTOP_SUGGESTION_TRACE_ROOT: '',
  });
  run('restart', {});
  const optedOut = JSON.parse(await readFile(jobPath, 'utf8'));
  assert.equal(optedOut.environment.CODEX_DESKTOP_SUGGESTION_OBSERVER, '0');
  assert.equal(optedOut.environment.CODEX_DESKTOP_SUGGESTION_TRACE_ROOT, '');
  assert.equal(after.environment.HOST_NOTIFICATION_RELAY_URL, undefined);
  assert.equal(after.environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE, undefined);
  // Exercise service persistence and the real worker; native calls and runtime entry are stubbed.
  const relayURL = 'https://notifications.example.invalid/push?host=windows&name="fixture"';
  const tokenFile = 'missing publisher token & fixture.txt';
  const rawToken = 'fixture-raw-publisher-token-must-not-be-copied';
  const httpsEndpoints = '["https://windows.tail.ts.net:8443"]';
  const notificationEnvironment = () => readFile(jobPath, 'utf8').then(JSON.parse);
  const configuredResult = run('restart', {
    HOST_NOTIFICATION_RELAY_URL: relayURL,
    HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: tokenFile,
    HOST_NOTIFICATION_PUBLISHER_TOKEN: rawToken,
    HOST_AGENT_HTTPS_ENDPOINTS: httpsEndpoints,
  });
  const configured = await notificationEnvironment();
  assert.equal(configured.environment.HOST_NOTIFICATION_RELAY_URL, relayURL);
  assert.equal(
    configured.environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
    join(directory, tokenFile),
  );
  assert.equal(configured.environment.HOST_NOTIFICATION_PUBLISHER_TOKEN, undefined);
  assert.equal(configured.environment.HOST_AGENT_HTTPS_ENDPOINTS, httpsEndpoints);
  assert.ok(!JSON.stringify(configured).includes(rawToken));
  assert.ok(
    !configuredResult.stdout.includes(rawToken) && !configuredResult.stderr.includes(rawToken),
  );
  assert.ok(
    !configuredResult.stdout.includes(relayURL) && !configuredResult.stdout.includes(tokenFile),
  );
  run('restart', {});
  assert.deepEqual(await notificationEnvironment(), configured);
  const workerSnapshot = join(directory, 'worker-environment.json');
  await mkdir(join(directory, 'apps/host-agent/dist'), { recursive: true });
  await writeFile(
    join(directory, 'apps/host-agent/dist/runtime-entry.js'),
    `import { writeFileSync } from 'node:fs';
writeFileSync(${JSON.stringify(workerSnapshot)}, JSON.stringify({
  relayURL: process.env.HOST_NOTIFICATION_RELAY_URL,
  tokenFile: process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
  httpsEndpoints: process.env.HOST_AGENT_HTTPS_ENDPOINTS,
}));
`,
  );
  const runWorker = (config) =>
    childProcess.spawnSync(
      process.execPath,
      [
        '--import',
        mockURL,
        join(directory, 'scripts/windows-desktop-worker.mjs'),
        config,
        '--host-child',
      ],
      {
        encoding: 'utf8',
        env: nativeChildEnvironment,
        cwd: directory,
        timeout: 10_000,
      },
    );
  const worker = runWorker(jobPath);
  assert.equal(worker.status, 0, worker.stderr);
  assert.equal(worker.stdout, '');
  assert.deepEqual(JSON.parse(worker.stderr), {
    diagnostic: 'host-runtime-failure',
    scope: 'startup-recovery',
    code: 'WINDOWS_CONFIG_REFRESHED',
  });
  assert.deepEqual(JSON.parse(await readFile(workerSnapshot, 'utf8')), {
    relayURL,
    tokenFile: join(directory, tokenFile),
    httpsEndpoints,
  });
  const invalidWorkerConfig = join(directory, 'invalid-worker.json');
  for (const invalid of [
    { HOST_NOTIFICATION_PUBLISHER_TOKEN: rawToken },
    { UNRELATED_SETTING: 'fixture' },
    { HOST_NOTIFICATION_RELAY_URL: 42 },
    { HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: 42 },
    { HOST_AGENT_HTTPS_ENDPOINTS: 42 },
  ]) {
    await rm(workerSnapshot, { force: true });
    await writeFile(
      invalidWorkerConfig,
      JSON.stringify({ environment: { ...configured.environment, ...invalid } }),
      { mode: 0o600 },
    );
    const rejected = runWorker(invalidWorkerConfig);
    assert.equal(rejected.status, 1);
    assert.match(rejected.stderr, /Invalid Host environment/);
    assert.equal(rejected.stdout, '');
    assert.ok(!rejected.stderr.includes(rawToken));
    await assert.rejects(readFile(workerSnapshot), { code: 'ENOENT' });
  }
  run('restart', { HOST_NOTIFICATION_RELAY_URL: relayURL + '&updated=1' });
  const updated = (await notificationEnvironment()).environment;
  assert.equal(updated.HOST_NOTIFICATION_RELAY_URL, relayURL + '&updated=1');
  assert.equal(updated.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE, join(directory, tokenFile));
  assert.equal(updated.HOST_AGENT_NAME, 'Preserved PC');
  assert.equal(updated.HOST_AGENT_PORT, '8822');
  run('restart', { HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: tokenFile + '.new' });
  const updatedFile = (await notificationEnvironment()).environment;
  assert.equal(updatedFile.HOST_NOTIFICATION_RELAY_URL, updated.HOST_NOTIFICATION_RELAY_URL);
  assert.equal(
    updatedFile.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
    join(directory, tokenFile + '.new'),
  );
  run('restart', {
    HOST_NOTIFICATION_RELAY_URL: '',
    HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: '',
  });
  run('restart', {});
  const cleared = (await notificationEnvironment()).environment;
  assert.equal(cleared.HOST_NOTIFICATION_RELAY_URL, '');
  assert.equal(cleared.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE, '');
  const clearedWorker = runWorker(jobPath);
  assert.equal(clearedWorker.status, 0, clearedWorker.stderr);
  assert.deepEqual(JSON.parse(await readFile(workerSnapshot, 'utf8')), {
    relayURL: '',
    tokenFile: '',
    httpsEndpoints,
  });
  console.log(
    'Windows notification configuration: persistence, worker forwarding and input rejection passed. Native calls and runtime entry mocked.',
  );
  const powershell =
    process.env.DESKTOP_TASK_FIXTURE_PWSH ??
    (process.platform === 'win32' ? 'powershell.exe' : undefined);
  if (powershell) {
    const taskScript = join(directory, 'windows-host-task.ps1');
    const source = await readFile(new URL('./windows-host-task.ps1', import.meta.url), 'utf8');
    // Stub only identity acquisition; execute the original PowerShell lifecycle and policy.
    await writeFile(
      taskScript,
      source
        .replaceAll(
          '[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value',
          "'fixture-sid'",
        )
        .replace(
          'return $commandClock.ElapsedMilliseconds',
          'return ($commandClock.ElapsedMilliseconds + $global:FixtureElapsedMs)',
        )
        .replace('            Initialize-HostProcessIdentity\n', '')
        .replace(
          '[AgentHub.HostProcessIdentityV1]::Read($nativeProcess.Handle)',
          '(Get-FixtureHostOwnerSid $nativeProcess.Handle)',
        ),
    );
    for (const scenario of [
      'lifecycle',
      'unrelated',
      'wrong-owner',
      'stop-tree',
      'wrong-process-owner',
      'owner-query-failed',
      'batch-identity-drift',
      'wide-tree',
      'pid-reuse',
      'stop-timeout',
      'wait-kernel-signal',
      'wait-kernel-timeout',
      'wait-kernel-throw',
      'slow-queries',
      'command-budget',
      'late-child',
      'outer-recovery',
      'launcher-migration',
      'launcher-tree',
      'unrelated-launcher',
      'msix-paths',
      'msix-other-root',
      'msix-wrong-owner',
      'msix-wrong-arguments',
    ]) {
      const result = childProcess.spawnSync(
        powershell,
        [
          '-NoProfile',
          '-NonInteractive',
          '-File',
          fileURLToPath(new URL('./windows-host-task-fixture.ps1', import.meta.url)),
          '-ScriptPath',
          taskScript,
          '-Scenario',
          scenario,
        ],
        {
          encoding: 'utf8',
          timeout: 30_000,
          env: windowsPowerShellEnvironment(nativeChildEnvironment),
        },
      );
      assert.equal(result.status, 0, result.stderr || String(result.error));
      const output = JSON.parse(result.stdout);
      assert.equal(output.scenario, scenario);
      assert.equal(
        [
          'lifecycle',
          'stop-tree',
          'wait-kernel-signal',
          'slow-queries',
          'wide-tree',
          'late-child',
          'outer-recovery',
          'launcher-migration',
          'launcher-tree',
          'msix-paths',
        ].includes(scenario)
          ? output.passed
          : output.rejected,
        true,
      );
      if (
        [
          'slow-queries',
          'wide-tree',
          'command-budget',
          'outer-recovery',
          'launcher-migration',
          'launcher-tree',
          'unrelated-launcher',
          'msix-paths',
          'msix-other-root',
          'msix-wrong-owner',
          'msix-wrong-arguments',
        ].includes(scenario)
      )
        console.log(
          JSON.stringify({
            test: scenario.startsWith('msix-')
              ? 'windows-msix-helper-fixture'
              : scenario === 'outer-recovery'
                ? 'windows-outer-recovery-fixture'
                : scenario.includes('launcher')
                  ? 'windows-hidden-launcher-fixture'
                  : 'windows-command-budget',
            ...output,
          }),
        );
    }
    console.log(
      'Windows scheduled-task fixture: outer-supervisor exit/periodic recovery, persistent stop, login, singleton, disable-before-stop, owned tree/orphan/late-child cleanup, slow-provider and command-budget rejection passed. Task Scheduler/processes mocked.',
    );
  } else {
    console.log('Windows scheduled-task fixture not run: PowerShell unavailable.');
  }
  console.log(
    'Windows control policy: report-only acceptance, legacy mode migration and identity boundaries passed. Native calls mocked; Windows hardware not verified.',
  );
} finally {
  closePrivateState?.();
  permissions.restore();
  Object.defineProperty(process, 'platform', originalPlatform);
  childProcess.execFileSync = originalExec;
  console.warn = originalWarn;
  if (previousLocalData === undefined) delete process.env.LOCALAPPDATA;
  else process.env.LOCALAPPDATA = previousLocalData;
  if (previousSystemRoot === undefined) delete process.env.SystemRoot;
  else process.env.SystemRoot = previousSystemRoot;
  syncBuiltinESMExports();
  await rm(directory, { recursive: true, force: true });
}
