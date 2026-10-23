import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DesktopForkBroker } from '../packages/codex-adapter/dist/desktop-forks.js';
import { parseThreadForkRequest } from '../packages/protocol/dist/thread-forks.js';

// Actual preparation subprocesses and durable receipts. No desktop or model is contacted.
if (process.platform === 'win32') throw new Error('POSIX fixture; record Windows separately');
const root = await realpath(await mkdtemp(join(tmpdir(), 'agent-hub-fork-')));
const cwd = join(root, 'workspace'),
  home = join(root, 'codex-home');
await mkdir(cwd);
await mkdir(home);
const hostId = randomUUID(),
  threadId = randomUUID(),
  turnId = randomUUID();
const trace = join(root, 'calls.jsonl');
const behavior = join(root, 'behavior.json');
const executable = join(root, 'metadata-fixture.mjs');
await writeFile(
  executable,
  `#!${process.execPath}
import {createInterface} from 'node:readline';
import {appendFileSync,readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
let thread;const behavior=JSON.parse(readFileSync(${JSON.stringify(behavior)},'utf8'));
createInterface({input:process.stdin}).on('line',line=>{
 const req=JSON.parse(line);if(req.id===undefined)return;
 appendFileSync(${JSON.stringify(trace)},JSON.stringify({method:req.method,params:req.params,home:process.env.CODEX_HOME,protectedPipe:process.env.CODEX_APP_TOOLS_PIPE_PATH!==undefined})+'\\n');
 if(req.method==='thread/fork'&&behavior.reject){process.stdout.write(JSON.stringify({id:req.id,error:{message:'rejected'}})+'\\n');return;}
 let result={};if(req.method==='thread/fork'){thread={id:randomUUID(),cwd:req.params.cwd,forkedFromId:req.params.threadId,ephemeral:false};result={thread};}
 if(req.method==='thread/read')result={thread};
 process.stdout.write(JSON.stringify({id:req.id,result})+'\\n');
});
process.stdin.on('end',()=>process.exit(behavior.exitCode??0));
`,
  { mode: 0o700 },
);
const brokers = [];
let owners = true,
  opens = 0,
  checks = 0;
