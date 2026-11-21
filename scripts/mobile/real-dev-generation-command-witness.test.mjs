import test from 'node:test';
import { createHash } from 'node:crypto';
import { runtimeIdentityFingerprint } from './real-dev-desktop-runtime-binding.mjs';
import assert from 'node:assert/strict';
import {
  boundedCommand,
  boundedZshEnvelope,
  boundedProcessWindow,
  originalProcessCount,
  q04TerminalFacts,
  originalUsers,
  originalInputId,
  originalInputText,
  noChildProof,
  runtimeFingerprintReference,
  fingerprintFields,
  verifyLocalMapping,
  verifyChildWitness,
} from './real-dev-generation-command-evidence.mjs';
const turn = () => ({
  items: [
    { type: 'userMessage', clientId: 'owned', content: [{ type: 'text', text: 'fixture' }] },
    {
      type: 'commandExecution',
      id: 'process',
      command: boundedCommand,
      cwd: '/task-empty-workdir',
      startedAtMs: 1000,
    },
  ],
  commandExecutionStartedAtMsById: { process: 1000 },
});
test('observed desktop command timestamp is used without substituting the observation time', () => {
  const result = boundedProcessWindow(turn(), '/task-empty-workdir');
  assert.equal(result.startedAtEpochMs, 1000);
  assert.equal(result.timestampSource, 'commandExecution.startedAtMs');
  assert.equal(result.processStarts, 1);
  assert.equal(result.terminalApiPollAssociation, null);
});
test('missing or conflicting original timestamp rejects a process window', () => {
  const missing = turn();
  delete missing.items[1].startedAtMs;
  assert.equal(boundedProcessWindow(missing, '/task-empty-workdir'), null);
  const mismatch = turn();
  mismatch.commandExecutionStartedAtMsById.process = 1001;
  assert.equal(boundedProcessWindow(mismatch, '/task-empty-workdir'), null);
});
test('a command wrapper or changed body is not silently accepted', () => {
  const wrapped = turn();
  wrapped.items[1].command = '/bin/sh -c ' + boundedCommand;
  assert.equal(boundedProcessWindow(wrapped, '/task-empty-workdir'), null);
  const changed = turn();
  changed.items[1].command = boundedCommand.replace('40', '90');
  assert.equal(boundedProcessWindow(changed, '/task-empty-workdir'), null);
});
test('the observed exact zsh envelope retains its own raw digest without shell parsing', () => {
  const wrapped = turn();
  wrapped.items[1].command = boundedZshEnvelope;
  const result = boundedProcessWindow(wrapped, '/task-empty-workdir');
  assert.equal(result.commandSha256, createHash('sha256').update(boundedCommand).digest('hex'));
  assert.equal(
    result.rendererCommandSha256,
    createHash('sha256').update(boundedZshEnvelope).digest('hex'),
  );
  assert.equal(result.rendererCommandShape, 'observed-zsh-lc-single-quote-literal');
  assert.equal(result.originalCommandItemId, 'process');
  assert.equal(result.startedAtEpochMs, 1000);
  assert.equal(result.terminalApiPollAssociation, null);
});
for (const command of [
  boundedZshEnvelope + '; echo extra',
  boundedZshEnvelope.replace('-lc', '-c'),
  boundedZshEnvelope.replace('/bin/zsh', '/bin/bash'),
  boundedZshEnvelope.replace('40', '90'),
  boundedZshEnvelope + ' ',
  "/bin/zsh -lc 'echo prefix; " + boundedCommand + "'",
])
  test(
    'nonliteral shell envelope rejects ' +
      createHash('sha256').update(command).digest('hex').slice(0, 8),
    () => {
      const wrapped = turn();
      wrapped.items[1].command = command;
      assert.equal(boundedProcessWindow(wrapped, '/task-empty-workdir'), null);
    },
  );
test('a second process or opaque poll cannot become one original process', () => {
  const extra = turn();
  extra.items.push({ ...extra.items[1], id: 'second' });
  assert.equal(boundedProcessWindow(extra, '/task-empty-workdir'), null);
  const poll = turn();
  poll.items.push({ type: 'toolCall', name: 'write_stdin' });
  assert.equal(originalProcessCount(poll), null);
  assert.equal(boundedProcessWindow(poll, '/task-empty-workdir'), null);
});
test('actual steering envelope uses original clientUserMessageId and input', () => {
  const item = {
    type: 'steeringUserMessage',
    clientUserMessageId: 'original-steer',
    serverClientUserMessageId: 'other',
    input: [{ type: 'text', text: 'same fixture' }],
  };
  assert.equal(originalInputId(item), 'original-steer');
  assert.equal(originalInputText(item), 'same fixture');
  assert.equal(originalUsers({ items: [item, { type: 'steered' }] }).length, 1);
});
test('only observed passive items prove zero process/child for minimal Q03', () => {
  const q03 = { items: [{ type: 'userMessage' }, { type: 'reasoning' }, { type: 'agentMessage' }] };
  assert.equal(originalProcessCount(q03), 0);
  assert.equal(noChildProof(q03), 0);
  assert.equal(noChildProof(turn()), null);
  assert.equal(noChildProof({ items: [{ type: 'collabAgentToolCall' }] }), null);
  assert.equal(originalProcessCount({ items: [{ type: 'futureUnknownTool' }] }), null);
});

