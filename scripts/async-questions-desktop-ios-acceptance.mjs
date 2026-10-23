import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, cp, readdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import {
  desktopQuestions,
  desktopQuestionAnswers,
  parseDesktopQuestionReply,
} from '../packages/codex-adapter/dist/desktop-questions.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { NotificationObserver } from '../packages/notification-runtime/dist/index.js';

const device = process.env.IOS_SIMULATOR_ID;
const prestartTask = process.env.ASYNC_ACCEPTANCE_PRESTART_TASK === '1';
assert(device, 'Use a dedicated simulator');
const executable = process.env.CODEX_EXECUTABLE;
assert(executable, 'Set the installed official CLI path');
const nonce = randomUUID().slice(0, 8);
const directory = resolve('.local/async-questions-dev', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const nativeDirectory = join(directory, 'native-source');
await cp(resolve('apps/ios'), nativeDirectory, { recursive: true });
const sourceHashes = {};
for (const name of await readdir(join(nativeDirectory, 'Sources')))
  if (name.endsWith('.swift'))
    sourceHashes[name] = createHash('sha256')
      .update(await readFile(join(nativeDirectory, 'Sources', name)))
      .digest('hex');
const report = {
  kind: 'real-desktop-native-async-questions',
  nonce,
  device,
  status: 'running',
  adapterSha256: preparedAdapterDigest(),
  nativeSourceHashes: sourceHashes,
  nativeUITestSha256: createHash('sha256')
    .update(await readFile(join(nativeDirectory, 'Tests/CodexManageUITests.swift')))
    .digest('hex'),
  observations: [],
  questionNotifications: [],
  gates: {},
  desktopQuestionWindow: {
    status: 'not-verified',
    reason:
      'Owner acceptance and native phone dismissal do not verify the desktop question window.',
  },
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const wait = async (probe, timeout = 30000) => {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 250));
  }
  throw Error('Acceptance timed out');
};
const preparer = new DesktopThreadPreparer(executable, homedir() + '/.codex');
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let service, server, child, observer;
try {
  report.threadId =
    process.env.ASYNC_ACCEPTANCE_THREAD_ID ??
    (await preparer.prepare(
      { cwd: process.cwd(), projectId: null, title: '异步提问验收 ' + nonce },
      {
        starting(pid) {
          report.preparationPid = pid;
        },
        identified(id) {
          report.preparedThreadId = id;
        },
        materialized() {
          report.gates.zeroTurnMetadata = true;
        },
        exited(code) {
          report.preparationExit = code;
        },
      },
    ));
  if (!process.env.ASYNC_ACCEPTANCE_THREAD_ID) assert.equal(report.preparationExit, 0);
  await openOriginalDesktopThread(report.threadId);
  await ipc.connect();
  await wait(async () => {
    const state = await ipc.follow(report.threadId, 1000, true);
    return state && ipc.hasOwner(report.threadId);
  });
  assert.equal(desktopTurns(ipc.state(report.threadId)).length, 0);
  report.gates.zeroTurnOwner = true;
  report.reusedPreparedThread = Boolean(process.env.ASYNC_ACCEPTANCE_THREAD_ID);
  report.desktopVersion = execFileSync(
    '/usr/libexec/PlistBuddy',
    ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
    { encoding: 'utf8' },
  ).trim();
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: join(directory, 'host'),
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: '异步问题 DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = await startRuntimeServer();
  const notificationObserver = new NotificationObserver(service.session.notificationTarget());
  report.notificationObserverSha256 = createHash('sha256')
    .update(await readFile('packages/notification-runtime/dist/observer.js'))
    .digest('hex');
  const token = (await readFile(join(directory, 'host/access-token'), 'utf8')).trim();
  const history = await service.session.open(report.threadId);
  assert.equal(history.runtime.canControl, true);
  const catalog = await service.session.models();
  const configuredModel = history.runtime.generationSettings?.model;
  const model =
    catalog.models.find((entry) => entry.id === configuredModel) ??
    catalog.models.find((entry) => entry.isDefault);
  assert(model, 'Keep the original/default model');
  const effortIndex = model.supportedEfforts.indexOf('xhigh');
  assert(effortIndex >= 0 && model.supportedEfforts.length > 1);
  const effortPosition = effortIndex / (model.supportedEfforts.length - 1);
  report.generationSetup = {
    model: model.id,
    supportedEfforts: model.supportedEfforts,
    effortPosition,
  };
  const fixture = {
    endpoint: 'http://127.0.0.1:' + service.server.address().port,
    token,
    hostId: service.session.hostId,
    threadId: report.threadId,
    threadTitle: history.thread.title,
    nonce,
    effortPosition,
    verifyQuestionNotifications: process.env.QUESTION_NOTIFICATIONS_ACCEPTANCE === '1',
    asyncQuestionTaskPrestarted: prestartTask,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  if (prestartTask) {
    // A prepared zero-turn owner can become unavailable while the native runner
    // builds. Submit one immutable task through the product Host while verified;
    // the native app still performs all question answers and the follow-up task.
    const clientMessageId = 'async-sidebar-task-' + nonce;
    report.initialTask = { clientMessageId, submittedBy: 'product-host', state: 'preparing' };
    await save();
    const receipt = await service.session.submit({
      hostId: service.session.hostId,
      threadId: report.threadId,
      clientMessageId,
      text: `真实异步问题专项验收。必须调用 request_user_input_async 一次提出三个问题：第一个标题为请选择交付格式，选项为简洁版和详细版；第二个标题为请输入验收暗号，不提供选项；第三个标题为可选的发布渠道，选项为测试渠道和正式渠道。提问后不要等待回答，立即调用 exec_command 执行 printf 'ASYNC_CONTINUE_${nonce}\\n'; sleep 300。独立工作完成后再读取异步回答；收到手机选择简洁版以及暗号 PHONE_${nonce} 后仅回复 ASYNC_DONE_${nonce}。第三题可以跳过，不要求回答；不要调用同步 request_user_input，不要修改项目文件，不要创建其他聊天。`,
      attachmentIds: [],
      mode: 'next-turn',
      model: model.id,
      effort: 'xhigh',
    });
    report.initialTask.state = receipt.state;
    await save();
    assert(
      ['submitted', 'completed'].includes(receipt.state),
      'Initial task needs a confirmed receipt; never replay an unknown input',
    );
  }
  server = createServer((req, res) => {
    if (
      req.method !== 'GET' ||
      !['/', '/status'].includes(req.url) ||
      req.headers.origin ||
      !['127.0.0.1:8895', 'localhost:8895'].includes(req.headers.host)
    ) {
      res.writeHead(403);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(
      JSON.stringify(
        req.url === '/status'
          ? { continuedWhileUnanswered: report.gates.continuedWhileUnanswered === true }
          : fixture,
      ),
    );
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8895, '127.0.0.1', done);
  });
  observer = setInterval(() => {
    const notificationObservation = service.session.notificationObservation(report.threadId);
    for (const event of notificationObserver.observe(notificationObservation).events) {
      if (event.kind !== 'question') continue;
      if (!report.questionNotifications.some((value) => value.id === event.id))
        report.questionNotifications.push(event);
      if (notificationObservation.outcome === 'completed' && event.taskSummary === '结束后继续对话')
        report.gates.completedQuestionNoticeRetained = true;
    }
    const state = ipc.state(report.threadId);
    const turns = desktopTurns(state ?? {}),
      latest = turns.at(-1);
    if (!latest) return;
    const items = latest.items ?? [];
    const questions = desktopQuestions(items);
    const command = items.find(
      (item) =>
        item.type === 'commandExecution' && item.command.includes('ASYNC_CONTINUE_' + nonce),
    );
    const answers = items.flatMap((item) =>
      parseDesktopQuestionReply((item.content ?? item.input ?? [])[0]?.text ?? ''),
    );
    const mobileSnapshot = service.session.history(report.threadId);
    const observation = {
      turnId: latest.turnId,
      status: latest.status,
      questions: questions.length,
      answers: answers.length,
      commandStatus: command?.status ?? null,
      hasOwner: ipc.hasOwner(report.threadId),
      mobileState: mobileSnapshot.runtime.state,
      pendingAsyncQuestionCount: mobileSnapshot.thread.pendingAsyncQuestionCount,
    };
    if (JSON.stringify(observation) !== JSON.stringify(report.observations.at(-1))) {
      report.observations.push(observation);
      void save();
    }
    if (questions.length && command && answers.length === 0 && latest.status === 'inProgress')
      report.gates.continuedWhileUnanswered = true;
  }, 250);
  report.resultBundle = join(directory, 'result.xcresult');
  await save();
  console.log(JSON.stringify({ directory, threadId: report.threadId, device }));
  child = spawn(
    'xcodebuild',
    [
      '-project',
      join(nativeDirectory, 'CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      join(directory, 'build'),
      '-resultBundlePath',
      report.resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopAsyncQuestions',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'ONLY_ACTIVE_ARCH=YES',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replace(
          /Type '.*' into "client-token" SecureTextField/g,
          'Type [REDACTED] into SecureTextField',
        );
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\s*Executed |^\*\* TEST (SUCCEEDED|FAILED)/.test(safe))
        console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  await new Promise((done) => log.end(done));
  clearInterval(observer);
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        report.resultBundle,
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  Object.assign(report, {
    passedTests: summary.passedTests,
    failedTests: summary.failedTests,
    skippedTests: summary.skippedTests,
  });
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  report.gates.singleQuestionSheetPagingAndDrafts = true;
  assert(
    report.observations.some(
      (row) => row.mobileState === 'running' && row.pendingAsyncQuestionCount === 3,
    ),
  );
  assert(
    report.observations.some(
      (row) => row.mobileState === 'idle' && row.pendingAsyncQuestionCount === 1,
    ),
  );
  assert(report.observations.some((row) => row.pendingAsyncQuestionCount === 0));
  report.gates.nativeSidebarAsyncQuestionStatus = true;
  const appDirectory = join(
    directory,
    'build/Build/Products/Debug-iphonesimulator/CodexManage.app',
  );
  const appExecutable = execFileSync(
    '/usr/libexec/PlistBuddy',
    ['-c', 'Print :CFBundleExecutable', join(appDirectory, 'Info.plist')],
    { encoding: 'utf8' },
  ).trim();
  report.nativeAppSha256 = createHash('sha256')
    .update(await readFile(join(appDirectory, appExecutable)))
    .digest('hex');
  const state = await ipc.follow(report.threadId, 1000, true);
  await writeFile(join(directory, 'independent-owner.json'), JSON.stringify(state, null, 2), {
    mode: 0o600,
  });
  const turns = desktopTurns(state),
    items = turns.flatMap((turn) => turn.items ?? []);
  report.turnSettings = turns.map((turn) => ({
    model: turn.params?.collaborationMode?.settings?.model,
    reasoningEffort: turn.params?.collaborationMode?.settings?.reasoning_effort,
    status: turn.status,
  }));
  for (const settings of report.turnSettings) assert.equal(settings.reasoningEffort, 'xhigh');
  report.model = state.latestModel;
  report.reasoningEffort = state.latestReasoningEffort;
  assert.equal(report.reasoningEffort, 'xhigh', 'Verify actual owner effort, not the prompt');
  const questions = desktopQuestions(items);
  assert.equal(questions.length, 4);
  for (const question of questions) {
    const notice = report.questionNotifications.find((event) => event.requestId === question.id);
    assert(notice, 'Each original-owner question must have its own notice');
    assert.equal(
      notice.taskSummary,
      question.title,
      'Original task input must not replace the actual question',
    );
  }
  assert(report.gates.completedQuestionNoticeRetained);
  report.gates.specificQuestionNotifications = true;
  if (fixture.verifyQuestionNotifications) report.gates.nativeSystemQuestionPreview = true;
  const users = items.filter(
    (item) =>
      item.type === 'userMessage' ||
      (item.type === 'steeringUserMessage' && item.status === 'accepted'),
  );
  const replies = users.flatMap((item) =>
    parseDesktopQuestionReply((item.content ?? item.input)?.[0]?.text ?? ''),
  );
  assert.equal(replies.length, 3);
  assert.equal(new Set(replies.map((reply) => reply.questionItemId)).size, 3);
  const acceptedQuestions = desktopQuestionAnswers(items);
  assert(replies.every((reply) => acceptedQuestions.has(reply.questionItemId)));
  report.gates.ownerAcceptedQuestionIds = true;
  assert(replies.some((reply) => reply.answer === '简洁版'));
  assert(replies.some((reply) => reply.answer === 'PHONE_' + nonce));
  assert(replies.some((reply) => reply.answer === '继续验收'));
  assert(!replies.some((reply) => reply.question === '可选的发布渠道'));
  assert(items.some((item) => item.type === 'agentMessage' && item.text === 'ASYNC_DONE_' + nonce));
  assert(
    items.some((item) => item.type === 'agentMessage' && item.text === 'ASYNC_FOLLOWUP_' + nonce),
  );
  assert.equal(
    turns.length,
    3,
    'Active answers stay in the original turn; completed answer starts one follow-up',
  );
  assert.equal(turns.at(-1).status, 'completed');
  report.questionCount = questions.length;
  report.acceptedReplyCount = replies.length;
  report.turnCount = turns.length;
  report.acceptedInputCount = users.length;
  assert.equal(state.requests.length, 0);
  assert(report.gates.continuedWhileUnanswered);
  report.gates.independentOwner = true;
  report.gates.optionsFreeformSkip = true;
  report.gates.sameTurnAndFollowup = true;
  const persisted = JSON.parse(await readFile(join(directory, 'host/runtime.json'), 'utf8'));
  assert.equal(Object.keys(persisted.asyncQuestionActions).length, 4);
  assert(Object.values(persisted.asyncQuestionActions).every((action) => action.resolved === true));
  report.gates.durableQuestionConfirmation = true;
  assert.equal(
    Object.values(persisted.asyncQuestionActions).filter((action) => action.decision === 'skip')
      .length,
    1,
  );
  await new Promise((done) => {
    service.server.once('close', done);
    service.close();
  });
  service = await startRuntimeServer();
  const restored = await service.session.open(report.threadId);
  assert.equal(restored.runtime.approvals.length, 0);
  assert.equal(restored.thread.pendingAsyncQuestionCount, 0);
  assert.equal(restored.runtime.turnId, null);
  assert.equal(
    desktopTurns(await ipc.follow(report.threadId, 1000, true))
      .flatMap((turn) => turn.items ?? [])
      .filter(
        (item) =>
          item.type === 'userMessage' ||
          (item.type === 'steeringUserMessage' && item.status === 'accepted'),
      ).length,
    users.length,
  );
  report.gates.hostRestartNoReplay = true;
  const screenshotDirectory = join(directory, 'screenshots');
  await mkdir(screenshotDirectory);
  execFileSync(
    'xcrun',
    [
      'xcresulttool',
      'export',
      'attachments',
      '--path',
      report.resultBundle,
      '--output-path',
      screenshotDirectory,
    ],
    { stdio: 'ignore' },
  );
  report.status = 'passed';
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      directory,
      gates: report.gates,
      passedTests: report.passedTests,
    }),
  );
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  await save();
  throw error;
} finally {
  clearInterval(observer);
  child?.kill('SIGTERM');
  server?.close();
  ipc.close();
  preparer.close();
  service?.close();
}
