import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { runtimeIdentityFingerprint } from './real-dev-desktop-runtime-binding.mjs';

export const boundedCommand = 'python3 -c "import time; time.sleep(40)"';
// Observed on existing original Turns, not an arbitrary shell grammar.
export const boundedZshEnvelope = "/bin/zsh -lc '" + boundedCommand + "'";
const hash = (text) => createHash('sha256').update(text).digest('hex');
// Shapes observed from a current original owner. Unknown item types are never zero tools/children.
const passive = new Set([
  'userMessage',
  'steeringUserMessage',
  'steered',
  'agentMessage',
  'reasoning',
  'plan',
  'turnSummary',
  'compaction',
  'contextCompaction',
]);
export function originalUsers(turn) {
  return (turn?.items ?? []).filter((item) =>
    ['userMessage', 'steeringUserMessage'].includes(item.type),
  );
}
export function originalInputId(item) {
  return item?.type === 'userMessage'
    ? item.clientId
    : item?.type === 'steeringUserMessage'
      ? item.clientUserMessageId
      : undefined;
}
export function originalInputText(item) {
  return (item?.type === 'steeringUserMessage' ? (item.input ?? []) : (item?.content ?? []))
    .filter((part) => typeof part.text === 'string')
    .map((part) => part.text)
    .join('');
}
export function originalProcessCount(turn) {
  const items = turn?.items;
  if (
    !Array.isArray(items) ||
    !items.every((item) => passive.has(item.type) || item.type === 'commandExecution')
  )
    return null;
  return items.filter((item) => item.type === 'commandExecution').length;
}
export function noChildProof(turn) {
  return Array.isArray(turn?.items) && turn.items.every((item) => passive.has(item.type))
    ? 0
    : null;
}

// Terminal Q04 facts only; baseline Turns (including the earlier Q03 child) are excluded.
// Completeness predicates match the existing independent Q02 witness historyComplete contract.
export function q04TerminalFacts(plan, state, turns, records) {
  const unknown = { stopOriginToolCount: null, childStarts: null };
  const history = state?.turnHistory?.history;
  const complete =
    state?.turnHistory?.kind === 'canonical'
      ? history?.isComplete === true &&
        Array.isArray(history.islands) &&
        history.entitiesByKey != null &&
        typeof history.entitiesByKey === 'object'
      : Array.isArray(state?.turns) &&
        state.turnsPagination?.hasLoadedOldest === true &&
        state.turnsPagination.olderCursor == null;
  if (plan.scenario !== 'q04' || !complete || !Array.isArray(turns)) return unknown;
  const id = (turn) => turn?.turnId ?? turn?.id;
  const ids = turns.map(id);
  const baseline = plan.baselineTurnIds;
  if (
    !Array.isArray(baseline) ||
    new Set(baseline).size !== baseline.length ||
    !ids.every((value) => typeof value === 'string' && value) ||
    new Set(ids).size !== ids.length ||
    !baseline.every((value) => ids.includes(value))
  )
    return unknown;
  const current = turns.filter((turn) => !baseline.includes(id(turn)));
  if (
    current.length !== 2 ||
    !current.every(
      (turn) =>
        Array.isArray(turn.items) &&
        turn.items.every((item) => item != null && typeof item.type === 'string'),
    )
  )
    return unknown;
  const bound = {};
  for (const name of ['origin', 'stopOrigin']) {
    const operation = records[name]?.id;
    if (typeof operation !== 'string' || !operation) return unknown;
    const matches = current.flatMap((turn) =>
      originalUsers(turn)
        .filter((item) => originalInputId(item) === operation)
        .map((item) => ({ turn, item })),
    );
    if (
      matches.length !== 1 ||
      hash(originalInputText(matches[0].item)) !== plan.slots[name]?.textSha256
    )
      return unknown;
    bound[name] = matches[0].turn;
  }
  if (
    records.origin.id === records.stopOrigin.id ||
    bound.origin === bound.stopOrigin ||
    bound.origin.status !== 'completed' ||
    bound.stopOrigin.status !== 'interrupted'
  )
    return unknown;
  const counts = current.map(originalProcessCount);
  const stopCount = counts[current.indexOf(bound.stopOrigin)];
  const stopCommand = boundedProcessWindow(bound.stopOrigin, plan.modelToolWorkingDirectory);
  return {
    stopOriginToolCount: stopCount === 1 && stopCommand === null ? null : stopCount,
    // Known passive/command-only full new Turns establish absence, never the planned budget.
    // Collab or any other unrecognized kind remains unknown rather than being guessed as zero.
    childStarts: counts.every((count) => count !== null) ? 0 : null,
  };
}

