import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { installWindowsPrivateStateFixture } from './fixtures/windows-private-state-boundary.mjs';

// Synthetic Windows trust/permissions, real discovery/archive parsing and real
// Node subprocess JSONL. This does not prove Windows DACL/signatures or device acceptance.
const directory = mkdtempSync(join(tmpdir(), 'desktop-workspace-cli-refresh-'));
const log = join(directory, 'methods.jsonl');
const platformDescriptor = Object.getOwnPropertyDescriptor(process, 'platform');
const originalSpawn = childProcess.spawn;
const originalExec = childProcess.execFileSync;
const originalTimeout = globalThis.setTimeout;
const environment = Object.fromEntries(
  [
    'SystemRoot',
    'LOCALAPPDATA',
    'PSModulePath',
    'CODEX_EXECUTABLE',
    'CODEX_DESKTOP_WINDOWS_CONFIG',
  ].map((key) => [key, process.env[key]]),
);
const permissions = installWindowsPrivateStateFixture(
  directory,
  new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url),
);
const fixtures = new Set();
const children = [];
const readers = [];
const missing = join(directory, 'missing-reader.exe');
let view;
let closePrivateState;
let identity;
let trustedCli;
let cliTrusted = true;
let beforeSpawn;
let inspections = 0;
let signatures = 0;

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function readerScript(path, rejectInitialize = false) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(
    path,
    [
      "import { createInterface } from 'node:readline';",
      "import { appendFileSync } from 'node:fs';",
      'const log = ' + JSON.stringify(log) + ';',
      'const rejectInitialize = ' + rejectInitialize + ';',
      "createInterface({ input: process.stdin }).on('line', line => {",
      '  const request = JSON.parse(line);',
      "  appendFileSync(log, request.method + '\\n');",
      '  if (request.id === undefined) return;',
      "  if (request.method === 'initialize' && rejectInitialize) {",
      "    process.stdout.write(JSON.stringify({ id: request.id, error: { code: -32600, message: 'fixture-private-detail' } }) + '\\n');",
      '    return;', // Remain alive until the production reader retires this rejected child.
      '  }',
      "  const result = request.method === 'initialize' ? {} : { data: [], nextCursor: null };",
      "  process.stdout.write(JSON.stringify({ id: request.id, result }) + '\\n');",
      '});',
    ].join('\n'),
  );
  fixtures.add(path);
  return path;
}

