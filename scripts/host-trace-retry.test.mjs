// Real retention worker scheduling, synthetic reclamation only. No user Trace data.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Worker } from 'node:worker_threads';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

test('retained backlog backs off while fresh, active and recovered bundles remain eligible', async () => {
  await mkdir(resolve('.local'), { recursive: true, mode: 0o700 });
  const directory = await mkdtemp(resolve('.local/retention-retry-'));
  let worker;
  try {
    const root = join(directory, 'traces');
    await mkdir(root, { mode: 0o700 });
    const names = Array.from({ length: 225 }, () => `trace-${randomUUID()}-${randomUUID()}`);
    await Promise.all(names.map((name) => mkdir(join(root, name))));
    const source = await readFile(
      new URL('../packages/codex-adapter/dist/desktop-trace-retention-worker.js', import.meta.url),
      'utf8',
    );
    await writeFile(
      join(directory, 'worker.mjs'),
      source
        .replace(
          "'@codex-manage/protocol'",
          JSON.stringify(new URL('../packages/protocol/dist/index.js', import.meta.url).href),
        )
        .replace("'./desktop-trace-retention.js'", "'./retention.mjs'")
        .replace("'./desktop-trace-native.js'", "'./platform.mjs'")
        .replace("'./desktop-platform.js'", "'./platform.mjs'"),
    );
    await writeFile(
      join(directory, 'platform.mjs'),
      `
export const createWindowsTraceBackend=()=>{throw Error('unused Windows path')};
export const closeWindowsPrivateStateBackend=()=>{};
export const inspectWindowsPipe=()=>{throw Error('unused Windows path')};
export const windowsTraceInstanceReady=()=>false;
`,
    );
    await writeFile(
      join(directory, 'retention.mjs'),
      `
import {parentPort,workerData} from 'node:worker_threads';
import {rmSync} from 'node:fs';
import {join} from 'node:path';
export class DesktopTraceRetention {
 async process(name, now) {
  const elapsed=now-globalThis.started;
  let state=name===workerData.names[0]?'active-or-unsealed':
    ['occupied','diagnostic-retention','source-unverified'][workerData.names.indexOf(name)%3];
  if(name===workerData.fresh || (name===workerData.names[1] && elapsed>=1800000)) {
    state='retired';rmSync(join(workerData.traceRoot,name),{recursive:true});
  }
  parentPort.postMessage({kind:'attempt',name,elapsed,state});
  return {state,bytes:0};
 }
}
`,
    );
    const fresh = `trace-${randomUUID()}-${randomUUID()}`;
    await writeFile(
      join(directory, 'entry.mjs'),
      `
import {workerData} from 'node:worker_threads';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';
Object.defineProperty(process,'platform',{value:'darwin'});
let now=Date.now(),added=false;globalThis.started=now;Date.now=()=>now;
const original=setTimeout;
globalThis.setTimeout=(fn,ms,...args)=>original(()=>{
 now+=ms;
 if(!added && now-globalThis.started>=1800000){added=true;mkdirSync(join(workerData.traceRoot,workerData.fresh));}
 fn(...args);
},1);
await import('./worker.mjs');
`,
    );
    const attempts = [];
    worker = new Worker(pathToFileURL(join(directory, 'entry.mjs')), {
      workerData: {
        traceRoot: root,
        directory,
        target: {
          agentId: 'codex',
          hostId: 'synthetic-host',
          workspaceId: 'synthetic-workspace',
          runtimeId: 'desktop-runtime',
        },
        names,
        fresh,
      },
    });
    const done = new Promise((resolveDone, reject) => {
      worker.on('message', (message) => {
        if (message.kind === 'attempt') attempts.push(message);
        if (attempts.at(-1)?.elapsed >= 3600000) {
          worker.postMessage({ kind: 'close' });
        }
      });
      worker.once('error', reject);
      worker.once('exit', (code) =>
        code === 0 ? resolveDone() : reject(new Error(`worker exited ${code}`)),
      );
    });
    await done;
    assert(
      names.every((name) => attempts.some((x) => x.name === name)),
      'cold backlog is not starved',
    );
    for (const name of names.slice(2)) {
      assert(
        attempts.filter((x) => x.name === name).length <= 10,
        'retained items cannot spin every minute',
      );
    }
    const active = attempts.filter((x) => x.name === names[0]);
    assert(active.length > 300, 'active trace keeps the short retry path');
    const added = attempts.find((x) => x.name === fresh);
    assert(added && added.elapsed < 1810000, 'fresh work bypasses retained backoff');
    const recovered = attempts.find((x) => x.name === names[1] && x.state === 'retired');
    assert(
      recovered && recovered.elapsed <= 2705000,
      'recovery is reconsidered within fifteen minutes',
    );
  } finally {
    if (worker) await worker.terminate();
    await rm(directory, { recursive: true, force: true });
  }
});
