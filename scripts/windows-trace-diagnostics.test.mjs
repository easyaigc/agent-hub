import assert from 'node:assert/strict';
import { channel } from 'node:diagnostics_channel';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { test, after } from 'node:test';
import fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import ts from 'typescript';
// Compile only these production modules into a private fixture; never overwrite dist.
const directory = fs.mkdtempSync(join(tmpdir(), 'trace-diagnostics-'));
fs.chmodSync(directory, 0o700);
const write = (name, text) => fs.writeFileSync(join(directory, name), text, { mode: 0o600 });
write('package.json', '{"type":"module"}');
const protocol = new URL('../packages/protocol/dist/index.js', import.meta.url).href;
for (const name of [
  'windows-trace-diagnostics',
  'desktop-suggestion-worker-client',
  'desktop-suggestion-worker-limits',
  'desktop-trace-native',
]) {
  const input = fs.readFileSync(
    new URL('../packages/codex-adapter/src/' + name + '.ts', import.meta.url),
    'utf8',
  );
  write(
    name + '.js',
    ts
      .transpileModule(input, {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
      })
      .outputText.replace(/(['"])@codex-manage\/protocol\1/g, JSON.stringify(protocol)),
  );
}
write(
  'windows-process.js',
  `export const getWindowsProcessGate=()=>new SharedArrayBuffer(4);export const registerWindowsProcessWorker=()=>{};`,
);
write('desktop-platform.js', `export const installWindowsTraceBackend=()=>{};`);
write(
  'desktop-private-state-native.js',
  `import {Worker,MessageChannel} from 'node:worker_threads';
export const registerWindowsTraceTransport=()=>{};
export const retireWindowsNativeOwner=async()=>true;
let mode='normal';export const setMode=value=>mode=value;
export const children=[];
export function createWindowsTraceTransport(_identity,_root,shared){
 const {port1,port2}=new MessageChannel();
 const worker=new Worker(new URL('./peer.mjs',import.meta.url),{workerData:{shared,port:port2,mode},transferList:[port2]});
 children.push(new Promise((resolve,reject)=>{worker.once('exit',resolve);worker.once('error',reject)}));
 return {port:port1,close(){port1.postMessage({kind:'close'});port1.close()}};
}`,
);
write(
  'peer.mjs',
  `import {workerData,parentPort} from 'node:worker_threads';
import {createHash} from 'node:crypto';import {win32} from 'node:path';
const {shared,port,mode}=workerData,control=new Int32Array(shared,0,2),bytes=new Uint8Array(shared,8);
const digest=s=>createHash('sha256').update(s).digest('base64');
const snapshot=item=>{const paths=[];for(let p=item.path;;p=win32.dirname(p)){paths.unshift(p);if(p===win32.parse(p).root)break}
 const chain=paths.map((_,i)=>'1:0:'+(i+1)+':0:0:'+digest('synthetic'));
 return {identity:digest(chain.join('|')),chain,bytes:Buffer.alloc(item.maximum,65).toString('base64')};};
port.on('message',request=>{
 if(request.kind==='close'){port.close();parentPort.close();return}
 if(mode==='timeout')return;
 let value=request.kind==='probe'?{ready:true}:request.kind==='tokens'?{tokens:[]}:request.kind==='snapshot'?snapshot({path:request.path,maximum:request.maximum}):{schemaVersion:1,kind:'snapshot-many',id:request.id,items:request.items.map(item=>({index:item.index,snapshot:snapshot(item)}))};
 const body=Buffer.from(mode==='bad-json'?'!':JSON.stringify(value));bytes.set(body);Atomics.store(control,1,body.length);Atomics.store(control,0,1);Atomics.notify(control,0);
});port.postMessage({kind:'ready'});`,
);
write(
  'client-peer.mjs',
  `import {parentPort,workerData} from 'node:worker_threads';import {randomUUID} from 'node:crypto';
let pending;const state=()=>parentPort.postMessage({kind:'state',available:true,sampledAt:performance.now()});state();
parentPort.on('message',r=>{
 if(r.kind==='close'){parentPort.close();return}if(r.kind==='abort'){pending=undefined;return}
 if(r.kind==='prepare'){if(workerData.scope.root==='hang')return;if(workerData.scope.root==='slow-prepare')Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,3200);pending={id:r.id,generation:r.generation,target:r.target,threadId:r.threadId,sourceTurnId:r.sourceTurnId,nonce:randomUUID()};parentPort.postMessage({kind:'prepared',...pending,available:true,sampledAt:performance.now()});return}
 if(r.kind==='commit'){parentPort.postMessage({kind:'fenced-result',...pending,available:true,sampledAt:performance.now(),value:{schemaVersion:1,readOnly:true,source:'native-trace',target:pending.target,threadId:pending.threadId,sourceTurnId:pending.sourceTurnId,suggestion:null}});pending=undefined}
});`,
);
const moduleURL = (name) => pathToFileURL(join(directory, name + '.js')).href;
const { createWindowsTraceDiagnostics } = await import(moduleURL('windows-trace-diagnostics'));
const { DesktopSuggestionWorkerClient } = await import(
  moduleURL('desktop-suggestion-worker-client')
);
const { createWindowsTraceBackend } = await import(moduleURL('desktop-trace-native'));
const nativeFixture = await import(moduleURL('desktop-private-state-native'));
after(async () => {
  await Promise.all(nativeFixture.children);
  fs.rmSync(directory, { recursive: true, force: true });
});

const run = promisify(execFile);
test('fixed diagnostics preserve return/exception identity and cannot serialize arbitrary fields', async () => {
  const events = [],
    summary = channel('agent-hub.windows-trace.summary');
  const receive = (event) => events.push(event);
  summary.subscribe(receive);
  try {
    const diagnostic = createWindowsTraceDiagnostics('reader', 3);
    const secret = { text: 'PRIVATE_MODEL_BODY', path: 'PRIVATE_TRACE_PATH' };
    assert.equal(
      diagnostic.phase('prepare', () => secret),
      secret,
    );
    const failure = new Error('PRIVATE_ERROR_WITH_CREDENTIAL');
    assert.throws(
      () =>
        diagnostic.phase('verify', () => {
          throw failure;
        }),
      (error) => error === failure,
    );
    assert.equal(await diagnostic.asyncPhase('commit', async () => secret), secret);
    diagnostic.phase('PRIVATE_PHASE', () => secret);
    diagnostic.end('native_rejected', { request: 9, remainingMs: 4, body: secret, error: failure });
    diagnostic.end('PRIVATE_REASON', { body: secret });
    const output = JSON.stringify(events);
    assert(!output.includes('PRIVATE_'));
    assert.equal(events.length, 2);
    assert.equal(events[0].phases.prepare.calls, 1);
    assert.equal(events[0].phases.verify.calls, 1);
    assert.equal(events[0].phases.commit.calls, 1);
    assert.equal(events[0].request, 9);
    assert.equal(events[1].reason, 'invalid_message');
  } finally {
    summary.unsubscribe(receive);
  }
});

test('diagnostic toggle keeps the same result and emits only terminal numeric metadata', async () => {
  const script = `import {createWindowsTraceDiagnostics as create} from ${JSON.stringify(moduleURL('windows-trace-diagnostics'))};
const d=create('native');const value=d.phase('parse',()=>({text:'PRIVATE_MODEL_BODY'}));
d.end('phase_deadline',{request:2,remainingMs:-1,childExited:false,body:value});console.log(JSON.stringify(value));`;
  const env = { ...process.env };
  delete env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
  const ordinary = await run(process.execPath, ['--input-type=module', '-e', script], { env });
  const observed = await run(process.execPath, ['--input-type=module', '-e', script], {
    env: { ...env, AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS: '1' },
  });
  assert.equal(observed.stdout, ordinary.stdout);
  assert.equal(ordinary.stderr, '');
  const rows = observed.stderr.trim().split('\n').map(JSON.parse);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].windowsTrace.reason, 'phase_deadline');
  assert.equal(rows[0].windowsTrace.childExited, false);
  assert(!observed.stderr.includes('PRIVATE_MODEL_BODY'));
});

test('subscriber mutation cannot inject fields into stderr and unknown getters are not evaluated', async () => {
  const script = `import {channel} from 'node:diagnostics_channel';import {createWindowsTraceDiagnostics as create} from ${JSON.stringify(moduleURL('windows-trace-diagnostics'))};
channel('agent-hub.windows-trace.summary').subscribe(event=>{event.body='PRIVATE_BODY';event.phases.secret={path:'PRIVATE_PATH'}});
const fields={request:2,parserReadSnapshotCalls:3,parserCatalogWalks:4,parserGuardCount:5};
Object.defineProperty(fields,'secret',{get(){throw Error('DO_NOT_READ')}});
create('reader').end('ok',fields);`;
  const { stderr } = await run(process.execPath, ['--input-type=module', '-e', script], {
    env: { ...process.env, AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS: '1' },
  });
  const event = JSON.parse(stderr).windowsTrace;
  assert.equal(event.operation, 'parser');
  assert.equal(event.stage, 'snapshot');
  assert.equal(event.window, 'snapshot');
  assert.deepEqual(
    [event.parserReadSnapshotCalls, event.parserCatalogWalks, event.parserGuardCount],
    [3, 4, 5],
  );
  assert(!stderr.includes('PRIVATE_'));
});

test('disabled native metrics do not create samples or read the clock', async () => {
  const script = `import {createWindowsTraceDiagnostics as create} from ${JSON.stringify(moduleURL('windows-trace-diagnostics'))};
const d=create('reader');const original=performance.now;performance.now=()=>{throw Error('NEW_HOT_LOOP_CLOCK')};
try{if(d.enabled||d.nativeCall('S')!==undefined)throw Error('ENABLED')}finally{performance.now=original}`;
  const env = { ...process.env };
  delete env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS;
  assert.equal(
    (await run(process.execPath, ['--input-type=module', '-e', script], { env })).stderr,
    '',
  );
});

test('parser integer projection and pending native samples are bounded and preserve decode exceptions', () => {
  const events = [],
    c = channel('agent-hub.windows-trace.summary'),
    receive = (e) => events.push(e);
  c.subscribe(receive);
  try {
    const d = createWindowsTraceDiagnostics('reader', 4, { operation: 'read', request: 2 });
    const first = d.nativeCall('B', 2),
      waiting = d.nativeCall('S');
    const error = new Error('PRIVATE_DECODE');
    first.waited();
    assert.throws(
      () =>
        first.decode(() => {
          throw error;
        }),
      (e) => e === error,
    );
    first.end(false);
    first.end(true);
    d.nativeCall('PRIVATE_KIND');
    d.end('native_rejected', {
      parserReadSnapshotCalls: -1,
      parserCatalogWalks: 1.5,
      parserGuardCount: Number.MAX_SAFE_INTEGER + 1,
    });
    const event = events[0];
    assert.deepEqual(Object.keys(event.native).sort(), ['B', 'S']);
    assert.equal(event.native.B.failed, 1);
    assert.equal(event.native.B.pending, 0);
    assert.equal(event.native.B.items, 2);
    assert.equal(event.native.S.pending, 1);
    assert(!Object.hasOwn(event, 'parserGuardCount'));
    assert(!Object.hasOwn(event, 'parserReadSnapshotCalls'));
    waiting.end(false);
  } finally {
    c.unsubscribe(receive);
  }
});

async function until(check, ms = 3000) {
  const end = performance.now() + ms;
  while (!check()) {
    assert(performance.now() < end, 'actual fixture event missing');
    await delay(5);
  }
}
const target = {
  agentId: 'codex',
  hostId: 'diagnostic-fixture',
  workspaceId: 'synthetic',
  runtimeId: 'desktop-runtime',
};
async function withClient(root, body, observe = true) {
  const c = channel('agent-hub.windows-trace.summary'),
    events = [],
    receive = (e) => events.push(e);
  if (observe) c.subscribe(receive);
  const client = new DesktopSuggestionWorkerClient(
    { directory, root, traceRoot: directory, target },
    pathToFileURL(join(directory, 'client-peer.mjs')),
  );
  client.start();
  const worker = client.worker;
  const exited = new Promise((resolve) => worker.once('exit', resolve));
  try {
    await until(() => client.available);
    await body(client, events);
  } finally {
    client.close();
    await exited;
    if (observe) {
      await until(() =>
        events.some((e) => e.operation === 'retirement' && e.readerExited === true),
      );
      c.unsubscribe(receive);
    } else {
      await until(() => client.retiring.size === 0);
    }
  }
}

test('actual client separates prepare and commit summaries across two requests and retirement', async () => {
  await withClient('normal', async (client, events) => {
    for (let i = 1; i <= 2; i++) {
      const p = await client.prepare('thread', 'turn', performance.now() + 10000);
      await p.commit();
      p.dispose();
    }
    const reads = events.filter((e) => e.operation === 'read');
    assert.equal(reads.length, 4);
    for (let i = 1; i <= 2; i++) {
      const phases = reads.filter((e) => e.request === i);
      assert.equal(phases.length, 2);
      assert.equal(phases[0].phases.prepare.calls, 1);
      assert.equal(phases[0].phases.commit, undefined);
      assert.equal(phases[1].phases.commit.calls, 1);
      assert.equal(phases[1].phases.prepare, undefined);
      assert.equal(phases[0].stage, 'prepared');
      assert.equal(phases[1].stage, 'committed');
      assert.equal(
        phases[1].selectedDeadlineKind,
        'phase',
        'Fast prepare leaves the original 4s commit phase as its selected boundary',
      );
      assert.equal(phases[1].deadlineMask, 1);
      assert(phases[1].phaseRemainingMs > 0 && phases[1].phaseRemainingMs <= 4000);
      assert(phases[1].remainingMs > phases[1].phaseRemainingMs);
      assert(!Object.hasOwn(phases[0], 'deadlineKind'));
    }
  });
});

test('commit completion keeps the shared boundary after a real slow prepare and publishes only after committed state', async () => {
  await withClient('slow-prepare', async (client, events) => {
    const started = performance.now();
    const p = await client.prepare('thread', 'turn', performance.now() + 20000);
    assert(
      performance.now() - started >= 3000,
      'Real prepare consumes enough of the original 7s shared budget',
    );
    let stateAtPublish;
    const summary = channel('agent-hub.windows-trace.summary');
    const receive = (event) => {
      if (event.scope === 'client' && event.operation === 'read' && event.stage === 'committed')
        stateAtPublish = client.prepared.phase;
    };
    summary.subscribe(receive);
    try {
      const value = await p.commit();
      assert.equal(value.suggestion, null);
      assert.equal(
        stateAtPublish,
        'committed',
        'State must change before synchronous diagnostic publication',
      );
      const completed = events.find((e) => e.operation === 'read' && e.stage === 'committed');
      assert.equal(completed.selectedDeadlineKind, 'shared_rpc');
      assert.equal(completed.deadlineMask, 2);
      assert(completed.phaseRemainingMs > 0 && completed.phaseRemainingMs < 4000);
      assert(
        Math.abs(completed.remainingMs - completed.phaseRemainingMs) < 20,
        'Both remaining values refer to the same shared boundary',
      );
      assert.equal(
        completed.deadlineKind,
        undefined,
        'Success never claims the selected deadline fired',
      );
      p.dispose();
    } finally {
      summary.unsubscribe(receive);
    }
  });
});

test('diagnostics off never samples deadlineFields on a real successful Worker commit', async () => {
  await withClient(
    'normal',
    async (client) => {
      let samples = 0;
      const original = client.deadlineFields;
      client.deadlineFields = function (...args) {
        samples++;
        return Reflect.apply(original, this, args);
      };
      try {
        const p = await client.prepare('thread', 'turn', performance.now() + 10000);
        assert.equal((await p.commit()).suggestion, null);
        p.dispose();
        assert.equal(samples, 0);
      } finally {
        client.deadlineFields = original;
      }
    },
    false,
  );
});

test('client reports the HTTP-clamped boundary without calling an abort a deadline', async () => {
  await withClient('hang', async (client, events) => {
    await assert.rejects(
      client.prepare('thread', 'turn', performance.now() + 40),
      (e) => e.status === 503,
    );
    const failed = events.find((e) => e.operation === 'read' && e.reason === 'phase_deadline');
    assert.equal(failed.deadlineKind, 'http');
    assert.equal(failed.deadlineMask, 4);
    assert.equal(failed.deadlineTriggered, true);
  });
  await withClient('normal', async (client, events) => {
    const signal = new AbortController();
    await client.prepare('thread', 'turn', performance.now() + 10000, signal.signal);
    signal.abort();
    const failed = events.find((e) => e.operation === 'read' && e.reason === 'aborted');
    assert.equal(failed.deadlineTriggered, false);
    assert.equal(failed.deadlineKind, undefined);
    assert.equal(failed.selectedDeadlineKind, 'shared_rpc');
  });
});

test('actual client phase timeout and prepared wait keep different deadline ownership', async () => {
  await withClient('hang', async (client, events) => {
    await assert.rejects(
      client.prepare('thread', 'turn', performance.now() + 20000),
      (e) => e.status === 503,
    );
    assert.equal(
      events.find((e) => e.operation === 'read' && e.reason === 'phase_deadline').deadlineKind,
      'phase',
    );
  });
  await withClient('normal', async (client, events) => {
    await client.prepare('thread', 'turn', performance.now() + 20000);
    await until(
      () => events.some((e) => e.operation === 'read' && e.reason === 'phase_deadline'),
      8000,
    );
    assert.equal(
      events.find((e) => e.operation === 'read' && e.reason === 'phase_deadline').deadlineKind,
      'shared_rpc',
    );
  });
});

test('actual Reader-side calls account P S T B, typed batch decode, failures and per-phase isolation', async () => {
  const events = [],
    c = channel('agent-hub.windows-trace.summary'),
    receive = (e) => events.push(e);
  c.subscribe(receive);
  let active = createWindowsTraceDiagnostics('reader', 2, {
    operation: 'read',
    phase: 'prepare',
    request: 1,
  });
  let backend;
  try {
    nativeFixture.setMode('normal');
    backend = await createWindowsTraceBackend({}, 'C:\\fixture', undefined, () => active);
    assert.equal(backend.backend.inspect().ready, true);
    assert.equal(
      backend.backend.snapshot('C:\\fixture', 'C:\\fixture\\one', 1).bytes.toString(),
      'A',
    );
    assert.deepEqual(backend.tokens(), { tokens: [] });
    const values = backend.backend.snapshotMany('C:\\fixture', [
      { path: 'C:\\fixture', maximum: 0 },
      { path: 'C:\\fixture\\one', maximum: 2 },
    ]);
    assert.equal(values[1].bytes.toString(), 'AA');
    active.end('ok');
    for (const kind of ['P', 'S', 'T', 'B']) {
      const v = events.at(-1).native[kind];
      assert.equal(v.calls, 1);
      assert.equal(v.completed, 1);
      assert.equal(v.failed, 0);
      assert.equal(v.pending, 0);
      assert(v.elapsedMs >= v.waitMs);
      assert(v.decodeMs >= 0);
    }
    assert.equal(events.at(-1).native.B.items, 2);
    active = createWindowsTraceDiagnostics('reader', 2, {
      operation: 'read',
      phase: 'commit',
      request: 1,
    });
    backend.backend.inspect();
    active.end('ok');
    assert.deepEqual(Object.keys(events.at(-1).native), ['P']);
    backend.close();
    backend = undefined;
    nativeFixture.setMode('bad-json');
    backend = await createWindowsTraceBackend({}, 'C:\\fixture', undefined, () => active);
    assert.throws(() => backend.tokens(), SyntaxError);
    active.end('native_rejected');
    assert.equal(events.at(-1).native.T.failed, 1);
    backend.close();
    backend = undefined;
    nativeFixture.setMode('timeout');
    active = createWindowsTraceDiagnostics('reader', 2, { operation: 'read', request: 2 });
    backend = await createWindowsTraceBackend({}, 'C:\\fixture', undefined, () => active);
    backend.budget(25);
    assert.throws(() => backend.backend.inspect(), /Trace native operation unavailable/);
    active.end('native_rejected');
    assert.equal(events.at(-1).native.P.failed, 1);
    assert.equal(events.at(-1).native.P.pending, 0);
    assert(events.some((e) => e.request === 2 && e.native.P?.pending === 1));
  } finally {
    backend?.close();
    c.unsubscribe(receive);
  }
});

test('native B8 summary counts all eight obligations without accepting an oversized count', () => {
  const events = [],
    summary = channel('agent-hub.windows-trace.summary');
  const receive = (event) => events.push(event);
  summary.subscribe(receive);
  try {
    const diagnostic = createWindowsTraceDiagnostics('reader', 1, {
      operation: 'parser',
    });
    diagnostic.nativeCall('B', 8).end(true);
    diagnostic.nativeCall('B', 9).end(false);
    diagnostic.end('ok');
    assert.equal(events[0].native.B.items, 8);
    assert.equal(events[0].native.B.calls, 2);
    assert.equal(events[0].native.B.completed, 1);
    assert.equal(events[0].native.B.failed, 1);
  } finally {
    summary.unsubscribe(receive);
  }
});
