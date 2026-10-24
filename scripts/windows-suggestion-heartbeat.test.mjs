import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Worker } from 'node:worker_threads';
import { setTimeout as delay } from 'node:timers/promises';
import { after, before, test } from 'node:test';
import ts from 'typescript';

// Actual production reader Worker + real timers/messages. Only observer/native
// boundaries are synthetic: these tests are not C#/NTFS or HTTP acceptance.
const sourceRoot = fileURLToPath(new URL('../packages/codex-adapter/src/', import.meta.url));
const protocol = new URL('../packages/protocol/dist/index.js', import.meta.url).href;
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const inputs = new Map();
let directory;
const target = {
  agentId: 'codex',
  hostId: 'heartbeat-host',
  workspaceId: 'heartbeat-workspace',
  runtimeId: 'desktop-runtime',
};

before(() => {
  directory = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'suggestion-heartbeat-')));
  fs.chmodSync(directory, 0o700);
  const write = (name, text) => fs.writeFileSync(join(directory, name), text, { mode: 0o600 });
  write('package.json', '{"type":"module"}');
  for (const name of [
    'desktop-suggestion-read-worker',
    'desktop-suggestion-worker-limits',
    'windows-trace-diagnostics',
  ]) {
    const file = join(sourceRoot, name + '.ts');
    const input = fs.readFileSync(file, 'utf8');
    inputs.set(file, hash(input));
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
    'boundary.mjs',
    `import {parentPort,workerData} from 'node:worker_threads';
export const options=workerData.options;
export const shared=new Int32Array(workerData.shared);
export const emit=(event,fields={})=>parentPort.postMessage({kind:'fixture-event',event,at:performance.now(),...fields});
export const block=ms=>{if(ms)Atomics.wait(shared,1,0,ms)};
export const ready=()=>Atomics.load(shared,0)===1;
`,
  );
  write('desktop-platform.js', `export const inspectWindowsPipe=()=>({pid:1,startedAtUnixMs:1});`);
  write(
    'desktop-trace-native.js',
    `import {setTimeout as delay} from 'node:timers/promises';
import {emit,options,ready,shared} from './boundary.mjs';
export async function createWindowsTraceBackend(_instance,_root,signal){
 emit('native-start');
 signal.addEventListener('abort',()=>emit('startup-abort'),{once:true});
 if(options.startupMs)await delay(options.startupMs);
 return {backend:{inspect(){emit('inspect');if(options.inspectThrows)throw Error('synthetic inspect');return {ready:ready()}}},
 budget(){emit('budget')},resetBudget(){emit('reset-budget')},close(){Atomics.add(shared,2,1);emit('native-close')}};
}`,
  );
  write(
    'desktop-suggestion-observer.js',
    `import {emit,options,ready,block} from './boundary.mjs';
export class DesktopSuggestionObserver{
 start(){emit('observer-start')}
 close(){emit('observer-close')}
 workerReadiness(instance){emit('readiness');return instance.ready}
 prepareBoundSource(target,threadId,sourceTurnId,onSample){
  emit('prepare-enter');block(options.prepareMs);
  if(options.prepareThrows)throw Error('synthetic prepare');
  onSample(options.prepareAvailable!==false);
  let disposed=false;
  return {dispose(){if(!disposed){disposed=true;block(options.disposeMs);emit('receipt-disposed');if(options.disposeThrows)throw Error('synthetic dispose')}},
   commit(onFinal){emit('commit-enter');block(options.commitMs);
    if(options.commitThrows)throw Error('synthetic commit');
    emit('final-guard');
    if(!ready())throw Object.assign(Error('synthetic changed instance'),{status:409});
    onFinal(options.commitAvailable!==false);
    return {schemaVersion:1,target,threadId,sourceTurnId,readOnly:true,source:'native-trace',suggestion:null};
   }};
 }
}`,
  );
  write(
    'entry.mjs',
    `import {parentPort,workerData} from 'node:worker_threads';
import {channel} from 'node:diagnostics_channel';
import {runSuggestionReadWorker} from './desktop-suggestion-read-worker.js';
channel('agent-hub.windows-trace.summary').subscribe(summary=>parentPort.postMessage({kind:'fixture-diagnostic',summary}));
await runSuggestionReadWorker(workerData.scope);
`,
  );
});

