import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { join, win32 } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import ts from 'typescript';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const source = join(repository, 'packages/codex-adapter/src');
const hash = (value) => createHash('sha256').update(value).digest('hex');
const write = (path, value) => fs.writeFileSync(path, value, { mode: 0o600 });
const run = promisify(execFile);

// Actual Node filesystem, mapped into a disposable synthetic Windows namespace.
// Native replies are synthetic: no DACL/Windows success is claimed on macOS.
export async function batchSnapshotFixture(run, senderFactory) {
  const fixture = await frozenBatchFixture();
  const drive = join(fixture.directory, 'drive');
  fs.mkdirSync(join(drive, 'fixture', 'traces'), { recursive: true, mode: 0o700 });
  write(
    join(fixture.directory, 'fs.mjs'),
    `import fs from 'node:fs';
import {join} from 'node:path';
export const local=path=>join(${JSON.stringify(drive)},...path.slice(3).split('\\\\'));
export const constants=fs.constants,closeSync=fs.closeSync,fstatSync=fs.fstatSync,readSync=fs.readSync;
export const lstatSync=(path,...args)=>fs.lstatSync(local(path),...args);
export const realpathSync=path=>{if(fs.realpathSync(local(path))!==local(path))throw Error('alias');return path};
export const openSync=(path,...args)=>fs.openSync(local(path),...args);
export const opendirSync=path=>fs.opendirSync(local(path));
`,
  );
  const platform = await import(pathToFileURL(join(fixture.directory, 'desktop-platform.js')));
  const module = await import(
    pathToFileURL(join(fixture.directory, 'desktop-prediction-trace.js'))
  );
  const counts = { single: 0, batch: 0, snapshots: 0, paths: [] };
  let changed;
  const local = (path) => join(drive, ...path.slice(3).split('\\'));
  const snapshot = (_root, path, maximum = 0) => {
    counts.snapshots++;
    counts.paths.push(path);
    const paths = [];
    for (let at = path; ; at = win32.dirname(at)) {
      paths.unshift(at);
      if (at === win32.parse(at).root) break;
    }
    const chain = paths.map((part) => {
      const stat = fs.lstatSync(local(part), { bigint: true });
      assert(!stat.isSymbolicLink());
      const inode = stat.ino;
      return `1:${inode >> 32n}:${inode & 0xffffffffn}:0:0:${hash(String(stat.mode)).slice(0, 16)}`;
    });
    return {
      identity: hash(chain.join('|')),
      chain,
      bytes: maximum ? fs.readFileSync(local(path)).subarray(0, maximum) : Buffer.alloc(0),
    };
  };
  const send = (items) => {
    counts.batch++;
    return items.map((item, index) => {
      changed?.('before', index, item);
      const result = snapshot('C:\\fixture\\traces', item.path, item.maximum);
      changed?.('after', index, item);
      return result;
    });
  };
  const sender = senderFactory?.(
    'C:\\fixture\\traces',
    (request) => send(request.items),
    () => {},
  );
  platform.installWindowsTraceBackend({
    snapshot(...args) {
      counts.single++;
      return snapshot(...args);
    },
    snapshotMany(root, items) {
      return sender ? sender.read(root, items) : send(items);
    },
  });
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { value: 'win32' });
  try {
    await run({
      TraceSnapshot: module.TraceSnapshot,
      root: 'C:\\fixture\\traces',
      local,
      counts,
      change: (callback) => {
        changed = callback;
      },
      reset() {
        sender?.beginPhase();
        counts.single = 0;
        counts.batch = 0;
        counts.snapshots = 0;
        counts.paths = [];
      },
    });
  } finally {
    Object.defineProperty(process, 'platform', descriptor);
    fixture.unchanged();
  }
}