test('a frozen reference and matching disk hash cannot verify the actual running runtime', () => {
  const proof = runtimeFingerprintReference('frozen-reference', 'frozen-reference');
  assert.equal(proof.desktopRuntimeFingerprintReference, 'frozen-reference');
  assert.equal(proof.observedDesktopExecutableSha256, 'frozen-reference');
  assert.equal(proof.desktopRuntimeFingerprint, null);
  assert.equal(proof.desktopRuntimeFingerprintVerified, false);
  assert.match(proof.desktopRuntimeFingerprintSource, /unverified/);
});

test('the real tool cwd must equal the frozen empty task directory', () => {
  const t = turn();
  t.items[1].cwd = '/user-project';
  assert.equal(boundedProcessWindow(t, '/task-empty-workdir'), null);
});

const mappedFixture = () => {
  const identity = {
    hostId: 'provider-dev',
    runtimeId: 'desktop-runtime',
    pid: 1,
    uid: 501,
    startedAtText: 'Fri Nov 13 01:00:00 2026',
    executable: '/trusted/desktop',
    device: '17',
    inode: '33',
    size: '71',
    executableSha256: 'b'.repeat(64),
    socket: '/fixture/.codex/ipc/ipc.sock',
    socketDevice: '17',
    socketInode: '34',
  };
  const environment = {
    identity,
    fingerprint: runtimeIdentityFingerprint(identity),
    threadId: 'business',
    processAndInstallVerified: true,
    originalOwnerVerified: true,
    source: 'fixture independent observation',
  };
  const plan = {
    controlHostId: 'physical-control',
    threadId: 'business',
    target: {
      agentId: 'codex',
      hostId: 'provider-dev',
      workspaceId: 'dev-workspace',
      runtimeId: 'desktop-runtime',
    },
    desktopExecutable: '/trusted/desktop',
    desktopExecutableSha256: identity.executableSha256,
    desktopRuntimeFingerprint: environment.fingerprint,
    allowedEndpoints: ['https://local.invalid'],
    expiresAtEpochMs: 2000,
  };
  const mapping = {
    version: 1,
    sourceAuthority: 'independent-local-desktop-and-host',
    controlHostId: plan.controlHostId,
    actualTarget: { ...plan.target },
    threadId: plan.threadId,
    endpoint: plan.allowedEndpoints[0],
    expiresAtEpochMs: 2000,
    environment,
  };
  const host = {
    hostId: 'provider-dev',
    workspaceStatus: 200,
    runtimeMode: 'desktop-runtime',
    endpoint: 'https://local.invalid',
    observedAt: new Date(1000).toISOString(),
  };
  return { plan, mapping, host, environment };
};
test('different valid executable SHA and composite runtime FP verify independently', () => {
  const { plan, environment } = mappedFixture();
  assert.notEqual(plan.desktopExecutableSha256, plan.desktopRuntimeFingerprint);
  assert.equal(
    fingerprintFields(plan, plan.desktopExecutableSha256, environment, true)
      .desktopRuntimeFingerprintVerified,
    true,
  );
  assert.throws(() => fingerprintFields(plan, 'c'.repeat(64), environment, true));
  assert.equal(
    fingerprintFields(
      plan,
      plan.desktopExecutableSha256,
      { ...environment, fingerprint: 'c'.repeat(64) },
      true,
    ).desktopRuntimeFingerprintVerified,
    false,
  );
  for (const field of ['desktopExecutableSha256', 'desktopRuntimeFingerprint'])
    assert.throws(() =>
      fingerprintFields({ ...plan, [field]: 'not-64-hex' }, plan.desktopExecutableSha256),
    );
});
test('independent Provider/control Hosts require observed target and fresh Host mapping', () => {
  const { plan, mapping, host } = mappedFixture();
  assert.notEqual(plan.controlHostId, plan.target.hostId);
  assert.equal(verifyLocalMapping(plan, mapping, host, 1000), true);
  assert.throws(() =>
    verifyLocalMapping(plan, { ...mapping, controlHostId: 'other-control' }, host, 1000),
  );
  assert.throws(() =>
    verifyLocalMapping(
      plan,
      { ...mapping, actualTarget: { ...mapping.actualTarget, hostId: 'other-provider' } },
      host,
      1000,
    ),
  );
  assert.throws(() => verifyLocalMapping(plan, mapping, { ...host, hostId: 'other-host' }, 1000));
  assert.throws(() =>
    verifyLocalMapping(
      plan,
      mapping,
      { ...host, observedAt: new Date(-40000).toISOString() },
      1000,
    ),
  );
  assert.throws(() =>
    verifyLocalMapping(
      plan,
      { ...mapping, environment: { ...mapping.environment, fingerprint: 'c'.repeat(64) } },
      host,
      1000,
    ),
  );
});

