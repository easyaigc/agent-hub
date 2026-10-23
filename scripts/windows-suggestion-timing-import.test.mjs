import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { Worker } from 'node:worker_threads';

const repository = fileURLToPath(new URL('../', import.meta.url));
const tool = join(repository, 'scripts/diagnostics/windows-suggestion-timing-import.mjs');
const run = promisify(execFile);
const workerClocks = [];
const observationSecret = 'PRIVATE_NATIVE_FS_OBSERVATION_MARKER';
const environment = {
  ...Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) => !/^(HOST_|CODEX_|AGENT_HUB_|FIXTURE_|NODE_OPTIONS$)/i.test(key),
    ),
  ),
  AGENT_HUB_TRACE_TIMING_DEV: '1',
};
const classes = {
  'runtime-session': 'RuntimeSession',
  'runtime-persistence': 'RuntimePersistence',
  'desktop-ipc': 'DesktopIpc',
  'desktop-framing': 'DesktopFrameDecoder',
  'desktop-workspace-view': 'DesktopWorkspaceView',
  'desktop-prepared-bridge': 'DesktopPreparedBridge',
  'history-reader': 'CodexHistoryReader',
  'desktop-suggestion-observer': 'DesktopSuggestionObserver',
  'desktop-suggestion-worker-client': 'DesktopSuggestionWorkerClient',
  'desktop-prediction-trace': 'DesktopPredictionTraceReader',
};

// Real Node streams and EventEmitter lifecycle, but no child process/native API.
const nativeObservationBootstrap = `
import child from 'node:child_process';
import {EventEmitter} from 'node:events';
import {Writable,Readable} from 'node:stream';
globalThis.nativeFixture={calls:[],next:undefined,failure:undefined};
globalThis.makeChild=()=>{
  const value=new EventEmitter();
  value.pid=4567;
  value.stdin=new Writable({highWaterMark:1,write(chunk,encoding,callback){queueMicrotask(callback)}});
  value.stdout=new Readable({read(){}});
  return value;
};
child.spawn=function(...args){
  nativeFixture.calls.push({receiver:this,args});
  if(nativeFixture.failure)throw nativeFixture.failure;
  return nativeFixture.next;
};
const timeout=globalThis.setTimeout;
globalThis.setTimeout=function(fn,ms,...args){
  if(ms===90000)globalThis.stopObservation=fn;
  return Reflect.apply(timeout,this,[fn,ms,...args]);
};
`;

async function observeOffline(t, role, bootstrap, body) {
  const f = await fixture({ observationBootstrap: bootstrap });
  let worker;
  t.after(async () => {
    if (worker) await worker.terminate();
    await rm(f.root, { recursive: true, force: true });
  });
  const entry = join(f.program, 'offline-observation-entry.mjs');
  await put(
    entry,
    `
import assert from 'node:assert/strict';
import fs from 'node:fs';
import child,{spawn} from 'node:child_process';
import {parentPort} from 'node:worker_threads';
import {writeTimingEvidence} from './scripts/diagnostics/windows-suggestion-timing-import.mjs';
import {DesktopPredictionTraceReader} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-prediction-trace.js';
import {DesktopSuggestionObserver} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-suggestion-observer.js';
import {DesktopSuggestionWorkerClient} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-suggestion-worker-client.js';
const secret=${JSON.stringify(observationSecret)};
const evidence=()=>JSON.parse(fs.readFileSync(writeTimingEvidence(),'utf8'));
${body}
parentPort?.close();
`,
  );
  if (role === 'main') {
    const result = await run(process.execPath, ['--import', pathToFileURL(f.guard).href, entry], {
      env: environment,
      timeout: 15000,
    });
    assert.equal(result.stderr, '');
  } else {
    worker = new Worker(pathToFileURL(entry), {
      execArgv: ['--import', pathToFileURL(f.guard).href],
      env: environment,
      workerData: {
        windowsProcessGate: new SharedArrayBuffer(4),
        ...(role === 'native-channel'
          ? { shared: new SharedArrayBuffer(8) }
          : { kind: 'windows-suggestion-reader' }),
      },
    });
    worker.on('message', (message) => {
      if (message.observationReady) worker.postMessage({ kind: 'read', id: message.ordinal });
      if (message.preparedPhaseReady)
        worker.postMessage({
          kind: message.phase,
          id: message.ordinal,
          generation: message.generation,
        });
    });
    await new Promise((resolve, reject) => {
      worker.once('error', reject);
      worker.once('exit', (code) =>
        code === 0 ? resolve() : reject(Error('Offline observation Worker failed')),
      );
    });
  }
  const base = join(f.program, '.local/windows-suggestion-timing');
  const directories = await readdir(base);
  assert.equal(directories.length, 1);
  const raw = await readFile(join(base, directories[0], 'timing.json'), 'utf8');
  assert(
    !raw.includes(observationSecret),
    'Command/FS path, argument, payload or error text leaked',
  );
  return JSON.parse(raw);
}

test('persistent command observation preserves streams, backpressure, frames and exact forwarding', async (t) => {
  const summary = await observeOffline(
    t,
    'native-channel',
    nativeObservationBootstrap,
    `
const c=makeChild(); nativeFixture.next=c;
const args=[secret], options=new Proxy({}, {get(){throw Error('Options must not be inspected')}});
assert.equal(spawn('C:/private/trace-state.exe',args,options),c);
assert.equal(nativeFixture.calls[0].args[1],args);
assert.equal(nativeFixture.calls[0].args[2],options);
assert.equal(c.stdout.listenerCount('data'),0);
assert.equal(c.stdout.readableFlowing,null);
assert.equal(c.listenerCount('error'),0);
assert.equal(c.stdout.emit('data',Buffer.from('{"ready":true}\\n')),false);
assert.equal(c.emit('spawn'),false);
let callbackCount=0;
assert.equal(c.stdin.write('P\\n',()=>callbackCount++),false,'Real Writable backpressure');
assert.equal(c.stdout.emit('data',Buffer.from('{"private":"'+secret)),false);
assert.equal(c.stdout.emit('data',Buffer.from('"}\\n')),false);
assert.equal(c.stdin.write('S'),false);
assert.equal(c.stdin.write('\\t'+secret+'\\n'),false);
c.stdout.emit('data',Buffer.from('{"error":"WINDOWS_'));
c.stdout.emit('data',Buffer.from('TRACE_NATIVE_REJECTED"}\\r\\n'));
assert.equal(c.stdin.write(Buffer.from('T\\nB\\t'+secret+'\\n')),false);
const frames=Buffer.from('[]\\n{"private":"'+secret+'"}\\n');
let received;
c.stdout.once('data',value=>{received=value});
assert.equal(c.stdout.emit('data',frames),true);
assert.equal(received,frames);
await new Promise(resolve=>setImmediate(resolve));
assert.equal(callbackCount,1);
assert.equal(c.stdout.listenerCount('data'),0,'No diagnostic data listener');
assert.equal(c.emit('exit',0,null),false);
assert.equal(c.emit('close',0,null),false);
const direct=makeChild();
const exact={}; const argv=[Buffer.from('P\\n'),()=>{}];
let invocation;
direct.stdin={write(...values){invocation={receiver:this,values};return exact}};
nativeFixture.next=direct; assert.equal(spawn('trace-state.exe'),direct);
assert.equal(Reflect.apply(direct.stdin.write,exact,argv),exact);
assert.equal(invocation.receiver,exact); assert.deepEqual(invocation.values,argv);
direct.stdout.emit('data',Buffer.from('true\\n'));
const other=makeChild();nativeFixture.next=other;const originalWrite=other.stdin.write;
assert.equal(spawn('private-state.exe'),other);assert.equal(other.stdin.write,originalWrite);
const s=evidence(); assert.equal(s.complete,true); assert.equal(s.dropped,0);
assert.equal(s.persistentTraceCommands.complete,true);
`,
  );
  assert.deepEqual(summary.persistentTraceCommands.observedCounts, { P: 2, S: 1, T: 1, B: 1 });
  const commands = summary.rows.filter((row) => row.name === 'trace-native-command');
  assert.deepEqual(
    commands.map((row) => row.commandKind),
    ['P', 'S', 'T', 'B', 'P'],
  );
  assert.deepEqual(
    commands.map((row) => row.commandOrdinal),
    [1, 2, 3, 4, 1],
  );
  assert.deepEqual(
    commands.map((row) => row.nativeErrorFrame),
    [false, true, false, false, false],
  );
  assert(
    commands.every((row) => row.childProcessId === 4567 && row.commandCompleted && row.wallMs >= 0),
  );
  assert.equal(
    commands[1].responseBytes,
    Buffer.byteLength('{"error":"WINDOWS_TRACE_NATIVE_REJECTED"}\r\n'),
  );
});

