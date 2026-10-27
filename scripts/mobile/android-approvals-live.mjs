import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
import { startQuestionColdHost } from './android-question-cold-host.mjs';
import { assertExactApprovalCommand } from './android-approval-command-guard.mjs';

const preflightOnly = process.env.ANDROID_APPROVAL_PREFLIGHT_ONLY === '1';
const fullDecisions = process.env.ANDROID_APPROVAL_FULL_DECISIONS === '1';
const lateCallback = process.env.ANDROID_APPROVAL_LATE_CALLBACK === '1';
const externalFirst = process.env.ANDROID_APPROVAL_EXTERNAL_FIRST === '1';
const staleConflict = process.env.ANDROID_APPROVAL_STALE_CONFLICT === '1';
const coldHostRestart = process.env.ANDROID_APPROVAL_COLD_HOST_RESTART === '1';
const viewportOrientation = process.env.ANDROID_APPROVAL_VIEWPORT ?? '';
assert(['', 'portrait', 'landscape'].includes(viewportOrientation));
const viewportTheme = process.env.ANDROID_APPROVAL_THEME ?? '';
const nativeHit = process.env.ANDROID_APPROVAL_NATIVE_HIT === '1';
assert(!nativeHit || viewportOrientation);
assert(['', 'light', 'dark'].includes(viewportTheme));
assert(!viewportTheme || viewportOrientation);
assert(!coldHostRestart || staleConflict);
assert(!staleConflict || (externalFirst && !lateCallback));
const replacementMode = process.env.ANDROID_APPROVAL_CALLBACK_REPLACEMENT ?? '';
assert(['', 'success', 'failure'].includes(replacementMode));
assert(!replacementMode || (lateCallback && !externalFirst));
assert(!lateCallback || !externalFirst);
assert(
  !fullDecisions ||
    (!replacementMode && !lateCallback && !externalFirst && !staleConflict && !coldHostRestart),
);
assert(preflightOnly || process.env.ANDROID_APPROVAL_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_APPROVAL_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_APPROVAL_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_APPROVAL_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(
  output,
  (externalFirst ? 'approval-invalidation-live-' : 'approval-model-live-') +
    Date.now() +
    '-' +
    randomBytes(6).toString('hex'),
);
await mkdir(output, { recursive: true, mode: 0o700 });
await mkdir(root, { mode: 0o700 });
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb'),
  pkg = 'com.easyai.agenthub.android.acceptance';
const execute = promisify(execFile);
const android = async (...args) =>
  (
    await execute(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 30000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout.trim();
assert.equal(await android('get-state'), 'device');
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
// Session permission is deliberately last so it cannot suppress a later approval.
const caseKinds = fullDecisions
  ? ['DENY', 'ALLOW', 'CANCEL', 'SESSION']
  : replacementMode
    ? ['REPLACEMENT']
    : ['DENY', 'ALLOW'];
const cases = caseKinds.map((kind, index) => {
  const toolMarker =
    'ANDROID_P4_APPROVAL_' + nonce + '_' + (replacementMode ? 'ALLOW' : kind) + '_TOOL';
  const secondToolMarker = 'ANDROID_P4_APPROVAL_' + nonce + '_DENY_TOOL';
  const doneMarker = 'ANDROID_P4_APPROVAL_' + nonce + '_' + kind + '_DONE';
  return {
    index,
    kind,
    ...(fullDecisions
      ? {
          requiredDecision: {
            DENY: 'decline',
            ALLOW: 'accept',
            CANCEL: 'cancel',
            SESSION: 'acceptForSession',
          }[kind],
        }
      : {}),
    toolMarker,
    secondToolMarker,
    doneMarker,
    text: replacementMode
      ? `安卓原owner同Turn新审批验收：仅顺序调用两次exec_command，第一条严格为printf '${toolMarker}'，第二条严格为printf '${secondToolMarker}'。两次都必须显式设置sandbox_permissions=require_escalated，justification分别为验证原审批第一次允许和验证新审批第二次拒绝或取消。等待第一项允许并完成后才请求第二项；第一项若拒绝或取消，不再执行第二项。第二项若拒绝或取消不得重试，不得改用其他工具或命令。所有命令仅打印各自标识，不读写文件，不访问网络，不创建其他聊天或联系Agent。第二项处理后最终仅回复${doneMarker}。`
      : `安卓专用真实工具审批验收：仅调用一次 exec_command 执行 printf '${toolMarker}'，必须显式设置 sandbox_permissions=require_escalated，justification=验证安卓原请求${kind}审批。命令仅打印上述标识，不读写文件、不派生任务、不联系其他聊天或Agent。等待用户审批；若拒绝或取消，不得重试、不改用其他工具；若原任务继续，最终仅回复 ${doneMarker}。`,
  };
});
await writeFile(
  join(root, 'immutable-cases.json'),
  JSON.stringify({ nonce, projectId, projectRoot, cases }, null, 2),
  { mode: 0o600 },
);
const fingerprint = async () => {
  const files = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'apps/host-agent',
      'packages/codex-adapter',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile/android-approvals-live.mjs',
      'scripts/mobile/android-approval-command-guard.mjs',
      'scripts/mobile/android-question-cold-host.mjs',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const hash = createHash('sha256');
  for (const file of new Set(files))
    hash
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return hash.digest('hex');
};
const report = {
  phase: nativeHit
    ? 'P4-4o.17'
    : viewportTheme
      ? 'P4-4o.16'
      : viewportOrientation
        ? 'P4-4o.15'
        : coldHostRestart
          ? 'P4-4o.13'
          : staleConflict
            ? 'P4-4o.11'
            : replacementMode
              ? 'P4-4o.10'
              : lateCallback
                ? 'P4-4o.6'
                : externalFirst
                  ? 'P4-4f.11'
                  : 'P4-4d',
  externalFirst,
  staleConflict,
  coldHostRestart,
  viewportOrientation,
  viewportTheme,
  nativeHit,
  hostProcesses: [],
  hostProcessExits: [],
  staleApprovalAttempts: [],
  replacementMode,
  replacementCallbacks: [],
  fullDecisions,
  scope: fullDecisions
    ? 'Physical original-owner four declared decisions; cancellation before session permission; exact harmless command outcomes and preserved drafts, no unknown replay'
    : staleConflict
      ? 'Real second named HTTP client resolves the exact original owner approval; one stale native POST gets actual Host409, visible error and explicit refresh recover, then a fresh native approval succeeds; no desktop-UI or natural-network claim'
      : replacementMode
        ? 'Exact original-owner two approvals in one Turn; first harmless print allowed, second print declined/cancelled only by declared decisions; controlled old success/failure and newer pre-forward wait, not natural-network/desktop-UI proof'
        : externalFirst
          ? 'A second named HTTP client answers the exact original request once; native authoritative refresh invalidates its retained action; fresh native approval still works. Real Electron owner, not desktop UI or network-path proof.'
          : 'Physical native original-owner tool approval deny/cancel and allow, real command outcomes and preserved composer; USB loopback, not network path proof',
  lateCallback,
  callbackScope: replacementMode
    ? 'Old actual response held; new immutable approval POST held before forwarding; unchanged old success or injected HTTP503 after actual Host success cannot release the newer wait'
    : lateCallback
      ? 'Controlled delay of unchanged actual approval success response; local A/B/A selection and newer error/draft protection; not natural-network or desktop-UI proof'
      : null,
  status: 'running',
  preflightOnly,
  nonce,
  hostId,
  serial,
  projectId,
  projectRoot,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  claims: [],
  posts: [],
  native: [],
  scenarios: [],
  rejectedReads: [],
  rejectedPosts: [],
  cleanupReadCancellations: [],
  newRealModelInputsConfirmed: preflightOnly ? 0 : null,
  externalDecisionPosts: [],
  approvalClaims: [],
  approvalPosts: [],
  consumedOriginalInputs: [],
  unresolvedOriginalCases: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original approval read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, endpoint, fixture;
let conflictReadsHeld = false,
  releaseConflictReads;
const conflictReadGate = new Promise((done) => {
  releaseConflictReads = done;
});
async function holdConflictRead(req) {
  if (req.method !== 'GET' || !staleConflict || !conflictReadsHeld) return;
  let timer;
  try {
    await Promise.race([
      conflictReadGate,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(Error('Bounded stale-view read hold expired')), 30000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}
let restartGate;
async function startCold(port = 0) {
  const instance = await startQuestionColdHost({
    root,
    state,
    nonce,
    hostId,
    index: report.hostProcesses.length,
    port,
  });
  report.hostProcesses.push({ pid: instance.pid, port: instance.server.address().port });
  return instance;
}
async function stopCold(instance) {
  const exit = await instance.close();
  if (exit) report.hostProcessExits.push(exit);
  await save();
}
async function restartConflictHost() {
  assert(
    coldHostRestart &&
      !report.coldRestart &&
      report.staleApprovalAttempts[0]?.actualHostStatus === 409,
  );
  const originalApproval = report.externalDecisionPosts[0];
  assert(originalApproval.outcome === 'accepted' && originalApproval.decision === 'cancel');
  const old = service,
    port = old.server.address().port,
    oldToken = token;
  const before = await old.session.open(report.threadId);
  assert.equal(before.runtime.approvals.length, 0);
  let release;
  restartGate = new Promise((done) => {
    release = done;
  });
  conflictReadsHeld = false;
  releaseConflictReads();
  try {
    await wait(
      () => [...upstreams].every((entry) => !entry.forwarding),
      'Owned reads drained before cold exit',
    );
    await stopCold(old);
    service = await startCold(port);
    assert.notEqual(service.pid, old.pid);
    token = service.token;
    endpoint = service.endpoint;
    assert.equal(token, oldToken);
    const after = await wait(async () => {
      const current = await service.session.open(report.threadId);
      return current.runtime?.canControl ? current : null;
    }, 'Cold Host original owner control ready');
    assert.equal(after.runtime.approvals.length, 0);
    await witness.ipc.follow(report.threadId, 5000, true);
    const actualOwner = witness.ipc.state(report.threadId),
      turns = desktopTurns(actualOwner);
    assert(witness.ipc.hasOwner(report.threadId) && actualOwner.hostId === 'local');
    assert.equal(turns.length, 1);
    assert.equal(turns[0].turnId, originalApproval.turnId);
    assert.equal(turns[0].status, 'interrupted');
    assert(
      turns[0].items
        .filter((i) => i.type === 'commandExecution')
        .every(
          (i) =>
            i.status !== 'completed' && i.exitCode == null && !(i.aggregatedOutput ?? '').trim(),
        ),
    );
    report.coldRestart = {
      oldPid: old.pid,
      newPid: service.pid,
      oldExited: true,
      port,
      hostId,
      tokenUnchanged: true,
      originalApproval,
      originalTurnInterrupted: true,
      oldApprovalAbsent: true,
      canControl: true,
      noReplay: true,
    };
    await save();
  } finally {
    release();
    restartGate = undefined;
  }
}
const replacementSlots = new Map();
let releaseNewerPost;
const newerPostGate = new Promise((done) => {
  releaseNewerPost = done;
});
let releaseCallback;
const callbackRelease = new Promise((done) => {
  releaseCallback = done;
});
const reverses = [],
  claims = new Map(),
  submitted = new Map(),
  submittedMethods = new Set(),
  upstreams = new Set();
const clean = (value) =>
  String(value)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const body = async (req) => {
  const chunks = [];
  let size = 0;
  for await (const part of req) {
    size += part.length;
    assert(size < 180000);
    chunks.push(part);
  }
  return JSON.parse(Buffer.concat(chunks).toString());
};
const listen = async (server) => {
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  return server.address().port;
};
const wait = async (fn, label) => {
  const until = Date.now() + 25000;
  let last;
  do {
    try {
      const value = await fn();
      if (value) return value;
    } catch (e) {
      last = e;
    }
    await new Promise((done) => setTimeout(done, 500));
  } while (Date.now() < until);
  throw Error(label + ': ' + (last?.message ?? 'unavailable'));
};
const originalText = (index) => cases[index].text;
const approvalLeases = new Map();
async function original(index, value, completed = false) {
  const sent = submitted.get(index);
  assert(sent && sent.operationId === value.record.id);
  const clientId = index === 0 ? 'first-' + sent.operationId : sent.operationId;
  const owner = await wait(async () => {
    await witness.ipc.follow(value.threadId, 5000, true);
    const state = witness.ipc.state(value.threadId);
    return state &&
      witness.ipc.hasOwner(value.threadId) &&
      desktopTurns(state).some((t) =>
        t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
      )
      ? state
      : null;
  }, 'Exact original tool approval owner');
  assert.equal(owner.id, value.threadId);
  assert.equal(owner.hostId, 'local');
  assert.equal(await realpath(owner.cwd), projectRoot);
  const turns = desktopTurns(owner).filter((t) =>
    t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
  );
  assert.equal(turns.length, 1);
  const turn = turns[0];
  assert.equal(turn.params.collaborationMode.mode, 'default');
  assert.equal(turn.params.model ?? turn.params.collaborationMode.settings?.model, fixture.model);
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode.settings?.reasoning_effort,
    'xhigh',
  );
  assert.equal(turn.params.serviceTier, 'priority');
  assert.equal(turn.params.sandboxPolicy.type, 'readOnly');
  assert.equal(turn.params.approvalPolicy, 'on-request');
  const canonical = await witness.reader.read('thread/read', {
    threadId: value.threadId,
    includeTurns: true,
  });
  assert.equal(await realpath(canonical.thread.cwd), projectRoot);
  assert.equal(
    canonical.thread.turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage')
      .length,
    index + 1,
  );
  assert.equal(sha(value.record.intent), value.record.intentSha256);
  assert.deepEqual(JSON.parse(value.record.intent), sent.body);
  if (index === 0) report.threadId = value.threadId;
  else assert.equal(value.threadId, report.threadId);
  if (!report.consumedOriginalInputs.some((r) => r.index === index))
    report.consumedOriginalInputs.push({
      index,
      threadId: value.threadId,
      operationId: sent.operationId,
      clientId,
      turnId: turn.turnId,
      canonicalTotalInputs: index + 1,
    });
  report.newRealModelInputsConfirmed = report.consumedOriginalInputs.length;
  const runtime = (await service.session.open(value.threadId)).runtime;
  if (!completed) {
    assert.equal(turn.status, 'inProgress');
    const requests = owner.requests.filter((r) => r.method.includes('Approval'));
    assert.equal(requests.length, 1);
    assert.equal(runtime.approvals.length, 1);
    const a = runtime.approvals[0];
    assert.equal(a.kind, 'approval');
    assert.equal(a.turnId, turn.turnId);
    const commandScope = assertExactApprovalCommand({
      request: requests[0],
      approval: a,
      threadId: value.threadId,
      turnId: turn.turnId,
      projectRoot,
      toolMarker: cases[index].toolMarker,
    });
    const known = {
      id: a.id,
      threadId: a.threadId,
      turnId: a.turnId,
      kind: a.kind,
      title: a.title,
      detail: a.detail,
      decisions: a.decisions,
    };
    assert.deepEqual(value.approval, known);
    const choice = (
      cases[index].requiredDecision
        ? [cases[index].requiredDecision]
        : index === 0 && !replacementMode
          ? ['decline', 'deny', 'cancel']
          : ['accept', 'approve']
    ).find((c) => a.decisions.includes(c));
    assert(choice);
    assert.equal(value.choice, choice);
    assert(!approvalLeases.has(index));
    const lease = {
      index,
      threadId: value.threadId,
      turnId: turn.turnId,
      approvalId: a.id,
      decision: choice,
      commandScope,
    };
    approvalLeases.set(index, lease);
    report.approvalClaims.push(lease);
    if (choice === 'cancel' && !fullDecisions)
      report.denialLimitation =
        'Original owner offers cancellation rather than decline-and-continue; actual interruption is recorded separately.';
  } else {
    const lease = approvalLeases.get(index);
    assert(lease);
    assert.equal(value.choice, lease.decision);
    assert.equal(value.approval.id, lease.approvalId);
    assert.equal(turn.turnId, lease.turnId);
    assert.equal(runtime.approvals.length, 0);
    assert.equal(owner.requests.filter((r) => r.method.includes('Approval')).length, 0);
    const actions = report.approvalPosts.filter((r) => r.index === index);
    assert.equal(actions.length, replacementMode ? 2 : 1);
    assert.equal(actions[0].approvalId, lease.approvalId);
    const cancelled =
      lease.decision === 'cancel' ||
      (replacementMode && approvalLeases.get('replacement')?.decision === 'cancel');
    assert.equal(turn.status, cancelled ? 'interrupted' : 'completed');
    if (!cancelled)
      assert(
        turn.items.some(
          (i) => i.type === 'agentMessage' && i.text.trim() === cases[index].doneMarker,
        ),
      );
    const commands = turn.items.filter(
      (i) => i.type === 'commandExecution' && i.command.includes(cases[index].toolMarker),
    );
    if (['accept', 'approve', 'acceptForSession'].includes(lease.decision))
      assert(
        commands.some(
          (i) =>
            i.status === 'completed' &&
            i.exitCode === 0 &&
            i.aggregatedOutput.trim() === cases[index].toolMarker,
        ),
        'The allowed original command must actually execute',
      );
    else
      assert(
        !commands.some((i) => i.status === 'completed' && i.exitCode === 0),
        'The denied/cancelled original command must never succeed',
      );
    if (replacementMode) {
      const second = approvalLeases.get('replacement');
      assert(second && ['decline', 'deny', 'cancel'].includes(second.decision));
      assert.equal(second.turnId, turn.turnId);
      assert.notEqual(second.approvalId, lease.approvalId);
      assert.equal(actions[1].approvalId, second.approvalId);
      const denied = turn.items.filter(
        (i) => i.type === 'commandExecution' && i.command.includes(cases[index].secondToolMarker),
      );
      assert(
        !denied.some((i) => i.status === 'completed' && i.exitCode === 0),
        'The denied second command must never succeed',
      );
      assert(
        denied.every((i) => !(i.aggregatedOutput ?? '').trim() && i.exitCode == null),
        'The unapproved second command has no execution output or exit code',
      );
      const all = turn.items.filter((i) => i.type === 'commandExecution');
      assert(
        all.every(
          (i) =>
            i.command.includes(cases[index].toolMarker) ||
            i.command.includes(cases[index].secondToolMarker),
        ),
        'Only the two authorized harmless print commands',
      );
      report.replacementCommandOutcome = {
        firstExecuted: true,
        secondNeverSucceeded: true,
        originalTurnId: turn.turnId,
        secondDecision: second.decision,
        turnStatus: turn.status,
        secondOutputEmpty: true,
      };
    }
    if (index > 0) {
      const receipt = runtime.receipts.find((r) => r.clientMessageId === clientId);
      assert(receipt);
      assert.equal(receipt.text, cases[index].text);
    }
    report.scenarios.push({
      ...lease,
      operationId: sent.operationId,
      clientId,
      canonicalTotalInputs: index + 1,
      textSha256: sha(cases[index].text),
      commands: commands.map((i) => ({ status: i.status, exitCode: i.exitCode })),
      nativePid: value.pid,
    });
  }
  await writeFile(
    join(root, `case-${index}-${completed ? 'after' : 'before'}-original-owner.json`),
    JSON.stringify(owner, null, 2),
    { mode: 0o600 },
  );
  await writeFile(
    join(root, `case-${index}-${completed ? 'after' : 'before'}-native-record.json`),
    JSON.stringify(value.record, null, 2),
    { mode: 0o600 },
  );
  await save();
  return { ok: true };
}

async function answerOriginalExternally(index) {
  assert(
    externalFirst && index === 0 && !preflightOnly && report.externalDecisionPosts.length === 0,
  );
  const lease = approvalLeases.get(index);
  assert(lease && ['decline', 'deny', 'cancel'].includes(lease.decision));
  const value = {
    hostId,
    threadId: lease.threadId,
    turnId: lease.turnId,
    approvalId: lease.approvalId,
    decision: lease.decision,
    answers: {},
  };
  // Persist the immutable once-only intention before the write; unknown outcomes are never retried.
  const operation = {
    ...lease,
    actor: 'second-named-http-client',
    bodySha256: sha(JSON.stringify(value)),
    attempted: true,
    outcome: 'unknown',
  };
  report.externalDecisionPosts.push(operation);
  await save();
  const response = await fetch(endpoint + '/v1/approvals', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
    redirect: 'error',
    signal: AbortSignal.timeout(30000),
  });
  const result = await response.json();
  operation.httpStatus = response.status;
  assert.equal(response.status, 200);
  assert.equal(result.ok, true);
  operation.outcome = 'accepted';
  report.approvalPosts.push(operation);
  await save();
}

try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const bridge = join(state, 'bridge.json');
  await writeFile(bridge, JSON.stringify({ hostId, desktopVersion: witness.version }), {
    mode: 0o600,
  });
  await writeFile(
    join(state, 'runtime.json'),
    JSON.stringify({
      hostId,
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
    }),
    { mode: 0o600 },
  );
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android real tool approval DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = coldHostRestart ? await startCold() : await startRuntimeServer();
  if (coldHostRestart)
    await wait(
      async () => (await service.session.workspace()).capabilities.includes('desktop:creations'),
      'Fresh actual Host workspace ready',
    );
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  endpoint = 'http://127.0.0.1:' + service.server.address().port;
  const workspace = await service.session.workspace(),
    project = workspace.projects.find((p) => p.id === projectId);
  assert(
    project &&
      (await Promise.all(project.roots.map((path) => realpath(path)))).includes(projectRoot),
  );
  assert(workspace.capabilities.includes('desktop:creations'));
  const model = (await service.session.models()).models.find((m) => m.isDefault);
  assert(
    model && model.supportedEfforts.includes('xhigh') && model.supportedSpeeds.includes('fast'),
  );
  const gets = new Set([
    '/healthz',
    '/v1/workspace',
    '/v1/history',
    '/v1/models',
    '/v1/events',
    '/v1/receipts',
    '/v1/replies',
    '/v1/replies/status',
    '/v1/desktop/creation-receipts',
    '/v1/desktop/creation-status',
    '/v1/threads/fork-options',
    '/v1/desktop/open-status',
    '/v1/desktop/creations',
    '/v1/commands/status',
  ]);
  const posts = new Set([
    '/v1/desktop/creations',
    '/v1/desktop/creations/submit',
    '/v1/replies',
    '/v1/desktop/open',
    '/v1/approvals',
  ]);
  proxy = createServer((req, res) => {
    const entry = { controller: new AbortController(), cleanupAbort: false, forwarding: false };
    upstreams.add(entry);
    void (async () => {
      assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + token);
      const url = new URL(req.url, endpoint);
      assert(
        req.method === 'GET'
          ? gets.has(url.pathname)
          : req.method === 'POST' && posts.has(url.pathname),
      );
      if (restartGate) {
        assert.equal(req.method, 'GET');
        await restartGate;
      }
      await holdConflictRead(req);
      let bytes, heldCandidate, staleCandidate;
      if (req.method === 'POST') {
        assert(!preflightOnly);
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const key =
          url.pathname +
          ':' +
          (value.clientMessageId ??
            value.clientOperationId ??
            value.approvalId + ':' + value.turnId);
        assert(!submittedMethods.has(key), 'No original write replay');
        submittedMethods.add(key);
        if (Object.hasOwn(value, 'text')) {
          const index = fixture.activeCase;
          assert(claims.has(index) && !submitted.has(index));
          assert.equal(value.text, originalText(index));
          assert.equal(value.model, model.id);
          assert.equal(value.effort, 'xhigh');
          assert.equal(value.speed, 'fast');
          assert.equal(value.permissionMode, 'read-only');
          assert.equal(value.collaborationMode, 'default');
          if (index === 0) {
            assert.equal(url.pathname, '/v1/desktop/creations');
            assert.equal(value.projectId, projectId);
            assert.deepEqual(value.attachments, []);
          } else {
            assert.equal(url.pathname, '/v1/replies');
            assert.equal(value.threadId, report.threadId);
            assert(!Object.hasOwn(value, 'expectedPlanId'));
            assert.deepEqual(value.attachmentIds, []);
          }
          submitted.set(index, {
            operationId: value.clientOperationId ?? value.clientMessageId,
            body: value,
          });
        }
        if (url.pathname === '/v1/approvals') {
          if (lateCallback && fixture.activeCase === 0)
            heldCandidate = {
              approvalId: value.approvalId,
              turnId: value.turnId,
              threadId: value.threadId,
            };
          const index = fixture.activeCase,
            lease = replacementMode
              ? [...approvalLeases.values()].find((l) => l.approvalId === value.approvalId)
              : approvalLeases.get(index);
          assert(
            !externalFirst || index !== 0 || staleConflict,
            'An invalidated original approval must never be sent again',
          );
          assert(lease);
          if (staleConflict && index === 0) {
            assert(conflictReadsHeld && report.externalDecisionPosts[0]?.outcome === 'accepted');
            assert.equal(report.staleApprovalAttempts.length, 0);
            staleCandidate = {
              ...lease,
              actor: 'stale-native-ui',
              attempted: true,
              bodySha256: sha(JSON.stringify(value)),
            };
            report.staleApprovalAttempts.push(staleCandidate);
          } else {
            assert(
              !report.approvalPosts.some((p) =>
                replacementMode ? p.approvalId === lease.approvalId : p.index === index,
              ),
            );
          }
          for (const key of ['threadId', 'turnId', 'approvalId', 'decision'])
            assert.equal(value[key], lease[key]);
          assert.deepEqual(value.answers, {});
          if (!staleCandidate)
            report.approvalPosts.push({ ...lease, bodySha256: sha(JSON.stringify(value)) });
        }
        if (url.pathname === '/v1/desktop/creations/submit')
          assert.equal(value.clientOperationId, submitted.get(0)?.operationId);
        if (url.pathname === '/v1/desktop/open') assert.equal(value.threadId, report.threadId);
        report.posts.push({
          path: url.pathname,
          index: fixture.activeCase,
          operationId: value.clientMessageId ?? value.clientOperationId,
          bodySha256: sha(JSON.stringify(value)),
        });
        await save();
        bytes = JSON.stringify(value);
      }
      if (replacementMode && heldCandidate && report.approvalPosts.length === 2) {
        assert(!report.newerPostHeld);
        report.newerPostHeld = {
          ...heldCandidate,
          bodySha256: sha(bytes),
          heldAt: Date.now(),
          forwarded: false,
        };
        await save();
        let timer;
        try {
          await Promise.race([
            newerPostGate,
            new Promise((_, reject) => {
              timer = setTimeout(
                () => reject(Error('Newer request bounded pre-forward hold expired')),
                30000,
              );
            }),
          ]);
        } finally {
          clearTimeout(timer);
        }
        assert(
          report.newerPostHeld.allowed,
          'Failed verification cannot forward an unaccepted newer input',
        );
        report.newerPostHeld.forwarded = true;
        report.newerPostHeld.forwardedAt = Date.now();
        await save();
      }
      if (restartGate) {
        assert.equal(req.method, 'GET');
        await restartGate;
      }
      entry.forwarding = true;
      const timeout = setTimeout(
        () => entry.controller.abort(new Error('upstream-read-deadline')),
        30000,
      );
      try {
        const response = await fetch(endpoint + req.url, {
          method: req.method,
          headers: {
            Authorization: 'Bearer ' + token,
            ...(bytes ? { 'Content-Type': 'application/json' } : {}),
          },
          ...(bytes ? { body: bytes } : {}),
          redirect: 'error',
          signal: entry.controller.signal,
        });
        const data = Buffer.from(await response.arrayBuffer());
        entry.forwarding = false;
        if (staleCandidate) {
          assert.equal(response.status, 409);
          assert.equal(JSON.parse(data.toString()).error, '审批已处理或已过期');
          Object.assign(staleCandidate, {
            actualHostStatus: response.status,
            responseSha256: sha(data),
            error: JSON.parse(data.toString()).error,
          });
          await save();
        }
        await holdConflictRead(req);
        if (heldCandidate && replacementMode) {
          assert.equal(response.status, 200);
          assert(!replacementSlots.has(heldCandidate.approvalId));
          let release;
          const gate = new Promise((done) => {
            release = done;
          });
          const observation = {
            ...heldCandidate,
            ordinal: report.replacementCallbacks.length,
            originalStatus: response.status,
            originalSha256: sha(data),
            heldAt: Date.now(),
          };
          assert(observation.ordinal < 2);
          const slot = { gate, release, observation };
          replacementSlots.set(heldCandidate.approvalId, slot);
          report.replacementCallbacks.push(observation);
          await save();
          if (observation.ordinal === 1) {
            assert(report.newerPostHeld?.forwarded && report.replacementCallbacks[0].deliveredAt);
            slot.release();
          }
          let timer;
          try {
            await Promise.race([
              gate,
              new Promise((_, reject) => {
                timer = setTimeout(
                  () => reject(Error('Replacement callback bounded hold expired')),
                  30000,
                );
              }),
            ]);
          } finally {
            clearTimeout(timer);
          }
          const injected = observation.ordinal === 0 && replacementMode === 'failure';
          const delivered = injected
            ? Buffer.from(JSON.stringify({ error: 'Controlled old response failure ' + nonce }))
            : data;
          const status = injected ? 503 : response.status;
          res.writeHead(status, { 'Content-Type': 'application/json' });
          res.end(delivered);
          Object.assign(observation, {
            deliveredAt: Date.now(),
            deliveredStatus: status,
            deliveredSha256: sha(delivered),
            controlledFailure: injected,
          });
          await save();
          return;
        }
        if (heldCandidate) {
          assert.equal(response.status, 200);
          assert(!report.callbackResponseHeld);
          report.callbackResponseHeld = {
            ...heldCandidate,
            status: response.status,
            sha256: sha(data),
            receivedAt: Date.now(),
          };
          await save();
          let heldTimer;
          try {
            await Promise.race([
              callbackRelease,
              new Promise((_, reject) => {
                heldTimer = setTimeout(
                  () => reject(Error('Owned callback release deadline')),
                  20000,
                );
              }),
            ]);
          } finally {
            clearTimeout(heldTimer);
          }
        }
        res.writeHead(response.status, {
          'Content-Type': response.headers.get('content-type') ?? 'application/json',
        });
        res.end(data);
        if (heldCandidate) {
          report.callbackResponseDelivered = { at: Date.now(), sha256: sha(data) };
          await save();
        }
      } finally {
        entry.forwarding = false;
        clearTimeout(timeout);
      }
    })()
      .catch((e) => {
        const error = clean(e.message);
        if (entry.cleanupAbort && req.method === 'GET')
          report.cleanupReadCancellations.push({ path: req.url, error });
        else
          (req.method === 'POST' ? report.rejectedPosts : report.rejectedReads).push({
            path: req.url,
            error,
          });
        void save();
        if (!res.destroyed && !res.headersSent) json(res, { error }, 500);
        else res.destroy();
      })
      .finally(() => upstreams.delete(entry));
  });
  const proxyPort = await listen(proxy);
  control = createServer((req, res) => {
    void (async () => {
      assert(
        req.method === 'POST' &&
          !req.headers.origin &&
          req.headers.authorization === 'Bearer ' + controlKey,
      );
      const value = await body(req);
      assert.equal(value.nonce, nonce);
      if (req.url === '/preflight-complete') {
        assert(preflightOnly && report.posts.length === 0);
        report.preflightNative = value;
        await save();
        return json(res, { ok: true });
      }
      assert(!preflightOnly);
      if (req.url === '/cold-approval-native' || req.url === '/cold-new-approval') {
        assert(
          coldHostRestart &&
            report.coldRestart &&
            value.threadId === report.threadId &&
            value.draftProtected &&
            value.noReplay,
        );
        if (req.url === '/cold-approval-native') {
          assert(value.oldAbsent && value.canControl);
          report.coldNative = value;
        } else {
          const original = report.coldRestart.originalApproval;
          assert(
            value.approvalId !== original.approvalId &&
              value.turnId !== original.turnId &&
              value.buttonsEnabled,
          );
          report.coldFreshNative = value;
        }
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/stale-conflict-refresh') {
        assert(
          staleConflict &&
            value.threadId === report.threadId &&
            report.staleApprovalAttempts.length === 1,
        );
        assert.equal(report.staleApprovalAttempts[0].actualHostStatus, 409);
        if (coldHostRestart) await restartConflictHost();
        else {
          conflictReadsHeld = false;
          releaseConflictReads();
        }
        report.staleConflictNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (
        [
          '/replacement-ready',
          '/replacement-approval-ready',
          '/replacement-release',
          '/replacement-complete',
        ].includes(req.url)
      ) {
        assert(replacementMode && value.threadId === report.threadId);
        if (req.url === '/replacement-ready') {
          const first = await wait(
            () => replacementSlots.get(value.approvalId),
            'Actual first approval response held',
          );
          assert.equal(first.observation.ordinal, 0);
          const next = await wait(async () => {
            await witness.ipc.follow(report.threadId, 5000, true);
            const owner = witness.ipc.state(report.threadId);
            if (
              !owner ||
              !witness.ipc.hasOwner(report.threadId) ||
              owner.id !== report.threadId ||
              owner.hostId !== 'local'
            )
              return null;
            const approvals = (await service.session.open(report.threadId)).runtime.approvals;
            const requests = owner.requests.filter((r) => r.method.includes('Approval'));
            const approval = approvals.find(
              (a) => a.id !== value.approvalId && a.turnId === first.observation.turnId,
            );
            return approvals.length === 1 && requests.length === 1 && approval
              ? { owner, approval, request: requests[0] }
              : null;
          }, 'Second real original-owner approval in the same Turn');
          assert.equal(await realpath(next.owner.cwd), projectRoot);
          const commandScope = assertExactApprovalCommand({
            request: next.request,
            approval: next.approval,
            threadId: report.threadId,
            turnId: first.observation.turnId,
            projectRoot,
            toolMarker: cases[0].secondToolMarker,
          });
          const choice = ['decline', 'deny', 'cancel'].find((c) =>
            next.approval.decisions.includes(c),
          );
          assert(choice);
          if (choice === 'cancel')
            report.denialLimitation =
              'Original owner declares cancellation, not decline-and-continue; verify interrupted Turn and no execution permission/output for the second command.';
          const lease = {
            index: 0,
            threadId: report.threadId,
            turnId: first.observation.turnId,
            approvalId: next.approval.id,
            decision: choice,
            commandScope,
          };
          assert(!approvalLeases.has('replacement'));
          approvalLeases.set('replacement', lease);
          report.approvalClaims.push(lease);
          const a = next.approval;
          report.replacementApproval = {
            id: a.id,
            threadId: a.threadId,
            turnId: a.turnId,
            kind: a.kind,
            title: a.title,
            detail: a.detail,
            decisions: a.decisions,
          };
          await save();
          return json(res, { ok: true, choice });
        }
        assert(report.replacementApproval);
        if (req.url === '/replacement-approval-ready') {
          assert.deepEqual(value.approval, report.replacementApproval);
          assert.equal(value.choice, approvalLeases.get('replacement').decision);
          const current = (await service.session.open(report.threadId)).runtime.approvals;
          assert(
            current.some((a) => a.id === value.approval.id && a.turnId === value.approval.turnId),
          );
          report.replacementNativeReady = true;
          await save();
          return json(res, { ok: true });
        }
        if (req.url === '/replacement-release') {
          let slot = replacementSlots.get(value.approvalId);
          if (slot?.observation.ordinal === 0) {
            await wait(
              () => report.newerPostHeld,
              'New exact approval input held before forwarding',
            );
            assert(report.replacementNativeReady);
            assert.equal(value.newApprovalId, report.replacementApproval.id);
            assert(!report.newerPostHeld.forwarded && !slot.observation.deliveredAt);
            report.newerPostHeld.notForwardedAtOldRelease = true;
          } else {
            assert.equal(value.approvalId, report.replacementApproval.id);
            assert(
              report.replacementCallbacks[0].deliveredAt &&
                report.newerPostHeld &&
                !report.newerPostHeld.allowed,
            );
            report.newerPostHeld.allowed = true;
            await save();
            releaseNewerPost();
            slot = await wait(
              () => replacementSlots.get(value.approvalId),
              'New original approval response',
            );
          }
          slot.release();
          await wait(() => slot.observation.deliveredAt, 'Exact approval response delivered');
          return json(res, { ok: true, status: slot.observation.deliveredStatus });
        }
        assert(
          report.replacementCallbacks.length === 2 &&
            report.replacementCallbacks.every((c) => c.deliveredAt),
        );
        for (const flag of ['newerPendingProtected', 'draftProtected', 'noOldError', 'noReplay'])
          assert.equal(value[flag], true);
        assert.equal(value.newApprovalId, report.replacementApproval.id);
        report.replacementNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (['/callback-held', '/release-callback', '/callback-complete'].includes(req.url)) {
        assert(lateCallback && value.threadId === report.threadId);
        await wait(() => report.callbackResponseHeld, 'Actual callback response held');
        assert.equal(value.approvalId, report.callbackResponseHeld.approvalId);
        if (req.url === '/callback-held') {
          const current = await service.session.open(report.threadId);
          assert(!current.runtime.approvals.some((a) => a.id === value.approvalId));
          report.callbackOriginalHandled = true;
          await save();
          return json(res, { ok: true });
        }
        assert(report.callbackOriginalHandled);
        if (req.url === '/release-callback') {
          assert(!report.callbackResponseDelivered);
          releaseCallback();
          await wait(() => report.callbackResponseDelivered, 'Actual callback response delivered');
          return json(res, { ok: true });
        }
        assert(
          report.callbackResponseDelivered &&
            value.draftProtected &&
            value.newerErrorProtected &&
            value.bDraftProtected,
        );
        report.callbackNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/claim-case') {
        const index = value.index;
        assert(
          index === fixture.activeCase && index >= 0 && index < cases.length && !claims.has(index),
        );
        assert.equal(value.text, originalText(index));
        if (index === 0) {
          assert.equal(value.threadId, '');
          assert.equal(report.posts.length, 0);
        } else {
          assert.equal(value.threadId, report.threadId);
          assert(
            Array.from({ length: index }, (_, n) => n).every((n) =>
              report.scenarios.some((r) => r.index === n),
            ),
          );
        }
        claims.set(index, value);
        report.claims.push(value);
        await save();
        return json(res, { ready: true });
      }
      assert(['/approval-ready', '/case-complete'].includes(req.url));
      assert.equal(value.index, fixture.activeCase);
      const result = await original(value.index, value, req.url === '/case-complete');
      if (externalFirst && value.index === 0 && req.url === '/approval-ready') {
        if (staleConflict) conflictReadsHeld = true;
        await answerOriginalExternally(value.index);
        if (staleConflict)
          await wait(async () => {
            const current = (await service.session.open(report.threadId)).runtime;
            const owner = witness.ipc.state(report.threadId);
            return (
              current.approvals.length === 0 && desktopTurns(owner).at(-1)?.status === 'interrupted'
            );
          }, 'Original owner cancellation committed before stale native write');
      }
      return json(res, result);
    })().catch((e) => {
      report.witnessError = clean(e.stack);
      void save();
      json(res, { error: clean(e.message) }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts = {
    host: service.server.address().port,
    proxy: proxyPort,
    control: controlPort,
  };
  await save();
  fixture = {
    nonce,
    evidenceRun: root.split('/').at(-1),
    hostId,
    token,
    endpoint: 'http://127.0.0.1:' + proxyPort,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    projectId,
    model: model.id,
    preflightOnly,
    fullDecisions,
    lateCallback,
    replacementMode,
    externalFirst,
    staleConflict,
    coldHostRestart,
    viewportOrientation,
    viewportTheme,
    nativeHit,
    independentDraft: 'Android 审批时独立草稿 ' + nonce,
    cases,
  };
  for (const port of [proxyPort, controlPort]) {
    await android('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    reverses.push(port);
  }
  for (const [name, source, packageName] of [
    ['app', '.build/native/android/outputs/apk/debug/android-debug.apk', pkg],
    [
      'test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      pkg + '.test',
    ],
  ]) {
    const apk = join(root, name + '.apk');
    await writeFile(apk, await readFile(source), { mode: 0o600 });
    report[name + 'Sha256'] = sha(await readFile(apk));
    await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true });
  }
  for (const index of preflightOnly ? [-1] : cases.map((row) => row.index)) {
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    fixture.activeCase = index;
    fixture.threadId = report.threadId ?? '';
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-approval-model-config.json'",
      ],
      { input: JSON.stringify(fixture), encoding: 'utf8', timeout: 10000 },
    );
    let result;
    try {
      result = await execute(
        adb,
        [
          '-s',
          serial,
          'shell',
          'am',
          'instrument',
          '-w',
          '-r',
          '-e',
          'class',
          'com.agenthub.android.MobileApprovalModelLiveTest#originalToolApprovalCase',
          '-e',
          'approvalModelCase',
          String(index),
          '-e',
          'nativeForeground',
          '1',
          pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
        ],
        { encoding: 'utf8', timeout: 650000, maxBuffer: 16 * 1024 * 1024 },
      );
    } catch (e) {
      await writeFile(
        join(root, `case-${index}-command-failure.json`),
        JSON.stringify(
          { code: e.code, killed: e.killed, stdout: clean(e.stdout), stderr: clean(e.stderr) },
          null,
          2,
        ),
        { mode: 0o600 },
      );
      throw e;
    }
    const log = clean(result.stdout + result.stderr);
    await writeFile(join(root, `case-${index}-instrumentation.log`), log, { mode: 0o600 });
    assert(
      /OK \(1 test\)/.test(log) &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed/.test(log),
    );
    report.native.push({ index, status: 'passed', logSha256: sha(log) });
    await save();
  }
  assert.equal(await fingerprint(), report.sourceSha256);
  assert.equal(report.rejectedPosts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  if (preflightOnly)
    assert(report.preflightNative && report.posts.length === 0 && submitted.size === 0);
  else {
    assert.equal(report.scenarios.length, cases.length);
    assert.equal(submitted.size, cases.length);
    assert.equal(report.newRealModelInputsConfirmed, cases.length);
    assert.equal(report.approvalPosts.length, fullDecisions ? cases.length : 2);
    if (externalFirst) {
      assert.equal(report.externalDecisionPosts.length, 1);
      assert.equal(report.externalDecisionPosts[0].outcome, 'accepted');
      assert.equal(
        report.posts.filter((p) => p.path === '/v1/approvals').length,
        staleConflict ? 2 : 1,
      );
      if (staleConflict)
        assert(
          report.staleApprovalAttempts.length === 1 &&
            report.staleApprovalAttempts[0].actualHostStatus === 409 &&
            report.staleConflictNative?.draftProtected,
        );
    }
  }
  if (replacementMode && !preflightOnly) {
    assert(
      report.replacementNative &&
        report.replacementCommandOutcome &&
        report.newerPostHeld?.notForwardedAtOldRelease &&
        report.newerPostHeld.forwarded,
    );
    assert.equal(report.replacementCallbacks.length, 2);
    assert.equal(report.replacementCallbacks[0].controlledFailure, replacementMode === 'failure');
    for (const c of report.replacementCallbacks)
      if (c.controlledFailure)
        assert(c.ordinal === 0 && c.originalStatus === 200 && c.deliveredStatus === 503);
      else assert(c.originalSha256 === c.deliveredSha256 && c.deliveredStatus === 200);
  }
  if (lateCallback && !replacementMode && !preflightOnly)
    assert(
      report.callbackNative &&
        report.callbackResponseHeld.sha256 === report.callbackResponseDelivered.sha256,
    );
  if (coldHostRestart && !preflightOnly)
    assert(
      report.coldNative &&
        report.coldFreshNative &&
        report.coldRestart.oldExited &&
        report.hostProcesses.length === 2,
    );
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  conflictReadsHeld = false;
  releaseConflictReads();
  releaseCallback();
  if (report.newerPostHeld && !report.newerPostHeld.allowed)
    report.newerPostHeld.cancelledBeforeForward = true;
  releaseNewerPost();
  for (const slot of replacementSlots.values()) slot.release();
  // Recover only the exact owned creation binding for final read-only counting.
  // A hidden/unknown public response never authorizes replay, another input or another decision.
  if (!report.threadId && submitted.has(0)) {
    try {
      const operation = submitted.get(0);
      const localState = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
      assert.equal(localState.hostId, hostId);
      const records = JSON.parse(
        await readFile(join(state, 'desktop-creations/creations.json'), 'utf8'),
      );
      const original = records[operation.operationId];
      assert(original && original.clientOperationId === operation.operationId);
      assert.equal(original.text, cases[0].text);
      assert.equal(original.projectId, projectId);
      assert.equal(await realpath(original.directory), projectRoot);
      assert.match(original.threadId ?? '', /^[a-f0-9-]{36}$/);
      assert(!original.existingThreadIds.includes(original.threadId));
      report.threadId = original.threadId;
      report.finalAuditUsedUnconfirmedOwnedRecord = true;
    } catch (e) {
      report.unconfirmedOriginalAuditError = clean(e.message);
    }
  }
  if (report.threadId) {
    try {
      await witness.ipc.follow(report.threadId, 5000, true);
      const owner = witness.ipc.state(report.threadId);
      assert(
        owner &&
          owner.id === report.threadId &&
          owner.hostId === 'local' &&
          witness.ipc.hasOwner(report.threadId),
      );
      assert.equal(await realpath(owner.cwd), projectRoot);
      const turns = desktopTurns(owner);
      const accepted = [];
      for (const [index, sent] of submitted) {
        const clientId = index === 0 ? 'first-' + sent.operationId : sent.operationId;
        const found = turns.filter((t) =>
          t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
        );
        assert(found.length <= 1);
        if (found.length)
          accepted.push({
            index,
            operationId: sent.operationId,
            clientId,
            turnId: found[0].turnId,
          });
      }
      report.consumedOriginalInputs = accepted;
      report.newRealModelInputsConfirmed = accepted.length;
      const canonical = await witness.reader.read('thread/read', {
        threadId: report.threadId,
        includeTurns: true,
      });
      report.finalCanonicalInputs = canonical.thread.turns
        .flatMap((t) => t.items ?? [])
        .filter((i) => i.type === 'userMessage').length;
      assert.equal(report.finalCanonicalInputs, accepted.length);
      await writeFile(join(root, 'final-original-owner.json'), JSON.stringify(owner, null, 2), {
        mode: 0o600,
      });
    } catch (e) {
      report.finalReadOnlyAuditError = clean(e.message);
    }
  }
  for (const [index, sent] of submitted)
    if (!report.scenarios.some((s) => s.index === index))
      report.unresolvedOriginalCases.push({
        index,
        operationId: sent.operationId,
        bodySha256: sha(JSON.stringify(sent.body)),
      });
  try {
    const archive = (
      await execute(
        adb,
        [
          '-s',
          serial,
          'exec-out',
          'run-as',
          pkg,
          'tar',
          '-cf',
          '-',
          'files/acceptance/P4-approval-model/' + nonce + '/' + fixture.evidenceRun,
        ],
        { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
      )
    ).stdout;
    await writeFile(join(root, 'native-evidence.tar'), archive, { mode: 0o600 });
  } catch (e) {
    report.artifactError = clean(e.message);
  }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const port of reverses) await android('reverse', '--remove', 'tcp:' + port).catch(() => {});
  for (const entry of upstreams) {
    entry.cleanupAbort = true;
    entry.controller.abort(new Error('owned-examiner-close'));
  }
  for (const server of [proxy, control])
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
  witness.close();
  if (coldHostRestart && service) await stopCold(service);
  else service?.close();
  if (
    (report.rejectedPosts.length ||
      report.rejectedReads.length ||
      report.finalReadOnlyAuditError) &&
    report.status === 'passed'
  ) {
    report.status = 'failed';
    report.error = 'Unexpected original reads/writes; preserve original IDs and never replay';
    process.exitCode = 1;
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      preflightOnly,
      nativePhases: report.native.length,
      confirmedNewModelInputs: report.newRealModelInputsConfirmed,
      unresolvedOriginalCases: report.unresolvedOriginalCases.length,
      error: report.error,
    }),
  );
}
