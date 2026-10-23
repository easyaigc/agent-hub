import assert from 'node:assert/strict';
import {
  historyIdentity,
  retainedDesktopHistory,
} from '../packages/codex-adapter/dist/desktop-history-identity.js';

const row = (id, runId = 'run', kind = 'agentMessage', text = 'completed body') => ({
  id,
  runId,
  kind,
  text,
  role: kind === 'userMessage' ? 'user' : 'assistant',
  replyPhase: kind === 'agentMessage' ? 'final_answer' : undefined,
  status: 'completed',
});
const retained = (before, after) => retainedDesktopHistory(before, after, new Set());

assert.notEqual(historyIdentity('item-1', 'one'), historyIdentity('item-1', 'two'));
assert.equal(historyIdentity('msg-stable', 'one'), 'msg-stable');
assert.equal(historyIdentity('item-1'), 'item-1', 'No invented Turn identity');
for (const provisional of ['item-1', historyIdentity('item-1', 'run')]) {
  assert.deepEqual(
    retained([row(provisional)], [row('msg-owner')]),
    [],
    'Observed reader/owner identity change must not duplicate a final body',
  );
  assert.deepEqual(
    retained([row(provisional, 'old')], [row('msg-owner')]),
    [row(provisional, 'old')],
    'Equal text from another Turn remains',
  );
  assert.deepEqual(
    retained(
      [row(provisional, 'run', 'commandExecution')],
      [row('exec-owner', 'run', 'commandExecution')],
    ),
    [row(provisional, 'run', 'commandExecution')],
    'Tool identity is not inferred from its text',
  );
}
assert.deepEqual(
  retained([row('msg-old')], [row('msg-new')]),
  [row('msg-old')],
  'Two real IDs with equal text are distinct messages',
);
assert.equal(
  retained([row('item-1'), row('item-2')], [row('msg-one')]).length,
  1,
  'One replacement cannot remove two identical historical messages',
);
const live = [row('msg-one'), row('msg-two')];
assert.equal(retained([row('item-1'), row('item-2')], live).length, 0);
assert.equal(live.length, 2, 'Two real equal messages remain present');
assert.equal(
  retained([row('item-1')], [{ ...row('msg-owner'), replyPhase: 'commentary' }]).length,
  1,
  'Commentary cannot replace the final answer',
);
assert.equal(
  retained([row('item-1')], [row('msg-owner', 'run', 'agentMessage', 'changed body')]).length,
  1,
  'Different content is not an alias',
);
assert.equal(
  retained([row('older-id', 'old')], []).length,
  1,
  'A short snapshot must keep the older prefix',
);
assert.equal(
  retained([row('item-1'), row('msg-owner')], [row('msg-owner')]).length,
  1,
  'A partial snapshot repeating a known real item cannot erase another historical item',
);
assert.equal(
  retainedDesktopHistory(
    [row('item-1'), row('msg-owner')],
    [row('msg-owner')],
    new Set(),
    new Set(['run']),
  ).length,
  0,
  'Completed original-owner dialog repairs the observed legacy cached duplicate',
);
assert.equal(
  retained([{ ...row('item-1'), files: ['historical file'] }], [row('msg-owner')]).length,
  1,
  'Unknown attachment identity is not inferred from equal text',
);
assert.equal(
  retained([{ ...row('compaction', 'old', 'contextCompaction'), status: 'inProgress' }], [])[0]
    .status,
  'unknown',
);
assert.deepEqual(
  retainedDesktopHistory(
    [row('initialization'), row('implementation', 'run', 'planImplementation')],
    [],
    new Set(['initialization']),
  ),
  [],
);
console.log(
  'Desktop history identity boundaries passed: provisional adoption, legacy migration, Turn isolation, multiplicity, real IDs, reply phase, tools and retained prefixes.',
);
