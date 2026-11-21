#!/usr/bin/env node
// Read-only ORIGINAL desktop observer. No Host HTTP writer, model task or arbitrary IPC method.
import { readFile, lstat, open, realpath } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join, dirname } from 'node:path';
import { homedir } from 'node:os';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { currentSpeed } from '../../packages/codex-adapter/dist/generation-settings.js';
import { observeDesktopRuntime } from './real-dev-desktop-runtime-binding.mjs';
import {
  originalUsers,
  originalInputId,
  originalInputText,
  originalProcessCount,
  q04TerminalFacts,
  boundedProcessWindow,
  noChildProof,
  fingerprintFields,
  verifyLocalMapping,
  verifyChildWitness,
} from './real-dev-generation-command-evidence.mjs';

const actions = new Set([
  'q03',
  'origin',
  'queueCancel',
  'queueExecute',
  'steer',
  'stopOrigin',
  'cancel',
  'execute',
  'stop',
  'after-origin',
  'after-stopOrigin',
  'q03-completed',
  'queue-completed',
  'stopped',
]);
const hash = (b) => createHash('sha256').update(b).digest('hex');
const require = (ok, message) => {
  if (!ok) throw Error(message);
};
const options = Object.fromEntries(
  Array.from({ length: (process.argv.length - 2) / 2 }, (_, i) => [
    process.argv[2 + i * 2],
    process.argv[3 + i * 2],
  ]),
);
require(process.argv.length % 2 === 0 &&
  Object.keys(options).every((k) =>
    [
      '--plan',
      '--action',
      '--native',
      '--output',
      '--host-proof',
      '--local-mapping',
      '--child-proof',
      '--child-plan',
    ].includes(k),
  ), 'Exact named arguments required');
require(actions.has(options['--action']) &&
  options['--plan'] &&
  options['--output']?.endsWith('.json'), 'Missing plan/action/JSON output');
require(new Set(process.argv.slice(2).filter((_, i) => i % 2 === 0)).size ===
  (process.argv.length - 2) / 2, 'Duplicate options');
async function privateBytes(path, limit = 65536) {
  const s = await lstat(path);
  require(s.isFile() &&
    !s.isSymbolicLink() &&
    s.uid === process.getuid() &&
    (s.mode & 0o077) === 0 &&
    s.nlink === 1 &&
    s.size <= limit, 'Private bounded file required');
  require((await realpath(path)) === path, 'Canonical path required');
  return readFile(path);
}
const raw = await privateBytes(options['--plan']);
const plan = JSON.parse(raw);
require(plan.version === 1 &&
  plan.provider === 'codex' &&
  plan.target?.agentId === 'codex' &&
  plan.target.runtimeId ===
    'desktop-runtime', 'Exact local Codex target required; EasyWork uses original product export');
require(plan.businessChatAuthorized === true &&
  plan.modelBudgetApproved === true &&
  Date.now() <
    plan.expiresAtEpochMs, 'Current controller lease required even for scoped observation');
require(plan.excludedCodingThreadIds?.length === 6 &&
  new Set(plan.excludedCodingThreadIds).size === 6 &&
  !plan.excludedCodingThreadIds.includes(
    plan.threadId,
  ), 'Never observe a coding/control thread for business proof');
require(/^[a-f0-9]{64}$/.test(plan.desktopRuntimeFingerprint) &&
  /^[a-f0-9]{64}$/.test(
    plan.desktopExecutableSha256,
  ), 'Separate 64hex runtime fingerprint and executable SHA references required');
// Hash only the controller-bound executable. It is never launched by this script.
const exe = await lstat(plan.desktopExecutable);
require(exe.isFile() &&
  !exe.isSymbolicLink() &&
  [0, process.getuid()].includes(exe.uid) &&
  (exe.mode & 0o022) === 0 &&
  exe.size < 256 * 1024 * 1024 &&
  (await realpath(plan.desktopExecutable)) ===
    plan.desktopExecutable, 'Trusted bound executable required');
const observedExecutableSha256 = hash(await readFile(plan.desktopExecutable));
fingerprintFields(plan, observedExecutableSha256);
const native = options['--native']
  ? JSON.parse(await privateBytes(options['--native']))
  : { records: {} };
require(Boolean(options['--child-proof']) ===
  Boolean(
    options['--child-plan'],
  ), 'Child proof and frozen observe-only scope plan must be provided together');