const digest = (value) => createHash('sha256').update(value).digest('hex');
function childFixture() {
  const now = 100000;
  const helpers = {
    'q02-readonly-witness.mjs': 'a'.repeat(64),
    'q02-witness-core.mjs': 'b'.repeat(64),
    'q02-readonly-files.mjs': 'c'.repeat(64),
    'q02-history-access.mjs': 'd'.repeat(64),
  };
  const target = {
    agentId: 'codex',
    hostId: 'provider',
    workspaceId: 'workspace',
    runtimeId: 'desktop-runtime',
  };
  const body = 'one frozen combined body';
  const intent = JSON.stringify({
    hostId: 'provider',
    threadId: 'qa',
    clientMessageId: 'native-id',
    text: body,
    attachmentIds: [],
  });
  const record = { id: 'native-id', intent, intentSha256: digest(intent) };
  const plan = {
    scenario: 'q03',
    maxChildStarts: 1,
    taskId: 'task',
    controlHostId: 'control',
    threadId: 'qa',
    target,
    slots: { q03: { text: body, textSha256: digest(body) } },
    excludedCodingThreadIds: ['coding'],
    expiresAtEpochMs: now + 10000,
    childWitness: {
      helperPins: helpers,
      artifactPinsSha256: digest('{}'),
      sourcePinsSha256: digest('{}'),
    },
  };
  const scope = {
    version: 1,
    taskId: 'task',
    scope: 'observe-only',
    modelExecutionAuthorized: false,
    controlHostId: 'control',
    parentThreadId: 'qa',
    providerTarget: target,
    subjectOperationId: record.id,
    subjectInputSha256: digest(body),
    expiresAtEpochMs: now + 10000,
    maxChildReads: 1,
    excludedCodingThreadIds: ['coding'],
    expectedChild: { model: 'gpt-6.1-sol', effort: 'xhigh', finalLineCount: 96 },
    helperPins: helpers,
    artifactPins: {},
    sourcePins: {},
    limits: { rolloutBytes: 8388608, outputBytes: 1048576 },
  };
  const parent = {
    id: 'parent-turn',
    items: [
      { type: 'userMessage', clientId: record.id, content: [{ type: 'text', text: body }] },
      {
        id: 'spawn-id',
        type: 'collabAgentToolCall',
        tool: 'spawn',
        status: 'completed',
        receiverThreadIds: ['child'],
      },
    ],
  };
  const text = Array.from({ length: 96 }, (_, i) => String(i + 1)).join('\n');
  const proof = {
    version: 2,
    parentThreadId: 'qa',
    controlHostId: 'control',
    providerTarget: target,
    observedAt: new Date(now).toISOString(),
    planSHA256: digest(JSON.stringify(scope)),
    helperPins: helpers,
    helperSHA256: helpers['q02-readonly-witness.mjs'],
    artifactPinsSHA256: digest('{}'),
    status: 'child-witness-complete',
    q02WitnessComplete: true,
    childStarts: 1,
    subjectOperationVerified: true,
    subjectTurnId: parent.id,
    parent: {
      ownerVerified: true,
      historyComplete: true,
      parentThreadId: null,
      forkedFromId: null,
    },
    unknownParentItemKinds: [],
    modelOrQueueWrites: 0,
    metadataReaderInstantiated: false,
    metadataReaderLifecycle: 'not-created',
    metadataReaderTrace: [],
    explicitChildIds: ['child'],
    parentLinkFacts: [
      {
        turnId: parent.id,
        type: 'collabAgentToolCall',
        tool: 'spawn',
        status: 'completed',
        itemId: 'spawn-id',
        childId: 'child',
      },
    ],
    readTrace: [
      { method: 'follow', id: 'qa' },
      { method: 'follow', id: 'child' },
      { method: 'follow', id: 'qa' },
    ],
    child: {
      id: 'child',
      ownerVerified: true,
      authority: 'original-desktop-ipc',
      controlAvailable: null,
      historyComplete: true,
      turnCount: 1,
      lastTurnId: 'child-turn',
      lastTurnStatus: 'completed',
      parentThreadId: 'qa',
      parentClaims: [{ path: 'parentThreadId', value: 'qa', valid: true }],
      graph: {
        verified: true,
        edgeVerified: true,
        rootVerified: true,
        backlinkVerified: true,
        rootThreadId: 'qa',
        branchThreadIds: ['qa', 'child'],
      },
      generationVerified: true,
      generationEvidenceComplete: true,
      outputEvidenceComplete: true,
      completedSuccessfully: true,
      toolFacts: {
        schemaVersion: 1,
        turnId: 'child-turn',
        authority: 'original-desktop-ipc',
        turnContextMatched: true,
        executionTurnVerified: true,
        inheritedTurnExcluded: false,
        sourceComplete: true,
        terminal: true,
        noToolsVerified: true,
        activeToolCount: 0,
        knownActiveToolCount: 0,
        activeItems: [],
        unknownItemTypes: [],
      },
      generation: { model: 'gpt-6.1-sol', effort: 'xhigh', conflict: false },
      turnContext: { turnId: 'child-turn', model: 'gpt-6.1-sol', effort: 'xhigh', conflict: false },
      rollout: {
        available: true,
        sessionId: 'child',
        metadataParentCompatible: true,
        exactFinalMatches: 1,
        sameTurnFinalMatched: true,
        source: {
          authority: 'official exact-session JSONL; read-only consistent snapshot',
          sha256: 'e'.repeat(64),
          bytes: 1000,
        },
      },
      transcript: { omitted: false, verification: 'terminal-output-and-jsonl-verified' },
      final: { text, sha256: digest(text), lines: 96, exactJSONLMatch: true },
    },
  };
  const pins = { proofSha256: digest(JSON.stringify(proof)), planSHA256: proof.planSHA256 };
  return { plan, record, parent, proof, scope, pins, now };
}
const joinChild = (x) =>
  verifyChildWitness(x.plan, x.record, x.parent, x.proof, x.scope, x.pins, x.now);
