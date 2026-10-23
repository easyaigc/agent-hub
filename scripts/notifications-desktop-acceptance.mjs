import assert from 'node:assert/strict';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createWriteStream } from 'node:fs';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID, generateKeyPairSync, createHash } from 'node:crypto';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { defaultNotificationPreferences } from '../packages/protocol/dist/index.js';

// Actual original owner and model, isolated Host; no mobile chat open or execution clone.
const executable = process.env.CODEX_EXECUTABLE;
assert.ok(executable, 'Select the installed official CLI for real desktop acceptance');
const resume = process.env.NOTIFICATION_ACCEPTANCE_RESUME;
const directory = resume
  ? resolve(resume)
  : resolve('.local/task-notifications/desktop', randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_ROOT: directory,
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_NAME: 'Notification DEV',
});
delete process.env.HOST_NOTIFICATION_RELAY_URL;
delete process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE;
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
delete process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG;
const service = await startRuntimeServer();
const witness = new DesktopRuntime(
  {
    mode: 'desktop-runtime',
    executable,
    ...(process.env.CODEX_DESKTOP_WINDOWS_CONFIG
      ? { windowsDesktopConfig: resolve(process.env.CODEX_DESKTOP_WINDOWS_CONFIG) }
      : {}),
  },
  service.session.hostId,
  'Notification independent witness',
);
const preparer = new DesktopThreadPreparer(
  executable,
  process.env.CODEX_HOME ?? join(homedir(), '.codex'),
);
const report = resume
  ? JSON.parse(await readFile(join(directory, 'report.json'), 'utf8'))
  : {
      kind: 'real-desktop-host-notifications',
      adapterSha256: preparedAdapterDigest(),
      startedAt: new Date().toISOString(),
      scenarios: {},
      submissions: [],
    };
if (report.passed) throw Error('This acceptance already passed; do not submit another task');
if (report.error) {
  report.failures ??= [];
  report.failures.push({
    error: report.error,
    stack: report.errorStack,
    runtime: report.failureRuntime,
    nativeResultBundle: report.nativeResultBundle,
    nativeTestSummary: report.nativeTestSummary,
  });
}
delete report.error;
delete report.errorStack;
delete report.failureRuntime;
report.executionAdapterSha256 = preparedAdapterDigest();
const notificationDigest = createHash('sha256');
for (const file of ['index.js', 'observer.js', 'state-file.js'])
  notificationDigest.update(await readFile(join('packages/notification-runtime/dist', file)));
report.notificationRuntimeSha256 = notificationDigest.digest('hex');
report.notificationContract = JSON.parse(
  await readFile('packages/protocol/notification-contract-manifest.json', 'utf8'),
);
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const snapshotHandler = service.session.runtime.ipc.onSnapshot;
const ownerRequest = service.session.runtime.ipc.request.bind(service.session.runtime.ipc);
service.session.runtime.ipc.request = async (...values) => {
  let result;
  try {
    result = await ownerRequest(...values);
  } catch (error) {
    if (values[0] === 'thread-owner-discovery' && values[1]?.conversationId === report.threadId) {
      report.ownerChecks ??= [];
      report.ownerChecks.push({ time: Date.now(), error: error.message });
    }
    throw error;
  }
  if (values[0] === 'thread-owner-discovery' && values[1]?.conversationId === report.threadId) {
    report.ownerChecks ??= [];
    report.ownerChecks.push({
      time: Date.now(),
      owner: result.handledByClientId,
      supported: result.result?.supportsUntrustedAppInput,
    });
  }
  return result;
};
service.session.runtime.ipc.onSnapshot = (...values) => {
  if (values[0] === report.threadId) {
    report.hostSnapshotCount = (report.hostSnapshotCount ?? 0) + 1;
  }
  try {
    snapshotHandler(...values);
  } catch (error) {
    report.observationError = error.stack;
    throw error;
  }
};
await save();
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const endpoint = 'http://127.0.0.1:' + service.server.address().port;
const request = async (path, body) => {
  const response = await fetch(endpoint + path, {
    ...(body
      ? { method: 'POST', body: JSON.stringify({ hostId: service.session.hostId, ...body }) }
      : {}),
    headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
    signal: AbortSignal.timeout(35_000),
  });
  assert.equal(response.status, 200, 'Host rejected ' + path.split('?')[0]);
  return response.json();
};
const wait = async (check, name, timeout = 120_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const result = await check();
    if (result) return result;
    await new Promise((done) => setTimeout(done, 250));
  }
  throw Error('Timed out: ' + name);
};
const feed = () =>
  request(
    '/v1/notifications/events?hostId=' + service.session.hostId + '&deviceId=desktop-acceptance',
  );