// This joins an independently observed W5 child, not a parent marker or a requested setting.
// The separately frozen scope plan contains the actual journal-generated native input ID.
export function verifyChildWitness(plan, record, parentTurn, proof, scope, pins, now = Date.now()) {
  const same = isDeepStrictEqual;
  const reject = (ok, message) => {
    if (!ok) throw Error('Independent child proof: ' + message);
  };
  reject(plan.scenario === 'q03' && plan.maxChildStarts === 1, 'combined intent required');
  const intent = JSON.parse(record.intent);
  reject(
    hash(record.intent) === record.intentSha256 &&
      intent.clientMessageId === record.id &&
      intent.hostId === plan.target.hostId &&
      intent.threadId === plan.threadId &&
      intent.text === plan.slots.q03.text &&
      hash(intent.text) === plan.slots.q03.textSha256 &&
      intent.attachmentIds?.length === 0,
    'original native journal/input mismatch',
  );
  reject(
    scope.version === 1 &&
      scope.taskId === plan.taskId &&
      scope.scope === 'observe-only' &&
      scope.modelExecutionAuthorized === false &&
      scope.controlHostId === plan.controlHostId &&
      scope.parentThreadId === plan.threadId &&
      same(scope.providerTarget, plan.target) &&
      scope.subjectOperationId === record.id &&
      scope.subjectInputSha256 === plan.slots.q03.textSha256 &&
      scope.expiresAtEpochMs <= plan.expiresAtEpochMs &&
      now < scope.expiresAtEpochMs &&
      scope.maxChildReads === 1 &&
      same(scope.excludedCodingThreadIds, plan.excludedCodingThreadIds) &&
      same(scope.expectedChild, { model: 'gpt-6.1-sol', effort: 'xhigh', finalLineCount: 96 }),
    'provider/control/QA/native ID/body/lease scope mismatch',
  );
  const freeze = plan.childWitness;
  const helperNames = [
    'q02-readonly-witness.mjs',
    'q02-witness-core.mjs',
    'q02-readonly-files.mjs',
    'q02-history-access.mjs',
  ];
  reject(
    proof.version === 2 &&
      freeze &&
      sha256Hex(pins.proofSha256) &&
      sha256Hex(pins.planSHA256) &&
      sha256Hex(freeze.artifactPinsSha256) &&
      sha256Hex(freeze.sourcePinsSha256) &&
      same(Object.keys(freeze.helperPins ?? {}).sort(), helperNames.sort()) &&
      Object.values(freeze.helperPins).every(sha256Hex) &&
      pins.planSHA256 === proof.planSHA256 &&
      same(scope.helperPins, freeze.helperPins) &&
      same(proof.helperPins, freeze.helperPins) &&
      proof.helperSHA256 === freeze.helperPins['q02-readonly-witness.mjs'] &&
      hash(JSON.stringify(scope.artifactPins)) === freeze.artifactPinsSha256 &&
      proof.artifactPinsSHA256 === freeze.artifactPinsSha256 &&
      hash(JSON.stringify(scope.sourcePins)) === freeze.sourcePinsSha256 &&
      same(scope.limits, { rolloutBytes: 8388608, outputBytes: 1048576 }),
    'source pins changed',
  );
  const at = Date.parse(proof.observedAt);
  reject(
    Number.isSafeInteger(at) && now - at >= 0 && now - at <= 30000,
    'stale/future independent observation',
  );
  const parentId = parentTurn?.turnId ?? parentTurn?.id;
  const inputMatches = originalUsers(parentTurn).filter(
    (item) =>
      originalInputId(item) === record.id &&
      hash(originalInputText(item)) === scope.subjectInputSha256,
  );
  reject(
    typeof parentId === 'string' &&
      inputMatches.length === 1 &&
      proof.parentThreadId === plan.threadId &&
      proof.controlHostId === plan.controlHostId &&
      same(proof.providerTarget, plan.target) &&
      proof.subjectOperationVerified === true &&
      proof.subjectTurnId === parentId &&
      proof.parent?.ownerVerified === true &&
      proof.parent.historyComplete === true &&
      proof.parent.parentThreadId === null &&
      proof.parent.forkedFromId === null &&
      proof.unknownParentItemKinds?.length === 0 &&
      proof.modelOrQueueWrites === 0,
    'original parent/operation/authority mismatch',
  );
  const child = proof.child;
  const history = child?.authority === 'official-history';
  reject(
    child?.controlAvailable === null &&
      (history
        ? child.ownerVerified === false && proof.historyOutputEvidenceComplete === true
        : child?.authority === 'original-desktop-ipc' && child.ownerVerified === true),
    'child source authority cannot substitute for live ownership/control',
  );
  if (history) {
    reject(
      same(scope.historyReadPolicy, {
        method: 'thread/read',
        includeTurns: true,
        maxChildren: 1,
        onlyAfterChildOwnerUnavailable: true,
        noOwnerRegistration: true,
      }) &&
        typeof freeze.historyReaderExecutable === 'string' &&
        freeze.historyReaderExecutable.startsWith('/') &&
        sha256Hex(freeze.historyReaderExecutableSHA256) &&
        scope.historyReaderExecutable === freeze.historyReaderExecutable &&
        scope.historyReaderExecutableSHA256 === freeze.historyReaderExecutableSHA256,
      'fixed frozen official read-only history policy/executable required',
    );
    reject(
      proof.metadataReaderInstantiated === true &&
        ['closed-confirmed', 'close-unconfirmed'].includes(proof.metadataReaderLifecycle) &&
        same(proof.metadataReaderTrace, [
          { method: 'connect', role: 'metadata-reader-only' },
          { method: 'thread/read', params: { threadId: child.id, includeTurns: true } },
          ...(proof.metadataReaderLifecycle === 'closed-confirmed'
            ? [{ method: 'closed-and-confirmed', role: 'metadata-reader-only' }]
            : []),
        ]),
      'unrecognized metadata reader method/scope/lifecycle',
    );
  } else
    reject(
      proof.metadataReaderInstantiated === false &&
        proof.metadataReaderLifecycle === 'not-created' &&
        same(proof.metadataReaderTrace, []),
      'live proof must not hide a history reader',
    );
  const claims = child.parentClaims;
  reject(
    Array.isArray(claims) &&
      claims.every((claim) => claim.valid === true && claim.value === plan.threadId) &&
      (claims.length
        ? child.parentThreadId === plan.threadId && child.graph?.backlinkVerified === true
        : child.parentThreadId === null && child.graph?.backlinkVerified === null),
    'reported parent conflict or invented backlink',
  );
  reject(
    proof.status === 'child-witness-complete' &&
      proof.q02WitnessComplete === true &&
      proof.childStarts === 1 &&
      child.historyComplete === true &&
      child.lastTurnStatus === 'completed' &&
      typeof child.lastTurnId === 'string' &&
      child.graph?.verified === true &&
      child.graph.edgeVerified === true &&
      child.graph.rootVerified === true &&
      child.graph.rootThreadId === plan.threadId &&
      same(child.graph.branchThreadIds, [plan.threadId, child.id]) &&
      same(proof.explicitChildIds, [child.id]) &&
      child.id !== plan.threadId &&
      !plan.excludedCodingThreadIds.includes(child.id),
    'one complete independently linked child required',
  );
  const spawn = (proof.parentLinkFacts ?? []).filter(
    (fact) =>
      fact.turnId === parentId &&
      fact.type === 'collabAgentToolCall' &&
      ['spawn', 'spawnAgent', 'spawn_agent'].includes(fact.tool) &&
      fact.status === 'completed' &&
      typeof fact.itemId === 'string' &&
      fact.childId === child.id,
  );
  const parentItems = parentTurn.items;
  const spawnTools = new Set(['spawn', 'spawnAgent', 'spawn_agent']);
  const permittedCollab = new Set([
    ...spawnTools,
    'wait',
    'waitAgent',
    'closeAgent',
    'close_agent',
  ]);
  const originalSpawns = Array.isArray(parentItems)
    ? parentItems.filter((item) => item.type === 'collabAgentToolCall' && spawnTools.has(item.tool))
    : [];
  reject(
    Array.isArray(parentItems) &&
      originalSpawns.length === 1 &&
      spawn.length === 1 &&
      originalSpawns[0].id === spawn[0].itemId &&
      originalSpawns[0].tool === spawn[0].tool &&
      originalSpawns[0].status === 'completed' &&
      parentItems.every(
        (item) =>
          passive.has(item.type) ||
          (item.type === 'collabAgentToolCall' &&
            typeof item.id === 'string' &&
            item.id &&
            permittedCollab.has(item.tool) &&
            same(item.receiverThreadIds, [child.id])) ||
          (item.type === 'subAgentActivity' &&
            typeof item.id === 'string' &&
            item.id &&
            item.agentThreadId === child.id &&
            ['started', 'interacted', 'completed', 'interrupted'].includes(item.kind)),
      ),
    'actual parent Turn permits one same-child spawn/wait/close only; zero command or unapproved tools required',
  );
  const reads = proof.readTrace;
  const historyReads = Array.isArray(reads)
    ? reads.filter((read) => read.method === 'CodexHistoryReader.read')
    : [];
  reject(
    spawn.length === 1 &&
      Array.isArray(reads) &&
      reads.length > 0 &&
      reads.every((read) =>
        ['follow', 'completeHistory'].includes(read.method)
          ? [plan.threadId, child.id].includes(read.id)
          : history &&
            read.method === 'CodexHistoryReader.read' &&
            read.rpc === 'thread/read' &&
            read.threadId === child.id &&
            read.includeTurns === true,
      ) &&
      historyReads.length === (history ? 1 : 0) &&
      reads.some((read) => read.method === 'follow' && read.id === child.id) &&
      reads.filter((read) => read.method === 'follow' && read.id === plan.threadId).length >=
        (history ? 3 : 2) &&
      ['follow', 'completeHistory'].includes(reads.at(-1).method) &&
      reads.at(-1).id === plan.threadId,
    'unrecognized child link/read scope',
  );
  reject(
    child.generationVerified === true &&
      child.generationEvidenceComplete === true &&
      child.outputEvidenceComplete === true &&
      child.completedSuccessfully === true &&
      child.generation?.conflict === false &&
      child.generation.model === 'gpt-6.1-sol' &&
      child.generation.effort === 'xhigh' &&
      child.turnContext?.turnId === child.lastTurnId &&
      child.turnContext.conflict === false &&
      child.turnContext.model === 'gpt-6.1-sol' &&
      child.turnContext.effort === 'xhigh' &&
      child.rollout?.available === true &&
      child.rollout.sessionId === child.id &&
      child.rollout.metadataParentCompatible === true &&
      child.rollout.exactFinalMatches === 1 &&
      child.rollout.sameTurnFinalMatched === true &&
      child.rollout.source?.authority ===
        'official exact-session JSONL; read-only consistent snapshot' &&
      sha256Hex(child.rollout.source.sha256) &&
      Number.isSafeInteger(child.rollout.source.bytes) &&
      child.rollout.source.bytes > 0 &&
      child.rollout.source.bytes <= scope.limits.rolloutBytes &&
      child.transcript?.omitted === false &&
      child.transcript.verification === 'terminal-output-and-jsonl-verified' &&
      child.final?.lines === 96 &&
      child.final.exactJSONLMatch === true &&
      typeof child.final.text === 'string' &&
      hash(child.final.text) === child.final.sha256,
    'actual original child Turn/JSONL/full text required',
  );
  const tools = child.toolFacts;
  reject(
    tools?.schemaVersion === 1 &&
      tools.turnId === child.lastTurnId &&
      tools.turnId === child.turnContext.turnId &&
      tools.authority === child.authority &&
      tools.turnContextMatched === true &&
      tools.executionTurnVerified === true &&
      tools.inheritedTurnExcluded === false &&
      tools.sourceComplete === true &&
      tools.terminal === true &&
      tools.noToolsVerified === true &&
      tools.activeToolCount === 0 &&
      tools.knownActiveToolCount === 0 &&
      same(tools.activeItems, []) &&
      same(tools.unknownItemTypes, []),
    'same actual child execution Turn must independently prove zero active tools; unknown is not zero',
  );
  return {
    childStarts: 1,
    childProofVerified: true,
    childProofSha256: pins.proofSha256,
    childScopePlanSha256: pins.planSHA256,
    childThreadId: child.id,
    childTurnId: child.lastTurnId,
    childProofObservedAt: proof.observedAt,
    childAuthority: child.authority,
    childOwnerVerified: child.ownerVerified,
    childControlAvailable: child.controlAvailable,
    childMetadataReaderLifecycle: proof.metadataReaderLifecycle,
    parentToolBudgetVerified: true,
    parentCommandExecutionCount: 0,
    parentUnapprovedToolCount: 0,
    childToolBudgetVerified: true,
    childActiveToolCount: tools.activeToolCount,
    childExecutionTurnId: tools.turnId,
    childProofAuthority:
      'independent W5 fresh parent owner + ' +
      child.authority +
      ' + exact official child Turn JSONL; provider mapping separately verified',
  };
}
export function boundedProcessWindow(turn, expectedWorkingDirectory) {
  if (originalProcessCount(turn) !== 1) return null;
  const item = turn.items.find((entry) => entry.type === 'commandExecution');
  const rendererCommandShape =
    item.command === boundedCommand
      ? 'direct-literal'
      : item.command === boundedZshEnvelope
        ? 'observed-zsh-lc-single-quote-literal'
        : null;
  // Two exact strings only. Never re-parse a shell, trim whitespace or use a substring match.
  if (rendererCommandShape === null || typeof item.id !== 'string' || !item.id) return null;
  if (
    typeof expectedWorkingDirectory !== 'string' ||
    !expectedWorkingDirectory.startsWith('/') ||
    item.cwd !== expectedWorkingDirectory
  )
    return null;
  const start = item.startedAtMs;
  if (!Number.isSafeInteger(start) || start <= 0) return null;
  const map = turn.commandExecutionStartedAtMsById;
  if (map != null && (!Object.hasOwn(map, item.id) || map[item.id] !== start)) return null;
  return {
    startedAtEpochMs: start,
    seconds: 40,
    commandSha256: hash(boundedCommand),
    rendererCommandSha256: hash(item.command),
    rendererCommandShape,
    processStarts: 1,
    cwd: item.cwd,
    originalCommandItemId: item.id,
    timestampSource: 'commandExecution.startedAtMs',
    // The desktop projection does not identify terminal API polls. Never infer their session from text.
    terminalApiPollAssociation: null,
  };
}