test('complete independent child joins the actual native ID/body/Turn while provider differs from control', () => {
  const x = childFixture();
  assert.notEqual(x.plan.controlHostId, x.plan.target.hostId);
  assert.equal(joinChild(x).childStarts, 1);
  assert.equal(joinChild(x).childProofVerified, true);
});
for (const [name, mutate] of Object.entries({
  'null child count': (x) => {
    x.proof.childStarts = null;
  },
  'marker-only incomplete child': (x) => {
    x.proof.q02WitnessComplete = false;
  },
  'wrong provider': (x) => {
    x.proof.providerTarget = { ...x.plan.target, hostId: 'other' };
  },
  'wrong control': (x) => {
    x.proof.controlHostId = 'other';
  },
  'wrong native input': (x) => {
    x.scope.subjectOperationId = 'coordination-id';
  },
  'wrong frozen body': (x) => {
    x.scope.subjectInputSha256 = 'f'.repeat(64);
  },
  'wrong parent Turn': (x) => {
    x.proof.subjectTurnId = 'other';
  },
  'stale child': (x) => {
    x.proof.observedAt = new Date(x.now - 30001).toISOString();
  },
  'future child': (x) => {
    x.proof.observedAt = new Date(x.now + 1).toISOString();
  },
  'changed helper': (x) => {
    x.proof.helperSHA256 = 'f'.repeat(64);
  },
  'changed source pin': (x) => {
    x.scope.sourcePins = { changed: 'f'.repeat(64) };
  },
  'changed artifact pin': (x) => {
    x.proof.artifactPinsSHA256 = 'f'.repeat(64);
  },
  'scope file digest mismatch': (x) => {
    x.pins.planSHA256 = 'f'.repeat(64);
  },
  'unverified original child settings': (x) => {
    x.proof.child.generationVerified = false;
  },
  'wrong original child model': (x) => {
    x.proof.child.generation.model = 'other';
  },
  'missing same-Turn context': (x) => {
    x.proof.child.turnContext.turnId = 'other';
  },
  'excluded child': (x) => {
    x.plan.excludedCodingThreadIds.push('child');
    x.scope.excludedCodingThreadIds.push('child');
  },
  'unknown child read': (x) => {
    x.proof.readTrace.push({ method: 'follow', id: 'unrelated' });
  },
  'truncated text': (x) => {
    x.proof.child.transcript.omitted = true;
  },
  'changed final text': (x) => {
    x.proof.child.final.text = 'marker';
  },
}))
  test('child join rejects ' + name, () => {
    const x = childFixture();
    mutate(x);
    assert.throws(() => joinChild(x));
  });