for (const scenario of [
  'pending',
  'partial',
  'close',
  'error',
  'write-error',
  'opaque',
  'opaque-write',
  'getters',
  'proxy',
  'spawn-error',
  'multi-frame',
  'missing',
  'deadline',
  'limit',
])
  test(`persistent command observation reports ${scenario} without changing execution`, async (t) => {
    const summary = await observeOffline(
      t,
      'native-channel',
      nativeObservationBootstrap,
      `
const scenario=${JSON.stringify(scenario)};
let getterReads=0,proxyReads=0;
const failure=new Error(secret);
const c=makeChild(); nativeFixture.next=c;
if(scenario==='getters') {
  Object.defineProperty(c,'stdin',{get(){getterReads++;throw failure}});
  Object.defineProperty(c,'pid',{get(){getterReads++;throw failure}});
  Object.defineProperty(c.stdout,'emit',{get(){getterReads++;throw failure}});
}
if(scenario==='proxy')nativeFixture.next=new Proxy(c,{
  get(){proxyReads++;throw failure},getOwnPropertyDescriptor(){proxyReads++;throw failure},getPrototypeOf(){proxyReads++;throw failure}
});
if(scenario==='spawn-error')nativeFixture.failure=failure;
if(scenario==='write-error')c.stdin={write(){throw failure}};
if(scenario==='opaque-write')c.stdin={write(){return false}};
if(!['missing'].includes(scenario)) {
  if(scenario==='spawn-error')assert.throws(()=>spawn('trace-state.exe'),error=>error===failure);
  else assert.equal(spawn('trace-state.exe'),nativeFixture.next);
}
if(['pending','close','error','multi-frame','deadline'].includes(scenario)) {
  c.stdout.emit('data',Buffer.from('ready\\n'));
  c.stdin.write('P\\n');
  c.stdout.emit('data',Buffer.from('partial'));
}
if(scenario==='partial')c.stdin.write('B\\t'+secret);
if(scenario==='close')c.emit('close');
if(scenario==='error')assert.throws(()=>c.emit('error',failure),error=>error===failure);
if(scenario==='write-error')assert.throws(()=>c.stdin.write('P\\n'),error=>error===failure);
if(scenario==='opaque') {
  c.stdin={};
  // A proxied frame must be forwarded untouched, without diagnostic traps.
  const opaque=new Proxy({}, {get(){proxyReads++;throw failure},getPrototypeOf(){proxyReads++;throw failure}});
  assert.equal(c.stdout.emit('data',opaque),false);
}
if(scenario==='opaque-write') {
  const opaque=new Proxy({}, {get(){proxyReads++;throw failure},getPrototypeOf(){proxyReads++;throw failure}});
  assert.equal(c.stdin.write(opaque),false);
}
if(scenario==='multi-frame')c.stdout.emit('data',Buffer.from('\\nextra\\n'));
if(scenario==='limit')for(let at=0;at<20010;at++){
  c.stdin.write('P\\n');c.stdout.emit('data',Buffer.from('true\\n'));
}
if(scenario==='deadline') {
  const path=stopObservation();const before=fs.readFileSync(path,'utf8');
  c.stdout.emit('data',Buffer.from('\\n'));c.stdin.write('P\\n');c.emit('close');
  assert.equal(fs.readFileSync(writeTimingEvidence(),'utf8'),before);
}
assert.equal(getterReads,0);assert.equal(proxyReads,0);
const s=evidence();assert.equal(s.complete,false);assert.equal(s.persistentTraceCommands.complete,false);
`,
    );
    assert(summary.rows.length <= 20000);
    if (scenario === 'limit') assert(summary.dropped > 0);
    if (scenario === 'pending' || scenario === 'deadline') {
      assert(summary.pendingAtStop.includes('trace-native-command'));
      assert.equal(summary.persistentTraceCommands.childrenAtStop[0].pendingCommands, 1);
    }
    if (scenario === 'partial')
      assert(summary.persistentTraceCommands.childrenAtStop[0].partialRequestBytes > 0);
    if (scenario === 'missing') assert.equal(summary.persistentTraceCommands.hookedChildren, 0);
    if (['getters', 'proxy'].includes(scenario))
      assert.equal(summary.persistentTraceCommands.hookFailures, 1);
  });

for (const role of ['main', 'suggestion-reader'])
  test(`persistent hook skips non-native role ${role}`, async (t) => {
    const s = await observeOffline(
      t,
      role,
      nativeObservationBootstrap,
      `
const c=makeChild();nativeFixture.next=c;const write=c.stdin.write,emit=c.stdout.emit;
assert.equal(spawn('trace-state.exe'),c);
assert.equal(c.stdin.write,write);assert.equal(c.stdout.emit,emit);
evidence();
`,
    );
    assert.equal(s.persistentTraceCommands.spawnAttempts, 0);
    assert.equal(s.persistentTraceCommands.expected, false);
  });

const fsObservationBootstrap = `
import fs from 'node:fs';
globalThis.fsFixture={calls:[],sentinel:{},result:{},failure:undefined,getterReads:0};
for(const method of ['lstatSync','realpathSync','fstatSync','openSync','readSync','closeSync','opendirSync']) {
  const original=fs[method];
  const stub=function(...args){
    if(args[0]!==fsFixture.sentinel)return Reflect.apply(original,this,args);
    fsFixture.calls.push({method,receiver:this,args});
    if(fsFixture.failure)throw fsFixture.failure;
    if(method==='realpathSync')fs.lstatSync(...args);
    return fsFixture.result;
  };
  Object.defineProperty(stub,'diagnosticGetter',{get(){fsFixture.getterReads++;throw Error('Do not evaluate function properties')}});
  if(method==='realpathSync')Object.defineProperty(stub,'native',Object.getOwnPropertyDescriptor(original,'native'));
  fs[method]=stub;
}
globalThis.originalRealpathNativeDescriptor=Object.getOwnPropertyDescriptor(fs.realpathSync,'native');
`;

test('scan FS aggregates exact existing calls only in the scan subtree, preserves descriptors and ESM bindings', async (t) => {
  const summary = await observeOffline(
    t,
    'suggestion-reader',
    fsObservationBootstrap,
    `
const named=await import('node:fs');
assert.equal(named.realpathSync,fs.realpathSync,'Builtin ESM binding synchronized');
assert.deepEqual(Object.getOwnPropertyDescriptor(fs.realpathSync,'native'),originalRealpathNativeDescriptor);
const options=new Proxy({}, {get(){throw Error('Diagnostic options access')},getOwnPropertyDescriptor(){throw Error('Diagnostic options access')}});
const result=new Proxy({}, {get(){throw Error('Diagnostic result access')},getPrototypeOf(){throw Error('Diagnostic result access')}});
fsFixture.result=result;
const methods=['lstatSync','realpathSync','fstatSync','openSync','readSync','closeSync','opendirSync'];
for(const method of methods)assert.equal(fs[method](fsFixture.sentinel,options),result);
const receiver={};const reader=new DesktopPredictionTraceReader();
const childObserver=new DesktopSuggestionObserver();
childObserver.operation=function(){
  for(const method of methods) {
    assert.equal(Reflect.apply(named[method],receiver,[fsFixture.sentinel,options]),result);
    const call=fsFixture.calls.findLast(call=>call.method===method);
    assert.equal(call.receiver,receiver);assert.equal(call.args[1],options);
  }
};
reader.operation=function(){childObserver.rootStable();return [secret]};
assert.equal(reader.readSourcePredictions()[0],secret);
const failure=new Error(secret);fsFixture.failure=failure;
reader.operation=function(){return fs.readSync(fsFixture.sentinel,options)};
assert.throws(()=>reader.readSourcePredictions(),error=>error===failure);
fsFixture.failure=undefined;
reader.operation=async function(){await Promise.resolve();fs.fstatSync(fsFixture.sentinel,options);return null};
assert.equal(await reader.readSourcePredictions(),null);
assert.equal(fsFixture.getterReads,0);
const s=evidence();assert.equal(s.complete,true);
// Recording stopped; the same original methods, receivers and errors still apply.
assert.equal(fs.lstatSync(fsFixture.sentinel,options),result);
assert.equal(fsFixture.getterReads,0);
`,
  );
  const rows = summary.rows.filter((row) => row.name === 'prediction-scan-fs');
  assert.equal(rows.length, 3);
  assert.equal(
    rows[0].methods.lstatSync.count,
    2,
    'Includes original realpath subtree, excludes all out-of-scan calls',
  );
  for (const method of [
    'realpathSync',
    'fstatSync',
    'openSync',
    'readSync',
    'closeSync',
    'opendirSync',
  ])
    assert.equal(rows[0].methods[method].count, 1);
  assert.equal(rows[1].methods.readSync.errors, 1);
  assert.equal(rows[2].methods.fstatSync.count, 1);
  for (const row of rows) {
    assert.equal(row.pending, false);
    assert(row.topLevelWallMs >= 0);
    assert(
      row.topLevelWallMs <=
        Object.values(row.methods).reduce((sum, value) => sum + value.wallMs, 0) + 0.001,
    );
  }
  assert.equal(summary.predictionScanFS.complete, true);
});

test('scan FS records real Node FS overloads and original errors with no extra calls', async (t) => {
  const s = await observeOffline(
    t,
    'suggestion-reader',
    '',
    `
const named=await import('node:fs');
const reader=new DesktopPredictionTraceReader();
const file=new URL('./package.json',import.meta.url);
reader.operation=function(){
  assert.equal(named.lstatSync(file,{bigint:true}).isFile(),true);
  assert.equal(typeof named.realpathSync(file,{encoding:'buffer'}).byteLength,'number');
  const fd=named.openSync(file,'r');
  try {
    assert(named.fstatSync(fd).isFile());
    assert(named.readSync(fd,Buffer.alloc(4),0,4,0)>0);
  }finally{named.closeSync(fd)}
  const dir=named.opendirSync(new URL('./',import.meta.url));dir.closeSync();
  assert.throws(()=>named.lstatSync(new URL('./'+secret,import.meta.url)),{code:'ENOENT'});
  return [];
};
reader.readSourcePredictions();evidence();
`,
  );
  const rows = s.rows.filter((row) => row.name === 'prediction-scan-fs');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].methods.lstatSync.errors, 1);
  for (const method of [
    'realpathSync',
    'fstatSync',
    'openSync',
    'readSync',
    'closeSync',
    'opendirSync',
  ])
    assert.equal(rows[0].methods[method].count, 1);
});

test('scan FS read ordinals join the final availability boolean without reading result payloads', async (t) => {
  const s = await observeOffline(
    t,
    'suggestion-reader',
    fsObservationBootstrap,
    `
await new Promise(resolve=>{
let count=0;
parentPort.on('message',message=>{
  const reader=new DesktopPredictionTraceReader();
  reader.operation=function(){fs.lstatSync(fsFixture.sentinel);return null};
  const observer=new DesktopSuggestionObserver();
  observer.operation=function(){reader.readSourcePredictions();return {suggestion:null}};
  const value=observer.readBoundSource();
  const result={kind:'result',id:message.id,value,available:++count===1};
  result.body=secret;
  parentPort.postMessage(result);
  if(count===1)parentPort.postMessage({observationReady:true,ordinal:2});
  else {evidence();resolve()}
});
parentPort.postMessage({observationReady:true,ordinal:1});
});
`,
  );
  assert.deepEqual(
    s.rows.filter((row) => row.name === 'prediction-scan-fs').map((row) => row.workerReadOrdinal),
    [1, 2],
  );
  assert.deepEqual(
    s.rows
      .filter((row) => row.name === 'worker-result-post')
      .map((row) => [row.workerReadOrdinal, row.finalAvailabilityPresent, row.finalAvailability]),
    [
      [1, true, true],
      [2, true, false],
    ],
  );
});