export function runtimeFingerprintReference(reference, observedExecutableSha256) {
  return {
    desktopRuntimeFingerprintReference: reference,
    observedDesktopExecutableSha256: observedExecutableSha256,
    desktopRuntimeFingerprint: null,
    desktopRuntimeFingerprintVerified: false,
    desktopRuntimeFingerprintSource:
      'controller-frozen reference plus on-disk file read; running-process binding unverified',
  };
}

const sha256Hex = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const demand = (ok, message) => {
  if (!ok) throw Error(message);
};
export function fingerprintFields(
  plan,
  observedExecutableSha256,
  environment = null,
  hostVerified = false,
) {
  demand(
    sha256Hex(plan.desktopExecutableSha256) && sha256Hex(plan.desktopRuntimeFingerprint),
    'Two explicit SHA256 references required',
  );
  demand(
    sha256Hex(observedExecutableSha256) &&
      observedExecutableSha256 === plan.desktopExecutableSha256,
    'Observed disk SHA differs from executable reference',
  );
  const result = runtimeFingerprintReference(
    plan.desktopRuntimeFingerprint,
    observedExecutableSha256,
  );
  if (!environment || !sha256Hex(environment.fingerprint)) return result;
  return {
    ...result,
    desktopRuntimeFingerprint: environment.fingerprint,
    desktopRuntimeFingerprintVerified:
      hostVerified === true &&
      environment.processAndInstallVerified === true &&
      environment.originalOwnerVerified === true &&
      environment.fingerprint === plan.desktopRuntimeFingerprint,
    desktopRuntimeFingerprintSource:
      environment.source + ' + independently checked local mapping and authenticated Host GET',
    runtimeEnvironmentEvidence: environment.identity,
    runtimeObservedAtEpochMs: environment.observedAtEpochMs,
  };
}
export function verifyLocalMapping(plan, mapping, host, now = Date.now()) {
  demand(
    mapping.version === 1 && mapping.sourceAuthority === 'independent-local-desktop-and-host',
    'Independent local mapping source required',
  );
  demand(
    mapping.controlHostId === plan.controlHostId && mapping.threadId === plan.threadId,
    'Control Host/original business thread mapping mismatch',
  );
  demand(
    isDeepStrictEqual(mapping.actualTarget, plan.target),
    'Actual Provider target differs from frozen scope',
  );
  demand(
    mapping.expiresAtEpochMs >= now && mapping.expiresAtEpochMs <= plan.expiresAtEpochMs,
    'Mapping lease expired/out of bounds',
  );
  const environment = mapping.environment;
  demand(
    environment?.processAndInstallVerified === true &&
      environment.originalOwnerVerified === true &&
      environment.threadId === plan.threadId,
    'Original local environment evidence required',
  );
  demand(
    environment.identity?.hostId === plan.target.hostId &&
      environment.identity.runtimeId === plan.target.runtimeId &&
      environment.identity.executable === plan.desktopExecutable &&
      environment.identity.executableSha256 === plan.desktopExecutableSha256,
    'Observed installation and Provider runtime mapping mismatch',
  );
  demand(
    runtimeIdentityFingerprint(environment.identity) === environment.fingerprint &&
      environment.fingerprint === plan.desktopRuntimeFingerprint,
    'Independent mapping runtime fingerprint mismatch',
  );
  demand(
    host?.hostId === plan.target.hostId &&
      host.workspaceStatus === 200 &&
      host.runtimeMode === plan.target.runtimeId &&
      host.endpoint === mapping.endpoint &&
      plan.allowedEndpoints.includes(host.endpoint) &&
      now - Date.parse(host.observedAt) >= 0 &&
      now - Date.parse(host.observedAt) <= 30000,
    'Fresh actual Provider Host proof required',
  );
  return true;
}