function historyChildFixture() {
  const x = childFixture();
  const policy = {
    method: 'thread/read',
    includeTurns: true,
    maxChildren: 1,
    onlyAfterChildOwnerUnavailable: true,
    noOwnerRegistration: true,
  };
  Object.assign(x.scope, {
    historyReadPolicy: policy,
    historyReaderExecutable: '/trusted/metadata-codex',
    historyReaderExecutableSHA256: 'f'.repeat(64),
  });
  Object.assign(x.plan.childWitness, {
    historyReaderExecutable: x.scope.historyReaderExecutable,
    historyReaderExecutableSHA256: x.scope.historyReaderExecutableSHA256,
  });
  x.proof.planSHA256 = digest(JSON.stringify(x.scope));
  x.pins.planSHA256 = x.proof.planSHA256;
  Object.assign(x.proof, {
    historyOutputEvidenceComplete: true,
    metadataReaderInstantiated: true,
    metadataReaderLifecycle: 'closed-confirmed',
    metadataReaderTrace: [
      { method: 'connect', role: 'metadata-reader-only' },
      { method: 'thread/read', params: { threadId: 'child', includeTurns: true } },
      { method: 'closed-and-confirmed', role: 'metadata-reader-only' },
    ],
    readTrace: [
      { method: 'follow', id: 'qa' },
      { method: 'follow', id: 'child' },
      { method: 'follow', id: 'qa' },
      {
        method: 'CodexHistoryReader.read',
        rpc: 'thread/read',
        threadId: 'child',
        includeTurns: true,
      },
      { method: 'follow', id: 'qa' },
    ],
  });
  Object.assign(x.proof.child, {
    ownerVerified: false,
    authority: 'official-history',
    controlAvailable: null,
    generation: {
      model: 'gpt-6.1-sol',
      effort: 'xhigh',
      conflict: false,
      source: 'official JSONL turn_context bound to same terminal Turn; no default model/effort',
    },
  });
  x.proof.child.toolFacts.authority = 'official-history';
  return x;
}
test('W5 v2 official-history complete child joins without claiming live child ownership/control', () => {
  const x = historyChildFixture();
  const result = joinChild(x);
  assert.equal(result.childStarts, 1);
  assert.equal(result.childProofVerified, true);
  assert.equal(result.childAuthority, 'official-history');
  assert.equal(result.childOwnerVerified, false);
  assert.equal(result.childControlAvailable, null);
});
test('unreported backlink stays unknown while a fresh verified parent edge and full official source join', () => {
  const x = historyChildFixture();
  x.proof.child.parentClaims = [];
  x.proof.child.parentThreadId = null;
  x.proof.child.graph.backlinkVerified = null;
  assert.equal(joinChild(x).childProofVerified, true);
});
test('unconfirmed metadata reader close remains a separate unknown, not invented control or confirmed cleanup', () => {
  const x = historyChildFixture();
  x.proof.metadataReaderLifecycle = 'close-unconfirmed';
  x.proof.metadataReaderTrace.pop();
  const result = joinChild(x);
  assert.equal(result.childMetadataReaderLifecycle, 'close-unconfirmed');
  assert.equal(result.childOwnerVerified, false);
  assert.equal(result.childControlAvailable, null);
});
for (const [name, mutate] of Object.entries({
  'parent owner unavailable': (x) => {
    x.proof.parent.ownerVerified = false;
  },
  'unknown child authority': (x) => {
    x.proof.child.authority = 'persisted-history';
  },
  'history masquerading as owner': (x) => {
    x.proof.child.ownerVerified = true;
  },
  'history masquerading as control': (x) => {
    x.proof.child.controlAvailable = true;
  },
  'unknown history output completeness': (x) => {
    x.proof.historyOutputEvidenceComplete = null;
  },
  'unknown child output completeness': (x) => {
    x.proof.child.outputEvidenceComplete = null;
  },
  'failed terminal output': (x) => {
    x.proof.child.lastTurnStatus = 'failed';
    x.proof.child.completedSuccessfully = false;
  },
  'missing actual same-Turn generation': (x) => {
    x.proof.child.generationEvidenceComplete = false;
  },
  'reported parent conflict': (x) => {
    x.proof.child.parentClaims.push({ path: 'parent_thread_id', value: 'other', valid: true });
  },
  'invalid parent claim': (x) => {
    x.proof.child.parentClaims[0].valid = false;
  },
  'invented missing backlink': (x) => {
    x.proof.child.parentClaims = [];
    x.proof.child.parentThreadId = null;
  },
  'wrong history child id': (x) => {
    x.proof.readTrace[3].threadId = 'other';
  },
  'wrong history method': (x) => {
    x.proof.readTrace[3].rpc = 'thread/resume';
  },
  'partial history request': (x) => {
    x.proof.readTrace[3].includeTurns = false;
  },
  'missing parent recheck': (x) => {
    x.proof.readTrace.pop();
  },
  'extra metadata method': (x) => {
    x.proof.metadataReaderTrace.splice(2, 0, { method: 'thread/list' });
  },
  'wrong metadata child id': (x) => {
    x.proof.metadataReaderTrace[1].params.threadId = 'other';
  },
  'unfrozen metadata executable': (x) => {
    x.scope.historyReaderExecutableSHA256 = '0'.repeat(64);
  },
  'owner registration permitted': (x) => {
    x.scope.historyReadPolicy.noOwnerRegistration = false;
  },
  'fallback before owner unavailable': (x) => {
    x.scope.historyReadPolicy.onlyAfterChildOwnerUnavailable = false;
  },
  'missing history-access source pin': (x) => {
    delete x.plan.childWitness.helperPins['q02-history-access.mjs'];
  },
  'unknown helper module': (x) => {
    x.plan.childWitness.helperPins['arbitrary.mjs'] = '0'.repeat(64);
  },
  'missing JSONL source digest': (x) => {
    delete x.proof.child.rollout.source.sha256;
  },
  'wrong JSONL authority': (x) => {
    x.proof.child.rollout.source.authority = 'requested-model';
  },
  'nonterminal transcript label': (x) => {
    x.proof.child.transcript.verification = 'observed-only-not-completion-proof';
  },
}))
  test('official-history join rejects ' + name, () => {
    const x = historyChildFixture();
    mutate(x);
    assert.throws(() => joinChild(x));
  });