let independentChild = null;
if (options['--child-proof']) {
  const childBytes = await privateBytes(options['--child-proof'], 1024 * 1024);
  const digest = (await privateBytes(options['--child-proof'] + '.sha256', 65)).toString().trim();
  require(/^[a-f0-9]{64}$/.test(digest) &&
    hash(childBytes) === digest, 'Independent child proof sidecar mismatch');
  const childPlanBytes = await privateBytes(options['--child-plan'], 256 * 1024);
  independentChild = {
    proof: JSON.parse(childBytes),
    scope: JSON.parse(childPlanBytes),
    pins: { proofSha256: digest, planSHA256: hash(childPlanBytes) },
  };
}
const rawRecords = native.records ?? (native.record ? [native.record] : []);
const records = Array.isArray(rawRecords)
  ? Object.fromEntries(
      rawRecords.map((record) => {
        const intent = JSON.parse(record.intent);
        const names = Object.keys(plan.slots).filter(
          (name) => plan.slots[name].textSha256 === hash(intent.text),
        );
        require(names.length === 1, 'Exactly one frozen slot must match each native record');
        return [names[0], record];
      }),
    )
  : rawRecords;
if (Array.isArray(rawRecords))
  require(Object.keys(records).length ===
    rawRecords.length, 'Duplicate native slot must not disappear during normalization');