test('scan FS exposes pending scans at stop instead of reporting complete zero', async (t) => {
  const s = await observeOffline(
    t,
    'suggestion-reader',
    fsObservationBootstrap,
    `
const reader=new DesktopPredictionTraceReader();
reader.operation=function(){fs.lstatSync(fsFixture.sentinel);return new Promise(()=>{})};
reader.readSourcePredictions();
const s=evidence();assert.equal(s.complete,false);assert.equal(s.predictionScanFS.complete,false);
`,
  );
  assert(s.pendingAtStop.includes('DesktopPredictionTraceReader.readSourcePredictions'));
  assert.equal(s.rows.find((row) => row.name === 'prediction-scan-fs').pending, true);
});

test('scan FS skips opaque function descriptors without triggering Proxy/getter traps', async (t) => {
  const s = await observeOffline(
    t,
    'suggestion-reader',
    `
import fs from 'node:fs';
globalThis.traps=0;
const fail=()=>{traps++;throw Error('Opaque FS descriptor')};
fs.readSync=new Proxy(fs.readSync,{get:fail,getOwnPropertyDescriptor:fail,ownKeys:fail,getPrototypeOf:fail});
Object.defineProperty(fs.fstatSync,'diagnosticGetter',{configurable:true,get:fail});
`,
    `
assert.equal(traps,0);const s=evidence();assert.equal(traps,0);
assert.equal(s.predictionScanFS.complete,false);
assert.equal(s.complete,false);
`,
  );
  assert.equal(s.predictionScanFS.hooks.readSync, false);
  assert.equal(s.predictionScanFS.hooks.fstatSync, true);
});

async function put(path, text) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, text);
}

async function fixture({ dummyNativeWorker = false, observationBootstrap = '' } = {}) {
  await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
  const root = await mkdtemp(join(repository, '.local/timing-import-test-'));
  const program = join(root, 'offline program 项目');
  const adapter = join(program, 'apps/host-agent/node_modules/@codex-manage/codex-adapter');
  const dist = join(adapter, 'dist');
  const preload = join(program, 'scripts/diagnostics/windows-suggestion-timing-import.mjs');
  await mkdir(dirname(preload), { recursive: true });
  await cp(tool, preload);
  await put(join(program, 'package.json'), JSON.stringify({ type: 'module' }));
  await put(
    join(adapter, 'package.json'),
    JSON.stringify({
      type: 'module',
      exports: { '.': { import: './dist/index.js', require: './wrong.cjs' } },
    }),
  );
  const forbidden = "throw Error('The import probe must not execute an entry or API');\n";
  // Neither the package entry, Host server, worker entries nor source dist may run.
  for (const path of [
    join(adapter, 'wrong.cjs'),
    join(dist, 'index.js'),
    join(dist, 'desktop-suggestion-read-worker.js'),
    join(dist, 'desktop-suggestion-worker-limits.js'),
    join(dist, 'desktop-trace-native-worker.js'),
    join(dist, 'desktop-trace-native.js'),
    join(dist, 'desktop-private-state-native.js'),
    join(dist, 'desktop-private-state-native-worker.js'),
    join(program, 'apps/host-agent/dist/runtime-server.js'),
    join(program, 'packages/codex-adapter/dist/index.js'),
    join(program, 'packages/codex-adapter/dist/desktop-trace-native-worker.js'),
    join(program, 'packages/codex-adapter/dist/desktop-suggestion-worker-limits.js'),
    join(program, 'packages/codex-adapter/dist/desktop-private-state-native.js'),
    join(program, 'packages/codex-adapter/dist/desktop-private-state-native-worker.js'),
    ...Object.keys(classes).map((name) =>
      join(program, 'packages/codex-adapter/dist', name + '.js'),
    ),
  ])
    await put(path, forbidden);
  for (const name of [
    'desktop-private-state-native',
    'desktop-private-state-native-worker',
    'desktop-suggestion-worker-limits',
  ])
    await put(join(dist, name + '.js'), forbidden + `// Actual Host dependency: ${name}\n`);
  // The one allowed nominal import is the real BuiltinWorker-only gate, not
  // the nominal Adapter entry. Keep a distinct actual Host ESM module copy.
  const gateBytes = await readFile(
    join(repository, 'packages/codex-adapter/dist/windows-process.js'),
  );
  for (const gate of [
    join(program, 'packages/codex-adapter/dist/windows-process.js'),
    join(dist, 'windows-process.js'),
  ])
    await put(gate, gateBytes);
  // Synthetic classes let us test the preload's forwarding, without desktop APIs.
  for (const [name, Type] of Object.entries(classes))
    await put(
      join(dist, name + '.js'),
      `export class ${Type} { constructor() { this.failure=null; this.value=undefined; } }
Object.setPrototypeOf(${Type}.prototype, new Proxy({}, {
  get: () => function (...args) {
    this.observed = { receiver: this, args };
    if (this.failure) throw this.failure;
    if (Object.hasOwn(this,'operation')) return Reflect.apply(this.operation,this,args);
    return this.value;
  }
}));\n`,
    );
  await put(
    join(dist, 'desktop-platform.js'),
    `export function protectWindowsStateDirectory(path) { globalThis.protection.push(['directory',path]); }
export function protectWindowsStateFile(path) { globalThis.protection.push(['file',path]); }\n`,
  );

  const guard = join(program, 'offline-preload.mjs');
  await put(join(program, 'offline-observation-bootstrap.mjs'), observationBootstrap);
  await put(
    guard,
    `import assert from 'node:assert/strict';
import child from 'node:child_process';
import net from 'node:net';
import http from 'node:http';
import https from 'node:https';
import workerThreads from 'node:worker_threads';
import {syncBuiltinESMExports} from 'node:module';
const reject = () => { throw Error('An offline import attempted an external API'); };
const exec = child.execFileSync;
child.execFileSync = function(command,args,options) {
  assert.equal(command,process.execPath);
  assert.deepEqual(args.slice(0,3),['--experimental-import-meta-resolve','--input-type=module','--eval']);
  assert.equal(args.length,4);
  assert.equal(args[3],"import {readFileSync} from 'node:fs';const v=JSON.parse(readFileSync(0,'utf8'));console.log(JSON.stringify(import.meta.resolve(v.name,v.parent)));");
  assert.equal(JSON.parse(options.input).name,'@codex-manage/codex-adapter');
  assert.equal(JSON.parse(options.input).parent,new URL('./apps/host-agent/dist/runtime-server.js',import.meta.url).href);
  assert.equal(options.env.NODE_OPTIONS,undefined);
  return Reflect.apply(exec,this,[command,args,options]);
};
for(const name of ['spawn','spawnSync','exec','execSync','execFile','fork']) child[name]=reject;
net.Server.prototype.listen=reject;
net.connect=net.createConnection=reject;
for(const api of [http,https]) api.get=api.request=reject;
globalThis.fetch=reject;
globalThis.protection=[];
globalThis.originalWait=Atomics.wait;
globalThis.workerConstructions=[];
if(${JSON.stringify(dummyNativeWorker)}) {
  class DummyNativeWorker {
    constructor(...args) {
      globalThis.workerConstructions.push({receiver:this,args,newTarget:new.target});
      if(args[1]?.failure) throw args[1].failure;
    }
  }
  workerThreads.Worker=DummyNativeWorker;
}
globalThis.originalWorkerConstructor=workerThreads.Worker;
globalThis.deadlines=[];
const timeout=setTimeout;
globalThis.setTimeout=(fn,ms,...args)=>{globalThis.deadlines.push(ms);return timeout(fn,ms,...args)};
await import('./offline-observation-bootstrap.mjs');
syncBuiltinESMExports();
await import('./scripts/diagnostics/windows-suggestion-timing-import.mjs');\n`,
  );
  const entry = join(program, 'offline-entry.mjs');
  await put(
    entry,
    `import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {EventEmitter} from 'node:events';
import http from 'node:http';
import workerThreads,{isMainThread,parentPort,threadId,workerData} from 'node:worker_threads';
import * as timing from './scripts/diagnostics/windows-suggestion-timing-import.mjs';
import {RuntimeSession} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/runtime-session.js';
import {DesktopFrameDecoder} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-framing.js';
import {DesktopIpc} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-ipc.js';
import {DesktopSuggestionWorkerClient} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-suggestion-worker-client.js';
import {DesktopSuggestionObserver} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-suggestion-observer.js';
assert.deepEqual(Object.keys(timing),['writeTimingEvidence']);
assert(globalThis.deadlines.includes(90000));
assert.equal(globalThis.workerConstructions.length,0,'Import must not start a Worker');
if(!isMainThread) assert.equal(workerThreads.Worker,globalThis.originalWorkerConstructor);
const sensitive=['fixture-body-private','fixture-token-private','fixture-thread-private','fixture-turn-private','C:/fixture/private/path','privateTrialId','912345678901234','fixture-host-private','fixture-account-private'];
const expectedReads=[];
function finish() {
  const output=timing.writeTimingEvidence();
  assert.equal(timing.writeTimingEvidence(),output);
  const text=readFileSync(output,'utf8');
  for(const value of sensitive) assert(!text.includes(value),'Sensitive diagnostic value leaked');
  const summary=JSON.parse(text);
  assert(!('pid' in summary)); assert(!('threadId' in summary));
  assert.equal(summary.processId,process.pid);
  assert.equal(summary.isolateThreadId,threadId);
  assert.equal(summary.completenessScope,'isolate');
  function checkOsFields(value) {
    if(!value || typeof value!=='object') return;
    for(const [key,field] of Object.entries(value)) {
      if(/Id$/.test(key) || key==='id' || key==='pid') {
        assert(['processId','isolateThreadId','workerThreadId'].includes(key));
        assert(Number.isSafeInteger(field) && field>=0);
        assert.equal(field,key==='processId'?process.pid:threadId);
      }
      checkOsFields(field);
    }
  }
  checkOsFields(summary);
  assert.deepEqual(globalThis.protection.map(([type])=>type),['directory','directory','file']);
  assert.equal(globalThis.protection[2][1],output);
  if(isMainThread) {
    assert.equal(summary.rows.length,20000);
    assert(summary.dropped>0); assert.equal(summary.complete,false);
    assert(summary.rows.some(row=>row.name==='HTTP /healthz'));
    const reads=summary.rows.filter(row=>row.name==='DesktopSuggestionWorkerClient.read');
    assert.equal(reads.length,expectedReads.length);
    reads.forEach((row,index)=>{
      const actual={};
      for(const key of ['workerThreadId','nextWorkerReadOrdinal'])
        if(Object.hasOwn(row,key)) actual[key]=row[key];
      assert.deepEqual(actual,expectedReads[index]);
    });
  } else if(workerData.shared) {
    assert.equal(summary.complete,false);
    assert.deepEqual(summary.pendingAtStop,['RuntimeSession.desktopSuggestionSource']);
  }
}
if(isMainThread) {
  assert.equal(Atomics.wait,globalThis.originalWait);
  const target=new RuntimeSession(), args=[...sensitive], value={unchanged:true};
  target.value=value;
  assert.equal(target.event(...args),value);
  assert.equal(target.observed.receiver,target);
  assert.deepEqual(target.observed.args,args);
  const failure=new Error(sensitive[0]);
  Object.defineProperty(failure,'status',{get(){throw new Error(sensitive[1])}});
  target.failure=failure;
  assert.throws(()=>target.event(...args),error=>error===failure);
  target.failure=null;
  target.value=Promise.resolve(value);
  assert.equal(target.desktopSuggestionSource(...args),target.value);
  assert.equal(await target.value,value);
  target.value=Promise.reject(failure);
  assert.equal(target.desktopSuggestionSource(...args),target.value);
  await assert.rejects(target.value,error=>error===failure);
  const ipc=new DesktopIpc();
  ipc.follows=new Map(); ipc.value=value;
  assert.equal(ipc.follow(sensitive[2],sensitive[0],true),value);
  const client=new DesktopSuggestionWorkerClient();
  client.value=value;
  const checkRead=expected=>{
    assert.equal(client.read(...sensitive),value);
    expectedReads.push(expected);
  };
  client.nextId=0; client.worker={threadId};
  checkRead({nextWorkerReadOrdinal:1,workerThreadId:threadId});
  for(const invalid of [sensitive[1],String(threadId),-1,0.5,NaN,Infinity,Number.MAX_SAFE_INTEGER+1]) {
    client.nextId=invalid; client.worker={threadId:invalid};
    checkRead({});
  }
  client.nextId=Number.MAX_SAFE_INTEGER; client.worker={threadId};
  checkRead({workerThreadId:threadId});
  client.nextId=0; client.worker={get threadId(){throw failure}};
  checkRead({nextWorkerReadOrdinal:1});
  Object.defineProperty(client,'nextId',{get(){throw failure}});
  client.worker={threadId};
  checkRead({workerThreadId:threadId});
  const server=new http.Server(), response=new EventEmitter();
  response.statusCode=503;
  server.on('request',()=>response.emit('finish'));
  server.emit('request',{method:'GET',url:'/healthz?token='+sensitive[1],headers:{'x-agenthub-timing-trial':sensitive[5]},body:sensitive[0]},response);
  const frames=new DesktopFrameDecoder();
  for(let i=0;i<20010;i++) frames.push(null);
  finish();
  process.stdout.write('offline-main-ok');
} else if(workerData.kind==='windows-suggestion-reader') {
  assert.equal(typeof parentPort.prependListener,'undefined');
  parentPort.on('message',message=>{
    const observer=new DesktopSuggestionObserver();
    observer.value=sensitive;
    assert.equal(observer.readBoundSource(...sensitive),sensitive);
    parentPort.postMessage({kind:'result',id:message.id,value:sensitive});
    finish();
    parentPort.close();
  });
  parentPort.postMessage({kind:'ready'});
} else {
  assert.notEqual(Atomics.wait,globalThis.originalWait);
  const array=new Int32Array(workerData.shared);
  Atomics.store(array,0,1);
  assert.equal(Atomics.wait(array,0,0,1),'not-equal');
  const pending=new RuntimeSession();
  pending.value=new Promise(()=>{});
  assert.equal(pending.desktopSuggestionSource(...sensitive),pending.value);
  finish();
  parentPort.close();
}\n`,
  );
  return { root, program, dist, guard, entry };
}