after(() => {
  try {
    for (const [file, expected] of inputs)
      assert.equal(hash(fs.readFileSync(file)), expected, 'Reader fixture source changed');
  } finally {
    if (directory) fs.rmSync(directory, { recursive: true, force: true });
  }
});

async function until(predicate, timeout = 3000) {
  const deadline = performance.now() + timeout;
  while (!predicate()) {
    assert(performance.now() < deadline, 'Expected actual Worker event was not observed');
    await delay(5);
  }
}

async function fixture(options, run, waitReady = true) {
  const shared = new SharedArrayBuffer(12);
  const control = new Int32Array(shared);
  Atomics.store(control, 0, 1);
  const worker = new Worker(pathToFileURL(join(directory, 'entry.mjs')), {
    workerData: {
      generation: 7,
      scope: { directory, root: directory, traceRoot: directory, target },
      shared,
      options,
    },
  });
  const messages = [],
    errors = [];
  let exited = false,
    exitCode;
  worker.on('message', (value) => messages.push(value));
  worker.on('error', (error) => errors.push(error));
  worker.on('exit', (code) => {
    exited = true;
    exitCode = code;
  });
  const count = (event) =>
    messages.filter((x) => x.kind === 'fixture-event' && x.event === event).length;
  const states = () => messages.filter((x) => x.kind === 'state');
  const closed = async () => {
    await until(() => exited);
    assert.equal(exitCode, 0);
    assert.deepEqual(errors, []);
  };
  let id = 0;
  async function prepare(ttl = 6500, extra = {}) {
    const request = {
      kind: 'prepare',
      id: ++id,
      generation: 7,
      target,
      threadId: randomUUID(),
      sourceTurnId: randomUUID(),
      deadlineAt: performance.now() + ttl,
      ...extra,
    };
    worker.postMessage(request);
    await until(() => messages.some((x) => x.kind === 'prepared' && x.id === request.id));
    return messages.find((x) => x.kind === 'prepared' && x.id === request.id);
  }
  const continuation = (p, kind = 'commit', extra = {}) => ({
    kind,
    id: p.id,
    generation: p.generation,
    nonce: p.nonce,
    target: p.target,
    threadId: p.threadId,
    sourceTurnId: p.sourceTurnId,
    ...extra,
  });
  async function commit(p) {
    const disposedBefore = count('receipt-disposed');
    worker.postMessage(continuation(p));
    await until(() => messages.some((x) => x.kind === 'fenced-result' && x.id === p.id));
    // The response precedes finally; it is not an acknowledgement of cleanup.
    // Wait for actual receipt evidence, including when its delivery is delayed.
    await until(() => count('receipt-disposed') > disposedBefore);
    return messages.find((x) => x.kind === 'fenced-result' && x.id === p.id);
  }
  async function cleanup() {
    if (!exited) worker.postMessage({ kind: 'close' });
    try {
      await closed();
    } catch (error) {
      await worker.terminate(); // Only this owned synthetic Worker, after failed cleanup.
      throw error;
    }
  }
  try {
    if (waitReady) await until(() => states().length === 1);
    await run({ worker, messages, control, count, states, prepare, continuation, commit, closed });
  } finally {
    await cleanup();
  }
}