async function stdioChild() {
  const { createInterface } = await import('node:readline');
  const { appendFileSync } = await import('node:fs');
  const { createHash } = await import('node:crypto');
  const { win32 } = await import('node:path');
  const mode = process.env.TRACE_BATCH_MODE;
  const record = (event) =>
    appendFileSync(process.env.TRACE_BATCH_LOG, JSON.stringify({ event, pid: process.pid }) + '\n');
  const digest = (value) => createHash('sha256').update(value).digest('base64');
  const send = (value) => process.stdout.write(JSON.stringify(value) + '\n');
  const snapshot = (item) => {
    const paths = [];
    for (let at = item.path; ; at = win32.dirname(at)) {
      paths.unshift(at);
      if (at === win32.parse(at).root) break;
    }
    const chain = paths.map(
      (_, index) => `1:0:${index + 1}:0:0:${digest('synthetic-private-ACL')}`,
    );
    return {
      identity: digest(chain.join('|')),
      chain,
      bytes: Buffer.alloc(item.maximum, 65).toString('base64'),
    };
  };
  record('started');
  send({ ready: true });
  setTimeout(() => process.exit(92), 6000).unref();
  const input = createInterface({ input: process.stdin });
  let previous;
  let batchCount = 0;
  input.on('line', (line) => {
    if (line === 'QUIT') {
      record('quit');
      process.exit(0);
    }
    record('request');
    assertLine: {
      if (!line.startsWith('B\t')) break assertLine;
      const request = JSON.parse(Buffer.from(line.slice(2), 'base64'));
      batchCount++;
      const value = {
        schemaVersion: 1,
        kind: 'snapshot-many',
        id: request.id,
        items: request.items.map((item, index) => ({ index, snapshot: snapshot(item) })),
      };
      if (mode === 'stale-while-next' && previous) {
        send(previous);
        return;
      }
      previous = value;
      if (mode === 'wrong-id') value.id++;
      if (mode === 'wrong-kind') value.kind = 'snapshot';
      if (mode === 'reordered') value.items.reverse();
      if (mode === 'short') value.items.pop();
      if (mode === 'extra-item') value.items.push(value.items[0]);
      if (mode === 'extra-field') value.trusted = true;
      if (mode === 'bad-base64') value.items[0].snapshot.bytes = '!!!!';
      if (mode === 'wrong-chain') value.items[0].snapshot.chain.pop();
      if (mode === 'extra-snapshot-field') value.items[0].snapshot.trusted = true;
      if (mode === 'huge-bytes') value.items[0].snapshot.bytes = 'QQ==';
      if (mode === 'duplicate-key') {
        process.stdout.write(JSON.stringify(value).replace('"id":1', '"id":2,"id":1') + '\n');
        return;
      }
      if (mode === 'two-frames') {
        process.stdout.write(JSON.stringify(value) + '\n' + JSON.stringify(value) + '\n');
        return;
      }
      if (mode === 'oversized') {
        process.stdout.write(' '.repeat(256 * 1024 + 1) + '\n');
        return;
      }
      if (mode === 'second-failure') {
        record('first-item');
        send({ error: 'SYNTHETIC_SECOND_ITEM_REJECTED' });
        return;
      }
      if (mode === 'batch-timeout' || (mode === 'cold-second-timeout' && batchCount === 2)) {
        setTimeout(() => record('first-item'), 450);
        setTimeout(() => send(value), 900);
        return;
      }
      if (mode === 'budget') {
        setTimeout(() => send(value), 180);
        return;
      }
      if (mode === 'cold-shared-budget') {
        setTimeout(() => send(value), 200);
        return;
      }
      if (mode === 'owner-close') {
        setTimeout(() => send(value), 900);
        return;
      }
      send(value);
      if (mode === 'duplicate-late') setTimeout(() => send(value), 100);
      return;
    }
    send({ error: 'FIXED_BATCH_ONLY' });
  });
  input.once('close', () => {
    record('eof');
    process.exit(0);
  });
}