async function resolverGateFixture(scenario) {
  const f = await fixture({ dummyNativeWorker: true });
  const nominal = join(f.program, 'packages/codex-adapter/dist/windows-process.js');
  if (scenario === 'missing-nominal') await rm(nominal);
  if (scenario === 'missing-actual') await rm(join(f.dist, 'windows-process.js'));
  if (scenario === 'unshared-actual') {
    const actual = join(f.dist, 'windows-process.js');
    const bytes = await readFile(actual, 'utf8');
    assert(bytes.includes('codex-manage.windows-process-gate.v1'));
    await put(
      actual,
      bytes.replace('codex-manage.windows-process-gate.v1', 'offline-incompatible-gate'),
    );
  }
  if (scenario === 'invalid-module') await put(nominal, 'export const unsupported = true;\n');
  const guard = join(f.program, 'offline-resolver-gate-preload.mjs');
  const prior = await readFile(f.guard, 'utf8');
  const marker = "await import('./scripts/diagnostics/windows-suggestion-timing-import.mjs');";
  assert.equal(prior.split(marker).length, 2);
  await put(
    guard,
    prior.replace(
      marker,
      `
// Isolated Windows-branch simulation on every OS. No production platform hook.
Object.defineProperty(process,'platform',{value:'win32'});
const {isMainThread,threadId,workerData}=await import('node:worker_threads');
const scenario=${JSON.stringify(scenario)};
const token=threadId+1, foreign=0x7ffffffe;
let shared, owner, clock=0, waits=0, resolverCalls=0, nativeCalls=0;
if(!['missing-nominal','invalid-module','missing-buffer','invalid-buffer'].includes(scenario)) {
  const nominal=await import('./packages/codex-adapter/dist/windows-process.js');
  shared=nominal.getWindowsProcessGate();
  owner=new Int32Array(shared);
  if(scenario==='contended'||scenario==='timeout')Atomics.store(owner,0,foreign);
}
Object.defineProperty(performance,'now',{configurable:true,value:()=>clock});
const wait=Atomics.wait;
Atomics.wait=function(view,index,value,timeout) {
  if(view.buffer!==shared)return Reflect.apply(wait,this,[view,index,value,timeout]);
  assert.equal(index,0);assert.equal(value,foreign);assert(timeout>0&&timeout<=50);
  waits++;clock+=timeout;
  if(scenario==='contended'&&waits===16){Atomics.store(owner,0,0);Atomics.notify(owner,0)}
  return 'ok';
};
const resolve=child.execFileSync;
child.execFileSync=function(command,args,options) {
  assert(owner,'A missing shared gate must never dispatch a process');
  assert.equal(Atomics.load(owner,0),token,'Process creation must hold the original shared gate');
  if(command==='private-state.exe') {
    nativeCalls++;assert.deepEqual(args,['check']);
    assert.equal(options.timeout,3000);
    return 'synthetic-native-result';
  }
  resolverCalls++;assert.equal(resolverCalls,1);
  assert.equal(command,process.execPath);
  assert.equal(options.timeout,scenario==='contended'?4200:5000,'The original 5s must include gate wait');
  const result=Reflect.apply(resolve,this,[command,args,options]);
  assert.equal(Atomics.load(owner,0),token,'Sync pipe completion must remain inside the gate');
  return result;
};
syncBuiltinESMExports();
let rejected;
try { await import('./scripts/diagnostics/windows-suggestion-timing-import.mjs'); }
catch(error){rejected=error}
if(scenario==='contended') {
  assert.equal(rejected,undefined);
  assert.equal(waits,16);assert.equal(clock,800);assert.equal(resolverCalls,1);
  assert.equal(Atomics.load(owner,0),0);
  const nominal=await import('./packages/codex-adapter/dist/windows-process.js');
  const actual=await import('./apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/windows-process.js');
  assert.notEqual(nominal.withWindowsProcessCreation,actual.withWindowsProcessCreation,'Must remain two actual ESM identities');
  assert.equal(actual.getWindowsProcessGate(),shared);
  assert.equal(nominal.getWindowsProcessGate(),shared);
  if(!isMainThread)assert.equal(shared,workerData.windowsProcessGate,'No worker-local fallback');
  assert.equal(nominal.withWindowsProcessCreation(3000,timeout=>child.execFileSync('private-state.exe',['check'],{timeout})),'synthetic-native-result');
  assert.equal(nativeCalls,1,'The later timing wrapper must not take a nested production lock');
  assert.equal(Atomics.load(owner,0),0);
} else {
  assert(rejected,'Unsupported/expired imports must fail, never fall back');
  assert.equal(rejected.code,scenario==='timeout'?'ETIMEDOUT':'WINDOWS_TIMING_GATE_UNSUPPORTED');
  assert.equal(resolverCalls,['missing-actual','unshared-actual'].includes(scenario)?1:0);
  assert.equal(nativeCalls,0);
  assert.equal(globalThis.protection.length,0);
  if(scenario==='timeout') {
    assert.equal(clock,5000);assert.equal(waits,100);
    assert.equal(rejected.spawnStarted,false);
    assert.equal(Atomics.load(owner,0),foreign,'Timeout cannot clear an unknown owner');
  }
}
assert.equal(globalThis.workerConstructions.length,0,'No import may create a service or native Worker');
globalThis.resolverGateCheck={rejected:!!rejected,resolverCalls,nativeCalls};
`,
    ),
  );
  const entry = join(f.program, 'offline-resolver-gate-entry.mjs');
  await put(
    entry,
    `import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {isMainThread,parentPort,workerData} from 'node:worker_threads';
assert(globalThis.resolverGateCheck);
if(!globalThis.resolverGateCheck.rejected) {
  const {writeTimingEvidence}=await import('./scripts/diagnostics/windows-suggestion-timing-import.mjs');
  const summary=JSON.parse(readFileSync(writeTimingEvidence(),'utf8'));
  assert.equal(summary.role,isMainThread?'main':workerData.kind==='windows-suggestion-reader'?'suggestion-reader':'native-channel');
  const digest=url=>createHash('sha256').update(readFileSync(url)).digest('hex');
  assert.equal(summary.moduleSHA256['windows-process'],digest(new URL('./apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/windows-process.js',import.meta.url)));
  assert.equal(summary.moduleSHA256['timing/resolver-windows-process'],digest(new URL('./packages/codex-adapter/dist/windows-process.js',import.meta.url)));
  assert.equal(summary.rows.filter(row=>row.name==='native.private-state.exe.check').length,1);
}
if(isMainThread)process.stdout.write('offline-resolver-gate-ok');
else parentPort.close();
`,
  );
  return { ...f, guard, entry };
}