for (const [name, record] of Object.entries(records)) {
  require(plan.slots[name] &&
    hash(record.intent) === record.intentSha256, 'Native immutable record mismatch');
  const intent = JSON.parse(record.intent);
  require(intent.hostId === plan.target.hostId &&
    intent.threadId === plan.threadId &&
    intent.clientMessageId === record.id &&
    intent.text === plan.slots[name].text &&
    intent.attachmentIds?.length === 0, 'Native record is not this frozen business slot');
}
const ipc = new DesktopIpc(join(homedir(), '.codex', 'ipc', 'ipc.sock'));
const text = originalInputText;
const users = originalUsers;
const id = (turn) => turn?.turnId ?? turn?.id ?? null;
try {
  await ipc.connect();
  let state;
  let runtimeFields = fingerprintFields(plan, observedExecutableSha256);
  if (options['--host-proof']) {
    const bytes = await privateBytes(options['--host-proof']);
    const host = JSON.parse(bytes);
    require(options['--local-mapping'], 'Explicit independent local mapping required');
    const mappingBytes = await privateBytes(options['--local-mapping']);
    require(hash(mappingBytes) === plan.localMappingSha256, 'Frozen local mapping bytes changed');
    const mapping = JSON.parse(mappingBytes);
    verifyLocalMapping(plan, mapping, host);
    require(host.hostId === plan.target.hostId &&
      host.workspaceStatus === 200 &&
      host.runtimeMode === 'desktop-runtime' &&
      plan.allowedEndpoints.includes(host.endpoint) &&
      Date.now() - Date.parse(host.observedAt) >= 0 &&
      Date.now() - Date.parse(host.observedAt) <=
        30000, 'Fresh exact authenticated Host GET proof required');
    try {
      const { state: original, ...environment } = await observeDesktopRuntime({
        ipc,
        desktopTurns,
        hostId: plan.target.hostId,
        threadId: plan.threadId,
        executable: plan.desktopExecutable,
        executableSha256: plan.desktopExecutableSha256,
        projectRoot: plan.projectRoot,
      });
      state = original;
      runtimeFields = {
        ...fingerprintFields(plan, observedExecutableSha256, environment, true),
        hostProofSha256: hash(bytes),
        hostProofObservedAt: host.observedAt,
        localMappingSha256: hash(mappingBytes),
        localMappingVerified: true,
      };
    } catch {
      runtimeFields.runtimeEnvironmentReason =
        'Actual process/install/IPC binding unavailable; no reference substitution';
    }
  }
  state ??= await ipc.follow(plan.threadId, 5000, true);
  require(state?.id === plan.threadId &&
    state.hostId === 'local' &&
    ipc.hasOwner(plan.threadId), 'Fresh exact original owner unavailable');
  const turns = desktopTurns(state),
    allUsers = turns.flatMap(users);
  const turnFor = (name) =>
    turns.find((t) => users(t).some((u) => originalInputId(u) === records[name]?.id));
  const count = (name) => {
    if (!records[name]) return null;
    const matching = allUsers.filter((u) => originalInputId(u) === records[name].id);
    require(matching.every(
      (u) => hash(text(u)) === plan.slots[name].textSha256,
    ), 'Original owner input differs under the same operation ID');
    return matching.length;
  };
  const active = turns.findLast((t) => t.status === 'inProgress');
  const action = options['--action'];
  const subjectName =
    {
      cancel: 'queueCancel',
      execute: 'queueExecute',
      stop: 'stopOrigin',
      'q03-completed': 'q03',
      'after-origin': 'origin',
      'after-stopOrigin': 'stopOrigin',
    }[action] ?? action;
  const turn = turnFor(subjectName) ?? active;
  const params = turn?.params ?? {},
    collaboration = params.collaborationMode?.settings ?? {};
  const generation = {
    source: 'original-turn-params',
    model: params.model ?? collaboration.model ?? null,
    effort: params.effort ?? params.reasoningEffort ?? collaboration.reasoning_effort ?? null,
    permissionMode:
      params.permissions === ':danger-full-access' ||
      params.sandboxPolicy?.type === 'dangerFullAccess'
        ? 'full-access'
        : null,
    speed: Object.hasOwn(params, 'serviceTier') ? currentSpeed(params.serviceTier) : null,
    speedParameterPresent: Object.hasOwn(params, 'serviceTier'),
  };
  const toolWindow = boundedProcessWindow(turn, plan.modelToolWorkingDirectory);
  const origin = turnFor('origin'),
    stopTurn = turnFor('stopOrigin');
  const q03Turn = turnFor('q03');
  const q04 = q04TerminalFacts(plan, state, turns, records);
  // A combined child needs a separate current child/owner witness; never invent a count from a marker.
  const toolCount = originalProcessCount;
  const finalCount = (t) =>
    t
      ? (t.items ?? []).filter(
          (x) =>
            ['agentMessage', 'assistantMessage'].includes(x.type) &&
            x.phase === 'final_answer' &&
            (x.text ?? text(x)).trim() === plan.expectedFinalMarker,
        ).length
      : null;
  let childFields = {
    childStarts:
      plan.scenario === 'q04'
        ? q04.childStarts
        : plan.maxChildStarts === 0
          ? noChildProof(q03Turn)
          : null,
    childProofVerified: false,
    combinedAccepted: false,
  };
  if (independentChild) {
    require(action === 'q03-completed' &&
      records.q03 &&
      q03Turn, 'Child proof is only joined after actual native Q03 submission');
    const joined = verifyChildWitness(
      plan,
      records.q03,
      q03Turn,
      independentChild.proof,
      independentChild.scope,
      independentChild.pins,
    );
    const combinedAccepted =
      runtimeFields.desktopRuntimeFingerprintVerified === true &&
      runtimeFields.localMappingVerified === true &&
      q03Turn.status === 'completed' &&
      count('q03') === 1 &&
      generation.model === 'gpt-6.1-sol' &&
      generation.effort === 'xhigh' &&
      generation.permissionMode === plan.settings.permissionMode &&
      (plan.nativeSpeedSelection == null || generation.speed === plan.nativeSpeedSelection) &&
      finalCount(q03Turn) === 1;
    require(combinedAccepted, 'Independent child cannot replace original Q03/provider/settings/final proof');
    childFields = { ...joined, combinedAccepted };
  }
  const proof = {
    run: plan.run,
    leaseId: plan.leaseId,
    planSha256: hash(raw),
    action,
    target: plan.target,
    threadId: plan.threadId,
    ownerVerified: true,
    ...runtimeFields,
    sourceAuthority: 'original-desktop-ipc',
    observedAtEpochMs: Date.now(),
    activeTurnId: id(active),
    subjectOperationId: records[subjectName]?.id ?? null,
    subjectTurnId: id(turn),
    originalUserInputCount: count(subjectName),
    generation,
    ...childFields,
    toolWindow,
    originalToolCount: toolCount(turn),
    toolAccounting:
      'original-desktop-command-executions; separate terminal API poll association remains unknown',
    originToolCount: toolCount(origin),
    stopOriginToolCount: q04.stopOriginToolCount,
    originalFinalMarkerCount: finalCount(action === 'queue-completed' ? origin : turn),
    cancelledInputNeverExecuted: !!records.queueCancel && count('queueCancel') === 0,
    originalQueuePromotedOnce:
      !!origin && count('queueExecute') === 1 && id(turnFor('queueExecute')) === id(origin),
    sameTurnSteerOnce: !!origin && count('steer') === 1 && id(turnFor('steer')) === id(origin),
    completedTurnId: origin?.status === 'completed' ? id(origin) : null,
    stoppedTurnId: stopTurn?.status === 'interrupted' ? id(stopTurn) : null,
    originalTurnInterrupted: !!stopTurn && stopTurn.status === 'interrupted',
    originalTurns: turns.map((t) => ({ id: id(t), status: t.status })),
    modelOrQueueWrites: 0,
    limitations:
      'Unknown wire fields stay null; no inferred model/child/tool timestamp or rewritten IDs. EasyWork and combined child require independent actual product witnesses.',
  };
  const data = Buffer.from(JSON.stringify(proof, null, 2) + '\n');
  const parent = await lstat(dirname(options['--output']));
  require(parent.isDirectory() &&
    !parent.isSymbolicLink() &&
    parent.uid === process.getuid() &&
    (parent.mode & 0o077) === 0, 'Private output directory required');
  const file = await open(options['--output'], 'wx', 0o600);
  try {
    await file.writeFile(data);
    await file.sync();
  } finally {
    await file.close();
  }
  const sidecar = await open(options['--output'].replace(/\.json$/, '') + '.sha256', 'wx', 0o600);
  try {
    await sidecar.writeFile(hash(data) + '\n');
    await sidecar.sync();
  } finally {
    await sidecar.close();
  }
  console.log(JSON.stringify({ action, sha256: hash(data), modelOrQueueWrites: 0 }));
} finally {
  ipc.close();
}
