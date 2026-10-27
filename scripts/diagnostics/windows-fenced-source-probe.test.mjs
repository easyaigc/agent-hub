import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { parseProbePlan, compiledFingerprint, runProbe } from './windows-fenced-source-probe.mjs';
const plan = {
  format: 'windows-fenced-source-probe-v1',
  operationId: '11111111-1111-4111-8111-111111111111',
  hostId: '22222222-2222-4222-8222-222222222222',
  threadId: '33333333-3333-4333-8333-333333333333',
  sourceTurnId: '44444444-4444-4444-8444-444444444444',
  program: '/fixture/program',
  hostConfig: '/fixture/host.json',
  runtimeState: '/fixture/runtime.json',
  output: '/fixture/new-probe',
  adapterFingerprint: 'a'.repeat(64),
  protocolFingerprint: 'b'.repeat(64),
};
test('one-source plan is copied and rejects extra execution/transport fields', () => {
  const value = parseProbePlan(plan);
  value.hostId = 'changed';
  assert.notEqual(value.hostId, plan.hostId);
  for (const extra of ['sources', 'command', 'method', 'token', 'deadlineMs'])
    assert.throws(() => parseProbePlan({ ...plan, [extra]: 'untrusted' }));
});
test('wrong format, incomplete target and duplicate source IDs fail before imports', () => {
  for (const value of [
    { ...plan, format: 'other' },
    { ...plan, hostId: 'not-an-id' },
    { ...plan, sourceTurnId: plan.threadId },
    { ...plan, program: 'relative' },
    { ...plan, adapterFingerprint: 'short' },
  ])
    assert.throws(() => parseProbePlan(value));
});
test('complete compiled inventory hash changes for body edits and unexpected modules', async () => {
  const root = await mkdtemp(join(tmpdir(), 'probe-inventory-'));
  try {
    await writeFile(join(root, 'a.js'), 'export const v=1;');
    const before = await compiledFingerprint(root);
    await writeFile(join(root, 'a.js'), 'export const v=2;');
    const changed = await compiledFingerprint(root);
    assert.notEqual(before, changed);
    await writeFile(join(root, 'b.js'), 'export {};');
    assert.notEqual(changed, await compiledFingerprint(root));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test(
  'unsupported platform does not read a private plan or start a reader',
  { skip: process.platform === 'win32' },
  async () => {
    await assert.rejects(runProbe('/does-not-exist'), /Native Windows only/);
  },
);