for (const role of ['main', 'suggestion-reader', 'native-channel'])
  test(
    `timing preload imports offline in ${role} with Host ESM closure and private output`,
    { timeout: 20000 },
    async (t) => {
      const f = await fixture();
      // An inherited preload must not recurse into the resolver subprocess.
      const env = { ...environment, NODE_OPTIONS: '--import=' + pathToFileURL(f.guard).href };
      let worker;
      let expectedProcessId, expectedIsolateThreadId;
      t.after(async () => {
        if (worker) await worker.terminate();
        await rm(f.root, { recursive: true, force: true });
      });
      if (role === 'main') {
        const running = run(process.execPath, ['--import', pathToFileURL(f.guard).href, f.entry], {
          env,
          timeout: 15000,
        });
        expectedProcessId = running.child.pid;
        expectedIsolateThreadId = 0;
        const result = await running;
        assert.equal(result.stdout, 'offline-main-ok');
        assert.equal(result.stderr, '');
      } else {
        worker = new Worker(pathToFileURL(f.entry), {
          execArgv: ['--import', pathToFileURL(f.guard).href],
          env,
          workerData:
            role === 'suggestion-reader'
              ? { kind: 'windows-suggestion-reader', windowsProcessGate: new SharedArrayBuffer(4) }
              : { shared: new SharedArrayBuffer(8), windowsProcessGate: new SharedArrayBuffer(4) },
        });
        expectedProcessId = process.pid;
        expectedIsolateThreadId = worker.threadId;
        let replied = false;
        await new Promise((resolve, reject) => {
          worker.once('error', reject);
          worker.once('exit', (code) =>
            code === 0 ? resolve() : reject(new Error('Offline Worker failed')),
          );
          worker.on('message', (value) => {
            if (value.kind === 'ready') worker.postMessage({ kind: 'read', id: 912345678901234 });
            if (value.kind === 'result') replied = true;
          });
        });
        assert.equal(replied, role === 'suggestion-reader');
      }
      const base = join(f.program, '.local/windows-suggestion-timing');
      const directories = await readdir(base);
      assert.equal(directories.length, 1);
      assert.match(directories[0], /^[a-f0-9-]{36}$/);
      const directory = join(base, directories[0]);
      assert.deepEqual(await readdir(directory), ['timing.json']);
      const file = join(directory, 'timing.json');
      const summary = JSON.parse(await readFile(file, 'utf8'));
      assert.equal(summary.role, role);
      assert.equal(summary.processId, expectedProcessId);
      assert.equal(summary.isolateThreadId, expectedIsolateThreadId);
      assert.equal(summary.completenessScope, 'isolate');
      const start = BigInt(summary.monotonicStartNs);
      assert(start > 0n);
      if (role !== 'main') {
        const previous = workerClocks.at(-1);
        if (previous) {
          assert.equal(summary.processId, previous.processId);
          assert.notEqual(summary.isolateThreadId, previous.isolateThreadId);
          assert(start >= previous.start, 'Same-process role clocks must share a monotonic origin');
        }
        workerClocks.push({
          processId: summary.processId,
          isolateThreadId: summary.isolateThreadId,
          start,
        });
      }
      for (const name of [
        'runtime-session',
        'desktop-private-state-native',
        'desktop-private-state-native-worker',
        'desktop-suggestion-worker-limits',
      ]) {
        const expected = createHash('sha256')
          .update(await readFile(join(f.dist, name + '.js')))
          .digest('hex');
        assert.equal(summary.moduleSHA256[name], expected);
      }
      if (process.platform !== 'win32') {
        assert.equal((await stat(base)).mode & 0o777, 0o700);
        assert.equal((await stat(directory)).mode & 0o777, 0o700);
        assert.equal((await stat(file)).mode & 0o777, 0o600);
      }
      if (role === 'suggestion-reader') {
        const names = [
          'worker-read-dispatch',
          'DesktopSuggestionObserver.readBoundSource',
          'worker-result-post',
        ];
        assert.deepEqual(
          summary.rows.map((row) => row.name),
          names,
        );
        assert(summary.rows.every((row) => row.workerReadOrdinal === 1));
      }
      if (role === 'native-channel') {
        assert(summary.rows.some((row) => row.name === 'Atomics.wait'));
        assert.equal(summary.complete, false);
        assert.deepEqual(summary.pendingAtStop, ['RuntimeSession.desktopSuggestionSource']);
      }
      assert.deepEqual(await readdir(join(f.program, 'scripts/diagnostics')), [
        'windows-suggestion-timing-import.mjs',
      ]);
    },
  );

test('Main timing preload restores only the actual fixed Trace Worker preload without recursion', async (t) => {
  const f = await fixture({ dummyNativeWorker: true });
  t.after(() => rm(f.root, { recursive: true, force: true }));
  const entry = join(f.program, 'offline-broker-entry.mjs');
  await put(
    entry,
    `import assert from 'node:assert/strict';
import workerThreads,{Worker} from 'node:worker_threads';
import {writeTimingEvidence} from './scripts/diagnostics/windows-suggestion-timing-import.mjs';
const original=globalThis.originalWorkerConstructor;
assert.equal(Worker,workerThreads.Worker,'Named Worker import must follow the builtin export');
assert.notEqual(Worker,original);
assert.equal(globalThis.workerConstructions.length,0,'The preload must not construct a Worker');
const trace=new URL('./apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-trace-native-worker.js',import.meta.url);
const timing=new URL('./scripts/diagnostics/windows-suggestion-timing-import.mjs',import.meta.url).href;
const flags=Object.freeze(['--enable-source-maps','--conditions=offline fixture']);
const workerData={kind:'windows-trace-native',shared:new SharedArrayBuffer(8)};
const transferList=[{ownerPort:'synthetic-only'}];
const environment={NODE_OPTIONS:'',FIXTURE_ONLY:'retained'};
const marker=Symbol('opaque option');
const options={execArgv:flags,workerData,transferList,env:environment,resourceLimits:{maxOldGenerationSizeMb:64}};
Object.defineProperty(options,marker,{value:{opaque:true},enumerable:false});
Object.freeze(options);
const extra={untouched:true};
const first=new Worker(trace,options,extra);
const record=globalThis.workerConstructions.at(-1);
assert.equal(record.receiver,first);
assert.equal(record.newTarget,Worker);
assert(first instanceof original && first instanceof Worker);
assert.equal(record.args[0],trace,'Fixed executable entry must be identical');
assert.equal(record.args[2],extra,'Additional arguments must be forwarded');
const augmented=record.args[1];
assert.notEqual(augmented,options);
assert.deepEqual(augmented.execArgv,[...flags,'--import',timing]);
assert.deepEqual(flags,['--enable-source-maps','--conditions=offline fixture']);
assert.equal(options.execArgv,flags,'Original options/flags must not be mutated');
assert.equal(Object.isFrozen(augmented),true);
assert.deepEqual(Reflect.ownKeys(augmented),Reflect.ownKeys(options));
for(const key of Reflect.ownKeys(options)) {
if(key==='execArgv')continue;
assert.equal(augmented[key],options[key]);
assert.deepEqual(Object.getOwnPropertyDescriptor(augmented,key),Object.getOwnPropertyDescriptor(options,key));
}
class Derived extends Worker {}
const derived=new Derived(trace,options);
assert.equal(globalThis.workerConstructions.at(-1).newTarget,Derived);
assert.equal(globalThis.workerConstructions.at(-1).receiver,derived);
assert(derived instanceof Derived && derived instanceof original);
const pass=(filename,value)=>{
const result=new Worker(filename,value,extra);
const seen=globalThis.workerConstructions.at(-1);
assert.equal(seen.receiver,result);
assert.deepEqual(seen.args,[filename,value,extra]);
assert.equal(seen.args[1],value,'Unmatched construction must preserve options identity');
};
for(const filename of [
new URL('./desktop-private-state-native-worker.js',trace),
new URL('./desktop-suggestion-read-worker.js',trace),
new URL('./packages/codex-adapter/dist/desktop-trace-native-worker.js',import.meta.url),
new URL(trace.href+'?lookalike'),
new URL(trace.href+'#lookalike'),
trace.pathname,
])pass(filename,options);
pass('synthetic eval only',{...options,eval:true});
for(const ownImport of [['--import',timing],['--import='+timing]])
pass(trace,{...options,execArgv:[...flags,...ownImport]});
pass(trace,undefined);
pass(trace,{...options,execArgv:undefined});
let getterReads=0;
pass(trace,{get execArgv(){getterReads++;return flags}});
assert.equal(getterReads,0,'Diagnostic setup must not evaluate an options getter');
const failure=new Error('synthetic original constructor error');
assert.throws(()=>new Worker(trace,{...options,failure}),error=>error===failure);
const seen=globalThis.workerConstructions.at(-1);
assert.equal(seen.args[0],trace);
assert.equal(seen.args[1].failure,failure);
assert.deepEqual(seen.args[1].execArgv,[...flags,'--import',timing]);
writeTimingEvidence();
process.stdout.write('offline-broker-ok');\n`,
  );
  const result = await run(process.execPath, ['--import', pathToFileURL(f.guard).href, entry], {
    env: { ...environment, NODE_OPTIONS: '--import=' + pathToFileURL(f.guard).href },
    timeout: 15000,
  });
  assert.equal(result.stdout, 'offline-broker-ok');
  assert.equal(result.stderr, '');
  const base = join(f.program, '.local/windows-suggestion-timing');
  const directories = await readdir(base);
  assert.equal(directories.length, 1);
  const summary = JSON.parse(await readFile(join(base, directories[0], 'timing.json'), 'utf8'));
  assert.equal(summary.role, 'main');
  assert.equal(summary.complete, true);
  assert.equal(summary.completenessScope, 'isolate');
  for (const name of [
    'desktop-trace-native-worker',
    'desktop-private-state-native',
    'desktop-private-state-native-worker',
  ])
    assert.equal(
      summary.moduleSHA256[name],
      createHash('sha256')
        .update(await readFile(join(f.dist, name + '.js')))
        .digest('hex'),
    );
});