function installation(version) {
  const owner = join(directory, version, 'Codex.exe');
  const cli = join(directory, version, 'resources/codex.exe');
  readerScript(cli);
  writeFileSync(owner, 'synthetic signed owner');
  const info = Buffer.from(JSON.stringify({ version }));
  const module = Buffer.from(
    'win32 codex-ipc ipc.sock {"thread-stream-state-changed":11,"thread-follower-start-turn":2,"thread-follower-interrupt-turn":4}',
  );
  const tree = {
    files: {
      'package.json': { offset: '0', size: info.length },
      '.vite': {
        files: {
          build: {
            files: {
              'ipc.js': { offset: String(info.length), size: module.length },
            },
          },
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
  writeFileSync(join(dirname(cli), 'app.asar'), Buffer.concat([header, info, module]));
  identity = {
    pid: 1234,
    startedAtUnixMs: Date.now() - 60_000,
    executable: owner,
    executableSha256: 'a'.repeat(64),
    userSid: 'S-1-5-21-fixture',
    sameUser: true,
    sameSession: true,
    trustedPublisher: true,
  };
  trustedCli = cli;
  return { owner, cli };
}

async function closed(record) {
  let timer;
  try {
    await Promise.race([
      record.actualClose.promise,
      new Promise((_, reject) => {
        timer = originalTimeout(() => reject(new Error('Fixture child did not close')), 5000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function safeFailure(error, stage, code, osCode = 'UNKNOWN') {
  assert.equal(error.name, 'CodexMetadataReader_' + stage + '_' + osCode);
  assert.equal(error.role, 'metadata-reader');
  assert.equal(error.stage, stage);
  assert.equal(error.code, code);
  assert.equal(error.osCode, osCode);
  for (const key of ['cause', 'path', 'stderr', 'env']) assert.equal(error[key], undefined);
  assert(!JSON.stringify(error).includes('fixture-private-detail'));
  assert(!error.stack.includes('fixture-private-detail'));
  return true;
}

try {
  process.env.SystemRoot = join(directory, 'system');
  process.env.LOCALAPPDATA = join(directory, 'user');
  Object.defineProperty(process, 'platform', { value: 'win32' });
  childProcess.execFileSync = (file, args, options) => {
    assert(file.endsWith('powershell.exe'), 'Only synthetic Windows trust calls are allowed');
    assert.equal(options.windowsHide, true);
    if (args.includes('inspect')) {
      inspections++;
      return JSON.stringify(identity);
    }
    if (args.some((arg) => arg.endsWith('desktop-cli.ps1'))) {
      signatures++;
      assert(!args.includes('-Desktop'), 'Current fixed CLI inspection takes only its path');
      assert.equal(args[args.indexOf('-Path') + 1], trustedCli);
      if (!cliTrusted)
        throw Object.assign(new Error('fixture-private-detail'), {
          stderr: 'WINDOWS_CLI_REJECTED\n',
        });
      return JSON.stringify({ sha256: 'b'.repeat(64) });
    }
    throw new Error('Unexpected Windows trust operation');
  };
  childProcess.spawn = (command, args, options) => {
    assert(fixtures.has(command) || command === missing, 'Only this task fixture may spawn');
    assert.deepEqual(args, ['app-server', '--stdio']);
    assert.equal(options.windowsHide, true);
    assert.equal(options.stdio, 'pipe');
    beforeSpawn?.(command);
    const childEnvironment = { ...options.env };
    // The synthetic trust paths belong to this parent. Real Windows Node must
    // initialize under its original OS environment before running our JSONL fixture.
    for (const key of Object.keys(childEnvironment))
      if (/^(SystemRoot|LOCALAPPDATA|PSModulePath)$/i.test(key)) delete childEnvironment[key];
    for (const key of ['SystemRoot', 'LOCALAPPDATA', 'PSModulePath'])
      if (environment[key] !== undefined) childEnvironment[key] = environment[key];
    const child = fixtures.has(command)
      ? originalSpawn(
          process.execPath,
          ['--input-type=module', '--eval', readFileSync(command, 'utf8'), '--', ...args],
          { ...options, env: childEnvironment },
        )
      : originalSpawn(command, args, options);
    const record = {
      child,
      command,
      actualClose: deferred(),
      actualClosed: false,
      deliveredClose: false,
      holdClose: false,
      releaseClose: undefined,
    };
    children.push(record);
    const emit = child.emit;
    child.emit = function (event, ...values) {
      if (event === 'close') {
        record.actualClosed = true;
        record.actualClose.resolve();
        // Only this scenario withholds the notification to model an unconfirmed
        // exit/stdio close. The real OS process still exits and is independently observed.
        const deliver = () => {
          if (record.deliveredClose) return false;
          record.deliveredClose = true;
          return emit.call(this, event, ...values);
        };
        if (record.holdClose) {
          record.releaseClose = deliver;
          return true;
        }
        return deliver();
      }
      return emit.call(this, event, ...values);
    };
    return child;
  };
  syncBuiltinESMExports();
  const { CodexHistoryReader } = await import('../packages/codex-adapter/dist/history-reader.js');
  const { DesktopWorkspaceView } =
    await import('../packages/codex-adapter/dist/desktop-workspace-view.js');
  let loadWindowsDesktop;
  ({ closeWindowsPrivateStateBackend: closePrivateState, loadWindowsDesktop } =
    await import('../packages/codex-adapter/dist/desktop-platform.js'));
  const makeReader = (executable, resolver) => {
    const reader = new CodexHistoryReader(
      'fixture-host',
      'Fixture',
      executable,
      undefined,
      resolver,
    );
    readers.push(reader);
    return reader;
  };

  // Preserve the frozen R2 injected-resolver seam under the current workspace API.
  // The actual shared production discovery is covered by desktop-update-recovery.
  const first = installation('old-install');
  const config = join(directory, 'windows-desktop.json');
  const configBytes = JSON.stringify({ executable: first.owner });
  writeFileSync(config, configBytes, { mode: 0o600 });
  process.env.CODEX_DESKTOP_WINDOWS_CONFIG = config;
  process.env.CODEX_EXECUTABLE = first.cli;
  const ownerInputs = ['fixture-original-input'];
  let ownerCommands = 0;
  const session = {
    hostId: 'fixture-host',
    async connectDesktopReader(reader) {
      await reader.connect();
    },
    async workspace() {
      return {
        hostId: this.hostId,
        connectionMode: 'desktop-runtime',
        projects: [],
        threads: [{ id: 'owner', hostId: this.hostId, state: 'running' }],
        nextProjectCursor: null,
        nextThreadCursor: null,
      };
    },
    isThreadArchived: () => false,
    history: () => ({ runtime: { canControl: true } }),
    submit() {
      ownerCommands++;
      ownerInputs.push('unexpected');
      throw new Error('Unexpected input');
    },
    rememberDesktopThread() {
      throw new Error('The synthetic catalog is empty');
    },
  };
  const makeView = () =>
    new DesktopWorkspaceView(
      session,
      'Fixture',
      first.cli,
      join(directory, 'state.json'),
      (current) => loadWindowsDesktop(config, session.hostId, { executable: current }).executable,
    );
  const workspaceFailure = (error) => {
    assert.equal(error.status, 503);
    assert.equal(error.code, 'CODEX_READER_EXECUTABLE_RESOLUTION_FAILED');
    assert(!JSON.stringify(error).includes('fixture-private-detail'));
    return true;
  };
  view = makeView();
  const initial = view.workspace();
  assert.equal(view.workspace(), initial, 'Catalog requests coalesce');
  assert.equal((await initial).threads[0].state, 'running');
  const previous = children.at(-1);
  previous.child.kill('SIGTERM');
  await closed(previous);
  const second = installation('new-install');
  unlinkSync(first.cli);
  assert.equal(existsSync(first.cli), false);
  beforeSpawn = (command) => {
    assert(previous.actualClosed && previous.deliveredClose);
    assert.equal(command, second.cli, 'Do not spawn the removed startup CLI');
  };
  const beforeInspections = inspections;
  const beforeSignatures = signatures;
  assert.equal((await view.workspace()).threads[0].state, 'running');
  assert(inspections > beforeInspections && signatures > beforeSignatures);
  assert.equal(view.reader.executable, second.cli);
  assert.equal(process.env.CODEX_EXECUTABLE, first.cli);
  assert.equal(readFileSync(config, 'utf8'), configBytes, 'Never rewrite private startup state');
  beforeSpawn = undefined;
  children.at(-1).child.kill('SIGTERM');
  await closed(children.at(-1));
  const beforeRejected = children.length;
  for (const [property, value] of [
    ['sameUser', false],
    ['sameSession', false],
    ['pid', 0],
    ['startedAtUnixMs', 0],
  ]) {
    // Each independent trust input gets a new view, retaining the production
    // backoff of every view instead of clearing private timers or budgets.
    view.close();
    view = makeView();
    const saved = identity[property];
    identity[property] = value;
    await assert.rejects(view.workspace(), workspaceFailure);
    identity[property] = saved;
    assert.equal(
      children.length,
      beforeRejected,
      'Rejected owner must never spawn or return cache',
    );
  }
  view.close();
  view = makeView();
  cliTrusted = false;
  await assert.rejects(view.workspace(), workspaceFailure);
  assert.equal(children.length, beforeRejected, 'Rejected CLI must not fall back to the old path');
  cliTrusted = true;
  view.close();
  view = makeView();
  identity.trustedPublisher = false; // General desktop contract; producer's own guard is separate.
  await view.workspace();
  identity.trustedPublisher = true;
  assert.equal(session.history().runtime.canControl, true);
  assert.deepEqual(ownerInputs, ['fixture-original-input']);
  assert.equal(ownerCommands, 0);
  view.close();
  await closed(children.at(-1));

  // A rejected initialize leaves a real, live reader. Production must retire it
  // before publishing failure or resolving and spawning a replacement.
  const rejectedCli = readerScript(join(directory, 'rejected-reader.exe'), true);
  let selected = rejectedCli;
  let rejectedChild;
  const retry = makeReader(rejectedCli, () => {
    if (rejectedChild) assert(rejectedChild.actualClosed && rejectedChild.deliveredClose);
    return selected;
  });
  await assert.rejects(retry.connect(), (error) => {
    assert.equal(error.message, 'Codex rejected the read request');
    assert(!JSON.stringify(error).includes('fixture-private-detail'));
    return true;
  });
  rejectedChild = children.at(-1);
  assert(rejectedChild.actualClosed && rejectedChild.deliveredClose);
  selected = second.cli;
  await retry.connect();
  assert.equal(retry.executable, second.cli);
  assert.equal(retry.ready, true);
  await assert.rejects(retry.read('turn/start', {}), /Read-only method required/);
  await assert.rejects(retry.read('thread/resume', {}), /Read-only method required/);
  retry.close();
  await closed(children.at(-1));

  // Resolver and spawn errors expose only fixed classifications, never arbitrary
  // code strings, stderr, paths, env or nested causes.
  for (const code of ['EACCES', 'fixture-private-detail']) {
    const denied = makeReader(first.cli, () => {
      throw Object.assign(new Error('fixture-private-detail'), {
        code,
        path: 'fixture-private-detail',
        stderr: 'fixture-private-detail',
        env: { TOKEN: 'fixture-private-detail' },
      });
    });
    const before = children.length;
    await assert.rejects(denied.connect(), (error) =>
      safeFailure(
        error,
        'resolve',
        'CODEX_READER_EXECUTABLE_RESOLUTION_FAILED',
        code === 'EACCES' ? code : 'UNKNOWN',
      ),
    );
    assert.equal(children.length, before);
    assert.equal(
      denied.executable,
      first.cli,
      'Failed resolution cannot partially update executable',
    );
  }
  const absent = makeReader(missing);
  await assert.rejects(absent.connect(), (error) =>
    safeFailure(error, 'spawn', 'CODEX_READER_SPAWN_FAILED', 'ENOENT'),
  );
  assert(children.at(-1).actualClosed && children.at(-1).deliveredClose);

  // Concurrent direct connects share resolution and the real initialization.
  const selectedPath = deferred();
  const resolving = deferred();
  let resolutions = 0;
  const concurrent = makeReader(first.cli, () => {
    resolutions++;
    resolving.resolve();
    return selectedPath.promise;
  });
  const beforeConcurrent = children.length;
  const connecting = concurrent.connect();
  assert.equal(concurrent.connect(), connecting);
  await resolving.promise;
  assert.equal(concurrent.connect(), connecting);
  assert.equal(children.length, beforeConcurrent);
  selectedPath.resolve(second.cli);
  await connecting;
  assert.equal(resolutions, 1);
  assert.equal(children.length, beforeConcurrent + 1);
  concurrent.close();
  await closed(children.at(-1));

  // Closing during async resolution invalidates that start without spawning.
  const pendingPath = deferred();
  const pendingResolution = deferred();
  const closing = makeReader(first.cli, () => {
    pendingResolution.resolve();
    return pendingPath.promise;
  });
  const beforeClosing = children.length;
  const cancelledStart = closing.connect();
  await pendingResolution.promise;
  closing.close();
  pendingPath.resolve(second.cli);
  await assert.rejects(cancelledStart, (error) =>
    safeFailure(error, 'closed', 'CODEX_READER_CLOSED'),
  );
  assert.equal(children.length, beforeClosing);

  // Unknown shutdown cannot refresh trust or spawn. Retry can proceed only once
  // the same child's withheld close notification has actually been delivered.
  let unknownResolutions = 0;
  const unknown = makeReader(second.cli, () => {
    unknownResolutions++;
    return second.cli;
  });
  await unknown.connect();
  const held = children.at(-1);
  held.holdClose = true;
  unknown.close();
  await closed(held);
  const beforeUnknown = children.length;
  globalThis.setTimeout = (callback, ms, ...args) =>
    originalTimeout(callback, ms === 10_000 ? 50 : ms, ...args);
  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      await assert.rejects(unknown.connect(), (error) =>
        safeFailure(error, 'shutdown', 'CODEX_READER_EXIT_UNCONFIRMED'),
      );
      assert.equal(unknownResolutions, 1);
      assert.equal(children.length, beforeUnknown);
    }
  } finally {
    globalThis.setTimeout = originalTimeout;
  }
  held.releaseClose();
  await unknown.connect();
  assert.equal(unknownResolutions, 2);
  assert.equal(children.length, beforeUnknown + 1);
  unknown.close();
  await closed(children.at(-1));

  const methods = readFileSync(log, 'utf8').trim().split('\n');
  assert(methods.includes('project/list') && methods.includes('thread/list'));
  assert(
    methods.every((method) =>
      ['initialize', 'initialized', 'project/list', 'thread/list'].includes(method),
    ),
    'Catalog reconnect never requests a model, resumes an owner or submits an input',
  );
  assert.deepEqual(ownerInputs, ['fixture-original-input']);
  assert.equal(ownerCommands, 0);
  console.log(
    'Desktop workspace CLI refresh fixtures passed (synthetic trust, real subprocesses).',
  );
} finally {
  globalThis.setTimeout = originalTimeout;
  beforeSpawn = undefined;
  view?.close();
  for (const reader of readers) reader.close();
  for (const record of children) {
    record.releaseClose?.();
    if (!record.actualClosed) record.child.kill('SIGTERM');
  }
  try {
    await Promise.all(children.map(closed));
  } finally {
    closePrivateState?.();
    permissions.restore();
    childProcess.spawn = originalSpawn;
    childProcess.execFileSync = originalExec;
    syncBuiltinESMExports();
    Object.defineProperty(process, 'platform', platformDescriptor);
    for (const [key, value] of Object.entries(environment)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    if (children.every((record) => record.actualClosed)) rmSync(directory, { recursive: true });
  }
}
