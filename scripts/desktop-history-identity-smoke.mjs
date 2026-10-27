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
for (const fallback of ['item-4', historyIdentity('item-4', 'run')]) {
  const completed = (previous, incoming) =>
    retainedDesktopHistory(previous, incoming, new Set(), new Set(['run']));
  assert.deepEqual(
    completed([row('msg-before-restart')], [row(fallback)]),
    [],
    'A completed original owner can expose ordinal identity after restart without duplicating a prior stable dialog',
  );
  assert.equal(
    retained([row('msg-before-restart')], [row(fallback)]).length,
    1,
    'A provisional partial/live snapshot cannot retire an existing stable message',
  );
  assert.deepEqual(
    completed([row('msg-one'), row('msg-two')], [row(fallback)]).map((v) => v.id),
    ['msg-two'],
    'One ordinal replacement retires at most one equal historical dialog',
  );
  assert.equal(
    completed([row('msg-one'), row('msg-two')], [row(fallback), row('item-5')]).length,
    0,
    'Two identical completed dialog items remain two incoming messages',
  );
  assert.equal(
    completed([row('msg-one'), row(fallback)], [row(fallback)]).length,
    0,
    'Repair an already persisted stable plus ordinal duplicate without clearing history',
  );
  for (const prior of [
    row('msg-other-turn', 'other'),
    { ...row('msg-commentary'), replyPhase: 'commentary' },
    row('msg-tool', 'run', 'commandExecution'),
    { ...row('msg-file'), files: ['original.pdf'] },
    { ...row('msg-attachment'), attachments: [{ id: 'original-file' }] },
  ])
    assert.deepEqual(completed([prior], [row(fallback)]), [prior]);
  assert.equal(
    completed([row('msg-one')], [{ ...row(fallback), attachments: [{ id: 'new-file' }] }]).length,
    1,
    'Incoming attachment identity cannot be inferred from equal dialog text',
  );
}
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

// Full resource identity repairs a completed original reply without dropping
// distinct resources, raw body changes, multiplicity or unproven live history.
const file = {
  id: 'registered-version',
  hostId: 'host',
  threadId: 'thread',
  name: 'report.txt',
  mediaType: 'text/plain',
  sizeBytes: 7,
  sha256: 'a'.repeat(64),
  resourceId: 'registered-resource',
};
const resource = (id, claims = [file], files = []) => ({ ...row(id), attachments: claims, files });
const completedResources = (before, after) =>
  retainedDesktopHistory(before, after, new Set(), new Set(['run']));
const historical = resource('history-fallback:run:item-3', [file], ['report.txt']);
const stable = resource('msg-final');
assert.deepEqual(
  completedResources([historical], [stable]),
  [],
  'A redundant display name cannot duplicate an identical completed registered final reply',
);
assert.equal(
  retained([historical], [stable]).length,
  1,
  'Resource aliases require positive original completed-Turn evidence',
);
assert.equal(
  completedResources(
    [historical, resource('history-fallback:run:item-4', [file], ['report.txt'])],
    [stable],
  ).length,
  1,
  'One resource replacement retires one provisional occurrence only',
);
assert.equal(
  completedResources([stable], [resource('msg-another')]).length,
  1,
  'Two real resource replies remain distinct',
);
assert.deepEqual(
  completedResources([stable], [historical]),
  [],
  'Completed restart can expose the equivalent ordinal resource identity',
);
for (const changed of [
  { ...file, id: 'other-version' },
  { ...file, hostId: 'other-host' },
  { ...file, threadId: 'other-thread' },
  { ...file, name: 'other.txt' },
  { ...file, mediaType: 'application/octet-stream' },
  { ...file, sizeBytes: 8 },
  { ...file, sha256: 'b'.repeat(64) },
  { ...file, resourceId: 'other-resource' },
  { ...file, unexpected: 'unproven' },
  { ...file, sizeBytes: NaN },
  { ...file, resourceId: '' },
  { ...file, sha256: 'invalid' },
  { id: 'only-a-name' },
])
  assert.equal(
    completedResources([historical], [resource('msg-final', [changed])]).length,
    1,
    'Every registered claim participates, malformed/unknown claims are not inferred',
  );