async function transportOwner() {
  const { parentPort, workerData } = await import('node:worker_threads');
  const { createWindowsTraceBackend } = await import('./desktop-trace-native.js');
  const root = workerData.root;
  const backend = await createWindowsTraceBackend(
    { pid: 123, startedAtUnixMs: 1, executable: 'C:\\fixture\\codex.exe' },
    root,
  );
  let passed = false,
    error,
    identities,
    firstPassed;
  const started = performance.now();
  const originalNow = Date.now;
  const originalWait = Atomics.wait;
  const timing =
    workerData.mode === 'batch-timeout'
      ? { timeOrigin: performance.timeOrigin, waits: [] }
      : undefined;
  if (timing)
    Atomics.wait = function (view, index, expected, timeout) {
      const sample = { entered: performance.now(), timeout, before: Atomics.load(view, index) };
      timing.waits.push(sample);
      try {
        const status = Reflect.apply(originalWait, this, [view, index, expected, timeout]);
        sample.returned = performance.now();
        sample.status = status;
        sample.after = Atomics.load(view, index);
        return status;
      } catch (error) {
        sample.threwAt = performance.now();
        throw error;
      }
    };
  try {
    backend.budget(1800);
    if (workerData.mode === 'cold-shared-budget') backend.budget(300);
    if (workerData.mode === 'clock-forward') Date.now = () => originalNow() + 60_000;
    if (workerData.mode === 'clock-backward') {
      backend.budget(100);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 120);
      Date.now = () => originalNow() - 60_000;
    }
    if (workerData.mode === 'budget')
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1680);
    if (workerData.mode === 'closed') backend.close();
    const items = Array.from({ length: workerData.itemCount }, (_, index) =>
      index % 2 === 0 ? { path: root, maximum: 0 } : { path: root + '\\one.txt', maximum: 4 },
    );
    if (timing) timing.readEntered = performance.now();
    const values = backend.backend.snapshotMany(root, items);
    if (timing) timing.readReturned = performance.now();
    passed = values.length === items.length && values[1].bytes.toString() === 'AAAA';
    firstPassed = passed;
    identities = values.map((value) => value.identity);
    if (workerData.mode === 'stale-while-next' || workerData.mode === 'duplicate-late') {
      passed = false;
      if (workerData.mode === 'duplicate-late') await new Promise((done) => setTimeout(done, 200));
      backend.backend.snapshotMany(root, items);
      passed = true;
    }
  } catch (failure) {
    if (timing) timing.readThrew = performance.now();
    error = failure.message;
  } finally {
    Atomics.wait = originalWait;
    Date.now = originalNow;
    if (timing) timing.closeEntered = performance.now();
    try {
      backend.close();
    } finally {
      if (timing) timing.closeReturned = performance.now();
    }
  }
  parentPort.postMessage({
    passed,
    error,
    identities,
    firstPassed,
    elapsed: performance.now() - started,
    timing,
  });
  parentPort.close();
}

