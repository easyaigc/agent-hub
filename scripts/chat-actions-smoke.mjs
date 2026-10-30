import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { renderItem } from '../packages/codex-adapter/dist/runtime.js';
import { DesktopRuntime } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { parseChatActionRequest } from '../packages/protocol/dist/index.js';
import {
  DesktopChatActions,
  desktopPinnedSection,
} from '../packages/codex-adapter/dist/desktop-chat-actions.js';
const directory = await mkdtemp(join(tmpdir(), 'chat-actions-boundary-'));
const target = {
  agentId: 'codex',
  hostId: 'host-fixture',
  workspaceId: 'workspace-fixture',
  runtimeId: 'desktop-runtime',
};
const request = {
  hostId: target.hostId,
  target,
  threadId: 'thread-fixture',
  clientOperationId: 'operation-fixture',
  action: 'pin',
};
const log = join(directory, 'calls.jsonl'),
  executable = join(directory, 'fixture.mjs');
await writeFile(
  executable,
  `#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { appendFileSync } from 'node:fs';
createInterface({input:process.stdin}).on('line', line=>{
const r=JSON.parse(line);if(r.id===undefined)return;
appendFileSync(${JSON.stringify(log)}, JSON.stringify(r)+'\\n');
if(r.method==='thread/delete'){process.exit(0);return;}
if(r.method==='thread/archive'){process.stdout.write(JSON.stringify({id:r.id,error:{code:-32600,message:'thread already has an active writer'}})+'\\n');return;}
if(r.method==='thread/unarchive'){process.stdout.write(JSON.stringify({id:r.id,error:{code:-32603,message:'internal metadata failure'}})+'\\n');return;}
process.stdout.write(JSON.stringify({id:r.id,result:r.method==='thread/read'?{thread:{id:r.params.threadId}}:{}})+'\\n');
});`,
  { mode: 0o700 },
);
const journal = join(directory, 'journal');
let broker = new DesktopChatActions(journal, target.hostId, executable, directory, async () => {});
try {
  // The desktop API, rather than execution state or an uncertain reply, decides
  // lifecycle eligibility. Identity still belongs to this runtime.
  const runtime = new DesktopRuntime({ mode: 'desktop-runtime' }, 'fixture-host', 'fixture');
  let ownerReads = 0;
  runtime.ipc = {
    ready: true,
    async follow() {
      ownerReads++;
      return { threadRuntimeStatus: { type: 'running' } };
    },
  };
  runtime.reader = { ready: true };
  const session = Object.create(RuntimeSession.prototype);
  session.options = { mode: 'desktop-runtime' };
  session.runtime = runtime;
  session.state = {
    threads: [{ id: request.threadId }],
    receipts: [{ threadId: request.threadId, state: 'uncertain' }],
  };
  await session.guardChatAction(request.threadId);
  assert.equal(
    ownerReads,
    0,
    'Management must not probe, interrupt or wait for the execution owner',
  );
  await assert.rejects(session.guardChatAction('unknown-thread'), /不属于/);
  session.desktopSuggestionSources = new Map();
  session.completedDesktopSuggestionSource = () => undefined;
  for (const status of ['completed', 'interrupted', 'failed', 'inProgress']) {
    const thread = { id: request.threadId };
    session.desktopSummary(thread, {
      turns: [{ turnId: 'latest-turn', status }],
      threadRuntimeStatus: { type: status === 'inProgress' ? 'active' : 'idle' },
    });
    assert.equal(thread.lastTurnStatus, status, 'Task outcome comes from the original turn');
    assert.equal(
      thread.state,
      status === 'inProgress' ? 'running' : status === 'failed' ? 'failed' : 'idle',
      'Task outcome must not change execution eligibility or manufacture a running turn',
    );
  }
  session.runtime = {};
  await assert.rejects(session.guardChatAction(request.threadId), /原桌面/);

  const largeOutput = 'EARLY_OUTPUT_MATCH' + 'x'.repeat(250000);
  const item = {
    id: 'large-tool',
    type: 'commandExecution',
    command: 'fixture',
    aggregatedOutput: largeOutput,
    exitCode: 0,
  };
  assert(!renderItem(item).detail.includes('EARLY_OUTPUT_MATCH'));
  assert(renderItem(item, Infinity).detail.startsWith('EARLY_OUTPUT_MATCH'));
  assert.deepEqual(parseChatActionRequest(request), request);
  for (const invalid of [
    { ...request, method: 'turn/start' },
    { ...request, action: 'rpc' },
    { ...request, target: { ...target, hostId: 'other-fixture' } },
  ])
    assert.throws(() => parseChatActionRequest(invalid));
  const first = await broker.submit(request);
  assert.equal(first.phase, 'confirmed');
  const duplicate = await broker.submit(request);
  assert.deepEqual(duplicate, first);
  await assert.rejects(broker.submit({ ...request, action: 'archive' }), /其他意图/);
  const calls = (await readFile(log, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(calls.filter((r) => r.method === 'thread/section/move').length, 1);
  assert.deepEqual(calls.find((r) => r.method === 'thread/section/move').params, {
    threadId: request.threadId,
    sectionId: desktopPinnedSection,
    beforeThreadId: null,
  });
  // A synchronous process-construction failure happens before any dispatch.
  // Neither the thread nor operation may remain busy for a later intent.
  const beforeConstructorFailure = await readFile(log, 'utf8');
  const cannotStart = new DesktopChatActions(
    join(directory, 'constructor-rejected'),
    target.hostId,
    executable + '\0',
    directory,
    async () => {},
  );
  try {
    const precreation = { ...request, clientOperationId: 'constructor-failure-operation' };
    for (const intent of [
      precreation,
      { ...precreation, clientOperationId: 'next-constructor-failure-operation' },
      { ...precreation, threadId: 'separate-constructor-failure-thread' },
    ])
      await assert.rejects(cannotStart.submit(intent), { code: 'ERR_INVALID_ARG_VALUE' });
  } finally {
    cannotStart.close();
  }
  assert.equal(
    await readFile(log, 'utf8'),
    beforeConstructorFailure,
    'Process-construction rejection must not dispatch metadata writes',
  );
  const writer = await broker.submit({
    ...request,
    clientOperationId: 'writer-archive-fixture',
    action: 'archive',
  });
  assert.equal(writer.phase, 'rejected');
  assert.match(writer.error, /电脑端拒绝/);
  const internal = await broker.submit({
    ...request,
    clientOperationId: 'internal-archive-fixture',
    action: 'unarchive',
  });
  assert.equal(internal.phase, 'outcome-unknown');
  assert.match(internal.error, /待确认/);
  await assert.rejects(
    broker.submit({ ...request, clientOperationId: 'new-id-after-unknown' }),
    /待确认/,
  );
  const deleting = {
    ...request,
    threadId: 'separate-delete-thread',
    clientOperationId: 'unknown-delete-fixture',
    action: 'delete',
  };
  assert.equal((await broker.submit(deleting)).phase, 'outcome-unknown');
  broker.close();
  const before = await readFile(log, 'utf8');
  broker = new DesktopChatActions(journal, target.hostId, executable, directory, async () => {});
  assert.equal((await broker.submit(deleting)).phase, 'outcome-unknown');
  assert.equal(
    await readFile(log, 'utf8'),
    before,
    'Unknown outcome must never replay after restart',
  );
  assert.throws(() => broker.status('other-thread', request.clientOperationId), /目标不一致/);
  await assert.rejects(
    broker.submit({ ...request, hostId: 'other-host', clientOperationId: 'wrong-host-op' }),
    /电脑身份/,
  );
  const blocked = new DesktopChatActions(
    join(directory, 'blocked'),
    target.hostId,
    executable,
    directory,
    async () => {
      throw Error('Active owner');
    },
  );
  await assert.rejects(
    blocked.submit({ ...request, clientOperationId: 'blocked-operation' }),
    /Active owner/,
  );
  blocked.close();
  const failedDirectory = join(directory, 'failed');
  await mkdir(join(failedDirectory, 'persist-failure-op.json'), { recursive: true });
  const failed = new DesktopChatActions(
    failedDirectory,
    target.hostId,
    executable,
    directory,
    async () => {},
  );
  await assert.rejects(failed.submit({ ...request, clientOperationId: 'persist-failure-op' }));
  failed.close();
  const after = (await readFile(log, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(
    after.filter((r) => r.method === 'thread/section/move').length,
    1,
    'No metadata write after persistence/owner/identity rejection',
  );
  assert(
    after.every((r) =>
      [
        'initialize',
        'thread/read',
        'thread/section/move',
        'thread/delete',
        'thread/archive',
        'thread/unarchive',
      ].includes(r.method),
    ),
  );
  console.log(
    'Chat actions: strict contract, exact server pin, durable deduplication, restart unknown/no replay, target isolation and pre-submit failure boundaries passed.',
  );
} finally {
  broker.close();
  await rm(directory, { recursive: true, force: true });
}
