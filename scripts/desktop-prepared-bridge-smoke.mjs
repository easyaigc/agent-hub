import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { DesktopPreparedBridge } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';

// Exercise a real child process and durable journals; never contacts a desktop.
if (process.platform === 'win32')
  throw new Error('POSIX fixture; run real Windows acceptance separately');
const directory = await mkdtemp(join(tmpdir(), 'codex-empty-bridge-'));
const home = join(directory, 'codex-home'),
  cwd = join(directory, 'workspace');
await mkdir(home);
await mkdir(cwd);
const threadId = randomUUID(),
  projectId = randomUUID(),
  hostId = randomUUID();
const output = join(directory, 'metadata.json'),
  calls = join(directory, 'calls.jsonl');
const executable = join(directory, 'official-cli-fixture');
const script = `#!${process.execPath}
import {createInterface} from 'node:readline';
import {writeFileSync,appendFileSync} from 'node:fs';
let metadata;
const out=${JSON.stringify(output)},calls=${JSON.stringify(calls)};
createInterface({input:process.stdin}).on('line',line=>{
 const req=JSON.parse(line);
 if(req.id===undefined)return;
 appendFileSync(calls,JSON.stringify({method:req.method,home:process.env.CODEX_HOME,protectedPipe:process.env.CODEX_APP_TOOLS_PIPE_PATH!==undefined})+'\\n');
 let result={};
 if(req.method==='thread/start') metadata={id:${JSON.stringify(threadId)},projectId:req.params.projectId??null,cwd:req.params.cwd,turns:[]},result={thread:metadata};
 if(req.method==='thread/name/set')writeFileSync(out,JSON.stringify(metadata));
 if(req.method==='thread/read')result={thread:metadata};
 process.stdout.write(JSON.stringify({id:req.id,result})+'\\n');
});
process.stdin.on('end',()=>process.exit(0));
`;
await writeFile(executable, script, { mode: 0o700 });
await chmod(executable, 0o700);
const config = join(directory, 'bridge.json');
await writeFile(config, JSON.stringify({ hostId, desktopVersion: 'fixture' }), { mode: 0o600 });
let owner = true,
  submitted = 0,
  desktopAvailable = true;
