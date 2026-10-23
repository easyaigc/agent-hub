import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createServer, request as httpRequest } from 'node:http';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, open, rename } from 'node:fs/promises';
import { resolve, join, basename } from 'node:path';
import { homedir } from 'node:os';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { startPushServer } from '../apps/relay/dist/push-server.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';

// Physical-only driver: fixed fixture setup/verification, never a generic remote RPC endpoint.
const directory = resolve(
  process.env.PHYSICAL_ACCEPTANCE_DIRECTORY ?? '.local/physical-features/' + randomUUID(),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const file = join(directory, 'report.json');
let report;
try {
  report = JSON.parse(await readFile(file, 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
report ??= {
  version: 1,
  kind: 'physical-integrated-features',
  nonce: randomUUID().slice(0, 8),
  hostId: randomUUID(),
  projects: [],
  emptyThreads: [],
  verifications: {},
  notificationSubmissions: [],
};
report.originalHostId = process.env.PHYSICAL_ORIGINAL_HOST_ID ?? report.originalHostId;
assert(
  /^[a-f0-9-]{36}$/.test(report.originalHostId ?? ''),
  'Explicit original local Host identity required',
);
const label = 'PHONE_DEV_' + report.nonce;
report.label = label;
report.prompt =
  label +
  '：只运行一条 printf 输出 TOOL_' +
  report.nonce +
  '，然后只答 DONE_' +
  report.nonce +
  '。不改文件。';
const atomic = async (path, value) => {
  const temp = path + '.' + randomUUID() + '.tmp',
    fd = await open(temp, 'wx', 0o600);
  try {
    await fd.writeFile(JSON.stringify(value, null, 2));
    await fd.sync();
  } finally {
    await fd.close();
  }
  await rename(temp, path);
  const parent = await open(resolve(path, '..'), 'r');
  try {
    await parent.sync();
  } finally {
    await parent.close();
  }
};
const save = () => atomic(file, report);
await save();
const controlFile = join(directory, 'control-token');
let controlToken;
try {
  controlToken = (await readFile(controlFile, 'utf8')).trim();
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  controlToken = randomBytes(32).toString('base64url');
  await writeFile(controlFile, controlToken, { mode: 0o600 });
}
const baseURL = process.env.PHYSICAL_DEV_BASE_URL,
  relayURL = process.env.PHYSICAL_DEV_RELAY_URL;
assert(
  baseURL?.startsWith('https://') && relayURL?.startsWith('https://'),
  'Provide the task-owned private HTTPS routes',
);
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const home = process.env.CODEX_HOME ?? join(homedir(), '.codex');
const stateDirectory = join(directory, 'host');
await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
try {
  await readFile(join(stateDirectory, 'runtime.json'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  await atomic(join(stateDirectory, 'runtime.json'), {
    hostId: report.hostId,
    projects: [],
    threads: [],
    attachments: [],
    receipts: [],
    creations: {},
  });
}
const publisherFile = join(directory, 'publisher-token');
let publisherToken;
try {
  publisherToken = (await readFile(publisherFile, 'utf8')).trim();
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  publisherToken = randomBytes(32).toString('base64url');
  await writeFile(publisherFile, publisherToken, { mode: 0o600 });
}
const bridge = join(directory, 'bridge.json');
await atomic(bridge, { hostId: report.hostId });
const keys = {};
for (const environment of ['development', 'production']) {
  const path =
    process.env[
      environment === 'development' ? 'PHYSICAL_APNS_SANDBOX_KEY' : 'PHYSICAL_APNS_PRODUCTION_KEY'
    ];
  if (path) {
    const keyId = basename(path).match(
      /(?:APNs-(?:sandbox|production)-|AuthKey_)([A-Z0-9]+)\.p8$/,
    )?.[1];
    assert(keyId);
    keys[environment] = { keyPath: resolve(path), keyId };
  }
}
assert(
  keys.development && process.env.APPLE_TEAM_ID,
  'Physical development APNs signing must be configured',
);
const push = await startPushServer(
  {
    statePath: join(directory, 'push.json'),
    topic: 'com.easyai.agenthub',
    teamId: process.env.APPLE_TEAM_ID,
    keys,
    hosts: [{ hostId: report.hostId, token: publisherToken }],
  },
  { address: '127.0.0.1', port: Number(process.env.PHYSICAL_RELAY_PORT ?? 18989) },
);
const owned = new Set(
  [
    report.loadedThreadId,
    ...report.emptyThreads.map((t) => t.id),
    ...(report.lifecycleThreads ?? []).map((t) => t.id),
  ].filter(Boolean),
);
// Scope only the test process's delivery observations; original runtime facts are unchanged.
// No unrelated desktop task may produce a DEV push on the user's phone.
const observations = RuntimeSession.prototype.notificationObservations;
const subscribe = RuntimeSession.prototype.onNotificationObservation;
RuntimeSession.prototype.notificationObservations = function () {
  return observations.call(this).filter((v) => owned.has(v.threadId));
};
RuntimeSession.prototype.onNotificationObservation = function (listener) {
  return subscribe.call(this, (value) => {
    if (owned.has(value.threadId)) listener(value);
  });
};
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE: executable,
  HOST_AGENT_STATE_DIR: stateDirectory,
  HOST_AGENT_ROOT: directory,
  HOST_AGENT_NAME: label,
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_DISCOVERY: '0',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
  HOST_NOTIFICATION_RELAY_URL: relayURL,
  HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: publisherFile,
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const host = await startRuntimeServer();
assert.equal(host.session.hostId, report.hostId);
const hostToken = (await readFile(join(stateDirectory, 'access-token'), 'utf8')).trim();
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  report.hostId,
  'Physical independent witness',
);
const preparer = new DesktopThreadPreparer(executable, home);
await witness.connect();
const catalog = async (method, params) => {
  let cursor,
    data = [],
    seen = new Set();
  do {
    const page = await witness.reader.read(method, { ...params, ...(cursor ? { cursor } : {}) });
    data.push(...page.data);
    cursor = page.nextCursor;
    if (cursor) {
      assert(!seen.has(cursor));
      seen.add(cursor);
    }
  } while (cursor);
  return data;
};
const threads = async (archived) =>
  catalog('thread/list', { limit: 100, modelProviders: [], useStateDbOnly: true, archived });
const projects = async () => catalog('project/list', { limit: 100 });
// Project creation is fixture metadata only, through a fixed official whitelist.
const createProject = async (params) => {
  const child = spawn(executable, ['app-server', '--stdio'], {
    env: { ...process.env, CODEX_HOME: home },
    stdio: 'pipe',
  });
  child.stderr.resume();
  let next = 0;
  const pending = new Map();
  const ended = new Promise((done) => child.once('close', done));
  createInterface({ input: child.stdout }).on('line', (line) => {
    try {
      const row = JSON.parse(line),
        p = pending.get(row.id);
      if (!p) return;
      pending.delete(row.id);
      clearTimeout(p.timer);
      if (row.error) p.reject(Error('Official project setup rejected'));
      else p.resolve(row.result);
    } catch {
      /* Ignore non-JSON diagnostic lines. */
    }
  });
  const rpc = (method, data) => {
    assert(['initialize', 'project/create'].includes(method));
    const id = ++next;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(Error('Metadata setup result unknown; do not replay'));
      }, 20000);
      pending.set(id, { resolve, reject, timer });
      child.stdin.write(JSON.stringify({ id, method, params: data }) + '\n');
    });
  };
  try {
    await rpc('initialize', {
      clientInfo: { name: 'physical_feature_fixture', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    });
    child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
    return await rpc('project/create', params);
  } finally {
    child.stdin.end();
    await ended;
  }
};
try {
  for (let i = 0; i < 2; i++) {
    if (report.projects[i]?.id) continue;
    assert(!report.projects[i], 'Unknown project setup intent must first be reconciled');
    const root = join(directory, 'project-' + (i + 1));
    await mkdir(root, { mode: 0o700 });
    const contents = label + '_KEEP_' + i;
    await writeFile(join(root, 'keep.txt'), contents);
    const entry = {
      name: label + '_PROJECT_' + (i + 1),
      root,
      keepSha256: createHash('sha256').update(contents).digest('hex'),
      idempotencyKey: randomUUID(),
    };
    report.projects.push(entry);
    await save();
    const result = await createProject({
      name: entry.name,
      roots: [{ path: root }],
      idempotencyKey: entry.idempotencyKey,
    });
    assert(result.project?.id);
    entry.id = result.project.id;
    await save();
  }
  for (let i = 0; i < 2; i++) {
    if (report.emptyThreads[i]?.ready) continue;
    assert(!report.emptyThreads[i], 'Unknown preparation intent must first be reconciled');
    const entry = { title: label + '_EMPTY_' + (i + 1), preparation: [] };
    report.emptyThreads.push(entry);
    await save();
    entry.id = await preparer.prepare(
      { cwd: report.projects[0].root, projectId: report.projects[0].id, title: entry.title },
      {
        starting(pid) {
          entry.preparation.push({ stage: 'starting', pid });
        },
        identified(id) {
          entry.id = id;
        },
        materialized() {
          entry.preparation.push({ stage: 'zero-turn-materialized' });
        },
        exited(code) {
          entry.preparation.push({ stage: 'exited', code });
        },
      },
    );
    entry.ready = true;
    owned.add(entry.id);
    await save();
  }
  if (process.env.PHYSICAL_PREPARE_UNLOADED === '1') {
    assert(report.loadedThreadId && report.verifications.model?.firstTurnId);
    report.lifecycleThreads ??= [];
    for (let i = 0; i < 3; i++) {
      if (report.lifecycleThreads[i]?.ready) continue;
      assert(!report.lifecycleThreads[i], 'Reconcile an unknown fork setup; never replay');
      const entry = {
        title: label + '_UNLOADED_' + (i + 1),
        sourceThreadId: report.loadedThreadId,
        sourceTurnId: report.verifications.model.firstTurnId,
        preparation: [],
      };
      report.lifecycleThreads.push(entry);
      await save();
      try {
        entry.id = await preparer.fork(
          {
            sourceThreadId: entry.sourceThreadId,
            turnId: entry.sourceTurnId,
            cwd: report.projects[0].root,
            title: entry.title,
            model: 'gpt-6.1-sol',
            effort: 'xhigh',
          },
          {
            starting(pid) {
              entry.preparation.push({ stage: 'starting', pid });
            },
            identified(id) {
              entry.id = id;
            },
            materialized() {
              entry.preparation.push({ stage: 'copied-completed-prefix-without-execution' });
            },
            exited(code) {
              entry.preparation.push({ stage: 'exited', code });
            },
          },
        );
        const value = await witness.reader.read('thread/read', {
          threadId: entry.id,
          includeTurns: true,
        });
        assert.equal(value.thread.forkedFromId, entry.sourceThreadId);
        entry.copiedTurnCount = value.thread.turns.length;
        assert.equal(entry.copiedTurnCount, 1);
        assert.equal(value.thread.turns[0].status, 'completed');
        const listed = await threads(false);
        assert(
          listed.some((t) => t.id === entry.id),
          'Unloaded history must exist in the official catalog',
        );
        entry.ready = true;
        owned.add(entry.id);
        await save();
      } catch (error) {
        await save();
        throw error;
      }
    }
  }
  report.environment = {
    node: process.version,
    desktopVersion: witness.version,
    phone: 'physical-device-selected-by-caller',
    actualAPNs: true,
    deliveryScope:
      'Only immutable fixture IDs; unrelated observations filtered in this driver process',
  };
  await save();
  const verify = async (name) => {
    const active = await threads(false),
      archived = await threads(true);
    let proof;
    const lifecycle = report.lifecycleThreads ?? report.emptyThreads;
    const a = lifecycle[0].id,
      b = lifecycle[1].id;
    if (name === 'model') {
      const creations = Object.values(
        JSON.parse(
          await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8'),
        ),
      ).filter((v) => v.text === report.prompt);
      assert.equal(creations.length, 1);
      assert.equal(creations[0].phase, 'confirmed');
      const id = creations[0].threadId;
      assert(id);
      if (report.loadedThreadId) assert.equal(id, report.loadedThreadId);
      report.loadedThreadId = id;
      owned.add(id);
      const owner = await witness.ipc.follow(id, 5000, true);
      assert(owner?.hostId === 'local');
      const all = desktopTurns(await witness.ipc.completeHistory(id));
      const turn = all.find((t) =>
        (t.items ?? []).some((v) => v.type === 'userMessage' && JSON.stringify(v).includes(label)),
      );
      assert(turn?.status === 'completed');
      const commands = turn.items.filter((v) => v.type === 'commandExecution');
      assert.equal(commands.length, 1);
      assert.equal(commands[0].exitCode, 0);
      assert(commands[0].aggregatedOutput.includes('TOOL_' + report.nonce));
      assert.equal(owner.latestReasoningEffort, 'xhigh');
      proof = {
        threadId: id,
        firstTurnId: turn.turnId,
        firstTurnUserInputs: turn.items.filter((v) => v.type === 'userMessage').length,
        toolId: commands[0].id,
        effort: owner.latestReasoningEffort,
      };
      assert.equal(proof.firstTurnUserInputs, 1);
    } else if (name === 'pin' || name === 'unpin') {
      const current = active.find((v) => v.id === report.loadedThreadId);
      assert(current);
      if (name === 'pin') assert.equal(current.section?.id, '01984de2-8f74-7c91-a3b2-5c5e937cf318');
      else assert.equal(current.section ?? null, null);
      proof = { threadId: current.id, section: current.section ?? null };
    } else if (name === 'archive' || name === 'bulk-archive') {
      const ids = name === 'archive' ? [a] : lifecycle.slice(1).map((v) => v.id);
      assert(
        ids.every((id) => archived.some((t) => t.id === id) && !active.some((t) => t.id === id)),
      );
      proof = { threadIds: ids, archived: true };
    } else if (name === 'restored' || name === 'delete-cancelled' || name === 'bulk-restored') {
      const id = name === 'bulk-restored' ? b : a;
      assert(active.some((t) => t.id === id));
      assert(!archived.some((t) => t.id === id));
      proof = { threadId: id, active: true };
    } else if (name === 'deleted') {
      assert(![...active, ...archived].some((t) => t.id === a));
      proof = { threadId: a, deleted: true };
    } else if (name === 'projects-deleted') {
      const list = await projects();
      assert(report.projects.every((p) => !list.some((v) => v.id === p.id)));
      for (const p of report.projects)
        assert.equal(
          createHash('sha256')
            .update(await readFile(join(p.root, 'keep.txt')))
            .digest('hex'),
          p.keepSha256,
        );
      assert([...active, ...archived].some((v) => v.id === report.loadedThreadId));
      proof = {
        projectIds: report.projects.map((p) => p.id),
        filesPreserved: true,
        modelThreadPreserved: report.loadedThreadId,
      };
    } else if (name === 'apns-ready') {
      const state = JSON.parse(await readFile(join(stateDirectory, 'notifications.json'), 'utf8'));
      const devices = Object.values(state.devices);
      const device = devices.find((d) => d.bindingId && !d.error && d.registration?.deviceToken);
      assert(device, 'The real phone APNs registration is not ready');
      proof = {
        bindingId: device.bindingId,
        environment: device.registration.environment,
        preferences: device.registration.preferences,
      };
    } else if (name === 'approval') {
      const observation = host.session.notificationObservation(report.loadedThreadId);
      const approval = host.session
        .history(report.loadedThreadId)
        .runtime.approvals?.find((a) => a.kind !== 'question');
      assert(approval);
      const state = JSON.parse(await readFile(join(directory, 'push.json'), 'utf8'));
      const hostNotifications = JSON.parse(
        await readFile(join(stateDirectory, 'notifications.json'), 'utf8'),
      );
      const events = hostNotifications.entries.filter(
        (v) =>
          v.event.kind === 'approval' &&
          v.event.threadId === report.loadedThreadId &&
          v.event.runId === approval.turnId,
      );
      const ids = new Set(events.map((v) => v.event.id));
      const accepted = Object.values(state.deliveries ?? {}).filter(
        (d) => d.state === 'accepted' && ids.has(d.envelope.eventId),
      );
      assert(accepted.length);
      proof = {
        threadId: report.loadedThreadId,
        title: observation.title,
        summary: approval.taskSummary ?? observation.taskSummary,
        requestId: approval.id,
        turnId: approval.turnId,
        acceptedPushes: accepted.length,
        decisions: approval.decisions,
      };
    } else if (name === 'notification-resolved') {
      const owner = await witness.ipc.follow(report.loadedThreadId, 5000, true);
      assert(owner?.threadRuntimeStatus?.type === 'idle');
      const all = desktopTurns(await witness.ipc.completeHistory(report.loadedThreadId));
      const intent = report.notificationSubmissions[0];
      assert(intent);
      const turn = all.find((t) =>
        (t.items ?? []).some(
          (v) =>
            v.type === 'userMessage' &&
            JSON.stringify(v).includes(JSON.stringify(intent.text).slice(1, -1)),
        ),
      );
      const offered = report.verifications.approval?.decisions ?? [];
      const stopped = turn?.status === 'interrupted';
      assert(turn?.status === 'completed' || (stopped && offered.includes('cancel')));
      assert.equal(turn.items.filter((v) => v.type === 'userMessage').length, 1);
      const completedMarker = turn.items.some(
        (v) =>
          v.type === 'agentMessage' &&
          JSON.stringify(v).includes('PHONE_NOTIFICATION_DONE_' + report.nonce),
      );
      assert(stopped || completedMarker);
      assert.equal(all.length, 2);
      const commands = turn.items.filter((v) => v.type === 'commandExecution');
      assert.equal(
        commands.filter((v) => v.exitCode === 0).length,
        0,
        'A declined approval must not execute the command',
      );
      assert.equal(owner.latestReasoningEffort, 'xhigh');
      proof = {
        notificationTurnId: turn.turnId,
        commandStates: commands.map((v) => ({ status: v.status, exitCode: v.exitCode ?? null })),
        submittedUserInputs: 1,
        status: turn.status,
        completedMarker: completedMarker ? 'PHONE_NOTIFICATION_DONE_' + report.nonce : null,
        threadId: owner.id,
        turns: desktopTurns(owner).length,
        effort: owner.latestReasoningEffort,
      };
    } else if (['completion-notice', 'completion-notice-recheck'].includes(name)) {
      const recheck = name === 'completion-notice-recheck';
      const intent = (
        recheck ? report.completionRecheckSubmissions : report.completionNotificationSubmissions
      )?.[0];
      assert(intent);
      const owner = await witness.ipc.follow(report.loadedThreadId, 5000, true);
      assert(owner?.threadRuntimeStatus?.type === 'idle');
      const turns = desktopTurns(await witness.ipc.completeHistory(report.loadedThreadId));
      const turn = turns.find((t) =>
        (t.items ?? []).some(
          (v) =>
            v.type === 'userMessage' &&
            JSON.stringify(v).includes(JSON.stringify(intent.text).slice(1, -1)),
        ),
      );
      assert(turn?.status === 'completed');
      assert.equal(turns.length, recheck ? 4 : 3);
      assert.equal(turn.items.filter((v) => v.type === 'userMessage').length, 1);
      assert.equal(turn.items.filter((v) => v.type === 'commandExecution').length, 0);
      assert(
        turn.items.some(
          (v) =>
            v.type === 'agentMessage' &&
            JSON.stringify(v).includes(
              'PHONE_COMPLETE_' + report.nonce + (recheck ? '_RECHECK' : ''),
            ),
        ),
      );
      const effort =
        turn.requestedReasoningEffort ??
        turn.params?.collaborationMode?.settings?.reasoning_effort ??
        turn.params?.effort;
      assert.equal(effort, 'xhigh');
      const publisher = JSON.parse(
        await readFile(join(stateDirectory, 'notifications.json'), 'utf8'),
      );
      const ids = new Set(
        publisher.entries
          .filter(
            (v) =>
              v.event.kind === 'completed' &&
              v.event.threadId === report.loadedThreadId &&
              v.event.runId === turn.turnId,
          )
          .map((v) => v.event.id),
      );
      const relay = JSON.parse(await readFile(join(directory, 'push.json'), 'utf8'));
      const accepted = Object.values(relay.deliveries).filter(
        (v) => v.state === 'accepted' && ids.has(v.envelope.eventId),
      );
      assert(accepted.length);
      proof = {
        threadId: report.loadedThreadId,
        turnId: turn.turnId,
        submittedUserInputs: 1,
        commands: 0,
        effort,
        effortEvidence: 'original-owner-turn-settings',
        acceptedPushes: accepted.length,
      };
    } else if (name === 'cleanup') {
      const state = JSON.parse(await readFile(join(stateDirectory, 'notifications.json'), 'utf8'));
      assert(
        Object.values(state.devices).every(
          (v) => !v.registration.preferences.enabled && !v.bindingId,
        ),
      );
      const relay = JSON.parse(await readFile(join(directory, 'push.json'), 'utf8'));
      assert(
        Object.values(relay.bindings)
          .filter((v) => v.hostId === report.hostId)
          .every((v) => !v.enabled),
      );
      proof = { phoneDEVBindingRevoked: true };
    } else throw Error('Unknown fixed verification');
    report.verifications[name] = { ...proof, at: new Date().toISOString() };
    await save();
    return proof;
  };
  const startNotification = async () => {
    assert(report.loadedThreadId && report.verifications['apns-ready']);
    if (report.notificationSubmissions.length) return report.notificationSubmissions[0];
    const text =
      '这是 ' +
      label +
      ' 真机通知验收。仅通过 exec_command，显式 sandbox_permissions=require_escalated，请求运行 printf "PHONE_NOTIFICATION_' +
      report.nonce +
      '\\n"，审批说明写为“是否允许运行 PHONE_NOTIFICATION_' +
      report.nonce +
      ' 真机通知验收命令？”。等待该操作被拒绝后仅回复 PHONE_NOTIFICATION_DONE_' +
      report.nonce +
      '。不再次请求审批，不读写文件，不调用其他工具。';
    const params = {
      threadId: report.loadedThreadId,
      input: [{ type: 'text', text }],
      effort: 'xhigh',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'user',
      sandboxPolicy: { type: 'readOnly' },
    };
    const intent = {
      id: randomUUID(),
      threadId: report.loadedThreadId,
      text,
      params,
      fingerprint: createHash('sha256').update(JSON.stringify(params)).digest('hex'),
      phase: 'submitted',
    };
    report.notificationSubmissions.push(intent);
    await save();
    try {
      const result = await witness.call('turn/start', params);
      intent.runId = result.turn?.id ?? result.turnId;
      assert(intent.runId);
      intent.phase = 'confirmed';
    } catch {
      intent.phase = 'outcome-unknown';
    }
    await save();
    return intent;
  };
  const startCompletionNotification = async (recheck = false) => {
    if (recheck) assert(report.verifications['completion-notice']);
    assert(report.verifications['notification-resolved']);
    const key = recheck ? 'completionRecheckSubmissions' : 'completionNotificationSubmissions';
    report[key] ??= [];
    if (report[key].length) return report[key][0];
    const text =
      '专用真机完成通知' +
      (recheck ? '复验' : '验收') +
      '。只回复 PHONE_COMPLETE_' +
      report.nonce +
      (recheck ? '_RECHECK' : '') +
      '。不调用工具，不读写文件。';
    const params = {
      threadId: report.loadedThreadId,
      input: [{ type: 'text', text }],
      effort: 'xhigh',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'user',
      sandboxPolicy: { type: 'readOnly' },
    };
    const intent = {
      id: randomUUID(),
      threadId: report.loadedThreadId,
      text,
      params,
      fingerprint: createHash('sha256').update(JSON.stringify(params)).digest('hex'),
      phase: 'submitted',
    };
    report[key].push(intent);
    await save();
    try {
      const result = await witness.call('turn/start', params);
      intent.runId = result.turn?.id ?? result.turnId;
      assert(intent.runId);
      intent.phase = 'confirmed';
    } catch {
      intent.phase = 'outcome-unknown';
    }
    await save();
    return intent;
  };
  const json = (res, status, value) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  const gateway = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname.startsWith('/__physical/')) {
        assert.equal(req.headers.authorization, 'Bearer ' + controlToken);
        assert(!req.headers.origin);
        if (req.method === 'POST' && url.pathname === '/__physical/preferences') {
          const bytes = [];
          for await (const part of req) {
            bytes.push(part);
            assert(bytes.reduce((n, v) => n + v.length, 0) < 8192);
          }
          const values = JSON.parse(Buffer.concat(bytes).toString());
          const allowed = [
            'notifications-enabled',
            'notification-content-preview',
            'notification-kind-approval',
            'notification-kind-completed',
            'notification-host-' + report.originalHostId,
            'notification-host-' + report.hostId,
          ];
          assert(
            Object.entries(values).every(
              ([key, value]) => allowed.includes(key) && typeof value === 'boolean',
            ),
          );
          report.phonePreferences ??= values;
          await save();
          return json(res, 200, report.phonePreferences);
        }
        if (req.method === 'GET' && url.pathname === '/__physical/config')
          return json(res, 200, {
            connection: {
              endpoint: baseURL,
              token: hostToken,
              hostId: report.hostId,
              threadId: report.loadedThreadId ?? '',
              threadTitle: label,
              nonce: report.nonce,
              secondEndpoint: '',
              secondToken: '',
              secondHostId: '',
              delayedEndpoint: '',
            },
            prompt: report.prompt,
            projects: report.projects,
            emptyThreads: report.lifecycleThreads ?? report.emptyThreads,
            phases: Object.keys(report.verifications),
            phonePreferences: report.phonePreferences,
            originalHostId: report.originalHostId,
            label,
          });
        if (req.method === 'GET' && url.pathname.startsWith('/__physical/verify/'))
          return json(res, 200, await verify(url.pathname.slice('/__physical/verify/'.length)));
        if (
          req.method === 'POST' &&
          url.pathname === '/__physical/start-completion-notification-recheck'
        )
          return json(res, 200, await startCompletionNotification(true));
        if (req.method === 'POST' && url.pathname === '/__physical/start-completion-notification')
          return json(res, 200, await startCompletionNotification());
        if (req.method === 'POST' && url.pathname === '/__physical/start-notification')
          return json(res, 200, await startNotification());
        return json(res, 404, { error: 'No such fixed control' });
      }
      const chunks = [];
      let size = 0;
      for await (const part of req) {
        size += part.length;
        assert(size < 2 * 1024 * 1024);
        chunks.push(part);
      }
      const body = Buffer.concat(chunks);
      if (req.method === 'POST' && req.headers['content-type']?.includes('application/json')) {
        const value = JSON.parse(body.toString());
        const readonlyWrites = [
          '/v1/threads/read',
          '/v1/notifications/register',
          '/v1/notifications/preferences',
          '/v1/notifications/unregister',
        ];
        const threadWrites = [
          '/v1/desktop/open',
          '/v1/replies',
          '/v1/stop',
          '/v1/queue/cancel',
          '/v1/approvals',
          '/v1/threads/actions',
        ];
        const fixtureWrites = [
          '/v1/workspace-management',
          '/v1/desktop/creations',
          '/v1/desktop/creations/submit',
        ];
        assert(
          [...readonlyWrites, ...threadWrites, ...fixtureWrites].includes(url.pathname),
          'Write outside physical acceptance scope',
        );
        if (threadWrites.includes(url.pathname))
          assert(owned.has(value.threadId), 'Only owned acceptance chats may receive commands');
        if (url.pathname === '/v1/desktop/creations') {
          assert.equal(value.text, report.prompt);
          assert.equal(value.projectId, report.projects[0].id);
        }
        if (url.pathname === '/v1/desktop/creations/submit') {
          const intents = Object.values(
            JSON.parse(
              await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8'),
            ),
          );
          assert(
            intents.some(
              (v) =>
                v.text === report.prompt &&
                [v.id, v.clientOperationId].includes(value.id ?? value.clientOperationId),
            ),
            'Only the immutable fixture creation may be submitted',
          );
        }
        if (url.pathname === '/v1/threads/actions') assert(owned.has(value.threadId));
        if (url.pathname === '/v1/workspace-management') {
          const ids = value.ids ?? value.items?.map((v) => (typeof v === 'string' ? v : v.id));
          assert(Array.isArray(ids));
          const allowed =
            value.action === 'projects:delete' ? report.projects.map((v) => v.id) : [...owned];
          assert(ids.every((id) => allowed.includes(id)));
        }
        if (url.pathname.includes('/creations') && value.text)
          assert.equal(value.text, report.prompt);
      }
      const upstream = httpRequest(
        {
          hostname: '127.0.0.1',
          port: host.server.address().port,
          path: req.url,
          method: req.method,
          headers: { ...req.headers, host: '127.0.0.1:' + host.server.address().port },
        },
        (response) => {
          res.writeHead(response.statusCode, response.headers);
          response.pipe(res);
        },
      );
      upstream.on('error', () => {
        if (!res.headersSent) json(res, 502, { error: 'DEV Host unavailable' });
        else res.destroy();
      });
      req.on('close', () => {
        if (!req.complete) upstream.destroy();
      });
      upstream.end(body);
    } catch (error) {
      json(res, 409, { error: error.message });
    }
  });
  await new Promise((done, reject) => {
    gateway.once('error', reject);
    gateway.listen(Number(process.env.PHYSICAL_GATEWAY_PORT ?? 18988), '127.0.0.1', done);
  });
  await atomic(join(directory, 'runner-environment.json'), {
    PHYSICAL_ACCEPTANCE_URL: baseURL + '/__physical',
    PHYSICAL_ACCEPTANCE_CONTROL_TOKEN: controlToken,
  });
  console.log(
    JSON.stringify({
      ready: true,
      directory,
      label,
      baseURL,
      hostId: report.hostId,
      controlCredentials: 'runner-environment.json',
      actualAPNs: true,
    }),
  );
  await new Promise((done) => {
    process.once('SIGTERM', done);
    process.once('SIGINT', done);
  });
  gateway.closeAllConnections();
  await new Promise((done) => gateway.close(done));
} finally {
  preparer.close();
  witness.close();
  await host.close();
  await push.close();
  RuntimeSession.prototype.notificationObservations = observations;
  RuntimeSession.prototype.onNotificationObservation = subscribe;
}
