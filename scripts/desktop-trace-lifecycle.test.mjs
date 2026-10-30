import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  realpathSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { after, before, describe, test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';

// Actual frozen JS transports, synthetic native stdio only. No Windows C#,
// original desktop, Host, model, permission evidence or captured user input.
const repository = fileURLToPath(new URL('../', import.meta.url));
const source = join(repository, 'packages/codex-adapter/src');
const names = [
  'diagnostic-events',
  'windows-process',
  'windows-trace-diagnostics',
  'desktop-private-state-native',
  'desktop-trace-native',
  'desktop-trace-native-worker',
  'desktop-suggestion-read-worker',
  'desktop-suggestion-worker-client',
  'desktop-suggestion-worker-limits',
  'desktop-suggestion-observer',
  'desktop-prediction-trace',
  'desktop-suggestion-store',
  'desktop-native-suggestion-channel',
  'desktop-native-sample-platform',
];
const modes = [
  'normal',
  'storage-error',
  'slow-start',
  'slow-operation',
  'forced-start',
  'forced-operation',
  'gate-start-close',
  'gate-start-forced',
];
const hash = (value) => createHash('sha256').update(value).digest('hex');
const write = (path, value) => writeFileSync(path, value, { mode: 0o600 });
function publishResult(directory, result) {
  const pending = join(directory, 'result.partial.json');
  write(pending, JSON.stringify(result, null, 2));
  renameSync(pending, join(directory, 'result.json'));
}
function preserveRunnerFailure(directory, error) {
  const path = join(directory, 'runner.log');
  if (!existsSync(path))
    write(path, String(error.stack) + '\n' + (error.stdout ?? '') + (error.stderr ?? ''));
}
const events = (log) =>
  existsSync(log)
    ? readFileSync(log, 'utf8')
        .trim()
        .split('\n')
        .filter(Boolean)
        .map((line) => JSON.parse(line))
    : [];
function exists(pid) {
  assert(Number.isSafeInteger(pid) && pid > 0, 'Only recorded fixture child PIDs may be inspected');
  try {
    // Signal zero never kills; zombies remain positive and must fail too.
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    throw error;
  }
}
async function until(check, message, milliseconds) {
  const deadline = performance.now() + milliseconds;
  for (;;) {
    assert(performance.now() < deadline, message);
    const complete = check();
    assert(performance.now() < deadline, message);
    if (complete) return;
    await delay(Math.min(15, Math.max(1, deadline - performance.now())));
  }
}

async function runLifecycleProbe(directory, caseDirectory, env) {
  const phases = [],
    startedAt = performance.now();
  const mark = (event, fields = {}) =>
    phases.push({ event, elapsedMs: performance.now() - startedAt, ...fields });
  let execution,
    child,
    outputTail = '';
  mark('launch');
  try {
    execution = await new Promise((resolve) => {
      child = execFile(
        process.execPath,
        [join(directory, 'probe.mjs'), caseDirectory, caseDirectory.split(/[\\/]/).at(-1)],
        { env, timeout: 15000, windowsHide: true },
        (error, stdout, stderr) => {
          mark('callback', {
            error: error
              ? {
                  name: error.name,
                  code: error.code ?? null,
                  signal: error.signal ?? null,
                  killed: error.killed ?? false,
                }
              : null,
          });
          resolve({ error, stdout, stderr });
        },
      );
      mark('supervision-installed', { timeoutMs: 15000, childPid: child.pid ?? null });
      const kill = child.kill;
      child.kill = function (...args) {
        mark('kill-enter', { signal: args[0] ?? 'SIGTERM' });
        try {
          const result = Reflect.apply(kill, this, args);
          mark('kill-return', { result });
          return result;
        } catch (error) {
          mark('kill-throw', { code: error.code ?? null });
          throw error;
        }
      };
      child.on('exit', (code, signal) => mark('probe-exit', { code, signal }));
      child.on('close', (code, signal) => mark('probe-close', { code, signal }));
      for (const [name, stream] of [
        ['stdin', child.stdin],
        ['stdout', child.stdout],
        ['stderr', child.stderr],
      ]) {
        stream.on('end', () => mark('stdio-end', { name }));
        stream.on('close', () => mark('stdio-close', { name }));
      }
      child.stdout.on('data', (chunk) => {
        outputTail += chunk.toString('utf8');
        if (outputTail.length > 65536) {
          mark('invalid-phase-output');
          outputTail = '';
          return;
        }
        for (;;) {
          const end = outputTail.indexOf('\n');
          if (end < 0) break;
          const line = outputTail.slice(0, end);
          outputTail = outputTail.slice(end + 1);
          try {
            const value = JSON.parse(line);
            if (value.lifecycle) mark('probe-phase', value.lifecycle);
          } catch {
            mark('unrecognized-probe-line');
          }
        }
      });
    });
    write(
      join(caseDirectory, 'runner.log'),
      execution.error
        ? String(execution.error.stack) + '\n' + execution.stdout + execution.stderr
        : execution.stdout + execution.stderr,
    );
    return execution;
  } finally {
    const path = join(caseDirectory, 'result.json');
    let resultState = 'missing';
    if (existsSync(path)) {
      const bytes = readFileSync(path);
      if (!bytes.length) resultState = 'empty';
      else {
        try {
          JSON.parse(bytes.toString());
          resultState = 'complete';
        } catch {
          resultState = 'invalid-json';
        }
      }
    } else if (existsSync(join(caseDirectory, 'result.partial.json'))) resultState = 'partial';
    const report = { schemaVersion: 1, syntheticOnly: true, timeoutMs: 15000, resultState, phases };
    const pending = join(caseDirectory, 'supervisor-report.partial.json');
    write(pending, JSON.stringify(report, null, 2));
    renameSync(pending, join(caseDirectory, 'supervisor-report.json'));
  }
}

async function syntheticChild() {
  const { appendFileSync } = await import('node:fs');
  const { createInterface } = await import('node:readline');
  const mode = process.env.TRACE_LIFECYCLE_MODE;
  const record = (value) => {
    const line = JSON.stringify(value) + '\n';
    appendFileSync(process.env.TRACE_LIFECYCLE_LOG, line);
    process.stderr.write(line);
  };
  record({ event: 'started', pid: process.pid });
  process.once('exit', () => record({ event: 'exit' }));
  // Failure containment only; passing assertions must finish before this fires.
  setTimeout(() => process.exit(92), 12000).unref();
  const send = (value) => process.stdout.write(JSON.stringify(value) + '\n');
  const ready = () => {
    record({ event: 'ready' });
    send({ ready: true });
  };
  if (mode === 'slow-start') setTimeout(ready, 6000);
  else ready();
  const input = createInterface({ input: process.stdin });
  input.on('line', (line) => {
    if (line === 'QUIT') {
      record({ event: 'quit' });
      if (mode !== 'slow-operation') process.exit(0);
      return;
    }
    record({ event: 'request', kind: line[0] });
    if (mode === 'slow-operation' && line === 'P') return;
    if (line === 'P')
      send({
        pid: 123,
        startedAtUnixMs: 1,
        traceRoot: process.argv[3],
        executors: [{ pid: 124, parentPid: 123, startedAtUnixMs: 1, traceRoot: process.argv[3] }],
      });
    else send({ error: 'SYNTHETIC_REJECTED' });
  });
  input.once('close', () => {
    record({ event: 'eof' });
    if (mode !== 'slow-operation') process.exit(0);
  });
  if (mode === 'slow-operation') setInterval(() => {}, 1000);
}

async function bootstrap() {
  Object.defineProperty(process, 'platform', { value: 'win32' });
  const { default: assert } = await import('node:assert/strict');
  const { default: child } = await import('node:child_process');
  const { appendFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const { syncBuiltinESMExports } = await import('node:module');
  const { getWindowsProcessGate } = await import('./windows-process.js');
  const { threadId, parentPort } = await import('node:worker_threads');
  const wait = Atomics.wait;
  let recorded = false;
  Atomics.wait = function (...args) {
    if (!recorded && args[0]?.buffer === getWindowsProcessGate()) {
      recorded = true;
      appendFileSync(
        process.env.TRACE_LIFECYCLE_LOG,
        JSON.stringify({ event: 'gate-wait', threadId }) + '\n',
      );
    }
    return Reflect.apply(wait, this, args);
  };
  const executable = fileURLToPath(new URL('./child.mjs', import.meta.url));
  const original = child.spawn;
  child.spawn = function (command, args, options) {
    assert.equal(command, executable);
    assert.deepEqual(args, [
      'serve',
      join(process.env.TRACE_LIFECYCLE_DIRECTORY, 'traces'),
      '123',
      '1',
      'synthetic',
    ]);
    assert.equal(options.shell, undefined);
    assert.equal(
      Atomics.load(new Int32Array(getWindowsProcessGate()), 0),
      threadId + 1,
      'Trace native spawn must remain inside the production gate',
    );
    const value = Reflect.apply(original, this, [process.execPath, [executable, ...args], options]);
    const report = (event) =>
      parentPort.postMessage({ ...event, requestKind: event.kind, kind: 'fixture-lifecycle' });
    report({ event: 'spawn', pid: value.pid });
    let tail = '';
    value.stderr.on('data', (chunk) => {
      tail += chunk.toString('utf8');
      for (;;) {
        const end = tail.indexOf('\n');
        if (end < 0) break;
        const row = JSON.parse(tail.slice(0, end));
        tail = tail.slice(end + 1);
        report(row);
      }
    });
    value.once('exit', (code, signal) =>
      report({ event: 'child-exit', pid: value.pid, code, signal }),
    );
    value.once('close', (code, signal) =>
      report({ event: 'child-close', pid: value.pid, code, signal }),
    );
    for (const [name, stream] of [
      ['stdin', value.stdin],
      ['stdout', value.stdout],
      ['stderr', value.stderr],
    ])
      stream.once('close', () => report({ event: 'child-stdio-close', pid: value.pid, name }));

    appendFileSync(
      process.env.TRACE_LIFECYCLE_LOG,
      JSON.stringify({ event: 'spawn', pid: value.pid }) + '\n',
    );
    return value;
  };
  syncBuiltinESMExports();
}

async function probe() {
  const { monitorPrivateStateWorkers } = await import('./transport-monitor.mjs');
  const observed = [];
  const mark = (event) => {
    const row = { ...event, atMs: performance.now() };
    observed.push(row);
    process.stdout.write(JSON.stringify({ lifecycle: row }) + '\n');
  };
  const monitor = monitorPrivateStateWorkers(
    new URL('./desktop-trace-native-worker.js', import.meta.url),
    process.argv[2] + '/events.jsonl',
    { onEvent: mark },
  );
  const { DesktopSuggestionWorkerClient } = await import('./desktop-suggestion-worker-client.js');
  const [directory, mode] = process.argv.slice(2);
  const log = join(directory, 'events.jsonl');
  write(log, '');
  process.env.TRACE_LIFECYCLE_DIRECTORY = directory;
  process.env.TRACE_LIFECYCLE_LOG = log;
  process.env.TRACE_LIFECYCLE_MODE = mode.replace('forced-', 'slow-');
  const workspace = join(directory, 'workspace'),
    traceRoot = join(directory, 'traces');
  mkdirSync(workspace, { mode: 0o700 });
  mkdirSync(traceRoot, { mode: 0o700 });
  const state = join(directory, 'state');
  if (mode === 'storage-error') write(state, 'blocking regular file');
  else mkdirSync(state, { mode: 0o700 });
  const client = new DesktopSuggestionWorkerClient(
    {
      directory: state,
      root: workspace,
      traceRoot,
      target: {
        hostId: 'synthetic-host',
        workspaceId: 'synthetic-workspace',
        runtimeId: 'desktop-runtime',
        agentId: 'codex',
      },
    },
    new URL('./desktop-suggestion-read-worker.js', import.meta.url),
  );
  client.start();
  const owner = client.worker;
  let exited = false,
    failed = false,
    stateCount = 0,
    ownerError,
    failure,
    closeAt,
    ownerExitAt;
  owner.once('exit', () => {
    exited = true;
    ownerExitAt = performance.now();
    mark({ event: 'owner-exit' });
  });
  owner.on('error', (error) => {
    ownerError = error;
  });
  owner.on('message', (value) => {
    if (value.kind === 'failed') failed = true;
    if (value.kind === 'state') stateCount++;
  });
  const records = () => observed;
  const childrenRetired = () =>
    records()
      .filter((v) => v.event === 'spawn')
      .every(
        ({ pid }) =>
          records().some((v) => v.event === 'child-exit' && v.pid === pid) &&
          records().some((v) => v.event === 'child-close' && v.pid === pid) &&
          new Set(
            records()
              .filter((v) => v.event === 'child-stdio-close' && v.pid === pid)
              .map((v) => v.name),
          ).size === 3,
      );
  try {
    await until(
      () => {
        if (ownerError) throw ownerError;
        return records().some((v) => v.event === 'started');
      },
      'Fixture did not start',
      5000,
    );
    if (mode.endsWith('operation'))
      await until(
        () => records().some((v) => v.event === 'request'),
        'Native probe did not start',
        5000,
      );
    else if (!mode.endsWith('start'))
      await until(() => stateCount > 0, 'Reader sent no state', 5000);
    if (mode.endsWith('start')) {
      assert(!records().some((v) => v.event === 'ready'), 'Must close before the real 6s ready');
      assert.equal(stateCount, 0);
    }
    closeAt = performance.now();
    mark({ event: 'close-requested' });
    if (mode.startsWith('forced-')) {
      const terminated = owner.terminate();
      client.close();
      await terminated;
    } else client.close();
    await until(
      () => exited && monitor.active.size === 0 && childrenRetired(),
      'Reader/native transport/child must retire within the original 2500ms grace',
      Math.max(1, 2500 - (performance.now() - closeAt)),
    );
    if (ownerError) throw ownerError;
  } catch (error) {
    failure = error;
  } finally {
    client.close();
    try {
      await until(
        () => exited && monitor.active.size === 0 && childrenRetired(),
        'Cleanup incomplete',
        3000,
      );
    } catch (error) {
      failure ??= error;
    }
    const all = records(),
      children = all.filter((v) => v.event === 'spawn');
    const result = {
      mode,
      failed,
      stateCount,
      ownerExitAfterCloseMs:
        closeAt === undefined || ownerExitAt === undefined ? null : ownerExitAt - closeAt,
      transportsExited: monitor.active.size === 0,
      children: children.map((v) => ({
        pid: v.pid,
        gone: childrenRetired(),
        pidAbsent: !exists(v.pid),
      })),
      events: all.map((v) => v.event),
      error: failure?.message,
    };
    publishResult(directory, result);
    monitor.restore();
    if (!failure) {
      assert.equal(children.length, 1);
      assert.equal(all.find((v) => v.event === 'started')?.pid, children[0].pid);
      assert(result.children.every((v) => v.gone && v.pidAbsent));
      assert.equal(result.transportsExited, true);
      if (mode.endsWith('start')) assert(!result.events.includes('ready'));
      if (mode.endsWith('operation'))
        assert(result.events.includes('quit'), 'The hanging helper must receive and ignore QUIT');
      if (mode === 'slow-operation') assert(failed, 'Hung probe must fail closed');
    }
  }
  if (failure) throw failure;
}

async function gateProbe() {
  const { Worker } = await import('node:worker_threads');
  const { getWindowsProcessGate, registerWindowsProcessWorker } =
    await import('./windows-process.js');
  const { monitorPrivateStateWorkers } = await import('./transport-monitor.mjs');
  const { DesktopSuggestionWorkerClient } = await import('./desktop-suggestion-worker-client.js');
  const [directory, mode] = process.argv.slice(2);
  const log = join(directory, 'events.jsonl');
  write(log, '');
  process.env.TRACE_LIFECYCLE_DIRECTORY = directory;
  process.env.TRACE_LIFECYCLE_LOG = log;
  process.env.TRACE_LIFECYCLE_MODE = 'normal';
  const control = new Int32Array(new SharedArrayBuffer(8));
  const holder = new Worker(new URL('./gate-actor.mjs', import.meta.url), {
    execArgv: [],
    workerData: {
      gateURL: new URL('./windows-process.js', import.meta.url).href,
      windowsProcessGate: getWindowsProcessGate(),
      control: control.buffer,
      mode: 'hold',
      holdMs: 8000,
    },
  });
  registerWindowsProcessWorker(holder);
  const holderExit = new Promise((resolve) => holder.once('exit', resolve));
  await new Promise((resolve, reject) => {
    holder.once('message', resolve);
    holder.once('error', reject);
  });
  Atomics.store(control, 0, 1);
  Atomics.notify(control, 0);
  const token = holder.threadId + 1;
  const monitor = monitorPrivateStateWorkers(
    new URL('./desktop-trace-native-worker.js', import.meta.url),
    log,
  );
  const workspace = join(directory, 'workspace'),
    traceRoot = join(directory, 'traces');
  mkdirSync(workspace, { mode: 0o700 });
  mkdirSync(traceRoot, { mode: 0o700 });
  const client = new DesktopSuggestionWorkerClient({
    directory,
    root: workspace,
    traceRoot,
    target: {
      hostId: 'synthetic-host',
      workspaceId: 'synthetic-workspace',
      runtimeId: 'desktop-runtime',
      agentId: 'codex',
    },
  });
  let duration;
  try {
    client.start();
    const owner = client.worker;
    let exited = false,
      ownerError;
    owner.once('exit', () => {
      exited = true;
    });
    owner.once('error', (error) => {
      ownerError = error;
    });
    await until(
      () => {
        if (ownerError) throw ownerError;
        return events(log).some((row) => row.event === 'gate-wait');
      },
      'Trace transport must be waiting in the real gate before cancellation',
      5000,
    );
    const started = performance.now();
    if (mode === 'gate-start-forced') await owner.terminate();
    client.close();
    await until(
      () => exited && monitor.active.size === 0,
      'Reader and native transport must exit within the original 2500ms grace',
      Math.max(1, 2500 - (performance.now() - started)),
    );
    duration = performance.now() - started;
    assert(duration < 2500);
    assert.equal(
      Atomics.load(new Int32Array(getWindowsProcessGate()), 0),
      token,
      'Unrelated holder must remain live and keep its own gate',
    );
    assert.equal(events(log).filter((row) => row.event === 'spawn').length, 0);
    Atomics.store(control, 1, 1);
    Atomics.notify(control, 1);
    assert.equal(await holderExit, 0);
    await delay(80);
    assert.equal(
      events(log).filter((row) => row.event === 'spawn' || row.event === 'request').length,
      0,
      'Cancelled reader must not spawn or replay after gate release',
    );
    write(
      join(directory, 'result.json'),
      JSON.stringify(
        {
          mode,
          children: [],
          ownerExitAfterCloseMs: duration,
          holderSurvived: true,
          cancelledWithoutReplay: true,
        },
        null,
        2,
      ),
    );
  } finally {
    client.close();
    await holder.terminate();
    monitor.restore();
  }
}

const platform = `import {fileURLToPath} from 'node:url';
let backend;
export const installWindowsTraceBackend=value=>{backend=value};
export const inspectWindowsPipe=()=>({pid:123,startedAtUnixMs:1,executable:'synthetic'});
export const windowsTraceNativeExecutable=()=>fileURLToPath(new URL('./child.mjs',import.meta.url));
export const inspectWindowsTraceInstance=()=>backend.inspect();
// Native result capture is disabled in this legacy transport fixture.
export const inspectWindowsInstallation=()=>{throw Error('Native result capture not provided by this fixture')};
export const windowsSuggestionProducerIdentity=()=>{throw Error('Native result capture not provided by this fixture')};
export const windowsPowerShellEnvironment=()=>{throw Error('Native result capture not provided by this fixture')};
export const windowsTraceInstanceReady=()=>true;
export const windowsTraceSnapshot=(...args)=>backend.snapshot(...args);
export const windowsTraceSnapshotBatchAvailable=()=>backend?.snapshotMany!==undefined;
export const windowsTraceSnapshotMany=(...args)=>backend.snapshotMany(...args);
import {mkdirSync,openSync,writeFileSync,renameSync} from 'node:fs';
export const makePrivateDirectorySync=path=>mkdirSync(path,{recursive:true,mode:0o700});
export {openSync as openPrivateFileSync,writeFileSync as writePrivateFileSync,renameSync as renamePrivateFileSync};
export const assertPrivateFile=()=>{};
export const protectWindowsStateDirectory=()=>{};
export const protectWindowsStateFile=()=>{};
`;

describe(
  'frozen Trace transport lifecycle (synthetic Node native process)',
  { concurrency: false },
  () => {
    let directory;
    const sourceHashes = new Map(),
      frozenHashes = new Map();
    function unchanged() {
      for (const [path, expected] of [...sourceHashes, ...frozenHashes])
        assert.equal(hash(readFileSync(path)), expected, 'Frozen lifecycle input changed: ' + path);
    }
    before(() => {
      mkdirSync(join(repository, '.local'), { recursive: true, mode: 0o700 });
      directory = realpathSync(mkdtempSync(join(repository, '.local/desktop-trace-lifecycle-')));
      chmodSync(directory, 0o700);
      sourceHashes.set(
        fileURLToPath(import.meta.url),
        hash(readFileSync(new URL(import.meta.url))),
      );
      const protocol = join(repository, 'packages/protocol/dist');
      // Bind the actual built protocol closure; never guess a node_modules layout.
      for (const name of readdirSync(protocol).filter((name) => name.endsWith('.js'))) {
        const path = join(protocol, name);
        sourceHashes.set(path, hash(readFileSync(path)));
      }
      for (const name of names) {
        const path = join(source, name + '.ts'),
          input = readFileSync(path, 'utf8');
        sourceHashes.set(path, hash(input));
        const compiled = ts
          .transpileModule(input, {
            compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
          })
          .outputText.replace(
            /(['"])@codex-manage\/protocol\1/g,
            JSON.stringify(pathToFileURL(join(protocol, 'index.js')).href),
          );
        const prefix =
          name === 'desktop-trace-native-worker'
            ? "import './native-bootstrap.mjs';\n"
            : name === 'desktop-suggestion-read-worker'
              ? "Object.defineProperty(process,'platform',{value:'win32'});\n"
              : '';
        write(join(directory, name + '.js'), prefix + compiled);
      }
      write(join(directory, 'package.json'), '{"type":"module"}');
      write(join(directory, 'desktop-platform.js'), platform);
      write(join(directory, 'child.mjs'), `await (${syntheticChild.toString()})();`);
      write(join(directory, 'native-bootstrap.mjs'), `await (${bootstrap.toString()})();`);
      for (const [name, output] of [
        ['windows-process-fixture', 'gate-actor'],
        ['windows-private-state-monitor', 'transport-monitor'],
      ]) {
        const path = fileURLToPath(new URL('./fixtures/' + name + '.mjs', import.meta.url));
        const input = readFileSync(path, 'utf8');
        sourceHashes.set(path, hash(input));
        write(join(directory, output + '.mjs'), input);
      }
      const imports = `import assert from 'node:assert/strict';
import {existsSync,readFileSync,mkdirSync,writeFileSync,renameSync} from 'node:fs';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
`;
      write(
        join(directory, 'probe.mjs'),
        imports +
          [write, publishResult, events, exists, until]
            .map((fn) =>
              fn.name === 'write' || fn.name === 'events'
                ? `const ${fn.name}=${fn.toString()};`
                : fn.toString(),
            )
            .join('\n') +
          `\nif(process.argv[3].startsWith('gate-'))await (${gateProbe.toString()})();else await (${probe.toString()})();`,
      );
      for (const name of readdirSync(directory)) {
        const path = join(directory, name);
        frozenHashes.set(path, hash(readFileSync(path)));
      }
      write(
        join(directory, 'inputs.json'),
        JSON.stringify(
          { source: Object.fromEntries(sourceHashes), frozen: Object.fromEntries(frozenHashes) },
          null,
          2,
        ),
      );
      unchanged();
    });
    after(() => unchanged());
    for (const mode of modes)
      test(mode, async (t) => {
        unchanged();
        const caseDirectory = join(directory, mode),
          log = join(caseDirectory, 'events.jsonl');
        mkdirSync(caseDirectory, { mode: 0o700 });
        t.diagnostic(caseDirectory);
        // Disposable Node process: only OS location variables, no Host/Codex secrets
        // or inherited preloads. The production broker still chooses its own flags.
        const env = Object.fromEntries(
          Object.entries(process.env).filter(([key]) =>
            /^(PATH|SYSTEMROOT|WINDIR|TEMP|TMP|USERPROFILE|HOME|LOCALAPPDATA|APPDATA)$/i.test(key),
          ),
        );
        let failure;
        try {
          const execution = await runLifecycleProbe(directory, caseDirectory, env);
          if (execution.error) throw execution.error;
        } catch (error) {
          failure = error;
          preserveRunnerFailure(caseDirectory, error);
        } finally {
          if (existsSync(log)) chmodSync(log, 0o600);
          const children = events(log).filter((v) => v.event === 'spawn');
          try {
            await until(
              () => children.every((v) => !exists(v.pid)),
              'Fixture child survives disposable supervisor',
              3000,
            );
          } catch (error) {
            failure ??= error;
            // Fixed OS diagnostic, known child PIDs only; absence never means gone.
            if (process.platform !== 'win32' && existsSync('/bin/ps')) {
              for (const { pid } of children)
                if (exists(pid)) {
                  try {
                    write(
                      join(caseDirectory, `child-${pid}.txt`),
                      execFileSync('/bin/ps', ['-p', String(pid), '-o', 'stat=,ppid='], {
                        encoding: 'utf8',
                      }),
                    );
                  } catch {
                    /* Keep the original failure even if diagnostic collection fails. */
                  }
                }
            }
          }
          unchanged();
        }
        if (failure) throw failure;
        const supervisor = JSON.parse(
          readFileSync(join(caseDirectory, 'supervisor-report.json'), 'utf8'),
        );
        assert.equal(supervisor.resultState, 'complete');
        assert(supervisor.phases.some((v) => v.event === 'probe-exit'));
        assert(supervisor.phases.some((v) => v.event === 'probe-close'));
        const result = JSON.parse(readFileSync(join(caseDirectory, 'result.json'), 'utf8'));
        assert.equal(result.error, undefined);
        assert.equal(result.children.length, mode.startsWith('gate-') ? 0 : 1);
        assert(result.children.every((v) => v.gone));
        if (mode.startsWith('gate-')) {
          assert.equal(result.holderSurvived, true);
          assert.equal(result.cancelledWithoutReplay, true);
        }
      });
  },
);

test('strict lifecycle deadline rejects a condition that only becomes true after a blocking check', async () => {
  await assert.rejects(
    until(
      () => {
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 25);
        return true;
      },
      'Late true is not timely completion',
      5,
    ),
    /Late true/,
  );
});

test('parent preserves an incomplete child report and actual exit/close evidence', async () => {
  mkdirSync(join(repository, '.local'), { recursive: true, mode: 0o700 });
  const directory = mkdtempSync(join(repository, '.local/lifecycle-report-failure-'));
  const caseDirectory = join(directory, 'partial');
  mkdirSync(caseDirectory);
  write(
    join(directory, 'probe.mjs'),
    "import{writeFileSync}from'node:fs';import{join}from'node:path';writeFileSync(join(process.argv[2],'result.partial.json'),'{');console.log('fixed-stdout-marker');console.error('fixed-stderr-marker');process.exitCode=2;",
  );
  const { error } = await runLifecycleProbe(directory, caseDirectory, {});
  assert.equal(error.code, 2);
  preserveRunnerFailure(caseDirectory, error);
  const log = readFileSync(join(caseDirectory, 'runner.log'), 'utf8');
  assert(log.includes('fixed-stdout-marker'));
  assert(log.includes('fixed-stderr-marker'));
  const report = JSON.parse(readFileSync(join(caseDirectory, 'supervisor-report.json'), 'utf8'));
  assert.equal(report.resultState, 'partial');
  assert(report.phases.some((v) => v.event === 'probe-exit' && v.code === 2));
  assert(report.phases.some((v) => v.event === 'probe-close' && v.code === 2));
  assert.equal(readFileSync(join(caseDirectory, 'result.partial.json'), 'utf8'), '{');
});