test('combined parent has one actual spawn and only same-child wait/close plus observed lifecycle metadata', () => {
  const x = historyChildFixture();
  x.parent.items.push(
    {
      id: 'wait-id',
      type: 'collabAgentToolCall',
      tool: 'waitAgent',
      status: 'completed',
      receiverThreadIds: ['child'],
    },
    {
      id: 'close-id',
      type: 'collabAgentToolCall',
      tool: 'closeAgent',
      status: 'completed',
      receiverThreadIds: ['child'],
    },
    { id: 'activity-id', type: 'subAgentActivity', agentThreadId: 'child', kind: 'completed' },
  );
  assert.equal(originalProcessCount(x.parent), null);
  const proof = joinChild(x);
  assert.equal(proof.parentToolBudgetVerified, true);
  assert.equal(proof.parentCommandExecutionCount, 0);
  assert.equal(proof.parentUnapprovedToolCount, 0);
});
for (const [name, mutate] of Object.entries({
  'collab plus commandExecution': (x) => {
    x.parent.items.push({ type: 'commandExecution', id: 'extra-command', command: boundedCommand });
  },
  'collab plus fileChange': (x) => {
    x.parent.items.push({ type: 'fileChange', id: 'extra-file' });
  },
  'collab plus network tool': (x) => {
    x.parent.items.push({ type: 'webSearch', id: 'extra-network' });
  },
  'collab plus unknown tool': (x) => {
    x.parent.items.push({ type: 'futureUnknownTool', id: 'extra' });
  },
  'second spawn': (x) => {
    x.parent.items.push({ ...x.parent.items[1], id: 'second-spawn' });
  },
  'missing actual spawn': (x) => {
    x.parent.items.pop();
  },
  'mismatched original spawn id': (x) => {
    x.parent.items[1].id = 'other-spawn';
  },
  'spawn wrong child': (x) => {
    x.parent.items[1].receiverThreadIds = ['other'];
  },
  'wait wrong child': (x) => {
    x.parent.items.push({
      id: 'wait',
      type: 'collabAgentToolCall',
      tool: 'waitAgent',
      receiverThreadIds: ['other'],
    });
  },
  'resume child': (x) => {
    x.parent.items.push({
      id: 'resume',
      type: 'collabAgentToolCall',
      tool: 'resumeAgent',
      receiverThreadIds: ['child'],
    });
  },
  'send extra child input': (x) => {
    x.parent.items.push({
      id: 'send',
      type: 'collabAgentToolCall',
      tool: 'sendInput',
      receiverThreadIds: ['child'],
    });
  },
  'unknown lifecycle metadata': (x) => {
    x.parent.items.push({
      id: 'activity',
      type: 'subAgentActivity',
      agentThreadId: 'child',
      kind: 'unknown',
    });
  },
}))
  test('combined parent budget rejects ' + name, () => {
    const x = historyChildFixture();
    mutate(x);
    assert.throws(() => joinChild(x), /actual parent Turn/);
  });

test('combined joins W5 exact child execution tool facts rather than only q02WitnessComplete', () => {
  for (const x of [childFixture(), historyChildFixture()]) {
    const result = joinChild(x);
    assert.equal(result.childToolBudgetVerified, true);
    assert.equal(result.childActiveToolCount, 0);
    assert.equal(result.childExecutionTurnId, x.proof.child.lastTurnId);
  }
});
test('inherited history Turns are not new child executions; exact selected execution Turn remains required', () => {
  const x = historyChildFixture();
  x.proof.child.turnCount = 2;
  assert.equal(joinChild(x).childToolBudgetVerified, true);
  x.proof.child.toolFacts.inheritedTurnExcluded = true;
  assert.throws(() => joinChild(x), /same actual child execution Turn/);
});
for (const [name, mutate] of Object.entries({
  'missing facts': (x) => {
    delete x.proof.child.toolFacts;
  },
  'unknown facts': (x) => {
    x.proof.child.toolFacts = null;
  },
  'unrecognized schema': (x) => {
    x.proof.child.toolFacts.schemaVersion = 2;
  },
  'wrong execution Turn': (x) => {
    x.proof.child.toolFacts.turnId = 'other-turn';
  },
  'wrong source authority': (x) => {
    x.proof.child.toolFacts.authority = 'original-desktop-ipc';
  },
  'unverified execution': (x) => {
    x.proof.child.toolFacts.executionTurnVerified = false;
  },
  'unmatched context': (x) => {
    x.proof.child.toolFacts.turnContextMatched = false;
  },
  'inherited selected Turn': (x) => {
    x.proof.child.toolFacts.inheritedTurnExcluded = true;
  },
  'incomplete source': (x) => {
    x.proof.child.toolFacts.sourceComplete = false;
  },
  'running passive prefix': (x) => {
    x.proof.child.toolFacts.terminal = false;
  },
  'unverified zero': (x) => {
    x.proof.child.toolFacts.noToolsVerified = false;
  },
  'unknown count with known partial zero': (x) => {
    x.proof.child.toolFacts.activeToolCount = null;
  },
  'known command despite q02 flag': (x) => {
    x.proof.child.toolFacts.activeToolCount = 1;
  },
  'partial active classification': (x) => {
    x.proof.child.toolFacts.knownActiveToolCount = 1;
  },
  'active collab item': (x) => {
    x.proof.child.toolFacts.activeItems = [{ id: 'extra', type: 'collabAgentToolCall' }];
  },
  'unknown item type': (x) => {
    x.proof.child.toolFacts.unknownItemTypes = ['futureTool'];
  },
}))
  test('combined child budget rejects ' + name, () => {
    const x = historyChildFixture();
    assert.equal(x.proof.q02WitnessComplete, true);
    mutate(x);
    assert.throws(() => joinChild(x), /same actual child execution Turn/);
  });