for (const changed of [
  { ...stable, runId: 'other-turn' },
  { ...stable, text: 'different original bytes' },
  { ...stable, replyPhase: 'commentary' },
  { ...stable, role: 'user', kind: 'userMessage' },
  { ...stable, status: 'inProgress' },
  resource('msg-final', [file], ['additional-unregistered.txt']),
  resource('msg-final', [file, { ...file, id: 'additional-version' }]),
  resource('msg-final', []),
])
  assert.equal(
    completedResources([historical], [changed]).length,
    1,
    'Other source/body/phase/resources preserve historical content',
  );
assert.equal(
  completedResources([{ ...historical, files: ['unknown.txt'] }], [stable]).length,
  1,
  'An unrepresented display-only file is not redundant',
);
assert.equal(
  completedResources(
    [{ ...historical, role: 'user', kind: 'userMessage' }],
    [{ ...stable, role: 'user', kind: 'userMessage' }],
  ).length,
  1,
  'User attachment formatting is not reinterpreted as a final-reply alias',
);
const reordered = Object.fromEntries(Object.entries(file).reverse());
assert.deepEqual(
  completedResources([historical], [resource('msg-final', [reordered])]),
  [],
  'Object field order cannot manufacture a distinct resource',
);
console.log(
  'Completed resource alias boundaries passed: exact body/registered claims, redundant hints, original completion, restart, one-for-one multiplicity, unknown/changed data retained.',
);

for (const malformed of [
  { ...stable, files: {} },
  { ...stable, attachments: 'invalid' },
  { ...stable, attachments: [Object.create(file)] },
])
  assert.equal(
    completedResources([historical], [malformed]).length,
    1,
    'Malformed/inherited claims do not erase old history',
  );

for (const changed of [
  { ...stable, detail: 'additional original detail' },
  { ...stable, replyMessageId: 'different logical reply' },
  { ...stable, blocks: [{ type: 'text', text: 'other structured content' }] },
])
  assert.equal(
    completedResources([historical], [changed]).length,
    1,
    'Additional or changed rendering data remains historical content',
  );

// A cold reader and the original owner render the same uploaded image input
// with different IDs. Only completed, fully registered matching file claims
// can retire that provisional occurrence; display names alone cannot.
const imageInput = (id, claims = [file], files = []) => ({
  ...row(id, 'run', 'userMessage', 'Read the image marker'),
  attachments: claims,
  files,
});
const oldImageInput = imageInput('history-fallback:run:item-1', [file], ['report.txt']);
assert.deepEqual(completedResources([oldImageInput], [imageInput('user-original')]), []);
assert.deepEqual(completedResources([imageInput('user-original')], [oldImageInput]), []);
assert.equal(retained([oldImageInput], [imageInput('user-original')]).length, 1);
assert.equal(
  completedResources(
    [oldImageInput, { ...oldImageInput, id: 'history-fallback:run:item-2' }],
    [imageInput('user-original')],
  ).length,
  1,
  'One original input cannot erase a second equal historical occurrence',
);
assert.equal(completedResources([imageInput('user-one')], [imageInput('user-two')]).length, 1);
for (const changed of [
  imageInput('user-original', [], ['report.txt']),
  imageInput('user-original', [{ ...file, sha256: 'b'.repeat(64) }]),
  imageInput('user-original', [{ ...file, hostId: 'other-host' }]),
  imageInput('user-original', [{ ...file, threadId: 'other-thread' }]),
  imageInput('user-original', [{ ...file, resourceId: 'other-resource' }]),
  imageInput('user-original', [file], ['unknown-image']),
  { ...imageInput('user-original'), runId: 'another-run' },
  { ...imageInput('user-original'), status: 'inProgress' },
  { ...imageInput('user-original'), replyPhase: 'commentary' },
  { ...imageInput('user-original'), detail: 'changed original detail' },
]) {
  assert.equal(completedResources([oldImageInput], [changed]).length, 1);
}
console.log(
  'Completed uploaded-image input aliases: registered exact bytes/target, multiplicity, unknown media and original completion boundaries passed.',
);