for (const role of ['main', 'suggestion-reader', 'native-channel'])
  test(
    `timing primitive summaries preserve privacy and forwarding in ${role}`,
    { timeout: 20000 },
    async (t) => {
      const f = await fixture({ dummyNativeWorker: true });
      let worker;
      t.after(async () => {
        if (worker) await worker.terminate();
        await rm(f.root, { recursive: true, force: true });
      });
      const entry = join(f.program, 'offline-summary-entry.mjs');
      await put(
        entry,
        `import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {EventEmitter} from 'node:events';
import http from 'node:http';
import workerThreads,{isMainThread,parentPort,threadId,workerData} from 'node:worker_threads';
import {writeTimingEvidence} from './scripts/diagnostics/windows-suggestion-timing-import.mjs';
import {RuntimeSession} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/runtime-session.js';
import {DesktopSuggestionObserver} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-suggestion-observer.js';
import {DesktopSuggestionWorkerClient} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-suggestion-worker-client.js';
import {DesktopPredictionTraceReader} from './apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-prediction-trace.js';
const secret='OFFLINE_PRIVATE_MARKER_TIMING';
const thread='11111111-1111-4111-8111-111111111111';
const turn='22222222-2222-4222-8222-222222222222';
const id='a'.repeat(64);
const hash=value=>createHash('sha256').update(value).digest('hex');
let getterReads=0, proxyReads=0;
const failure=new Error(secret+'-error');
Object.defineProperty(failure,'status',{get(){getterReads++;throw Error(secret+'-status')}});
const args=[secret,{text:secret,body:secret,path:secret,env:secret,credentials:secret}];
const source=Object.freeze({threadId:thread,sourceTurnId:turn,...args[1]});
const suggestion={id,...args[1]};
Object.defineProperty(suggestion,'text',{get(){getterReads++;throw failure}});
Object.freeze(suggestion);
const envelope=Object.freeze({suggestion,...args[1]});
const empty=Object.freeze({suggestion:null,...args[1]});
function invoke(target,method,value) {
  target.value=value;
  const actual=target[method](...args);
  assert.equal(actual,value,'Return/Promise identity must remain unchanged');
  assert.equal(target.observed.receiver,target);
  assert.deepEqual(target.observed.args,args);
  return actual;
}
const session=new RuntimeSession();
const observer=new DesktopSuggestionObserver();
const probe=new DesktopSuggestionObserver();
const client=new DesktopSuggestionWorkerClient();
const predictions=new DesktopPredictionTraceReader();
let ordinal=0;
client.worker={threadId};
client.operation=function(...received) {
  assert.equal(this,client); assert.deepEqual(received,args);
  predictions.value=ordinal===1?[secret]:null;
  assert.equal(predictions.readSourcePredictions(...args),predictions.value);
  invoke(probe,'boundReadAvailable',true);
  invoke(probe,'windowsInstanceStable',true);
  return this.value;
};
observer.operation=function(...received) {
  assert.equal(this,observer); assert.deepEqual(received,args);
  const actual=client.read(...received);
  assert.equal(actual,client.value);
  return actual;
};
const server=new http.Server(), response=new EventEmitter();
response.statusCode=200;
let trial;
server.on('request',()=>{
  trial=(async()=>{
    for(let i=0;i<3;i++) {
      const p=Promise.resolve(source);
      assert.equal(invoke(session,'desktopSuggestionSource',p),p);
      assert.equal(await p,source);
      if(i===2)break;
      ordinal++;
      client.nextId=ordinal-1;
      const value=ordinal===1?envelope:empty;
      const pending=Promise.resolve(value);
      client.value=pending;
      assert.equal(invoke(observer,'readBoundSourceAsync',pending),pending);
      assert.equal(await pending,value);
    }
    response.emit('finish');
  })();
});
server.emit('request',{method:'GET',url:'/v1/threads/next-turn-suggestion?token='+secret,headers:{authorization:secret},body:secret},response);
await trial;
delete client.operation; delete observer.operation;
invoke(predictions,'readSourcePredictions',null);
invoke(predictions,'readSourcePredictions',[]);
const mutablePredictions=[secret,secret];
invoke(predictions,'readSourcePredictions',mutablePredictions);
mutablePredictions.length=0;
invoke(probe,'boundReadAvailable',false);
invoke(probe,'windowsInstanceStable',false);
invoke(probe,'workerReadiness',true);
invoke(probe,'workerReadiness',false);
invoke(observer,'readBoundSource',envelope);
invoke(observer,'readBoundSource',empty);
const accessorEnvelope={get suggestion(){getterReads++;throw failure}};
invoke(observer,'readBoundSource',accessorEnvelope);
const accessorSource={get threadId(){getterReads++;throw failure},get sourceTurnId(){getterReads++;throw failure}};
invoke(session,'desktopSuggestionSource',accessorSource);
const opaque=new Proxy({suggestion:envelope.suggestion},{
  get(){proxyReads++;throw failure},getOwnPropertyDescriptor(){proxyReads++;throw failure},getPrototypeOf(){proxyReads++;throw failure}
});
invoke(observer,'readBoundSource',opaque);
const protectedPromise=Promise.resolve(envelope);
Object.defineProperty(protectedPromise,'then',{get(){getterReads++;throw failure}});
assert.equal(invoke(observer,'readBoundSourceAsync',protectedPromise),protectedPromise);
assert.equal(await protectedPromise,envelope);
const mutableEnvelope={suggestion};
const mutablePromise=Promise.resolve(mutableEnvelope);
assert.equal(invoke(observer,'readBoundSourceAsync',mutablePromise),mutablePromise);
assert.equal(await mutablePromise,mutableEnvelope);
mutableEnvelope.suggestion=null;
session.failure=failure;
assert.throws(()=>session.desktopSuggestionSource(...args),error=>error===failure);
session.failure=null;
const rejected=Promise.reject(failure);
assert.equal(invoke(session,'desktopSuggestionSource',rejected),rejected);
await assert.rejects(rejected,error=>error===failure);
Object.defineProperty(client,'nextId',{get(){getterReads++;throw failure}});
Object.defineProperty(client,'worker',{get(){getterReads++;throw failure}});
invoke(client,'read',envelope);
// Observing an exotic Promise constructor would execute application code.
// Keep the original Promise and explicitly report that span as pending instead.
const exotic=Promise.resolve(source);
Object.defineProperty(exotic,'constructor',{configurable:true,get(){getterReads++;throw failure}});
assert.equal(invoke(session,'desktopSuggestionSource',exotic),exotic);
delete exotic.constructor;
await exotic;
if(workerData?.shared) {
  const control=new Int32Array(workerData.shared);
  Atomics.store(control,0,1);
  assert.equal(Atomics.wait(control,0,0,1),'not-equal');
}
const lateResponse=new EventEmitter();
Object.defineProperty(lateResponse,'statusCode',{get(){getterReads++;throw failure}});
new http.Server().emit('request',{method:'GET',url:'/healthz'},lateResponse);
function finish() {
  assert.equal(getterReads,0); assert.equal(proxyReads,0);
  const output=writeTimingEvidence();
  const text=readFileSync(output,'utf8');
  for(const sensitive of [secret,thread,turn,id])assert(!text.includes(sensitive),'Private value leaked');
  const summary=JSON.parse(text);
  const span=summary.rows.find(row=>row.name==='HTTP /v1/threads/next-turn-suggestion');
  assert.equal(span.httpStatus,200);
  const sources=summary.rows.filter(row=>row.name==='RuntimeSession.desktopSuggestionSource'&&row.parentSequence===span.sequence);
  assert.equal(sources.length,3);
  assert(sources.every(row=>row.sourceTupleHash===hash(JSON.stringify([thread,turn]))));
  const reads=summary.rows.filter(row=>row.name==='DesktopSuggestionObserver.readBoundSourceAsync'&&row.parentSequence===span.sequence);
  assert.equal(reads.length,2);
  assert.deepEqual(reads.map(row=>row.resultNull),[false,true]);
  assert.deepEqual(reads.map(row=>row.suggestionIdHash),[hash(id),null]);
  for(const [index,read] of reads.entries()) {
    const child=summary.rows.find(row=>row.name==='DesktopSuggestionWorkerClient.read'&&row.parentSequence===read.sequence);
    assert.equal(child.nextWorkerReadOrdinal,index+1);
    assert.equal(child.workerThreadId,threadId);
    assert.equal(child.resultNull,read.resultNull);
    assert.equal(child.suggestionIdHash,read.suggestionIdHash);
    const recovery=summary.rows.find(row=>row.name==='DesktopPredictionTraceReader.readSourcePredictions'&&row.parentSequence===child.sequence);
    assert.equal(recovery.resultNull,index===1);
    assert.equal(recovery.resultCount,index===1?null:1);
  }
  const recovered=summary.rows.filter(row=>row.name==='DesktopPredictionTraceReader.readSourcePredictions');
  assert(recovered.some(row=>row.resultNull===true&&row.resultCount===null));
  assert(recovered.some(row=>row.resultNull===false&&row.resultCount===0));
  assert(recovered.some(row=>row.resultNull===false&&row.resultCount===2));
  const immediate=summary.rows.filter(row=>row.name==='DesktopSuggestionObserver.readBoundSourceAsync'&&row.parentSequence===null);
  assert.equal(immediate.length,2);
  assert(immediate.every(row=>row.resultNull===false&&row.suggestionIdHash===hash(id)),'Snapshot primitives before callers mutate their result');
  for(const method of ['boundReadAvailable','windowsInstanceStable','workerReadiness']) {
    const available=summary.rows.filter(row=>row.name==='DesktopSuggestionObserver.'+method);
    assert(available.some(row=>row.availability===true));
    assert(available.some(row=>row.availability===false));
  }
  const failed=summary.rows.filter(row=>row.name==='RuntimeSession.desktopSuggestionSource'&&row.outcome==='error');
  assert.equal(failed.length,2);
  assert(failed.every(row=>!Object.hasOwn(row,'status')&&!Object.hasOwn(row,'sourceTupleHash')));
  assert.equal(summary.complete,false);
  assert.equal(summary.dropped,0);
  assert.deepEqual(summary.pendingAtStop,['RuntimeSession.desktopSuggestionSource','HTTP /healthz']);
  for(const row of summary.rows)for(const key of ['resultNull','resultCount','suggestionIdHash','sourceTupleHash','availability'])
    if(Object.hasOwn(row,key)) assert(row[key]===null||['boolean','number','string'].includes(typeof row[key]));
  if(workerData?.kind==='windows-suggestion-reader') {
    const bound=summary.rows.filter(row=>row.name==='DesktopSuggestionObserver.readBoundSource'&&Number.isInteger(row.workerReadOrdinal));
    assert.deepEqual(bound.map(row=>row.workerReadOrdinal),[1,2]);
    assert.deepEqual(bound.map(row=>row.resultNull),[false,true]);
    assert.deepEqual(summary.rows.filter(row=>row.name==='worker-result-post').map(row=>row.workerReadOrdinal),[1,2]);
  }
  if(workerData?.shared)assert(summary.rows.some(row=>row.name==='Atomics.wait'));
  // Every expired wrapper forwards directly, without touching diagnostic fields.
  invoke(client,'read',opaque);
  invoke(observer,'readBoundSource',accessorEnvelope);
  invoke(session,'desktopSuggestionSource',accessorSource);
  const request={get url(){getterReads++;throw failure}};
  new http.Server().emit('request',request,{});
  lateResponse.emit('finish');
  assert.equal(getterReads,0); assert.equal(proxyReads,0);
  if(isMainThread) {
    const trace=new URL('./apps/host-agent/node_modules/@codex-manage/codex-adapter/dist/desktop-trace-native-worker.js',import.meta.url);
    const options={execArgv:[]};
    new workerThreads.Worker(trace,options);
    assert.equal(globalThis.workerConstructions.at(-1).args[1],options,'Expired broker hook must not add a preload');
  }
  assert.equal(writeTimingEvidence(),output);
  assert.equal(readFileSync(output,'utf8'),text);
}
if(workerData?.kind==='windows-suggestion-reader') {
  let count=0;
  parentPort.on('message',message=>{
    count++;
    const reader=new DesktopSuggestionObserver();
    const value=count===1?envelope:empty;
    invoke(reader,'readBoundSource',value);
    parentPort.postMessage({kind:'result',id:message.id,value:{suggestion:count===1?{id,text:secret}:null}});
    if(count===2){finish();parentPort.close()}
  });
  parentPort.postMessage({kind:'ready'});
} else {
  finish();
  if(isMainThread)process.stdout.write('offline-summary-ok');
  else parentPort.close();
}
`,
      );
      const env = { ...environment, NODE_OPTIONS: '--import=' + pathToFileURL(f.guard).href };
      if (role === 'main') {
        const result = await run(
          process.execPath,
          ['--import', pathToFileURL(f.guard).href, entry],
          {
            env,
            timeout: 15000,
          },
        );
        assert.equal(result.stdout, 'offline-summary-ok');
        assert.equal(result.stderr, '');
      } else {
        worker = new Worker(pathToFileURL(entry), {
          execArgv: ['--import', pathToFileURL(f.guard).href],
          env,
          workerData:
            role === 'suggestion-reader'
              ? { kind: 'windows-suggestion-reader', windowsProcessGate: new SharedArrayBuffer(4) }
              : { shared: new SharedArrayBuffer(8), windowsProcessGate: new SharedArrayBuffer(4) },
        });
        let replies = 0;
        await new Promise((resolve, reject) => {
          worker.once('error', reject);
          worker.once('exit', (code) =>
            code === 0 ? resolve() : reject(Error('Offline summary Worker failed')),
          );
          worker.on('message', (message) => {
            if (message.kind === 'ready') worker.postMessage({ kind: 'read', id: 100 });
            if (message.kind === 'result' && ++replies === 1)
              worker.postMessage({ kind: 'read', id: 101 });
          });
        });
        assert.equal(replies, role === 'suggestion-reader' ? 2 : 0);
      }
      const base = join(f.program, '.local/windows-suggestion-timing');
      const directories = await readdir(base);
      assert.equal(directories.length, 1);
      const summary = JSON.parse(await readFile(join(base, directories[0], 'timing.json'), 'utf8'));
      assert.equal(summary.role, role);
    },
  );

