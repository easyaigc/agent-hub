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
import {
  desktopQuestions,
  parseDesktopQuestionReply,
} from '../../packages/codex-adapter/dist/desktop-questions.js';

const idleOnly = process.env.ANDROID_QUESTION_IDLE_ONLY === '1';
const recoveryOnly = process.env.ANDROID_QUESTION_RECOVERY_ONLY === '1';
const priorPath = process.env.ANDROID_QUESTION_PRIOR_REPORT;
const prior = priorPath ? JSON.parse(await readFile(priorPath, 'utf8')) : null;
const preflightOnly = process.env.ANDROID_QUESTION_PREFLIGHT_ONLY === '1';
const lateCallback = process.env.ANDROID_QUESTION_LATE_CALLBACK === '1';
const staleConflict = process.env.ANDROID_QUESTION_STALE_CONFLICT === '1';
const coldHostRestart = process.env.ANDROID_QUESTION_COLD_HOST_RESTART === '1';
const unknownAfterWrite = process.env.ANDROID_QUESTION_UNKNOWN_AFTER_WRITE === '1';
assert(!coldHostRestart || staleConflict);
assert(!staleConflict || (idleOnly && !lateCallback && !prior && !recoveryOnly));
const replacementMode = process.env.ANDROID_QUESTION_CALLBACK_REPLACEMENT ?? '';
assert(['', 'success', 'failure'].includes(replacementMode));
assert(!staleConflict || !replacementMode);
assert(
  !unknownAfterWrite ||
    (idleOnly &&
      !staleConflict &&
      !coldHostRestart &&
      !lateCallback &&
      !replacementMode &&
      !prior &&
      !recoveryOnly),
);
const sequentialQuestions = Boolean(replacementMode || staleConflict);
assert(!replacementMode || (idleOnly && lateCallback && !prior && !recoveryOnly));
assert(!lateCallback || (!prior && !recoveryOnly));
assert(preflightOnly || recoveryOnly || process.env.ANDROID_QUESTION_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_QUESTION_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_QUESTION_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_QUESTION_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
if (priorPath)
  assert(
    isAbsolute(priorPath) &&
      resolve(priorPath).startsWith(resolve(output) + '/question-model-live-') &&
      priorPath.endsWith('/report.json'),
  );
const root = join(
  output,
  'question-model-live-' + Date.now() + '-' + randomBytes(6).toString('hex'),
);
await mkdir(output, { recursive: true, mode: 0o700 });
await mkdir(root, { mode: 0o700 });
const state = prior ? join(resolve(priorPath, '..'), 'host') : join(root, 'host');
if (!prior) await mkdir(state, { mode: 0o700 });
if (prior) {
  assert.equal(prior.projectId, projectId);
  assert.equal(prior.projectRoot, projectRoot);
  assert.equal(prior.serial, serial);
  if (prior.idleOnly) {
    assert(recoveryOnly && idleOnly);
    assert.equal(prior.phase, 'P4-4e-permission-fix');
    assert.equal(prior.newRealModelInputsConfirmed, 2);
    assert.equal(prior.scenarios.length, 1);
    assert.equal(prior.native.length, 1);
    assert.equal(prior.native[0].status, 'passed');
    assert.equal(prior.rejectedPosts.length, 0);
  } else {
    assert.equal(prior.phase, 'P4-4e');
    assert.equal(prior.newRealModelInputsConfirmed, 3);
    assert.equal(prior.scenarios.length, 0);
  }
}

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
const hostId = prior?.hostId ?? randomUUID(),
  nonce = prior?.nonce ?? randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const fullCases = [
  {
    index: 0,
    doneMarker: 'ASYNC_DONE_' + nonce,
    text: `真实安卓异步问题验收。必须调用 request_user_input_async 一次提出三个问题：第一个标题为请选择交付格式，选项为简洁版和详细版；第二个标题为请输入验收暗号，不提供选项；第三个标题为可选的发布渠道，不提供选项。提问后不要等待回答，立即调用 exec_command 仅执行 printf 'ASYNC_CONTINUE_${nonce}'; sleep 100，不设置提权参数。独立工作完成后再读取异步回答；收到手机选择简洁版以及暗号 PHONE_${nonce} 后仅最终回复 ASYNC_DONE_${nonce}。第三题可以跳过，不要求回答；不要调用同步 request_user_input，不读写文件，不调用其他命令、网络或其他工具，不创建其他聊天或联系Agent。`,
  },
  {
    index: 1,
    doneMarker: 'ASYNC_IDLE_' + nonce,
    text: sequentialQuestions
      ? `真实安卓原owner请求替换验收。必须调用request_user_input_async提一个问题，标题为结束后继续对话，选项为继续验收和稍后处理。不要等待回答，立即最终回复ASYNC_IDLE_${nonce}。收到继续验收后，必须再次调用request_user_input_async提一个新问题，标题为替换后的原问题，选项为完成验收和稍后处理。不要等待回答，立即最终回复ASYNC_REPLACED_${nonce}。收到完成验收后仅最终回复ASYNC_FOLLOWUP_${nonce}。全程不读写文件、不执行命令或网络、不调用其他工具、不创建其他聊天或联系Agent。`
      : `第二轮安卓真实异步专项：必须调用 request_user_input_async 提一个问题，标题为结束后继续对话，选项为继续验收和稍后处理。不要等回答，立即最终回复 ASYNC_IDLE_${nonce}。之后收到回答继续验收时仅最终回复 ASYNC_FOLLOWUP_${nonce}。不读写文件、不调用其他工具或命令、不创建其他聊天或联系Agent。`,
  },
];
assert(!prior || idleOnly === prior.idleOnly);
const cases = idleOnly ? [{ ...fullCases[1], index: 0 }] : fullCases;
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
      'scripts/mobile/android-questions-live.mjs',
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
  phase: unknownAfterWrite
    ? 'P4-4o.14'
    : coldHostRestart
      ? 'P4-4o.12'
      : staleConflict
        ? 'P4-4o.11'
        : replacementMode
          ? 'P4-4o.9'
          : lateCallback
            ? 'P4-4o.6'
            : idleOnly
              ? 'P4-4e-permission-fix'
              : 'P4-4e',
  idleOnly,
  staleConflict,
  coldHostRestart,
  unknownAfterWrite,
  hostProcesses: [],
  hostProcessExits: [],
  staleQuestionAttempts: [],
  scope: unknownAfterWrite
    ? 'Real original async answer written and accepted once, only client response replaced with controlled503; native derives original immutable ID before POST and queries typed status GET without replay, preserves draft and original answer history. Not natural network or send-record UI proof'
    : coldHostRestart
      ? 'Actual stale409 after external resolution, old owned Host process exits and fresh PID binds same identity/port/state; original immutable answer receipt and pending new question recover without replay; physical native refresh, answer history and fresh answer. Not crash/power loss or desktop-UI/network-path proof'
      : staleConflict
        ? 'Exact original-owner question answered once by a second named HTTP client; different stale native answer receives actual Host409; visible error, explicit refresh and fresh question submission recover. No desktop-UI or natural-network claim'
        : replacementMode
          ? 'Exact original owner two sequential questions; real Native replies and controlled late success/failure after authoritative handling; no natural-network or desktop-UI proof'
          : lateCallback
            ? 'Physical native original-owner asynchronous question callback; unchanged actual response delayed after authoritative handling, no Host restart in this mode; loopback, not natural network delay or desktop UI proof'
            : 'Physical native original-owner asynchronous questions, active steer/freeform/skip and idle followup after owned Host restart; USB loopback, not network path proof',
  lateCallback,
  replacementMode,
  replacementCallbacks: [],
  callbackScope: replacementMode
    ? 'Old actual response held; newer immutable POST held before forwarding so the real new request remains unresolved; old unchanged success or explicit injected HTTP503 cannot release the newer wait'
    : lateCallback
      ? 'Controlled delay of unchanged actual approval success response; local A/B/A selection and newer error/draft protection; not natural-network or desktop-UI proof'
      : null,
  status: 'running',
  preflightOnly,
  recoveryOnly,
  priorReportSha256: prior ? sha(await readFile(priorPath)) : null,
  priorConsumedInputs: prior?.newRealModelInputsConfirmed ?? 0,
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
  newRealModelInputsConfirmed: 0,
  questionClaims: [],
  questionPosts: [],
  answerObservations: [],
  restartObservations: [],
  consumedOriginalInputs: [],
  unresolvedOriginalCases: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original async read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, endpoint, fixture;
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
const questionLeases = new Map();
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
      report.staleQuestionAttempts[0]?.actualHostStatus === 409,
  );
  const old = service,
    port = old.server.address().port,
    oldToken = token;
  const before = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
  const action = before.asyncQuestionActions[report.conflictOriginal.id];
  assert(action?.resolved && action.reply?.clientMessageId);
  const query = { threadId: report.threadId, id: action.reply.clientMessageId };
  const receiptBefore = await old.read('/v1/replies/status', query);
  assert.equal(receiptBefore.status, 'accepted');
  assert(receiptBefore.fingerprint);
  let release;
  restartGate = new Promise((done) => {
    release = done;
  });
  conflictReadsHeld = false;
  releaseConflictReads();
  try {
    await wait(
      () => [...upstreams].every((entry) => !entry.forwarding),
      'Owned reads drained before cold process exit',
    );
    await stopCold(old);
    service = await startCold(port);
    assert.notEqual(service.pid, old.pid);
    token = service.token;
    endpoint = service.endpoint;
    assert.equal(token, oldToken);
    const receiptAfter = await service.read('/v1/replies/status', query);
    for (const key of [
      'hostId',
      'threadId',
      'clientMessageId',
      'status',
      'target',
      'fingerprint',
      'fingerprintVersion',
    ])
      assert.deepEqual(receiptAfter[key], receiptBefore[key]);
    const after = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
    assert.deepEqual(after.asyncQuestionActions[report.conflictOriginal.id], action);
    report.coldRestart = {
      oldPid: old.pid,
      newPid: service.pid,
      oldExited: true,
      hostId,
      port,
      tokenUnchanged: true,
      actionUnchanged: true,
      receiptBefore,
      receiptAfter,
      receiptId: query.id,
      noReplay: true,
    };
    await save();
  } finally {
    release();
    restartGate = undefined;
  }
}
let conflictReadsHeld = false,
  releaseConflictReads;