// Q04 terminal-budget facts: offline boundary inputs only, never real DEV evidence.
const q04FactHash = (value) => createHash('sha256').update(value).digest('hex');
const q04UnknownFacts = { stopOriginToolCount: null, childStarts: null };
const q04User = (id, text) => ({
  type: 'userMessage',
  clientId: id,
  content: [{ type: 'text', text }],
});
const q04Command = (id) => ({
  type: 'commandExecution',
  id,
  command: boundedCommand,
  cwd: '/offline-q04-workdir',
  startedAtMs: 1000,
});
const q04Collab = () => ({
  type: 'collabAgentToolCall',
  id: 'offline-spawn',
  tool: 'spawnAgent',
  status: 'completed',
  receiverThreadIds: ['offline-baseline-child'],
});
function q04FactSample() {
  const plan = {
    scenario: 'q04',
    run: 'offline-run',
    leaseId: 'offline-lease',
    target: {
      agentId: 'codex',
      hostId: 'offline-host',
      workspaceId: 'offline-workspace',
      runtimeId: 'desktop-runtime',
    },
    baselineTurnIds: ['baseline-q03'],
    desktopRuntimeFingerprint: 'd'.repeat(64),
    settings: { model: 'gpt-6.1-sol', effort: 'xhigh', permissionMode: 'full-access' },
    modelToolWorkingDirectory: '/offline-q04-workdir',
    toolCommandSha256: q04FactHash(boundedCommand),
    maxChildStarts: 0,
    maxTextSubmissions: 5,
    slots: {
      origin: { textSha256: q04FactHash('offline origin') },
      stopOrigin: { textSha256: q04FactHash('offline stop') },
    },
  };
  const records = { origin: { id: 'offline-origin-op' }, stopOrigin: { id: 'offline-stop-op' } };
  const turns = [
    {
      turnId: 'baseline-q03',
      status: 'completed',
      items: [q04User('prior-op', 'prior input'), q04Collab()],
    },
    {
      turnId: 'new-origin',
      status: 'completed',
      items: [q04User(records.origin.id, 'offline origin'), q04Command('origin-process')],
      commandExecutionStartedAtMsById: { 'origin-process': 1000 },
    },
    {
      turnId: 'new-stop',
      status: 'interrupted',
      items: [q04User(records.stopOrigin.id, 'offline stop'), q04Command('stop-process')],
      commandExecutionStartedAtMsById: { 'stop-process': 1000 },
    },
  ];
  return { plan, records, turns };
}
function q04FactHistory(f, kind = 'canonical') {
  if (kind === 'legacy')
    return { turns: f.turns, turnsPagination: { hasLoadedOldest: true, olderCursor: null } };
  return {
    turnHistory: {
      kind: 'canonical',
      history: {
        isComplete: true,
        islands: [{ entries: f.turns.map((_, i) => ({ value: `key-${i}` })) }],
        entitiesByKey: Object.fromEntries(f.turns.map((turn, i) => [`key-${i}`, turn])),
      },
    },
  };
}
const q04Facts = (f, state = q04FactHistory(f), turns = f.turns) =>
  q04TerminalFacts(f.plan, state, turns, f.records);

test('Q04 terminal G01 complete canonical and legacy terminal facts exclude the one baseline Q03 child', () => {
  const f = q04FactSample();
  assert.equal(f.turns[0].items[1].receiverThreadIds.length, 1);
  assert.equal(originalProcessCount(f.turns[0]), null);
  for (const kind of ['canonical', 'legacy']) {
    const result = q04Facts(f, q04FactHistory(f, kind));
    assert.deepEqual(result, { stopOriginToolCount: 1, childStarts: 0 });
  }
});

test('Q04 terminal G02 queue-completed has only origin and cannot claim terminal child zero', () => {
  const f = q04FactSample();
  f.turns.pop();
  delete f.records.stopOrigin;
  assert.deepEqual(q04Facts(f), q04UnknownFacts);
  assert.equal(originalProcessCount(f.turns[1]), 1);
});

test('Q04 terminal G03 early stop snapshots and nonterminal or wrong final statuses cannot certify stopped', () => {
  for (const status of ['inProgress', 'completed', 'failed', undefined]) {
    const f = q04FactSample();
    f.turns[2].status = status;
    assert.deepEqual(q04Facts(f), q04UnknownFacts, String(status));
  }
  const f = q04FactSample();
  f.turns[1].status = 'inProgress';
  assert.deepEqual(q04Facts(f), q04UnknownFacts);
});

test('Q04 terminal G04 incomplete canonical or legacy history and unavailable normalized Turns stay unknown', () => {
  const f = q04FactSample();
  const canonicalMutations = [
    (s) => {
      s.turnHistory.history.isComplete = false;
    },
    (s) => {
      delete s.turnHistory.history.isComplete;
    },
    (s) => {
      delete s.turnHistory.history.islands;
    },
    (s) => {
      s.turnHistory.history.entitiesByKey = null;
    },
  ];
  for (const mutate of canonicalMutations) {
    const s = q04FactHistory(f);
    mutate(s);
    assert.deepEqual(q04Facts(f, s), q04UnknownFacts);
  }
  for (const pagination of [
    {},
    { hasLoadedOldest: false },
    { hasLoadedOldest: true, olderCursor: 'remaining' },
  ]) {
    assert.deepEqual(q04Facts(f, { turns: f.turns, turnsPagination: pagination }), q04UnknownFacts);
  }
  assert.deepEqual(
    q04TerminalFacts(f.plan, q04FactHistory(f), undefined, f.records),
    q04UnknownFacts,
  );
  assert.deepEqual(q04TerminalFacts(f.plan, q04FactHistory(f), [{}], f.records), q04UnknownFacts);
});

