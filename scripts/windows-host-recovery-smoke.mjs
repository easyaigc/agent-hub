import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  existsSync,
  readdirSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { superviseWorker, workerRecoveryState } from './host-worker-supervisor.mjs';
import { installWindowsPrivateStateFixture } from './fixtures/windows-private-state-boundary.mjs';

// Actual discovery, archive parsing and atomic publication. Only OS identity APIs
// are stubbed; this is not Windows signature/DACL/desktop acceptance.
const directory = mkdtempSync(join(tmpdir(), 'windows-recovery-'));
const permissions = installWindowsPrivateStateFixture(
  directory,
  new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url),
);
const originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform');
const originalExec = childProcess.execFileSync;
const originalEnv = {
  SystemRoot: process.env.SystemRoot,
  LOCALAPPDATA: process.env.LOCALAPPDATA,
  PSModulePath: process.env.PSModulePath,
};
let supervisor;
let closePrivateState;
try {
  const { discoverWindowsDesktopCli, refreshWindowsHostConfiguration } =
    await import('./windows-desktop-recovery.mjs');
  const { inspectWindowsPipe, loadWindowsDesktop, closeWindowsPrivateStateBackend } =
    await import('../packages/codex-adapter/dist/desktop-platform.js');
  closePrivateState = closeWindowsPrivateStateBackend;
  const owner = join(directory, 'installed/Codex.exe');
  const cli = join(directory, 'user/OpenAI/Codex/bin/e78bad9887fa50cf/codex.exe');
  const desktopPath = join(directory, 'windows-desktop.json');
  const hostPath = join(directory, 'windows-host.json');
  mkdirSync(join(directory, 'installed/resources'), { recursive: true });
  mkdirSync(join(directory, 'user/OpenAI/Codex/bin/e78bad9887fa50cf'), { recursive: true });
  writeFileSync(owner, 'synthetic desktop');
  writeFileSync(cli, 'synthetic cli');
  const info = Buffer.from(JSON.stringify({ version: 'future-metadata' }));
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
  header.writeUInt32LE(headerSize, 4);
  header.writeUInt32LE(json.length, 12);
  json.copy(header, 16);
  writeFileSync(
    join(directory, 'installed/resources/app.asar'),
    Buffer.concat([header, info, module]),
  );
  const identity = {
    pid: 1234,
    startedAtUnixMs: Date.now() - 60_000,
    executable: owner,
    executableSha256: 'a'.repeat(64),
    sameUser: true,
    sameSession: true,
  };
  let failure;
  let validCli = true;
  const calls = [];
  process.env.SystemRoot = join(directory, 'system');
  process.env.LOCALAPPDATA = join(directory, 'user');
  process.env.PSModulePath = 'synthetic-ps7';
  mkdirSync(join(process.env.SystemRoot, 'Microsoft.NET/Framework64/v4.0.30319'), {
    recursive: true,
  });
  writeFileSync(
    join(process.env.SystemRoot, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'),
    'synthetic compiler',
  );
  Object.defineProperty(process, 'platform', { value: 'win32' });
  childProcess.execFileSync = (file, args, options) => {
    calls.push(args);
    if (file.endsWith('powershell.exe'))
      assert(!Object.keys(options.env).some((key) => key.toUpperCase() === 'PSMODULEPATH'));
    if (file.endsWith('csc.exe')) {
      writeFileSync(args.find((v) => v.startsWith('/out:')).slice(5), 'synthetic helper');
      return '';
    }
    if (args.includes('inspect')) {
      if (failure) throw Object.assign(new Error('private error'), { stderr: failure + '\n' });
      return JSON.stringify(identity);
    }
    if (args.some((v) => v.endsWith('desktop-cli.ps1'))) {
      if (!validCli)
        throw Object.assign(new Error('private CLI identity error'), {
          stderr: 'WINDOWS_CLI_REJECTED\n',
        });
      return JSON.stringify({ sha256: args.includes(cli) ? 'b'.repeat(64) : 'c'.repeat(64) });
    }
    throw new Error('Unexpected OS call');
  };
  syncBuiltinESMExports();
  const desktop = {
    executable: 'missing-old-desktop',
    validationOnly: true,
    verification: 'historical-report',
    custom: 'preserved',
  };
  const configuration = {
    custom: 'preserved',
    environment: {
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: 'missing-cli',
      CODEX_DESKTOP_WINDOWS_CONFIG: desktopPath,
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: 'preserved-prepared',
      HOST_AGENT_ROOT: 'preserved-root',
      HOST_AGENT_PORT: '8794',
    },
  };
  writeFileSync(desktopPath, JSON.stringify(desktop));
  writeFileSync(hostPath, JSON.stringify(configuration));
  const refreshed = refreshWindowsHostConfiguration(hostPath);
  assert(refreshed.changed);
  assert.equal(refreshed.configuration.environment.CODEX_EXECUTABLE, cli);
  assert.equal(refreshed.configuration.environment.CODEX_DESKTOP_VERSION, 'future-metadata');
  for (const key of ['CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG', 'HOST_AGENT_ROOT', 'HOST_AGENT_PORT'])
    assert.equal(refreshed.configuration.environment[key], configuration.environment[key]);
  assert.equal(refreshed.configuration.custom, configuration.custom);
  const resultDesktop = JSON.parse(readFileSync(desktopPath));
  assert.equal(resultDesktop.validationOnly, true);
  assert.equal(resultDesktop.verification, desktop.verification);
  assert.equal(resultDesktop.custom, desktop.custom);
  assert(!refreshWindowsHostConfiguration(hostPath).changed, 'Repeated startup is idempotent');
  // Reproduce the live worker's stale startup paths after a desktop update.
  // The ordinary strict inspector still rejects them. Runtime reconnection
  // resolves the current native owner without rewriting any private state.
  writeFileSync(desktopPath, JSON.stringify({ ...desktop, archive: 'missing-old-archive' }));
  const staleBytes = readFileSync(desktopPath);
  assert.throws(() => loadWindowsDesktop(desktopPath, 'host'), /Wrong Windows desktop/);
  const live = loadWindowsDesktop(desktopPath, 'host', { executable: 'missing-cli' });
  assert.equal(live.identity.executable, owner);
  assert.equal(live.executable, cli);
  assert.equal(live.archive.version, 'future-metadata');
  assert.deepEqual(readFileSync(desktopPath), staleBytes);
  assert.deepEqual(
    readFileSync(hostPath),
    Buffer.from(JSON.stringify(refreshed.configuration, null, 2) + '\n'),
  );
  for (const property of ['sameUser', 'sameSession']) {
    identity[property] = false;
    assert.throws(() => loadWindowsDesktop(desktopPath, 'host', { executable: cli }), /identity/);
    identity[property] = true;
  }
  validCli = false;
  assert.throws(
    () => loadWindowsDesktop(desktopPath, 'host', { executable: cli }),
    (error) => error.code === 'WINDOWS_CLI_NOT_FOUND',
  );
  validCli = true;
  assert.deepEqual(readFileSync(desktopPath), staleBytes);
  refreshWindowsHostConfiguration(hostPath);

  const before = readFileSync(hostPath);
  const desktopBefore = readFileSync(desktopPath);
  for (const reason of ['WINDOWS_DESKTOP_NOT_RUNNING', 'WINDOWS_POWERSHELL_MODULE_UNAVAILABLE']) {
    failure = reason;
    assert.throws(
      () => refreshWindowsHostConfiguration(hostPath),
      (error) => error.code === reason,
    );
    assert.deepEqual(readFileSync(hostPath), before);
    assert.deepEqual(readFileSync(desktopPath), desktopBefore);
  }
  failure = undefined;
  validCli = false;
  assert.throws(
    () => refreshWindowsHostConfiguration(hostPath),
    (error) => error.code === 'WINDOWS_CLI_NOT_FOUND',
  );
  assert.deepEqual(readFileSync(hostPath), before);
  validCli = true;
  const second = join(directory, 'user/OpenAI/Codex/bin/abcdef0123456789');
  mkdirSync(second);
  writeFileSync(join(second, 'codex.exe'), 'different synthetic payload');
  assert.throws(
    () => discoverWindowsDesktopCli(identity, 'missing-cli'),
    (error) => error.code === 'WINDOWS_CLI_AMBIGUOUS',
  );
  assert.equal(
    discoverWindowsDesktopCli(identity, cli),
    cli,
    'A valid configured CLI survives old cached payloads',
  );
  assert(!refreshWindowsHostConfiguration(hostPath).changed);
  const startedAtUnixMs = identity.startedAtUnixMs;
  for (const invalid of [undefined, 0, Date.now() + 60_000]) {
    identity.startedAtUnixMs = invalid;
    assert.throws(() => inspectWindowsPipe(), /Invalid Windows desktop identity/);
  }
  identity.startedAtUnixMs = startedAtUnixMs;
  identity.sameUser = false;
  assert.throws(() => inspectWindowsPipe(), /Invalid Windows desktop identity/);
  assert.equal(process.env.PSModulePath, 'synthetic-ps7', 'Parent environment preserved');
  assert(!readdirSync(directory).some((name) => name.endsWith('.tmp')));
  assert(
    calls.every((args) => !args.includes('app-server')),
    'Discovery never executes CLI or model input',
  );
  assert(permissions.calls.some((call) => call.action === 'check' && call.path === hostPath));
  assert(
    permissions.calls.some((call) => call.action === 'protect-file' && call.path.endsWith('.tmp')),
    'Atomic publication must still cross the private-file boundary',
  );
  Object.defineProperty(process, 'platform', originalPlatform);
  childProcess.execFileSync = originalExec;
  syncBuiltinESMExports();

  // A real Node HTTP child hangs, then degrades, then recovers. Probe transitions
  // never kill a live worker, and stop prevents further probes or launches.
  const child = join(directory, 'http-child.mjs');
  const marker = join(directory, 'port');
  const control = join(directory, 'health-control');
  writeFileSync(control, 'http-unhealthy');
  writeFileSync(
    child,
    `import {createServer} from 'node:http'; import {writeFileSync,readFileSync} from 'node:fs';
const server=createServer((_req,res)=>{ const state=readFileSync(${JSON.stringify(control)},'utf8'); if(state!=='http-unhealthy')res.end(JSON.stringify({state})); }); server.listen(0,'127.0.0.1',()=>writeFileSync(${JSON.stringify(marker)},String(server.address().port)));
process.on('message',()=>{server.closeAllConnections();server.close(()=>process.exit(0));}); process.once('disconnect',()=>process.exit(1));`,
  );
  let probeState = 'http-unhealthy';
  let probeCount = 0;
  let actualHttpTimeouts = 0;
  const reports = [];
  supervisor = superviseWorker({
    executable: process.execPath,
    args: [child],
    report: (entry) => reports.push(entry),
    probeIntervalMs: 10,
    probe: async () => {
      probeCount++;
      if (!existsSync(marker)) throw Error('starting');
      const port = readFileSync(marker, 'utf8');
      try {
        return (
          await (
            await fetch('http://127.0.0.1:' + port, { signal: AbortSignal.timeout(100) })
          ).json()
        ).state;
      } catch (error) {
        if (error.name === 'TimeoutError') actualHttpTimeouts++;
        throw error;
      }
    },
  });
  const until = async (condition) => {
    for (let i = 0; i < 200; i++) {
      if (condition()) return;
      await delay(10);
    }
    throw new Error('Recovery fixture timed out');
  };
  await until(
    () =>
      existsSync(marker) &&
      actualHttpTimeouts > 0 &&
      reports.some((v) => v.state === 'http-unhealthy'),
  );
  probeState = 'persistence-degraded';
  writeFileSync(control, probeState);
  await until(() => reports.some((v) => v.state === probeState));
  probeState = 'healthy';
  writeFileSync(control, probeState);
  await until(() => reports.some((v) => v.state === probeState));
  assert.equal(reports.filter((v) => v.event === 'started').length, 1);
  assert.equal(reports.filter((v) => v.event === 'exited').length, 0);
  await supervisor.stop();
  const stopped = probeCount;
  await delay(50);
  assert.equal(probeCount, stopped);
  assert.equal(
    workerRecoveryState('uncaught', 'WINDOWS_DESKTOP_NOT_RUNNING'),
    'desktop-not-running',
  );
  assert.equal(
    workerRecoveryState('uncaught', 'WINDOWS_CLI_NOT_FOUND'),
    'configuration-refresh-required',
  );
  assert.equal(workerRecoveryState('history-save', 'ENOSPC'), 'persistence-degraded');
  console.log(
    JSON.stringify({
      result: 'passed',
      evidence: 'OS identity fixture plus isolated real Node child; not Windows acceptance',
      hashedCliDiscovery: true,
      startupRefreshPreservesConfiguration: true,
      liveWorkerNeverKilledForHealth: true,
      actualHttpTimeouts,
    }),
  );
} finally {
  await supervisor?.stop();
  closePrivateState?.();
  permissions.restore();
  Object.defineProperty(process, 'platform', originalPlatform);
  childProcess.execFileSync = originalExec;
  syncBuiltinESMExports();
  for (const [key, value] of Object.entries(originalEnv))
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  rmSync(directory, { recursive: true, force: true });
}