const conflictReadGate = new Promise((done) => {
  releaseConflictReads = done;
});
async function holdConflictRead(req) {
  if (req.method !== 'GET' || !(staleConflict || unknownAfterWrite) || !conflictReadsHeld) return;
  if (!['/v1/history', '/v1/events'].includes(new URL(req.url, endpoint).pathname)) return;
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
if (prior) {
  report.threadId = prior.threadId;
  report.consumedOriginalInputs = structuredClone(prior.consumedOriginalInputs);
  report.continuedUnanswered = prior.continuedUnanswered;
  for (const q of prior.questionClaims) questionLeases.set(q.approvalId, q);
}
const tally = (total) => {
  report.workflowAcceptedInputs = total;
  report.newRealModelInputsConfirmed = total - report.priorConsumedInputs;
  assert(report.newRealModelInputsConfirmed >= 0);
};
const repliesIn = (items) =>
  items
    .filter(
      (i) =>
        i.type === 'userMessage' || (i.type === 'steeringUserMessage' && i.status === 'accepted'),
    )
    .flatMap((i) =>
      parseDesktopQuestionReply((i.content ?? i.input ?? [])[0]?.text ?? '').map((reply) => ({
        reply,
        itemId: i.id,
        clientId: i.clientId,
        type: i.type,
        status: i.status,
      })),
    );
const owner = async () => {
  await witness.ipc.follow(report.threadId, 5000, true);
  const value = witness.ipc.state(report.threadId);
  assert(
    value &&
      witness.ipc.hasOwner(report.threadId) &&
      value.id === report.threadId &&
      value.hostId === 'local',
  );
  assert.equal(await realpath(value.cwd), projectRoot);
  return value;
};
const effective = (turn) => {
  assert.equal(turn.params.collaborationMode.mode, 'default');
  assert.equal(turn.params.model ?? turn.params.collaborationMode.settings?.model, fixture.model);
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode.settings?.reasoning_effort,
    'xhigh',
  );
  assert.equal(turn.params.serviceTier, 'priority');
  assert.equal(turn.params.sandboxPolicy.type, 'readOnly');
  assert.equal(turn.params.approvalPolicy, 'on-request');
};
const approvalProjection = (a) => ({
  id: a.id,
  threadId: a.threadId,
  turnId: a.turnId,
  kind: a.kind,
  interactionMode: a.interactionMode,
  questions: a.questions.map((q) => ({
    id: q.id,
    question: q.question,
    options: q.options.map((o) => o.label),
  })),
});
async function observeAnswers() {
  const value = await owner(),
    turns = desktopTurns(value),
    answers = repliesIn(turns.flatMap((t) => t.items ?? []));
  const persisted = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
  const actions = Object.entries(persisted.asyncQuestionActions ?? {});
  const found = [];
  for (const [id, action] of actions) {
    if (action.decision === 'skip') continue;
    const q = questionLeases.get(id);
    assert(q && action.reply && action.resolved);
    assert.equal(action.reply.clientMessageId, 'async-question-' + sha(id));
    const matched = answers.filter((r) => r.reply.questionItemId === q.questionId);
    assert.equal(matched.length, 1, 'Original accepted question reply exactly once');
    assert.equal(matched[0].reply.answer, q.answer);
    assert.equal(matched[0].reply.question, q.title);
    const receipt = persisted.receipts.find(
      (r) => r.clientMessageId === action.reply.clientMessageId,
    );
    assert(receipt && ['submitted', 'completed', 'interrupted'].includes(receipt.state));
    found.push({
      approvalId: id,
      questionId: q.questionId,
      clientId: action.reply.clientMessageId,
      mode: action.reply.mode,
      expectedTurnId: action.reply.expectedTurnId ?? null,
      original: matched[0],
      receiptState: receipt.state,
    });
  }
  report.acceptedQuestionInputs = found;
  tally(report.consumedOriginalInputs.length + found.length);
  return { value, turns, answers, persisted };
}
async function original(index, value, completed = false) {
  const active = index === 0 && !idleOnly;
  const sent = submitted.get(index);
  assert(sent && sent.operationId === value.record.id);
  const clientId = index === 0 ? 'first-' + sent.operationId : sent.operationId;
  if (index === 0) report.threadId = value.threadId;
  else assert.equal(value.threadId, report.threadId);
  const observed = await wait(async () => {
    const v = await owner();
    return desktopTurns(v).some((t) =>
      t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
    )
      ? v
      : null;
  }, 'Original async input owner');
  const turns = desktopTurns(observed).filter((t) =>
    t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
  );
  assert.equal(turns.length, 1);
  const turn = turns[0];
  effective(turn);
  assert.equal(sha(value.record.intent), value.record.intentSha256);
  assert.deepEqual(JSON.parse(value.record.intent), sent.body);
  if (!report.consumedOriginalInputs.some((r) => r.index === index))
    report.consumedOriginalInputs.push({
      index,
      threadId: value.threadId,
      operationId: sent.operationId,
      clientId,
      turnId: turn.turnId,
    });
  tally(
    report.consumedOriginalInputs.length +
      (report.acceptedQuestionInputs?.length ??
        prior?.acceptedQuestionInputIdsReadOnly?.length ??
        0),
  );
  const runtime = (await service.session.open(value.threadId)).runtime;
  assert.equal(observed.requests.length, 0, 'No unexpected blocking tool or permission request');
  if (!completed) {
    const expected = active
      ? ['请选择交付格式', '请输入验收暗号', '可选的发布渠道']
      : ['结束后继续对话'];
    const questions = desktopQuestions(turn.items);
    assert.deepEqual(
      questions.map((q) => q.title),
      expected,
    );
    assert.deepEqual(
      questions[0].options,
      active ? ['简洁版', '详细版'] : ['继续验收', '稍后处理'],
    );
    assert.deepEqual(value.approvals, runtime.approvals.map(approvalProjection));
    assert.equal(runtime.approvals.length, expected.length);
    runtime.approvals.forEach((a, n) => {
      assert.equal(a.id, value.threadId + ':async:' + questions[n].id);
      assert.equal(a.turnId, turn.turnId);
      assert.equal(a.questions[0].id, questions[n].id);
    });
    if (active) {
      const continued = await wait(async () => {
        const t = desktopTurns(await owner()).find((t) => t.turnId === turn.turnId);
        const c = t.items.find((i) => i.type === 'commandExecution');
        return c && t.status === 'inProgress' && repliesIn(t.items).length === 0
          ? { turn: t, command: c }
          : null;
      }, 'Original command continues before any answer');
      const expectedCommand = `/bin/zsh -lc "printf 'ASYNC_CONTINUE_${nonce}'; sleep 100"`;
      assert(
        continued.command.command === expectedCommand,
        'Only authorized print and bounded sleep command',
      );
      assert.equal(continued.command.status, 'inProgress');
      report.continuedUnanswered = {
        turnId: turn.turnId,
        commandSha256: sha(expectedCommand),
        status: continued.command.status,
        outputSeen: continued.command.aggregatedOutput ?? null,
        noAcceptedAnswers: true,
      };
    } else {
      assert.equal(turn.status, 'completed');
      assert(
        turn.items.some(
          (i) => i.type === 'agentMessage' && i.text.trim() === cases[index].doneMarker,
        ),
      );
    }
    report.pendingQuestionSets ??= [];
    report.pendingQuestionSets.push({
      index,
      turnId: turn.turnId,
      questions,
      approvals: value.approvals,
    });
  } else {
    const result = await observeAnswers();
    assert.equal(runtime.approvals.length, 0);
    assert.equal(turn.status, 'completed');
    assert(
      turn.items.some(
        (i) => i.type === 'agentMessage' && i.text.trim() === cases[index].doneMarker,
      ),
    );
    assert.equal(
      (prior && index === 0 ? prior.questionPosts : report.questionPosts).filter(
        (p) => p.index === index,
      ).length,
      active ? 3 : sequentialQuestions ? 2 : 1,
    );
    const commands = turn.items.filter((i) => i.type === 'commandExecution');
    assert(
      !turn.items.some((i) =>
        ['fileChange', 'mcpToolCall', 'dynamicToolCall', 'webSearch'].includes(i.type),
      ),
    );
    if (active) {
      assert.equal(commands.length, 1);
      report.commandTerminalVerified =
        commands[0].status === 'completed' && commands[0].exitCode === 0;
      if (report.commandTerminalVerified)
        assert.equal(commands[0].aggregatedOutput.trim(), 'ASYNC_CONTINUE_' + nonce);
      else
        report.commandTerminalLimitation = {
          originalStatus: commands[0].status,
          exitCode: commands[0].exitCode ?? null,
          scope:
            'Started before answers observed; terminal command outcome remains unverified, independently from completed original Turn and accepted answers.',
        };
      assert.equal(result.answers.length, 2);
      assert.equal(report.acceptedQuestionInputs.length, 2);
      assert(
        report.acceptedQuestionInputs.every(
          (a) => a.mode === 'steer' && a.expectedTurnId === turn.turnId,
        ),
      );
    } else {
      assert.equal(commands.length, 0);
      assert.equal(result.answers.length, sequentialQuestions ? 2 : idleOnly ? 1 : 3);
      assert.equal(
        report.acceptedQuestionInputs.length,
        sequentialQuestions ? 2 : idleOnly ? 1 : 3,
      );
      const follow = result.turns.at(-1);
      assert.notEqual(follow.turnId, turn.turnId);
      assert.equal(follow.status, 'completed');
      effective(follow);
      assert(
        follow.items.some(
          (i) => i.type === 'agentMessage' && i.text.trim() === 'ASYNC_FOLLOWUP_' + nonce,
        ),
      );
      assert.equal(report.acceptedQuestionInputs.at(-1).mode, 'next-turn');
      assert.equal(result.turns.length, sequentialQuestions ? 3 : idleOnly ? 2 : 3);
      if (sequentialQuestions) {
        effective(result.turns[1]);
        assert.equal(result.turns[1].status, 'completed');
      }
    }
    const canonical = await witness.reader.read('thread/read', {
      threadId: value.threadId,
      includeTurns: true,
    });
    const userItems = canonical.thread.turns
      .flatMap((t) => t.items ?? [])
      .filter((i) => i.type === 'userMessage');
    assert.equal(
      userItems.length,
      report.consumedOriginalInputs.length + (report.acceptedQuestionInputs?.length ?? 0),
    );
    report.scenarios.push({
      index,
      threadId: value.threadId,
      turnId: turn.turnId,
      operationId: sent.operationId,
      clientId,
      textSha256: sha(cases[index].text),
      nativePid: value.pid,
      canonicalUserItemCount: userItems.length,
      acceptedInputs: report.newRealModelInputsConfirmed,
    });
    await writeFile(
      join(root, `case-${index}-original-owner.json`),
      JSON.stringify(result.value, null, 2),
      { mode: 0o600 },
    );
  }
  await save();
  return { ok: true };
}