test('overdue heartbeat cannot run between long prepare and commit; final guard and idle heartbeat remain', async () => {
  await fixture(
    // Keep a real result-to-cleanup window so the old helper deterministically races.
    { prepareMs: 1150, commitMs: 1100, disposeMs: 200 },
    async ({ prepare, commit, messages, count, states }) => {
      assert.equal(count('inspect'), 1, 'Initial native instance is sampled');
      assert.equal(count('readiness'), 1, 'Initial root/readiness is sampled');
      const p = await prepare();
      assert.equal(p.error, undefined);
      await delay(1200); // Real overdue periodic timer while Main/source fence holds the receipt.
      assert.equal(states().length, 1, 'No periodic state can precede the continuation');
      assert.equal(count('inspect'), 1);
      assert.equal(count('readiness'), 1);
      const result = await commit(p);
      assert.equal(result.error, undefined);
      assert.equal(result.available, true);
      assert.equal(count('final-guard'), 1, 'Receipt commit still supplies final freshness');
      assert.equal(count('receipt-disposed'), 1);
      assert.equal(states().length, 1, 'No queued state runs at commit completion');
      await until(() => states().length === 2);
      assert.equal(count('inspect'), 2, 'Idle instance sampling resumes');
      assert.equal(count('readiness'), 2);
      const diagnostic = (stage) =>
        messages.find(
          (x) =>
            x.kind === 'fixture-diagnostic' &&
            x.summary.operation === 'read' &&
            x.summary.stage === stage,
        )?.summary;
      await until(() => diagnostic('committed'));
      const summary = diagnostic('committed'),
        preparation = diagnostic('prepared');
      assert.equal(summary.phases.state, undefined, 'No periodic state is charged to commit');
      assert.equal(preparation.phases.state, undefined, 'No periodic state is charged to prepare');
      assert.equal(preparation.phases.prepare.calls, 1);
      assert.equal(summary.phases.prepare, undefined, 'Each phase has its own metrics');
      assert.equal(summary.phases.commit.calls, 1);
      assert(!messages.some((x) => x.kind === 'failed'));
    },
  );
});

test('abort releases the receipt, resumes idle sampling and clears the old expiry before a new prepare', async () => {
  await fixture({}, async ({ worker, prepare, continuation, commit, count, states, messages }) => {
    const old = await prepare(1800);
    worker.postMessage(continuation(old, 'abort'));
    await until(() => count('receipt-disposed') === 1);
    await until(() => states().length === 2);
    const next = await prepare();
    await delay(1100); // Cross the aborted receipt's deadline while the new receipt is paused.
    assert.equal(states().length, 2);
    assert(!messages.some((x) => x.kind === 'failed'));
    assert.equal((await commit(next)).error, undefined);
    assert.equal(count('receipt-disposed'), 2);
  });
});

for (const options of [{ prepareThrows: true }, { prepareAvailable: false }])
  test(`failed prepare resumes idle heartbeat: ${Object.keys(options)[0]}`, async () => {
    await fixture(options, async ({ prepare, states, count }) => {
      const result = await prepare();
      assert.deepEqual(result.error, { status: 503 });
      assert.equal(Object.hasOwn(result, 'value'), false);
      await until(() => states().length === 2);
      assert.equal(count('inspect'), 2);
      assert.equal(count('receipt-disposed'), options.prepareThrows ? 0 : 1);
    });
  });

for (const mode of ['commitThrows', 'commitAvailable', 'instanceChanged'])
  test(`failed commit retains freshness rejection and resumes idle heartbeat: ${mode}`, async () => {
    const options = mode === 'instanceChanged' ? {} : { [mode]: mode === 'commitThrows' };
    await fixture(options, async ({ prepare, commit, control, states, count }) => {
      const p = await prepare();
      if (mode === 'instanceChanged') Atomics.store(control, 0, 0);
      const result = await commit(p);
      assert.deepEqual(result.error, { status: mode === 'instanceChanged' ? 409 : 503 });
      assert.equal(Object.hasOwn(result, 'value'), false);
      assert.equal(count('receipt-disposed'), 1);
      assert.equal(count('final-guard'), mode === 'commitThrows' ? 0 : 1);
      await until(() => states().length === 2);
      assert.equal(states().at(-1).available, mode !== 'instanceChanged');
    });
  });