async function transportMain() {
  const assert = (await import('node:assert/strict')).default;
  const fs = (await import('node:fs')).default;
  const { join } = await import('node:path');
  const { Worker } = await import('node:worker_threads');
  const { setTimeout: delay } = await import('node:timers/promises');
  const { registerWindowsTraceTransport } = await import('./desktop-private-state-native.js');
  const { getWindowsProcessGate, registerWindowsProcessWorker } =
    await import('./windows-process.js');
  const { monitorPrivateStateWorkers } = await import('./monitor.mjs');
  const directory = process.argv[2],
    mode = process.argv[3];
  process.env.TRACE_BATCH_MODE = mode;
  process.env.TRACE_BATCH_LOG = join(directory, 'events.jsonl');
  const rows = () =>
    fs.existsSync(process.env.TRACE_BATCH_LOG)
      ? fs
          .readFileSync(process.env.TRACE_BATCH_LOG, 'utf8')
          .trim()
          .split('\n')
          .filter(Boolean)
          .map(JSON.parse)
      : [];
  const until = async (check) => {
    const end = Date.now() + 4000;
    while (!check()) {
      assert(Date.now() < end, 'Batch fixture did not retire');
      await delay(10);
    }
  };
  const itemCount = Number(process.argv[4]);
  assert(
    itemCount === 2 || itemCount === 8 || itemCount === 64,
    'Fixed synthetic batch width required',
  );
  registerWindowsTraceTransport();
  const monitor = monitorPrivateStateWorkers(
    new URL('./desktop-trace-native-worker.js', import.meta.url),
    join(directory, 'workers.jsonl'),
  );
  const owner = new Worker(new URL('./owner.mjs', import.meta.url), {
    execArgv: [],
    workerData: {
      root: process.env.TRACE_BATCH_ROOT ?? 'C:\\fixture\\traces',
      mode,
      itemCount,
      windowsProcessGate: getWindowsProcessGate(),
    },
  });
  registerWindowsProcessWorker(owner);
  let answer;
  const completed = new Promise((resolve, reject) => {
    owner.once('message', (value) => {
      answer = value;
    });
    owner.once('error', reject);
    owner.once('exit', resolve);
  });
  try {
    if (mode === 'owner-close') {
      await until(() => rows().some((row) => row.event === 'request'));
      await owner.terminate();
    }
    await completed;
    await until(() => monitor.active.size === 0);
    for (const row of rows().filter((row) => row.event === 'started')) {
      let alive;
      try {
        process.kill(row.pid, 0);
        alive = true;
      } catch (error) {
        if (error.code !== 'ESRCH') throw error;
      }
      assert(!alive, 'Owned synthetic child still alive or zombie');
    }
    const result = {
      answer,
      requests: rows().filter((row) => row.event === 'request').length,
      partialExecuted: rows().some((row) => row.event === 'first-item'),
      allRetired: true,
    };
    if (mode === 'batch-timeout') {
      result.nativeTimers = [];
      result.diagnosticIncomplete = false;
      try {
        const files = fs
          .readdirSync(directory)
          .filter((name) => /^native-timer-[0-9]+\.json$/.test(name));
        if (files.length !== 1) result.diagnosticIncomplete = true;
        for (const name of files.slice(0, 2)) {
          const path = join(directory, name);
          if (fs.statSync(path).size > 8192) throw new Error('Bounded timer diagnostics exceeded');
          const value = JSON.parse(fs.readFileSync(path, 'utf8'));
          const finite = (number) =>
            typeof number === 'number' && Number.isFinite(number) && number >= 0;
          if (
            value.version !== 1 ||
            !finite(value.timeOrigin) ||
            !Number.isInteger(value.threadId) ||
            value.threadId <= 0 ||
            value.omitted !== 0 ||
            !Array.isArray(value.rows) ||
            value.rows.length < 1 ||
            value.rows.length > 32 ||
            !value.rows.every(
              (row) =>
                row &&
                row.delay === 750 &&
                finite(row.created) &&
                ['fired', 'cleared'].every(
                  (field) =>
                    row[field] === undefined || (finite(row[field]) && row[field] >= row.created),
                ),
            )
          )
            result.diagnosticIncomplete = true;
          result.nativeTimers.push(value);
        }
      } catch {
        result.diagnosticIncomplete = true;
      }
    }
    fs.writeFileSync(join(directory, 'result.json'), JSON.stringify(result));
  } finally {
    await owner.terminate();
    monitor.restore();
  }
}