test('Q04 terminal G05 duplicate missing or unexpected baseline and Turn identities never become zero', () => {
  for (const mutate of [
    (f) => {
      f.plan.baselineTurnIds.push('baseline-q03');
    },
    (f) => {
      f.plan.baselineTurnIds = ['missing-baseline'];
    },
    (f) => {
      f.plan.baselineTurnIds = [];
    },
    (f) => {
      f.turns.shift();
    },
    (f) => {
      f.turns[2].turnId = 'new-origin';
    },
    (f) => {
      f.turns[2].turnId = '';
    },
    (f) => {
      f.turns.push({ turnId: 'unexpected-new', status: 'completed', items: [] });
    },
    (f) => {
      f.turns.splice(1, 1);
    },
  ]) {
    const f = q04FactSample();
    mutate(f);
    assert.deepEqual(q04Facts(f), q04UnknownFacts);
  }
});

test('Q04 terminal G06 the exact original operation and text digest must bind uniquely', () => {
  for (const mutate of [
    (f) => {
      delete f.records.stopOrigin;
    },
    (f) => {
      f.records.stopOrigin.id = 'different-op';
    },
    (f) => {
      f.records.stopOrigin.id = '';
    },
    (f) => {
      f.plan.slots.stopOrigin.textSha256 = q04FactHash('other body');
    },
    (f) => {
      f.turns[2].items[0].content[0].text = 'rewritten body';
    },
    (f) => {
      f.turns[2].items.push(structuredClone(f.turns[2].items[0]));
    },
    (f) => {
      f.records.stopOrigin.id = f.records.origin.id;
    },
  ]) {
    const f = q04FactSample();
    mutate(f);
    assert.deepEqual(q04Facts(f), q04UnknownFacts);
  }
});

test('Q04 terminal G07 final snapshot preserves extra or missing stop command counts', () => {
  const f = q04FactSample();
  assert.deepEqual(q04Facts(f), { stopOriginToolCount: 1, childStarts: 0 });
  f.turns[2].items.push(q04Command('second-process'));
  assert.deepEqual(q04Facts(f), { stopOriginToolCount: 2, childStarts: 0 });
  const noCommand = q04FactSample();
  noCommand.turns[2].items.pop();
  assert.deepEqual(q04Facts(noCommand), { stopOriginToolCount: 0, childStarts: 0 });
});

test('Q04 terminal G08 collab child or unknown active item is never silently converted to child zero', () => {
  for (const index of [1, 2])
    for (const item of [
      q04Collab(),
      { type: 'subAgentActivity', id: 'child-start', kind: 'started', agentThreadId: 'new-child' },
      { type: 'mcpToolCall' },
      { type: 'futureUnknownTool' },
      { type: 'toolCall', name: 'write_stdin' },
    ]) {
      const f = q04FactSample();
      f.turns[index].items.push(item);
      const result = q04Facts(f);
      assert.equal(result.childStarts, null);
      assert.equal(result.stopOriginToolCount, index === 2 ? null : 1);
    }
});

test('Q04 terminal G09 final command literal cwd and original timestamp must still identify the bounded command', () => {
  for (const mutate of [
    (t) => {
      t.items[1].command = boundedCommand.replace('40', '90');
    },
    (t) => {
      t.items[1].command = '/bin/sh -c ' + boundedCommand;
    },
    (t) => {
      t.items[1].cwd = '/different-directory';
    },
    (t) => {
      delete t.items[1].startedAtMs;
    },
    (t) => {
      t.commandExecutionStartedAtMsById['stop-process'] = 1001;
    },
  ]) {
    const f = q04FactSample();
    mutate(f.turns[2]);
    assert.deepEqual(q04Facts(f), { stopOriginToolCount: null, childStarts: 0 });
  }
  const f = q04FactSample();
  f.turns[2].items[1].command = boundedZshEnvelope;
  assert.deepEqual(q04Facts(f), { stopOriginToolCount: 1, childStarts: 0 });
  assert.equal(
    boundedProcessWindow(f.turns[2], f.plan.modelToolWorkingDirectory).terminalApiPollAssociation,
    null,
  );
});

test('Q04 terminal G10 Q04 child facts come from complete Turns rather than a zero planned budget', () => {
  const f = q04FactSample();
  f.plan.maxChildStarts = 0;
  f.turns[1].items.push(q04Collab());
  assert.equal(q04Facts(f).childStarts, null);
  f.turns[1].items.pop();
  delete f.plan.maxChildStarts;
  assert.deepEqual(q04Facts(f), { stopOriginToolCount: 1, childStarts: 0 });
  f.plan.scenario = 'q03';
  assert.deepEqual(q04Facts(f), q04UnknownFacts);
});