for (const [role, scenario] of [
  ['main', 'contended'],
  ['suggestion-reader', 'contended'],
  ['native-channel', 'contended'],
  ['main', 'timeout'],
  ['main', 'missing-nominal'],
  ['main', 'missing-actual'],
  ['main', 'unshared-actual'],
  ['main', 'invalid-module'],
  ['suggestion-reader', 'missing-buffer'],
  ['native-channel', 'invalid-buffer'],
])
  test(
    `timing Windows resolver leaf uses real shared gate: ${role}/${scenario} (offline)`,
    { timeout: 20000 },
    async (t) => {
      const f = await resolverGateFixture(scenario);
      let worker;
      t.after(async () => {
        if (worker) await worker.terminate();
        await rm(f.root, { recursive: true, force: true });
      });
      const env = { ...environment, NODE_OPTIONS: '--import=' + pathToFileURL(f.guard).href };
      if (role === 'main') {
        const result = await run(
          process.execPath,
          ['--import', pathToFileURL(f.guard).href, f.entry],
          {
            env,
            timeout: 15000,
          },
        );
        assert.equal(result.stdout, 'offline-resolver-gate-ok');
        assert.equal(result.stderr, '');
      } else {
        const data =
          role === 'suggestion-reader'
            ? { kind: 'windows-suggestion-reader' }
            : { shared: new SharedArrayBuffer(8) };
        if (scenario !== 'missing-buffer')
          data.windowsProcessGate = new SharedArrayBuffer(scenario === 'invalid-buffer' ? 8 : 4);
        worker = new Worker(pathToFileURL(f.entry), {
          execArgv: ['--import', pathToFileURL(f.guard).href],
          env,
          workerData: data,
        });
        await new Promise((resolve, reject) => {
          worker.once('error', reject);
          worker.once('exit', (code) =>
            code === 0 ? resolve() : reject(Error('Offline resolver gate Worker failed')),
          );
        });
      }
    },
  );

test('prepared Main continuations preserve original Promise/receiver/args/errors and expose late acceptance/fail fences', async (t) => {
  const s = await observeOffline(
    t,
    'main',
    '',
    `
const client=new DesktopSuggestionWorkerClient();
const observer=new DesktopSuggestionObserver();
const args=[secret,{text:secret,path:secret,hostId:secret,threadId:secret}];
let commits=0,disposes=0,getters=0;
const failure=new Error(secret);
Object.defineProperty(failure,'status',{get(){getters++;throw failure}});
const result=Object.freeze({get suggestion(){getters++;throw failure}});
const committed=Promise.resolve(result);
const receipt={commit(...received){assert.equal(this,receipt);assert.deepEqual(received,args);commits++;return committed},
  dispose(...received){assert.equal(this,receipt);assert.deepEqual(received,args);disposes++;return result}};
const descriptor=Object.getOwnPropertyDescriptor(receipt,'commit');
const prepared=Promise.resolve(receipt);
client.nextId=0;client.generation=7;client.worker={threadId:0};client.value=prepared;
assert.equal(client.prepare(...args),prepared);
assert.equal(await prepared,receipt);
client.nextId=secret;client.generation=secret;client.worker={threadId:secret};
const emptyPrepared=Promise.resolve(null);client.value=emptyPrepared;
assert.equal(client.prepare(...args),emptyPrepared);assert.equal(await emptyPrepared,null);
client.nextId=1;client.generation=7;client.worker={threadId:0};
for(const key of ['writable','configurable','enumerable'])assert.equal(Object.getOwnPropertyDescriptor(receipt,'commit')[key],descriptor[key]);
assert.equal(receipt.commit(...args),committed);assert.equal(await committed,result);
const outer={commit(...received){assert.equal(this,outer);assert.deepEqual(received,args);return Reflect.apply(receipt.commit,receipt,received)},
  dispose(...received){assert.equal(this,outer);assert.deepEqual(received,args);return Reflect.apply(receipt.dispose,receipt,received)}};
const outerPromise=Promise.resolve(outer);observer.value=outerPromise;
assert.equal(observer.prepareBoundSourceAsync(...args),outerPromise);assert.equal(await outerPromise,outer);
assert.equal(outer.commit(...args),committed);
assert.equal(outer.dispose(...args),result);
client.prepared={id:1,phase:'preparing',generation:7,startedAt:performance.now()-4001,deadlineAt:performance.now()+1000};
client.operation=function(...received){assert.equal(this,client);assert.equal(received[0],this.worker);this.prepared.phase='prepared';return result};
const message={kind:'prepared',available:true,get value(){getters++;throw failure},hostId:secret,nonce:secret};
assert.equal(client.acceptFenced(client.worker,message),result);
client.operation=function(){this.prepared=undefined;throw failure};
assert.throws(()=>client.acceptFenced(client.worker,{kind:'fenced-result',error:failure,available:true}),e=>e===failure);
client.prepared={phase:'committing',startedAt:performance.now(),commitAt:performance.now(),deadlineAt:performance.now()-1,generation:7};
client.operation=function(...received){assert.equal(this,client);this.prepared=undefined;return received[1]};
assert.equal(client.fail(client.worker,failure),failure);
const failed=Promise.reject(failure);observer.value=failed;
assert.equal(observer.prepareBoundSourceAsync(...args),failed);await assert.rejects(failed,e=>e===failure);
observer.failure=failure;assert.throws(()=>observer.prepareBoundSource(...args),e=>e===failure);
observer.failure=null;
assert.equal(getters,0);
assert.equal(commits,2);assert.equal(disposes,1);
const before=evidence();assert.equal(before.complete,true);
assert.equal(receipt.commit(...args),committed);assert.equal(commits,3);
assert.equal(getters,0);
`,
  );
  const prepares = s.rows.filter((r) => r.name === 'DesktopSuggestionWorkerClient.prepare');
  assert.equal(prepares.length, 2);
  assert.equal(Object.hasOwn(prepares[1], 'nextWorkerReadOrdinal'), false);
  assert.equal(Object.hasOwn(prepares[1], 'workerGeneration'), false);
  assert.equal(Object.hasOwn(prepares[1], 'workerThreadId'), false);
  assert.equal(prepares[0].nextWorkerReadOrdinal, 1);
  assert.equal(prepares[0].workerGeneration, 7);
  assert.equal(prepares[0].receiptObservationComplete, true);
  const accept = s.rows.filter((r) => r.name === 'DesktopSuggestionWorkerClient.acceptFenced');
  assert.equal(accept.length, 2);
  assert.equal(accept[0].pendingPhase, 'preparing');
  assert.equal(accept[0].pendingWorkerReadOrdinal, 1);
  assert(accept[0].phaseElapsedMs >= 4000);
  assert.equal(accept[0].requestDeadlineExpired, false);
  assert.equal(accept[0].availability, true);
  assert.equal(accept[1].outcome, 'error');
  assert.equal(accept[1].errorPresent, true);
  assert.equal(accept[1].availability, true); // observed, never interpreted as successful readiness
  const after = s.rows.filter((r) => r.name === 'fenced-acceptance-after');
  assert.equal(after[0].pendingPhase, 'prepared');
  assert.equal(after[1].pendingPresent, false);
  const fail = s.rows.find((r) => r.name === 'DesktopSuggestionWorkerClient.fail');
  assert.equal(fail.pendingPhase, 'committing');
  assert.equal(fail.requestDeadlineExpired, true);
  assert(
    s.rows.some(
      (r) =>
        r.name === 'DesktopSuggestionObserver.prepareBoundSourceAsync' && r.outcome === 'error',
    ),
  );
  assert(
    s.rows.some(
      (r) => r.name === 'DesktopSuggestionObserver.prepareBoundSource' && r.outcome === 'error',
    ),
  );
  assert(s.rows.some((r) => r.name === 'DesktopSuggestionObserver.prepareBoundSourceAsync.commit'));
});