const runtime = {
  version: 'fixture',
  ready: true,
  desktopAvailable: async () => desktopAvailable,
  ipc: {
    follow: async () =>
      owner ? { id: threadId, hostId: 'local', turns: [], workspaceKind: 'project' } : undefined,
    hasOwner: () => owner,
    state: () => ({ id: threadId, hostId: 'local', turns: [], workspaceKind: 'project' }),
  },
  reader: { read: async () => ({ thread: JSON.parse(await readFile(output, 'utf8')) }) },
};
const bridge = new DesktopPreparedBridge(
  config,
  hostId,
  runtime,
  join(directory, 'journal'),
  executable,
  home,
  async (_plan, id, boundary, fingerprint) => {
    let record = JSON.parse(
      await readFile(join(directory, 'journal', operationId + '.json'), 'utf8'),
    );
    assert.equal(record.exitCode, 0);
    assert.equal(record.phase, 'input-preparing');
    assert.throws(() => process.kill(record.pid, 0));
    boundary.beforeWrite({
      version: 1,
      hostId,
      threadId: id,
      clientMessageId: 'first-' + operationId,
      preparationFingerprint: fingerprint,
      replyFingerprint: 'a'.repeat(64),
      target: {
        agentId: 'codex',
        hostId,
        workspaceId: hostId,
        runtimeId: 'desktop-runtime',
        account: { authority: 'fixture', subject: 'fixture' },
      },
      writerPid: process.pid,
      attemptId: randomUUID(),
    });
    record = JSON.parse(await readFile(join(directory, 'journal', operationId + '.json'), 'utf8'));
    assert.equal(record.phase, 'input-submitting');
    assert.equal(record.inputBoundary.state, 'may-have-written');
    submitted++;
  },
);
const operationId = randomUUID();
const plan = {
  hostId,
  clientOperationId: operationId,
  projectId,
  directory: cwd,
  text: 'Real first input fixture',
  attachments: [],
  files: [],
  receiptPath: join(directory, 'receipt.json'),
};
try {
  await bridge.refresh();
  assert.equal(bridge.available, true);
  assert.equal(bridge.openAvailable, true);
  assert.equal(bridge.validationOnly, false);
  assert.equal(bridge.openValidationOnly, false);
  assert.equal(await bridge.create(plan), threadId);
  assert.equal(submitted, 1);
  assert.equal(await bridge.create(plan), threadId);
  assert.equal(submitted, 1);
  assert.equal(await bridge.reconcile(plan), threadId);
  assert.equal(submitted, 1);
  const trace = (await readFile(calls, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.deepEqual(
    trace.map((r) => r.method),
    ['initialize', 'thread/start', 'thread/name/set', 'thread/read'],
  );
  assert.ok(trace.every((r) => r.home === home && !r.protectedPipe));
  // Global new-chat readiness does not inspect any ordinary controller owner.
  owner = false;
  await bridge.refresh();
  assert.equal(bridge.available, true);
  assert.equal(bridge.openAvailable, true);
  // Missing, stale and unreadable acceptance reports never disable a healthy installation.
  const unreadable = join(directory, 'unreadable-proof.json');
  await writeFile(unreadable, '{invalid-proof', { mode: 0o000 });
  await chmod(unreadable, 0o000);
  for (const verification of [join(directory, 'missing-proof.json'), unreadable]) {
    await writeFile(config, JSON.stringify({ hostId, desktopVersion: 'fixture', verification }));
    await bridge.refresh();
    assert.equal(bridge.available, true);
    assert.equal(bridge.openAvailable, true);
    assert.equal(bridge.validationOnly, false);
  }
  const stale = join(directory, 'stale-proof.json');
  const staleEvidence = JSON.stringify({ hostId: randomUUID(), adapterSha256: 'old-build' });
  await writeFile(stale, staleEvidence);
  await writeFile(
    config,
    JSON.stringify({ hostId, desktopVersion: 'fixture', verification: stale }),
  );
  await bridge.refresh();
  assert.equal(bridge.available, true);
  assert.equal(bridge.openAvailable, true);
  await writeFile(
    config,
    JSON.stringify({ hostId, desktopVersion: 'fixture', validationOnly: false }),
  );
  await bridge.refresh();
  assert.equal(bridge.available, true);
  assert.equal(bridge.validationOnly, false);
  assert.equal(bridge.openValidationOnly, false);
  // Build labels are optional metadata. Refresh never rewrites old configuration or evidence.
  for (const compatible of [
    { hostId },
    { hostId, desktopVersion: 'old-build', verification: stale },
    { hostId, desktopVersion: 'future-build' },
  ]) {
    const configuration = JSON.stringify(compatible);
    await writeFile(config, configuration);
    runtime.version = 'upgraded-build';
    await bridge.refresh();
    assert.equal(bridge.available, true);
    assert.equal(bridge.openAvailable, true);
    assert.equal(await readFile(config, 'utf8'), configuration);
  }
  runtime.version = '';
  await bridge.refresh();
  assert.equal(bridge.available, true);
  assert.equal(bridge.openAvailable, true);
  assert.equal(await readFile(stale, 'utf8'), staleEvidence);
  // Runtime identity, protocol readiness and private configuration still decide availability.
  for (const invalid of [{ hostId: randomUUID(), desktopVersion: 'fixture' }]) {
    await writeFile(config, JSON.stringify(invalid));
    await bridge.refresh();
    assert.equal(bridge.available, false);
    assert.equal(bridge.openAvailable, false);
    await assert.rejects(() => bridge.create({ ...plan, clientOperationId: randomUUID() }));
    await assert.rejects(() => bridge.open(threadId, randomUUID()));
  }
  await writeFile(config, JSON.stringify({ hostId, desktopVersion: 'fixture' }));
  runtime.ready = false;
  await bridge.refresh();
  assert.equal(bridge.available, false);
  assert.equal(bridge.openAvailable, false);
  await assert.rejects(() => bridge.open(threadId, randomUUID()));
  runtime.ready = true;
  desktopAvailable = false;
  await bridge.refresh();
  assert.equal(bridge.available, false);
  assert.equal(bridge.openAvailable, false);
  desktopAvailable = true;
  await chmod(config, 0o666);
  await bridge.refresh();
  assert.equal(bridge.available, false);
  assert.equal(bridge.openAvailable, false);
  await chmod(config, 0o600);
  await bridge.refresh();
  assert.equal(bridge.available, true);
  assert.equal(bridge.openAvailable, true);
  owner = true;
  await assert.rejects(() => bridge.create({ ...plan, hostId: randomUUID() }));
  assert.equal(await bridge.open(threadId, randomUUID()), true);
  assert.equal(submitted, 1);
  assert.equal((await readFile(calls, 'utf8')).trim().split('\n').length, trace.length);
  // A known uncertain ID can be read but must never start or submit again.
  const unknownId = randomUUID(),
    unknownPlan = { ...plan, clientOperationId: unknownId };
  await writeFile(
    config,
    JSON.stringify({ hostId, desktopVersion: 'fixture', validationOnly: true }),
    { mode: 0o600 },
  );
  const uncertain = new DesktopPreparedBridge(
    config,
    hostId,
    runtime,
    join(directory, 'uncertain'),
    executable,
    home,
    async () => {
      throw Error('Input result unknown');
    },
  );
  owner = true;
  await uncertain.refresh();
  assert.equal(uncertain.validationOnly, true);
  assert.equal(uncertain.openValidationOnly, true);
  await assert.rejects(() => uncertain.create(unknownPlan));
  const before = (await readFile(calls, 'utf8')).length;
  assert.equal(await uncertain.reconcile(unknownPlan), threadId);
  await assert.rejects(() => uncertain.create(unknownPlan));
  assert.equal((await readFile(calls, 'utf8')).length, before);
  uncertain.close();
  const recoveryJournal = join(directory, 'open-recovery-journal');
  let recoveryInputs = 0;
  const recovery = new DesktopPreparedBridge(
    config,
    hostId,
    runtime,
    recoveryJournal,
    executable,
    home,
    async () => {
      recoveryInputs++;
      throw Error('Must never execute recovery input');
    },
  );
  recovery.open = async () => false;
  const recoveryPlan = { ...plan, clientOperationId: randomUUID() };
  await recovery.refresh();
  await assert.rejects(() => recovery.create(recoveryPlan), /接管结果待确认/);
  const recoveryFile = join(recoveryJournal, recoveryPlan.clientOperationId + '.json');
  const proofBytes = await readFile(recoveryFile),
    proofRecord = JSON.parse(proofBytes);
  assert.equal(proofRecord.phase, 'released');
  assert.equal(proofRecord.exitCode, 0);
  assert.equal(proofRecord.inputBoundary, undefined);
  const callsBeforeRecovery = await readFile(calls, 'utf8');
  const metadata = await recovery.preparedThreadMetadata(recoveryPlan, threadId);
  assert.equal(metadata.id, threadId);
  assert.equal(metadata.hostId, hostId);
  assert.equal(metadata.projectId, projectId);
  assert.equal(await recovery.preparedThreadMetadata(recoveryPlan, randomUUID()), undefined);
  for (const changed of [
    { phase: 'materialized' },
    { exitCode: null },
    { hostId: randomUUID() },
    { fingerprint: 'f'.repeat(64) },
    { inputBoundary: { state: 'may-have-written' } },
  ]) {
    await writeFile(recoveryFile, JSON.stringify({ ...proofRecord, ...changed }));
    assert.equal(
      await recovery.preparedThreadMetadata(recoveryPlan, threadId).catch(() => undefined),
      undefined,
    );
    await writeFile(recoveryFile, proofBytes);
  }
  const originalRead = runtime.reader.read,
    emptyMetadata = JSON.parse(await readFile(output, 'utf8'));
  for (const changed of [
    { id: randomUUID() },
    { projectId: randomUUID() },
    { cwd: home },
    { turns: [{ id: randomUUID(), status: 'completed', items: [] }] },
    { archived: true },
  ]) {
    runtime.reader.read = async () => ({ thread: { ...emptyMetadata, ...changed } });
    assert.equal(await recovery.preparedThreadMetadata(recoveryPlan, threadId), undefined);
  }
  runtime.reader.read = originalRead;
  assert.equal(
    await readFile(calls, 'utf8'),
    callsBeforeRecovery,
    'recovery never starts another preparer',
  );
  assert.equal(recoveryInputs, 0);
  recovery.close();
  assert.throws(() => new DesktopThreadPreparer('relative-cli', home));
  console.log(
    'Production readiness without acceptance reports, target/private-config checks, empty metadata preparation, process-exit handoff and no replay: passed',
  );
} finally {
  bridge.close();
  await rm(directory, { recursive: true, force: true });
}
await import('./desktop-first-input-boundary-smoke.mjs');
