import assert from 'node:assert/strict';
import { WorkspaceManagementBroker } from '../packages/codex-adapter/dist/workspace-management.js';
import { CodexRejection } from '../packages/codex-adapter/dist/runtime.js';
import { parseWorkspaceManagementRequest } from '../packages/protocol/dist/index.js';

const target = {
  agentId: 'codex',
  hostId: 'test-host',
  workspaceId: 'workspace',
  runtimeId: 'desktop-runtime',
};
const request = (changes = {}) =>
  parseWorkspaceManagementRequest({
    hostId: target.hostId,
    target,
    clientOperationId: 'batch-operation-1',
    action: 'threads:archive',
    ids: ['idle', 'busy', 'lost'],
    ...changes,
  });
for (const bad of [
  { ids: [] },
  { ids: ['x', 'x'] },
  { ids: Array.from({ length: 101 }, (_, i) => String(i)) },
  { action: 'shell' },
  { extra: 'no' },
  { target: { ...target, hostId: 'other' } },
])
  assert.throws(() => request(bad));
const records = {},
  writes = [],
  completed = [];
let durable;
const broker = new WorkspaceManagementBroker(
  target,
  records,
  () => {
    durable = structuredClone(records);
  },
  async (action, id) => {
    assert.equal(
      durable['batch-operation-1'].items.find((item) => item.id === id).state,
      'submitting',
    );
    writes.push([action, id]);
    if (id === 'busy') throw new CodexRejection('running');
    if (id === 'lost') throw new Error('transport closed after write');
  },
  async (_action, id) => id === 'lost',
  (_action, id) => completed.push(id),
);
broker.submit(request());
for (
  let i = 0;
  i < 10 &&
  records['batch-operation-1'].items.some((item) => ['pending', 'submitting'].includes(item.state));
  i++
)
  await new Promise((done) => setImmediate(done));
assert.deepEqual(
  records['batch-operation-1'].items.map((item) => item.state),
  ['completed', 'rejected', 'outcome-unknown'],
);
assert.throws(
  () => broker.submit(request({ clientOperationId: 'overlapping-request', ids: ['lost'] })),
  /原操作/,
);
assert.throws(() => broker.submit(request({ ids: ['different'] })), /不同/);
assert.throws(
  () => broker.submit(request({ target: { ...target, workspaceId: 'other' } })),
  /目标/,
);
const status = await broker.status('batch-operation-1', target);
assert.deepEqual(
  status.items.map((item) => item.state),
  ['completed', 'rejected', 'completed'],
);
broker.submit(request());
assert.equal(writes.length, 3, 'Repeat submission must never replay mutations');
assert.deepEqual(completed, ['idle', 'lost']);
await assert.rejects(broker.status('batch-operation-1', { ...target, runtimeId: 'other' }));
// Restart leaves uncertain writes queryable and rejects unsubmitted work, without executing it.
const restartRecords = {
  'restart-operation': {
    ...request({ clientOperationId: 'restart-operation', ids: ['x', 'y'] }),
    items: [
      { id: 'x', state: 'submitting', error: null },
      { id: 'y', state: 'pending', error: null },
    ],
  },
};
new WorkspaceManagementBroker(
  target,
  restartRecords,
  () => {},
  async () => assert.fail('No restart replay'),
  async () => false,
  () => {},
);
assert.deepEqual(
  restartRecords['restart-operation'].items.map((item) => item.state),
  ['outcome-unknown', 'rejected'],
);
let attempts = 0;
const failed = new WorkspaceManagementBroker(
  target,
  {},
  () => {
    throw new Error('disk full');
  },
  async () => {
    attempts++;
  },
  async () => false,
  () => {},
);
assert.throws(() => failed.submit(request()), /disk full/);
assert.equal(attempts, 0, 'A failed durable intent cannot mutate the desktop');
console.log(
  'Workspace management: strict targets, partial failure, durable intent, immutable IDs, uncertainty reconciliation and restart without replay passed.',
);