export async function frozenBatchFixture() {
  fs.mkdirSync(join(repository, '.local'), { recursive: true, mode: 0o700 });
  const directory = fs.realpathSync(fs.mkdtempSync(join(repository, '.local/trace-batch-')));
  const inputs = new Map();
  for (const name of [
    'windows-trace-diagnostics',
    'desktop-trace-native',
    'desktop-trace-native-worker',
    'desktop-private-state-native',
    'diagnostic-events',
    'windows-process',
    'desktop-prediction-trace',
  ]) {
    const path = join(source, name + '.ts'),
      input = fs.readFileSync(path, 'utf8');
    inputs.set(path, hash(input));
    let compiled = ts.transpileModule(input, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText;
    if (name === 'desktop-prediction-trace')
      compiled =
        compiled
          .replace(/(['"])node:fs\1/g, "'./fs.mjs'")
          .replace(/(['"])node:path\1/g, "'./path.mjs'") + '\nexport {TraceSnapshot};\n';
    if (name === 'desktop-trace-native-worker') compiled = "import './bootstrap.mjs';\n" + compiled;
    write(join(directory, name + '.js'), compiled);
  }
  write(join(directory, 'package.json'), '{"type":"module"}');
  write(
    join(directory, 'path.mjs'),
    "import {win32} from 'node:path';export const {dirname,isAbsolute,join,parse,relative,resolve}=win32;\n",
  );
  write(
    join(directory, 'desktop-platform.js'),
    `import {fileURLToPath} from 'node:url';
let backend;
export const installWindowsTraceBackend=value=>{backend=value};
export const windowsTraceSnapshot=(...args)=>backend.snapshot(...args);
export const windowsTraceSnapshotBatchAvailable=()=>backend?.snapshotMany!==undefined;
export const windowsTraceSnapshotMany=(...args)=>backend.snapshotMany(...args);
export const windowsTraceNativeExecutable=()=>process.env.TRACE_BATCH_EXE??fileURLToPath(new URL('./child.mjs',import.meta.url));
`,
  );
  write(
    join(directory, 'bootstrap.mjs'),
    `import assert from 'node:assert/strict';
import child from 'node:child_process';import {fileURLToPath} from 'node:url';
import {syncBuiltinESMExports} from 'node:module';
import {writeFileSync} from 'node:fs';import {dirname,join} from 'node:path';
import {threadId} from 'node:worker_threads';
if(process.env.TRACE_BATCH_MODE==='batch-timeout'){
 const originalSet=globalThis.setTimeout,originalClear=globalThis.clearTimeout,rows=[],handles=new Map();
 let omitted=0;
 globalThis.setTimeout=function(callback,delay,...args){
  if(delay!==750||typeof callback!=='function')return Reflect.apply(originalSet,this,[callback,delay,...args]);
  const row={created:performance.now(),delay};
  if(rows.length<32)rows.push(row);else omitted++;
  const handle=Reflect.apply(originalSet,this,[function(...passed){row.fired=performance.now();return Reflect.apply(callback,this,passed)},delay,...args]);
  handles.set(handle,row);return handle;
 };
 globalThis.clearTimeout=function(handle){const row=handles.get(handle);if(row)row.cleared=performance.now();return Reflect.apply(originalClear,this,[handle])};
 process.once('exit',()=>{try{writeFileSync(join(dirname(process.env.TRACE_BATCH_LOG),'native-timer-'+threadId+'.json'),JSON.stringify({version:1,timeOrigin:performance.timeOrigin,threadId,rows,omitted}),{flag:'wx',mode:0o600})}catch{}});
}
Object.defineProperty(process,'platform',{value:'win32'});
const entry=fileURLToPath(new URL('./child.mjs',import.meta.url)),original=child.spawn;
child.spawn=function(command,args,options){
 if(process.env.TRACE_BATCH_EXE){assert.equal(command,process.env.TRACE_BATCH_EXE);return Reflect.apply(original,this,[command,args,options]);}
 assert.equal(command,entry);assert.equal(args[0],'serve');assert.equal(options.shell,undefined);
 return Reflect.apply(original,this,[process.execPath,[entry,...args],options]);
};syncBuiltinESMExports();
`,
  );
  write(join(directory, 'child.mjs'), `await (${stdioChild.toString()})();`);
  write(join(directory, 'owner.mjs'), `await (${transportOwner.toString()})();`);
  write(join(directory, 'main.mjs'), `await (${transportMain.toString()})();`);
  const monitor = fileURLToPath(new URL('./windows-private-state-monitor.mjs', import.meta.url));
  const monitorInput = fs.readFileSync(monitor);
  inputs.set(monitor, hash(monitorInput));
  write(join(directory, 'monitor.mjs'), monitorInput);
  return {
    directory,
    unchanged() {
      for (const [path, expected] of inputs) assert.equal(hash(fs.readFileSync(path)), expected);
    },
  };
}

export async function batchTransportFixture(mode, extra = {}, itemCount = 2) {
  assert(
    itemCount === 2 || itemCount === 8 || itemCount === 64,
    'Fixed synthetic batch width required',
  );
  const fixture = await frozenBatchFixture();
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([key]) =>
      /^(PATH|SYSTEMROOT|WINDIR|TEMP|TMP|USERPROFILE|HOME|LOCALAPPDATA|APPDATA)$/i.test(key),
    ),
  );
  try {
    await run(
      process.execPath,
      [join(fixture.directory, 'main.mjs'), fixture.directory, mode, String(itemCount)],
      {
        env: { ...env, ...extra },
        timeout: 12000,
        windowsHide: true,
      },
    );
    return JSON.parse(fs.readFileSync(join(fixture.directory, 'result.json')));
  } finally {
    fixture.unchanged();
  }
}