test('prepared expiry still disposes and closes without periodic native sampling', async () => {
  await fixture({}, async ({ prepare, closed, count, messages, states }) => {
    await prepare(1350);
    await closed();
    assert(messages.some((x) => x.kind === 'failed'));
    assert.equal(count('receipt-disposed'), 1);
    assert.equal(count('native-close'), 1);
    assert.equal(count('observer-close'), 1);
    assert.equal(states().length, 1);
    assert.equal(count('inspect'), 1);
  });
});

for (const mode of ['wrong-commit', 'wrong-abort', 'second-prepare'])
  test(`invalid continuation closes rather than resuming a paused worker: ${mode}`, async () => {
    await fixture(
      {},
      async ({ worker, prepare, continuation, closed, count, messages, states }) => {
        const p = await prepare();
        const invalid =
          mode === 'second-prepare'
            ? { kind: 'prepare', id: 2 }
            : continuation(p, mode === 'wrong-abort' ? 'abort' : 'commit', { nonce: randomUUID() });
        worker.postMessage(invalid);
        await closed();
        assert(messages.some((x) => x.kind === 'failed'));
        assert.equal(count('receipt-disposed'), 1);
        assert.equal(count('native-close'), 1);
        assert.equal(states().length, 1);
      },
    );
  });

test('explicit close disposes a paused receipt and never restarts heartbeat', async () => {
  await fixture({}, async ({ worker, prepare, closed, count, states }) => {
    await prepare();
    worker.postMessage({ kind: 'close' });
    await closed();
    assert.equal(count('receipt-disposed'), 1);
    assert.equal(count('native-close'), 1);
    assert.equal(states().length, 1);
  });
});

for (const action of ['abort', 'commit'])
  test(`receipt disposal failure closes without restarting heartbeat: ${action}`, async () => {
    await fixture(
      { disposeThrows: true },
      async ({ worker, prepare, continuation, closed, count, messages, states }) => {
        const p = await prepare();
        worker.postMessage(continuation(p, action));
        await closed();
        assert(messages.some((x) => x.kind === 'failed'));
        assert.equal(count('receipt-disposed'), 1);
        assert.equal(count('native-close'), 1);
        assert.equal(states().length, 1);
      },
    );
  });

test('close during native initialization closes the late backend without starting state sampling', async () => {
  await fixture(
    { startupMs: 200 },
    async ({ worker, closed, count, states, control }) => {
      await until(() => count('native-start') === 1);
      worker.postMessage({ kind: 'close' });
      await closed();
      assert.equal(Atomics.load(control, 2), 1, 'Late backend closes after parent port is closed');
      assert.equal(count('startup-abort'), 1);
      assert.equal(count('observer-start'), 0);
      assert.equal(states().length, 0);
    },
    false,
  );
});

test('initial state inspection failure closes instead of creating an idle heartbeat', async () => {
  await fixture(
    { inspectThrows: true },
    async ({ closed, count, messages, states }) => {
      await closed();
      assert(messages.some((x) => x.kind === 'failed'));
      assert.equal(count('inspect'), 1);
      assert.equal(count('native-close'), 1);
      assert.equal(states().length, 0);
    },
    false,
  );
});

test('Reader startup and entered/prepared/committed summaries identify their own phase', async () => {
  await fixture({ startupMs: 50 }, async ({ messages, prepare, commit }) => {
    const startup = messages.find(
      (x) => x.kind === 'fixture-diagnostic' && x.summary.operation === 'startup',
    ).summary;
    assert(startup.phases.native_start.elapsedMs >= 40);
    assert.equal(startup.request, undefined);
    const p = await prepare();
    await commit(p);
    const rows = messages
      .filter((x) => x.kind === 'fixture-diagnostic' && x.summary.operation === 'read')
      .map((x) => x.summary);
    assert.deepEqual(
      rows.map((x) => [x.request, x.phase, x.stage]),
      [
        [p.id, 'prepare', 'entered'],
        [p.id, 'prepare', 'prepared'],
        [p.id, 'commit', 'entered'],
        [p.id, 'commit', 'committed'],
      ],
    );
  });
});