try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const bridge = join(state, 'bridge.json');
  await writeFile(bridge, JSON.stringify({ hostId, desktopVersion: witness.version }), {
    mode: 0o600,
  });
  if (!prior)
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
    HOST_AGENT_NAME: 'Android real async question DEV',
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
      'Fresh owned Host actual workspace ready',
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
    const entry = { controller: new AbortController(), cleanupAbort: false };
    upstreams.add(entry);
    void (async () => {
      assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + token);
      const url = new URL(req.url, endpoint);
      if (restartGate) {
        assert.equal(req.method, 'GET');
        await restartGate;
      }
      assert(
        req.method === 'GET'
          ? gets.has(url.pathname)
          : req.method === 'POST' && posts.has(url.pathname),
      );
      await holdConflictRead(req);
      let bytes, heldCandidate, staleCandidate, unknownCandidate;
      if (req.method === 'POST') {
        assert(!preflightOnly && !recoveryOnly);
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
          if (lateCallback && fixture.activeCase === (idleOnly ? 0 : 1))
            heldCandidate = {
              approvalId: value.approvalId,
              turnId: value.turnId,
              threadId: value.threadId,
            };
          const lease = questionLeases.get(value.approvalId);
          assert(lease);
          for (const key of ['threadId', 'turnId', 'approvalId', 'decision'])
            assert.equal(value[key], lease[key]);
          if (staleConflict && value.approvalId === report.conflictOriginal?.id) {
            assert(conflictReadsHeld && report.externalQuestionDecision?.httpStatus === 200);
            assert.equal(report.staleQuestionAttempts.length, 0);
            assert.deepEqual(value.answers, { [lease.questionId]: ['稍后处理'] });
            staleCandidate = {
              ...lease,
              answers: value.answers,
              actor: 'stale-native-ui',
              attempted: true,
              bodySha256: sha(JSON.stringify(value)),
            };
            report.staleQuestionAttempts.push(staleCandidate);
          } else {
            assert(!report.questionPosts.some((p) => p.approvalId === lease.approvalId));
            assert.deepEqual(value.answers, lease.answers);
            report.questionPosts.push({ ...lease, bodySha256: sha(JSON.stringify(value)) });
            if (unknownAfterWrite) {
              assert(!report.unknownSubmission && report.questionPosts.length === 1);
              unknownCandidate = {
                ...lease,
                clientMessageId: 'async-question-' + sha(value.approvalId),
                bodySha256: sha(JSON.stringify(value)),
                attempted: true,
                outcome: 'unknown',
              };
              report.unknownSubmission = unknownCandidate;
              conflictReadsHeld = true;
            }
          }
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
      if (replacementMode && heldCandidate && report.questionPosts.length === 2) {
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
      // A read may have awaited the conflict gate after its first restart check.
      // Re-check immediately before forwarding so the old process cannot receive a late GET.
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
          assert.equal(JSON.parse(data.toString()).error, '此问题已有不同的提交，请刷新');
          Object.assign(staleCandidate, {
            actualHostStatus: response.status,
            responseSha256: sha(data),
            error: JSON.parse(data.toString()).error,
          });
          await save();
        }
        if (unknownCandidate) {
          assert.equal(response.status, 200);
          assert.equal(JSON.parse(data.toString()).ok, true);
          const persisted = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
          const action = persisted.asyncQuestionActions[unknownCandidate.approvalId];
          assert(
            action?.resolved && action.reply?.clientMessageId === unknownCandidate.clientMessageId,
          );
          assert.equal(action.threadId, unknownCandidate.threadId);
          assert.equal(action.turnId, unknownCandidate.turnId);
          assert.deepEqual(action.answers, unknownCandidate.answers);
          const url = new URL('/v1/replies/status', endpoint);
          for (const [key, value] of Object.entries({
            hostId,
            threadId: unknownCandidate.threadId,
            id: unknownCandidate.clientMessageId,
          }))
            url.searchParams.set(key, value);
          const checked = await fetch(url, {
            headers: { Authorization: 'Bearer ' + token },
            redirect: 'error',
            signal: AbortSignal.timeout(10000),
          });
          assert.equal(checked.status, 200);
          const receipt = await checked.json();
          assert.equal(receipt.status, 'accepted');
          assert(receipt.fingerprint);
          assert.equal(receipt.clientMessageId, unknownCandidate.clientMessageId);
          assert.equal(receipt.threadId, unknownCandidate.threadId);
          assert.equal(receipt.hostId, hostId);
          report.unknownWritten = {
            actualHostStatus: response.status,
            actualResponseSha256: sha(data),
            deliveredStatus: 503,
            controlledFailure: true,
            clientResult: 'unknown',
            action,
            receipt,
            bodySha256: unknownCandidate.bodySha256,
          };
          await save();
          json(res, { error: '验收受控回调：回答结果待核对，请查询原回执' }, 503);
          return;
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
      if (req.url === '/unknown-receipt-native') {
        assert(
          unknownAfterWrite &&
            report.unknownWritten &&
            value.threadId === report.threadId &&
            value.unknownErrorShown &&
            value.draftProtected &&
            value.noReplay,
        );
        const written = report.unknownWritten,
          known = written.receipt;
        assert.equal(value.derivedBeforeSubmitId, written.action.reply.clientMessageId);
        for (const key of [
          'hostId',
          'threadId',
          'clientMessageId',
          'status',
          'target',
          'fingerprint',
          'fingerprintVersion',
        ])
          assert.deepEqual(value.receipt[key], known[key]);
        assert.equal(report.questionPosts.length, 1);
        report.unknownNative = value;
        conflictReadsHeld = false;
        releaseConflictReads();
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/unknown-recovery-complete') {
        assert(
          unknownAfterWrite &&
            report.unknownNative &&
            value.threadId === report.threadId &&
            value.originalAbsent &&
            value.acceptedAnswerVisible &&
            value.draftProtected &&
            value.noReplay,
        );
        assert.equal(report.questionPosts.length, 1);
        report.unknownRecoveryNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/cold-receipt-native') {
        assert(coldHostRestart && report.coldRestart && value.threadId === report.threadId);
        const known = report.coldRestart.receiptAfter;
        for (const key of [
          'hostId',
          'threadId',
          'clientMessageId',
          'status',
          'target',
          'fingerprint',
          'fingerprintVersion',
        ])
          assert.deepEqual(value.receipt[key], known[key]);
        assert(
          value.draftProtected && value.acceptedAnswerVisible && value.pendingNewQuestionUnselected,
        );
        report.coldNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/stale-question-start') {
        assert(
          staleConflict && value.threadId === report.threadId && !report.externalQuestionDecision,
        );
        const current = (await service.session.open(report.threadId)).runtime.approvals;
        assert.equal(current.length, 1);
        assert.deepEqual(value.approval, approvalProjection(current[0]));
        const a = current[0],
          q = a.questions[0];
        assert.equal(q.question, '结束后继续对话');
        assert(!questionLeases.has(a.id));
        const lease = {
          index: 0,
          approvalId: a.id,
          threadId: a.threadId,
          turnId: a.turnId,
          questionId: q.id,
          title: q.question,
          answer: '继续验收',
          decision: '',
          answers: { [q.id]: ['继续验收'] },
        };
        questionLeases.set(a.id, lease);
        report.questionClaims.push(lease);
        report.conflictOriginal = value.approval;
        const body = {
          hostId,
          threadId: a.threadId,
          turnId: a.turnId,
          approvalId: a.id,
          decision: '',
          answers: lease.answers,
        };
        report.externalQuestionDecision = {
          ...lease,
          actor: 'second-named-http-client',
          attempted: true,
          outcome: 'unknown',
          bodySha256: sha(JSON.stringify(body)),
        };
        conflictReadsHeld = true;
        await save();
        const response = await fetch(endpoint + '/v1/approvals', {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          redirect: 'error',
          signal: AbortSignal.timeout(30000),
        });
        const bytes = Buffer.from(await response.arrayBuffer());
        report.externalQuestionDecision.httpStatus = response.status;
        await save();
        assert.equal(response.status, 200);
        assert.equal(JSON.parse(bytes.toString()).ok, true);
        Object.assign(report.externalQuestionDecision, {
          outcome: 'accepted',
          responseSha256: sha(bytes),
        });
        report.questionPosts.push({
          ...lease,
          actor: 'second-named-http-client',
          bodySha256: sha(JSON.stringify(body)),
        });
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/stale-question-refresh') {
        assert(
          staleConflict &&
            value.threadId === report.threadId &&
            report.staleQuestionAttempts.length === 1,
        );
        assert.equal(report.staleQuestionAttempts[0].actualHostStatus, 409);
        if (coldHostRestart) await restartConflictHost();
        else {
          conflictReadsHeld = false;
          releaseConflictReads();
        }
        const next = await wait(async () => {
          const o = await owner(),
            turns = desktopTurns(o),
            approvals = (await service.session.open(report.threadId)).runtime.approvals;
          return approvals.length === 1 &&
            approvals[0].id !== report.conflictOriginal.id &&
            approvals[0].questions[0]?.question === '替换后的原问题' &&
            turns.at(-1)?.status === 'completed'
            ? { approval: approvals[0], turn: turns.at(-1) }
            : null;
        }, 'New original question after external answer');
        assert.notEqual(next.approval.turnId, report.conflictOriginal.turnId);
        effective(next.turn);
        report.conflictReplacement = approvalProjection(next.approval);
        await observeAnswers();
        await save();
        report.staleQuestionNative = value;
        await save();
        return json(res, {
          ok: true,
          ...(coldHostRestart ? { coldReceipt: report.coldRestart.receiptAfter } : {}),
        });
      }
      if (
        ['/replacement-ready', '/replacement-release', '/replacement-complete'].includes(req.url)
      ) {
        assert(replacementMode && value.threadId === report.threadId);
        if (req.url === '/replacement-ready') {
          const first = await wait(
            () => replacementSlots.get(value.approvalId),
            'First real response held',
          );
          assert.equal(first.observation.ordinal, 0);
          const next = await wait(async () => {
            const o = await owner();
            const turns = desktopTurns(o);
            const a = (await service.session.open(report.threadId)).runtime.approvals;
            const question = a.find((a) => a.questions[0]?.question === '替换后的原问题');
            const turn = turns.find((t) => t.turnId === question?.turnId);
            return a.length === 1 &&
              question &&
              turn?.status === 'completed' &&
              turn.items.some(
                (i) => i.type === 'agentMessage' && i.text.trim() === 'ASYNC_REPLACED_' + nonce,
              )
              ? { question, turn }
              : null;
          }, 'New original-owner request after accepted first answer');
          assert.notEqual(next.question.id, value.approvalId);
          assert.notEqual(next.question.turnId, first.observation.turnId);
          assert.deepEqual(
            next.question.questions[0].options.map((o) => o.label),
            ['完成验收', '稍后处理'],
          );
          effective(next.turn);
          const observed = await observeAnswers();
          assert.equal(observed.answers.length, 1);
          report.replacementQuestion = approvalProjection(next.question);
          await save();
          return json(res, { ok: true });
        }
        assert(report.replacementQuestion);
        if (req.url === '/replacement-release') {
          let slot = replacementSlots.get(value.approvalId);
          if (slot?.observation.ordinal === 0) {
            await wait(
              () => report.newerPostHeld,
              'New immutable input held before Host forwarding',
            );
            assert.equal(value.newApprovalId, report.replacementQuestion.id);
            assert(!report.newerPostHeld.forwarded && !slot.observation.deliveredAt);
            report.newerPostHeld.notForwardedAtOldRelease = true;
          } else {
            assert.equal(value.approvalId, report.replacementQuestion.id);
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
              'New exact Host response',
            );
          }
          slot.release();
          await wait(() => slot.observation.deliveredAt, 'Exact response delivered');
          return json(res, { ok: true, status: slot.observation.deliveredStatus });
        }
        assert(
          report.replacementCallbacks.length === 2 &&
            report.replacementCallbacks.every((c) => c.deliveredAt),
        );
        for (const flag of [
          'newerPendingProtected',
          'draftProtected',
          'newerErrorProtected',
          'noReplay',
        ])
          assert.equal(value[flag], true);
        assert.equal(value.newApprovalId, report.replacementQuestion.id);
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
      if (req.url === '/recovery-complete') {
        assert(prior && !preflightOnly && value.index === 0 && report.posts.length === 0);
        const data = JSON.parse(value.record.intent),
          expected = prior.posts.find((p) => p.path === '/v1/desktop/creations');
        assert.equal(sha(JSON.stringify(data)), expected.bodySha256);
        assert.equal(data.clientOperationId, prior.consumedOriginalInputs[0].operationId);
        submitted.set(0, { operationId: data.clientOperationId, body: data });
        const recovered = await original(0, value, true);
        report.readOnlyRecovery = {
          threadId: value.threadId,
          recoveryMarker: idleOnly ? 'ASYNC_FOLLOWUP_' + nonce : 'ASYNC_DONE_' + nonce,
          priorOperationId: data.clientOperationId,
          nativePid: value.pid,
          noBusinessWrites: true,
        };
        await save();
        return json(res, recovered);
      }
      if (req.url === '/claim-case') {
        const index = value.index;
        assert(index === fixture.activeCase && index >= 0 && index <= 1 && !claims.has(index));
        assert.equal(value.text, originalText(index));
        if (index === 0) {
          assert.equal(value.threadId, '');
          assert.equal(report.posts.length, 0);
        } else {
          assert.equal(value.threadId, report.threadId);
          assert(report.scenarios.some((r) => r.index === 0));
        }
        claims.set(index, value);
        report.claims.push(value);
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/question-current') {
        assert(
          lateCallback &&
            value.threadId === report.threadId &&
            fixture.activeCase === (idleOnly ? 0 : 1),
        );
        const pending = (await service.session.open(report.threadId)).runtime.approvals;
        assert.equal(pending.length, 1);
        assert.deepEqual(value.approval, approvalProjection(pending[0]));
        assert.equal(desktopTurns(await owner()).at(-1).status, 'completed');
        report.callbackFreshQuestionVerified = true;
        report.callbackHostRestarted = false;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/answer-ready') {
        const a = value.approval,
          index = value.index;
        assert.equal(index, fixture.activeCase);
        assert(!questionLeases.has(a.id));
        const current = (await service.session.open(report.threadId)).runtime.approvals.find(
          (r) => r.id === a.id,
        );
        assert(current);
        assert.deepEqual(a, approvalProjection(current));
        assert.equal(current.kind, 'question');
        assert.equal(current.interactionMode, 'async');
        const q = current.questions[0],
          expected = {
            请选择交付格式: '简洁版',
            请输入验收暗号: 'PHONE_' + nonce,
            可选的发布渠道: null,
            结束后继续对话: '继续验收',
            ...(sequentialQuestions ? { 替换后的原问题: '完成验收' } : {}),
          };
        assert(Object.hasOwn(expected, q.question));
        assert.equal(value.answer, expected[q.question]);
        assert.equal(value.skip, q.question === '可选的发布渠道');
        const o = await owner(),
          t = desktopTurns(o).find((t) => t.turnId === a.turnId);
        assert(t && desktopQuestions(t.items).some((q) => q.id === current.questions[0].id));
        assert.equal(t.status, index === 0 && !idleOnly ? 'inProgress' : 'completed');
        if (index === 1 || idleOnly) {
          if (lateCallback) assert(report.callbackFreshQuestionVerified);
          else if (!staleConflict && !unknownAfterWrite)
            assert.equal(report.restartObservations.length, 1);
        }
        const lease = {
          index,
          approvalId: a.id,
          threadId: a.threadId,
          turnId: a.turnId,
          questionId: q.id,
          title: q.question,
          answer: value.answer,
          decision: value.skip ? 'skip' : '',
          answers: value.skip ? {} : { [q.id]: [value.answer] },
        };
        questionLeases.set(a.id, lease);
        report.questionClaims.push(lease);
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/restart') {
        assert.equal(fixture.activeCase, idleOnly ? 0 : 1);
        assert.equal(value.threadId, report.threadId);
        assert.equal(report.restartObservations.length, 0);
        const result = await observeAnswers();
        assert.equal(result.answers.length, idleOnly ? 0 : 2);
        const a = (await service.session.open(report.threadId)).runtime.approvals;
        assert.equal(a.length, 1);
        assert.deepEqual(value.approval, approvalProjection(a[0]));
        const actions = result.persisted.asyncQuestionActions ?? {};
        assert.equal(Object.keys(actions).length, idleOnly ? 0 : 3);
        assert.equal(
          Object.values(actions).filter((a) => a.decision === 'skip').length,
          idleOnly ? 0 : 1,
        );
        const oldPort = service.server.address().port,
          oldToken = token;
        let release;
        restartGate = new Promise((done) => (release = done));
        try {
          // New GETs wait at the gate; finish already forwarded reads before closing the Host.
          await wait(
            () => [...upstreams].every((entry) => !entry.forwarding),
            'In-flight reads drained before restart',
          );
          report.restartReadsDrained = true;
          await new Promise((done) => {
            service.server.once('close', done);
            service.close();
          });
          process.env.HOST_AGENT_PORT = String(oldPort);
          service = await startRuntimeServer();
          assert.equal(service.server.address().port, oldPort);
          assert.equal((await readFile(join(state, 'access-token'), 'utf8')).trim(), oldToken);
          assert.equal((await service.session.workspace()).hostId, hostId);
          const ownerReadyStarted = Date.now();
          let ownerReadyPolls = 0;
          const restored = await wait(async () => {
            ownerReadyPolls++;
            const opened = await service.session.open(report.threadId);
            if (
              opened.hostId !== hostId ||
              opened.threadId !== report.threadId ||
              !opened.runtime.canControl
            )
              return null;
            const values = opened.runtime.approvals;
            return JSON.stringify(values.map(approvalProjection)) ===
              JSON.stringify(a.map(approvalProjection))
              ? values
              : null;
          }, 'Exact original owner and pending question ready after Host restart');
          report.restartOwnerReady = {
            polls: ownerReadyPolls,
            elapsedMs: Date.now() - ownerReadyStarted,
            approvalId: restored[0].id,
            turnId: restored[0].turnId,
          };
          const after = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
          assert.deepEqual(after.asyncQuestionActions ?? {}, actions);
          report.restartObservations.push({
            hostId,
            port: oldPort,
            tokenUnchanged: true,
            priorActionsUnchanged: true,
            approvalId: a[0].id,
            threadId: report.threadId,
            at: new Date().toISOString(),
          });
        } finally {
          release();
          restartGate = undefined;
        }
        await save();
        return json(res, { ok: true });
      }
      assert(['/questions-ready', '/case-complete'].includes(req.url));
      assert.equal(value.index, fixture.activeCase);
      return json(res, await original(value.index, value, req.url === '/case-complete'));
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
    isolatedSession: !prior,
    replacementMode,
    lateCallback,
    idleOnly,
    staleConflict,
    coldHostRestart,
    unknownAfterWrite,
    recoveryMarker: idleOnly ? 'ASYNC_FOLLOWUP_' + nonce : 'ASYNC_DONE_' + nonce,
    priorOperationId: prior?.consumedOriginalInputs[0].operationId ?? null,
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
  if (prior)
    assert.equal(
      report.appSha256,
      prior.appSha256,
      'Read-only recovery must use the exact original product package',
    );
  for (const index of preflightOnly
    ? [-1]
    : prior
      ? recoveryOnly
        ? [-2]
        : [-2, 1]
      : idleOnly
        ? [0]
        : [0, 1]) {
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    fixture.activeCase = index;
    fixture.threadId = report.threadId ?? '';
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-question-model-config.json'",
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
          'com.agenthub.android.MobileQuestionModelLiveTest#' +
            (index === -2 ? 'originalAsyncReadOnlyRecovery' : 'originalAsyncQuestionCase'),
          '-e',
          'questionModelCase',
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
  else if (recoveryOnly) {
    assert(
      report.readOnlyRecovery &&
        report.posts.length === 0 &&
        report.newRealModelInputsConfirmed === 0,
    );
  } else {
    assert.equal(report.scenarios.length, idleOnly ? 1 : 2);
    assert.equal(submitted.size, idleOnly ? 1 : 2);
    assert.equal(
      report.newRealModelInputsConfirmed,
      sequentialQuestions ? 3 : prior ? 2 : idleOnly ? 2 : 5,
    );
    assert.equal(
      report.questionPosts.length,
      sequentialQuestions ? 2 : prior ? 1 : idleOnly ? 1 : 4,
    );
    assert.equal(
      report.restartObservations.length,
      lateCallback || staleConflict || unknownAfterWrite ? 0 : 1,
    );
  }
  if (replacementMode && !preflightOnly) {
    assert(report.replacementNative && report.replacementCallbacks.length === 2);
    assert(report.newerPostHeld?.notForwardedAtOldRelease && report.newerPostHeld.forwarded);
    assert.equal(report.replacementCallbacks[0].controlledFailure, replacementMode === 'failure');
    for (const c of report.replacementCallbacks) {
      assert(c.deliveredAt);
      if (c.controlledFailure)
        assert(
          replacementMode === 'failure' &&
            c.ordinal === 0 &&
            c.originalStatus === 200 &&
            c.deliveredStatus === 503,
        );
      else assert(c.originalSha256 === c.deliveredSha256 && c.deliveredStatus === 200);
    }
  }
  if (lateCallback && !replacementMode && !preflightOnly)
    assert(
      report.callbackNative &&
        report.callbackResponseHeld.sha256 === report.callbackResponseDelivered.sha256,
    );
  if (staleConflict && !preflightOnly)
    assert(
      report.staleQuestionNative?.draftProtected &&
        report.staleQuestionAttempts.length === 1 &&
        report.staleQuestionAttempts[0].actualHostStatus === 409 &&
        report.externalQuestionDecision?.outcome === 'accepted',
    );
  if (coldHostRestart && !preflightOnly)
    assert(report.coldNative && report.coldRestart?.oldExited && report.hostProcesses.length === 2);
  if (unknownAfterWrite && !preflightOnly)
    assert(
      report.unknownNative &&
        report.unknownRecoveryNative &&
        report.unknownWritten.actualHostStatus === 200 &&
        report.unknownWritten.deliveredStatus === 503 &&
        report.questionPosts.length === 1,
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
  // Count accepted inputs even if an assertion later fails; never infer a safe replay.
  if (report.threadId && service) {
    try {
      const observed = await owner(),
        turns = desktopTurns(observed);
      for (const [index, sent] of submitted) {
        const clientId = index === 0 ? 'first-' + sent.operationId : sent.operationId;
        const matched = turns.filter((t) =>
          t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
        );
        if (matched.length === 1 && !report.consumedOriginalInputs.some((r) => r.index === index))
          report.consumedOriginalInputs.push({
            index,
            threadId: report.threadId,
            operationId: sent.operationId,
            clientId,
            turnId: matched[0].turnId,
            recoveredReadOnly: true,
          });
      }
      const replies = repliesIn(turns.flatMap((t) => t.items ?? []));
      const accepted = new Set(replies.map((r) => r.reply.questionItemId));
      report.acceptedQuestionInputIdsReadOnly = [...accepted];
      tally(report.consumedOriginalInputs.length + accepted.size);
      await writeFile(join(root, 'final-original-owner.json'), JSON.stringify(observed, null, 2), {
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
          'files/acceptance/P4-question-model/' + nonce + '/' + fixture.evidenceRun,
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
  if ((report.rejectedPosts.length || report.rejectedReads.length) && report.status === 'passed') {
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