const source = {
  cwd,
  title: 'Synthetic source',
  projectId: randomUUID(),
  model: 'fixture-model',
  effort: 'xhigh',
};
const journal = join(root, 'journal');
function broker(directory = journal) {
  const value = new DesktopForkBroker(
    directory,
    hostId,
    executable,
    home,
    async (id, operationId) => {
      const record = JSON.parse(await readFile(join(directory, operationId + '.json'), 'utf8'));
      assert.equal(record.forkedThreadId, id);
      assert.equal(record.preparationExitCode, 0);
      assert.throws(() => process.kill(record.preparationPid, 0));
      opens++;
      return owners;
    },
    async (record, directory) => {
      checks++;
      assert.equal(directory, cwd);
      assert.ok(record.forkedThreadId);
      return owners;
    },
  );
  brokers.push(value);
  return value;
}
const request = () => ({
  hostId,
  threadId,
  turnId,
  clientOperationId: randomUUID(),
  environment: 'same-directory',
});
const calls = async () => {
  try {
    return (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);
  } catch {
    return [];
  }
};
const finish = async (value, input) => {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const r = await value.query(input.threadId, input.clientOperationId);
    if (r.phase !== 'submitted') return r;
    await new Promise((done) => setTimeout(done, 20));
  }
  throw Error('Fixture fork did not settle');
};
try {
  const input = request();
  assert.deepEqual(parseThreadForkRequest(input), input);
  for (const patch of [
    { environment: 'shell' },
    { cwd },
    { turnId: '' },
    { clientOperationId: '__proto__' },
    { threadId: null },
  ])
    assert.throws(() => parseThreadForkRequest({ ...input, ...patch }));
  await writeFile(behavior, '{}');
  const first = broker();
  assert.equal(first.submit(input, source).phase, 'submitted');
  assert.equal(first.submit(input, source).phase, 'submitted');
  const done = await finish(first, input);
  assert.equal(done.phase, 'confirmed');
  assert.equal(done.projectId, source.projectId);
  assert.equal(opens, 1);
  let history = await calls();
  assert.deepEqual(
    history.map((x) => x.method),
    ['initialize', 'thread/fork', 'thread/name/set', 'thread/read'],
  );
  const fork = history.find((x) => x.method === 'thread/fork');
  assert.equal(fork.params.threadId, threadId);
  assert.equal(fork.params.lastTurnId, turnId);
  assert.equal(fork.params.cwd, cwd);
  assert.equal(fork.params.model, 'fixture-model');
  assert.equal(fork.params.config.model_reasoning_effort, 'xhigh');
  assert.ok(history.every((x) => x.home === home && !x.protectedPipe));
  assert.throws(() => first.submit({ ...input, environment: 'worktree' }, source));
  assert.throws(() => first.submit({ ...input, hostId: randomUUID() }, source));
  await assert.rejects(first.query(randomUUID(), input.clientOperationId));
  first.close();
  const restored = broker();
  assert.deepEqual(await restored.query(threadId, input.clientOperationId), done);
  assert.deepEqual(restored.submit(input, source), done);
  assert.equal((await calls()).length, 4);
  // Missing owner preserves the exact accepted child; query cannot open or fork again.
  owners = false;
  const late = request();
  restored.submit(late, source);
  const uncertain = await finish(restored, late);
  assert.equal(uncertain.phase, 'outcome-unknown');
  assert.ok(uncertain.forkedThreadId);
  const priorOpens = opens,
    priorCalls = (await calls()).length;
  assert.equal((await restored.query(threadId, late.clientOperationId)).phase, 'outcome-unknown');
  assert.equal(opens, priorOpens);
  assert.equal((await calls()).length, priorCalls);
  restored.close();
  owners = true;
  const restart = broker();
  assert.equal((await restart.query(threadId, late.clientOperationId)).phase, 'confirmed');
  assert.equal(opens, priorOpens);
  assert.equal((await calls()).length, priorCalls);
  // An API rejection is definitive. A nonzero preparation exit forbids desktop takeover.
  await writeFile(behavior, JSON.stringify({ reject: true }));
  const rejected = request();
  restart.submit(rejected, source);
  assert.equal((await finish(restart, rejected)).phase, 'rejected');
  assert.equal(opens, priorOpens);
  await writeFile(behavior, JSON.stringify({ exitCode: 1 }));
  const badExit = request();
  restart.submit(badExit, source);
  assert.equal((await finish(restart, badExit)).phase, 'outcome-unknown');
  assert.equal(opens, priorOpens);
  assert.equal((await restart.query(threadId, badExit.clientOperationId)).phase, 'outcome-unknown');
  // Crash checkpoint: unknown fork with no returned ID stays unknown across every retry.
  const lost = request();
  const path = join(journal, lost.clientOperationId + '.json');
  const template = JSON.parse(
    await readFile(join(journal, input.clientOperationId + '.json'), 'utf8'),
  );
  const { createHash } = await import('node:crypto');
  const fingerprint = createHash('sha256').update(JSON.stringify(lost)).digest('hex');
  await writeFile(
    path,
    JSON.stringify({
      ...template,
      ...lost,
      fingerprint,
      phase: 'submitted',
      forkedThreadId: null,
      checkpoint: 'fork-written',
      preparationExitCode: null,
    }),
    { mode: 0o600 },
  );
  const before = (await calls()).length;
  assert.equal((await restart.query(threadId, lost.clientOperationId)).phase, 'outcome-unknown');
  assert.equal(restart.submit(lost, source).phase, 'outcome-unknown');
  assert.equal((await calls()).length, before);
  const worktreeFailure = { ...request(), environment: 'worktree' };
  restart.submit(worktreeFailure, source);
  assert.equal((await finish(restart, worktreeFailure)).phase, 'rejected');
  assert.equal((await calls()).length, before);
  assert.equal(opens, priorOpens);
  assert.ok(checks >= 3);
  console.log(
    JSON.stringify({
      kind: 'thread-fork-boundary-smoke',
      passed: true,
      checks: [
        'strict-contract',
        'exact-completed-turn',
        'no-model-input',
        'released-preparer',
        'duplicate-and-changed-intent',
        'host-thread-isolation',
        'restart-and-late-owner',
        'rejection',
        'failed-preparation-exit',
        'unknown-never-replays',
        'worktree-failure-before-fork',
      ],
    }),
  );
} finally {
  for (const value of brokers) value.close();
  await rm(root, { recursive: true, force: true });
}