try {
  await witness.connect();
  report.environment = {
    node: process.version,
    platform: process.platform,
    originalDesktopVersion: witness.version,
    mobileParticipated: false,
    actualAPNsParticipated: false,
  };
  const key = generateKeyPairSync('x25519'),
    preferences = {
      ...defaultNotificationPreferences(),
      enabled: true,
      kinds: ['approval', 'question', 'plan', 'failed', 'interrupted', 'completed'],
    };
  await request('/v1/notifications/register', {
    registration: {
      version: 1,
      deviceId: 'desktop-acceptance',
      target: service.session.notificationTarget(),
      publicKey: key.publicKey
        .export({ format: 'der', type: 'spki' })
        .subarray(-32)
        .toString('base64'),
      preferences,
    },
  });
  const threadId =
    report.threadId ??
    (await preparer.prepare(
      { cwd: directory, projectId: null, title: '任务通知独立 DEV' },
      {
        starting: (pid) => {
          report.preparationPid = pid;
        },
        identified: (id) => {
          report.threadId = id;
        },
        materialized: () => {},
        exited: (code) => {
          report.preparationExitCode = code;
        },
      },
    ));
  assert.equal(report.preparationExitCode, 0);
  await save();
  await openOriginalDesktopThread(threadId);
  await wait(async () => {
    await witness.ipc.follow(threadId, 1000, true);
    return witness.ipc.hasOwner(threadId);
  }, 'original owner');
  if (!resume) assert.equal(desktopTurns(witness.ipc.state(threadId) ?? {}).length, 0);
  const submit = async (scenario, text, extra = {}) => {
    const existing = report.submissions.find((value) => value.scenario === scenario);
    if (existing) {
      assert.ok(existing.runId, 'Unknown submission result must be reconciled, never replayed');
      return existing.runId;
    }
    report.submissions.push({ scenario, intentAt: new Date().toISOString() });
    await save();
    const result = await witness.call('turn/start', {
      threadId,
      input: [{ type: 'text', text }],
      effort: 'xhigh',
      ...extra,
    });
    const runId = result.turn?.id ?? result.turnId;
    assert.ok(runId);
    report.submissions.at(-1).runId = runId;
    await save();
    return runId;
  };
  const commandNonce = randomUUID();
  const approvalRun = await submit(
    'approval',
    "在本验收聊天测试真实命令审批。仅通过 exec_command，显式 sandbox_permissions=require_escalated，请求运行这个只输出随机码的命令：printf '%s\\n' '" +
      commandNonce +
      "'。不读写文件，不自行批准，不执行其他命令。",
    { approvalPolicy: 'on-request', sandboxPolicy: { type: 'readOnly' } },
  );
  await wait(
    () =>
      service.session
        .notificationObservations()
        .some((value) => value.threadId === threadId && value.live),
    'autonomous background discovery',
  );
  report.scenarios['desktop-background-discovery-without-mobile-open'] = {
    status: 'passed-this-run',
    threadId,
    adapterSha256: preparedAdapterDigest(),
  };
  await save();
  const priorNotice = report.scenarios['actual-model-approval-notice'];
  const nativeRequest = priorNotice
    ? { id: priorNotice.requestId }
    : await wait(
        () =>
          witness.ipc
            .state(threadId)
            ?.requests?.find(
              (value) =>
                value.method === 'item/commandExecution/requestApproval' &&
                value.params?.turnId === approvalRun,
            ),
        'real command approval',
      );
  const notice = priorNotice
    ? { id: priorNotice.eventId, requestId: priorNotice.requestId }
    : await wait(
        async () =>
          (await feed()).events.find(
            (event) =>
              event.threadId === threadId &&
              event.runId === approvalRun &&
              event.requestId === String(nativeRequest.id) &&
              event.kind === 'approval',
          ),
        'actual request notice',
      );
  if (!priorNotice) {
    assert(
      notice.threadTitle && notice.taskSummary,
      'A real actionable notice needs a conversation and summary',
    );
    assert.notEqual(
      notice.taskSummary,
      service.session.notificationObservation(threadId).taskSummary,
      'The approval description must take priority over the original task request',
    );
  }
  report.scenarios['actual-model-approval-notice'] = {
    status: 'passed-this-run',
    eventId: notice.id,
    runId: approvalRun,
    requestId: notice.requestId,
    adapterSha256: preparedAdapterDigest(),
    ...((priorNotice ?? notice).threadTitle
      ? {
          threadTitle: (priorNotice ?? notice).threadTitle,
          taskSummary: (priorNotice ?? notice).taskSummary,
        }
      : {}),
  };
  await save();
  const simulator = process.env.IOS_SIMULATOR_ID;
  if (simulator && report.scenarios['native-action-notification']?.status !== 'passed-this-run') {
    let expected = priorNotice ?? notice;
    const currentFeed = await feed();
    if (report.nativePreviewEvent && currentFeed.activeIds.includes(report.nativePreviewEvent.id)) {
      expected = report.nativePreviewEvent;
    } else if (!currentFeed.activeIds.includes(notice.id)) {
      const state = await witness.ipc.follow(threadId, 1000, true);
      assert(
        ['completed', 'failed', 'interrupted', 'cancelled'].includes(
          desktopTurns(state).at(-1)?.status,
        ),
        'A resolved approval must have a known terminal run before a separate preview task',
      );
      const previewRun = await submit(
        'native-preview-approval',
        '这是独立的原生通知展示验收。仅通过 exec_command，显式 sandbox_permissions=require_escalated，请求运行 printf "NOTICE_PREVIEW_APPROVAL\\n"，审批说明写为“是否允许运行通知展示验收命令？”。等待该操作被拒绝后结束，不再次请求审批，不读写文件，不调用其他工具，不创建其他聊天。',
        { approvalPolicy: 'on-request', sandboxPolicy: { type: 'readOnly' } },
      );
      expected = await wait(
        async () =>
          (await feed()).events.find(
            (event) =>
              event.kind === 'approval' &&
              event.threadId === threadId &&
              event.runId === previewRun,
          ),
        'separate real approval for native preview',
      );
      report.nativePreviewEvent = expected;
      report.priorApprovalResolvedBeforeNative = true;
      await save();
    }
    assert(
      expected.threadTitle && expected.taskSummary,
      'Resuming native verification requires the exact saved notice',
    );
    const nativeDirectory = join(directory, 'native-source');
    await cp(resolve('apps/ios'), nativeDirectory, {
      recursive: true,
      filter: async (source, destination) => {
        try {
          return !(await readFile(source)).equals(await readFile(destination));
        } catch {
          return true;
        } // Directories and new files are copied normally.
      },
    });
    const nativeFiles = [
      'Sources/NotificationModels.swift',
      'Sources/TaskNotifications.swift',
      'NotificationService/NotificationService.swift',
      'NotificationContent/NotificationViewController.swift',
      'Tests/CodexManageUITests.swift',
    ];
    report.nativeSourceHashes = Object.fromEntries(
      await Promise.all(
        nativeFiles.map(async (file) => [
          file,
          createHash('sha256')
            .update(await readFile(join(nativeDirectory, file)))
            .digest('hex'),
        ]),
      ),
    );
    const controls = createServer((req, res) => {
      if (
        req.method !== 'GET' ||
        req.url !== '/' ||
        req.headers.origin ||
        !['127.0.0.1:8895', 'localhost:8895'].includes(req.headers.host)
      ) {
        res.writeHead(403);
        res.end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(
        JSON.stringify({
          endpoint,
          token,
          hostId: service.session.hostId,
          threadId,
          threadTitle: expected.threadTitle,
          notificationSummary: expected.taskSummary,
          notificationKind: expected.kind === 'question' ? '待回答' : '待审批',
          reuseDeliveredNotification: process.env.NOTIFICATION_NATIVE_REUSE_DELIVERED === '1',
          nonce: randomUUID(),
          secondEndpoint: '',
          secondToken: '',
          secondHostId: '',
          delayedEndpoint: '',
        }),
      );
    });
    try {
      await new Promise((done, reject) => {
        controls.once('error', reject);
        controls.listen(8895, '127.0.0.1', done);
      });
      const runId = randomUUID();
      const resultBundle = join(directory, 'native-' + runId + '.xcresult');
      report.nativeResultBundle = resultBundle;
      await save();
      const child = spawn(
        'xcodebuild',
        [
          '-project',
          join(nativeDirectory, 'CodexManage.xcodeproj'),
          '-scheme',
          'CodexManage',
          '-destination',
          `platform=iOS Simulator,id=${simulator}`,
          '-derivedDataPath',
          join(directory, 'native-build'),
          '-resultBundlePath',
          resultBundle,
          '-parallel-testing-enabled',
          'NO',
          '-collect-test-diagnostics',
          'never',
          '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopActionNotificationPreview',
          'CODE_SIGNING_ALLOWED=YES',
          'CODE_SIGN_IDENTITY=-',
          'ONLY_ACTIVE_ARCH=YES',
          'test',
        ],
        { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
      );
      const log = createWriteStream(join(directory, 'native-' + runId + '.log'), { mode: 0o600 });
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
      const result = JSON.parse(
        execFileSync(
          'xcrun',
          [
            'xcresulttool',
            'get',
            'test-results',
            'summary',
            '--path',
            resultBundle,
            '--format',
            'json',
          ],
          { encoding: 'utf8' },
        ),
      );
      report.nativeTestSummary = {
        passed: result.passedTests,
        failed: result.failedTests,
        skipped: result.skippedTests,
      };
      await save();
      assert.equal(code, 0);
      assert.equal(result.passedTests, 1);
      assert.equal(result.failedTests, 0);
      assert.equal(result.skippedTests, 0);
      report.scenarios['native-action-notification'] = {
        status: 'passed-this-run',
        simulator,
        realAPNs: false,
      };
      report.environment.mobileParticipated = true;
    } finally {
      await new Promise((done) => controls.close(done));
    }
  }
  const owner = await witness.ipc.follow(threadId, 1000, true);
  assert.equal(owner.latestReasoningEffort, 'xhigh');
  report.actualReasoningEffort = owner.latestReasoningEffort;
  await save();
  // This is the notification click's sync/open boundary; background observation
  // intentionally does not load the transcript for every directory-listed chat.
  await service.session.open(threadId);
  if (report.nativePreviewEvent) {
    let action = 'already-resolved';
    const pending = service.session
      .history(threadId)
      .runtime.approvals.find((value) => value.turnId === report.nativePreviewEvent.runId);
    if (pending) {
      const decision =
        pending.interactionMode === 'async'
          ? 'skip'
          : pending.decisions.find((value) => ['decline', 'cancel', 'deny'].includes(value));
      assert(decision);
      await service.session.answer(threadId, pending.id, pending.turnId, decision, {});
      action = decision;
    }
    await wait(
      async () => !(await feed()).activeIds.includes(report.nativePreviewEvent.id),
      'preview action cleared without resubmission',
    );
    report.scenarios['native-preview-action-cleared'] = {
      status: 'passed-this-run',
      action,
      replayedInput: false,
    };
    await save();
  }
  const approval = service.session
    .history(threadId)
    .runtime.approvals.find(
      (value) => value.turnId === approvalRun && value.notificationRequestId === notice.requestId,
    );
  if (approval) {
    const denial = approval.decisions.find((value) =>
      ['decline', 'cancel', 'deny'].includes(value),
    );
    assert.ok(denial, 'A safe denial decision must be explicitly available');
    await service.session.answer(threadId, approval.id, approvalRun, denial, {});
  } else {
    assert.ok(
      priorNotice &&
        !witness.ipc
          .state(threadId)
          ?.requests?.some((value) => String(value.id) === notice.requestId),
      'Actual pending approval must be reconciled',
    );
  }
  await wait(
    async () => !(await feed()).activeIds.includes(notice.id),
    'resolved approval removed',
  );
  report.scenarios['resolved-real-approval-filtered'] = {
    status: 'passed-this-run',
    adapterSha256: preparedAdapterDigest(),
  };
  await save();
  await wait(
    () =>
      ['completed', 'failed', 'interrupted', 'cancelled'].includes(
        desktopTurns(witness.ipc.state(threadId) ?? {}).at(-1)?.status,
      ),
    'declined approval task ended',
  );
  const nonce = report.completedNonce ?? 'NOTIFICATION_OK_' + randomUUID().slice(0, 8);
  report.completedNonce = nonce;
  await save();
  const completedRun = await submit(
    'completed',
    '这是独立任务通知验收，不修改文件或其他聊天。仅回复 ' + nonce + '。',
  );
  await wait(
    () =>
      desktopTurns(witness.ipc.state(threadId) ?? {}).find(
        (turn) =>
          turn.turnId === completedRun &&
          turn.status === 'completed' &&
          turn.items.some((item) => item.type === 'agentMessage' && item.text?.includes(nonce)),
      ),
    'actual model completion',
  );
  const completed = await wait(
    async () =>
      (await feed()).events.find(
        (event) =>
          event.threadId === threadId && event.runId === completedRun && event.kind === 'completed',
      ),
    'completion notice',
  );
  report.scenarios['actual-model-completed-notice'] = {
    status: 'passed-this-run',
    eventId: completed.id,
    runId: completedRun,
    adapterSha256: preparedAdapterDigest(),
  };
  await save();
  report.passed = true;
} catch (error) {
  report.error = error.message;
  report.errorStack = error.stack;
  report.failureRuntime = {
    hostReady: service.session.runtime.ipc.ready,
    hostOwner: service.session.runtime.ipc.hasOwner(report.threadId),
    witnessReady: witness.ipc.ready,
    witnessOwner: witness.ipc.hasOwner(report.threadId),
  };
  report.passed = false;
  process.exitCode = 1;
} finally {
  preparer.close();
  witness.close();
  service.close();
  await save();
}
console.log(
  JSON.stringify({
    passed: report.passed,
    report: join(directory, 'report.json'),
    scenarios: report.scenarios,
  }),
);