test('real Worker prepare/commit FS spans, fresh booleans and failure posts keep one ordinal and reject stale-generation correlation', async (t) => {
  const s = await observeOffline(
    t,
    'suggestion-reader',
    fsObservationBootstrap,
    `
let count=0,current,originalCommit,receipt,getters=0;
const failure=new Error(secret);
Object.defineProperty(failure,'status',{get(){getters++;throw failure}});
const args=[secret,{path:secret,threadId:secret}];
const request=(phase,ordinal,generation=7)=>parentPort.postMessage({preparedPhaseReady:true,phase,ordinal,generation});
await new Promise(resolve=>{
  parentPort.on('message',message=>{
    if(message.kind==='prepare'){
      count++;
      const reader=new DesktopPredictionTraceReader(), probe=new DesktopSuggestionObserver();
      probe.value=true;
      const frozen=Object.freeze({commit(){fs.readSync(fsFixture.sentinel);return []},dispose(){}});
      originalCommit=frozen.commit;
      reader.operation=function(...received){assert.equal(this,reader);assert.deepEqual(received,args);fs.lstatSync(fsFixture.sentinel);return frozen};
      const observer=new DesktopSuggestionObserver();
      observer.operation=function(...received){
        assert.equal(this,observer);assert.deepEqual(received,args);
        probe.boundReadAvailable();
        assert.equal(reader.prepareSourcePredictions(...received),frozen);
        current={commit(...received){assert.equal(this,current);assert.deepEqual(received,args);
          frozen.commit();assert.equal(frozen.commit,originalCommit);
          probe.boundReadAvailable();if(count===2)throw failure;
          return {suggestion:null,body:secret}},dispose(){return undefined}};
        return current;
      };
      receipt=observer.prepareBoundSource(...args);assert.equal(receipt,current);
      parentPort.postMessage({kind:'prepared',id:message.id,generation:7,available:true});
      request('commit',message.id,count===2?99:7);
    }else if(message.kind==='commit'&&message.generation===99){
      request('commit',message.id,7);
    }else{
      if(count===1){
        const value=receipt.commit(...args);
        parentPort.postMessage({kind:'fenced-result',id:message.id,generation:7,available:true,value});
      }else{
        assert.throws(()=>receipt.commit(...args),e=>e===failure);
        parentPort.postMessage({kind:'fenced-result',id:message.id,generation:7,error:{status:503}});
      }
      receipt.dispose();
      if(count===1)request('prepare',22);
      else {assert.equal(getters,0);evidence();resolve()}
    }
  });
  request('prepare',11);
});
`,
  );
  const fs = s.rows.filter((r) => r.name === 'prediction-scan-fs');
  assert.deepEqual(
    fs.map((r) => [r.phase, r.workerReadOrdinal]),
    [
      ['prepare', 1],
      ['commit', 1],
      ['prepare', 2],
      ['commit', 2],
    ],
  );
  assert.equal(fs[0].methods.lstatSync.count, 1);
  assert.equal(fs[1].methods.readSync.count, 1);
  assert.deepEqual(s.predictionScanFS.observedPhases, { legacy: 0, prepare: 2, commit: 2 });
  assert.equal(s.predictionScanFS.complete, true);
  const posts = s.rows.filter((r) => r.name === 'worker-phase-post');
  assert.deepEqual(
    posts.map((r) => [r.phase, r.workerReadOrdinal, r.availabilityPresent, r.errorPresent]),
    [
      ['prepared', 1, true, false],
      ['fenced-result', 1, true, false],
      ['prepared', 2, true, false],
      ['fenced-result', 2, false, true],
    ],
  );
  const stale = s.rows.find(
    (r) => r.name === 'worker-phase-dispatch' && r.matchedRequest === false,
  );
  assert.equal(stale.phase, 'commit');
  assert.equal(Object.hasOwn(stale, 'workerReadOrdinal'), false);
  assert(
    s.rows.some(
      (r) =>
        r.name === 'DesktopSuggestionObserver.prepareBoundSource.commit' && r.outcome === 'error',
    ),
  );
  assert.equal(s.preparedReadObservation.receiptHookFailures, 0);
  assert.equal(s.complete, true);
});

test('opaque/frozen prepared receipts and exotic Promise stay unchanged, no getters, explicitly incomplete', async (t) => {
  const s = await observeOffline(
    t,
    'main',
    '',
    `
let getters=0,proxies=0;
const observer=new DesktopSuggestionObserver(),parser=new DesktopPredictionTraceReader();
const failure=new Error(secret);
const frozen=Object.freeze({commit(){return secret},dispose(){}});
parser.value=frozen;assert.equal(parser.prepareSourcePredictions(secret),frozen);
assert.equal(frozen.commit(),secret);
for(const receipt of [frozen,{get commit(){getters++;throw failure},dispose(){}},
  new Proxy({}, {get(){proxies++;throw failure},getOwnPropertyDescriptor(){proxies++;throw failure}})]){
  observer.value=receipt;assert.equal(observer.prepareBoundSource(secret),receipt);
}
const exotic=Promise.resolve(frozen);
Object.defineProperty(exotic,'constructor',{configurable:true,get(){getters++;throw failure}});
observer.value=exotic;
assert.equal(observer.prepareBoundSourceAsync(secret),exotic);
delete exotic.constructor;await exotic;
assert.equal(getters,0);assert.equal(proxies,0);
const result=evidence();assert.equal(result.complete,false);
assert.equal(result.preparedReadObservation.receiptObservationComplete,false);
assert(result.pendingAtStop.includes('DesktopSuggestionObserver.prepareBoundSourceAsync'));
`,
  );
  assert.equal(s.preparedReadObservation.receiptHookFailures, 3);
  assert.equal(s.preparedReadObservation.receiptObservationComplete, false);
  assert.equal(s.complete, false);
});

test('pending Worker commit is not zero/complete; stop preserves Promise and late completion without new observations', async (t) => {
  const s = await observeOffline(
    t,
    'suggestion-reader',
    fsObservationBootstrap,
    `
let resolve;
const pending=new Promise(done=>resolve=done);
const receipt={commit(...args){assert.equal(this,receipt);assert.equal(args[0],secret);fs.lstatSync(fsFixture.sentinel);return pending},dispose(){}};
const observer=new DesktopSuggestionObserver();observer.value=receipt;
assert.equal(observer.prepareBoundSource(secret),receipt);
assert.equal(receipt.commit(secret),pending);
const result=evidence();assert.equal(result.complete,false);assert.equal(result.predictionScanFS.complete,false);
assert(result.pendingAtStop.includes('DesktopSuggestionObserver.prepareBoundSource.commit'));
resolve(secret);assert.equal(await pending,secret);assert.deepEqual(evidence(),result);
`,
  );
  const row = s.rows.find((r) => r.name === 'prediction-scan-fs' && r.phase === 'commit');
  assert.equal(row.pending, true);
  assert.equal(row.methods.lstatSync.count, 1);
  assert.equal(s.complete, false);
});

test('prepared receipt awaiting commit/dispose is explicitly pending even with no open Promise span', async (t) => {
  const s = await observeOffline(
    t,
    'main',
    '',
    `
const observer=new DesktopSuggestionObserver();
const receipt={commit(){return null},dispose(){}};
observer.value=receipt;
assert.equal(observer.prepareBoundSource(),receipt);
const result=evidence();assert.equal(result.complete,false);
assert(result.pendingAtStop.includes('DesktopSuggestionObserver.prepareBoundSource:prepared-continuation'));
assert.equal(result.preparedReadObservation.pendingContinuations[0].phase,'prepared');
// Recording stopped; dispose still performs the original operation.
assert.equal(receipt.dispose(),undefined);
`,
  );
  assert.equal(s.preparedReadObservation.pendingContinuations.length, 1);
  assert.equal(s.complete, false);
});
